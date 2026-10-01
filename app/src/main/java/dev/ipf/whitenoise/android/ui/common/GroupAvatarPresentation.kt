package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.graphics.ImageBitmap

/** Pixels and a legacy URL belong to the same current, explicitly owned presentation. */
internal data class GroupAvatarPresentation(
    val image: ImageBitmap?,
    val pictureUrl: String?,
    val readOriginalBytes: (suspend () -> ByteArray?)? = null,
)
