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
class PresetStoreTest {

    @get:Rule val clean = CleanPrefsRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `save and list roundtrip preserves volumes`() {
        assertTrue(PresetStore.save(ctx, "Rainy Night", mapOf("rain" to 40, "bowl" to 60)))
        val list = PresetStore.list(ctx)
        assertEquals(1, list.size)
        assertEquals(40, list[0].mix["rain"])
        assertEquals(60, list[0].mix["bowl"])
    }

    @Test
    fun `saving an existing name overwrites instead of duplicating`() {
        PresetStore.save(ctx, "Mix", mapOf("rain" to 40))
        PresetStore.save(ctx, "Mix", mapOf("ocean" to 80))
        val list = PresetStore.list(ctx)
        assertEquals(1, list.size)
        assertEquals(80, list[0].mix["ocean"])
    }

    @Test
    fun `blank names and empty mixes are rejected`() {
        assertFalse(PresetStore.save(ctx, "   ", mapOf("rain" to 40)))
        assertFalse(PresetStore.save(ctx, "Mix", emptyMap()))
        assertTrue(PresetStore.list(ctx).isEmpty())
    }

    @Test
    fun `rename rejects collisions and blanks`() {
        PresetStore.save(ctx, "A", mapOf("rain" to 40))
        PresetStore.save(ctx, "B", mapOf("rain" to 40))
        assertFalse(PresetStore.rename(ctx, "A", "B"))
        assertFalse(PresetStore.rename(ctx, "A", "  "))
        assertTrue(PresetStore.rename(ctx, "A", "C"))
        assertTrue(PresetStore.list(ctx).any { it.name == "C" })
    }

    @Test
    fun `delete removes the preset`() {
        PresetStore.save(ctx, "Temp", mapOf("rain" to 40))
        PresetStore.delete(ctx, "Temp")
        assertTrue(PresetStore.list(ctx).isEmpty())
    }

    @Test
    fun `seeding is idempotent and tone-based`() {
        PresetStore.seedIfFirstRun(ctx)
        assertEquals(3, PresetStore.list(ctx).size)
        PresetStore.seedIfFirstRun(ctx)
        assertEquals(3, PresetStore.list(ctx).size)
        assertTrue(PresetStore.list(ctx).all { p -> p.mix.keys.all { it.startsWith("tone_") } })
    }

    @Test
    fun `encodeMix keeps catalog order and skips sounds without clips`() {
        val (encoded, skipped) =
            PresetStore.encodeMix(ctx, mapOf("fire" to 50, "tone_theta" to 70, "rain" to 20))
        assertTrue("tone_theta:70" in encoded)
        assertEquals(SoundCatalog.hasSounds(ctx, "rain"), encoded.contains("rain:"))
        assertEquals(SoundCatalog.hasSounds(ctx, "fire"), encoded.contains("fire:"))
        assertEquals(
            listOf("rain", "fire").filter { !SoundCatalog.hasSounds(ctx, it) }
                .map { SoundCatalog.labelFor(ctx, it) },
            skipped
        )
    }
}
