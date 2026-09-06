package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.notifications.LocalNotificationContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/** Resolved override inputs shared by initial and late notification formatting. */
internal data class NotificationFirstPostContent(
    val conversationTitle: String?,
    val senderName: String?,
    val previewText: String?,
    val reactedToPreview: String?,
    val mediaKind: ReplyMediaKind,
    val recipientAccountSubtext: String?,
)

/** Exact user-visible text/subtext produced for one notification write. */
internal data class NotificationContentPresentation(
    val content: LocalNotificationContent?,
    val recipientAccountSubtext: String?,
)

/** Immutable eligibility, account-cache, content, and presentation state for one typed update. */
internal data class NotificationFirstPost(
    val epoch: Long,
    val accountCacheEpoch: Long,
    val engineMuted: Boolean,
    val shouldPost: Boolean,
    val content: NotificationFirstPostContent?,
    val presentation: NotificationContentPresentation,
    val lateCorrectionPermit: NotificationLateCorrectionPermit = NotificationLateCorrectionPermit(),
)

/** Privacy-safe stages exposed to deterministic JVM and device timing probes. */
internal enum class NotificationFirstPostTimingStage {
    Received,
    EligibilityComplete,
    ContentComplete,
    NotifyWritten,
}

/** PII-free timing event measured from receipt, with optional stage duration. */
internal data class NotificationFirstPostTimingEvent(
    val stage: NotificationFirstPostTimingStage,
    val observedAtElapsedRealtimeNanos: Long,
    val elapsedSinceReceiptMillis: Long,
    val stageElapsedMillis: Long? = null,
    val outcome: String,
)

/** Process-local single-writer permit shared by every late-correction source for one post. */
internal class NotificationLateCorrectionPermit {
    private val mutex = Mutex()
    private var consumed = false

    /** Waits for an in-flight claimant, then claims the slot unless an earlier write consumed it. */
    suspend fun acquire(): Boolean {
        mutex.lock()
        if (consumed) {
            mutex.unlock()
            return false
        }
        return true
    }

    /** Releases a failed claim or permanently consumes the slot after a successful write. */
    fun complete(written: Boolean) {
        if (written) consumed = true
        mutex.unlock()
    }
}

/** Bounded first-draw outcome; every non-resolved value selects the safe formatter fallback. */
internal sealed interface NotificationFirstPostContentResult<out T : Any> {
    data class Resolved<T : Any>(
        val value: T,
    ) : NotificationFirstPostContentResult<T>

    data object TimedOut : NotificationFirstPostContentResult<Nothing>

    data object Failed : NotificationFirstPostContentResult<Nothing>

    data object Busy : NotificationFirstPostContentResult<Nothing>
}

/** Fixed privacy-safe timing labels; values contain no notification identity. */
internal fun NotificationFirstPostContentResult<*>.timingOutcome(): String =
    when (this) {
        is NotificationFirstPostContentResult.Resolved -> "resolved_before_deadline"
        NotificationFirstPostContentResult.TimedOut -> "timeout_fallback"
        NotificationFirstPostContentResult.Failed -> "failed_fallback"
        NotificationFirstPostContentResult.Busy -> "busy_fallback"
    }

/** The sole optional write permitted after a notification's first successful post. */
internal enum class NotificationLateCorrectionPlan {
    Content,
    Avatar,
    None,
}

/** Selects at most one late write, giving changed text priority over imagery. */
internal fun notificationLateCorrectionPlan(
    firstPresentation: NotificationContentPresentation,
    resolvedPresentation: NotificationContentPresentation,
    hasReadyAvatar: Boolean,
): NotificationLateCorrectionPlan =
    when {
        firstPresentation != resolvedPresentation -> NotificationLateCorrectionPlan.Content
        hasReadyAvatar -> NotificationLateCorrectionPlan.Avatar
        else -> NotificationLateCorrectionPlan.None
    }

/** One caller-started content-stage budget tied to its originating coordinator clock. */
internal class NotificationFirstPostContentStage internal constructor(
    internal val owner: Any,
    val startedAtElapsedMillis: Long,
    internal val deadlineAtElapsedMillis: Long,
)

/** Outcome stamped to reject late resolver completion when timeout dispatch loses its race. */
private data class NotificationFirstPostContentCompletion<T : Any>(
    val result: NotificationFirstPostContentResult<T>,
    val completedAtElapsedMillis: Long,
)

/** Classifies captionless notification media from the exact stored message, if available. */
internal suspend fun resolveNotificationMediaKind(
    update: NotificationUpdateFfi,
    messageRecord: suspend (NotificationUpdateFfi) -> AppMessageRecordFfi?,
): ReplyMediaKind = messageRecord(update)?.let(MessageProjector::mediaKind) ?: ReplyMediaKind.None

/**
 * Runs all bounded local first-draw resolvers under one absolute deadline.
 * Timed-out synchronous work keeps the sole permit until it really returns, so
 * a stuck binding cannot grow an unbounded queue while later updates promptly
 * choose their privacy-correct fallback.
 */
internal class NotificationFirstPostContentCoordinator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val elapsedRealtimeMillis: () -> Long,
    private val timeoutMillis: Long = FIRST_POST_CONTENT_TIMEOUT_MILLIS,
) {
    private val gate = Semaphore(permits = 1)
    private val stageOwner = Any()

    init {
        require(timeoutMillis > 0L)
    }

    /** Starts the sole absolute content budget before the caller enters coordinator setup. */
    fun startStage(): NotificationFirstPostContentStage {
        val startedAt = elapsedRealtimeMillis()
        return NotificationFirstPostContentStage(
            owner = stageOwner,
            startedAtElapsedMillis = startedAt,
            deadlineAtElapsedMillis = startedAt + timeoutMillis,
        )
    }

    /** Resolves within the caller-started stage without renewing time spent on coordinator setup. */
    suspend fun <T : Any> resolve(
        stage: NotificationFirstPostContentStage,
        block: suspend () -> T,
    ): NotificationFirstPostContentResult<T> {
        require(stage.owner === stageOwner)
        return when {
            remainingMillis(stage) <= 0L -> NotificationFirstPostContentResult.TimedOut
            !gate.tryAcquire() -> NotificationFirstPostContentResult.Busy
            else -> resolveAfterAdmission(stage, block)
        }
    }

    /** Rechecks expiry after admission before constructing any resolver work. */
    private suspend fun <T : Any> resolveAfterAdmission(
        stage: NotificationFirstPostContentStage,
        block: suspend () -> T,
    ): NotificationFirstPostContentResult<T> {
        if (remainingMillis(stage) <= 0L) {
            gate.release()
            return NotificationFirstPostContentResult.TimedOut
        }
        val read = startRead(block)
        return if (read == null) {
            NotificationFirstPostContentResult.Failed
        } else {
            awaitRead(stage, read)
        }
    }

    /** Creates lazy resolver work while releasing admission if construction itself fails. */
    private fun <T : Any> startRead(block: suspend () -> T): Deferred<NotificationFirstPostContentCompletion<T>>? =
        try {
            scope.async(dispatcher, start = CoroutineStart.LAZY) {
                val result =
                    try {
                        NotificationFirstPostContentResult.Resolved(block())
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        NotificationFirstPostContentResult.Failed
                    }
                NotificationFirstPostContentCompletion(result, elapsedRealtimeMillis())
            }
        } catch (cancellation: CancellationException) {
            gate.release()
            throw cancellation
        } catch (_: Throwable) {
            gate.release()
            null
        }

    /** Awaits lazy resolver work within the unspent budget and releases admission on completion. */
    private suspend fun <T : Any> awaitRead(
        stage: NotificationFirstPostContentStage,
        read: Deferred<NotificationFirstPostContentCompletion<T>>,
    ): NotificationFirstPostContentResult<T> {
        read.invokeOnCompletion { gate.release() }
        return try {
            val remainingMillis = remainingMillis(stage)
            val completed =
                if (remainingMillis <= 0L) {
                    null
                } else {
                    withTimeoutOrNull(remainingMillis) { read.await() }
                }
            when {
                completed == null -> NotificationFirstPostContentResult.TimedOut
                completed.completedAtElapsedMillis >= stage.deadlineAtElapsedMillis ->
                    NotificationFirstPostContentResult.TimedOut
                else -> completed.result
            }
        } finally {
            if (!read.isCompleted) read.cancel()
        }
    }

    /** Returns only the unspent part of the caller-owned absolute budget. */
    private fun remainingMillis(stage: NotificationFirstPostContentStage): Long {
        val remainingMillis = stage.deadlineAtElapsedMillis - elapsedRealtimeMillis()
        return remainingMillis.coerceAtLeast(0L)
    }

    private companion object {
        // End local work before the observed 100 ms ceiling to leave room for caller resumption.
        const val FIRST_POST_CONTENT_TIMEOUT_MILLIS = 75L
    }
}
