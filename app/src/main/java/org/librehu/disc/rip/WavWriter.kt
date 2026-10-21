package org.librehu.disc.rip

import java.io.File
import java.io.RandomAccessFile

/**
 * WAV file of CD audio (44.1 kHz, 16-bit stereo PCM, as read from the disc), with the tags in a `LIST`/`INFO` chunk
 * (INAM title, IART artist, IPRD album, ICRD year, ITRK track number). Sizes are written when the file is closed.
 */
class WavWriter(
    file: File,
    tags: List<Pair<String, String>>,
) : AutoCloseable {
    private val out = RandomAccessFile(file, "rw")
    private val dataSizePos: Long
    private var dataBytes = 0L

    init {
        out.setLength(0)
        out.write("RIFF".toByteArray())
        le32(0) // RIFF size, at close()
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le32(16)
        le16(1) // PCM
        le16(2)
        le32(44100)
        le32(44100 * 4)
        le16(4)
        le16(16)
        val info = info(tags)
        if (info.isNotEmpty()) {
            out.write("LIST".toByteArray())
            le32(4 + info.size)
            out.write("INFO".toByteArray())
            out.write(info)
        }
        out.write("data".toByteArray())
        dataSizePos = out.filePointer
        le32(0)
    }

    fun write(
        pcm: ByteArray,
        bytes: Int,
    ) {
        out.write(pcm, 0, bytes)
        dataBytes += bytes
    }

    override fun close() {
        try {
            val end = out.filePointer
            out.seek(4)
            le32((end - 8).toInt())
            out.seek(dataSizePos)
            le32(dataBytes.toInt())
        } finally {
            out.close()
        }
    }

    private fun info(tags: List<Pair<String, String>>): ByteArray {
        val ids = mapOf("TITLE" to "INAM", "ARTIST" to "IART", "ALBUM" to "IPRD", "DATE" to "ICRD", "TRACKNUMBER" to "ITRK")
        val b = java.io.ByteArrayOutputStream()
        for ((k, v) in tags) {
            val id = ids[k.uppercase()] ?: continue
            if (v.isBlank()) continue
            val text = v.toByteArray(Charsets.UTF_8) + 0
            b.write(id.toByteArray())
            val n = text.size
            b.write(byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()))
            b.write(text)
            if (n % 2 == 1) b.write(0) // chunks are word-aligned
        }
        return b.toByteArray()
    }

    private fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v ushr 8).toByte()))

    private fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()))
}
