package com.example.zenflow.audio

import android.content.Context
import android.content.pm.PackageInfoCompat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.zenflow.AMBIENT_SOUNDS
import com.example.zenflow.SoundCatalog
import com.example.zenflow.ToneLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class KeyStat(val clips: Int, val bytes: Long, val avgDurationMs: Long)
data class ImportedSound(
    val key: String, val name: String, val fileName: String,
    val bytes: Long, val durationMs: Long
)

/**
 * Single source of truth for the app's sound files: the filesystem.
 * Atomic writes, per-key single-flight, self-healing, version-guarded
 * bundled metadata cache, SAF imports. Nothing here is irreplaceable
 * except user imports — which the files themselves own.
 */
object SoundStore {

    private const val PREFS = "zen_stats"
    private const val KEY_META = "sound_bundled_meta"
    private const val KEY_META_VERSION = "sound_bundled_meta_version"
    private const val KEY_IMPORTS = "sound_imports"
    private const val RETIRED_DB = "zen_sounds.db"

    const val IMPORT_PREFIX = "import_"
    const val MAX_IMPORT_BYTES = 25L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warmUpMutex = Mutex()
    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    data class SynthSpec(val key: String, val fileName: String, val produce: () -> ByteArray)

    private val synthSpecs = LinkedHashMap<String, SynthSpec>()

    @Synchronized
    fun registerSynth(spec: SynthSpec) { synthSpecs[spec.key] = spec }

    @Synchronized
    private fun registerBuiltins() {
        ToneLibrary.TONES.forEach { spec ->
            registerSynth(SynthSpec(spec.key, "${spec.key}.wav") { ToneLibrary.encodeWav(spec) })
        }
    }

    @Synchronized
    private fun snapshotSpecs(): List<SynthSpec> = synthSpecs.values.toList()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /* ---------- locations & integrity ---------- */

    fun soundsDir(context: Context): File =
        File(context.filesDir, "sounds").apply { mkdirs() }

    fun wavFile(context: Context, key: String): File = File(soundsDir(context), "$key.wav")

    fun isValidWav(file: File): Boolean =
        file.exists() && file.length() > WavCodec.HEADER_SIZE &&
            runCatching { WavCodec.decode(file.readBytes()) != null }.getOrDefault(false)

    fun atomicWrite(file: File, bytes: ByteArray): Boolean {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        return runCatching {
            tmp.writeBytes(bytes)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) throw IllegalStateException("rename failed")
            true
        }.getOrDefault(false).also { ok -> if (!ok) runCatching { tmp.delete() } }
    }

    private fun lockFor(key: String) = keyLocks.getOrPut(key) { Mutex() }

    /* ---------- lifecycle ---------- */

    fun warmUpAsync(context: Context, onDone: (() -> Unit)? = null): Job {
        val app = context.applicationContext
        return scope.launch {
            runCatching { warmUp(app) }
            onDone?.let { cb -> withContext(Dispatchers.Main) { cb() } }
        }
    }

    suspend fun warmUp(context: Context) = withContext(Dispatchers.IO) {
        warmUpMutex.withLock {
            removeRetiredArtifacts(context)
            registerBuiltins()
            pruneImports(context)
            runCatching { bundledMeta(context) }
            snapshotSpecs().forEach { spec ->
                runCatching { ensureWavFile(context, spec.key, spec.fileName, spec.produce) }
            }
        }
        Unit
    }

    /* ---------- synthesized WAVs ---------- */

    suspend fun ensureWavFile(
        context: Context, key: String, fileName: String, produce: () -> ByteArray
    ): File = withContext(Dispatchers.IO) {
        lockFor(key).withLock {
            val file = File(soundsDir(context), fileName)
            if (isValidWav(file)) return@withContext file
            runCatching { file.delete() }
            val bytes = runCatching { produce() }.getOrNull() ?: return@withContext file
            val valid = runCatching { WavCodec.decode(bytes) != null }.getOrDefault(false)
            if (valid) atomicWrite(file, bytes)
            file
        }
    }

    suspend fun deleteSynth(context: Context, key: String): Boolean =
        withContext(Dispatchers.IO) {
            lockFor(key).withLock {
                runCatching { File(soundsDir(context), "$key.wav").delete() }.getOrDefault(false)
            }
        }

    /* ---------- bundled clips (res/raw) metadata cache ---------- */

    suspend fun bundledMeta(context: Context, force: Boolean = false): Map<String, KeyStat> =
        withContext(Dispatchers.IO) {
            val prefs = prefs(context)
            val version = versionCode(context)
            val cached = prefs.getString(KEY_META, null)
            if (!force && cached != null && prefs.getLong(KEY_META_VERSION, -1L) == version) {
                return@withContext parseMeta(cached)
            }

            val out = LinkedHashMap<String, KeyStat>()
            for (sound in AMBIENT_SOUNDS) {
                if (SoundCatalog.isTone(sound.key)) continue
                val ids = SoundCatalog.variants(context, sound.key)
                if (ids.isEmpty()) continue
                var bytes = 0L
                var totalMs = 0L
                var timed = 0
                ids.forEach { resId ->
                    bytes += runCatching {
                        context.resources.openRawResourceFd(resId).use { it.length }
                    }.getOrDefault(0L)
                    val ms = bundledDurationMs(context, resId)
                    if (ms > 0) { totalMs += ms; timed++ }
                }
                out[sound.key] = KeyStat(
                    clips = ids.size, bytes = bytes,
                    avgDurationMs = if (timed == 0) 0L else totalMs / timed
                )
            }
            prefs.edit()
                .putString(KEY_META, encodeMeta(out))
                .putLong(KEY_META_VERSION, version)
                .apply()
            out
        }

    private fun encodeMeta(meta: Map<String, KeyStat>): String {
        val root = JSONObject()
        meta.forEach { (key, st) ->
            root.put(key, JSONObject()
                .put("clips", st.clips).put("bytes", st.bytes).put("avgMs", st.avgDurationMs))
        }
        return root.toString()
    }

    private fun parseMeta(json: String): Map<String, KeyStat> =
        runCatching {
            val root = JSONObject(json)
            val out = LinkedHashMap<String, KeyStat>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = root.getJSONObject(k)
                out[k] = KeyStat(o.optInt("clips"), o.optLong("bytes"), o.optLong("avgMs"))
            }
            out
        }.getOrDefault(emptyMap())

    private fun bundledDurationMs(context: Context, resId: Int): Long = runCatching {
        val mmr = MediaMetadataRetriever()
        try {
            context.resources.openRawResourceFd(resId).use { afd ->
                mmr.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { mmr.release() }
        }
    }.getOrDefault(0L)

    /* ---------- user imports (SAF) ---------- */

    fun imports(context: Context): List<ImportedSound> = runCatching {
        val root = JSONObject(prefs(context).getString(KEY_IMPORTS, "{}") ?: "{}")
        root.keys().asSequence().map { k ->
            val o = root.getJSONObject(k)
            ImportedSound(k, o.optString("name"), o.optString("file"),
                o.optLong("bytes"), o.optLong("ms"))
        }.sortedBy { it.name.lowercase() }.toList()
    }.getOrDefault(emptyList())

    fun hasImport(context: Context, key: String): Boolean =
        key.startsWith(IMPORT_PREFIX) && imports(context).any { it.key == key }

    fun importedFilePath(context: Context, key: String): String? =
        imports(context).firstOrNull { it.key == key }
            ?.let { File(soundsDir(context), it.fileName) }
            ?.takeIf { it.exists() }?.absolutePath

    private fun saveImports(context: Context, list: List<ImportedSound>) {
        val root = JSONObject()
        list.forEach { i ->
            root.put(i.key, JSONObject()
                .put("name", i.name).put("file", i.fileName)
                .put("bytes", i.bytes).put("ms", i.durationMs))
        }
        prefs(context).edit().putString(KEY_IMPORTS, root.toString()).apply()
    }

    suspend fun registerImport(
        context: Context, bytes: ByteArray, displayName: String
    ): Result<ImportedSound> = withContext(Dispatchers.IO) {
        runCatching {
            require(bytes.isNotEmpty()) { "Empty file" }
            require(bytes.size <= MAX_IMPORT_BYTES) {
                "File too large (max ${MAX_IMPORT_BYTES / (1024 * 1024)} MB)"
            }
            val parsedWav = runCatching { WavCodec.decode(bytes) }.getOrNull()
            val durationMs = parsedWav?.durationMs ?: probeDuration(bytes)
            require(durationMs > 0) { "Not a recognizable audio file" }

            val ext = parsedWav?.let { "wav" }
                ?: displayName.substringAfterLast('.', "")
                    .lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "audio" }
            val safeName = displayName.substringBeforeLast('.').trim()
                .ifEmpty { "Imported sound" }.take(40)

            val key = newImportKey(context)
            val fileName = "$key.$ext"
            val file = File(soundsDir(context), fileName)
            check(atomicWrite(file, bytes)) { "Could not save file" }

            val imp = ImportedSound(key, safeName, fileName, bytes.size.toLong(), durationMs)
            saveImports(context, imports(context) + imp)
            imp
        }
    }

    suspend fun importFromUri(context: Context, uri: Uri): Result<ImportedSound> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: -1L
                require(size in 1..MAX_IMPORT_BYTES) { "File too large or unreadable" }
                val name = resolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) ?: "Imported sound" }
                    ?: "Imported sound"
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not read file")
                registerImport(context, bytes, name).getOrThrow()
            }
        }

    suspend fun deleteImport(context: Context, key: String): Boolean =
        withContext(Dispatchers.IO) {
            lockFor(key).withLock {
                val imp = imports(context).firstOrNull { it.key == key }
                    ?: return@withLock false
                val ok = runCatching {
                    File(soundsDir(context), imp.fileName).delete()
                }.getOrDefault(false)
                saveImports(context, imports(context).filterNot { it.key == key })
                ok
            }
        }

    private fun pruneImports(context: Context) {
        val list = imports(context)
        val kept = list.filter { File(soundsDir(context), it.fileName).exists() }
        if (kept.size != list.size) saveImports(context, kept)
    }

    private fun probeDuration(bytes: ByteArray): Long = runCatching {
        val tmp = File.createTempFile("zenprobe", ".bin")
        try {
            tmp.writeBytes(bytes)
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(tmp.absolutePath)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } finally { runCatching { mmr.release() } }
        } finally { tmp.delete() }
    }.getOrDefault(0L)

    private fun newImportKey(context: Context): String {
        val existing = imports(context).map { it.key }.toSet()
        while (true) {
            val k = IMPORT_PREFIX + List(6) { "0123456789abcdef".random() }.joinToString("")
            if (k !in existing) return k
        }
    }

    /* ---------- queries & maintenance ---------- */

    suspend fun statsByKey(context: Context): Map<String, KeyStat> = withContext(Dispatchers.IO) {
        registerBuiltins()
        val out = LinkedHashMap<String, KeyStat>()
        snapshotSpecs().forEach { spec ->
            statOf(File(soundsDir(context), spec.fileName))?.let { out[spec.key] = it }
        }
        out.putAll(bundledMeta(context))
        imports(context).forEach { imp ->
            statOf(File(soundsDir(context), imp.fileName))?.let { out[imp.key] = it }
        }
        out
    }

    private fun statOf(file: File): KeyStat? {
        if (!file.exists()) return null
        val parsed = runCatching { WavCodec.decode(file.readBytes()) }.getOrNull() ?: return null
        return KeyStat(clips = 1, bytes = file.length(), avgDurationMs = parsed.durationMs)
    }

    data class HealthReport(
        val checked: Int, val verified: Int,
        val repaired: List<String>, val failed: List<String>
    ) {
        val summary: String get() = when {
            repaired.isEmpty() && failed.isEmpty() -> "$checked checked · all healthy ✓"
            failed.isEmpty() -> "$checked checked · $verified ok · ${repaired.size} repaired 🔧"
            else -> "$checked checked · ${repaired.size} repaired · ${failed.size} failed ⚠️"
        }
    }

    suspend fun healthCheck(context: Context): HealthReport {
        registerBuiltins()
        var verified = 0
        val repaired = mutableListOf<String>()
        val failed = mutableListOf<String>()
        snapshotSpecs().forEach { spec ->
            val file = File(soundsDir(context), spec.fileName)
            val healthyBefore = isValidWav(file)
            val ok = runCatching {
                ensureWavFile(context, spec.key, spec.fileName, spec.produce)
            }.getOrNull()?.let { isValidWav(it) } == true
            when {
                !ok -> failed.add(spec.fileName)
                healthyBefore -> verified++
                else -> repaired.add(spec.fileName)
            }
        }
        return HealthReport(snapshotSpecs().size, verified, repaired, failed)
    }

    private fun removeRetiredArtifacts(context: Context) {
        listOf("$RETIRED_DB", "$RETIRED_DB-wal", "$RETIRED_DB-shm", "$RETIRED_DB-journal")
            .forEach { runCatching { context.getDatabasePath(it).delete() } }
        runCatching {
            prefs(context).edit().remove("sound_db_indexed_version").apply()
        }
        runCatching {
            soundsDir(context).listFiles()?.forEach { f ->
                if (f.name.startsWith("chime_")) f.delete()
            }
        }
    }

    fun versionCode(context: Context): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )
    }.getOrDefault(0L)
}
