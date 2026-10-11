package com.example.zenflow

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.example.zenflow.audio.WavCodec
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

object ToneLibrary {

    data class ToneSpec(
        val key: String, val label: String, val emoji: String, val hint: String,
        val leftHz: Int, val rightHz: Int, val isochronicHz: Int
    )

    val TONES = listOf(
        ToneSpec("tone_delta", "Delta sleep", "🌑", "2 Hz binaural · deep sleep · headphones", 100, 102, 0),
        ToneSpec("tone_theta", "Theta meditate", "🌌", "6 Hz binaural · deep meditation · headphones", 140, 146, 0),
        ToneSpec("tone_alpha", "Alpha calm", "🌤️", "10 Hz binaural · relaxed alertness · headphones", 180, 190, 0),
        ToneSpec("tone_focus", "Iso focus", "🎯", "14 Hz isochronic · works on speakers", 200, 200, 14)
    )

    const val SAMPLE_RATE = 22050
    private const val AMPLITUDE = 0.32
    private const val MAX_LOOP_SECONDS = 2.0

    /** Smallest loop in which every frequency completes whole cycles — seamless. */
    fun loopSeconds(spec: ToneSpec): Double {
        val freqs = buildList {
            add(spec.leftHz); add(spec.rightHz)
            if (spec.isochronicHz > 0) add(spec.isochronicHz)
        }
        var ms = 1
        while (ms <= MAX_LOOP_SECONDS * 1000) {
            val t = ms / 1000.0
            if (freqs.all { f -> abs(f * t - round(f * t)) < 1e-9 }) return t
            ms++
        }
        return MAX_LOOP_SECONDS
    }

    fun loopBytes(spec: ToneSpec): Int =
        (loopSeconds(spec) * SAMPLE_RATE).roundToInt() * 2 * 2

    fun samples(spec: ToneSpec): ShortArray {
        val frames = (loopSeconds(spec) * SAMPLE_RATE).roundToInt()
        val pcm = ShortArray(frames * 2)
        var i = 0
        for (frame in 0 until frames) {
            val t = frame.toDouble() / SAMPLE_RATE
            var l = sin(2 * PI * spec.leftHz * t)
            var r = sin(2 * PI * spec.rightHz * t)
            if (spec.isochronicHz > 0) {
                val phase = (t % (1.0 / spec.isochronicHz)) * spec.isochronicHz
                val gate = 0.5 - 0.5 * cos(2 * PI * phase)
                l *= gate; r *= gate
            }
            pcm[i++] = (l * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
            pcm[i++] = (r * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
        }
        return pcm
    }

    fun encodeWav(spec: ToneSpec): ByteArray = WavCodec.encode(samples(spec), SAMPLE_RATE, 2)
}

/** Sample-perfect infinite loop via AudioTrack STATIC mode. */
class ToneLayer private constructor(pcm: ShortArray, sampleRate: Int, channels: Int) {

    private var track: AudioTrack? = null
    private var playing = false

    init {
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(
                    if (channels == 1) AudioFormat.CHANNEL_OUT_MONO
                    else AudioFormat.CHANNEL_OUT_STEREO
                )
                .build()
            val t = AudioTrack(
                attrs, format, pcm.size * 2,
                AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            if (t.state == AudioTrack.STATE_INITIALIZED) {
                t.write(pcm, 0, pcm.size)
                t.setLoopPoints(0, pcm.size / channels, -1)
                t.setVolume(0f)
                track = t
            } else t.release()
        }
    }

    constructor(pcm: ShortArray) : this(pcm, ToneLibrary.SAMPLE_RATE, 2)

    companion object {
        fun fromWavFile(file: File): ToneLayer? {
            if (!file.exists() || file.length() <= WavCodec.HEADER_SIZE) return null
            val parsed = runCatching { WavCodec.decode(file.readBytes()) }.getOrNull() ?: return null
            if (parsed.pcm.isEmpty()) return null
            return ToneLayer(parsed.pcm, parsed.sampleRate, parsed.channels)
        }
    }

    val isPlaying: Boolean get() = playing

    fun start() {
        val t = track ?: return
        if (!playing) { runCatching { t.play() }; playing = true }
    }

    fun pause() {
        if (playing) { runCatching { track?.pause() }; playing = false }
    }

    fun setVolume(v: Float) { runCatching { track?.setVolume(v.coerceIn(0f, 1f)) } }

    fun release() {
        pause()
        runCatching { track?.release() }
        track = null
    }
}
