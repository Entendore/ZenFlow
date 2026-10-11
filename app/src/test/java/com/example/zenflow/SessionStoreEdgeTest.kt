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
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionStoreEdgeTest {

    @get:Rule val clean = CleanPrefsRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun fixedClock(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `streak crosses the year boundary`() {
        val jan1Noon = fixedClock(2025, Calendar.JANUARY, 1)
        SessionStore.clock = { jan1Noon }
        Fixtures.add(
            Fixtures.record(timestamp = fixedClock(2024, Calendar.DECEMBER, 31, 20)),
            Fixtures.record(timestamp = fixedClock(2025, Calendar.JANUARY, 1, 9))
        )
        assertEquals(2, SessionStore.currentStreak(ctx))
    }

    @Test
    fun `several sessions on one day count the day once`() {
        Fixtures.add(
            Fixtures.record(timestamp = Fixtures.ts(0, 8)),
            Fixtures.record(timestamp = Fixtures.ts(0, 20)),
            Fixtures.record(timestamp = Fixtures.ts(1))
        )
        assertEquals(2, SessionStore.currentStreak(ctx))
    }

    @Test
    fun `weekly buckets exclude anything older than six days`() {
        Fixtures.add(
            Fixtures.record(minutes = 50, timestamp = Fixtures.ts(6)),
            Fixtures.record(minutes = 40, timestamp = Fixtures.ts(7))
        )
        val (minutes, _) = SessionStore.weeklyMinutes(ctx)
        assertEquals(50f, minutes[0], 0.001f)
        assertEquals(0f, minutes.sum() - 50f, 0.001f)
    }

    @Test
    fun `corrupted history json fails safe and self-heals on the next write`() {
        CleanPrefsRule.prefs().edit().putString("history_json", "not json at all {").commit()
        assertTrue(SessionStore.getSessions(ctx).isEmpty())
        assertEquals(0, SessionStore.totalSessions(ctx))
        Fixtures.add(Fixtures.record(title = "after corruption"))
        val all = SessionStore.getSessions(ctx)
        assertEquals(1, all.size)
        assertEquals("after corruption", all.first().title)
    }

    @Test
    fun `reflection update on empty history is a safe no-op`() {
        SessionStore.updateLatest(ctx, "😌", "nothing yet")
        assertTrue(SessionStore.getSessions(ctx).isEmpty())
    }
}
