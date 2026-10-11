package com.example.zenflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.zenflow.audio.SoundStore

/** A volume ramp from 1 → 0 over a duration, with pause/resume and restart. */
internal class Ramp {
    private var startMs = -1L
    private var durationMs = 0L
    private var pauseMarkMs = -1L

    fun begin(nowMs: Long, durationMs: Long) {
        if (startMs < 0) { startMs = nowMs; this.durationMs = durationMs }
    }

    fun restart(nowMs: Long, durationMs: Long) {
        startMs = nowMs; this.durationMs = durationMs; pauseMarkMs = -1L
    }

    fun cancel() { startMs = -1L; durationMs = 0L; pauseMarkMs = -1L }

    fun pause(nowMs: Long) { if (startMs > 0 && pauseMarkMs < 0) pauseMarkMs = nowMs }

    fun resume(nowMs: Long) {
        if (pauseMarkMs > 0) { startMs += nowMs - pauseMarkMs; pauseMarkMs = -1L }
    }

    fun factor(nowMs: Long): Float {
        if (startMs < 0 || durationMs <= 0) return 1f
        val elapsed = (nowMs - startMs).coerceAtLeast(0L)
        return (1f - elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    fun remainingMs(nowMs: Long): Long {
        if (startMs < 0 || durationMs <= 0) return 0L
        val ref = if (pauseMarkMs > 0) pauseMarkMs else nowMs
        return (durationMs - (ref - startMs)).coerceAtLeast(0L)
    }
}

/**
 * Multi-layer ambient mixer: bundled-clip layers with endless random crossfades,
 * single-file imported layers with self-crossfade, and synthesized tone layers.
 */
class AmbientMixer(private val context: Context) {

    private sealed interface Source {
        data class Res(val id: Int) : Source
        data class FileSrc(val path: String) : Source
    }

    private class Slot(var source: Source) {
        val player = MediaPlayer()
        var prepared = false
        var started = false
        var completed = false
    }

    private inner class Layer(val key: String, var volume: Float) {
        private val variants = SoundCatalog.variants(context, key)
        private val importPath: String? =
            if (!SoundCatalog.isTone(key) && variants.isEmpty())
                SoundStore.importedFilePath(context, key)
            else null
        private val bag = ArrayDeque<Int>()
        private var lastVariant = -1

        private var current: Slot? = null
        private var standby: Slot? = null
        private var crossfadeStart = -1L
        private var fadeInStart = -1L
        private var released = false

        private var tone: ToneLayer? = null

        fun prepareIfEmpty() {
            if (released) return
            if (SoundCatalog.isTone(key)) {
                if (tone == null) {
                    val spec = ToneLibrary.TONES.first { it.key == key }
                    val file = SoundStore.wavFile(context, key)
                    tone = (if (SoundStore.isValidWav(file)) ToneLayer.fromWavFile(file)
                            else ToneLayer(ToneLibrary.samples(spec)))
                        ?.also { if (sessionPlaying) it.start() }
                }
                return
            }
            if (variants.isEmpty() && importPath == null) return
            if (current == null) current = makeSlot(nextSource()).also { it.prepareNow() }
        }

        fun start() {
            if (released) return
            tone?.let { if (sessionPlaying) it.start(); return }
            prepareIfEmpty()
            val slot = current ?: return
            when {
                !slot.started && slot.prepared -> {
                    slot.started = true
                    slot.player.start()
                    fadeInStart = now()
                }
                slot.started && !slot.player.isPlaying -> {
                    slot.player.start()
                    fadeInStart = now()
                }
            }
        }

        fun pause() {
            tone?.pause()
            current?.player?.takeIf { it.isPlaying }?.pause()
            standby?.player?.takeIf { it.isPlaying }?.pause()
        }

        fun rewind() {
            if (tone != null) return
            if (crossfadeStart > 0) finishCrossfade()
            current?.let { s ->
                if (s.prepared && s.started && !s.completed) {
                    s.player.pause()
                    s.player.seekTo(0)
                    s.started = false
                }
            }
        }

        fun release() {
            released = true
            tone?.release()
            tone = null
            current?.player?.release()
            standby?.player?.release()
            current = null
            standby = null
        }

        fun dropStandby() {
            if (crossfadeStart > 0) return
            standby?.player?.release()
            standby = null
        }

        fun onTick(now: Long) {
            if (released) return

            tone?.let { t ->
                if (sessionPlaying && !t.isPlaying) t.start()
                t.setVolume(
                    clamp01(volume * masterVolume * sessionRamp.factor(now) *
                            sleepRamp.factor(now) * duckFactor)
                )
                return
            }
            if (variants.isEmpty() && importPath == null) return

            val cur = current ?: return

            if (sessionPlaying && !cur.started && cur.prepared && crossfadeStart < 0) {
                cur.started = true
                cur.player.start()
                fadeInStart = now
            }

            if (crossfadeStart > 0) {
                val next = standby ?: return
                val fadeMs = CrossfadeMath.fadeDurationMs(cur.player.duration, CROSSFADE_MS)
                val t = CrossfadeMath.progress(now - crossfadeStart, fadeMs)
                val base = clamp01(effectiveVolume(now))
                val (outV, inV) = CrossfadeMath.volumes(t)
                cur.player.takeIf { it.isPlaying }
                    ?.setVolume(clamp01(base * outV), clamp01(base * outV))
                next.player.setVolume(clamp01(base * inV), clamp01(base * inV))
                if (t >= 1f) finishCrossfade()
                return
            }

            if (cur.completed) {
                advance()
                return
            }

            if (cur.started && cur.prepared) {
                val fadeIn = if (fadeInStart > 0) {
                    val e = ((now - fadeInStart).toFloat() / FADE_IN_ON_START_MS).coerceIn(0f, 1f)
                    if (e >= 1f) fadeInStart = -1L
                    e
                } else 1f
                val v = clamp01(effectiveVolume(now) * fadeIn)
                cur.player.takeIf { it.isPlaying }?.setVolume(v, v)

                if (CrossfadeMath.shouldBegin(
                        cur.player.currentPosition, cur.player.duration, CROSSFADE_MS)
                ) beginCrossfade()
            }
        }

        private fun effectiveVolume(nowMs: Long): Float =
            volume * masterVolume * sessionRamp.factor(nowMs) * sleepRamp.factor(nowMs) * duckFactor

        private fun nextSource(): Source {
            val path = importPath
            return if (path != null) Source.FileSrc(path) else Source.Res(pickNext())
        }

        private fun beginCrossfade() {
            var next = standby
            if (next == null) {
                next = makeSlot(nextSource()).also { it.prepareNow() }
                standby = next
            }
            if (!next.prepared) return
            next.started = true
            next.player.setVolume(0f, 0f)
            next.player.start()
            crossfadeStart = now()
        }

        private fun finishCrossfade() {
            val old = current
            val next = standby
            current = next
            standby = null
            crossfadeStart = -1L
            fadeInStart = -1L
            if (old != null && next != null) {
                recycle(old, nextSource())
                standby = old
            }
        }

        private fun advance() {
            val cur = current ?: return
            val next = standby
            if (next != null && next.prepared) {
                current = next
                next.started = true
                next.player.setVolume(0f, 0f)
                next.player.start()
                fadeInStart = now()
                recycle(cur, nextSource())
                standby = cur
            } else {
                cur.completed = false
                runCatching {
                    cur.player.seekTo(0)
                    cur.player.start()
                }
            }
            crossfadeStart = -1L
        }

        private fun makeSlot(source: Source): Slot {
            val slot = Slot(source)
            slot.player.setAudioAttributes(ATTRS)
            slot.player.setOnPreparedListener { slot.prepared = true }
            slot.player.setOnCompletionListener { slot.completed = true }
            slot.player.setOnErrorListener { _, _, _ -> true }
            return slot
        }

        private fun Slot.prepareNow() {
            if (prepared) return
            runCatching {
                when (val s = source) {
                    is Source.Res -> context.resources.openRawResourceFd(s.id).use { afd ->
                        player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    }
                    is Source.FileSrc -> player.setDataSource(s.path)
                }
                player.prepareAsync()
            }
        }

        private fun recycle(slot: Slot, next: Source) {
            runCatching {
                slot.player.reset()
                slot.source = next
                slot.prepared = false
                slot.started = false
                slot.completed = false
                slot.prepareNow()
            }
        }

        private fun pickNext(): Int {
            if (bag.isEmpty()) {
                val shuffled = variants.shuffled().toMutableList()
                if (shuffled.size > 1 && shuffled.first() == lastVariant) {
                    val j = (1 until shuffled.size).random()
                    val tmp = shuffled[0]; shuffled[0] = shuffled[j]; shuffled[j] = tmp
                }
                bag.addAll(shuffled)
            }
            val next = bag.removeFirst()
            lastVariant = next
            return next
        }
    }

    private val layers = LinkedHashMap<String, Layer>()
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var sessionPlaying = false
    private var pausedByFocus = false
    private var duckFactor = 1f
    private var masterVolume = 1f
    private var noisyRegistered = false

    private val sessionRamp = Ramp()
    private val sleepRamp = Ramp()
    private var sleepCallback: (() -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (sessionPlaying && !pausedByFocus) {
                layers.values.forEach { it.onTick(now) }

                val callback = sleepCallback
                if (callback != null && sleepRamp.factor(now) <= 0f) {
                    sleepCallback = null
                    sleepRamp.cancel()
                    sessionPlaying = false
                    layers.values.forEach { it.pause() }
                    callback()
                    return
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { pausedByFocus = true; interruptPlayback() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duckFactor = DUCK_VOLUME
            AudioManager.AUDIOFOCUS_GAIN -> {
                duckFactor = 1f
                if (pausedByFocus && sessionPlaying) {
                    val now = now()
                    sessionRamp.resume(now)
                    sleepRamp.resume(now)
                    layers.values.forEach { it.start() }
                    handler.removeCallbacks(ticker)
                    handler.post(ticker)
                }
                pausedByFocus = false
            }
        }
    }

    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(ATTRS)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        } else null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = interruptPlayback()
    }

    /* ---------- public API ---------- */

    fun addLayer(key: String, volumePercent: Int) {
        if (!SoundCatalog.hasSounds(context, key)) return
        val existing = layers[key]
        if (existing != null) {
            existing.volume = volumePercent / 100f
            return
        }
        layers[key] = Layer(key, volumePercent / 100f).also {
            it.prepareIfEmpty()
            if (sessionPlaying) it.start()
        }
        if (sessionPlaying) {
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }
        updateNoisyRegistration()
    }

    fun removeLayer(key: String) {
        layers.remove(key)?.release()
        if (layers.isEmpty()) {
            sessionRamp.cancel()
            cancelSleepFade()
            abandonFocus()
            handler.removeCallbacks(ticker)
        }
        updateNoisyRegistration()
    }

    fun setLayerVolume(key: String, volumePercent: Int) {
        layers[key]?.volume = volumePercent / 100f
    }

    fun setMasterVolume(percent: Int) {
        masterVolume = percent.coerceIn(0, 100) / 100f
    }

    fun beginSessionFadeOut(durationMs: Long) = sessionRamp.begin(now(), durationMs)

    fun cancelSessionFadeOut() = sessionRamp.cancel()

    fun beginSleepFade(durationMs: Long, onFinished: () -> Unit) {
        sleepCallback = onFinished
        sleepRamp.restart(now(), durationMs)
    }

    fun cancelSleepFade() {
        sleepCallback = null
        sleepRamp.cancel()
    }

    fun sleepRemainingMs(): Long =
        if (sleepCallback != null) sleepRamp.remainingMs(now()) else 0L

    val isAudioActive: Boolean get() = sessionPlaying

    fun onMemoryPressure() = layers.values.forEach { it.dropStandby() }

    fun startAll() {
        sessionPlaying = true
        pausedByFocus = false
        val now = now()
        sessionRamp.resume(now)
        sleepRamp.resume(now)
        if (layers.isNotEmpty()) requestFocus()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        layers.values.forEach { it.start() }
    }

    fun pauseAll() {
        sessionPlaying = false
        pausedByFocus = false
        val now = now()
        sessionRamp.pause(now)
        sleepRamp.pause(now)
        pauseAudio()
        handler.removeCallbacks(ticker)
    }

    fun resetAll() = layers.values.forEach { it.rewind() }

    fun release() {
        sessionPlaying = false
        sleepCallback = null
        sessionRamp.cancel()
        sleepRamp.cancel()
        layers.values.forEach { it.release() }
        layers.clear()
        handler.removeCallbacksAndMessages(null)
        abandonFocus()
        updateNoisyRegistration()
    }

    private fun pauseAudio() = layers.values.forEach { it.pause() }

    private fun interruptPlayback() {
        val now = now()
        sessionRamp.pause(now)
        sleepRamp.pause(now)
        pauseAudio()
        handler.removeCallbacks(ticker)
    }

    private fun updateNoisyRegistration() {
        val want = layers.isNotEmpty()
        if (want == noisyRegistered) return
        noisyRegistered = want
        if (want) {
            ContextCompat.registerReceiver(
                context, noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else runCatching { context.unregisterReceiver(noisyReceiver) }
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)

    companion object {
        private const val TICK_MS = 200L
        private const val CROSSFADE_MS = 4000L
        private const val FADE_IN_ON_START_MS = 1200L
        private const val DUCK_VOLUME = 0.15f

        private val ATTRS = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
