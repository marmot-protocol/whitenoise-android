package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context

/**
 * Longest output edge for playback frames. A frame never shows more pixels than the display's longest side, so a 4K
 * source converts, crosses shared memory and uploads at screen size instead of at the 4096-pixel protocol maximum.
 */
internal fun playbackEdge(context: Context): Int {
    val metrics = context.resources.displayMetrics
    return playbackEdge(maxOf(metrics.widthPixels, metrics.heightPixels))
}

/** Clamps a display's longest side to the protocol range, keeping a usable floor for odd or missing metrics. */
internal fun playbackEdge(displayLongestPx: Int): Int =
    displayLongestPx.coerceIn(MIN_PLAYBACK_EDGE, AvStreamProtocol.MAX_EDGE)

private const val MIN_PLAYBACK_EDGE = 1280
