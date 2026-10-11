package com.example.zenflow.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WavCodecTest {

    @Test
    fun `encode decode roundtrip preserves samples rate and channels`() {
        val pcm = ShortArray(1000) { ((it % 200) - 100).toShort() }
        for (channels in listOf(1, 2)) {
            val parsed = WavCodec.decode(WavCodec.encode(pcm, 22050, channels))
            assertNotNull(parsed)
            assertEquals(22050, parsed!!.sampleRate)
            assertEquals(channels, parsed.channels)
            assertTrue(parsed.pcm.contentEquals(pcm))
        }
    }

    @Test
    fun `header fields are exact`() {
        val bytes = WavCodec.encode(ShortArray(2205), 44100, 1)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(44100, le(bytes, 24, 4))
        assertEquals(1, le(bytes, 22, 2))
        assertEquals(16, le(bytes, 34, 2))
        assertEquals(4410, le(bytes, 40, 4))
        assertEquals(44 + 4410, bytes.size)
    }

    @Test
    fun `decode rejects garbage and truncated files`() {
        assertNull(WavCodec.decode(ByteArray(10)))
        assertNull(WavCodec.decode("not a wav at all".toByteArray()))
        val good = WavCodec.encode(ShortArray(100), 44100, 1)
        assertNull(WavCodec.decode(good.copyOfRange(0, 30)))
        val bad = good.copyOf(); bad[20] = 3
        assertNull(WavCodec.decode(bad))
    }

    @Test
    fun `duration math matches the synthesized library`() {
        assertEquals(2200L, WavCodec.durationMs(194_040, 44_100, 1))
        assertEquals(500L, WavCodec.durationMs(44_100, 44_100, 1))
        assertEquals(500L, WavCodec.durationMs(44_100, 22_050, 2))
        assertEquals(0L, WavCodec.durationMs(1000, 0, 1))
    }

    private fun le(b: ByteArray, off: Int, n: Int): Int {
        var v = 0
        for (i in 0 until n) v = v or ((b[off + i].toInt() and 0xFF) shl (8 * i))
        return v
    }
}
