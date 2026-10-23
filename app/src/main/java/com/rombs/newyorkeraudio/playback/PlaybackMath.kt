package com.rombs.newyorkeraudio.playback

import java.util.Locale

const val SKIP_INTERVAL_MS = 15_000L
val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

internal fun validPlaybackSpeed(speed: Float): Float = speed.takeIf { it.isFinite() && it in 0.25f..4f } ?: 1f

/** Stored/retry positions at or beyond a shortened recording restart it. Explicit seeks use clampPosition. */
internal fun resumePlaybackPosition(positionMs: Long, durationMs: Long): Long =
    if (durationMs > 0 && positionMs >= durationMs) 0 else positionMs.coerceAtLeast(0)

fun clampPosition(positionMs: Long, durationMs: Long): Long =
    if (durationMs > 0) positionMs.coerceIn(0, durationMs) else positionMs.coerceAtLeast(0)

fun skipPosition(positionMs: Long, durationMs: Long, forward: Boolean): Long {
    val current = clampPosition(positionMs, durationMs)
    val target = if (forward) {
        if (current > Long.MAX_VALUE - SKIP_INTERVAL_MS) Long.MAX_VALUE else current + SKIP_INTERVAL_MS
    } else current - SKIP_INTERVAL_MS
    return clampPosition(target, durationMs)
}

fun formatPlaybackTime(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
