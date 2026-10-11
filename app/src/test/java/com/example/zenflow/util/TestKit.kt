package com.example.zenflow.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.zenflow.SessionRecord
import com.example.zenflow.SessionStore
import com.example.zenflow.SoundCatalog
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.Calendar

class CleanPrefsRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            reset()
            try { base.evaluate() } finally { reset() }
        }
    }

    companion object {
        fun prefs() = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("zen_stats", Context.MODE_PRIVATE)

        private fun reset() {
            prefs().edit().clear().commit()
            SoundCatalog.clearIndexCache()
            SessionStore.clearCacheForTests()
            SessionStore.clock = { System.currentTimeMillis() }
        }
    }
}

object Fixtures {

    fun ts(daysAgo: Int, hour: Int = 12, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -daysAgo)
    }.timeInMillis

    fun record(
        title: String = "Test",
        minutes: Int = 10,
        timestamp: Long = ts(0),
        mix: String = "",
        mood: String = "",
        note: String = ""
    ) = SessionRecord(timestamp, title, minutes, mix, mood, note)

    fun add(vararg sessions: SessionRecord) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        sessions.forEach { SessionStore.addSession(ctx, it) }
    }
}
