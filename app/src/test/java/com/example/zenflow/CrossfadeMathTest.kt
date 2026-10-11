package com.example.zenflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeMathTest {

    @Test
    fun `fade never outlives half a short track`() {
        assertEquals(3000L, CrossfadeMath.fadeDurationMs(6000))
        assertEquals(4000L, CrossfadeMath.fadeDurationMs(60_000))
        assertEquals(4000L, CrossfadeMath.fadeDurationMs(0))
        assertEquals(0L, CrossfadeMath.fadeDurationMs(1))
    }

    @Test
    fun `crossfade starts inside the fade window and never before it`() {
        assertFalse(CrossfadeMath.shouldBegin(20_000, 30_000))
        assertFalse(CrossfadeMath.shouldBegin(25_999, 30_000))
        assertTrue(CrossfadeMath.shouldBegin(26_000, 30_000))
        assertTrue(CrossfadeMath.shouldBegin(29_999, 30_000))
        assertFalse(CrossfadeMath.shouldBegin(10_000, 0))
    }

    @Test
    fun `progress sweeps zero to one and clamps outside the range`() {
        assertEquals(0f, CrossfadeMath.progress(0, 4000), 1e-6f)
        assertEquals(0.5f, CrossfadeMath.progress(2000, 4000), 1e-6f)
        assertEquals(1f, CrossfadeMath.progress(4000, 4000), 1e-6f)
        assertEquals(0f, CrossfadeMath.progress(-50, 4000), 1e-6f)
        assertEquals(1f, CrossfadeMath.progress(10_000, 4000), 1e-6f)
        assertEquals(0f, CrossfadeMath.progress(0, 0), 1e-6f)
    }

    @Test
    fun `linear crossfade keeps the sum of the two volumes at exactly one`() {
        for (i in 0..20) {
            val (out, inc) = CrossfadeMath.volumes(i / 20f)
            assertEquals("t=$i/20", 1f, out + inc, 1e-6f)
        }
    }

    @Test
    fun `the fade boundary is exact for every track length`() {
        listOf(1000L, 4000L, 8000L).forEach { cf ->
            for (dur in 2..120_000 step 997) {
                val fade = CrossfadeMath.fadeDurationMs(dur, cf)
                val firePos = (dur - fade).toInt()
                assertTrue("dur=$dur cf=$cf", CrossfadeMath.shouldBegin(firePos, dur, cf))
                assertFalse(CrossfadeMath.shouldBegin(firePos - 1, dur, cf))
            }
        }
    }
}
