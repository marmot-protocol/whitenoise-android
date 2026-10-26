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
 * following an admitted text flight shares its clock; every other accepted send snaps. The tail owner keeps the
 * transcript anchored while the empty input gradually reaches its minimal geometry.
 */
internal fun <T> composerGeometrySpec(
    collapsedBySend: Boolean,
    animatedSendCollapse: Boolean = false,
    durationMillis: Int,
    easing: Easing,
): AnimationSpec<T> =
    if (collapsedBySend && !animatedSendCollapse) {
        snap()
    } else {
        tween(
            if (collapsedBySend) {
                dev.ipf.whitenoise.android.ui.conversation.COMPOSER_SEND_MOTION_MILLIS
            } else {
                durationMillis
            },
            easing = easing,
        )
    }
