package org.librehu.disc.drive

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** One audio track of a CD: sectors [startLba, endLba). */
data class CdTrack(
    val number: Int,
    val startLba: Int,
    val endLba: Int,
) {
    val frames: Int get() = endLba - startLba
    val durationMs: Long get() = frames * 1000L / Cdrom.FRAMES_PER_SECOND
}

sealed interface DiscState {
    /** No USB optical drive (no `/dev/block/sr*`). */
    data object NoDrive : DiscState

    /** A drive, but its node cannot be opened: root refused or missing. */
    data class NoAccess(
        val device: String,
    ) : DiscState

    data object TrayOpen : DiscState

    data object NoDisc : DiscState

    /** Disc spinning up or being read. */
    data object Reading : DiscState

    data class AudioCd(
        val tracks: List<CdTrack>,
        /** Data tracks of an enhanced CD, not playable. */
        val dataTracks: Int,
    ) : DiscState

    data class DataDisc(
        val dvd: Boolean,
        /** Where the disc is mounted, or null when the mount failed. */
        val mountPoint: String?,
        /** A DVD-Video (VIDEO_TS folder). */
        val dvdVideo: Boolean,
        val capacity: Long,
    ) : DiscState
}

/**
 * Watches the USB optical drive (`/dev/block/srN`, kernel `sr` driver): gives the node to the app with root once per
 * plug, follows the tray and disc state, reads the table of contents of audio CDs and mounts data discs.
 */
object DiscDrive {
    private const val TAG = "LibreHU-Disc"
    private const val POLL_MS = 1500L

    /** Longest audio CD (99 min, overburnt): a longer "audio" track is the data track of a DVD. */
    private const val MAX_CD_FRAMES = 99 * 60 * Cdrom.FRAMES_PER_SECOND

    /** Mount point of data discs, in the global mount namespace. */
    const val MOUNT_POINT = "/mnt/librehu_disc"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<DiscState>(DiscState.NoDrive)
    val state: StateFlow<DiscState> = _state.asStateFlow()

    private var users = 0
    private var job: Job? = null

    /** Open drive, or -1. Shared with the CD player (the driver serialises the ioctls). */
    @Volatile
    var fd = -1
        private set

    @Volatile
    var device: String? = null
        private set

    private var loaded = false
    private var mounted = false
    private var chmodTried: String? = null

    /** Table of contents of the audio CD shown, compared at each poll: a disc swap the kernel did not report. */
    private var toc: IntArray? = null
    private var lastStatus = Int.MIN_VALUE

    /** Starts watching; each [start] needs a [stop] (activity, playback service). */
    @Synchronized
    fun start() {
        users++
        if (job?.isActive == true) return
        job =
            scope.launch {
                while (isActive) {
                    runCatching { poll() }.onFailure { Log.w(TAG, "poll: ${it.message}") }
                    delay(POLL_MS)
                }
            }
    }

    @Synchronized
    fun stop() {
        if (--users > 0) return
        users = 0
        job?.cancel()
        job = null
    }

    /** Opens the tray (unmounts first). */
    fun eject() {
        scope.launch {
            unmount()
            if (fd >= 0) Cdrom.eject(fd)
            loaded = false
        }
    }

    fun closeTray() {
        scope.launch { if (fd >= 0) Cdrom.closeTray(fd) }
    }

    /** Reads the disc again (after an error, or a refused root request now allowed). */
    fun refresh() {
        scope.launch {
            loaded = false
            chmodTried = null
            poll()
        }
    }

    private fun findDevice(): String? =
        File("/sys/block").list()?.filter { it.startsWith("sr") }?.sorted()?.firstOrNull()?.let { "/dev/block/$it" }

    @Synchronized
    private fun poll() {
        val dev = findDevice()
        if (dev == null || !File(dev).exists()) {
            if (device != null) Log.i(TAG, "drive removed")
            closeDrive()
            unmount()
            device = null
            chmodTried = null
            set(DiscState.NoDrive)
            return
        }
        if (dev != device) {
            closeDrive()
            device = dev
            loaded = false
        }
        if (fd < 0) {
            var r = Cdrom.nativeOpen(dev)
            if (r < 0 && chmodTried != dev) {
                // The node is root-only (ueventd: 0600 root): open it to the app, once per plug.
                chmodTried = dev
                RootShell.run("chmod 0666 $dev")
                r = Cdrom.nativeOpen(dev)
            }
            if (r < 0) {
                set(DiscState.NoAccess(dev))
                return
            }
            fd = r
            Log.i(TAG, "drive $dev opened")
        }
        val status = Cdrom.driveStatus(fd)
        if (status != lastStatus) {
            Log.i(TAG, "drive status $status")
            lastStatus = status
        }
        when (status) {
            Cdrom.CDS_TRAY_OPEN -> discGone(DiscState.TrayOpen)
            Cdrom.CDS_NO_DISC -> discGone(DiscState.NoDisc)
            Cdrom.CDS_DRIVE_NOT_READY -> if (!loaded) set(DiscState.Reading)
            Cdrom.CDS_DISC_OK -> {
                if (Cdrom.mediaChanged(fd) == 1 && loaded) {
                    Log.i(TAG, "disc changed")
                    reopen()
                    return
                }
                if (loaded && _state.value is DiscState.AudioCd) {
                    val now = Cdrom.readToc(fd)
                    if (now != null && !now.contentEquals(toc)) {
                        Log.i(TAG, "table of contents changed: another disc")
                        reopen()
                        return
                    }
                }
                if (!loaded) load()
            }
            else -> if (!loaded) load()
        }
    }

    private fun discGone(s: DiscState) {
        // A disc was there: the drive is opened again for the next one, so that the kernel reads its size anew.
        if (loaded || mounted || _state.value is DiscState.AudioCd || _state.value is DiscState.DataDisc) {
            unmount()
            closeDrive()
        }
        loaded = false
        toc = null
        set(s)
    }

    /** Another disc: closes the drive (playback stops) and reads the new one at the next poll. */
    private fun reopen() {
        unmount()
        closeDrive()
        toc = null
        set(DiscState.Reading)
    }

    private fun load() {
        set(DiscState.Reading)
        val status = Cdrom.discStatus(fd)
        val raw = Cdrom.readToc(fd)
        val tracks = raw?.let { parseToc(it) }.orEmpty()
        val audio = tracks.filter { !it.second }.map { it.first }
        val data = tracks.count { it.second }
        val audioFrames = audio.sumOf { it.frames.toLong() }
        val capacity = Cdrom.capacity(fd).coerceAtLeast(0)
        val dataStatus =
            status == Cdrom.CDS_DATA_1 || status == Cdrom.CDS_DATA_2 || status == Cdrom.CDS_XA_2_1 || status == Cdrom.CDS_XA_2_2
        Log.i(TAG, "disc status $status, ${audio.size} audio ($audioFrames frames) / $data data tracks, $capacity bytes")
        loaded = true
        toc = raw
        // Audio CD: audio tracks, a disc status that is not data only, and no longer than a CD can be (some drives
        // give a DVD a TOC whose single track is not marked as data).
        if (audio.isNotEmpty() && !dataStatus && audioFrames <= MAX_CD_FRAMES &&
            (status == Cdrom.CDS_AUDIO || status == Cdrom.CDS_MIXED || data == 0)
        ) {
            set(DiscState.AudioCd(audio, data))
            return
        }
        val dir = mount()
        val videoTs = dir?.let { d -> File(d).list()?.any { it.equals("VIDEO_TS", ignoreCase = true) } } == true
        set(DiscState.DataDisc(dvd = capacity > 900L * 1024 * 1024, mountPoint = dir, dvdVideo = videoTs, capacity = capacity))
    }

    /** (track, data?) from [Cdrom.readToc]. */
    private fun parseToc(v: IntArray): List<Pair<CdTrack, Boolean>> {
        if (v.size < 6) return emptyList()
        val n = (v.size - 3) / 3
        val leadOut = v[v.size - 1]
        return (0 until n).map { i ->
            val number = v[2 + 3 * i]
            val start = v[3 + 3 * i]
            val ctrl = v[4 + 3 * i]
            val end = if (i + 1 < n) v[3 + 3 * (i + 1)] else leadOut
            CdTrack(number, start, end) to (ctrl and Cdrom.DATA_TRACK != 0)
        }
    }

    /** Mounts the data disc read-only: UDF first (DVDs, UDF bridge), then ISO 9660 / Joliet (CDs). */
    private fun mount(): String? {
        val dev = device ?: return null
        unmount()
        val cmd =
            "mkdir -p $MOUNT_POINT && (mount -t udf -o ro $dev $MOUNT_POINT || " +
                "mount -t iso9660 -o ro,utf8 $dev $MOUNT_POINT || mount -t iso9660 -o ro $dev $MOUNT_POINT)"
        // Global namespace first (seen by the other apps), then the app's own if it does not show here.
        if (RootShell.run(cmd, mountMaster = true) != null) mounted = true
        if (File(MOUNT_POINT).list().isNullOrEmpty() && RootShell.run(cmd) != null) mounted = true
        return if (mounted && File(MOUNT_POINT).list() != null) MOUNT_POINT else null
    }

    private fun unmount() {
        if (!mounted) return
        RootShell.run("umount -l $MOUNT_POINT", mountMaster = true)
        RootShell.run("umount -l $MOUNT_POINT")
        mounted = false
    }

    private fun closeDrive() {
        if (fd >= 0) Cdrom.nativeClose(fd)
        fd = -1
        loaded = false
    }

    private fun set(s: DiscState) {
        if (_state.value != s) _state.value = s
    }
}
