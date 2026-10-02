package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Pixels and a legacy URL belong to the same current, explicitly owned presentation. [animationKey] is
 * set only when [image] is a stored person's profile picture, never a group-owned image, and names the
 * loader entry whose animated source may play over it.
 */
internal data class GroupAvatarPresentation(
    val image: ImageBitmap?,
    val pictureUrl: String?,
    val readOriginalBytes: (suspend () -> ByteArray?)? = null,
    val animationKey: String? = null,
)
