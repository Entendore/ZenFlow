package com.example.zenflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.zenflow.util.CleanPrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundCatalogTest {

    @get:Rule val clean = CleanPrefsRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `catalog exposes all nine sounds`() = assertEquals(9, AMBIENT_SOUNDS.size)

    @Test
    fun `tones need no files`() {
        AMBIENT_SOUNDS.filter { SoundCatalog.isTone(it.key) }.forEach {
            assertTrue(SoundCatalog.hasSounds(ctx, it.key))
            assertTrue(SoundCatalog.variants(ctx, it.key).isEmpty())
        }
    }

    @Test
    fun `missing clips report zero with the naming recipe`() {
        val msg = SoundCatalog.describe(ctx, "nothing_here")
        assertTrue(msg.contains("no clips"))
        assertTrue(msg.contains("nothing_here_01.mp3"))
    }

    @Test
    fun `tone descriptions mention synthesized size`() {
        val msg = SoundCatalog.describe(ctx, "tone_delta")
        assertTrue(msg.contains("synthesized"))
        assertTrue(msg.contains("KB"))
    }

    @Test
    fun `variant index is memoized`() {
        val first = SoundCatalog.variants(ctx, "ocean")
        assertSame(first, SoundCatalog.variants(ctx, "ocean"))
    }
}
