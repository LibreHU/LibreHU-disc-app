package org.librehu.disc.player

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.disc.R
import org.librehu.disc.drive.CdTrack
import org.librehu.disc.drive.Cdrom
import org.librehu.disc.drive.DiscDrive
import org.librehu.disc.drive.DiscState
import org.librehu.disc.meta.CdInfo
import org.librehu.disc.meta.CdInfoStore
import org.librehu.disc.rip.Ripper
import java.io.File
import java.util.concurrent.ArrayBlockingQueue

/** One entry of the play queue: a CD track or a file of a data disc. */
data class QueueItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val durationMs: Long,
    val track: CdTrack? = null,
    val file: File? = null,
)

data class PlayerState(
    val queue: List<QueueItem> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val error: String? = null,
) {
    val current: QueueItem? get() = queue.getOrNull(index)
}

/**
 * The player: audio CD tracks read sector by sector from the drive (CDROMREADAUDIO, raw 44.1 kHz PCM into an
 * AudioTrack, gapless) and audio files of data discs (MediaPlayer). Owns the media session (steering wheel keys,
 * LibreHU EasySource, the notification) and the audio focus.
 */
object Playback {
    private const val TAG = "LibreHU-Disc"

    /** Sectors per read: 1/6 s, small enough for a quick seek and a lively analyser, big enough for USB drives. */
    private const val CHUNK_FRAMES = 12

    /** Back to the start of the track instead of the previous one after this. */
    private const val RESTART_MS = 3000L

    /** Seconds of audio read ahead by default (anti-skip). */
    const val ANTISKIP_DEFAULT = 10

    /** Custom action of the session: the eject button of the LibreHU launcher's media card. */
    const val ACTION_EJECT = "org.librehu.disc.action.EJECT"

    private lateinit var app: Context
    private lateinit var audio: AudioManager
    lateinit var session: MediaSession
        private set

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var cd: CdThread? = null
    private var media: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var resumeOnFocus = false

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        audio = app.getSystemService(AudioManager::class.java)
        session =
            MediaSession(app, "LibreHU Disc").apply {
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onPlay() = resume()

                        override fun onPause() = pause()

                        override fun onStop() = stop()

                        override fun onSkipToNext() = next()

                        override fun onSkipToPrevious() = previous()

                        override fun onSeekTo(pos: Long) = seekTo(pos)

                        override fun onSkipToQueueItem(id: Long) = playIndex(id.toInt())

                        override fun onPlayFromMediaId(
                            mediaId: String,
                            extras: android.os.Bundle?,
                        ) = playFromMediaId(mediaId)

                        override fun onCustomAction(
                            action: String,
                            extras: android.os.Bundle?,
                        ) {
                            if (action == ACTION_EJECT) eject()
                        }
                    },
                )
                @Suppress("DEPRECATION")
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            }
        CdInfoStore.onChange = { renameCd() }
        CdInfoStore.start(app)
        publish()
    }

    // --- Queue ---------------------------------------------------------------------------------------------------

    fun cdQueue(tracks: List<CdTrack>): List<QueueItem> = tracks.map { cdItem(it, info()) }

    /** MusicBrainz info of the CD in the drive, if known. */
    private fun info(): CdInfo? {
        val id = (DiscDrive.state.value as? DiscState.AudioCd)?.discId ?: return null
        return CdInfoStore.info.value?.takeIf { it.discId == id }
    }

    /** "Track N" and "Audio CD", or the title and "artist - album" from MusicBrainz. */
    private fun cdItem(
        t: CdTrack,
        i: CdInfo?,
    ): QueueItem {
        val title = i?.titles?.get(t.number) ?: app.getString(R.string.track_n, t.number)
        val sub = if (i == null) app.getString(R.string.audio_cd) else listOf(i.artistOf(t.number), i.album).filter { it.isNotBlank() }.joinToString(" - ")
        return QueueItem("cd:${t.number}", title, sub, t.durationMs, track = t)
    }

    /** Titles arrived (or the disc changed): the CD tracks of the queue renamed, the session told. */
    private fun renameCd() {
        if (!::app.isInitialized) return
        val i = info()
        update { s -> s.copy(queue = s.queue.map { q -> q.track?.let { cdItem(it, i) } ?: q }) }
    }

    fun fileQueue(files: List<File>): List<QueueItem> =
        files.map { QueueItem("file:${it.absolutePath}", it.nameWithoutExtension, it.parentFile?.name.orEmpty(), 0, file = it) }

    fun playQueue(
        queue: List<QueueItem>,
        index: Int,
    ) {
        if (queue.isEmpty()) return
        update { PlayerState(queue = queue, index = index.coerceIn(0, queue.lastIndex)) }
        start(0)
    }

    /** From a media id of the browser tree (LibreHU EasySource) or the session. */
    fun playFromMediaId(id: String) {
        when (val s = DiscDrive.state.value) {
            is org.librehu.disc.drive.DiscState.AudioCd -> {
                val q = cdQueue(s.tracks)
                playQueue(q, q.indexOfFirst { it.id == id }.coerceAtLeast(0))
            }
            else -> {
                val q = _state.value.queue
                val i = q.indexOfFirst { it.id == id }
                if (i >= 0) playIndex(i)
            }
        }
    }

    fun playIndex(i: Int) {
        if (i !in _state.value.queue.indices) return
        update { it.copy(index = i, positionMs = 0, error = null) }
        start(0)
    }

    // --- Transport -----------------------------------------------------------------------------------------------

    fun toggle() = if (_state.value.playing) pause() else resume()

    fun resume() {
        var s = _state.value
        // A CD track queued from a disc no longer in the drive (play from the launcher after an eject): forgotten.
        val t = s.current?.track
        if (t != null && (DiscDrive.state.value as? DiscState.AudioCd)?.tracks?.contains(t) != true) {
            release()
            update { PlayerState() }
            s = _state.value
        }
        if (s.current == null) {
            // Nothing queued: the CD in the drive from its first track.
            (DiscDrive.state.value as? org.librehu.disc.drive.DiscState.AudioCd)?.let { playQueue(cdQueue(it.tracks), 0) }
            return
        }
        if (!requestFocus()) return
        cd?.paused = false
        media?.start()
        if (cd == null && media == null) start(s.positionMs)
        update { it.copy(playing = true) }
        startService()
    }

    fun pause() {
        cd?.paused = true
        runCatching { media?.pause() }
        update { it.copy(playing = false, positionMs = position()) }
    }

    fun stop() {
        release()
        abandonFocus()
        update { it.copy(playing = false, positionMs = 0) }
    }

    fun next() {
        val s = _state.value
        if (s.index + 1 < s.queue.size) playIndex(s.index + 1) else stop()
    }

    /** The CD left the drive: its tracks are forgotten (the queue of a data disc stays until its own disc goes). */
    fun discGone() {
        if (_state.value.queue.none { it.track != null }) return
        release()
        abandonFocus()
        update { PlayerState() }
    }

    /** Stops playback (everything queued is read from the disc) and opens the tray. */
    fun eject() {
        stop()
        DiscDrive.eject()
    }

    fun previous() {
        val s = _state.value
        if (position() > RESTART_MS || s.index == 0) seekTo(0) else playIndex(s.index - 1)
    }

    fun seekTo(ms: Long) {
        val item = _state.value.current ?: return
        cd?.let {
            it.seek(item.track!!.startLba + (ms * Cdrom.FRAMES_PER_SECOND / 1000).toInt())
            update { s -> s.copy(positionMs = ms) }
            return
        }
        runCatching { media?.seekTo(ms.toInt()) }
        update { s -> s.copy(positionMs = ms) }
    }

    fun position(): Long =
        cd?.positionMs() ?: runCatching { media?.currentPosition?.toLong() }.getOrNull() ?: _state.value.positionMs

    /** Called by the service's ticker and on changes: position, session state, metadata. */
    fun tick() {
        if (_state.value.playing) update { it.copy(positionMs = position()) }
    }

    // --- Engines -------------------------------------------------------------------------------------------------

    private fun start(fromMs: Long) {
        release()
        val item = _state.value.current ?: return
        if (!requestFocus()) {
            Log.w(TAG, "audio focus refused")
            return
        }
        update { it.copy(playing = true, positionMs = fromMs, error = null) }
        startService()
        when {
            // The drive is being read by a rip
            item.track != null && Ripper.busy -> fail(app.getString(R.string.rip_busy))
            item.track != null && DiscDrive.fd < 0 -> fail("drive closed")
            item.track != null -> {
                val ahead = app.getSharedPreferences("disc", Context.MODE_PRIVATE).getInt("antiskip", ANTISKIP_DEFAULT)
                Log.i(TAG, "play track ${item.track.number} from ${fromMs} ms (LBA ${item.track.startLba}..${item.track.endLba}, fd ${DiscDrive.fd}, anti-skip $ahead s)")
                cd = CdThread(item.track, (fromMs * Cdrom.FRAMES_PER_SECOND / 1000).toInt(), ahead).also { it.start() }
            }
            item.file != null -> {
                try {
                    media =
                        MediaPlayer().apply {
                            setAudioAttributes(attributes)
                            setDataSource(item.file.absolutePath)
                            setOnCompletionListener { next() }
                            setOnErrorListener { _, what, _ ->
                                fail("MediaPlayer $what")
                                true
                            }
                            prepare()
                            if (fromMs > 0) seekTo(fromMs.toInt())
                            start()
                        }
                    val d = media!!.duration.toLong().coerceAtLeast(0)
                    update { s -> s.copy(queue = s.queue.mapIndexed { i, q -> if (i == s.index) q.copy(durationMs = d) else q }) }
                } catch (e: Exception) {
                    fail(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun release() {
        cd?.finish()
        cd = null
        media?.let { runCatching { it.release() } }
        media = null
    }

    private fun fail(msg: String) {
        Log.w(TAG, "playback: $msg")
        release()
        update { it.copy(playing = false, error = msg) }
    }

    private val attributes: AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    /**
     * Plays CD tracks, gapless: a reader thread reads the sectors ahead of time into a buffer, this thread writes them
     * to the AudioTrack. Anti-skip option (pref `antiskip`, seconds): the reader keeps that much audio ahead, so that a
     * jolt of the road (the drive loses its track, reads fail for a moment) is read again before the buffer runs out
     * instead of being heard. Without it, 1/3 s ahead.
     */
    private class CdThread(
        first: CdTrack,
        startFrame: Int,
        aheadSeconds: Int,
    ) : Thread("cd-audio") {
        @Volatile
        var paused = false

        @Volatile
        private var running = true

        /** Track and sector last given to the AudioTrack (position, queue index). */
        @Volatile
        private var track = first

        @Volatile
        private var lba = first.startLba + startFrame.coerceIn(0, first.frames - 1)

        @Volatile
        private var seekTo = -1

        /** Retries of a sector before it is played as silence: more with a buffer, which gives the time. */
        private val retries = if (aheadSeconds > 0) 12 else 3

        private val buffer: ArrayBlockingQueue<Chunk> =
            ArrayBlockingQueue(maxOf(2, aheadSeconds * Cdrom.FRAMES_PER_SECOND / CHUNK_FRAMES))
        private var reader: Reader? = null

        /** Sectors of a track, or the end of the queue ([track] null), or the disc gone ([gone]), from reader [src]. */
        private class Chunk(
            val src: Any,
            val track: CdTrack?,
            val lba: Int,
            val frames: Int,
            val data: ByteArray,
            val gone: Boolean = false,
        )

        private val out: AudioTrack =
            AudioTrack
                .Builder()
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                ).setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                ).setBufferSizeInBytes(
                    maxOf(
                        AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT),
                        4 * CHUNK_FRAMES * Cdrom.FRAME_BYTES,
                    ),
                ).setTransferMode(AudioTrack.MODE_STREAM)
                .build()

        fun seek(target: Int) {
            seekTo = target
        }

        /** Position in the current track (sectors sent to the AudioTrack). */
        fun positionMs(): Long = ((lba - track.startLba).coerceAtLeast(0)) * 1000L / Cdrom.FRAMES_PER_SECOND

        fun finish() {
            running = false
            reader?.finish()
            interrupt()
        }

        /** Reads from [from] on into [buffer], the next queued tracks after the end of [from]'s track. */
        private inner class Reader(
            private var t: CdTrack,
            private var at: Int,
        ) : Thread("cd-read") {
            @Volatile
            private var on = true

            fun finish() {
                on = false
                interrupt()
            }

            override fun run() {
                var errors = 0
                try {
                    while (on && running) {
                        if (at >= t.endLba) {
                            val next = Playback.nextCd(t)
                            if (next == null) {
                                put(Chunk(this, null, at, 0, ByteArray(0)))
                                return
                            }
                            t = next
                            at = next.startLba
                            continue
                        }
                        val fd = DiscDrive.fd
                        if (fd < 0) {
                            put(Chunk(this, null, at, 0, ByteArray(0), gone = true))
                            return
                        }
                        val frames = minOf(CHUNK_FRAMES, t.endLba - at)
                        val buf = ByteArray(frames * Cdrom.FRAME_BYTES)
                        val r = Cdrom.readAudio(fd, at, frames, buf)
                        if (r < 0) {
                            // Disc removed or tray opened: stop, the drive watcher reads what is there now.
                            if (Cdrom.driveStatus(fd) != Cdrom.CDS_DISC_OK) {
                                Log.i(TAG, "read error $r at LBA $at: no disc any more")
                                put(Chunk(this, null, at, 0, ByteArray(0), gone = true))
                                return
                            }
                            // Jolt, scratch or bad sector: read again, then play it as silence instead of stopping.
                            if (++errors < retries) {
                                if (errors > 1) sleep(40L * errors)
                                continue
                            }
                            Log.w(TAG, "read error $r at LBA $at")
                        } else if (errors > 0) {
                            Log.i(TAG, "LBA $at read after ${errors + 1} tries")
                        }
                        errors = 0
                        put(Chunk(this, t, at, frames, buf))
                        at += frames
                    }
                } catch (_: InterruptedException) {
                }
            }

            private fun put(c: Chunk) {
                while (on && running) if (buffer.offer(c, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) return
            }
        }

        private fun startReader(
            t: CdTrack,
            at: Int,
        ) {
            reader?.finish()
            buffer.clear()
            reader = Reader(t, at).also { it.start() }
        }

        override fun run() {
            out.play()
            try {
                startReader(track, lba)
                while (running) {
                    if (paused) {
                        if (out.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            out.pause()
                            Spectrum.rest()
                        }
                        SystemClock.sleep(50)
                        continue
                    }
                    if (out.playState != AudioTrack.PLAYSTATE_PLAYING) out.play()
                    val s = seekTo
                    if (s >= 0) {
                        seekTo = -1
                        out.pause()
                        out.flush()
                        lba = s.coerceIn(track.startLba, track.endLba - 1)
                        startReader(track, lba)
                        out.play()
                    }
                    val c = buffer.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    // Read before a seek by the reader stopped then
                    if (seekTo >= 0 || c.src !== reader) continue
                    if (c.gone) {
                        DiscDrive.refresh()
                        break
                    }
                    val t = c.track
                    if (t == null) break
                    if (t != track) {
                        // Into the next track: the queue follows (gapless).
                        Playback.enterCd(t)
                        track = t
                    }
                    out.write(c.data, 0, c.data.size)
                    Spectrum.feed(c.data, c.data.size, out.bufferSizeInFrames * 1000L / 44100)
                    lba = c.lba + c.frames
                }
            } catch (_: InterruptedException) {
            } finally {
                reader?.finish()
                Spectrum.rest()
                runCatching {
                    out.pause()
                    out.flush()
                    out.release()
                }
            }
            if (running) Playback.onCdEnded()
        }
    }

    /** The CD track queued after [t], or null at the end of the queue. */
    @Synchronized
    private fun nextCd(t: CdTrack): CdTrack? {
        val q = _state.value.queue
        val i = q.indexOfFirst { it.track == t }
        return if (i >= 0) q.getOrNull(i + 1)?.track else null
    }

    /** The CD thread plays [t] now: it becomes the current queue item. */
    @Synchronized
    private fun enterCd(t: CdTrack) {
        val i = _state.value.queue.indexOfFirst { it.track == t }
        if (i >= 0) update { it.copy(index = i, positionMs = 0) }
    }

    private fun onCdEnded() {
        cd = null
        update { it.copy(playing = false, positionMs = 0) }
        abandonFocus()
    }

    // --- Focus, service, session ---------------------------------------------------------------------------------

    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeOnFocus = false
                    pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeOnFocus = _state.value.playing
                    pause()
                }
                AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) {
                    resumeOnFocus = false
                    resume()
                }
            }
        }

    private fun requestFocus(): Boolean {
        val req =
            focus ?: AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { focus = it }
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
    }

    private fun startService() {
        runCatching { ContextCompat.startForegroundService(app, Intent(app, DiscService::class.java)) }
    }

    @Synchronized
    private fun update(f: (PlayerState) -> PlayerState) {
        val old = _state.value
        val new = f(old)
        _state.value = new
        if (::app.isInitialized && (old.current != new.current || old.playing != new.playing || old.queue != new.queue)) publish()
        else if (::app.isInitialized) publishState()
    }

    private fun publish() {
        val s = _state.value
        val item = s.current
        val cdInfo = item?.track?.let { info() }
        session.setMetadata(
            MediaMetadata
                .Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, item?.title ?: app.getString(R.string.app_name))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, item?.track?.let { t -> cdInfo?.artistOf(t.number) } ?: item?.subtitle.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, cdInfo?.album ?: item?.subtitle.orEmpty())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, item?.durationMs ?: 0)
                .apply {
                    cdInfo?.cover?.let {
                        putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                        putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
                    }
                }.build(),
        )
        session.setQueue(
            s.queue.mapIndexed { i, q ->
                MediaSession.QueueItem(
                    android.media.MediaDescription
                        .Builder()
                        .setMediaId(q.id)
                        .setTitle(q.title)
                        .setSubtitle(q.subtitle)
                        .build(),
                    i.toLong(),
                )
            },
        )
        publishState()
        session.isActive = item != null
    }

    private fun publishState() {
        val s = _state.value
        session.setPlaybackState(
            PlaybackState
                .Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID,
                ).addCustomAction(
                    PlaybackState.CustomAction
                        .Builder(ACTION_EJECT, app.getString(R.string.eject), R.drawable.ic_eject)
                        .build(),
                ).setActiveQueueItemId(s.index.toLong())
                .setState(
                    when {
                        s.error != null -> PlaybackState.STATE_ERROR
                        s.playing -> PlaybackState.STATE_PLAYING
                        s.current != null -> PlaybackState.STATE_PAUSED
                        else -> PlaybackState.STATE_NONE
                    },
                    s.positionMs,
                    if (s.playing) 1f else 0f,
                ).build(),
        )
    }
}
