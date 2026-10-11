package com.example.zenflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.zenflow.util.CleanPrefsRule
import com.example.zenflow.util.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionStoreTest {

    @get:Rule val clean = CleanPrefsRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun add(minutes: Int = 10, daysAgo: Int = 0, title: String = "Test") =
        Fixtures.add(Fixtures.record(title = title, minutes = minutes, timestamp = Fixtures.ts(daysAgo)))

    @Test
    fun `records are newest first`() {
        add(title = "older", daysAgo = 1)
        add(title = "newer")
        assertEquals("newer", SessionStore.getSessions(ctx).first().title)
        assertEquals("older", SessionStore.getSessions(ctx).last().title)
    }

    @Test
    fun `history is capped at 200 records`() {
        repeat(205) { add(minutes = it + 1) }
        val all = SessionStore.getSessions(ctx)
        assertEquals(200, all.size)
        assertEquals(205, all.first().minutes)
        assertEquals(6, all.last().minutes)
    }

    @Test
    fun `streak counts consecutive days and is gracious about today`() {
        add(daysAgo = 3); add(daysAgo = 2); add(daysAgo = 1)
        assertEquals(3, SessionStore.currentStreak(ctx))
        add(daysAgo = 0)
        assertEquals(4, SessionStore.currentStreak(ctx))
    }

    @Test
    fun `streak survives until the day after a miss`() {
        add(daysAgo = 1); add(daysAgo = 2)
        assertEquals(2, SessionStore.currentStreak(ctx))
    }

    @Test
    fun `streak breaks across a gap`() {
        add(daysAgo = 0); add(daysAgo = 2)
        assertEquals(1, SessionStore.currentStreak(ctx))
    }

    @Test
    fun `empty history has zero streak`() {
        assertEquals(0, SessionStore.currentStreak(ctx))
    }

    @Test
    fun `weekly buckets place sessions in the right day`() {
        add(minutes = 10, daysAgo = 0)
        add(minutes = 25, daysAgo = 6)
        val (minutes, labels) = SessionStore.weeklyMinutes(ctx)
        assertEquals(25f, minutes[0], 0.001f)
        assertEquals(10f, minutes[6], 0.001f)
        assertEquals(0f, minutes[3], 0.001f)
        assertEquals(7, labels.size)
    }

    @Test
    fun `first milestone fires exactly once`() {
        add()
        assertEquals(listOf("first"), SessionStore.checkMilestones(ctx).map { it.id })
        assertTrue(SessionStore.checkMilestones(ctx).isEmpty())
    }

    @Test
    fun `milestones batch in catalog order`() {
        repeat(10) { add(minutes = 6) }
        val ids = SessionStore.checkMilestones(ctx).map { it.id }
        assertEquals(listOf("first", "sessions_10", "minutes_60"), ids)
    }

    @Test
    fun `seven day streak unlocks the flame`() {
        (0..6).forEach { add(daysAgo = it) }
        assertTrue("streak_7" in SessionStore.checkMilestones(ctx).map { it.id })
    }

    @Test
    fun `updateLatest fills the reflection on the newest record`() {
        add()
        SessionStore.updateLatest(ctx, "😌", "quiet morning")
        val r = SessionStore.getSessions(ctx).first()
        assertEquals("😌", r.mood)
        assertEquals("quiet morning", r.note)
    }

    @Test
    fun `totals aggregate`() {
        add(minutes = 10); add(minutes = 15)
        assertEquals(2, SessionStore.totalSessions(ctx))
        assertEquals(25, SessionStore.totalMinutes(ctx))
    }

    @Test
    fun `legacy totals migrate exactly once`() {
        CleanPrefsRule.prefs().edit()
            .putInt("sessions", 3).putInt("total_minutes", 45).apply()
        SessionStore.migrateIfNeeded(ctx)
        assertEquals(1, SessionStore.totalSessions(ctx))
        assertEquals(45, SessionStore.totalMinutes(ctx))
        SessionStore.migrateIfNeeded(ctx)
        assertEquals(1, SessionStore.totalSessions(ctx))
    }
}
