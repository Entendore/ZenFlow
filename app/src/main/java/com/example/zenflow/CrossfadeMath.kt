package com.example.zenflow

/** Pure crossfade timing math — unit-testable without audio hardware. */
object CrossfadeMath {

    fun shouldBegin(currentPositionMs: Int, trackDurationMs: Int, crossfadeMs: Long = 4000L): Boolean {
        if (trackDurationMs <= 0) return false
        return trackDurationMs - currentPositionMs <= fadeDurationMs(trackDurationMs, crossfadeMs)
    }

    fun fadeDurationMs(trackDurationMs: Int, crossfadeMs: Long = 4000L): Long =
        if (trackDurationMs <= 0) crossfadeMs
        else minOf(crossfadeMs, trackDurationMs / 2L)

    fun progress(elapsedMs: Long, fadeMs: Long): Float =
        (elapsedMs.toFloat() / fadeMs.coerceAtLeast(1L)).coerceIn(0f, 1f)

    fun volumes(t: Float): Pair<Float, Float> {
        val p = t.coerceIn(0f, 1f)
        return (1f - p) to p
    }
}
