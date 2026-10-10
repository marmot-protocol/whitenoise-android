package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.notifications.NotificationRouteFirstFrameGate
import kotlinx.coroutines.delay

/**
 * Longest a notification route may defer the chat-list bind waiting for its first conversation frame.
 *
 * A route that reaches its first frame, fails, is superseded or is backed out releases long before this, and
 * the warm budget is one second. The bound only guards against a route that stalls with every release path
 * silent, since a bind deferred forever would leave the app on its loading surface (#586).
 */
internal const val NOTIFICATION_FIRST_FRAME_MAX_HOLD_MS = 6_000L

/**
 * Process-owned first-frame priority for one notification-routed conversation (#586).
 *
 * The gate used to live in the shell's composition, so unmounting the shell released it and its
 * state vanished with it. Here it survives that unmount and ends only for a reason the route owns:
 * its first frame, a newer request, a failed or never-landing activation, Back, or the runtime
 * generation it was issued under going away. A release is idempotent and fenced by request, so a
 * stale request can never release its successor's gate.
 */
internal class NotificationFirstFramePriority(
    private val runtimeGeneration: () -> Int,
) {
    private var held by mutableStateOf<HeldGate?>(null)

    /**
     * The live gate, or null once it was released or its runtime generation is gone. A gate that
     * outlived its runtime is released here, so work still waiting on it can finish.
     */
    val gate: NotificationRouteFirstFrameGate?
        get() {
            val current = held ?: return null
            if (current.runtimeGeneration == runtimeGeneration()) return current.gate
            current.gate.release()
            return null
        }

    /**
     * Request id of the gate now held, or null once it was released or its runtime generation is gone. It reads
     * through [gate], so a gate that outlived its runtime is released here as well and the watchdog never has to
     * time it. Read in composition so the watchdog restarts for each request.
     */
    val heldRequestId: Long?
        get() = gate?.requestId

    /** Holds [gate] for the current runtime, releasing any predecessor that a newer request replaced. */
    fun begin(gate: NotificationRouteFirstFrameGate) {
        val previous = held
        held = HeldGate(gate, runtimeGeneration())
        if (previous != null && previous.gate !== gate) previous.gate.release()
    }

    /**
     * Releases the held gate. With a [requestId] only that request's gate is released, so a stale
     * callback cannot end the window a newer tap now owns.
     */
    fun release(requestId: Long? = null) {
        val current = held ?: return
        if (requestId != null && current.gate.requestId != requestId) return
        current.gate.release()
        held = null
    }

    /**
     * Whether a notification-owned retained conversation must keep the shell composed. It does while that
     * conversation is selected and the chat list's local projection is not yet loaded: without the shell the
     * root falls back to a loading surface, and the conversation, which only the shell can build, waits behind
     * that bind (#586). The same holds for a tap on the already-active account, whose list may still be binding.
     * Never under app lock, which composes no shell and leaves the staged tap unconsumed.
     */
    fun ownsShell(route: RetainedNotificationRouteState): Boolean {
        if (route.appLockScreenVisible || route.localProjectionAvailable || route.activeAccountRef == null) return false
        return route.conversationSelected && route.openContext.notificationRouteTraceRequestId != null
    }

    private data class HeldGate(
        val gate: NotificationRouteFirstFrameGate,
        val runtimeGeneration: Int,
    )
}

/** What the shell route currently is, passed in so the ownership decision stays pure and testable. */
internal data class RetainedNotificationRouteState(
    val conversationSelected: Boolean,
    val openContext: ConversationOpenContext,
    val activeAccountRef: String?,
    val localProjectionAvailable: Boolean,
    val appLockScreenVisible: Boolean,
)

/**
 * Releases a held first-frame priority window that no route event ended within [maxHoldMs].
 *
 * It lives in the root composition, which no shell unmount removes, restarts its timer for every request and
 * is cancelled by any earlier release, so a normal route never reaches it.
 */
@Composable
@Suppress("FunctionNaming")
internal fun NotificationFirstFrameWatchdog(
    priority: NotificationFirstFramePriority,
    maxHoldMs: Long = NOTIFICATION_FIRST_FRAME_MAX_HOLD_MS,
) {
    val requestId = priority.heldRequestId
    LaunchedEffect(requestId) {
        if (requestId == null) return@LaunchedEffect
        delay(maxHoldMs)
        priority.release(requestId)
    }
}
