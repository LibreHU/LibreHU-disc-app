package org.librehu.disc.drive

import android.util.Base64
import java.security.MessageDigest
import java.util.Locale

/**
 * MusicBrainz disc ID of an audio CD (https://musicbrainz.org/doc/Disc_ID_Calculation), from the table of contents of
 * [Cdrom.readToc]: SHA-1 of first / last track (2 hex digits), lead-out and the 99 track offsets (8 hex digits each,
 * LBA + 150 sectors of lead-in, 0 for missing tracks), in Base64 with `.` `_` `-` instead of `+` `/` `=`.
 */
object DiscId {
    private const val LEAD_IN = 150

    /** Gap between the audio session and the data track of an enhanced CD (multisession). */
    private const val SESSION_GAP = 11400

    /** Disc ID, or null for a table of contents without audio tracks. */
    fun of(v: IntArray): String? {
        if (v.size < 6) return null
        val n = (v.size - 3) / 3
        val numbers = IntArray(n) { v[2 + 3 * it] }
        val starts = IntArray(n) { v[3 + 3 * it] }
        val data = BooleanArray(n) { v[4 + 3 * it] and Cdrom.DATA_TRACK != 0 }
        if (data.all { it }) return null
        var last = n - 1
        var leadOut = v[v.size - 1]
        // Enhanced CD: the audio session ends 11400 sectors before its trailing data track, which is not counted.
        if (n > 1 && data[last] && !data[0]) {
            leadOut = starts[last] - SESSION_GAP
            last--
        }
        val offsets = IntArray(100)
        offsets[0] = leadOut + LEAD_IN
        for (i in 0..last) if (numbers[i] in 1..99) offsets[numbers[i]] = starts[i] + LEAD_IN
        val sb = StringBuilder(804)
        sb.append(String.format(Locale.ROOT, "%02X%02X", numbers[0], numbers[last]))
        for (o in offsets) sb.append(String.format(Locale.ROOT, "%08X", o))
        val sha = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray(Charsets.US_ASCII))
        return Base64
            .encodeToString(sha, Base64.NO_WRAP)
            .replace('+', '.')
            .replace('/', '_')
            .replace('=', '-')
    }
}
