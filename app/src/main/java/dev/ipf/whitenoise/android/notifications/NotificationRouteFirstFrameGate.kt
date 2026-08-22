package dev.ipf.whitenoise.android.notifications

import kotlinx.coroutines.CompletableDeferred

/**
 * Owns the short priority window for one inactive-account notification route.
 *
 * Account-wide chat-list hydration and best-effort account work await this
 * gate, leaving the target conversation's local timeline as the only broad
 * native read on the first-readable-frame path. Every terminal route releases
 * the gate; [release] is deliberately idempotent for supersession and Back.
 */
internal class NotificationRouteFirstFrameGate(
    val requestId: Long,
    val accountRef: String,
) {
    private val released = CompletableDeferred<Unit>()

    val isReleased: Boolean
        get() = released.isCompleted

    suspend fun awaitRelease() = released.await()

    fun release() {
        released.complete(Unit)
    }
}

internal fun shouldDeferNotificationChatListBind(
    gate: NotificationRouteFirstFrameGate?,
    activeAccountRef: String?,
): Boolean = gate?.isReleased == false && gate.accountRef == activeAccountRef
