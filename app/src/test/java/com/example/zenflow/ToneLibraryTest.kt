package com.example.zenflow

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneLibraryTest {

    @Test fun `every tone solves a seamless loop within one second`() {
        ToneLibrary.TONES.forEach { spec ->
            val t = ToneLibrary.loopSeconds(spec)
            assertTrue("${spec.key} loop = ${t}s", t in 0.001..1.0)
        }
    }

    @Test fun `bundled tones solve to the compact 0_5s loop`() {
        ToneLibrary.TONES.forEach { spec ->
            assertTrue(
                "${spec.key} expected 0.5s, got ${ToneLibrary.loopSeconds(spec)}",
                abs(ToneLibrary.loopSeconds(spec) - 0.5) < 1e-9
            )
        }
    }

    @Test fun `loop buffer is about 43 KB per tone`() {
        ToneLibrary.TONES.forEach { spec ->
            val bytes = ToneLibrary.loopBytes(spec)
            assertTrue("${spec.key} = $bytes bytes", bytes in 40_000..46_000)
        }
    }

    @Test fun `every tone loop is seamless at the seam`() {
        ToneLibrary.TONES.forEach { spec ->
            val pcm = ToneLibrary.samples(spec)
            val peak = pcm.maxOf { abs(it.toInt()) }.coerceAtLeast(1)
            val lDelta = abs(pcm[pcm.size - 2].toInt() - pcm[0].toInt())
            val rDelta = abs(pcm[pcm.size - 1].toInt() - pcm[1].toInt())
            assertTrue("${spec.key} L seam $lDelta", lDelta < peak * 0.08)
            assertTrue("${spec.key} R seam $rDelta", rDelta < peak * 0.08)
        }
    }

    @Test fun `isochronic tone is silent at the seam`() {
        val spec = ToneLibrary.TONES.first { it.isochronicHz > 0 }
        val pcm = ToneLibrary.samples(spec)
        val peak = pcm.maxOf { abs(it.toInt()) }.coerceAtLeast(1)
        assertTrue(abs(pcm[pcm.size - 2].toInt()) < peak * 0.02)
        assertTrue(abs(pcm[pcm.size - 1].toInt()) < peak * 0.02)
    }

    @Test fun `samples stay inside the amplitude envelope`() {
        ToneLibrary.TONES.forEach { spec ->
            val limit = (0.4 * Short.MAX_VALUE).toInt()
            ToneLibrary.samples(spec).forEach { s ->
                assertTrue(abs(s.toInt()) <= limit)
            }
        }
    }
}
