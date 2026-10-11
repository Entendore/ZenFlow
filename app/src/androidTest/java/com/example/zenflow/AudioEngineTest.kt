package com.example.zenflow

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioEngineTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun toneLayerLifecycle() {
        val spec = ToneLibrary.TONES.first()
        val layer = ToneLayer(ToneLibrary.samples(spec))
        layer.start()
        assertTrue(layer.isPlaying)
        layer.setVolume(0.5f)
        layer.pause()
        assertFalse(layer.isPlaying)
        layer.release()
        layer.release()
    }

    @Test
    fun mixerToneSmoke_exercisesTheFullApi() {
        val mixer = AmbientMixer(ctx)
        mixer.addLayer("tone_delta", 60)
        mixer.setMasterVolume(80)
        mixer.startAll()
        assertTrue(mixer.isAudioActive)
        mixer.beginSessionFadeOut(200)
        mixer.cancelSessionFadeOut()
        mixer.beginSleepFade(200) { }
        mixer.cancelSleepFade()
        mixer.onMemoryPressure()
        mixer.pauseAll()
        assertFalse(mixer.isAudioActive)
        mixer.removeLayer("tone_delta")
        mixer.release()
    }

    @Test
    fun fileLayersSmoke_whenClipsExist() {
        val keys = AMBIENT_SOUNDS
            .filter { !SoundCatalog.isTone(it.key) && SoundCatalog.hasSounds(ctx, it.key) }
        assumeTrue("No res/raw clips in this build — skipping", keys.isNotEmpty())

        val mixer = AmbientMixer(ctx)
        keys.take(2).forEach { mixer.addLayer(it, 60) }
        mixer.startAll()
        Thread.sleep(1500)
        assertTrue(mixer.isAudioActive)
        mixer.removeLayer(keys.first())
        mixer.release()
    }

    @Test
    fun synthPlaysAllVoicesWithoutCrash() {
        listOf(SynthVoices.COMPLETE, SynthVoices.INHALE, SynthVoices.HOLD, SynthVoices.EXHALE)
            .forEach { SynthPlayer.play(it) }
        Thread.sleep(400)
        SynthPlayer.play(SynthVoices.COMPLETE, 0.5f)
        SynthPlayer.release()
        SynthPlayer.release()
    }
}
