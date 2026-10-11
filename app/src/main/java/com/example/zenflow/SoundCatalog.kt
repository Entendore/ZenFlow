package com.example.zenflow

import android.content.Context
import com.example.zenflow.audio.SoundStore
import java.util.Locale

data class AmbientSound(val key: String, val label: String, val emoji: String)

val AMBIENT_SOUNDS: List<AmbientSound> =
    listOf(
        AmbientSound("rain", "Rain", "🌧️"),
        AmbientSound("ocean", "Ocean", "🌊"),
        AmbientSound("forest", "Forest", "🌲"),
        AmbientSound("fire", "Campfire", "🔥"),
        AmbientSound("bowl", "Singing bowl", "🔔")
    ) + ToneLibrary.TONES.map { AmbientSound(it.key, it.label, it.emoji) }

/** Indexed catalog of every playable sound: bundled res/raw + synthesized + imports. */
object SoundCatalog {

    private const val MAX_VARIANTS = 40
    private val variantIndex = HashMap<String, List<Int>>()

    fun isTone(key: String) = key.startsWith("tone_")

    fun variants(context: Context, key: String): List<Int> {
        if (isTone(key)) return emptyList()
        variantIndex[key]?.let { return it }

        val res = context.resources
        val pkg = context.packageName
        val found = mutableListOf<Int>()
        for (i in 1..MAX_VARIANTS) {
            for (suffix in listOf(String.format(Locale.US, "%02d", i), i.toString())) {
                val id = res.getIdentifier("${key}_$suffix", "raw", pkg)
                if (id != 0 && id !in found) found.add(id)
            }
        }
        if (found.isEmpty()) {
            val single = res.getIdentifier(key, "raw", pkg)
            if (single != 0) found.add(single)
        }
        variantIndex[key] = found
        return found
    }

    fun hasSounds(context: Context, key: String): Boolean =
        isTone(key) || variants(context, key).isNotEmpty() || SoundStore.hasImport(context, key)

    /** Canonical mix order: built-ins first, imports after, alphabetical. */
    fun orderedKeys(context: Context): List<String> =
        AMBIENT_SOUNDS.map { it.key } + SoundStore.imports(context).map { it.key }

    fun isKnownSound(context: Context, key: String): Boolean =
        AMBIENT_SOUNDS.any { it.key == key } || SoundStore.hasImport(context, key)

    fun emojiFor(key: String): String =
        AMBIENT_SOUNDS.firstOrNull { it.key == key }?.emoji
            ?: if (SoundStore.hasImport(appContextSafe(), key)) "🎧" else "🎵"

    fun labelFor(context: Context, key: String): String =
        AMBIENT_SOUNDS.firstOrNull { it.key == key }?.label
            ?: SoundStore.imports(context).firstOrNull { it.key == key }?.name
            ?: key

    fun describe(context: Context, key: String): String {
        if (SoundStore.hasImport(context, key)) {
            val imp = SoundStore.imports(context).first { it.key == key }
            val size = if (imp.bytes >= 1_000_000)
                String.format(Locale.US, "%.1f MB", imp.bytes / 1_000_000.0)
            else "${imp.bytes / 1000} KB"
            val dur = if (imp.durationMs > 0) " · ${imp.durationMs / 1000}s" else ""
            return "🎧 ${imp.name} — imported · $size$dur · loops with crossfades"
        }
        if (isTone(key)) {
            val spec = ToneLibrary.TONES.firstOrNull { it.key == key } ?: return labelFor(context, key)
            val kb = ToneLibrary.loopBytes(spec) / 1024
            return "${spec.emoji} ${spec.label} — synthesized in-app · seamless ${kb} KB loop"
        }
        val clips = variants(context, key).size
        return if (clips == 0) {
            "${emojiFor(key)} ${labelFor(context, key)} — no clips yet (add ${key}_01.mp3 … to res/raw)"
        } else {
            "${emojiFor(key)} ${labelFor(context, key)} — $clips clips · shuffled endlessly with 4 s crossfades"
        }
    }

    fun clearIndexCache() = variantIndex.clear()

    // describe/emojiFor are sometimes called where a Context is at hand anyway;
    // this shim keeps signature-call sites simple.
    private fun appContextSafe(): Context = AppContextHolder.get()
}

/** Application context holder, set by ZenFlowApp. */
object AppContextHolder {
    @Volatile internal var app: Context? = null
    fun get(): Context = app ?: throw IllegalStateException("AppContextHolder not initialized")
}
