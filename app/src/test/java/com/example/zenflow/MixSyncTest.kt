package com.example.zenflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.zenflow.util.CleanPrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MixSyncTest {

    @get:Rule val clean = CleanPrefsRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `parses values and enforces catalog order regardless of input order`() {
        val m = MixSync.parse(ctx, "tone_focus:30,tone_alpha:90,tone_delta:60")
        assertEquals(listOf("tone_delta", "tone_alpha", "tone_focus"), m.keys.toList())
        assertEquals(30, m["tone_focus"])
    }

    @Test
    fun `drops unknown keys and sounds without clips`() {
        assertTrue(MixSync.parse(ctx, "nope:50,rain:50").isEmpty())
        assertEquals(1, MixSync.parse(ctx, "nope:50,tone_theta:40").size)
    }

    @Test
    fun `clamps volumes and falls back to 60 on garbage numbers`() {
        assertEquals(100, MixSync.parse(ctx, "tone_delta:250")["tone_delta"])
        assertEquals(0, MixSync.parse(ctx, "tone_delta:-5")["tone_delta"])
        assertEquals(60, MixSync.parse(ctx, "tone_delta:abc")["tone_delta"])
    }

    @Test
    fun `skips malformed entries but keeps the healthy ones`() {
        val m = MixSync.parse(ctx, "tone_delta,tone_theta:40:20,::,tone_alpha:55")
        assertEquals(mapOf("tone_alpha" to 55), m)
    }

    @Test
    fun `null blank and duplicates behave`() {
        assertTrue(MixSync.parse(ctx, null).isEmpty())
        assertTrue(MixSync.parse(ctx, "   ").isEmpty())
        assertEquals(80, MixSync.parse(ctx, "tone_delta:20,tone_delta:80")["tone_delta"])
    }

    @Test
    fun `encode orders by catalog and omits absent keys`() {
        assertEquals(
            "tone_delta:60,tone_focus:30",
            MixSync.encode(mapOf("tone_focus" to 30, "tone_delta" to 60))
        )
        assertEquals("", MixSync.encode(emptyMap()))
    }

    @Test
    fun `encode then parse roundtrips exactly`() {
        val original = mapOf("tone_theta" to 45, "tone_focus" to 100)
        assertEquals(original, MixSync.parse(ctx, MixSync.encode(original)))
    }

    @Test
    fun `diff classifies removals additions and volume changes`() {
        val d = MixSync.diff(
            current = mapOf("rain" to 40, "fire" to 60, "bowl" to 80),
            target = mapOf("fire" to 25, "bowl" to 80, "ocean" to 70)
        )
        assertEquals(listOf("rain"), d.removals)
        assertEquals(mapOf("fire" to 25), d.volumeChanges)
        assertEquals(mapOf("ocean" to 70), d.additions)
        assertFalse(d.isEmpty)
    }

    @Test
    fun `diff is a no-op when maps are equal even in a different order`() {
        val d = MixSync.diff(
            current = linkedMapOf("rain" to 40, "fire" to 60),
            target = linkedMapOf("fire" to 60, "rain" to 40)
        )
        assertTrue(d.isEmpty)
    }

    @Test
    fun `diff handles empty sides`() {
        assertTrue(MixSync.diff(emptyMap(), emptyMap()).isEmpty)
        val allOut = MixSync.diff(mapOf("rain" to 40), emptyMap())
        assertEquals(listOf("rain"), allOut.removals)
        val allIn = MixSync.diff(emptyMap(), mapOf("rain" to 40))
        assertEquals(mapOf("rain" to 40), allIn.additions)
    }
}
