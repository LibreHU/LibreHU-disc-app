package org.librehu.disc.player

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Band levels of the CD audio being played, for the analyser of the retro display: a 512-point FFT of the PCM the
 * player sends out, 16 bands spaced logarithmically from 50 Hz to 16 kHz, levels 0..1. Only CD tracks feed it (the
 * player has their PCM); files played by MediaPlayer leave it at rest.
 */
object Spectrum {
    const val BANDS = 16
    private const val N = 512
    private const val RATE = 44100f

    private val _levels = MutableStateFlow(FloatArray(BANDS))
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }

    /** FFT bin edges of the bands. */
    private val edges =
        IntArray(BANDS + 1) { b ->
            val f = 50f * Math.pow(16000.0 / 50.0, b / BANDS.toDouble()).toFloat()
            (f * N / RATE).toInt().coerceIn(1, N / 2)
        }

    /**
     * [bytes] of 16-bit little-endian stereo PCM in [pcm], just written to the AudioTrack: analyses its middle and
     * shows it [delayMs] later, when it is heard (the AudioTrack buffer ahead of the speakers).
     */
    @Synchronized
    fun feed(
        pcm: ByteArray,
        bytes: Int,
        delayMs: Long,
    ) {
        val frames = bytes / 4
        if (frames < N) return
        val start = (frames - N) / 2 * 4
        for (i in 0 until N) {
            val o = start + i * 4
            val l = (pcm[o].toInt() and 0xff) or (pcm[o + 1].toInt() shl 8)
            val r = (pcm[o + 2].toInt() and 0xff) or (pcm[o + 3].toInt() shl 8)
            re[i] = (l + r) / 65536f * window[i]
            im[i] = 0f
        }
        fft()
        val out = FloatArray(BANDS)
        for (b in 0 until BANDS) {
            var peak = 0f
            for (k in edges[b]..maxOf(edges[b], edges[b + 1] - 1)) {
                val m = sqrt(re[k] * re[k] + im[k] * im[k])
                if (m > peak) peak = m
            }
            // Full scale with the Hann window = N / 4; 0..-54 dBFS shown as 1..0.
            val db = 20 * log10(peak / (N / 4f) + 1e-6f)
            out[b] = ((db + 54f) / 54f).coerceIn(0f, 1f)
        }
        main.postDelayed({ _levels.value = out }, delayMs)
    }

    fun rest() {
        main.removeCallbacksAndMessages(null)
        _levels.value = FloatArray(BANDS)
    }

    /** In-place iterative radix-2 FFT of [re] / [im]. */
    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]
                re[i] = re[j]
                re[j] = t
                t = im[i]
                im[i] = im[j]
                im[j] = t
            }
        }
        var len = 2
        while (len <= N) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < N) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
