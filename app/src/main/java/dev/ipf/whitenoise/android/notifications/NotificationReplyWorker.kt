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
        val action = notificationReplyActionFromInput(inputData) ?: return Result.failure()
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
                        return cryptoFailureResult(failure)
                    }
                NotificationReplyInput.Malformed -> return Result.failure()
            }
        if (reply.isBlank()) return Result.success()
        val completionStore = NotificationReplyCompletionStore.create(applicationContext)
        // WorkManager keeps this id stable across retries and assigns a new one
        // to every separately enqueued reply, even when the text is identical.
        val completionKey = notificationReplyCompletionKey(id)
        if (completionStore.isCompleted(completionKey)) {
            return completedReplyResult(application, action, reply)
        }
        when (completionStore.abandonedOutcome(completionKey)) {
            NotificationReplyAbandonedOutcome.Success -> return Result.success()
            NotificationReplyAbandonedOutcome.Failure -> return Result.failure()
            null -> Unit
        }
        if (!application.appState.notificationActionsAllowed) {
            if (BuildConfig.DEBUG) Log.w(TAG, "reply blocked by app lock group=${action.target.groupIdHex.take(8)}")
            return Result.retry()
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
            if (sendOutcome != NotificationReplySendOutcome.Failed) {
                withContext(Dispatchers.IO) {
                    completionStore.markCompleted(completionKey)
                }
                completedReplyResult(application, action, reply)
            } else {
                notificationReplyActionHandled(sent = false)
                if (BuildConfig.DEBUG) Log.w(TAG, "reply send returned false group=${action.target.groupIdHex.take(8)}")
                replyFailureResult(action, containsLegacyPlaintext, completionStore, completionKey)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "reply worker failed group=${action.target.groupIdHex.take(8)}", throwable)
            replyFailureResult(action, containsLegacyPlaintext, completionStore, completionKey)
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
    ): Result {
        if (shouldRetryAfterFailure(runAttemptCount, containsLegacyPlaintext)) return Result.retry()
        // Only persist the abandon marker once we're actually giving up (off the
        // worker thread); if it can't be recorded, retry so recovery state isn't lost.
        val persisted =
            withContext(Dispatchers.IO) {
                completionStore.markAbandoned(completionKey, NotificationReplyAbandonedOutcome.Failure)
            }
        if (!persisted) return Result.retry()
        val reason = if (containsLegacyPlaintext) "legacy plaintext cannot be retained" else "retry limit reached"
        Log.w(TAG, "reply failed ($reason) group=${action.target.groupIdHex.take(8)} attempts=${runAttemptCount + 1}")
        return Result.failure()
    }

    private fun cryptoFailureResult(failure: Throwable): Result =
        if (shouldRetryAfterCryptoFailure(failure, runAttemptCount)) Result.retry() else Result.failure()

    private suspend fun markReadAfterReply(
        application: WhiteNoiseApplication,
        action: NotificationAction,
    ) {
        val markReadFailureMessage =
            "reply sent but mark-read failed group=${action.target.groupIdHex.take(8)} " +
                "message=${action.target.messageIdHex.orEmpty().take(8)}"
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
                Log.w(TAG, markReadFailureMessage, throwable)
                null
            }
        if (result == false) Log.w(TAG, markReadFailureMessage)
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

        internal fun shouldRetryAfterCryptoFailure(
            failure: Throwable,
            runAttemptCount: Int,
        ): Boolean {
            val isTerminal =
                generateSequence(failure) { current -> current.cause?.takeUnless { it === current } }.any {
                    it is IllegalArgumentException || it is BadPaddingException || it is IllegalBlockSizeException
                }
            return !isTerminal && shouldRetryAfterFailure(runAttemptCount)
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
