package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationsSubscription
import dev.ipf.whitenoise.android.notifications.NotificationReactionSendOutcome
import dev.ipf.whitenoise.android.notifications.NotificationReplySendOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Only proven connect-phase relay failures are safe to resend automatically.
 * Other exceptions may be terminal or may occur after publication.
 */
internal fun notificationReplySendFailureOutcome(throwable: Throwable): NotificationReplySendOutcome =
    if (isTransientRelaySendError(throwable)) {
        NotificationReplySendOutcome.RetryableFailure
    } else {
        NotificationReplySendOutcome.NonRetryableFailure
    }

internal fun notificationReactionSendFailureOutcome(throwable: Throwable): NotificationReactionSendOutcome =
    if (isTransientRelaySendError(throwable)) {
        NotificationReactionSendOutcome.RetryableFailure
    } else {
        NotificationReactionSendOutcome.NonRetryableFailure
    }

internal data class ConversationNotificationTarget(
    val accountRef: String,
    val groupIdHex: String,
)

/**
 * The conversation whose pending tray cards should be cleared when a chat is
 * opened, or null when there's nothing concrete to dismiss.
 *
 * Opening a conversation must clear its notifications on the first open,
 * independent of whether the read watermark advances (issue #803). The
 * mark-read path can be deduped/swallowed or race a not-yet-ready read anchor
 * on a cold open, so this gives [WhiteNoiseAppState.setActiveConversation] a
 * target it can dismiss immediately and unconditionally. Returns a target only
 * when both the active account and the opened group are nonblank.
 */
internal fun conversationOpenDismissalTarget(
    activeAccountRef: String?,
    groupIdHex: String?,
): ConversationNotificationTarget? {
    val account = activeAccountRef?.takeIf { it.isNotBlank() } ?: return null
    val group = groupIdHex?.takeIf { it.isNotBlank() } ?: return null
    return ConversationNotificationTarget(account, group)
}

/**
 * The still-open conversation that becomes visible when the app returns to the
 * foreground. Backgrounding deliberately retains the active conversation, so
 * an unchanged Compose effect does not call [WhiteNoiseAppState.setActiveConversation]
 * again on resume. Do not clear its tray cards while the app lock covers it.
 */
internal fun visibleConversationDismissalTarget(
    appInForeground: Boolean,
    appLockScreenVisible: Boolean,
    activeAccountRef: String?,
    groupIdHex: String?,
): ConversationNotificationTarget? {
    if (!appInForeground || appLockScreenVisible) return null
    return conversationOpenDismissalTarget(activeAccountRef, groupIdHex)
}

internal suspend fun dismissConversationNotificationsOnOpen(
    activeAccountRef: String?,
    groupIdHex: String?,
    dismissConversationNotifications: suspend (String, String) -> Unit,
) {
    conversationOpenDismissalTarget(activeAccountRef, groupIdHex)?.let { target ->
        dismissConversationNotifications(target.accountRef, target.groupIdHex)
    }
}

/**
 * A live notification subscription is a healthy local broadcast receiver and
 * survives relay connectivity changes. Reuse the listener job so queued or
 * in-flight updates are never destroyed; if it is backing off, reconnect wakes
 * that same job and awaits the receiver state it owns.
 */
internal suspend fun awaitActiveNotificationReceiver(
    isReceiverActive: () -> Boolean,
    listenerJob: Job,
    awaitReceiverActive: suspend () -> Unit,
): Boolean {
    if (isReceiverActive()) return true
    return coroutineScope {
        val ready =
            async(start = CoroutineStart.UNDISPATCHED) {
                awaitReceiverActive()
                true
            }
        try {
            select {
                ready.onAwait { it }
                listenerJob.onJoin { isReceiverActive() }
            }
        } finally {
            ready.cancel()
        }
    }
}

/**
 * Establish the process-owned notification receiver at cold startup and wait
 * only for the bounded bootstrap budget. The listener itself outlives this
 * caller: timeout or bootstrap cancellation leaves its existing retry loop in
 * [notificationJob], while repeated startup callers reuse the same job.
 */
internal suspend fun awaitNotificationReceiverForStartup(
    notificationJob: NotificationJobSlot,
    receiverActive: StateFlow<Boolean>,
    receiverRetryWake: MutableStateFlow<Long>,
    timeoutMillis: Long,
    launchListener: () -> Job,
): Boolean {
    val listenerJob = notificationJob.currentOrStart(launchListener) ?: return false
    if (!receiverActive.value) receiverRetryWake.update { it + 1L }
    return withTimeoutOrNull(timeoutMillis) {
        awaitActiveNotificationReceiver(
            isReceiverActive = { receiverActive.value },
            listenerJob = listenerJob,
            awaitReceiverActive = { receiverActive.first { it } },
        )
    } ?: false
}

internal suspend fun awaitNotificationRetryWindow(
    retryWake: StateFlow<Long>,
    capturedGeneration: Long,
    backoffMillis: Long,
) {
    withTimeoutOrNull(backoffMillis) {
        retryWake.first { it != capturedGeneration }
    }
}

internal interface AppNotificationSubscription {
    suspend fun next(): NotificationUpdateFfi?

    fun close()
}

internal class NativeNotificationSubscription(
    private val delegate: NotificationsSubscription,
) : AppNotificationSubscription {
    override suspend fun next(): NotificationUpdateFfi? = delegate.next()

    override fun close() = delegate.close()
}

internal suspend fun subscribeToNotifications(marmot: MarmotInterface): AppNotificationSubscription =
    NativeNotificationSubscription(marmot.subscribeNotifications())

internal class NotificationJobSlot {
    private val lock = Any()
    private var job: Job? = null
    private var cancellation: CompletableDeferred<Unit>? = null

    fun isActive(): Boolean = synchronized(lock) { job?.isActive == true }

    // Called while holding [lock]; [start] must only enqueue work and return promptly.
    fun startIfInactive(start: () -> Job) {
        currentOrStart(start)
    }

    fun currentOrStart(start: () -> Job): Job? =
        synchronized(lock) {
            if (cancellation != null) return@synchronized null
            job?.takeIf { it.isActive } ?: start().also { job = it }
        }

    suspend fun cancelAndJoin() {
        var ownsCancellation = false
        var ownedJob: Job? = null
        val completion =
            synchronized(lock) {
                cancellation
                    ?: CompletableDeferred<Unit>().also {
                        cancellation = it
                        ownsCancellation = true
                        ownedJob = job
                        job = null
                    }
            }
        if (!ownsCancellation) {
            completion.await()
            return
        }
        try {
            withContext(NonCancellable) {
                ownedJob?.cancelAndJoin()
            }
        } finally {
            synchronized(lock) {
                if (cancellation === completion) cancellation = null
            }
            completion.complete(Unit)
        }
    }
}
