package org.librehu.disc.meta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

/**
 * Looks up the audio CD in the drive on MusicBrainz by its disc ID (one request per new disc, well under the limit of
 * one request per second), with a meaningful User-Agent as the API asks. Answers and covers are kept in the app's
 * files: a disc already seen shows its titles offline, in the car. A disc MusicBrainz does not know is asked again
 * after three days. Option "online disc info" (pref `musicbrainz`, on by default): only the disc ID is sent.
 */
object CdInfoStore {
    private const val TAG = "LibreHU-Disc"
    private const val RETRY_MS = 3L * 24 * 3600 * 1000
    private const val TIMEOUT_MS = 10_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _info = MutableStateFlow<CdInfo?>(null)

    /** Info of the audio CD in the drive, null while unknown. */
    val info: StateFlow<CdInfo?> = _info.asStateFlow()

    private lateinit var app: Context
    private lateinit var dir: File
    private var watch: Job? = null
    private var lookup: Job? = null
    private var current: String? = null
    private var userAgent = "LibreHU-Disc ( https://github.com/LibreHU/LibreHU-disc-app )"

    /** Listeners told when [info] changes (the player renames its queue). */
    var onChange: (() -> Unit)? = null

    fun enabled(context: Context) = context.getSharedPreferences("disc", Context.MODE_PRIVATE).getBoolean("musicbrainz", true)

    @Synchronized
    fun start(context: Context) {
        if (watch != null) return
        app = context.applicationContext
        dir = File(app.filesDir, "cdinfo").apply { mkdirs() }
        runCatching {
            val v = app.packageManager.getPackageInfo(app.packageName, 0).versionName
            userAgent = "LibreHU-Disc/$v ( https://github.com/LibreHU/LibreHU-disc-app )"
        }
        watch =
            scope.launch {
                DiscDrive.state.collect { s ->
                    val id = (s as? DiscState.AudioCd)?.discId
                    if (id != current) {
                        current = id
                        lookup?.cancel()
                        set(null)
                        if (id != null) lookup = scope.launch { load(id) }
                    }
                }
            }
    }

    /** The option was turned on: looks up the disc in the drive now. */
    fun retry() {
        val id = current ?: return
        File(dir, "$id.none").delete()
        lookup?.cancel()
        lookup = scope.launch { load(id) }
    }

    private fun set(i: CdInfo?) {
        if (_info.value == i) return
        _info.value = i
        onChange?.invoke()
    }

    private fun load(id: String) {
        val json = File(dir, "$id.json")
        val none = File(dir, "$id.none")
        var body = if (json.exists()) json.readText() else null
        if (body == null) {
            if (!enabled(app)) return
            if (none.exists() && System.currentTimeMillis() - none.lastModified() < RETRY_MS) return
            val url = "https://musicbrainz.org/ws/2/discid/$id?inc=recordings+artist-credits&fmt=json&cdstubs=no"
            body =
                try {
                    get(url)?.let { String(it, Charsets.UTF_8) }
                } catch (e: IOException) {
                    Log.i(TAG, "MusicBrainz: ${e.message}")
                    return // offline: asked again at the next insertion
                }
            if (body == null) {
                Log.i(TAG, "MusicBrainz: disc $id not found")
                none.writeText("")
                return
            }
        }
        val parsed =
            runCatching { parse(id, JSONObject(body)) }.getOrNull() ?: run {
                Log.i(TAG, "MusicBrainz: no release for disc $id")
                none.writeText("")
                return
            }
        if (!json.exists()) json.writeText(body)
        val (info, mbid) = parsed
        Log.i(TAG, "MusicBrainz: ${info.artist} - ${info.album} (${info.titles.size} titles)")
        if (current != id) return
        set(info)
        val cover = File(dir, "$id.jpg")
        val noCover = File(dir, "$id.nocover")
        if (!cover.exists() && !noCover.exists() && enabled(app)) {
            try {
                val img = get("https://coverartarchive.org/release/$mbid/front-500")
                if (img == null) noCover.writeText("") else cover.writeBytes(img)
            } catch (e: IOException) {
                Log.i(TAG, "Cover Art Archive: ${e.message}")
            }
        }
        if (cover.exists() && current == id) set(info.copy(cover = decode(cover)))
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

    private fun decode(f: File): Bitmap? =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 512) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
}
