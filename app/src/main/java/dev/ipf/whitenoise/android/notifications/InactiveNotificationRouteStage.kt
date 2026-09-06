package dev.ipf.whitenoise.android.notifications

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Waits only for account-local readiness, not slower process-scope follow-up work. */
internal suspend fun awaitNotificationAccountActivationBoundary(startActivation: (onLocalReady: () -> Unit) -> Unit) {
    val localReady = CompletableDeferred<Unit>()
    startActivation { localReady.complete(Unit) }
    localReady.await()
}

/**
 * True when a request's preload never left Loading — the staged route died
 * before publishing a terminal state. The caller must then clear the preload
 * and release the routing overlay, or the direct-load branch's Loading case
 * pins the loading screen forever while the request-id dedupe guard blocks a
 * retry of the same tap.
 */
internal fun notificationPreloadStuckLoading(
    preload: NotificationMessagePreload<*>?,
    key: NotificationMessagePreloadKey?,
): Boolean = preload.stateFor(key) is NotificationMessagePreloadState.Loading

/** A broad-list absence cannot outrun an exact in-flight or completed target read. */
internal fun notificationMessageRouteChatListReady(
    chatListReady: Boolean,
    targetPresent: Boolean,
    preloadState: NotificationMessagePreloadState<*>?,
): Boolean =
    chatListReady &&
        (
            targetPresent ||
                (
                    preloadState !is NotificationMessagePreloadState.Loading &&
                        preloadState !is NotificationMessagePreloadState.Ready
                )
        )

/** A failed pre-activation read gets one exact retry after the target account becomes active. */
internal fun shouldRetryNotificationMessageLoadAfterActivation(
    preloadState: NotificationMessagePreloadState<*>?,
    routingRequestId: Long,
    retriedRequestId: Long?,
): Boolean =
    preloadState is NotificationMessagePreloadState.Failed &&
        retriedRequestId != routingRequestId

/**
 * Runs the notification's targeted local read alongside account activation.
 *
 * [CoroutineStart.UNDISPATCHED] guarantees the read is entered before activation
 * begins; the SQLite-backed production read suspends while Marmot services it.
 *
 * The read's result is handed to [onPreload] the moment it completes — before
 * activation finishes — so a locally available conversation can open instantly
 * while the switch settles behind it. Publication happens only when
 * [isCurrent] still identifies this exact tap, preventing a late completion
 * from an older or process-recreated route. Activation is always awaited before
 * returning so the caller's post-activation reconciliation still runs.
 */
internal suspend fun <T> runInactiveNotificationRouteStage(
    key: NotificationMessagePreloadKey,
    loadTarget: suspend () -> T,
    activateAccount: suspend () -> Unit,
    isCurrent: () -> Boolean,
    onPreload: (NotificationMessagePreload<T>) -> Unit,
): Unit =
    coroutineScope {
        val preload =
            async(start = CoroutineStart.UNDISPATCHED) {
                loadNotificationMessageDirectly(loadTarget)
            }
        val activation = launch { activateAccount() }
        val outcome = preload.await()
        if (isCurrent()) {
            val state =
                when (outcome) {
                    is NotificationMessageDirectLoadOutcome.OpenConversation ->
                        NotificationMessagePreloadState.Ready(outcome.item)
                    NotificationMessageDirectLoadOutcome.AwaitChatList ->
                        NotificationMessagePreloadState.Failed
                }
            onPreload(NotificationMessagePreload(key = key, state = state))
        }
        activation.join()
    }
