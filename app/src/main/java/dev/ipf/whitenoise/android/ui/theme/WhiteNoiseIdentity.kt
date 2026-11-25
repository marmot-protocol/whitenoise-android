package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.ui.unit.dp

/** Geometry of the identity header shared by group details, person profile and both edit heroes. */
object WhiteNoiseIdentity {
    /** The avatar is this share of the screen width, so it scales with the device. */
    const val AVATAR_WIDTH_FRACTION = 0.32f
    val AvatarMinSize = 104.dp
    val AvatarMaxSize = 152.dp

    /** A long title wraps to this many lines before it ellipsizes. */
    const val NAME_MAX_LINES = 2
}
