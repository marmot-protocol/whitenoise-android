package dev.ipf.whitenoise.android.media.sandbox

import java.io.IOException

internal data class RawAvPlaybackSnapshot(
    val status: RawAvPlaybackEngine.Status,
    val prepared: Boolean,
    val playWhenReady: Boolean,
    val playWhenReadyChangeReason: Int,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    val volume: Float,
    val repeat: Boolean,
    val videoWidth: Int,
    val videoHeight: Int,
    val audioRate: Int,
    val audioChannels: Int,
    val error: IOException?,
    val renderedFrame: Long,
)
