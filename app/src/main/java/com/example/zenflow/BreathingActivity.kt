package com.example.zenflow

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import java.util.Locale

class BreathingActivity : AppCompatActivity() {

    private data class Phase(val key: String, val label: String, val seconds: Int, val targetScale: Float)
    private class Pattern(val id: String, val label: String, val phases: List<Phase>)

    private val patterns = listOf(
        Pattern("calm", "🧘 Calm", listOf(
            Phase("in", "Breathe In", 4, 1.7f), Phase("hold", "Hold", 4, 1.7f),
            Phase("out", "Breathe Out", 6, 1.0f), Phase("rest", "Rest", 2, 1.0f))),
        Pattern("box", "📦 Box 4-4-4-4", listOf(
            Phase("in", "Breathe In", 4, 1.7f), Phase("hold", "Hold", 4, 1.7f),
            Phase("out", "Breathe Out", 4, 1.0f), Phase("rest", "Rest", 4, 1.0f))),
        Pattern("sleep", "😴 Sleep 4-7-8", listOf(
            Phase("in", "Breathe In", 4, 1.7f), Phase("hold", "Hold", 7, 1.7f),
            Phase("out", "Breathe Out", 8, 1.0f))),
        Pattern("coherent", "🫁 Coherent 5-5", listOf(
            Phase("in", "Breathe In", 5, 1.7f), Phase("out", "Breathe Out", 5, 1.0f)))
    )

    private lateinit var circle: View
    private lateinit var tvPhase: TextView
    private lateinit var tvSeconds: TextView
    private lateinit var tvCycles: TextView
    private lateinit var tvLegend: TextView
    private lateinit var tvSessionTimer: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnSound: ImageButton
    private lateinit var btnHaptic: ImageButton

    private val prefs by lazy { getSharedPreferences("zen_stats", MODE_PRIVATE) }

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private var running = false
    private var cycles = 0
    private var soundOn = true
    private var hapticsOn = true
    private var selectedPatternId = "calm"
    private var durationMinutes = 0
    private var activePhases: List<Phase> = patterns.first().phases
    private var stopRequested = false

    private var secondsTimer: CountDownTimer? = null
    private var sessionTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_breathing)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        circle = findViewById(R.id.breathCircle)
        tvPhase = findViewById(R.id.tvPhase)
        tvSeconds = findViewById(R.id.tvSeconds)
        tvCycles = findViewById(R.id.tvCycles)
        tvLegend = findViewById(R.id.tvLegend)
        tvSessionTimer = findViewById(R.id.tvSessionTimer)
        btnToggle = findViewById(R.id.btnToggleBreathing)
        btnSound = findViewById(R.id.btnSoundToggle)
        btnHaptic = findViewById(R.id.btnHapticToggle)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        btnToggle.setOnClickListener { if (running) stopBreathing() else startBreathing() }

        soundOn = prefs.getBoolean("breath_sounds", true)
        hapticsOn = prefs.getBoolean("breath_haptics", true)
        selectedPatternId = prefs.getString("breath_pattern", "calm") ?: "calm"
        if (selectedPatternId == "custom" && customPattern() == null) selectedPatternId = "calm"
        durationMinutes = prefs.getInt("breath_duration", 0)
        cycles = savedInstanceState?.getInt("cycles", 0) ?: 0
        tvCycles.text = getString(R.string.cycles_fmt, cycles)

        btnSound.setOnClickListener {
            soundOn = !soundOn
            prefs.edit().putBoolean("breath_sounds", soundOn).apply()
            updateSoundIcon()
        }
        btnHaptic.setOnClickListener {
            hapticsOn = !hapticsOn
            prefs.edit().putBoolean("breath_haptics", hapticsOn).apply()
            updateHapticIcon()
            if (hapticsOn) vibrate(40)
        }
        updateSoundIcon()
        updateHapticIcon()

        buildPatternChips()
        buildDurationChips()
        updateLegend()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("cycles", cycles)
    }

    /* ---------- patterns ---------- */

    private fun customPattern(): Pattern? {
        val raw = prefs.getString("breath_custom", null) ?: return null
        val p = raw.split(",").map { it.toIntOrNull() ?: 0 }
        if (p.size != 4) return null
        val (inhale, hold, exhale, rest) = p
        if (inhale <= 0 || exhale <= 0) return null
        val phases = mutableListOf(Phase("in", "Breathe In", inhale, 1.7f))
        if (hold > 0) phases.add(Phase("hold", "Hold", hold, 1.7f))
        phases.add(Phase("out", "Breathe Out", exhale, 1.0f))
        if (rest > 0) phases.add(Phase("rest", "Rest", rest, 1.0f))
        return Pattern("custom", "🛠️ Custom", phases)
    }

    private fun activePattern(): Pattern {
        if (selectedPatternId == "custom") customPattern()?.let { return it }
        return patterns.firstOrNull { it.id == selectedPatternId } ?: patterns.first()
    }

    private fun buildPatternChips() {
        val row = findViewById<LinearLayout>(R.id.patternChips)
        row.removeAllViews()
        val entries = patterns.map { it.id to it.label } + listOf("custom" to "🛠️ Custom")
        entries.forEach { (id, label) ->
            row.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    if (id == "custom") showPatternBuilder()
                    else selectPattern(id)
                }
            })
        }
        refreshPatternChips()
    }

    private fun refreshPatternChips() {
        val row = findViewById<LinearLayout>(R.id.patternChips)
        val entries = patterns.map { it.id } + listOf("custom")
        entries.forEachIndexed { index, id ->
            val chip = row.getChildAt(index) as TextView
            val selected = id == selectedPatternId
            chip.background = ContextCompat.getDrawable(
                this, if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            chip.setTextColor(if (selected) Color.WHITE else Color.parseColor("#C2CBEF"))
        }
    }

    private fun selectPattern(id: String) {
        if (id == selectedPatternId) return
        selectedPatternId = id
        prefs.edit().putString("breath_pattern", id).apply()
        refreshPatternChips()
        updateLegend()
        restartIfRunning()
    }

    private fun showPatternBuilder() {
        val saved = prefs.getString("breath_custom", "4,4,6,2") ?: "4,4,6,2"
        val parts = saved.split(",").map { it.toIntOrNull() ?: 0 }
        val values = intArrayOf(
            parts.getOrElse(0) { 4 }, parts.getOrElse(1) { 4 },
            parts.getOrElse(2) { 6 }, parts.getOrElse(3) { 2 }
        )
        val names = listOf("Breathe in", "Hold", "Breathe out", "Rest")
        val maxes = intArrayOf(10, 10, 12, 10)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val valueViews = mutableListOf<TextView>()
        names.forEachIndexed { idx, name ->
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@BreathingActivity).apply {
                    text = name
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@BreathingActivity).apply {
                    text = "${values[idx]} s"
                    textSize = 14f
                    setTextColor(Color.parseColor("#6C63FF"))
                    setPadding(dp(8), 0, 0, 0)
                    valueViews.add(this)
                })
            })
            box.addView(Slider(this).apply {
                valueFrom = if (idx == 0 || idx == 2) 1f else 0f
                valueTo = maxes[idx].toFloat()
                stepSize = 1f
                value = values[idx].toFloat().coerceIn(valueFrom, valueTo)
                labelBehavior = LabelFormatter.LABEL_GONE
                trackColorActive = ColorStateList.valueOf(Color.parseColor("#6C63FF"))
                thumbColor = ColorStateList.valueOf(Color.parseColor("#6C63FF"))
                addOnChangeListener { _, v, fromUser ->
                    if (!fromUser) return@addOnChangeListener
                    values[idx] = v.toInt()
                    valueViews[idx].text = "${v.toInt()} s"
                }
            })
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.builder_title)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                if (values[0] < 1 || values[2] < 1) {
                    Toast.makeText(this, R.string.builder_need_io, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.edit().putString("breath_custom", values.joinToString(",")).apply()
                selectedPatternId = "custom"
                prefs.edit().putString("breath_pattern", "custom").apply()
                refreshPatternChips()
                updateLegend()
                restartIfRunning()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* ---------- timed session ---------- */

    private val durationOptions = listOf(0, 2, 5, 10)

    private fun buildDurationChips() {
        val row = findViewById<LinearLayout>(R.id.durationChips)
        durationOptions.forEach { minutes ->
            row.addView(TextView(this).apply {
                text = if (minutes == 0) "∞" else getString(R.string.chip_minutes_fmt, minutes)
                textSize = 12f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener { selectDuration(minutes) }
            })
        }
        refreshDurationChips()
    }

    private fun refreshDurationChips() {
        val row = findViewById<LinearLayout>(R.id.durationChips)
        durationOptions.forEachIndexed { index, minutes ->
            val chip = row.getChildAt(index) as TextView
            val selected = minutes == durationMinutes
            chip.background = ContextCompat.getDrawable(
                this, if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            chip.setTextColor(if (selected) Color.WHITE else Color.parseColor("#C2CBEF"))
        }
    }

    private fun selectDuration(minutes: Int) {
        durationMinutes = minutes
        prefs.edit().putInt("breath_duration", minutes).apply()
        refreshDurationChips()
        if (running) {
            sessionTimer?.cancel()
            if (minutes > 0) startSessionTimer() else {
                stopRequested = false
                tvSessionTimer.visibility = View.GONE
            }
        }
    }

    private fun startSessionTimer() {
        stopRequested = false
        tvSessionTimer.visibility = View.VISIBLE
        sessionTimer = object : CountDownTimer(durationMinutes * 60_000L, 1000) {
            override fun onTick(msUntilFinished: Long) {
                val m = msUntilFinished / 60000
                val s = (msUntilFinished / 1000) % 60
                tvSessionTimer.text = getString(R.string.session_left_fmt, m, s)
            }

            override fun onFinish() {
                stopRequested = true
                tvSessionTimer.text = getString(R.string.finishing_breath)
            }
        }.start()
    }

    /* ---------- session engine ---------- */

    private fun updateLegend() {
        tvLegend.text = activePattern().phases
            .joinToString(" · ") { "${it.seconds} s ${it.label.lowercase()}" }
    }

    private fun startBreathing() {
        running = true
        cycles = 0
        tvCycles.text = getString(R.string.cycles_fmt, cycles)
        btnToggle.text = getString(R.string.btn_stop)
        activePhases = activePattern().phases
        if (durationMinutes > 0) startSessionTimer()
        else { tvSessionTimer.visibility = View.GONE; stopRequested = false }
        runPhase(0)
    }

    private fun runPhase(index: Int) {
        if (!running) return
        val phase = activePhases[index]
        tvPhase.text = phase.label
        playCue(phase)

        circle.animate()
            .scaleX(phase.targetScale)
            .scaleY(phase.targetScale)
            .setDuration(phase.seconds * 1000L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withLayer()
            .withEndAction {
                if (!running) return@withEndAction
                if (stopRequested) { stopBreathing(celebrate = true); return@withEndAction }
                if (index == activePhases.lastIndex) {
                    cycles++
                    tvCycles.text = getString(R.string.cycles_fmt, cycles)
                    runPhase(0)
                } else {
                    runPhase(index + 1)
                }
            }
            .start()

        secondsTimer?.cancel()
        tvSeconds.text = phase.seconds.toString()
        secondsTimer = object : CountDownTimer(phase.seconds * 1000L, 1000) {
            override fun onTick(msUntilFinished: Long) {
                tvSeconds.text = ((msUntilFinished / 1000) + 1).toString()
            }

            override fun onFinish() { /* next phase updates the labels */ }
        }.start()
    }

    private fun playCue(phase: Phase) {
        when (phase.key) {
            "in" -> {
                if (soundOn) SynthPlayer.play(SynthVoices.INHALE, 0.9f)
                vibrate(60)
            }
            "hold" -> {
                if (soundOn) SynthPlayer.play(SynthVoices.HOLD, 0.7f)
                vibrate(25)
            }
            "out" -> {
                if (soundOn) SynthPlayer.play(SynthVoices.EXHALE, 0.9f)
                vibrate(60)
            }
        }
    }

    private fun stopBreathing(celebrate: Boolean = false) {
        running = false
        stopRequested = false
        secondsTimer?.cancel()
        sessionTimer?.cancel()
        tvSessionTimer.visibility = View.GONE
        circle.animate().cancel()
        circle.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(600)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withLayer()
            .start()
        tvPhase.text = getString(R.string.breath_done)
        tvSeconds.text = ""
        btnToggle.text = getString(R.string.btn_start)
        if (celebrate) {
            SynthPlayer.play(SynthVoices.COMPLETE, 0.9f)
            vibrate(80)
        }
    }

    private fun restartIfRunning() {
        if (running) {
            stopBreathing()
            startBreathing()
        }
    }

    /* ---------- cues ---------- */

    private fun updateSoundIcon() {
        btnSound.setImageResource(
            if (soundOn) R.drawable.ic_sound_on else R.drawable.ic_sound_off
        )
    }

    private fun updateHapticIcon() {
        btnHaptic.setImageResource(
            if (hapticsOn) R.drawable.ic_haptic else R.drawable.ic_haptic_off
        )
    }

    private fun vibrate(ms: Long) {
        if (!hapticsOn) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(ms)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        secondsTimer?.cancel()
        sessionTimer?.cancel()
        super.onDestroy()
    }
}
