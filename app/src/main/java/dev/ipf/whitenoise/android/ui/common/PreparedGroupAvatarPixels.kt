package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.graphics.ImageBitmap

/** Short-lived decoded-pixel pins for at most one bounded visible working set, not a protocol cache. */
internal data class PreparedGroupAvatarPixels(
    val accountRef: String?,
    val runtimeGeneration: Int,
    val lifetime: Long,
    val images: Map<String, ImageBitmap>,
)
