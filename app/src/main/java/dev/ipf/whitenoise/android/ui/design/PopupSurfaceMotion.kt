package dev.ipf.whitenoise.android.ui.design

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_CLOSE_MILLIS
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_OPEN_MILLIS
import dev.ipf.whitenoise.android.ui.common.AppMotionCloseEasing
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled

private const val POPUP_INITIAL_SCALE = 0.88f
private const val POPUP_SCALE_GROWTH = 0.12f
private const val POPUP_PIVOT_CENTER = 0.5f

/** A consumed request stays closed even if its callback leaves the owner mounted. */
private class PopupCloseRequest(
    val action: () -> Unit,
) {
    var consumed = false

    fun complete() {
        if (!consumed) {
            consumed = true
            action()
        }
    }
}

/** One visibility lifetime and growth curve for popup windows and dialog surfaces. */
internal class PopupSurfaceMotion(
    val visible: Boolean,
    private val inputAllowed: () -> Boolean,
    val presence: State<Float>,
    val dismiss: () -> Unit,
    private val requestClose: (() -> Unit) -> Unit,
    private val cancelClose: () -> Unit,
) {
    val acceptsInput: Boolean get() = inputAllowed()

    /** The first terminal action wins synchronously and runs only after the last exit frame. */
    fun close(afterExit: () -> Unit) = requestClose(afterExit)

    /** Forced owner/source navigation must not later execute an accepted button action. */
    fun cancelCompletion() = cancelClose()

    /** Keep the modal window inert during exit without disposing its native focus or IME host. */
    fun inputModifier(): Modifier =
        Modifier
            .then(if (acceptsInput) Modifier else Modifier.clearAndSetSemantics {})
            .onPreviewKeyEvent { !acceptsInput }
            .pointerInput(inputAllowed, acceptsInput) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!acceptsInput) event.changes.forEach { it.consume() }
                    }
                }
            }

    fun modifier(bottomAnchored: Boolean = false): Modifier =
        Modifier.graphicsLayer {
            alpha = presence.value
            scaleX = POPUP_INITIAL_SCALE + POPUP_SCALE_GROWTH * presence.value
            scaleY = scaleX
            transformOrigin = TransformOrigin(POPUP_PIVOT_CENTER, if (bottomAnchored) 1f else POPUP_PIVOT_CENTER)
        }
}

/** Delay a user dismissal until its last frame, even when the caller conditionally owns the window. */
@Composable
@Suppress("LongParameterList", "CyclomaticComplexMethod")
// Visibility, input gating, and accepted completion share the same remembered popup lifetime.
internal fun rememberPopupSurfaceMotion(
    expanded: Boolean,
    onDismiss: () -> Unit,
    ownerKey: Any? = Unit,
    animate: Boolean = true,
    dismissEnabled: () -> Boolean = { true },
    onDismissStart: () -> Unit = {},
): PopupSurfaceMotion {
    val motion = rememberSystemAnimationsEnabled() && animate
    var completion by remember(ownerKey, expanded) { mutableStateOf<PopupCloseRequest?>(null) }
    val inputAllowed = remember(ownerKey, expanded) { { expanded && completion == null } }
    val visibility = remember(ownerKey) { MutableTransitionState(if (motion) false else expanded) }
    visibility.targetState = expanded && completion == null
    val transition = updateTransition(visibility, label = "popup visibility")
    val presence =
        transition.animateFloat(
            transitionSpec = {
                if (!motion) {
                    snap()
                } else if (targetState) {
                    tween(APP_MOTION_OPEN_MILLIS, easing = AppMotionOpenEasing)
                } else {
                    tween(APP_MOTION_CLOSE_MILLIS, easing = AppMotionCloseEasing)
                }
            },
            label = "popup presence",
        ) { if (it) 1f else 0f }
    // Capture the action accepted by this owner. A replacement owner or external removal cancels it.
    LaunchedEffect(ownerKey, motion, visibility.isIdle, visibility.currentState, completion) {
        if (!motion || (visibility.isIdle && !visibility.currentState)) completion?.complete()
    }
    val close: (() -> Unit) -> Unit = { action ->
        if (expanded && completion == null) completion = PopupCloseRequest(action)
    }
    return PopupSurfaceMotion(
        visible =
            if (motion) {
                visibility.targetState || !visibility.isIdle || visibility.currentState
            } else {
                expanded &&
                    completion == null
            },
        inputAllowed = inputAllowed,
        presence = if (motion) presence else rememberUpdatedState(if (expanded && completion == null) 1f else 0f),
        dismiss = {
            if (dismissEnabled() && expanded && completion == null) {
                completion = PopupCloseRequest(onDismiss)
                onDismissStart()
            }
        },
        requestClose = close,
        cancelClose = { completion?.consumed = true },
    )
}
