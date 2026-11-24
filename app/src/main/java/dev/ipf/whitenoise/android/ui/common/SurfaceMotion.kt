package dev.ipf.whitenoise.android.ui.common

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/** The timing of an anchored surface traveling to its destination or returning to its source. */
internal enum class SurfaceMotion(
    val durationMillis: Int,
    val easing: Easing,
) {
    Open(APP_MOTION_OPEN_MILLIS, AppMotionOpenEasing),
    Close(APP_MOTION_CLOSE_MILLIS, AppMotionCloseEasing),
    ;

    fun <T> spec(): TweenSpec<T> = tween(durationMillis, easing = easing)
}
