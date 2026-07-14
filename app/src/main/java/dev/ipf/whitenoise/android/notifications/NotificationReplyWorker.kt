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
import androidx.work.workDataOf
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
        if (!application.appState.notificationActionsAllowed) {
            if (BuildConfig.DEBUG) Log.w(TAG, "reply blocked by app lock group=${action.target.groupIdHex.take(8)}")
            return Result.success()
        }

        return try {
            withContext(Dispatchers.Main.immediate) {
                application.appState.ensureNotificationRuntimeStarted()
            }
            if (
                completionStore.isCompleted(completionKey) ||
                (completionStore.hasStarted(completionKey) && alreadyCommitted(application, action, reply))
            ) {
                completionStore.markCompleted(completionKey)
                markReadAfterReply(application, action)
                dismissSentReplyNotification(applicationContext, action, reply)
                notificationReplyActionHandled(sent = true)
                return Result.success()
            }
            completionStore.markStarted(completionKey)
            val sent =
                withContext(Dispatchers.Main.immediate) {
                    application.appState.sendNotificationReply(
                        accountRef = action.target.accountRef,
                        groupIdHex = action.target.groupIdHex,
                        text = reply,
                    )
                }
            if (sent) {
                completionStore.markCompleted(completionKey)
                markReadAfterReply(application, action)
                dismissSentReplyNotification(applicationContext, action, reply)
                notificationReplyActionHandled(sent = true)
                Result.success()
            } else {
                notificationReplyActionHandled(sent = false)
                if (BuildConfig.DEBUG) Log.w(TAG, "reply send returned false group=${action.target.groupIdHex.take(8)}")
                replyFailureResult(action, containsLegacyPlaintext)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "reply worker failed group=${action.target.groupIdHex.take(8)}", throwable)
            replyFailureResult(action, containsLegacyPlaintext)
        }
    }

    private suspend fun alreadyCommitted(
        application: WhiteNoiseApplication,
        action: NotificationAction,
        reply: String,
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            application.appState.notificationReplyAlreadyCommitted(
                accountRef = action.target.accountRef,
                groupIdHex = action.target.groupIdHex,
                afterMessageIdHex = action.target.messageIdHex.orEmpty(),
                text = reply,
            )
        }

    private fun replyFailureResult(
        action: NotificationAction,
        containsLegacyPlaintext: Boolean,
    ): Result {
        if (shouldRetryAfterFailure(runAttemptCount, containsLegacyPlaintext)) return Result.retry()
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
        private const val KEY_ACTION = "action"
        private const val KEY_ACCOUNT_REF = "account_ref"
        private const val KEY_GROUP_ID_HEX = "group_id_hex"
        private const val KEY_MESSAGE_ID_HEX = "message_id_hex"
        private const val KEY_TARGET_KIND = "target_kind"
        private const val KEY_NOTIFICATION_TAG = "notification_tag"
        private const val KEY_NOTIFICATION_ID = "notification_id"
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
            workDataOf(
                KEY_ACTION to NotificationActions.ACTION_REPLY,
                KEY_ACCOUNT_REF to action.target.accountRef,
                KEY_GROUP_ID_HEX to action.target.groupIdHex,
                KEY_MESSAGE_ID_HEX to action.target.messageIdHex.orEmpty(),
                KEY_TARGET_KIND to action.target.kind.name,
                KEY_NOTIFICATION_TAG to action.notificationTag,
                KEY_NOTIFICATION_ID to action.notificationId,
                KEY_REPLY_IV to encryptedReply.initializationVector,
                KEY_REPLY_CIPHERTEXT to encryptedReply.ciphertext,
            )

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

        internal fun notificationReplyActionFromInput(data: Data): NotificationAction? =
            NotificationActions.parseRawFields(
                action = data.getString(KEY_ACTION),
                accountRef = data.getString(KEY_ACCOUNT_REF),
                groupIdHex = data.getString(KEY_GROUP_ID_HEX),
                messageIdHex = data.getString(KEY_MESSAGE_ID_HEX),
                targetKindName = data.getString(KEY_TARGET_KIND),
                notificationTag = data.getString(KEY_NOTIFICATION_TAG),
                notificationId = data.getInt(KEY_NOTIFICATION_ID, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE },
            )

        internal fun notificationReplyCompletionKey(workRequestId: UUID): String = COMPLETION_KEY_PREFIX + workRequestId
    }
}
