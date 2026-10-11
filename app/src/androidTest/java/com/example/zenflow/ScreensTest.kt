package com.example.zenflow

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.hasSibling
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreensTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun cleanState() {
        ctx.getSharedPreferences("zen_stats", Context.MODE_PRIVATE).edit().clear().commit()
        SessionStore.clearCacheForTests()
    }

    @Test
    fun homeShowsZeroedStatsAndFirstMeditation() {
        ActivityScenario.launch(MainActivity::class.java)
        onView(withId(R.id.tvSessions)).check(matches(withText("0")))
        onView(withId(R.id.tvStreak)).check(matches(withText("0")))
        onView(withText("Deep Calm")).check(matches(isDisplayed()))
        onView(withId(R.id.tvDailyText)).check(matches(withText(containsString(" "))))
    }

    @Test
    fun historyLinkShowsEmptyState() {
        ActivityScenario.launch(MainActivity::class.java)
        onView(withText("History →")).perform(click())
        onView(withText(containsString("No sessions yet"))).check(matches(isDisplayed()))
    }

    @Test
    fun openingPlayerShowsTitleAndSeededPresets() {
        ActivityScenario.launch(MainActivity::class.java)
        onView(withId(R.id.recyclerMeditations))
            .perform(RecyclerViewActions.actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()))
        onView(withId(R.id.tvTitle)).check(matches(withText("Deep Calm")))
        onView(withText("🌙 Deep Sleep")).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withText("💭 Contemplate")).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withText("📚 Library")).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    @Test
    fun libraryChipOpensSoundLibrary() {
        ActivityScenario.launch(PlayerActivity::class.java)
        onView(withText("📚 Library")).perform(scrollTo(), click())
        onView(withText(containsString("synthesized"))).check(matches(isDisplayed()))
    }

    @Test
    fun breathingLegendReflectsPattern() {
        ActivityScenario.launch(BreathingActivity::class.java)
        onView(withId(R.id.tvLegend))
            .check(matches(withText(containsString("6 s breathe out"))))
        onView(withText("📦 Box 4-4-4-4")).perform(scrollTo(), click())
        onView(withId(R.id.tvLegend)).check(matches(withText(containsString("4 s rest"))))
    }

    @Test
    fun breathingStartsAndStops() {
        val scenario = ActivityScenario.launch(BreathingActivity::class.java)
        onView(withText("Breathing Exercise")).check(matches(isDisplayed()))
        onView(withId(R.id.btnToggleBreathing)).perform(click())
        scenario.onActivity { a ->
            assertEquals("Stop", a.findViewById<Button>(R.id.btnToggleBreathing).text.toString())
            assertEquals("Breathe In", a.findViewById<TextView>(R.id.tvPhase).text.toString())
        }
        scenario.onActivity { a -> a.findViewById<Button>(R.id.btnToggleBreathing).performClick() }
        onView(withText("Start")).check(matches(isDisplayed()))
    }

    @Test
    fun contemplationCyclesWithoutImmediateRepeat() {
        ActivityScenario.launch(ContemplationActivity::class.java)
        var first = ""
        onView(withId(R.id.tvText)).check { view, e ->
            if (e != null) throw e
            first = (view as TextView).text.toString()
            assertEquals(false, first.isEmpty())
        }
        onView(withId(R.id.btnNext)).perform(click())
        onView(withId(R.id.tvText)).check { view, e ->
            if (e != null) throw e
            assertNotEquals(first, (view as TextView).text.toString())
        }
    }

    @Test
    fun favoritesPersistAcrossRestart() {
        val scenario = ActivityScenario.launch(ContemplationActivity::class.java)
        onView(withId(R.id.btnFav)).perform(click())
        scenario.close()
        ActivityScenario.launch(ContemplationActivity::class.java)
        onView(withText("❤️ Favorites")).perform(scrollTo(), click())
        onView(withText("No favorites yet")).check(doesNotExist())
    }

    @Test
    fun historyListsSessionWithReflection() {
        SessionStore.addSession(
            ctx,
            SessionRecord(
                System.currentTimeMillis(), "Deep Calm", 12, "rain,fire", "😌",
                "perfect rain on the window"
            )
        )
        ActivityScenario.launch(HistoryActivity::class.java)
        onView(withText("Deep Calm")).check(matches(isDisplayed()))
        onView(withText("12 min")).check(matches(isDisplayed()))
        onView(withText("“perfect rain on the window”")).check(matches(isDisplayed()))
    }

    @Test
    fun libraryListsAllNineSounds() {
        ActivityScenario.launch(SoundLibraryActivity::class.java)
        onView(withText("Rain")).check(matches(isDisplayed()))
        onView(withId(R.id.recycler))
            .perform(RecyclerViewActions.scrollToPosition<RecyclerView.ViewHolder>(8))
        onView(withText("Iso focus")).check(matches(isDisplayed()))
    }

    @Test
    fun tonePreviewStartsAndStops() {
        ActivityScenario.launch(SoundLibraryActivity::class.java)
        onView(withId(R.id.recycler))
            .perform(RecyclerViewActions.scrollToPosition<RecyclerView.ViewHolder>(5))
        val btn = allOf(withId(R.id.btnPreview), hasSibling(withText("Delta sleep")))
        onView(btn).perform(click())
        onView(btn).check(matches(withText("■")))
        onView(btn).perform(click())
        onView(btn).check(matches(withText("▶")))
    }

    @Test
    fun seededPresetAppliesThroughDiffSync() {
        val scenario = ActivityScenario.launch(PlayerActivity::class.java)
        onView(withText("🌙 Deep Sleep")).perform(scrollTo(), click())
        onView(withText(containsString("Deep Sleep applied"))).check(matches(isDisplayed()))
        scenario.onActivity { a ->
            val panel = a.findViewById<LinearLayout>(R.id.mixerPanel)
            assertEquals(View.VISIBLE, panel.visibility)
            assertEquals(1, panel.childCount)
        }
    }

    @Test
    fun switchingPatternMidRunRestartsSession() {
        val scenario = ActivityScenario.launch(BreathingActivity::class.java)
        onView(withId(R.id.btnToggleBreathing)).perform(click())
        scenario.onActivity { a ->
            a.findViewById<LinearLayout>(R.id.patternChips).getChildAt(3).performClick()
            assertEquals("Stop", a.findViewById<Button>(R.id.btnToggleBreathing).text.toString())
            assertTrue(
                a.findViewById<TextView>(R.id.tvLegend).text.toString()
                    .contains("5 s breathe in")
            )
        }
        scenario.onActivity { a -> a.findViewById<Button>(R.id.btnToggleBreathing).performClick() }
    }
}
