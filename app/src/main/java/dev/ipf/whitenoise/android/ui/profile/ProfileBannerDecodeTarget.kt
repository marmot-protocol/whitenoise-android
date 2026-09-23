package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * The physical-pixel width a full-width profile banner will actually be drawn at (#2762).
 *
 * Every banner surface passes this to the loader so its decode is derived from the rendered box and
 * the device's density rather than from the avatar cap the surfaces used to share.
 */
@Composable
internal fun profileBannerTargetWidthPx(availableWidth: Dp): Int {
    val density = LocalDensity.current
    return with(density) { availableWidth.roundToPx() }
}
