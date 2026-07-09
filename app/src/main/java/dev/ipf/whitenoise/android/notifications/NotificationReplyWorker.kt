package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.RemoteInput
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG = "DMNotifyReplyWorker"
private const val MAX_REPLY_SEND_ATTEMPTS = 3
private const val REPLY_RETRY_BACKOFF_MS = 10_000L

class NotificationReplyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val work = NotificationReplyWork.from(inputData) ?: return Result.success()
        val app = applicationContext as? WhiteNoiseApplication ?: return retryOrGiveUp(work.action, "application unavailable")
        val appState = app.appState
        if (!appState.notificationActionsAllowed) {
            Log.w(
                TAG,
                "notification reply blocked by app lock group=${work.action.target.groupIdHex.take(8)}",
            )
            return Result.success()
        }

        var sent = false
        var sendDispatched = false
        var keepRecentDispatch = false
        return try {
            appState.ensureNotificationRuntimeStarted()
            sendDispatched = true
            sent =
                appState.sendNotificationReply(
                    accountRef = work.action.target.accountRef,
                    groupIdHex = work.action.target.groupIdHex,
                    text = work.reply,
                )
            keepRecentDispatch = sent
            if (sent) {
                markNotificationReplyReadBestEffort(appState, work.action)
                dismissSentReplyNotification(applicationContext, work.action, work.reply)
            } else {
                Log.w(TAG, "notification reply send failed group=${work.action.target.groupIdHex.take(8)}")
            }
            if (notificationReplyActionHandled(sent = sent)) Result.success() else retryOrGiveUp(work.action, "send returned false")
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            if (sendDispatched) keepRecentDispatch = true
            Log.w(
                TAG,
                "notification reply worker failed group=${work.action.target.groupIdHex.take(8)} " +
                    "message=${work.action.target.messageIdHex.orEmpty().take(8)}",
                throwable,
            )
            retryOrGiveUp(work.action, "worker threw")
        } finally {
            finishNotificationReplyDispatch(work.action, work.reply, keepRecent = keepRecentDispatch)
        }
    }

    private fun retryOrGiveUp(
        action: NotificationAction,
        reason: String,
    ): Result {
        val attemptNumber = runAttemptCount + 1
        if (notificationReplyShouldRetry(attemptNumber)) return Result.retry()
        Log.w(
            TAG,
            "notification reply giving up reason=$reason attempts=$attemptNumber " +
                "group=${action.target.groupIdHex.take(8)} message=${action.target.messageIdHex.orEmpty().take(8)}",
        )
        // Leave the original notification in place for another user retry, but
        // do not mark an exhausted send as WorkManager-successful; failed work is
        // visible in diagnostics instead of being silently hidden as complete.
        return Result.failure()
    }

    companion object {
        fun enqueue(
            context: Context,
            action: NotificationAction,
            reply: String,
        ): Boolean =
            runCatching {
                val request =
                    OneTimeWorkRequestBuilder<NotificationReplyWorker>()
                        .setInputData(notificationReplyWorkData(action, reply))
                        .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            REPLY_RETRY_BACKOFF_MS,
                            TimeUnit.MILLISECONDS,
                        ).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    notificationReplyUniqueWorkName(action, reply),
                    ExistingWorkPolicy.KEEP,
                    request,
                )
                true
            }.onFailure {
                Log.w(
                    TAG,
                    "failed to enqueue notification reply group=${action.target.groupIdHex.take(8)} " +
                        "message=${action.target.messageIdHex.orEmpty().take(8)}",
                    it,
                )
            }.getOrDefault(false)
    }
}

private suspend fun markNotificationReplyReadBestEffort(
    appState: WhiteNoiseAppState,
    action: NotificationAction,
) {
    val failureMessage =
        "reply sent but mark-read failed group=${action.target.groupIdHex.take(8)} " +
            "message=${action.target.messageIdHex.orEmpty().take(8)}"
    val markReadResult =
        try {
            appState.markNotificationMessageRead(
                accountRef = action.target.accountRef,
                groupIdHex = action.target.groupIdHex,
                messageIdHex = action.target.messageIdHex.orEmpty(),
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Log.w(TAG, failureMessage, throwable)
            null
        }
    if (markReadResult == false) {
        Log.w(TAG, failureMessage)
    }
}

internal suspend fun dismissSentReplyNotification(
    appContext: Context,
    action: NotificationAction,
    reply: String,
) {
    val presenter = LocalNotificationPresenter(appContext)
    withContext(NonCancellable) {
        try {
            val completed =
                // Cooperative only, but this now runs in WorkManager instead of
                // inside the BroadcastReceiver goAsync deadline.
                withTimeoutOrNull(REPLY_DISMISS_BUDGET_MS) {
                    // A sent direct reply leaves the notification lifetime-extended
                    // by the system; a bare cancel() can't dismiss it. Signal
                    // "reply handled" (setRemoteInputHistory) to clear the
                    // extension, then cancel. The extension is applied a beat after
                    // the broadcast fires, so retry the re-post until the live
                    // notification appears, then let NMS settle before cancelling.
                    var resolved = false
                    repeat(REPLY_DISMISS_RETRIES) {
                        if (!resolved) {
                            resolved = presenter.markDirectReplyHandled(action.notificationTag, action.notificationId, reply)
                            if (!resolved) delay(REPLY_DISMISS_RETRY_DELAY_MS)
                        }
                    }
                    if (resolved) delay(REPLY_DISMISS_SETTLE_MS)
                    true
                }
            if (completed == null) {
                Log.w(
                    TAG,
                    "notification reply dismiss timed out group=${action.target.groupIdHex.take(8)}",
                )
            }
        } finally {
            presenter.cancel(action.notificationTag, action.notificationId)
        }
    }
}

internal fun notificationReplyTextFrom(intent: Intent): String =
    RemoteInput
        .getResultsFromIntent(intent)
        ?.getCharSequence(NotificationActions.KEY_TEXT_REPLY)
        ?.toString()
        ?.let(::normalizedNotificationReplyText)
        .orEmpty()

private fun normalizedNotificationReplyText(reply: String): String = reply.trim()

internal fun notificationReplyActionHandled(sent: Boolean): Boolean = sent

internal fun notificationReplyShouldRetry(attemptNumber: Int): Boolean = attemptNumber < MAX_REPLY_SEND_ATTEMPTS

internal fun notificationReplyDismissBudgetMs(
    retries: Int = REPLY_DISMISS_RETRIES,
    retryDelayMs: Long = REPLY_DISMISS_RETRY_DELAY_MS,
    settleMs: Long = REPLY_DISMISS_SETTLE_MS,
): Long = retries * retryDelayMs + settleMs

internal fun notificationReplyUniqueWorkName(
    action: NotificationAction,
    reply: String,
): String = notificationReplyUniqueWorkName(action.target.accountRef, action.target.groupIdHex, reply)

internal fun notificationReplyUniqueWorkName(
    accountRef: String,
    groupIdHex: String,
    reply: String,
): String = "notification_reply:${sha256Hex("$accountRef\u0000$groupIdHex\u0000${normalizedNotificationReplyText(reply)}")}"

internal fun notificationReplyWorkData(
    action: NotificationAction,
    reply: String,
): Data =
    Data
        .Builder()
        .putString(KEY_ACCOUNT_REF, action.target.accountRef)
        .putString(KEY_GROUP_ID_HEX, action.target.groupIdHex)
        .putString(KEY_MESSAGE_ID_HEX, action.target.messageIdHex.orEmpty())
        .putString(KEY_NOTIFICATION_TAG, action.notificationTag)
        .putInt(KEY_NOTIFICATION_ID, action.notificationId)
        .putString(KEY_REPLY_TEXT, normalizedNotificationReplyText(reply))
        .build()

private data class NotificationReplyWork(
    val action: NotificationAction,
    val reply: String,
) {
    companion object {
        fun from(data: Data): NotificationReplyWork? {
            val reply = data.getString(KEY_REPLY_TEXT)?.let(::normalizedNotificationReplyText)?.takeIf { it.isNotEmpty() } ?: return null
            val notificationId =
                data
                    .getInt(KEY_NOTIFICATION_ID, MISSING_NOTIFICATION_ID)
                    .takeUnless { it == MISSING_NOTIFICATION_ID }
                    ?: return null
            val action =
                NotificationActions.parseRawFields(
                    action = NotificationActions.ACTION_REPLY,
                    accountRef = data.getString(KEY_ACCOUNT_REF),
                    groupIdHex = data.getString(KEY_GROUP_ID_HEX),
                    messageIdHex = data.getString(KEY_MESSAGE_ID_HEX),
                    targetKindName = NotificationTargetKind.MESSAGE.name,
                    notificationTag = data.getString(KEY_NOTIFICATION_TAG),
                    notificationId = notificationId,
                ) ?: return null
            return NotificationReplyWork(action, reply)
        }
    }
}

private data class NotificationReplyDispatchKey(
    val accountRef: String,
    val groupIdHex: String,
    val replyHash: String,
)

// This map is intentionally process-local: it absorbs rapid duplicate receiver
// deliveries and suppresses immediate post-success resends while the process is
// alive. Durability across process death comes from WorkManager's unique work
// name and ExistingWorkPolicy.KEEP, not from this in-memory window.
internal fun tryBeginNotificationReplyDispatch(
    action: NotificationAction,
    reply: String,
): Boolean =
    synchronized(replyDispatches) {
        pruneExpiredReplyDispatchesLocked()
        val key = notificationReplyDispatchKey(action, reply)
        if (key in replyDispatches) return@synchronized false
        replyDispatches[key] = SystemClock.elapsedRealtime() + REPLY_DEDUP_WINDOW_MS
        true
    }

internal fun finishNotificationReplyDispatch(
    action: NotificationAction,
    reply: String,
    keepRecent: Boolean,
) {
    synchronized(replyDispatches) {
        val key = notificationReplyDispatchKey(action, reply)
        if (keepRecent) {
            replyDispatches[key] = SystemClock.elapsedRealtime() + REPLY_DEDUP_WINDOW_MS
        } else {
            replyDispatches.remove(key)
        }
        pruneExpiredReplyDispatchesLocked()
    }
}

private fun notificationReplyDispatchKey(
    action: NotificationAction,
    reply: String,
): NotificationReplyDispatchKey =
    NotificationReplyDispatchKey(
        accountRef = action.target.accountRef,
        groupIdHex = action.target.groupIdHex,
        replyHash = sha256Hex(normalizedNotificationReplyText(reply)),
    )

private fun pruneExpiredReplyDispatchesLocked() {
    val now = SystemClock.elapsedRealtime()
    replyDispatches.entries.removeAll { it.value <= now }
}

private fun sha256Hex(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private val replyDispatches = mutableMapOf<NotificationReplyDispatchKey, Long>()

private const val KEY_ACCOUNT_REF = "account_ref"
private const val KEY_GROUP_ID_HEX = "group_id_hex"
private const val KEY_MESSAGE_ID_HEX = "message_id_hex"
private const val KEY_NOTIFICATION_TAG = "notification_tag"
private const val KEY_NOTIFICATION_ID = "notification_id"
private const val KEY_REPLY_TEXT = "reply_text"
private const val MISSING_NOTIFICATION_ID = Int.MIN_VALUE

// The system applies FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY a beat after the
// reply broadcast fires, so the live notification may not be in the active set
// on the first look; retry the "reply handled" re-post a few times, then give
// NMS a moment to clear the extension before cancelling.
private val REPLY_DISMISS_BUDGET_MS = notificationReplyDismissBudgetMs()
private const val REPLY_DISMISS_RETRIES = 6
private const val REPLY_DISMISS_RETRY_DELAY_MS = 100L
private const val REPLY_DISMISS_SETTLE_MS = 350L
private const val REPLY_DEDUP_WINDOW_MS = 30_000L
