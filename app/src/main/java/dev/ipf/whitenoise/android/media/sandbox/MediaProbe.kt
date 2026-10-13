package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap

internal data class MediaProbe(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val poster: Bitmap?,
)
