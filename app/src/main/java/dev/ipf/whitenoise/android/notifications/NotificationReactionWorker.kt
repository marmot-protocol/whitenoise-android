package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

internal enum class NotificationReactionSendOutcome {
    Sent,
    RetryableFailure,
    NonRetryableFailure,
}

internal sealed interface NotificationReactionSendAttempt {
    data object Locked : NotificationReactionSendAttempt

    data class Completed(
        val outcome: NotificationReactionSendOutcome,
    ) : NotificationReactionSendAttempt
}

internal suspend fun attemptNotificationReactionSend(
    notificationActionsAllowed: () -> Boolean,
    sendReaction: suspend () -> NotificationReactionSendOutcome,
): NotificationReactionSendAttempt =
    if (notificationActionsAllowed()) {
        NotificationReactionSendAttempt.Completed(sendReaction())
    } else {
        NotificationReactionSendAttempt.Locked
    }

private sealed interface NotificationReactionInput {
    data class Ready(
        val action: NotificationAction,
        val reaction: String,
    ) : NotificationReactionInput

    data class CryptoFailure(
        val failure: Throwable,
    ) : NotificationReactionInput

    data object Malformed : NotificationReactionInput
}

class NotificationReactionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? WhiteNoiseApplication ?: return Result.success()
        val retryStore = NotificationActionRetryStore.create(applicationContext)
        val retryKey = id.toString()
        return when (val input = decodeReactionInput(inputData)) {
            is NotificationReactionInput.Ready -> processReaction(application, input, retryStore, retryKey)
            is NotificationReactionInput.CryptoFailure -> cryptoFailureResult(input.failure, retryStore, retryKey)
            NotificationReactionInput.Malformed -> terminalReactionResult(retryStore, retryKey, surfaceFailure = false)
        }
    }

    private suspend fun processReaction(
        application: WhiteNoiseApplication,
        input: NotificationReactionInput.Ready,
        retryStore: NotificationActionRetryStore,
        retryKey: String,
    ): Result {
        val actionsAllowed =
            withContext(Dispatchers.Main.immediate) {
                application.appState.notificationActionsAllowed
            }
        return when {
            !actionsAllowed -> lockedReactionResult(retryStore, retryKey)
            retryStore.operationFailureCount(retryKey) >= MAX_SEND_ATTEMPTS ->
                terminalReactionResult(retryStore, retryKey, surfaceFailure = true)
            else -> sendReaction(application, input, retryStore, retryKey)
        }
    }

    private suspend fun lockedReactionResult(
        retryStore: NotificationActionRetryStore,
        retryKey: String,
    ): Result =
        if (retryStore.shouldDeferForLock(retryKey, NotificationActionRetryStore.MAXIMUM_LOCK_WAIT_MILLIS)) {
            Result.retry()
        } else {
            terminalReactionResult(retryStore, retryKey, surfaceFailure = true)
        }

    private suspend fun sendReaction(
        application: WhiteNoiseApplication,
        input: NotificationReactionInput.Ready,
        retryStore: NotificationActionRetryStore,
        retryKey: String,
    ): Result {
        val dismissBaselineMs = System.currentTimeMillis()
        val runtimeFailure =
            runCatching {
                withContext(Dispatchers.Main.immediate) {
                    application.appState.ensureNotificationRuntimeStarted()
                }
            }.exceptionOrNull()
        if (runtimeFailure != null) {
            if (runtimeFailure is CancellationException) throw runtimeFailure
            Log.w(TAG, "notification reaction runtime start failed", runtimeFailure)
            return retryableReactionFailureResult(retryStore, retryKey)
        }

        val sendAttempt =
            withContext(Dispatchers.Main.immediate) {
                attemptNotificationReactionSend(
                    notificationActionsAllowed = { application.appState.notificationActionsAllowed },
                    sendReaction = {
                        application.appState.sendNotificationReaction(
                            accountRef = input.action.target.accountRef,
                            groupIdHex = input.action.target.groupIdHex,
                            messageIdHex =
                                input.action.target.messageIdHex
                                    .orEmpty(),
                            reaction = input.reaction,
                        )
                    },
                )
            }
        return when (sendAttempt) {
            NotificationReactionSendAttempt.Locked -> lockedReactionResult(retryStore, retryKey)
            is NotificationReactionSendAttempt.Completed ->
                when (sendAttempt.outcome) {
                    NotificationReactionSendOutcome.Sent ->
                        completedReactionResult(application, input, retryStore, retryKey, dismissBaselineMs)
                    NotificationReactionSendOutcome.RetryableFailure ->
                        retryableReactionFailureResult(retryStore, retryKey)
                    NotificationReactionSendOutcome.NonRetryableFailure ->
                        terminalReactionResult(retryStore, retryKey, surfaceFailure = true)
                }
        }
    }

    private suspend fun completedReactionResult(
        application: WhiteNoiseApplication,
        input: NotificationReactionInput.Ready,
        retryStore: NotificationActionRetryStore,
        retryKey: String,
        dismissBaselineMs: Long,
    ): Result =
        withContext(NonCancellable) {
            runCatching {
                withContext(Dispatchers.Main.immediate) {
                    application.recentEmojiRecentsOwner.onEmojiUsed(input.reaction)
                }
            }.onFailure { Log.w(TAG, "reaction sent but recent-emoji update failed", it) }
            runCatching {
                withContext(Dispatchers.Main.immediate) {
                    application.appState.markNotificationMessageRead(
                        accountRef = input.action.target.accountRef,
                        groupIdHex = input.action.target.groupIdHex,
                        messageIdHex =
                            input.action.target.messageIdHex
                                .orEmpty(),
                    )
                }
            }.onFailure { Log.w(TAG, "reaction sent but mark-read failed", it) }
            runCatching {
                LocalNotificationPresenter(applicationContext).dismissActionNotificationAndOlderSiblings(
                    notificationTag = input.action.notificationTag,
                    notificationId = input.action.notificationId,
                    actedMessageIdHex = input.action.target.messageIdHex,
                    accountRef = input.action.target.accountRef,
                    groupIdHex = input.action.target.groupIdHex,
                    sinceMs = dismissBaselineMs,
                )
            }.onFailure { Log.w(TAG, "reaction sent but notification cleanup failed", it) }
            retryStore.clear(retryKey)
            Result.success()
        }

    private suspend fun retryableReactionFailureResult(
        retryStore: NotificationActionRetryStore,
        retryKey: String,
    ): Result {
        val attempt = retryStore.recordOperationFailureAttempt(retryKey)
        return if (attempt != null && shouldRetryAfterFailure(attempt)) {
            Result.retry()
        } else {
            terminalReactionResult(retryStore, retryKey, surfaceFailure = true)
        }
    }

    private suspend fun cryptoFailureResult(
        failure: Throwable,
        retryStore: NotificationActionRetryStore,
        retryKey: String,
    ): Result {
        Log.w(TAG, "failed to decrypt notification reaction", failure)
        val attempt = retryStore.recordOperationFailureAttempt(retryKey)
        return if (attempt != null && NotificationReplyWorker.shouldRetryAfterCryptoFailure(failure, attempt)) {
            Result.retry()
        } else {
            terminalReactionResult(retryStore, retryKey, surfaceFailure = false)
        }
    }

    private suspend fun terminalReactionResult(
        retryStore: NotificationActionRetryStore,
        retryKey: String,
        surfaceFailure: Boolean,
    ): Result {
        retryStore.clear(retryKey)
        if (surfaceFailure) surfaceReactionFailure()
        return Result.failure()
    }

    private suspend fun surfaceReactionFailure() {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            Toast.makeText(applicationContext, R.string.toast_reaction_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun decodeReactionInput(data: Data): NotificationReactionInput {
        val action =
            NotificationActionWorkData
                .decode(data)
                ?.takeIf { it.kind == NotificationActionKind.REACT }
        val encryptedReaction = reactionFromInput(data)
        if (action == null || encryptedReaction == null) return NotificationReactionInput.Malformed
        return runCatching {
            NotificationReplyCipher.create().decrypt(encryptedReaction, id, action)
        }.fold(
            onSuccess = { reaction ->
                normalizeNotificationReaction(reaction)
                    ?.let { NotificationReactionInput.Ready(action, it) }
                    ?: NotificationReactionInput.Malformed
            },
            onFailure = NotificationReactionInput::CryptoFailure,
        )
    }

    companion object {
        private const val TAG = "DMReactionWorker"
        private const val KEY_REACTION_IV = "reaction_iv"
        private const val KEY_REACTION_CIPHERTEXT = "reaction_ciphertext"
        private const val MAX_SEND_ATTEMPTS = 3
        private const val BACKOFF_DELAY_SECONDS = 30L

        suspend fun enqueue(
            context: Context,
            action: NotificationAction,
            reaction: String,
        ): Boolean {
            val enqueueResult =
                runCatching {
                    val requestId = UUID.randomUUID()
                    val routingAction = action.copy(reaction = null)
                    val normalizedReaction = requireNotNull(normalizeNotificationReaction(reaction))
                    val encrypted =
                        NotificationReplyCipher.create().encrypt(normalizedReaction, requestId, routingAction)
                    WorkManager
                        .getInstance(context.applicationContext)
                        .enqueueUniqueWork(
                            notificationReactionWorkName(routingAction, normalizedReaction),
                            ExistingWorkPolicy.KEEP,
                            notificationReactionRequest(routingAction, requestId, encrypted),
                        ).await()
                }
            val failure = enqueueResult.exceptionOrNull()
            if (failure is CancellationException) throw failure
            if (failure != null) Log.w(TAG, "failed to encrypt or enqueue notification reaction", failure)
            return enqueueResult.isSuccess
        }

        internal fun shouldRetryAfterFailure(operationAttempt: Int): Boolean = operationAttempt < MAX_SEND_ATTEMPTS - 1

        internal fun notificationReactionWorkName(
            action: NotificationAction,
            reaction: String,
        ): String {
            val canonical =
                listOf(
                    action.target.accountRef,
                    action.target.groupIdHex,
                    action.target.messageIdHex.orEmpty(),
                    reaction,
                ).joinToString("\u0000")
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            return "notification_reaction_" + digest.toLowercaseHexString()
        }

        internal fun notificationReactionRequest(
            action: NotificationAction,
            requestId: UUID,
            encryptedReaction: EncryptedNotificationReply,
        ) = OneTimeWorkRequestBuilder<NotificationReactionWorker>()
            .setId(requestId)
            .setConstraints(
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            ).setInputData(reactionInputData(action, encryptedReaction))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS,
            ).build()

        internal fun reactionInputData(
            action: NotificationAction,
            encryptedReaction: EncryptedNotificationReply,
        ): Data =
            Data
                .Builder()
                .putAll(NotificationActionWorkData.encode(action.copy(reaction = null)))
                .putByteArray(KEY_REACTION_IV, encryptedReaction.initializationVector)
                .putByteArray(KEY_REACTION_CIPHERTEXT, encryptedReaction.ciphertext)
                .build()

        internal fun reactionFromInput(data: Data): EncryptedNotificationReply? {
            val iv = data.getByteArray(KEY_REACTION_IV)
            val ciphertext = data.getByteArray(KEY_REACTION_CIPHERTEXT)
            return if (iv != null && ciphertext != null) EncryptedNotificationReply(iv, ciphertext) else null
        }
    }
}
