package com.example.zenflow.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Every WAV the app writes or reads goes through here. */
object WavCodec {

    const val HEADER_SIZE = 44

    fun encode(pcm: ShortArray, sampleRate: Int, channels: Int): ByteArray {
        val dataBytes = pcm.size * 2
        val buf = ByteBuffer.allocate(HEADER_SIZE + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataBytes); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16)
        buf.putShort(1); buf.putShort(channels.toShort()); buf.putInt(sampleRate)
        buf.putInt(sampleRate * channels * 2); buf.putShort((channels * 2).toShort()); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(dataBytes)
        for (s in pcm) buf.putShort(s)
        return buf.array()
    }

    class Parsed(
        val pcm: ShortArray, val sampleRate: Int, val channels: Int, val dataBytes: Int
    ) {
        val durationMs: Long get() = durationMs(dataBytes, sampleRate, channels)
    }

    /** Tolerant RIFF chunk walker; null for anything that isn't 16-bit PCM. */
    fun decode(bytes: ByteArray): Parsed? {
        if (bytes.size < HEADER_SIZE) return null
        if (!matchesAt(bytes, 0, "RIFF") || !matchesAt(bytes, 8, "WAVE")) return null

        var sampleRate = 0; var channels = 0; var bits = 0
        var dataStart = -1; var dataBytes = 0
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = leInt(bytes, pos + 4)
            val start = pos + 8
            when (id) {
                "fmt " -> if (size >= 16) {
                    channels = leShort(bytes, start + 2)
                    sampleRate = leInt(bytes, start + 4)
                    bits = leShort(bytes, start + 14)
                }
                "data" -> if (dataStart < 0) {
                    dataStart = start
                    dataBytes = minOf(size, bytes.size - start)
                }
            }
            pos = start + size + (size and 1)
        }
        if (bits != 16 || channels <= 0 || sampleRate <= 0 || dataStart < 0 || dataBytes < 2) return null

        val end = dataStart + dataBytes
        val pcm = ShortArray(dataBytes / 2)
        var i = 0; var p = dataStart
        while (p + 1 < end) {
            pcm[i++] = (((bytes[p].toInt() and 0xFF) or (bytes[p + 1].toInt() shl 8))).toShort()
            p += 2
        }
        return Parsed(pcm, sampleRate, channels, dataBytes)
    }

    fun durationMs(dataBytes: Int, sampleRate: Int, channels: Int): Long =
        if (sampleRate <= 0 || channels <= 0) 0L
        else dataBytes * 1000L / (sampleRate.toLong() * channels * 2)

    private fun matchesAt(b: ByteArray, offset: Int, s: String): Boolean {
        if (b.size < offset + s.length) return false
        for (i in s.indices) if (b[offset + i] != s[i].toByte()) return false
        return true
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
