package org.librehu.disc.rip

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * FLAC encoder for CD audio (https://xiph.org/flac/format.html): 44.1 kHz, 16-bit stereo, fixed blocks of 4096 samples.
 * Each block: the best of independent / left-side / right-side / mid-side stereo, each channel coded as CONSTANT,
 * VERBATIM or FIXED (orders 0 to 4), residual in Rice partitions (orders 0 to 6, parameter per partition). No LPC: about
 * the size of `flac -1`/`-2`, fast enough on the head unit. STREAMINFO (sizes, sample count, MD5 of the audio) is
 * written again when the file is closed. Tags in a VORBIS_COMMENT block, the cover in a PICTURE block.
 */
class FlacWriter(
    file: File,
    tags: List<Pair<String, String>>,
    cover: ByteArray?,
    coverMime: String = "image/jpeg",
) : AutoCloseable {
    private val out = RandomAccessFile(file, "rw")
    private val md5 = MessageDigest.getInstance("MD5")
    private val pendingL = IntArray(BLOCK)
    private val pendingR = IntArray(BLOCK)
    private var pending = 0
    private var frameNumber = 0L
    private var totalSamples = 0L
    private var minFrame = Int.MAX_VALUE
    private var maxFrame = 0
    private val bw = BitWriter(64 * 1024)
    private val streamInfoPos: Long

    init {
        out.setLength(0)
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        streamInfoPos = out.filePointer
        out.write(ByteArray(4 + 34)) // STREAMINFO, written at close()
        val vc = vorbisComment(tags)
        val blocks = mutableListOf(4 to vc)
        if (cover != null) blocks.add(6 to picture(cover, coverMime))
        blocks.add(1 to ByteArray(1024)) // PADDING: room to edit the tags later
        for ((i, b) in blocks.withIndex()) {
            val type = b.first or (if (i == blocks.lastIndex) 0x80 else 0)
            out.write(byteArrayOf(type.toByte(), (b.second.size ushr 16).toByte(), (b.second.size ushr 8).toByte(), b.second.size.toByte()))
            out.write(b.second)
        }
    }

    /** [bytes] of 16-bit little-endian stereo PCM (CD sectors). */
    fun write(
        pcm: ByteArray,
        bytes: Int,
    ) {
        md5.update(pcm, 0, bytes)
        var o = 0
        while (o + 4 <= bytes) {
            pendingL[pending] = (pcm[o].toInt() and 0xff) or (pcm[o + 1].toInt() shl 8)
            pendingR[pending] = (pcm[o + 2].toInt() and 0xff) or (pcm[o + 3].toInt() shl 8)
            o += 4
            if (++pending == BLOCK) flushBlock()
        }
    }

    override fun close() {
        try {
            if (pending > 0) flushBlock()
            val si = BitWriter(34)
            // Fixed block size: min = max = 4096 (the last block may be shorter, as in libFLAC)
            si.bits(BLOCK.toLong(), 16)
            si.bits(BLOCK.toLong(), 16)
            si.bits(if (minFrame == Int.MAX_VALUE) 0 else minFrame.toLong(), 24)
            si.bits(maxFrame.toLong(), 24)
            si.bits(RATE.toLong(), 20)
            si.bits(1, 3) // channels - 1
            si.bits(15, 5) // bits per sample - 1
            si.bits(totalSamples, 36)
            out.seek(streamInfoPos)
            out.write(byteArrayOf(0, 0, 0, 34)) // STREAMINFO, not last
            out.write(si.bytes(), 0, si.size())
            out.write(md5.digest())
        } finally {
            out.close()
        }
    }

    private fun flushBlock() {
        val n = pending
        bw.reset()
        // Channel assignment: the smallest of the four stereo codings
        val l = pendingL.copyOf(n)
        val r = pendingR.copyOf(n)
        val side = IntArray(n) { l[it] - r[it] }
        val mid = IntArray(n) { (l[it] + r[it]) shr 1 }
        val cl = Subframe.best(l, 16)
        val cr = Subframe.best(r, 16)
        val cs = Subframe.best(side, 17)
        val cm = Subframe.best(mid, 16)
        val (assignment, pair) =
            listOf(
                1 to (cl to cr), // independent
                8 to (cl to cs), // left / side
                9 to (cs to cr), // side / right
                10 to (cm to cs), // mid / side
            ).minBy { it.second.first.bits + it.second.second.bits }
        header(n, assignment)
        pair.first.write(bw)
        pair.second.write(bw)
        bw.align()
        val crc16 = Crc.crc16(bw.bytes(), bw.size())
        bw.bits(crc16.toLong(), 16)
        out.write(bw.bytes(), 0, bw.size())
        minFrame = minOf(minFrame, bw.size())
        maxFrame = maxOf(maxFrame, bw.size())
        totalSamples += n
        frameNumber++
        pending = 0
    }

    private fun header(
        n: Int,
        assignment: Int,
    ) {
        bw.bits(0x3FFE, 14) // sync
        bw.bits(0, 1)
        bw.bits(0, 1) // fixed block size
        bw.bits(if (n == BLOCK) 12L else 7L, 4) // 4096, or 16-bit (n - 1) at the end of the header
        bw.bits(9, 4) // 44.1 kHz
        bw.bits(assignment.toLong(), 4)
        bw.bits(4, 3) // 16 bits per sample
        bw.bits(0, 1)
        utf8(frameNumber)
        if (n != BLOCK) bw.bits((n - 1).toLong(), 16)
        bw.align()
        bw.bits(Crc.crc8(bw.bytes(), bw.size()).toLong(), 8)
    }

    /** Frame number in the "UTF-8" coding of FLAC (up to 36 bits). */
    private fun utf8(v: Long) {
        if (v < 0x80) {
            bw.bits(v, 8)
            return
        }
        var bytes = 2
        while (v >= (1L shl (5 * bytes + 1))) bytes++
        val first = (0xFF00 shr bytes) and 0xFF
        bw.bits(first.toLong() or (v ushr (6 * (bytes - 1))), 8)
        for (i in bytes - 2 downTo 0) bw.bits(0x80L or ((v ushr (6 * i)) and 0x3F), 8)
    }

    private fun vorbisComment(tags: List<Pair<String, String>>): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        fun le32(v: Int) = b.write(byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()))
        val vendor = "LibreHU Disc".toByteArray()
        le32(vendor.size)
        b.write(vendor)
        val list = tags.filter { it.second.isNotBlank() }
        le32(list.size)
        for ((k, v) in list) {
            val c = "${k.uppercase()}=$v".toByteArray(Charsets.UTF_8)
            le32(c.size)
            b.write(c)
        }
        return b.toByteArray()
    }

    private fun picture(
        data: ByteArray,
        mime: String,
    ): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        fun be32(v: Int) = b.write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
        be32(3) // front cover
        val m = mime.toByteArray(Charsets.US_ASCII)
        be32(m.size)
        b.write(m)
        be32(0) // no description
        be32(0) // width, height, depth, colours unknown
        be32(0)
        be32(0)
        be32(0)
        be32(data.size)
        b.write(data)
        return b.toByteArray()
    }

    /** One channel of a frame, coded the smallest way found. */
    private class Subframe(
        val bits: Long,
        val write: (BitWriter) -> Unit,
    ) {
        companion object {
            fun best(
                x: IntArray,
                bps: Int,
            ): Subframe {
                val n = x.size
                if (x.all { it == x[0] }) {
                    return Subframe(8L + bps) { w ->
                        w.bits(0, 8) // CONSTANT
                        w.signed(x[0], bps)
                    }
                }
                var best = Subframe(8L + n.toLong() * bps) { w ->
                    w.bits(2, 8) // VERBATIM
                    for (v in x) w.signed(v, bps)
                }
                for (order in 0..minOf(4, n - 1)) {
                    val res = residual(x, order)
                    val rice = Rice.plan(res, order, n) ?: continue
                    val size = 8L + order.toLong() * bps + rice.bits
                    if (size < best.bits) {
                        best =
                            Subframe(size) { w ->
                                w.bits((0x08 or order).toLong() shl 1, 8) // 0, FIXED (001xxx = order), no wasted bits
                                for (i in 0 until order) w.signed(x[i], bps)
                                rice.write(w)
                            }
                    }
                }
                return best
            }

            private fun residual(
                x: IntArray,
                order: Int,
            ): IntArray {
                val n = x.size
                val e = IntArray(n - order)
                for (i in order until n) {
                    e[i - order] =
                        when (order) {
                            0 -> x[i]
                            1 -> x[i] - x[i - 1]
                            2 -> x[i] - 2 * x[i - 1] + x[i - 2]
                            3 -> x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
                            else -> x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
                        }
                }
                return e
            }
        }
    }

    /**
     * Residual coding: partitioned Rice, 4-bit parameters, the partition order with the fewest bits. The cost of each
     * parameter is summed once per finest partition, then merged for the coarser orders.
     */
    private class Rice(
        val bits: Long,
        val write: (BitWriter) -> Unit,
    ) {
        companion object {
            private const val MAX_K = 14

            fun plan(
                e: IntArray,
                predOrder: Int,
                n: Int,
            ): Rice? {
                val u = LongArray(e.size) { zigzag(e[it]) }
                var maxPo = 0
                while (maxPo < 6 && n % (1 shl (maxPo + 1)) == 0 && (n shr (maxPo + 1)) > predOrder) maxPo++
                if ((n shr maxPo) < predOrder) return null
                val parts = 1 shl maxPo
                // cost[p][k]: sum of (u >> k) over the finest partition p; count[p]: its samples
                val cost = Array(parts) { LongArray(MAX_K + 1) }
                val count = IntArray(parts)
                var s = 0
                for (p in 0 until parts) {
                    val c = if (p == 0) (n shr maxPo) - predOrder else (n shr maxPo)
                    count[p] = c
                    val row = cost[p]
                    for (i in s until s + c) {
                        val v = u[i]
                        for (k in 0..MAX_K) row[k] += v ushr k
                    }
                    s += c
                }
                var best: Rice? = null
                for (po in 0..maxPo) {
                    val group = 1 shl (maxPo - po)
                    val params = IntArray(1 shl po)
                    var total = 6L // method + partition order
                    for (p in 0 until (1 shl po)) {
                        var bestK = 0
                        var bestBits = Long.MAX_VALUE
                        var c = 0L
                        for (q in p * group until (p + 1) * group) c += count[q]
                        for (k in 0..MAX_K) {
                            var b = c * (k + 1)
                            for (q in p * group until (p + 1) * group) b += cost[q][k]
                            if (b < bestBits) {
                                bestBits = b
                                bestK = k
                            }
                        }
                        params[p] = bestK
                        total += 4 + bestBits
                    }
                    if (best == null || total < best.bits) {
                        val order = po
                        best =
                            Rice(total) { w ->
                                w.bits(0, 2) // Rice, 4-bit parameters
                                w.bits(order.toLong(), 4)
                                var i = 0
                                for (p in 0 until (1 shl order)) {
                                    val c = if (p == 0) (n shr order) - predOrder else (n shr order)
                                    val k = params[p]
                                    w.bits(k.toLong(), 4)
                                    for (j in i until i + c) w.rice(u[j], k)
                                    i += c
                                }
                            }
                    }
                }
                return best
            }

            private fun zigzag(v: Int): Long = if (v >= 0) v.toLong() shl 1 else ((-v.toLong()) shl 1) - 1
        }
    }

    /** MSB-first bit writer. */
    private class BitWriter(
        capacity: Int,
    ) {
        private var buf = ByteArray(capacity)
        private var len = 0
        private var acc = 0L
        private var accBits = 0

        fun reset() {
            len = 0
            acc = 0
            accBits = 0
        }

        fun bits(
            v: Long,
            n: Int,
        ) {
            var left = n
            while (left > 0) {
                val take = minOf(left, 32)
                left -= take
                acc = (acc shl take) or ((v ushr left) and ((1L shl take) - 1))
                accBits += take
                while (accBits >= 8) {
                    accBits -= 8
                    put(((acc ushr accBits) and 0xFF).toInt())
                }
                acc = acc and ((1L shl accBits) - 1)
            }
        }

        fun signed(
            v: Int,
            n: Int,
        ) = bits(v.toLong() and ((1L shl n) - 1), n)

        fun rice(
            u: Long,
            k: Int,
        ) {
            var q = u ushr k
            while (q >= 32) {
                bits(0, 32)
                q -= 32
            }
            bits(1, q.toInt() + 1) // q zeros then a one
            if (k > 0) bits(u and ((1L shl k) - 1), k)
        }

        fun align() {
            if (accBits > 0) bits(0, 8 - accBits)
        }

        fun size() = len

        fun bytes() = buf

        private fun put(b: Int) {
            if (len == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[len++] = b.toByte()
        }
    }

    private object Crc {
        private val t8 =
            IntArray(256) { i ->
                var c = i
                repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
                c
            }
        private val t16 =
            IntArray(256) { i ->
                var c = i shl 8
                repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
                c
            }

        fun crc8(
            b: ByteArray,
            n: Int,
        ): Int {
            var c = 0
            for (i in 0 until n) c = t8[c xor (b[i].toInt() and 0xFF)]
            return c
        }

        fun crc16(
            b: ByteArray,
            n: Int,
        ): Int {
            var c = 0
            for (i in 0 until n) c = ((c shl 8) and 0xFFFF) xor t16[((c ushr 8) xor (b[i].toInt() and 0xFF)) and 0xFF]
            return c
        }
    }

    companion object {
        const val BLOCK = 4096
        const val RATE = 44100
    }
}
