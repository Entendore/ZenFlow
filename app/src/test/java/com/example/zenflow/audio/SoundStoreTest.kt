package com.example.zenflow.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.zenflow.util.CleanPrefsRule
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundStoreTest {

    @get:Rule val clean = CleanPrefsRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun soundsDir() = SoundStore.soundsDir(ctx)
    private fun tonePcm(): ByteArray = WavCodec.encode(ShortArray(2205), 22050, 2)
    private fun tinyWav(): ByteArray = WavCodec.encode(ShortArray(8000), 8000, 1)

    @Before
    fun cleanFiles() {
        soundsDir().deleteRecursively()
        soundsDir().mkdirs()
    }

    @Test
    fun `warmUp materializes tone wav files with header-derived stats`() = runBlocking {
        SoundStore.warmUp(ctx)
        val f = File(soundsDir(), "tone_delta.wav")
        assertTrue(f.exists())
        assertTrue("size ${f.length()}", f.length() in 44_100..44_200)

        val st = SoundStore.statsByKey(ctx)["tone_delta"]!!
        assertEquals(1, st.clips)
        assertEquals(f.length(), st.bytes)
        assertEquals(500L, st.avgDurationMs)
    }

    @Test
    fun `warmUp is a no-op the second time`() = runBlocking {
        SoundStore.warmUp(ctx)
        val stamp = File(soundsDir(), "tone_theta.wav").lastModified()
        Thread.sleep(10)
        SoundStore.warmUp(ctx)
        assertEquals(stamp, File(soundsDir(), "tone_theta.wav").lastModified())
    }

    @Test
    fun `a corrupt file is detected and rebuilt`() = runBlocking {
        SoundStore.warmUp(ctx)
        val f = File(soundsDir(), "tone_focus.wav")
        f.writeBytes("garbage payload that is not a wav".toByteArray())
        SoundStore.warmUp(ctx)
        assertNotNull(WavCodec.decode(f.readBytes()))
    }

    @Test
    fun `invalid producer output is never adopted`() = runBlocking {
        val f = SoundStore.ensureWavFile(ctx, "bad", "bad.wav") { "not a wav".toByteArray() }
        assertFalse(SoundStore.isValidWav(f))
        SoundStore.ensureWavFile(ctx, "bad", "bad.wav") { tonePcm() }
        assertTrue(SoundStore.isValidWav(f))
    }

    @Test
    fun `deleteSynth removes the file and warmUp regenerates it`() = runBlocking {
        SoundStore.warmUp(ctx)
        assertTrue(SoundStore.deleteSynth(ctx, "tone_alpha"))
        assertFalse(File(soundsDir(), "tone_alpha.wav").exists())
        SoundStore.warmUp(ctx)
        assertTrue(SoundStore.isValidWav(File(soundsDir(), "tone_alpha.wav")))
    }

    @Test
    fun `concurrent ensures single-flight on one key`() = runBlocking {
        repeat(3) {
            coroutineScope {
                (1..6).map {
                    async { SoundStore.ensureWavFile(ctx, "race", "race.wav") { tonePcm() } }
                }.awaitAll()
            }
            val f = File(soundsDir(), "race.wav")
            assertNotNull(WavCodec.decode(f.readBytes()))
            assertEquals(44 + 2205 * 2, f.length())
            SoundStore.deleteSynth(ctx, "race")
        }
    }

    @Test
    fun `atomicWrite roundtrips bytes and leaves no tmp files`() {
        val f = File(soundsDir(), "atomic.wav")
        assertTrue(SoundStore.atomicWrite(f, byteArrayOf(1, 2, 3)))
        assertTrue(f.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(soundsDir().listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `healthCheck verifies healthy files and repairs deleted ones`() = runBlocking {
        SoundStore.warmUp(ctx)
        val r1 = SoundStore.healthCheck(ctx)
        assertTrue(r1.repaired.isEmpty() && r1.failed.isEmpty())
        assertEquals(4, r1.checked)

        File(soundsDir(), "tone_delta.wav").delete()
        val r2 = SoundStore.healthCheck(ctx)
        assertEquals(listOf("tone_delta.wav"), r2.repaired)
        assertTrue(SoundStore.isValidWav(File(soundsDir(), "tone_delta.wav")))
    }

    @Test
    fun `bundledMeta caches by app version`() = runBlocking {
        val first = SoundStore.bundledMeta(ctx)
        val prefs = ctx.getSharedPreferences("zen_stats", Context.MODE_PRIVATE)
        assertTrue(prefs.contains("sound_bundled_meta"))
        val second = SoundStore.bundledMeta(ctx)
        assertEquals(first, second)
    }

    @Test
    fun `registerImport persists file and registry with real metadata`() = runBlocking {
        val imp = SoundStore.registerImport(ctx, tinyWav(), "Ocean Deep.wav").getOrThrow()
        assertTrue(imp.key.startsWith("import_"))
        assertEquals("Ocean Deep", imp.name)
        assertEquals(1000L, imp.durationMs)
        assertTrue(File(soundsDir(), imp.fileName).exists())
        assertEquals(1, SoundStore.imports(ctx).size)
    }

    @Test
    fun `registerImport rejects garbage and oversized payloads`() = runBlocking {
        assertTrue(SoundStore.registerImport(ctx, "nope".toByteArray(), "x.mp3").isFailure)
        assertTrue(
            SoundStore.registerImport(
                ctx, ByteArray(SoundStore.MAX_IMPORT_BYTES + 1), "big.mp3"
            ).isFailure
        )
        assertTrue(SoundStore.imports(ctx).isEmpty())
    }

    @Test
    fun `deleteImport removes file and registry entry`() = runBlocking {
        val imp = SoundStore.registerImport(ctx, tinyWav(), "Rain Heavy.mp3").getOrThrow()
        assertTrue(SoundStore.deleteImport(ctx, imp.key))
        assertFalse(File(soundsDir(), imp.fileName).exists())
        assertTrue(SoundStore.imports(ctx).isEmpty())
    }

    @Test
    fun `warmUp prunes registry entries whose files vanished`() = runBlocking {
        val imp = SoundStore.registerImport(ctx, tinyWav(), "Gone.wav").getOrThrow()
        File(soundsDir(), imp.fileName).delete()
        SoundStore.warmUp(ctx)
        assertTrue(SoundStore.imports(ctx).isEmpty())
    }

    @Test
    fun `import keys survive MixSync parse and encode in order`() = runBlocking {
        val imp = SoundStore.registerImport(ctx, tinyWav(), "Ocean Deep.wav").getOrThrow()
        val parsed = MixSync.parse(ctx, "${imp.key}:45,tone_delta:60")
        assertEquals(listOf("tone_delta", imp.key), parsed.keys.toList())
    }
}
