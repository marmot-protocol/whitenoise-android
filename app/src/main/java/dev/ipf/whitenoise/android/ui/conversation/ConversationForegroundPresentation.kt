package dev.ipf.whitenoise.android.ui.conversation

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WarmResumeConversationPhase
import dev.ipf.whitenoise.android.state.WarmResumeTrace
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

/** Freshness may yield only to an admitted, unlocked frame belonging to the same live controller. */
internal fun canPresentRetainedConversationFrame(
    anchored: Boolean,
    ownsCurrentPresentation: Boolean,
    appReady: Boolean,
    lockVisible: Boolean,
): Boolean = anchored && ownsCurrentPresentation && appReady && !lockVisible

/** Distinguishes the lifecycle's one requested focus edge from foreground user focus intent. */
internal class ConversationForegroundFocusIntent {
    private var expectedAutomaticFocus: Boolean? = null

    fun pause() {
        expectedAutomaticFocus = null
    }

    fun resume(
        restoreFocus: Boolean,
        clearFocus: Boolean,
    ) {
        expectedAutomaticFocus =
            when {
                restoreFocus -> true
                clearFocus -> false
                else -> null
            }
    }

    fun supersedesRestore(
        focused: Boolean,
        resumed: Boolean,
    ): Boolean {
        if (!resumed) return false
        val expected = expectedAutomaticFocus
        if (expected != null) {
            // Pause can report its old focus after ON_RESUME but before the requested edge.
            // Keep that edge armed until it is observed; Back/typing/navigation retire their
            // restore tokens directly, independently of these automatic focus callbacks.
            if (focused == expected) expectedAutomaticFocus = null
        }
        return expected == null
    }
}

private typealias ForegroundSettlePredicate = (ConversationForegroundSettleState) -> Boolean

/** A retired or superseded receipt cannot count as a commit; the shared presentation deadline owns fallback. */
internal suspend fun awaitCommittedConversationTimeline(receipt: Deferred<Boolean>?) {
    if (receipt?.await() == false) {
        WarmResumeTrace.conversationPhase(WarmResumeConversationPhase.LocalHandoffSuperseded)
        awaitCancellation()
    }
}

/** Keeps an unsafe retained frame behind the existing handoff/admission deadline. */
internal suspend fun awaitConversationForegroundAdmission(
    preDrawSignals: ReceiveChannel<Unit>,
    canPresent: () -> Boolean,
    timeoutMillis: Long,
    awaitLocalTimeline: suspend () -> Unit,
) {
    withTimeoutOrNull(timeoutMillis) {
        awaitLocalTimeline()
        // A missing handoff receipt is not proof that this controller is current and unlocked.
        while (!canPresent()) preDrawSignals.receive()
    }
}

/** Layout and IME animation state required before the foreground frame can be exposed. */
internal data class ConversationForegroundSettleState(
    val geometry: ConversationForegroundGeometry,
    val imeTargetBottomPx: Int,
    val bottomChromeMeasured: Boolean,
    val timelineMeasured: Boolean = true,
) {
    /** Whether every measured surface agrees on one coherent viewport geometry. */
    fun isGeometrySettled(): Boolean =
        geometry.viewportHeightPx > 0 &&
            bottomChromeMeasured &&
            geometry.imeBottomPx == imeTargetBottomPx

    /** Whether measured current content and coherent geometry match the IME visibility requested at resume. */
    fun isSettled(expectedImeVisible: Boolean): Boolean =
        isGeometrySettled() &&
            timelineMeasured &&
            (geometry.imeBottomPx > 0) == expectedImeVisible
}

/**
 * Waits for the requested IME state for one bounded liveness window. When that
 * deadline expires, [onSettleDeadlineExpired] must open the draw gate and
 * schedule a frame immediately. Coherent geometry can then complete at once;
 * otherwise the captured snapshot remains armed until later settled geometry
 * arrives to apply the one deferred correction without blocking presentation.
 */
@Suppress("LongParameterList") // Independent local handoff, geometry and IME inputs share one liveness deadline.
internal suspend fun awaitConversationForegroundPresentation(
    preDrawSignals: ReceiveChannel<Unit>,
    currentState: () -> ConversationForegroundSettleState,
    expectedImeVisible: Boolean,
    expectedVisibilityTimeoutMillis: Long,
    onSettleDeadlineExpired: () -> Unit = {},
    awaitLocalTimeline: suspend () -> Unit = {},
): ConversationForegroundSettleState {
    /** Returns the first pre-draw state accepted by [predicate]. */
    suspend fun awaitState(predicate: ForegroundSettlePredicate): ConversationForegroundSettleState {
        while (true) {
            preDrawSignals.receive()
            val state = currentState()
            if (predicate(state)) return state
        }
    }

    val requestedPresentation =
        withTimeoutOrNull(expectedVisibilityTimeoutMillis) {
            awaitLocalTimeline()
            awaitState { it.isSettled(expectedImeVisible) }
        }
    if (requestedPresentation != null) return requestedPresentation

    onSettleDeadlineExpired()
    return currentState().takeIf { it.isGeometrySettled() }
        ?: awaitState { it.isGeometrySettled() }
}

/**
 * Keeps Android on its task snapshot while the foreground transaction owns
 * presentation. Every pre-draw pumps [onPreDraw] — the settle wait re-checks on
 * each signal, including after the deadline opens the gate, when blocked
 * pre-draws no longer occur but a deferred correction may still be armed.
 */
internal class ConversationForegroundDrawGate(
    private val isBlocked: () -> Boolean,
    private val onPreDrawSignal: () -> Unit = {},
) : ViewTreeObserver.OnPreDrawListener {
    /** Signals each frame attempt and exposes it only after the live gate opens. */
    override fun onPreDraw(): Boolean {
        onPreDrawSignal()
        val blocked = isBlocked()
        ConversationTranscriptDrawProbe.foregroundGateObserved(blocked)
        return !blocked
    }
}

/** Installs one root pre-draw gate for the lifetime of the mounted conversation. */
@Suppress("FunctionNaming")
@Composable
internal fun ConversationForegroundDrawGateEffect(
    isBlocked: () -> Boolean,
    onPreDraw: () -> Unit,
) {
    val view = LocalView.current
    val currentIsBlocked by rememberUpdatedState(isBlocked)
    val currentOnPreDraw by rememberUpdatedState(onPreDraw)

    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val gate =
            ConversationForegroundDrawGate(
                isBlocked = { currentIsBlocked() },
                onPreDrawSignal = { currentOnPreDraw() },
            )
        observer.addOnPreDrawListener(gate)
        onDispose {
            if (observer.isAlive) observer.removeOnPreDrawListener(gate)
        }
    }
}

/** Schedules the first root draw after the foreground gate opens. */
internal fun requestConversationForegroundFrame(view: android.view.View) {
    view.postInvalidateOnAnimation()
}

/** Requests native measurement when a closed draw gate would otherwise starve fixed-size Compose remeasure. */
@Suppress("FunctionNaming")
@Composable
internal fun ConversationForegroundTimelineMeasureEffect(
    viewport: ConversationTimelineViewport?,
    publication: List<TimelineMessage>,
    presentationBlocked: Boolean,
) {
    val view = LocalView.current
    SideEffect {
        // Compose may defer descendant remeasure until dispatchDraw. Force a native layout pass
        // only for the unmeasured publication; the existing freshness fence still owns drawing.
        if (presentationBlocked && viewport?.hasMeasuredTimeline(publication) == false) {
            view.requestLayout()
        }
    }
}
