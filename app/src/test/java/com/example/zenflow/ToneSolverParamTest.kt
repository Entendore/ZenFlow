package com.example.zenflow

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.abs

@RunWith(Parameterized::class)
class ToneSolverParamTest(private val spec: ToneLibrary.ToneSpec) {

    @Test
    fun `solves within one second`() {
        val t = ToneLibrary.loopSeconds(spec)
        assertTrue("${spec.leftHz}/${spec.rightHz} Hz → ${t}s", t in 0.001..1.0)
    }

    @Test
    fun `generated loop joins onto itself without a click`() {
        val pcm = ToneLibrary.samples(spec)
        val peak = pcm.maxOf { abs(it.toInt()) }.coerceAtLeast(1)
        assertTrue(abs(pcm[pcm.size - 2].toInt() - pcm[0].toInt()) < peak * 0.08)
        assertTrue(abs(pcm[pcm.size - 1].toInt() - pcm[1].toInt()) < peak * 0.08)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun specs(): List<ToneLibrary.ToneSpec> = listOf(
            ToneLibrary.ToneSpec("a", "unison", "🎯", "", 200, 200, 0),
            ToneLibrary.ToneSpec("b", "1 Hz beat", "🌑", "", 100, 101, 0),
            ToneLibrary.ToneSpec("c", "large coprime", "🌌", "", 173, 197, 0),
            ToneLibrary.ToneSpec("d", "A433", "🌤️", "", 432, 433, 0),
            ToneLibrary.ToneSpec("e", "iso 7 Hz", "🎯", "", 150, 150, 7)
        )
    }
}
