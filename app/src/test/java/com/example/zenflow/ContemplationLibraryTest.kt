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
class ContemplationLibraryTest {

    @get:Rule val clean = CleanPrefsRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val L = ContemplationLibrary

    @Test
    fun `all texts are unique`() {
        assertEquals(L.ALL.size, L.ALL.map { it.text }.distinct().size)
    }

    @Test
    fun `sound items are tagged and others are not`() {
        L.ALL.filter { it.category == L.Category.SOUND }
            .forEach { assertTrue("untagged SOUND item: ${it.text}", it.tag.isNotEmpty()) }
        L.ALL.filter { it.category != L.Category.SOUND }
            .forEach { assertTrue("stray tag on: ${it.text}", it.tag.isEmpty()) }
    }

    @Test
    fun `every category and every soundscape tag has content`() {
        L.Category.values().forEach { c ->
            assertTrue("empty category $c", L.ALL.any { it.category == c })
        }
        listOf("rain", "ocean", "fire", "forest", "bowl", "tone", "silence").forEach { tag ->
            assertTrue("no contemplations tagged $tag", L.ALL.any { it.tag == tag })
        }
    }

    @Test
    fun `daily pick is stable per seed and varies over time`() {
        assertEquals(L.dailyPick(42), L.dailyPick(42))
        assertTrue((0L..20L).map { L.dailyPick(it).text }.distinct().size > 1)
    }

    @Test
    fun `mix matching routes to the right pool`() {
        repeat(30) {
            assertEquals("rain", L.contemplationForMix(listOf("rain")).tag)
            assertEquals("tone", L.contemplationForMix(listOf("tone_delta", "tone_focus")).tag)
            assertEquals("silence", L.contemplationForMix(emptyList()).tag)
        }
        repeat(20) {
            val tag = L.contemplationForMix(listOf("rain", "fire")).tag
            assertTrue(tag == "rain" || tag == "fire")
        }
    }

    @Test
    fun `favorites toggle and persist`() {
        val c = L.ALL.first()
        assertTrue(L.toggleFavorite(ctx, c))
        assertTrue(L.isFavorite(ctx, c))
        assertEquals(listOf(c.text), L.allFavorites(ctx).map { it.text })
        assertFalse(L.toggleFavorite(ctx, c))
        assertTrue(L.allFavorites(ctx).isEmpty())
    }
}
