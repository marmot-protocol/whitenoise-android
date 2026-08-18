package dev.ipf.whitenoise.android.notifications

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Waits only for account-local readiness, not slower process-scope follow-up work. */
internal suspend fun awaitNotificationAccountActivationBoundary(startActivation: (onLocalReady: () -> Unit) -> Unit) {
    val localReady = CompletableDeferred<Unit>()
    startActivation { localReady.complete(Unit) }
    localReady.await()
}

/** A broad-list absence cannot outrun an exact in-flight or completed target read. */
internal fun notificationMessageRouteChatListReady(
    chatListReady: Boolean,
    targetPresent: Boolean,
    preloadState: NotificationMessagePreloadState<*>?,
): Boolean =
    chatListReady &&
        (
            targetPresent ||
                preloadState !is NotificationMessagePreloadState.Loading &&
                preloadState !is NotificationMessagePreloadState.Ready
        )

/**
 * Runs the notification's targeted local read alongside account activation.
 *
 * [CoroutineStart.UNDISPATCHED] guarantees the read is entered before activation
 * begins; the SQLite-backed production read suspends while Marmot services it.
 * The result is published only when [isCurrent] still identifies this exact tap,
 * preventing a late completion from an older or process-recreated route.
 */
internal suspend fun <T> runInactiveNotificationRouteStage(
    key: NotificationMessagePreloadKey,
    loadTarget: suspend () -> T,
    activateAccount: suspend () -> Unit,
    isCurrent: () -> Boolean,
): NotificationMessagePreload<T>? =
    coroutineScope {
        val preload =
            async(start = CoroutineStart.UNDISPATCHED) {
                loadNotificationMessageDirectly(loadTarget)
            }
        activateAccount()
        val outcome = preload.await()
        if (!isCurrent()) {
            return@coroutineScope null
        }
        val state =
            when (outcome) {
                is NotificationMessageDirectLoadOutcome.OpenConversation ->
                    NotificationMessagePreloadState.Ready(outcome.item)
                NotificationMessageDirectLoadOutcome.AwaitChatList ->
                    NotificationMessagePreloadState.Failed
            }
        NotificationMessagePreload(key = key, state = state)
    }
