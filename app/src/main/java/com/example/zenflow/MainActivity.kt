package com.example.zenflow

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("zen_stats", MODE_PRIVATE) }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showReminderTimePicker()
            else findViewById<SwitchMaterial>(R.id.switchReminder).isChecked = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        SessionStore.migrateIfNeeded(this)

        findViewById<TextView>(R.id.tvGreeting).text = greeting()
        findViewById<TextView>(R.id.tvDate).text =
            SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())

        val recycler = findViewById<RecyclerView>(R.id.recyclerMeditations)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.setHasFixedSize(true)
        recycler.adapter = MeditationAdapter(meditations()) { meditation ->
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_TITLE, meditation.title)
                    .putExtra(PlayerActivity.EXTRA_MINUTES, meditation.minutes)
            )
        }

        findViewById<View>(R.id.cardBreathing).setOnClickListener {
            startActivity(Intent(this, BreathingActivity::class.java))
        }
        findViewById<TextView>(R.id.tvHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<View>(R.id.dailyRow).setOnClickListener {
            startActivity(Intent(this, ContemplationActivity::class.java))
        }
        showDailyContemplation()

        findViewById<TextView>(R.id.tvOfflineBadge).setOnLongClickListener {
            val v = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "?"
            Toast.makeText(this, getString(R.string.version_toast, v), Toast.LENGTH_LONG).show()
            true
        }
        maybeShowFirstRunNudge()

        setupReminderSwitch()
    }

    override fun onResume() {
        super.onResume()
        showStats()
        showWeeklyChart()
    }

    private fun showStats() {
        findViewById<TextView>(R.id.tvTotalMinutes).text =
            SessionStore.totalMinutes(this).toString()
        findViewById<TextView>(R.id.tvSessions).text =
            SessionStore.totalSessions(this).toString()
        findViewById<TextView>(R.id.tvStreak).text =
            SessionStore.currentStreak(this).toString()
    }

    private fun showWeeklyChart() {
        val (minutes, labels) = SessionStore.weeklyMinutes(this)
        findViewById<WeeklyBarChart>(R.id.weekChart).setData(minutes, labels)
        findViewById<TextView>(R.id.tvWeekTotal).text =
            getString(R.string.week_total_fmt, minutes.sum().toInt())
    }

    private fun showDailyContemplation() {
        findViewById<TextView>(R.id.tvDailyText).text = ContemplationLibrary.dailyPick().text
    }

    private fun greeting(): String =
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> getString(R.string.greeting_morning)
            in 12..17 -> getString(R.string.greeting_afternoon)
            else -> getString(R.string.greeting_evening)
        }

    private fun maybeShowFirstRunNudge() {
        if (prefs.getBoolean("onboarded", false) || SessionStore.totalSessions(this) > 0) return
        prefs.edit().putBoolean("onboarded", true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.onboarding_title)
            .setMessage(R.string.onboarding_msg)
            .setPositiveButton(R.string.onboarding_start) { _, _ ->
                startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_TITLE, "Deep Sleep")
                        .putExtra(PlayerActivity.EXTRA_MINUTES, 15)
                )
            }
            .setNegativeButton(R.string.onboarding_explore, null)
            .show()
    }

    private fun meditations() = listOf(
        Meditation("Deep Calm", "Release tension and settle into stillness", 10, "🧘", "#5B8DEF"),
        Meditation("Morning Focus", "Sharpen your attention for the day ahead", 5, "🌅", "#F2994A"),
        Meditation("Deep Sleep", "Unwind and let sleep come naturally", 15, "🌙", "#9B51E0"),
        Meditation("Anxiety Relief", "Ground yourself and soften worry", 8, "🍃", "#2FBF71"),
        Meditation("Body Scan", "Relax from head to toe", 12, "💆", "#3BB4C1"),
        Meditation("Gratitude", "Open your heart to the present moment", 7, "💜", "#F06292")
    )

    private fun setupReminderSwitch() {
        val switch = findViewById<SwitchMaterial>(R.id.switchReminder)
        switch.isChecked = prefs.getBoolean("reminder_enabled", false)
        updateReminderSub()
        switch.setOnCheckedChangeListener { _, checked ->
            when {
                checked && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED ->
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                checked -> showReminderTimePicker()
                else -> {
                    prefs.edit().putBoolean("reminder_enabled", false).apply()
                    ReminderWorker.cancel(this)
                    updateReminderSub()
                }
            }
        }
    }

    private fun showReminderTimePicker() {
        val hour = prefs.getInt("reminder_hour", 8)
        val minute = prefs.getInt("reminder_minute", 0)
        TimePickerDialog(this, { h, m ->
            prefs.edit()
                .putBoolean("reminder_enabled", true)
                .putInt("reminder_hour", h)
                .putInt("reminder_minute", m)
                .apply()
            ReminderWorker.schedule(this, h, m)
            findViewById<SwitchMaterial>(R.id.switchReminder).isChecked = true
            updateReminderSub()
        }, hour, minute, false).apply {
            setOnCancelListener {
                if (!prefs.getBoolean("reminder_enabled", false)) {
                    findViewById<SwitchMaterial>(R.id.switchReminder).isChecked = false
                }
            }
            show()
        }
    }

    private fun updateReminderSub() {
        val sub = findViewById<TextView>(R.id.tvReminderSub)
        if (prefs.getBoolean("reminder_enabled", false)) {
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, prefs.getInt("reminder_hour", 8))
                set(Calendar.MINUTE, prefs.getInt("reminder_minute", 0))
            }
            sub.text = getString(
                R.string.reminder_every_day_at,
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(c.time)
            )
        } else {
            sub.text = getString(R.string.reminder_off)
        }
    }
}
