package com.example.zenflow

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.zenflow.audio.SoundStore
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    private lateinit var progressRing: ProgressBar
    private lateinit var tvTimeRemaining: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var mixerPanel: LinearLayout
    private lateinit var ambient: AmbientMixer

    private var sessionTitle = "Meditation"
    private var totalSeconds = 0
    private var remainingSeconds = 0
    private var timer: CountDownTimer? = null
    private var isPlaying = false
    private var sessionFinished = false
    private var sleepMinutes = 0

    private var pendingMood = ""
    private lateinit var noteInput: EditText
    private var newlyMilestones: List<SessionStore.Milestone> = emptyList()

    private val mix = LinkedHashMap<String, Int>()
    private val prefs by lazy { getSharedPreferences("zen_stats", MODE_PRIVATE) }

    private val sleepUiHandler = Handler(Looper.getMainLooper())
    private val sleepUiTicker = object : Runnable {
        override fun run() {
            val remaining = ambient.sleepRemainingMs()
            if (remaining <= 0) return
            val m = remaining / 60000
            val s = (remaining / 1000) % 60
            tvStatus.text = getString(R.string.status_fading_fmt, m, s)
            sleepUiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ambient = AmbientMixer(this)
        loadMix()

        sessionTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Meditation"
        sleepMinutes = prefs.getInt(PREF_SLEEP, 0)
        ambient.setMasterVolume(prefs.getInt(PREF_MASTER, 100))

        val minutes = intent.getIntExtra(EXTRA_MINUTES, 10)
        totalSeconds = minutes * 60
        remainingSeconds = totalSeconds

        progressRing = findViewById(R.id.progressRing)
        progressRing.max = totalSeconds
        tvTimeRemaining = findViewById(R.id.tvTimeRemaining)
        tvStatus = findViewById(R.id.tvStatus)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        mixerPanel = findViewById(R.id.mixerPanel)
        findViewById<TextView>(R.id.tvTitle).text = sessionTitle

        btnPlayPause.setOnClickListener {
            when {
                sessionFinished -> toggleAmbientOnly()
                isPlaying -> pause()
                else -> start()
            }
        }
        findViewById<ImageButton>(R.id.btnReset).setOnClickListener { reset() }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        setupMasterSlider()
        rebuildSoundChips()
        buildSleepChips()
        PresetStore.seedIfFirstRun(this)
        buildPresetChips()
        rebuildMixerPanel()
        updateTimeText()

        savedInstanceState?.let { s ->
            remainingSeconds = s.getInt("remaining", totalSeconds)
            sessionFinished = s.getBoolean("finished", false)
            progressRing.progress = totalSeconds - remainingSeconds
            updateTimeText()
            if (sessionFinished) tvStatus.text = getString(R.string.status_session_complete)
            else if (s.getBoolean("playing", false)) start()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("remaining", remainingSeconds)
        outState.putBoolean("playing", isPlaying)
        outState.putBoolean("finished", sessionFinished)
    }

    /* ---------- playback ---------- */

    private fun start() {
        isPlaying = true
        sessionFinished = false
        btnPlayPause.setImageResource(R.drawable.ic_pause)
        tvStatus.text = when {
            mix.isEmpty() -> getString(R.string.status_silence)
            mix.size == 1 -> getString(R.string.status_playing_one, SoundCatalog.labelFor(this, mix.keys.first()))
            else -> getString(R.string.status_mixing_n, mix.size)
        }
        if (sleepMinutes > 0) ambient.cancelSessionFadeOut()
        ambient.startAll()

        timer = object : CountDownTimer(remainingSeconds * 1000L, 1000) {
            override fun onTick(msUntilFinished: Long) {
                remainingSeconds = (msUntilFinished / 1000).toInt()
                progressRing.progress = totalSeconds - remainingSeconds
                updateTimeText()
                if (sleepMinutes == 0 && remainingSeconds <= END_FADE_SECONDS) {
                    ambient.beginSessionFadeOut(remainingSeconds * 1000L)
                }
            }

            override fun onFinish() {
                remainingSeconds = 0
                progressRing.progress = totalSeconds
                updateTimeText()
                onSessionComplete()
            }
        }.start()
    }

    private fun pause() {
        isPlaying = false
        timer?.cancel()
        ambient.pauseAll()
        btnPlayPause.setImageResource(R.drawable.ic_play)
        tvStatus.text = getString(R.string.status_paused)
    }

    private fun reset() {
        timer?.cancel()
        stopSleepUiTicker()
        ambient.pauseAll()
        ambient.cancelSessionFadeOut()
        ambient.cancelSleepFade()
        ambient.resetAll()
        isPlaying = false
        sessionFinished = false
        remainingSeconds = totalSeconds
        progressRing.progress = 0
        updateTimeText()
        btnPlayPause.setImageResource(R.drawable.ic_play)
        tvStatus.text = getString(R.string.status_ready)
    }

    private fun updateTimeText() {
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        tvTimeRemaining.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    /* ---------- completion, records & milestones ---------- */

    private fun onSessionComplete() {
        isPlaying = false
        sessionFinished = true
        btnPlayPause.setImageResource(R.drawable.ic_play)

        newlyMilestones = saveSession()

        if (sleepMinutes > 0) {
            SynthPlayer.play(SynthVoices.COMPLETE, 0.6f)
            ambient.beginSleepFade(sleepMinutes * 60_000L) { onSleepFinished() }
            startSleepUiTicker()
            tvStatus.text = getString(R.string.status_session_complete_sleep)
        } else {
            ambient.pauseAll()
            ambient.cancelSessionFadeOut()
            SynthPlayer.play(SynthVoices.COMPLETE)
            tvStatus.text = getString(R.string.status_session_complete)

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_well_done))
                .setMessage(completionMessage())
                .setView(buildReflectionView())
                .setPositiveButton(getString(R.string.btn_finish)) { _, _ ->
                    SessionStore.updateLatest(this, pendingMood, noteInput.text.toString().trim())
                    finish()
                }
                .setNegativeButton(getString(R.string.btn_keep_sitting), null)
                .show()
        }
    }

    private fun saveSession(): List<SessionStore.Milestone> {
        SessionStore.addSession(
            this,
            SessionRecord(
                System.currentTimeMillis(), sessionTitle, totalSeconds / 60,
                MixSync.encode(mix, SoundCatalog.orderedKeys(this)), "", ""
            )
        )
        prefs.edit()
            .putInt("total_minutes", prefs.getInt("total_minutes", 0) + totalSeconds / 60)
            .putInt("sessions", prefs.getInt("sessions", 0) + 1)
            .apply()
        return SessionStore.checkMilestones(this)
    }

    private fun completionMessage(): String {
        val mins = totalSeconds / 60
        val base = resources.getQuantityString(R.plurals.session_minutes, mins, mins)
        val c = ContemplationLibrary.contemplationForMix(mix.keys.toList())
        val quote = "\n\n💬 \u201C${c.text}\u201D"
        return if (newlyMilestones.isEmpty()) {
            base + quote + getString(R.string.dialog_see_you)
        } else {
            base + quote + "\n\n" + newlyMilestones.joinToString("\n") {
                getString(R.string.milestone_fmt, it.emoji, it.title)
            }
        }
    }

    private fun buildReflectionView(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.dialog_how_feel)
            textSize = 13f
            setTextColor(Color.parseColor("#8A8AA3"))
        })
        val moodRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        listOf("😊", "😌", "😐", "😔", "😣").forEach { m ->
            moodRow.addView(TextView(this).apply {
                text = m
                textSize = 26f
                setPadding(dp(10), dp(2), dp(10), dp(2))
                setOnClickListener {
                    pendingMood = if (pendingMood == m) "" else m
                    for (i in 0 until moodRow.childCount) {
                        val c = moodRow.getChildAt(i) as TextView
                        c.alpha =
                            if (pendingMood.isEmpty() || c.text.toString() == pendingMood) 1f else 0.25f
                    }
                }
            })
        }
        box.addView(moodRow)
        noteInput = EditText(this).apply {
            hint = getString(R.string.dialog_note_hint)
            textSize = 14f
        }
        box.addView(noteInput)
        return box
    }

    private fun onSleepFinished() {
        stopSleepUiTicker()
        btnPlayPause.setImageResource(R.drawable.ic_play)
        tvStatus.text = getString(R.string.status_sleep_finished)
    }

    private fun toggleAmbientOnly() {
        if (ambient.isAudioActive) {
            ambient.pauseAll()
            stopSleepUiTicker()
            btnPlayPause.setImageResource(R.drawable.ic_play)
            tvStatus.text = getString(R.string.status_sound_paused)
        } else {
            ambient.startAll()
            btnPlayPause.setImageResource(R.drawable.ic_pause)
            if (sessionFinished && sleepMinutes > 0 && ambient.sleepRemainingMs() <= 0L) {
                ambient.beginSleepFade(sleepMinutes * 60_000L) { onSleepFinished() }
            }
            if (ambient.sleepRemainingMs() > 0L) startSleepUiTicker()
            tvStatus.text = getString(R.string.status_sound_on)
        }
    }

    /* ---------- master volume ---------- */

    private fun setupMasterSlider() {
        val slider = findViewById<Slider>(R.id.masterSlider)
        slider.valueFrom = 0f
        slider.valueTo = 100f
        slider.stepSize = 5f
        slider.value = prefs.getInt(PREF_MASTER, 100).toFloat().coerceIn(0f, 100f)
        slider.labelBehavior = LabelFormatter.LABEL_GONE
        slider.trackColorActive = ColorStateList.valueOf(Color.parseColor("#A5A6F6"))
        slider.trackColorInactive = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
        slider.thumbColor = ColorStateList.valueOf(Color.WHITE)
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            ambient.setMasterVolume(value.toInt())
            prefs.edit().putInt(PREF_MASTER, value.toInt()).apply()
        }
    }

    /* ---------- sound picker & mixer ---------- */

    private fun rebuildSoundChips() {
        val row = findViewById<LinearLayout>(R.id.soundChipRow)
        row.removeAllViews()
        (AMBIENT_SOUNDS + SoundStore.imports(this)
            .map { AmbientSound(it.key, it.name, "🎧") }).forEach { sound ->
            row.addView(TextView(this).apply {
                text = "${sound.emoji} ${sound.label}"
                tag = sound.key
                textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener { toggleSound(sound) }
                setOnLongClickListener {
                    Toast.makeText(
                        context, SoundCatalog.describe(context, sound.key), Toast.LENGTH_LONG
                    ).show()
                    true
                }
            })
        }
        row.addView(TextView(this).apply {
            text = "💭 Contemplate"
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_chip)
            setTextColor(Color.parseColor("#C9CBEE"))
            contentDescription = getString(R.string.cd_contemplate)
            setOnClickListener { showContemplationDialog() }
        })
        row.addView(TextView(this).apply {
            text = "📚 Library"
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_chip)
            setTextColor(Color.parseColor("#C9CBEE"))
            contentDescription = getString(R.string.cd_library)
            setOnClickListener {
                startActivity(Intent(this@PlayerActivity, SoundLibraryActivity::class.java))
            }
        })
        refreshChips()
    }

    private fun toggleSound(sound: AmbientSound) {
        if (sound.key in mix) {
            mix.remove(sound.key)
            ambient.removeLayer(sound.key)
            tvStatus.text = getString(R.string.status_removed, sound.label)
        } else {
            if (!SoundCatalog.hasSounds(this, sound.key)) {
                Toast.makeText(
                    this, getString(R.string.toast_no_clips, sound.key), Toast.LENGTH_LONG
                ).show()
                return
            }
            if (mix.size >= MAX_MIX_LAYERS) {
                Toast.makeText(
                    this, getString(R.string.toast_cap, MAX_MIX_LAYERS), Toast.LENGTH_SHORT
                ).show()
                return
            }
            val vol = prefs.getInt("vol_${sound.key}", 60)
            mix[sound.key] = vol
            ambient.addLayer(sound.key, vol)
            tvStatus.text = getString(R.string.status_added, sound.label)
        }
        saveMix()
        refreshChips()
        rebuildMixerPanel()
    }

    private fun refreshChips() {
        val row = findViewById<LinearLayout>(R.id.soundChipRow)
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i) as? TextView ?: continue
            val key = chip.tag as? String ?: continue
            val selected = key in mix
            chip.background = ContextCompat.getDrawable(
                this, if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            chip.setTextColor(if (selected) Color.WHITE else Color.parseColor("#A6A9CF"))
        }
    }

    private fun rebuildMixerPanel() {
        mixerPanel.removeAllViews()
        (AMBIENT_SOUNDS.map { it to (it.key in mix) } +
            SoundStore.imports(this).map {
                AmbientSound(it.key, it.name, "🎧") to (it.key in mix)
            })
            .filter { it.second }.forEach { mixerPanel.addView(buildMixerRow(it.first)) }
        mixerPanel.visibility = if (mix.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun buildMixerRow(sound: AmbientSound): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val label = TextView(this).apply {
            text = "${sound.emoji} ${sound.label}"
            textSize = 13f
            setTextColor(Color.parseColor("#C9CBEE"))
            layoutParams = LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val slider = Slider(this).apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 5f
            value = mix[sound.key]?.toFloat() ?: 60f
            labelBehavior = LabelFormatter.LABEL_GONE
            trackColorActive = ColorStateList.valueOf(Color.parseColor("#A5A6F6"))
            trackColorInactive = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
            thumbColor = ColorStateList.valueOf(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                mix[sound.key] = value.toInt()
                ambient.setLayerVolume(sound.key, value.toInt())
                prefs.edit().putInt("vol_${sound.key}", value.toInt()).apply()
            }
        }

        val remove = TextView(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#8F93BE"))
            setPadding(dp(14), dp(8), dp(4), dp(8))
            contentDescription = getString(R.string.cd_remove_from_mix, sound.label)
            setOnClickListener { toggleSound(sound) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(label)
        row.addView(slider)
        row.addView(remove)
        return row
    }

    private fun showContemplationDialog() {
        val c = ContemplationLibrary.contemplationForMix(mix.keys.toList())
        val message = buildString {
            append("\u201C${c.text}\u201D")
            if (c.sub.isNotBlank()) append("\n\n").append(c.sub)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.contemplation_title)
            .setMessage(message)
            .setPositiveButton(R.string.btn_another) { d, _ ->
                d.dismiss()
                showContemplationDialog()
            }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }

    /* ---------- presets ---------- */

    private fun buildPresetChips() {
        val row = findViewById<LinearLayout>(R.id.presetChipRow)
        row.removeAllViews()
        PresetStore.list(this).forEach { preset ->
            row.addView(TextView(this).apply {
                text = preset.name
                textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_chip)
                setTextColor(Color.parseColor("#C9CBEE"))
                setOnClickListener { applyPreset(preset) }
                setOnLongClickListener {
                    showPresetManageDialog(preset, { mix }, { applyPreset(it) }, { buildPresetChips() })
                    true
                }
            })
        }
        row.addView(TextView(this).apply {
            text = "＋ Save"
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_chip)
            setTextColor(Color.parseColor("#A5A6F6"))
            setOnClickListener { showPresetSaveDialog(mix) { buildPresetChips() } }
        })
    }

    private fun applyPreset(preset: MixPreset) {
        val (encoded, skipped) = PresetStore.encodeMix(this, preset.mix)
        prefs.edit().putString(PREF_MIX, encoded).apply()
        syncMixWithPrefs()
        tvStatus.text = getString(R.string.status_preset_applied, preset.name)
        if (skipped.isNotEmpty()) {
            Toast.makeText(this, "No clips yet: ${skipped.joinToString()}", Toast.LENGTH_LONG).show()
        }
    }

    /* ---------- sleep timer ---------- */

    private val sleepOptions = listOf(0, 5, 15, 30, 60)

    private fun buildSleepChips() {
        val row = findViewById<LinearLayout>(R.id.sleepChipRow)
        sleepOptions.forEach { minutes ->
            row.addView(TextView(this).apply {
                text = if (minutes == 0) getString(R.string.chip_off)
                       else getString(R.string.chip_minutes_fmt, minutes)
                textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener { selectSleep(minutes) }
            })
        }
        updateSleepChips()
    }

    private fun updateSleepChips() {
        val row = findViewById<LinearLayout>(R.id.sleepChipRow)
        sleepOptions.forEachIndexed { index, minutes ->
            val chip = row.getChildAt(index) as TextView
            val selected = minutes == sleepMinutes
            chip.background = ContextCompat.getDrawable(
                this, if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            chip.setTextColor(if (selected) Color.WHITE else Color.parseColor("#A6A9CF"))
        }
    }

    private fun selectSleep(minutes: Int) {
        if (minutes == sleepMinutes) return
        sleepMinutes = minutes
        prefs.edit().putInt(PREF_SLEEP, minutes).apply()
        updateSleepChips()

        when {
            sessionFinished && minutes > 0 -> {
                if (ambient.isAudioActive) {
                    ambient.beginSleepFade(minutes * 60_000L) { onSleepFinished() }
                    startSleepUiTicker()
                    tvStatus.text = getString(R.string.sleep_fading_in_fmt, minutes)
                } else {
                    tvStatus.text = getString(R.string.sleep_set_when_resume)
                }
            }
            sessionFinished && minutes == 0 -> {
                ambient.cancelSleepFade()
                stopSleepUiTicker()
                tvStatus.text = getString(R.string.sleep_off_playing)
            }
            minutes > 0 -> tvStatus.text = getString(R.string.sleep_scheduled_fmt, minutes)
            else -> tvStatus.text = getString(R.string.sleep_off)
        }
    }

    private fun startSleepUiTicker() {
        sleepUiHandler.removeCallbacks(sleepUiTicker)
        sleepUiHandler.post(sleepUiTicker)
    }

    private fun stopSleepUiTicker() = sleepUiHandler.removeCallbacks(sleepUiTicker)

    /* ---------- persistence & sync ---------- */

    private fun loadMix() {
        val saved = prefs.getString(PREF_MIX, null)
        mix.clear()
        if (saved != null) {
            mix.putAll(MixSync.parse(this, saved))
        } else {
            prefs.getString(PREF_AMBIENT, null)?.takeIf { it != "none" }?.let { old ->
                if (SoundCatalog.hasSounds(this, old)) mix[old] = 60
            }
        }
        mix.forEach { (key, vol) -> ambient.addLayer(key, vol) }
    }

    private fun saveMix() =
        prefs.edit()
            .putString(PREF_MIX, MixSync.encode(mix, SoundCatalog.orderedKeys(this)))
            .apply()

    override fun onResume() {
        super.onResume()
        rebuildSoundChips()
        syncMixWithPrefs()
    }

    private fun syncMixWithPrefs() {
        if (!::ambient.isInitialized) return
        val d = MixSync.diff(mix, MixSync.parse(this, prefs.getString(PREF_MIX, null)))
        if (d.isEmpty) return
        d.removals.forEach { key -> mix.remove(key); ambient.removeLayer(key) }
        d.volumeChanges.forEach { (key, vol) -> mix[key] = vol; ambient.setLayerVolume(key, vol) }
        d.additions.forEach { (key, vol) -> mix[key] = vol; ambient.addLayer(key, vol) }
        refreshChips()
        rebuildMixerPanel()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            ambient.onMemoryPressure()
        }
    }

    override fun onDestroy() {
        timer?.cancel()
        sleepUiHandler.removeCallbacksAndMessages(null)
        ambient.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MINUTES = "extra_minutes"
        const val PREF_MIX = "mixer_layers"
        private const val PREF_AMBIENT = "ambient"
        private const val PREF_MASTER = "master_volume"
        private const val PREF_SLEEP = "sleep_minutes"
        const val MAX_MIX_LAYERS = 4
        private const val END_FADE_SECONDS = 10
    }
}
