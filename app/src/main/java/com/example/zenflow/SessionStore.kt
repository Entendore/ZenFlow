package com.example.zenflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class SessionRecord(
    val timestamp: Long,
    val title: String,
    val minutes: Int,
    val mix: String,
    val mood: String,
    val note: String
)

object SessionStore {

    private const val KEY_HISTORY = "history_json"
    private const val KEY_MILESTONES = "milestones"
    private const val MAX_RECORDS = 200

    /** Test seam — production always uses the real clock. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    @Volatile
    private var cache: List<SessionRecord>? = null

    internal fun clearCacheForTests() { cache = null }

    private fun prefs(context: Context) =
        context.getSharedPreferences("zen_stats", Context.MODE_PRIVATE)

    fun migrateIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getString(KEY_HISTORY, null) != null) return
        val sessions = p.getInt("sessions", 0)
        val minutes = p.getInt("total_minutes", 0)
        if (sessions > 0) {
            addSession(context, SessionRecord(clock(), "Earlier practice", minutes, "", "", ""))
        }
    }

    fun addSession(context: Context, record: SessionRecord) {
        val all = getSessions(context).toMutableList()
        all.add(0, record)
        while (all.size > MAX_RECORDS) all.removeAt(all.size - 1)
        persist(context, all)
    }

    fun updateLatest(context: Context, mood: String, note: String) {
        val all = getSessions(context).toMutableList()
        if (all.isNotEmpty()) {
            all[0] = all[0].copy(mood = mood, note = note)
            persist(context, all)
        }
    }

    fun getSessions(context: Context): List<SessionRecord> {
        cache?.let { return it }
        val json = prefs(context).getString(KEY_HISTORY, null)
        val parsed =
            if (json == null) emptyList()
            else runCatching { parse(json) }.getOrDefault(emptyList())
        cache = parsed
        return parsed
    }

    private fun parse(json: String): List<SessionRecord> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SessionRecord(
                o.optLong("t"), o.optString("title"), o.optInt("min"),
                o.optString("mix"), o.optString("mood"), o.optString("note")
            )
        }
    }

    private fun persist(context: Context, all: List<SessionRecord>) {
        val arr = JSONArray()
        all.forEach { r ->
            arr.put(
                JSONObject()
                    .put("t", r.timestamp).put("title", r.title).put("min", r.minutes)
                    .put("mix", r.mix).put("mood", r.mood).put("note", r.note)
            )
        }
        prefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply()
        cache = all
    }

    fun totalSessions(context: Context) = getSessions(context).size

    fun totalMinutes(context: Context) = getSessions(context).sumOf { it.minutes }

    private fun dayStart(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Consecutive practice days, ending today — or yesterday if today hasn't happened yet. */
    fun currentStreak(context: Context): Int {
        val now = clock()
        val days = getSessions(context).map { dayStart(it.timestamp) }.toSet()
        if (days.isEmpty()) return 0
        val todayStart = dayStart(now)
        val yesterdayStart = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }.let { dayStart(it.timeInMillis) }
        var cursor = when {
            todayStart in days -> todayStart
            yesterdayStart in days -> yesterdayStart
            else -> return 0
        }
        var streak = 0
        while (days.contains(cursor)) {
            streak++
            cursor = Calendar.getInstance().apply {
                timeInMillis = cursor
                add(Calendar.DAY_OF_YEAR, -1)
            }.let { dayStart(it.timeInMillis) }
            if (streak > 3650) break
        }
        return streak
    }

    /** Minutes per day for the last 7 days (index 0 = 6 days ago) — single pass. */
    fun weeklyMinutes(context: Context): Pair<FloatArray, List<String>> {
        val sessions = getSessions(context)
        val minutes = FloatArray(7)
        val letters = mutableListOf<String>()
        val dayNames = listOf("S", "M", "T", "W", "T", "F", "S")

        val cal = Calendar.getInstance()
        val bucket = HashMap<Long, Int>(7)
        for (i in 6 downTo 0) {
            cal.timeInMillis = clock()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val start = dayStart(cal.timeInMillis)
            bucket[start] = 6 - i
            letters.add(dayNames[cal.get(Calendar.DAY_OF_WEEK) - 1])
        }
        for (r in sessions) {
            val idx = bucket[dayStart(r.timestamp)] ?: continue
            minutes[idx] += r.minutes
        }
        return minutes to letters
    }

    data class Milestone(val id: String, val emoji: String, val title: String)

    val MILESTONES = listOf(
        Milestone("first", "🌱", "First meditation"),
        Milestone("sessions_10", "🧘", "10 sessions"),
        Milestone("sessions_50", "⭐", "50 sessions"),
        Milestone("streak_7", "🔥", "7-day streak"),
        Milestone("streak_30", "🌋", "30-day streak"),
        Milestone("minutes_60", "⏳", "1 hour of calm"),
        Milestone("minutes_600", "🏆", "10 hours of calm")
    )

    fun achievedIds(context: Context): Set<String> =
        prefs(context).getString(KEY_MILESTONES, "")
            ?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    fun checkMilestones(context: Context): List<Milestone> {
        val done = achievedIds(context).toMutableSet()
        val sessions = totalSessions(context)
        val minutes = totalMinutes(context)
        val streak = currentStreak(context)
        val newly = MILESTONES.filter { it.id !in done && when (it.id) {
            "first" -> sessions >= 1
            "sessions_10" -> sessions >= 10
            "sessions_50" -> sessions >= 50
            "streak_7" -> streak >= 7
            "streak_30" -> streak >= 30
            "minutes_60" -> minutes >= 60
            "minutes_600" -> minutes >= 600
            else -> false
        } }
        if (newly.isNotEmpty()) {
            done.addAll(newly.map { it.id })
            prefs(context).edit().putString(KEY_MILESTONES, done.joinToString(",")).apply()
        }
        return newly
    }
}
