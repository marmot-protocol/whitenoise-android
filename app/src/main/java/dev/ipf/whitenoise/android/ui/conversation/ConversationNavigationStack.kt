@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.navigation.floatingDictationCapsuleClearance

/**
 * The transcript's bottom-end navigation controls — Resume read-aloud follow, the mention chip and
 * Jump to newest. They sit 12dp above the composer and, when another chat's dictation capsule floats
 * over this composer, 12dp above that capsule too, so both sets stay readable and tappable (#3584).
 */
@Composable
internal fun BoxScope.ConversationNavigationStack(
    composerOverlap: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = composerOverlap)
                .padding(12.dp)
                .floatingDictationCapsuleClearance(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

internal const val CONVERSATION_NAVIGATION_CONTROL_MOTION_MILLIS = 180

/**
 * Grows a navigation control out of the stack's bottom-end corner and shrinks it back there, while the
 * stack closes the gap on the same clock. Reduced motion shows and removes the control at once.
 */
@Composable
internal fun ColumnScope.ConversationNavigationControl(
    visible: Boolean,
    // Read here, not by the caller, so lifecycle changes recompose only this control.
    motionEnabled: Boolean = rememberConversationMotionEnabled(),
    content: @Composable () -> Unit,
) {
    // The stack's end edge is the left one in RTL.
    val origin = TransformOrigin(if (LocalLayoutDirection.current == LayoutDirection.Rtl) 0f else 1f, 1f)
    val spec = tween<Float>(CONVERSATION_NAVIGATION_CONTROL_MOTION_MILLIS, easing = FastOutSlowInEasing)
    val size = tween<IntSize>(CONVERSATION_NAVIGATION_CONTROL_MOTION_MILLIS)
    AnimatedVisibility(
        visible = visible,
        enter =
            if (motionEnabled) {
                fadeIn(spec) + scaleIn(spec, initialScale = 0.6f, transformOrigin = origin) +
                    expandVertically(size, expandFrom = Alignment.Bottom)
            } else {
                EnterTransition.None
            },
        exit =
            if (motionEnabled) {
                fadeOut(spec) + scaleOut(spec, targetScale = 0.6f, transformOrigin = origin) +
                    shrinkVertically(size, shrinkTowards = Alignment.Bottom)
            } else {
                ExitTransition.None
            },
    ) {
        // A leaving control can no longer be tapped or reached by accessibility services.
        Box(if (visible) Modifier else InertControl) { content() }
    }
}

private val InertControl =
    Modifier
        .clearAndSetSemantics {}
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
