@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Delete
import dev.ipf.whitenoise.android.ui.icons.filled.Lock
import dev.ipf.whitenoise.android.ui.navigation.LocalDictationLauncherOrigin
import kotlin.math.roundToInt

private val DictationHoldLockDistance = 80.dp
private val DictationHoldCancelDistance = 120.dp
private val DictationHoldHintSize = 36.dp
private val DictationHoldHintGap = 56.dp

/**
 * Transient hold presentation owned by a stable composer ancestor. The dictation button leaves composition as soon
 * as dictation starts, so the ancestor owns the held pointer and hit-tests the launcher's reported window bounds.
 */
@Stable
internal class DictationHoldState {
    internal var container: LayoutCoordinates? = null

    var anchor by mutableStateOf<Rect?>(null)
        private set
    var verticalOffsetPx by mutableFloatStateOf(0f)
        private set
    var willLock by mutableStateOf(false)
        private set
    var willCancel by mutableStateOf(false)
        private set

    internal fun begin(bounds: Rect) {
        anchor = bounds
        track(0f, willLock = false, willCancel = false)
    }

    internal fun track(
        verticalOffsetPx: Float,
        willLock: Boolean,
        willCancel: Boolean,
    ) {
        this.verticalOffsetPx = verticalOffsetPx
        this.willLock = willLock
        this.willCancel = willCancel
    }

    internal fun end() {
        anchor = null
        track(0f, willLock = false, willCancel = false)
    }
}

internal enum class DictationHoldAction { Continue, Lock, Send, Cancel }

/** Mirrors the voice-note hold: up locks, left arms cancel, and release sends unless cancel is armed. */
internal fun dictationHoldAction(
    deltaX: Float,
    deltaY: Float,
    released: Boolean,
    lockPx: Float,
    cancelPx: Float,
): DictationHoldAction {
    val cancelArmed = -deltaX > cancelPx
    return when {
        !cancelArmed && -deltaY > lockPx -> DictationHoldAction.Lock
        !released -> DictationHoldAction.Continue
        cancelArmed -> DictationHoldAction.Cancel
        else -> DictationHoldAction.Send
    }
}

/**
 * Long-pressing the dictation launcher starts dictation. Releasing sends the transcript, sliding left before release
 * cancels, and sliding up leaves the session running with the ordinary dictation controls. A short tap reaches the
 * button unchanged. [startCapture] returns the controller only when this composer now owns a starting or open
 * capture; release acts only on that same session.
 */
@Composable
internal fun dictationHoldGesture(
    state: DictationHoldState,
    startCapture: (() -> ConversationDictationController?)?,
): Modifier {
    val latestStart by rememberUpdatedState(startCapture)
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val launcher by rememberUpdatedState(LocalDictationLauncherOrigin.current)
    return Modifier
        .onGloballyPositioned { state.container = it }
        .pointerInput(state) {
            val lockPx = DictationHoldLockDistance.toPx()
            val cancelPx = DictationHoldCancelDistance.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val container = state.container?.takeIf { it.isAttached } ?: return@awaitEachGesture
                val bounds = launcher?.boundsInWindow ?: return@awaitEachGesture
                if (latestStart == null || !bounds.contains(container.localToWindow(down.position))) {
                    return@awaitEachGesture
                }
                val endedEarly =
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        awaitReleaseOrSlop(down.id, down.position, viewConfiguration.touchSlop)
                    }
                if (endedEarly != null) return@awaitEachGesture
                val controller = latestStart?.invoke()
                if (controller == null) {
                    // Disclosure, permission or provider setup took over; the button must not also see a tap.
                    consumeUntilUp(down.id)
                    return@awaitEachGesture
                }
                val session = controller.state.sessionId
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                state.begin(bounds)
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // A stolen pointer keeps the session open with its ordinary controls rather than guessing.
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        val delta = change.position - down.position
                        val released = change.changedToUp() || !change.pressed
                        val cancelArmed = -delta.x > cancelPx
                        if (cancelArmed && !state.willCancel) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        state.track(delta.y, willLock = !cancelArmed && -delta.y > lockPx * 0.6f, cancelArmed)
                        when (dictationHoldAction(delta.x, delta.y, released, lockPx, cancelPx)) {
                            DictationHoldAction.Continue -> Unit
                            DictationHoldAction.Lock -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (!released) consumeUntilUp(down.id)
                                break
                            }
                            DictationHoldAction.Send -> {
                                // The controller rejects Send outside live recognition, keeping the bubble.
                                if (controller.state.sessionId == session) controller.send()
                                break
                            }
                            DictationHoldAction.Cancel -> {
                                if (controller.state.sessionId == session) controller.cancel()
                                break
                            }
                        }
                    }
                } finally {
                    state.end()
                }
            }
        }
}

/** Returns once the pointer lifts or travels past slop; a long-press timeout around it means the press held still. */
private suspend fun AwaitPointerEventScope.awaitReleaseOrSlop(
    pointer: PointerId,
    origin: Offset,
    slop: Float,
) {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == pointer } ?: return
        if (change.changedToUp() || !change.pressed) return
        if ((change.position - origin).getDistance() > slop) return
    }
}

private suspend fun AwaitPointerEventScope.consumeUntilUp(pointer: PointerId) {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == pointer } ?: return
        change.consume()
        if (change.changedToUp() || !change.pressed) return
    }
}

/** The voice-note lock hint, rising above the held dictation button; it turns into a delete mark once cancel arms. */
@Composable
internal fun DictationHoldHint(state: DictationHoldState) {
    val anchor = state.anchor ?: return
    val armed = state.willLock
    val cancel = state.willCancel
    Box(
        modifier =
            Modifier
                .absoluteOffset {
                    val container = state.container?.takeIf { it.isAttached } ?: return@absoluteOffset IntOffset.Zero
                    val local = container.windowToLocal(anchor.topCenter)
                    val rise = (-state.verticalOffsetPx).coerceIn(0f, DictationHoldLockDistance.toPx())
                    IntOffset(
                        (local.x - DictationHoldHintSize.toPx() / 2f).roundToInt(),
                        (local.y - DictationHoldHintGap.toPx() - rise).roundToInt(),
                    )
                }.size(DictationHoldHintSize)
                .clip(CircleShape)
                .background(
                    when {
                        cancel -> MaterialTheme.colorScheme.errorContainer
                        armed -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ).testTag("composer.dictation.hold.hint"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (cancel) Icons.Default.Delete else Icons.Default.Lock,
            contentDescription = null,
            tint =
                when {
                    cancel -> MaterialTheme.colorScheme.onErrorContainer
                    armed -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(18.dp),
        )
    }
}
