package com.example.zenflow

import android.content.Context

/** Persisted-mix parsing/encoding + the diff-based library/preset→player sync. */
object MixSync {

    fun parse(context: Context, encoded: String?): LinkedHashMap<String, Int> {
        val raw = LinkedHashMap<String, Int>()
        if (!encoded.isNullOrBlank()) {
            encoded.split(",").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2 &&
                    SoundCatalog.isKnownSound(context, parts[0]) &&
                    SoundCatalog.hasSounds(context, parts[0])
                ) {
                    raw[parts[0]] = parts[1].toIntOrNull()?.coerceIn(0, 100) ?: 60
                }
            }
        }
        val ordered = SoundCatalog.orderedKeys(context).filter { it in raw }
        val out = LinkedHashMap<String, Int>()
        ordered.forEach { out[it] = raw.getValue(it) }
        return out
    }

    fun encode(
        mix: Map<String, Int>,
        orderedKeys: List<String> = AMBIENT_SOUNDS.map { it.key }
    ): String = orderedKeys.filter { it in mix }.joinToString(",") { "${it}:${mix[it]}" }

    data class Diff(
        val removals: List<String>,
        val volumeChanges: Map<String, Int>,
        val additions: Map<String, Int>
    ) {
        val isEmpty: Boolean
            get() = removals.isEmpty() && volumeChanges.isEmpty() && additions.isEmpty()
    }

    fun diff(current: Map<String, Int>, target: Map<String, Int>): Diff = Diff(
        removals = current.keys.filter { it !in target },
        volumeChanges = target.filter { (k, v) -> k in current && current[k] != v },
        additions = target.filter { (k, v) -> k !in current }
    )
}
