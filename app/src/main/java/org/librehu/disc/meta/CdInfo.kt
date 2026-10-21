package org.librehu.disc.meta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.librehu.disc.drive.DiscDrive
import org.librehu.disc.drive.DiscState
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Album, artist and track titles of an audio CD, from MusicBrainz; front cover from the Cover Art Archive. */
data class CdInfo(
    val discId: String,
    val album: String,
    val artist: String,
    val year: String,
    /** Title of each track, by track number. */
    val titles: Map<Int, String>,
    /** Artist of a track when it is not the album's (compilations). */
    val artists: Map<Int, String>,
    val cover: Bitmap?,
) {
    fun artistOf(track: Int): String = artists[track] ?: artist
}

/** What the disc info cache holds (settings screen). */
data class CdInfoStats(
    val discs: Int = 0,
    val bytes: Long = 0,
    /** Discs seen offline, looked up when the network is back. */
    val pending: Int = 0,
)

/**
 * Looks up the audio CD in the drive on MusicBrainz by its disc ID (one request per disc, under the limit of one
 * request per second), with a meaningful User-Agent as the API asks; only the disc ID is sent. Settings:
 * - `musicbrainz` (on): look discs up at all;
 * - `mb_keep` (on): keep answers and covers in the app's files, so that a disc already seen shows its titles offline,
 *   in the car; off, they are forgotten when the disc leaves the drive;
 * - `mb_offline` (off): never connect; discs not known yet are noted and looked up when the mode is turned off.
 * A disc seen without network is noted too, and looked up as soon as a network is there. A disc MusicBrainz does not
 * know is asked again after three days.
 */
object CdInfoStore {
    private const val TAG = "LibreHU-Disc"
    private const val RETRY_MS = 3L * 24 * 3600 * 1000
    private const val TIMEOUT_MS = 10_000
    private const val RATE_MS = 1100L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _info = MutableStateFlow<CdInfo?>(null)
    private val _stats = MutableStateFlow(CdInfoStats())

    /** Info of the audio CD in the drive, null while unknown. */
    val info: StateFlow<CdInfo?> = _info.asStateFlow()

    val stats: StateFlow<CdInfoStats> = _stats.asStateFlow()

    private lateinit var app: Context
    private lateinit var dir: File
    private var watch: Job? = null
    private var lookup: Job? = null
    private var pendingJob: Job? = null
    private var current: String? = null
    private var userAgent = "LibreHU-Disc ( https://github.com/LibreHU/LibreHU-disc-app )"
    private var lastRequest = 0L

    /** Listeners told when [info] changes (the player renames its queue). */
    var onChange: (() -> Unit)? = null

    private fun prefs(context: Context) = context.getSharedPreferences("disc", Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("musicbrainz", true)

    fun keep(context: Context) = prefs(context).getBoolean("mb_keep", true)

    fun offline(context: Context) = prefs(context).getBoolean("mb_offline", false)

    private fun online() = enabled(app) && !offline(app)

    @Synchronized
    fun start(context: Context) {
        if (watch != null) return
        app = context.applicationContext
        dir = File(app.filesDir, "cdinfo").apply { mkdirs() }
        runCatching {
            val v = app.packageManager.getPackageInfo(app.packageName, 0).versionName
            userAgent = "LibreHU-Disc/$v ( https://github.com/LibreHU/LibreHU-disc-app )"
        }
        if (!keep(app)) forgetAll()
        refreshStats()
        watch =
            scope.launch {
                DiscDrive.state.collect { s ->
                    val id = (s as? DiscState.AudioCd)?.discId
                    if (id != current) {
                        val old = current
                        current = id
                        lookup?.cancel()
                        set(null)
                        if (old != null && !keep(app)) forget(old)
                        if (id != null) lookup = scope.launch { load(id) }
                    }
                }
            }
        // Network back: the discs seen offline are looked up.
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = lookUpPending()
                },
            )
        }
    }

    /** An option changed: the disc in the drive (and the discs seen offline) looked up now if allowed. */
    fun retry() {
        val id = current
        if (id != null) {
            File(dir, "$id.none").delete()
            lookup?.cancel()
            lookup = scope.launch { load(id) }
        }
        lookUpPending()
    }

    /** "Keep offline" turned off: everything but the disc in the drive forgotten. */
    fun keepChanged() {
        if (!keep(app)) forgetAll(except = current)
        refreshStats()
    }

    /** Front cover of [id] as downloaded (JPEG), for the files of a rip. */
    fun coverFile(id: String): File? = if (::dir.isInitialized) File(dir, "$id.jpg").takeIf { it.exists() } else null

    /** Settings: forgets every answer and cover (and the discs waiting for a network). */
    fun clear() {
        scope.launch {
            forgetAll()
            File(dir, PENDING).delete()
            refreshStats()
        }
    }

    private fun set(i: CdInfo?) {
        if (_info.value == i) return
        _info.value = i
        onChange?.invoke()
    }

    private fun load(id: String) {
        val json = File(dir, "$id.json")
        if (!json.exists()) {
            if (!enabled(app)) return
            val none = File(dir, "$id.none")
            if (none.exists() && System.currentTimeMillis() - none.lastModified() < RETRY_MS) return
            if (offline(app) || !fetch(id)) {
                // Kept for later only when answers are kept at all
                if (keep(app)) addPending(id)
                return
            }
        }
        show(id)
    }

    /** Info of [id] from its files, shown when it is still the disc in the drive. */
    private fun show(id: String) {
        val json = File(dir, "$id.json")
        if (!json.exists()) return
        val parsed = runCatching { parse(id, JSONObject(json.readText())) }.getOrNull() ?: return
        val (info, _) = parsed
        if (current != id) return
        val cover = File(dir, "$id.jpg")
        set(if (cover.exists()) info.copy(cover = decode(cover)) else info)
    }

    /**
     * Asks MusicBrainz for [id], then its cover: true when answered (found or not), false without network. The answer
     * is written to the cache, kept or not afterwards ([keep]).
     */
    private fun fetch(id: String): Boolean {
        val json = File(dir, "$id.json")
        val none = File(dir, "$id.none")
        val url = "https://musicbrainz.org/ws/2/discid/$id?inc=recordings+artist-credits&fmt=json&cdstubs=no"
        val body =
            try {
                pace()
                get(url)?.let { String(it, Charsets.UTF_8) }
            } catch (e: IOException) {
                Log.i(TAG, "MusicBrainz: ${e.message}")
                return false
            }
        val parsed = body?.let { b -> runCatching { parse(id, JSONObject(b)) }.getOrNull() }
        if (body == null || parsed == null) {
            Log.i(TAG, "MusicBrainz: disc $id not found")
            none.writeText("")
            refreshStats()
            return true
        }
        json.writeText(body)
        val (info, mbid) = parsed
        Log.i(TAG, "MusicBrainz: ${info.artist} - ${info.album} (${info.titles.size} titles)")
        if (current == id) set(info)
        val cover = File(dir, "$id.jpg")
        val noCover = File(dir, "$id.nocover")
        if (!cover.exists() && !noCover.exists()) {
            try {
                val img = get("https://coverartarchive.org/release/$mbid/front-500")
                if (img == null) noCover.writeText("") else cover.writeBytes(img)
            } catch (e: IOException) {
                Log.i(TAG, "Cover Art Archive: ${e.message}")
            }
        }
        refreshStats()
        return true
    }

    /** One request per second at most, as MusicBrainz asks. */
    private fun pace() {
        val wait = lastRequest + RATE_MS - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastRequest = System.currentTimeMillis()
    }

    @Synchronized
    private fun addPending(id: String) {
        val f = File(dir, PENDING)
        val ids = if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
        if (id !in ids) f.writeText((ids + id).joinToString("\n"))
        refreshStats()
    }

    @Synchronized
    private fun removePending(id: String) {
        val f = File(dir, PENDING)
        if (!f.exists()) return
        f.writeText(f.readLines().filter { it.isNotBlank() && it != id }.joinToString("\n"))
        refreshStats()
    }

    /** Looks up the discs noted offline, one per second, until done or the network is gone again. */
    fun lookUpPending() {
        if (!::app.isInitialized || !online() || pendingJob?.isActive == true) return
        pendingJob =
            scope.launch {
                val f = File(dir, PENDING)
                val ids = if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
                for (id in ids) {
                    if (!online()) break
                    if (!File(dir, "$id.json").exists() && !fetch(id)) break
                    removePending(id)
                    if (id == current) show(id)
                }
            }
    }

    private fun forget(id: String) {
        dir.listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.delete() }
        refreshStats()
    }

    private fun forgetAll(except: String? = null) {
        dir.listFiles()?.filter { it.name != PENDING && (except == null || !it.name.startsWith("$except.")) }?.forEach { it.delete() }
    }

    private fun refreshStats() {
        val files = dir.listFiles().orEmpty()
        val pending = File(dir, PENDING).takeIf { it.exists() }?.readLines()?.count { it.isNotBlank() } ?: 0
        _stats.value = CdInfoStats(files.count { it.name.endsWith(".json") }, files.sumOf { it.length() }, pending)
    }

    /** Body of a GET, null on 404; IOException otherwise (network, other status). */
    private fun get(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "application/json, image/*")
            val code = c.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** The release whose medium has this disc ID, its titles; null when none. */
    private fun parse(
        id: String,
        o: JSONObject,
    ): Pair<CdInfo, String>? {
        val releases = o.optJSONArray("releases") ?: return null
        var best: Pair<JSONObject, JSONObject>? = null
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            val media = r.optJSONArray("media") ?: continue
            for (j in 0 until media.length()) {
                val m = media.getJSONObject(j)
                val discs = m.optJSONArray("discs") ?: continue
                if ((0 until discs.length()).any { discs.getJSONObject(it).optString("id") == id }) {
                    best = r to m
                    break
                }
            }
            if (best != null) break
        }
        val (r, m) = best ?: return null
        val artist = credit(r.optJSONArray("artist-credit"))
        val titles = HashMap<Int, String>()
        val artists = HashMap<Int, String>()
        val tracks = m.optJSONArray("tracks") ?: JSONArray()
        for (k in 0 until tracks.length()) {
            val t = tracks.getJSONObject(k)
            val pos = t.optInt("position", k + 1)
            t.optString("title").takeIf { it.isNotBlank() }?.let { titles[pos] = it }
            credit(t.optJSONArray("artist-credit")).takeIf { it.isNotBlank() && it != artist }?.let { artists[pos] = it }
        }
        val info =
            CdInfo(
                discId = id,
                album = r.optString("title"),
                artist = artist,
                year = r.optString("date").take(4),
                titles = titles,
                artists = artists,
                cover = null,
            )
        return info to r.getString("id")
    }

    private fun credit(a: JSONArray?): String {
        if (a == null) return ""
        val sb = StringBuilder()
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            sb.append(c.optString("name")).append(c.optString("joinphrase"))
        }
        return sb.toString().trim()
    }

    private const val PENDING = "pending.txt"

    private fun decode(f: File): Bitmap? =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 512) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
}
