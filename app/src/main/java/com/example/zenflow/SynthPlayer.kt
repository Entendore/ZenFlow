package com.example.zenflow

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** One sine component of a synthesized voice. */
data class SynthPartial(
    val freqHz: Double,
    val gain: Double,
    val decay: Double = 0.0
)

/** A fully specified sound: partials + optional sweep + envelope. Pure data. */
data class SynthVoice(
    val durationMs: Long,
    val partials: List<SynthPartial>,
    val sweepToHz: Double? = null,
    val attackMs: Long = 12,
    val releaseMs: Long = 150,
    val gain: Double = 0.45
)

/** The app's cue set — musically identical to the original chimes. */
object SynthVoices {

    val COMPLETE = SynthVoice(
        durationMs = 2200,
        partials = listOf(
            SynthPartial(528.0, 1.00, 2.2),
            SynthPartial(528.0 * 2.01, 0.40, 2.2 * 2.01),
            SynthPartial(528.0 * 2.98, 0.20, 2.2 * 2.98),
            SynthPartial(528.0 * 4.16, 0.08, 2.2 * 4.16)
        ),
        attackMs = 10, releaseMs = 200, gain = 0.5
    )

    val INHALE = SynthVoice(
        durationMs = 500,
        partials = listOf(SynthPartial(392.0, 1.0)),
        sweepToHz = 523.25
    )

    val HOLD = SynthVoice(durationMs = 350, partials = listOf(SynthPartial(440.0, 1.0)))

    val EXHALE = SynthVoice(
        durationMs = 600,
        partials = listOf(SynthPartial(523.25, 1.0)),
        sweepToHz = 349.23
    )
}

/** Pure sample renderer — no Android dependencies, JVM-testable. */
object SynthRenderer {

    const val SAMPLE_RATE = 44100

    fun frameCount(voice: SynthVoice): Int =
        (voice.durationMs * SAMPLE_RATE / 1000L).toInt().coerceAtLeast(1)

    class Cursor internal constructor(voice: SynthVoice) {
        internal val phases = DoubleArray(voice.partials.size)
        internal var frame = 0
    }

    fun cursor(voice: SynthVoice) = Cursor(voice)

    fun render(voice: SynthVoice, cursor: Cursor, dst: ShortArray, from: Int, frames: Int) {
        val total = frameCount(voice)
        val baseF0 = voice.partials.first().freqHz
        val attackFrames = (voice.attackMs * SAMPLE_RATE / 1000L).toInt()
        val releaseFrames = (voice.releaseMs * SAMPLE_RATE / 1000L).toInt()
        val releaseStart = total - releaseFrames
        val sweep = voice.sweepToHz

        var f = cursor.frame
        var i = from
        val end = minOf(from + frames, dst.size)
        while (i < end && f < total) {
            val t = f.toDouble() / SAMPLE_RATE
            val f0 = when (sweep) {
                null -> baseF0
                else -> baseF0 + (sweep - baseF0) * (f.toDouble() / total)
            }
            var s = 0.0
            for (p in voice.partials.indices) {
                val partial = voice.partials[p]
                cursor.phases[p] += 2.0 * PI * f0 * (partial.freqHz / baseF0) / SAMPLE_RATE
                s += partial.gain * sin(cursor.phases[p]) * exp(-partial.decay * t)
            }
            val env = when {
                attackFrames > 0 && f < attackFrames -> f.toDouble() / attackFrames
                releaseFrames > 0 && f >= releaseStart ->
                    ((total - f).toDouble() / releaseFrames).coerceIn(0.0, 1.0)
                else -> 1.0
            }
            val v = s * env * voice.gain * Short.MAX_VALUE
            dst[i] = when {
                v > Short.MAX_VALUE -> Short.MAX_VALUE
                v < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> v.toInt()
            }.toShort()
            i++; f++
        }
        cursor.frame = f
    }
}

/** Real-time cue synthesizer: no files, no preload, no Context. */
object SynthPlayer {

    private const val MAX_VOICES = 2
    private const val CLEANUP_DELAY_MS = 200L

    private val synth = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zen-synth").apply { isDaemon = true }
    }
    private val active = CopyOnWriteArrayList<AudioTrack>()
    private val main = Handler(Looper.getMainLooper())

    fun play(voice: SynthVoice, gain: Float = 1f) {
        val scaled = if (gain == 1f) voice else voice.copy(gain = voice.gain * gain)
        synth.execute {
            while (active.size >= MAX_VOICES) {
                val oldest = active.removeAt(0)
                runCatching { oldest.pause(); oldest.flush(); oldest.release() }
            }

            val frames = SynthRenderer.frameCount(scaled)
            val pcm = ShortArray(frames)
            SynthRenderer.render(scaled, SynthRenderer.cursor(scaled), pcm, 0, frames)

            val track = newTrack(frames) ?: return@execute
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track.release() }
                return@execute
            }
            active.add(track)

            runCatching {
                track.play()
                var written = 0
                while (written < frames) {
                    val r = runCatching { track.write(pcm, written, frames - written) }
                        .getOrDefault(0)
                    if (r <= 0) break
                    written += r
                }
            }
            main.postDelayed({
                active.remove(track)
                runCatching { track.stop() }
                runCatching { track.release() }
            }, scaled.durationMs + CLEANUP_DELAY_MS)
        }
    }

    fun release() {
        active.forEach { t ->
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        active.clear()
    }

    private fun newTrack(frames: Int): AudioTrack? = runCatching {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SynthRenderer.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        AudioTrack(attrs, fmt, frames * 2, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE)
    }.getOrNull()
}
