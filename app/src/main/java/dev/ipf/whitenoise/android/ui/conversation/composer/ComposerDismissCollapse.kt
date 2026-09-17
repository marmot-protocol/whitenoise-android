package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween

/**
 * Whether the pill lays out its editing row. A dismiss in progress treats lingering focus as already
 * gone, so the compact row starts animating with the keyboard instead of after it; text, a forced
 * layout or a non-automatic height still keep the editing row.
 */
internal fun composerEditingRequested(
    focused: Boolean,
    hasText: Boolean,
    forceEditingLayout: Boolean,
    mode: ComposerExpansionMode,
    dismissInProgress: Boolean,
): Boolean =
    (focused && !dismissInProgress) ||
        hasText ||
        forceEditingLayout ||
        mode != ComposerExpansionMode.Automatic

/**
 * The spec for one piece of pill geometry. Ordinary edits tween over [durationMillis]; the collapse
 * that follows an accepted send snaps, so the pill and the transcript glued to it settle in the frame
 * the text leaves rather than easing down over the lines the message used to occupy.
 */
internal fun <T> composerGeometrySpec(
    collapsedBySend: Boolean,
    durationMillis: Int,
    easing: Easing,
): AnimationSpec<T> = if (collapsedBySend) snap() else tween(durationMillis, easing = easing)
