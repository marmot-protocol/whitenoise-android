package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

internal sealed interface NotificationReplyInput {
    data class Encrypted(
        val reply: EncryptedNotificationReply,
    ) : NotificationReplyInput

    data class LegacyPlaintext(
        val reply: String,
    ) : NotificationReplyInput

    data object Malformed : NotificationReplyInput
}

class NotificationReplyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? WhiteNoiseApplication ?: return Result.success()
        val retryStore = NotificationActionRetryStore.create(applicationContext)
        val retryKey = id.toString()
        val action =
            notificationReplyActionFromInput(inputData)
                ?: return Result.failure().also { retryStore.clear(retryKey) }
        val replyInput = notificationReplyFromInput(inputData)
        // Legacy rows already exist in WorkManager's database after an upgrade.
        // Process them once, but never schedule their plaintext input for backoff.
        val containsLegacyPlaintext = replyInput is NotificationReplyInput.LegacyPlaintext
        val reply =
            when (replyInput) {
                is NotificationReplyInput.LegacyPlaintext -> replyInput.reply.trim()
                is NotificationReplyInput.Encrypted ->
                    try {
                        NotificationReplyCipher
                            .create()
                            .decrypt(replyInput.reply, id, action)
                            .trim()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        Log.w(TAG, "failed to decrypt notification reply", failure)
                        return cryptoFailureResult(failure, retryStore, retryKey)
                    }
                NotificationReplyInput.Malformed -> return Result.failure().also { retryStore.clear(retryKey) }
            }
        if (reply.isBlank()) return Result.success().also { retryStore.clear(retryKey) }
        val completionStore = NotificationReplyCompletionStore.create(applicationContext)
        // WorkManager keeps this id stable across retries and assigns a new one
        // to every separately enqueued reply, even when the text is identical.
        val completionKey = notificationReplyCompletionKey(id)
        if (completionStore.isCompleted(completionKey)) {
            return completedReplyResult(application, action, reply).also { retryStore.clear(retryKey) }
        }
        when (completionStore.abandonedOutcome(completionKey)) {
            NotificationReplyAbandonedOutcome.Success -> return Result.success().also { retryStore.clear(retryKey) }
            NotificationReplyAbandonedOutcome.Failure -> return Result.failure().also { retryStore.clear(retryKey) }
            null -> Unit
        }
        if (!application.appState.notificationActionsAllowed) {
            if (BuildConfig.DEBUG) Log.w(TAG, "reply blocked by app lock group=${action.target.groupIdHex.take(8)}")
            if (containsLegacyPlaintext) {
                return Result.failure().also { retryStore.clear(retryKey) }
            }
            if (retryStore.shouldDeferForLock(retryKey, NotificationActionRetryStore.MAXIMUM_LOCK_WAIT_MILLIS)) {
                return Result.retry()
            }
            notificationWarning(TAG, "reply lock wait expired") {
                "group=${action.target.groupIdHex.take(8)}"
            }
            return Result.failure().also { retryStore.clear(retryKey) }
        }
        if (retryStore.operationFailureCount(retryKey) >= MAX_SEND_ATTEMPTS) {
            return finalizeReplyFailure(action, containsLegacyPlaintext, completionStore, completionKey, retryStore, retryKey)
        }

        return try {
            withContext(Dispatchers.Main.immediate) {
                application.appState.ensureNotificationRuntimeStarted()
            }
            val sendOutcome =
                withContext(Dispatchers.Main.immediate) {
                    application.appState.sendNotificationReply(
                        accountRef = action.target.accountRef,
                        groupIdHex = action.target.groupIdHex,
                        afterMessageIdHex = action.target.messageIdHex.orEmpty(),
                        text = reply,
                        completionStore = completionStore,
                        completionKey = completionKey,
                        recoveryScope = notificationReplyRecoveryScope(action.target.accountRef, action.target.groupIdHex),
                    )
                }
            when (sendOutcome) {
                NotificationReplySendOutcome.Sent,
                NotificationReplySendOutcome.AlreadyCommitted,
                -> {
                    withContext(Dispatchers.IO) {
                        completionStore.markCompleted(completionKey)
                    }
                    completedReplyResult(application, action, reply).also { retryStore.clear(retryKey) }
                }

                NotificationReplySendOutcome.RetryableFailure,
                NotificationReplySendOutcome.NonRetryableFailure,
                -> {
                    notificationReplyActionHandled(sent = false)
                    replySendFailureResult(
                        action,
                        sendOutcome,
                        containsLegacyPlaintext,
                        completionStore,
                        completionKey,
                        retryStore,
                        retryKey,
                    )
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "reply worker failed group=${action.target.groupIdHex.take(8)}", throwable)
            replyFailureResult(action, containsLegacyPlaintext, completionStore, completionKey, retryStore, retryKey)
        }
    }

    private suspend fun completedReplyResult(
        application: WhiteNoiseApplication,
        action: NotificationAction,
        reply: String,
    ): Result {
        try {
            withContext(Dispatchers.Main.immediate) {
                application.appState.ensureNotificationRuntimeStarted()
            }
            markReadAfterReply(application, action)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            Log.w(TAG, "reply sent but mark-read cleanup failed", throwable)
        }
        try {
            dismissSentReplyNotification(applicationContext, action, reply)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            Log.w(TAG, "reply sent but notification cleanup failed", throwable)
        }
        notificationReplyActionHandled(sent = true)
        return Result.success()
    }

    private suspend fun replyFailureResult(
        action: NotificationAction,
        containsLegacyPlaintext: Boolean,
        completionStore: NotificationReplyCompletionStore,
        completionKey: String,
        retryStore: NotificationActionRetryStore,
        retryKey: String,
    ): Result {
        val operationAttempt = retryStore.recordOperationFailureAttempt(retryKey)
        if (operationAttempt != null && shouldRetryAfterFailure(operationAttempt, containsLegacyPlaintext)) {
            return Result.retry()
        }
        return finalizeReplyFailure(
            action,
            containsLegacyPlaintext,
            completionStore,
            completionKey,
            retryStore,
            retryKey,
        )
    }

    private suspend fun replySendFailureResult(
        action: NotificationAction,
        outcome: NotificationReplySendOutcome,
        containsLegacyPlaintext: Boolean,
        completionStore: NotificationReplyCompletionStore,
        completionKey: String,
        retryStore: NotificationActionRetryStore,
        retryKey: String,
    ): Result {
        val operationAttempt =
            if (outcome == NotificationReplySendOutcome.RetryableFailure) {
                retryStore.recordOperationFailureAttempt(retryKey)
            } else {
                null
            }
        val result =
            resultAfterSendOutcome(
                outcome = outcome,
                operationFailureAttempt = operationAttempt ?: 0,
                containsLegacyPlaintext = containsLegacyPlaintext,
            )
        if (result == Result.retry()) return result
        return finalizeReplyFailure(
            action,
            containsLegacyPlaintext,
            completionStore,
            completionKey,
            retryStore,
            retryKey,
            failureReason =
                if (outcome == NotificationReplySendOutcome.NonRetryableFailure) {
                    "non-retryable send failure"
                } else {
                    null
                },
        )
    }

    private suspend fun finalizeReplyFailure(
        action: NotificationAction,
        containsLegacyPlaintext: Boolean,
        completionStore: NotificationReplyCompletionStore,
        completionKey: String,
        retryStore: NotificationActionRetryStore,
        retryKey: String,
        failureReason: String? = null,
    ): Result {
        // Only persist the abandon marker once we're actually giving up (off the
        // worker thread); if it can't be recorded, retry so recovery state isn't lost.
        val persisted =
            withContext(Dispatchers.IO) {
                completionStore.markAbandoned(completionKey, NotificationReplyAbandonedOutcome.Failure)
            }
        if (!persisted) return Result.retry()
        val reason = failureReason ?: if (containsLegacyPlaintext) "legacy plaintext cannot be retained" else "retry limit reached"
        notificationWarning(TAG, "reply failed ($reason) attempts=${retryStore.operationFailureCount(retryKey)}") {
            "group=${action.target.groupIdHex.take(8)}"
        }
        retryStore.clear(retryKey)
        return Result.failure()
    }

    private fun cryptoFailureResult(
        failure: Throwable,
        retryStore: NotificationActionRetryStore,
        retryKey: String,
    ): Result {
        val isTerminal = isTerminalCryptoFailure(failure)
        val operationAttempt = if (isTerminal) null else retryStore.recordOperationFailureAttempt(retryKey)
        if (operationAttempt != null && shouldRetryAfterCryptoFailure(failure, operationAttempt)) {
            return Result.retry()
        }
        retryStore.clear(retryKey)
        return Result.failure()
    }

    private suspend fun markReadAfterReply(
        application: WhiteNoiseApplication,
        action: NotificationAction,
    ) {
        val result =
            try {
                withContext(Dispatchers.Main.immediate) {
                    application.appState.markNotificationMessageRead(
                        accountRef = action.target.accountRef,
                        groupIdHex = action.target.groupIdHex,
                        messageIdHex = action.target.messageIdHex.orEmpty(),
                    )
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                notificationWarning(TAG, "reply sent but mark-read failed", throwable) {
                    "group=${action.target.groupIdHex.take(8)} message=${action.target.messageIdHex.orEmpty().take(8)}"
                }
                null
            }
        if (result == false) {
            notificationWarning(TAG, "reply sent but mark-read failed") {
                "group=${action.target.groupIdHex.take(8)} message=${action.target.messageIdHex.orEmpty().take(8)}"
            }
        }
    }

    private suspend fun dismissSentReplyNotification(
        appContext: Context,
        action: NotificationAction,
        reply: String,
    ) {
        val presenter = LocalNotificationPresenter(appContext)
        // Baseline for sparing sibling cards that arrive during the retry+settle
        // window below: only cards already present when we start are cleared.
        val dismissBaselineMs = System.currentTimeMillis()
        withContext(NonCancellable) {
            try {
                var resolved = false
                repeat(REPLY_DISMISS_RETRIES) {
                    if (!resolved) {
                        resolved = presenter.markDirectReplyHandled(action.notificationTag, action.notificationId, reply)
                        if (!resolved) delay(REPLY_DISMISS_RETRY_DELAY_MS)
                    }
                }
                if (resolved) delay(REPLY_DISMISS_SETTLE_MS)
            } finally {
                // The replied card was deliberately re-posted above to clear the
                // direct-reply lifetime extension. Cancel it only when its stamped
                // latest-message id still matches the replied action target.
                presenter.cancelRepliedConversationCardIfSameGeneration(
                    notificationTag = action.notificationTag,
                    notificationId = action.notificationId,
                    repliedMessageIdHex = action.target.messageIdHex,
                )
                presenter.dismissConversationSiblingCardsNotNewerThan(
                    accountRef = action.target.accountRef,
                    groupIdHex = action.target.groupIdHex,
                    sinceMs = dismissBaselineMs,
                )
            }
        }
    }

    companion object {
        private const val TAG = "DMReplyWorker"
        private const val KEY_LEGACY_REPLY = "reply"
        private const val KEY_REPLY_IV = "reply_iv"
        private const val KEY_REPLY_CIPHERTEXT = "reply_ciphertext"
        private const val COMPLETION_KEY_PREFIX = "notification_reply_"
        private const val MAX_SEND_ATTEMPTS = 3
        private const val REPLY_BACKOFF_DELAY_SECONDS = 30L

        suspend fun enqueue(
            context: Context,
            action: NotificationAction,
            reply: String,
        ): Boolean =
            try {
                val appContext = context.applicationContext
                val requestId = UUID.randomUUID()
                val encryptedReply = NotificationReplyCipher.create().encrypt(reply, requestId, action)
                WorkManager
                    .getInstance(appContext)
                    .enqueue(notificationReplyRequest(action, requestId, encryptedReply))
                    .await()
                true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                Log.w(TAG, "failed to encrypt or enqueue notification reply", failure)
                false
            }

        internal fun shouldRetryAfterFailure(
            runAttemptCount: Int,
            containsLegacyPlaintext: Boolean = false,
        ): Boolean = !containsLegacyPlaintext && runAttemptCount < MAX_SEND_ATTEMPTS - 1

        internal fun shouldRetryAfterSendOutcome(
            outcome: NotificationReplySendOutcome,
            operationFailureAttempt: Int,
            containsLegacyPlaintext: Boolean = false,
        ): Boolean =
            outcome == NotificationReplySendOutcome.RetryableFailure &&
                shouldRetryAfterFailure(operationFailureAttempt, containsLegacyPlaintext)

        internal fun resultAfterSendOutcome(
            outcome: NotificationReplySendOutcome,
            operationFailureAttempt: Int,
            containsLegacyPlaintext: Boolean = false,
        ): Result =
            when {
                outcome == NotificationReplySendOutcome.Sent ||
                    outcome == NotificationReplySendOutcome.AlreadyCommitted -> Result.success()
                shouldRetryAfterSendOutcome(outcome, operationFailureAttempt, containsLegacyPlaintext) -> Result.retry()
                else -> Result.failure()
            }

        internal fun shouldRetryAfterCryptoFailure(
            failure: Throwable,
            operationFailureAttempt: Int,
        ): Boolean = !isTerminalCryptoFailure(failure) && shouldRetryAfterFailure(operationFailureAttempt)

        private fun isTerminalCryptoFailure(failure: Throwable): Boolean =
            generateSequence(failure) { current -> current.cause?.takeUnless { it === current } }.any {
                it is IllegalArgumentException || it is BadPaddingException || it is IllegalBlockSizeException
            }

        internal fun notificationReplyRequest(
            action: NotificationAction,
            requestId: UUID,
            encryptedReply: EncryptedNotificationReply,
        ) = OneTimeWorkRequestBuilder<NotificationReplyWorker>()
            .setId(requestId)
            .setInputData(notificationReplyInputData(action, encryptedReply))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                REPLY_BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS,
            ).build()

        internal fun notificationReplyInputData(
            action: NotificationAction,
            encryptedReply: EncryptedNotificationReply,
        ): Data =
            Data
                .Builder()
                .putAll(NotificationActionWorkData.encode(action))
                .putByteArray(KEY_REPLY_IV, encryptedReply.initializationVector)
                .putByteArray(KEY_REPLY_CIPHERTEXT, encryptedReply.ciphertext)
                .build()

        internal fun notificationReplyFromInput(data: Data): NotificationReplyInput {
            val initializationVector = data.getByteArray(KEY_REPLY_IV)
            val ciphertext = data.getByteArray(KEY_REPLY_CIPHERTEXT)
            val legacyReply = data.getString(KEY_LEGACY_REPLY)
            return when {
                initializationVector != null && ciphertext != null && legacyReply == null ->
                    NotificationReplyInput.Encrypted(EncryptedNotificationReply(initializationVector, ciphertext))
                initializationVector == null && ciphertext == null && legacyReply != null ->
                    NotificationReplyInput.LegacyPlaintext(legacyReply)
                else -> NotificationReplyInput.Malformed
            }
        }

        internal fun notificationReplyActionFromInput(data: Data): NotificationAction? = NotificationActionWorkData.decode(data)

        internal fun notificationReplyCompletionKey(workRequestId: UUID): String = COMPLETION_KEY_PREFIX + workRequestId

        internal fun notificationReplyRecoveryScope(
            accountRef: String,
            groupIdHex: String,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$accountRef\u0000$groupIdHex".toByteArray())
            return buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX_DIGITS[value ushr 4])
                    append(HEX_DIGITS[value and 0x0f])
                }
            }
        }

        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
