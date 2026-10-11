package com.example.zenflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SynthRendererTest {

    private fun renderAll(voice: SynthVoice): ShortArray {
        val out = ShortArray(SynthRenderer.frameCount(voice))
        SynthRenderer.render(voice, SynthRenderer.cursor(voice), out, 0, out.size)
        return out
    }

    @Test
    fun `frame count matches duration`() {
        assertEquals(2200 * 44100 / 1000, SynthRenderer.frameCount(SynthVoices.COMPLETE))
        assertEquals(500, SynthRenderer.frameCount(SynthVoices.INHALE))
    }

    @Test
    fun `every bundled voice renders its full length without clipping`() {
        listOf(SynthVoices.COMPLETE, SynthVoices.INHALE, SynthVoices.HOLD, SynthVoices.EXHALE)
            .forEach { v ->
                val pcm = renderAll(v)
                assertEquals(SynthRenderer.frameCount(v), pcm.size)
                assertTrue(pcm.any { it.toInt() != 0 })
                assertTrue(pcm.all { abs(it.toInt()) < Short.MAX_VALUE })
            }
    }

    @Test
    fun `attack starts near silence and the tail decays away`() {
        val pcm = renderAll(SynthVoices.COMPLETE)
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertTrue(abs(pcm[0].toInt()) < peak * 0.1)
        assertTrue(abs(pcm[pcm.size - 1].toInt()) < peak * 0.1)
    }

    @Test
    fun `chunked rendering is sample-identical to one-shot rendering`() {
        listOf(SynthVoices.COMPLETE, SynthVoices.INHALE).forEach { voice ->
            val reference = renderAll(voice)
            val chunked = ShortArray(reference.size)
            val cursor = SynthRenderer.cursor(voice)
            val buf = ShortArray(997)
            var done = 0
            while (done < chunked.size) {
                val n = minOf(997, chunked.size - done)
                SynthRenderer.render(voice, cursor, buf, 0, n)
                System.arraycopy(buf, 0, chunked, done, n)
                done += n
            }
            assertTrue(reference.contentEquals(chunked))
        }
    }

    @Test
    fun `a rising sweep has more zero crossings at the end than the start`() {
        fun crossings(from: Int, to: Int, pcm: ShortArray): Int {
            var c = 0
            for (i in from + 1 until to) if ((pcm[i - 1] < 0) != (pcm[i] < 0)) c++
            return c
        }
        val pcm = renderAll(SynthVoices.INHALE)
        val third = pcm.size / 3
        val early = crossings(0, third, pcm)
        val late = crossings(pcm.size - third, pcm.size, pcm)
        assertTrue("early=$early late=$late", late > early)
    }

    @Test
    fun `an over-loud voice is clamped not wrapped`() {
        val loud = SynthVoice(
            durationMs = 100,
            partials = List(8) { SynthPartial(200.0 + it * 50, 1.0) },
            gain = 1.0
        )
        val pcm = renderAll(loud)
        assertTrue(pcm.all { abs(it.toInt()) <= Short.MAX_VALUE })
    }

    @Test
    fun `rendering is deterministic`() {
        assertTrue(renderAll(SynthVoices.COMPLETE).contentEquals(renderAll(SynthVoices.COMPLETE)))
    }
}
