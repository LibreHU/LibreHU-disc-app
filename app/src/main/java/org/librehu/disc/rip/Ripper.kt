package org.librehu.disc.rip

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.librehu.disc.R
import org.librehu.disc.drive.CdTrack
import org.librehu.disc.drive.Cdrom
import org.librehu.disc.drive.DiscDrive
import org.librehu.disc.drive.DiscState
import org.librehu.disc.meta.CdInfo
import org.librehu.disc.meta.CdInfoStore
import org.librehu.disc.player.Playback
import java.io.File
import java.io.IOException

/** Rip settings (prefs of the settings screen). */
data class RipOptions(
    /** "flac" or "wav". */
    val format: String = "flac",
    /** Each block read twice and compared, read again until two reads match. */
    val secure: Boolean = true,
    /** Reads of a sector that fails before it is written as silence. */
    val retries: Int = 10,
    /** File name: "nt" = "01 - Title", "nat" = "01 - Artist - Title", "at" = "Artist - Title". */
    val naming: String = "nt",
    /** Artist/Album folders, else one "Artist - Album" folder. */
    val folders: Boolean = true,
    /** Tags and front cover in the files, and folder.jpg. */
    val tags: Boolean = true,
    /** Folder picked with the system's file picker (USB key, SD card), else Music/LibreHU Disc. */
    val tree: String? = null,
) {
    companion object {
        fun load(context: Context): RipOptions {
            val p = context.getSharedPreferences("disc", Context.MODE_PRIVATE)
            return RipOptions(
                format = p.getString("rip_format", "flac") ?: "flac",
                secure = p.getBoolean("rip_secure", true),
                retries = p.getInt("rip_retries", 10),
                naming = p.getString("rip_naming", "nt") ?: "nt",
                folders = p.getBoolean("rip_folders", true),
                tags = p.getBoolean("rip_tags", true),
                tree = p.getString("rip_tree", null),
            )
        }
    }
}

data class RipState(
    val running: Boolean = false,
    /** Position of the track being ripped in the selection (1-based) and the selection's size. */
    val index: Int = 0,
    val count: Int = 0,
    val title: String = "",
    /** Of the track being ripped, 0..1. */
    val fraction: Float = 0f,
    /** Read speed, × real time. */
    val speed: Float = 0f,
    /** Blocks whose two reads differed and were read again (secure mode). */
    val corrected: Int = 0,
    /** Sectors that could not be read and were written as silence, or not confirmed by a second read. */
    val unreadable: Int = 0,
    /** Tracks written. */
    val done: Int = 0,
    /** Where they went. */
    val destination: String = "",
    val error: String? = null,
    val finished: Boolean = false,
)

/**
 * Rips audio CD tracks to FLAC ([FlacWriter]) or WAV ([WavWriter]): the sectors are read with the same ioctl as the
 * player (CDROMREADAUDIO), in blocks of [BLOCK_FRAMES]. Secure mode reads each block twice and compares; blocks that
 * differ are read again until two reads match. The block is bigger than the cache of usual slim drives (2 MB), so that
 * the second read comes from the disc, not the cache (not guaranteed for drives with a bigger cache). Playback stops
 * meanwhile (both would read the drive). Runs in [RipService] (foreground, notification with the progress).
 */
object Ripper {
    private const val TAG = "LibreHU-Disc"

    /** 20 s of audio, 3.5 MB. */
    private const val BLOCK_FRAMES = 1500
    private const val READ_FRAMES = 25
    private const val MAX_PASSES = 6

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RipState())
    val state: StateFlow<RipState> = _state.asStateFlow()
    private var job: Job? = null
    private var gone = "disc removed"

    val busy: Boolean get() = _state.value.running

    fun start(
        context: Context,
        tracks: List<CdTrack>,
    ) {
        if (busy || tracks.isEmpty()) return
        val app = context.applicationContext
        val disc = DiscDrive.state.value as? DiscState.AudioCd ?: return
        val info = CdInfoStore.info.value?.takeIf { it.discId == disc.discId }
        val opts = RipOptions.load(app)
        gone = app.getString(R.string.rip_disc_gone)
        Playback.stop()
        _state.value = RipState(running = true, count = tracks.size)
        runCatching { ContextCompat.startForegroundService(app, Intent(app, RipService::class.java)) }
        job =
            scope.launch {
                try {
                    rip(app, disc, info, tracks, opts)
                    _state.value = _state.value.copy(running = false, finished = true)
                } catch (e: Exception) {
                    Log.w(TAG, "rip: ${e.message}")
                    val msg = if (e is kotlinx.coroutines.CancellationException) app.getString(R.string.rip_cancelled) else e.message ?: e.javaClass.simpleName
                    _state.value = _state.value.copy(running = false, finished = true, error = msg)
                }
            }
    }

    fun cancel() {
        job?.cancel()
    }

    /** The card of the last rip closed. */
    fun dismiss() {
        if (!busy) _state.value = RipState()
    }

    private suspend fun rip(
        app: Context,
        disc: DiscState.AudioCd,
        info: CdInfo?,
        tracks: List<CdTrack>,
        o: RipOptions,
    ) {
        val album = info?.album?.takeIf { it.isNotBlank() } ?: app.getString(R.string.audio_cd) + " " + (disc.discId?.take(8) ?: "")
        val albumArtist = info?.artist?.takeIf { it.isNotBlank() } ?: app.getString(R.string.unknown_artist)
        val folder = if (o.folders) listOf(safe(albumArtist), safe(album)) else listOf(safe("$albumArtist - $album"))
        val cover = if (o.tags) disc.discId?.let { CdInfoStore.coverFile(it) }?.readBytes() else null
        val sink = Sink.of(app, o, folder)
        _state.value = _state.value.copy(destination = sink.where)
        if (cover != null) sink.put("folder.jpg", "image/jpeg") { it.writeBytes(cover) }
        val self = kotlinx.coroutines.currentCoroutineContext()[Job]!!
        val ext = if (o.format == "wav") "wav" else "flac"
        val mime = if (o.format == "wav") "audio/x-wav" else "audio/flac"
        for ((i, t) in tracks.withIndex()) {
            val title = info?.titles?.get(t.number) ?: app.getString(R.string.track_n, t.number)
            val artist = info?.artistOf(t.number)?.takeIf { it.isNotBlank() } ?: albumArtist
            val num = "%02d".format(t.number)
            val name =
                when (o.naming) {
                    "nat" -> "$num - $artist - $title"
                    "at" -> "$artist - $title"
                    else -> "$num - $title"
                }
            _state.value = _state.value.copy(index = i + 1, title = title, fraction = 0f)
            val tags =
                if (o.tags) {
                    listOf(
                        "TITLE" to title,
                        "ARTIST" to artist,
                        "ALBUM" to album,
                        "ALBUMARTIST" to albumArtist,
                        "DATE" to (info?.year ?: ""),
                        "TRACKNUMBER" to t.number.toString(),
                        "TRACKTOTAL" to disc.tracks.size.toString(),
                        "MUSICBRAINZ_DISCID" to (disc.discId ?: ""),
                    )
                } else {
                    emptyList()
                }
            sink.put("${safe(name)}.$ext", mime) { f ->
                val w: AutoCloseable =
                    if (o.format == "wav") WavWriter(f, tags) else FlacWriter(f, tags, cover)
                w.use { ripTrack(t, o, it, self) }
            }
            _state.value = _state.value.copy(done = i + 1, fraction = 1f)
        }
        sink.finish()
    }

    private fun ripTrack(
        t: CdTrack,
        o: RipOptions,
        w: AutoCloseable,
        job: Job,
    ) {
        val write: (ByteArray, Int) -> Unit =
            when (w) {
                is FlacWriter -> w::write
                is WavWriter -> w::write
                else -> error("writer")
            }
        var lba = t.startLba
        val started = System.nanoTime()
        while (lba < t.endLba) {
            job.ensureActive()
            val frames = minOf(BLOCK_FRAMES, t.endLba - lba)
            var a = readBlock(lba, frames, o)
            if (o.secure) {
                var passes = 1
                var b = readBlock(lba, frames, o)
                var differed = false
                while (!a.contentEquals(b) && passes < MAX_PASSES) {
                    differed = true
                    a = b
                    b = readBlock(lba, frames, o)
                    passes++
                }
                if (differed) _state.value = _state.value.copy(corrected = _state.value.corrected + 1)
                if (!a.contentEquals(b)) {
                    Log.w(TAG, "rip: LBA $lba..${lba + frames} not confirmed after $MAX_PASSES reads")
                    _state.value = _state.value.copy(unreadable = _state.value.unreadable + frames)
                }
                a = b
            }
            write(a, a.size)
            lba += frames
            val secs = (System.nanoTime() - started) / 1e9
            val audio = (lba - t.startLba).toDouble() / Cdrom.FRAMES_PER_SECOND
            _state.value =
                _state.value.copy(
                    fraction = (lba - t.startLba).toFloat() / t.frames,
                    speed = if (secs > 0) (audio / secs).toFloat() else 0f,
                )
        }
    }

    /** [frames] sectors from [lba]; a sector that cannot be read after [RipOptions.retries] tries is silence. */
    private fun readBlock(
        lba: Int,
        frames: Int,
        o: RipOptions,
    ): ByteArray {
        val out = ByteArray(frames * Cdrom.FRAME_BYTES)
        val buf = ByteArray(READ_FRAMES * Cdrom.FRAME_BYTES)
        var at = 0
        while (at < frames) {
            val n = minOf(READ_FRAMES, frames - at)
            var tries = 0
            while (true) {
                val fd = DiscDrive.fd
                if (fd < 0) throw IOException(gone)
                val r = Cdrom.readAudio(fd, lba + at, n, buf)
                if (r >= 0) {
                    System.arraycopy(buf, 0, out, at * Cdrom.FRAME_BYTES, n * Cdrom.FRAME_BYTES)
                    break
                }
                if (Cdrom.driveStatus(fd) != Cdrom.CDS_DISC_OK) throw IOException(gone)
                if (++tries >= o.retries) {
                    // One sector at a time then, so that only the bad ones are silence.
                    if (n > 1) {
                        for (k in 0 until n) {
                            val one = readOne(fd, lba + at + k, o.retries)
                            if (one == null) {
                                _state.value = _state.value.copy(unreadable = _state.value.unreadable + 1)
                            } else {
                                System.arraycopy(one, 0, out, (at + k) * Cdrom.FRAME_BYTES, Cdrom.FRAME_BYTES)
                            }
                        }
                    } else {
                        _state.value = _state.value.copy(unreadable = _state.value.unreadable + 1)
                    }
                    Log.w(TAG, "rip: read error $r at LBA ${lba + at}")
                    break
                }
                Thread.sleep(30L * tries)
            }
            at += n
        }
        return out
    }

    private fun readOne(
        fd: Int,
        lba: Int,
        retries: Int,
    ): ByteArray? {
        val b = ByteArray(Cdrom.FRAME_BYTES)
        repeat(retries) { if (Cdrom.readAudio(fd, lba, 1, b) >= 0) return b }
        return null
    }

    /** No characters that FAT / exFAT (USB keys) or Android refuse in a file name. */
    private fun safe(s: String): String =
        s
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
            .trim()
            .trimEnd('.')
            .take(120)
            .ifEmpty { "_" }

    /** Where the files go: a folder of the shared storage, or a folder picked with the system's picker. */
    private abstract class Sink(
        val where: String,
    ) {
        /** Writes [name] through [fill] (given a local file). */
        abstract fun put(
            name: String,
            mime: String,
            fill: (File) -> Unit,
        )

        open fun finish() {}

        companion object {
            fun of(
                app: Context,
                o: RipOptions,
                folder: List<String>,
            ): Sink = o.tree?.let { TreeSink(app, Uri.parse(it), folder) } ?: FileSink(app, folder)
        }
    }

    /** Music/LibreHU Disc/… (WRITE_EXTERNAL_STORAGE on Android 9; the app's own Music folder on newer ones). */
    private class FileSink(
        val app: Context,
        folder: List<String>,
    ) : Sink(base(app).let { b -> folder.fold(b) { d, n -> File(d, n) } }.path) {
        private val dir = File(where).apply { if (!mkdirs() && !isDirectory) throw IOException(app.getString(R.string.rip_no_folder, path)) }
        private val written = mutableListOf<String>()

        override fun put(
            name: String,
            mime: String,
            fill: (File) -> Unit,
        ) {
            val f = File(dir, name)
            val part = File(dir, "$name.part")
            try {
                fill(part)
                if (!part.renameTo(f)) {
                    part.copyTo(f, overwrite = true)
                    part.delete()
                }
            } catch (e: Throwable) {
                part.delete()
                throw e
            }
            written += f.path
        }

        override fun finish() {
            // Shown in the music apps right away
            MediaScannerConnection.scanFile(app, written.toTypedArray(), null, null)
        }

        companion object {
            fun base(app: Context): File =
                if (Build.VERSION.SDK_INT <= 28) {
                    @Suppress("DEPRECATION")
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "LibreHU Disc")
                } else {
                    File(app.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "LibreHU Disc")
                }
        }
    }

    /** A folder picked with the system's picker (storage access framework): files made locally, then copied. */
    private class TreeSink(
        val app: Context,
        tree: Uri,
        folder: List<String>,
    ) : Sink(folder.joinToString("/")) {
        private val cr: ContentResolver = app.contentResolver
        private val dir: Uri

        init {
            var d = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            for (n in folder) d = child(d, n, DocumentsContract.Document.MIME_TYPE_DIR) ?: throw IOException(app.getString(R.string.rip_no_folder, n))
            dir = d
        }

        override fun put(
            name: String,
            mime: String,
            fill: (File) -> Unit,
        ) {
            val tmp = File(app.cacheDir, "rip.tmp")
            try {
                fill(tmp)
                existing(dir, name)?.let { DocumentsContract.deleteDocument(cr, it) }
                val doc = DocumentsContract.createDocument(cr, dir, mime, name) ?: throw IOException(app.getString(R.string.rip_no_folder, name))
                (cr.openOutputStream(doc) ?: throw IOException(name)).use { out -> tmp.inputStream().use { it.copyTo(out) } }
            } finally {
                tmp.delete()
            }
        }

        /** The sub-folder [name] of [parent], made when missing. */
        private fun child(
            parent: Uri,
            name: String,
            mime: String,
        ): Uri? = existing(parent, name) ?: DocumentsContract.createDocument(cr, parent, mime, name)

        private fun existing(
            parent: Uri,
            name: String,
        ): Uri? {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
            cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0))
                }
            }
            return null
        }
    }
}
