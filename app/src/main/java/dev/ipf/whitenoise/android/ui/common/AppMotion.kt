package dev.ipf.whitenoise.android.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/*
 * The app's one motion language. Surfaces that open (routes, Settings cards, search, media) arrive
 * on one decelerating curve and leave on one accelerating curve, with the same two durations
 * everywhere, so every transition reads as the same gesture.
 *
 * Both curves start at rest. An interrupted animation restarts its curve from the current value, so a
 * curve that leaves quickly would jump on its first reversed frame; one that starts at rest turns around.
 */

/** Duration for a surface arriving from its origin. */
internal const val APP_MOTION_OPEN_MILLIS = 340

/** Duration for a surface returning to its origin. */
internal const val APP_MOTION_CLOSE_MILLIS = 280

/** Starts at rest, covers most of the distance early and settles softly. */
internal val AppMotionOpenEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Emphasized accelerate: starts gently and lands in its origin without lingering. */
internal val AppMotionCloseEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
