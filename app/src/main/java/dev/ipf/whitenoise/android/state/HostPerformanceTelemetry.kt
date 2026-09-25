package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.HostPerformanceOutcomeFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Every shared MDK operation Android can truthfully own; Linux-only stages stay unreported. */
internal val ANDROID_HOST_PERFORMANCE_OPERATIONS =
    setOf(
        HostPerformanceOperationFfi.SPLASH_READY,
        HostPerformanceOperationFfi.FOREGROUND_LOCAL_READY,
        HostPerformanceOperationFfi.OUTBOUND_MESSAGE_VISIBLE,
        HostPerformanceOperationFfi.INBOUND_MESSAGE_VISIBLE,
        HostPerformanceOperationFfi.CONVERSATION_LOCAL_VISIBLE,
        HostPerformanceOperationFfi.CONVERSATION_COMPOSER_READY,
        HostPerformanceOperationFfi.WINDOW_INIT,
        HostPerformanceOperationFfi.FONTS_INIT,
        HostPerformanceOperationFfi.RUNTIME_INIT,
        HostPerformanceOperationFfi.ACCOUNT_LOAD,
        HostPerformanceOperationFfi.ACCOUNT_SWITCH,
        HostPerformanceOperationFfi.FRAME_UPDATE,
        HostPerformanceOperationFfi.FRAME_LAYOUT,
        HostPerformanceOperationFfi.FRAME_DRAW,
        HostPerformanceOperationFfi.FRAME_PRESENT,
        HostPerformanceOperationFfi.CHAT_LIST_LOAD,
        HostPerformanceOperationFfi.CONTACTS_LOAD,
        HostPerformanceOperationFfi.ARCHIVED_CHAT_LIST_LOAD,
        HostPerformanceOperationFfi.PROFILE_LOAD,
        HostPerformanceOperationFfi.PROFILE_READ,
        HostPerformanceOperationFfi.TIMELINE_OPEN,
        HostPerformanceOperationFfi.TIMELINE_PAGE,
        HostPerformanceOperationFfi.TIMELINE_HANDOFF,
        HostPerformanceOperationFfi.TIMELINE_APPLY,
        HostPerformanceOperationFfi.MESSAGE_SEND,
        HostPerformanceOperationFfi.MESSAGE_SEARCH,
        HostPerformanceOperationFfi.CONVERSATION_SEARCH,
        HostPerformanceOperationFfi.MEDIA_QUEUE_WAIT,
        HostPerformanceOperationFfi.MEDIA_PREPARE,
        HostPerformanceOperationFfi.MEDIA_LOAD,
        HostPerformanceOperationFfi.MEDIA_CACHE_READ,
        HostPerformanceOperationFfi.MEDIA_DECODE,
        HostPerformanceOperationFfi.MEDIA_APPLY,
        HostPerformanceOperationFfi.SETTINGS_SAVE,
    )

/** Receives one closed-schema host duration without adding caller-controlled labels. */
internal fun interface HostPerformanceEmitter {
    fun emit(
        operation: HostPerformanceOperationFfi,
        durationMs: Long,
        outcome: HostPerformanceOutcomeFfi,
    )
}

/**
 * Creates exactly-once host timing attempts bound to the runtime generation that started them.
 *
 * The emitter is captured at [begin] so a late callback can never report into a replacement MDK
 * runtime. Generation replacement converts an otherwise successful late completion to cancellation.
 */
internal class HostPerformanceRecorder(
    private val generation: () -> Int,
    private val emitter: () -> HostPerformanceEmitter?,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    /** Starts an attempt with a monotonic timestamp and the current runtime owner. */
    fun begin(
        operation: HostPerformanceOperationFfi,
        startedAtMs: Long = nowMs(),
    ): HostPerformanceAttempt =
        HostPerformanceAttempt(
            operation = operation,
            startedAtMs = startedAtMs,
            generation = generation(),
            currentGeneration = generation,
            emitter = emitter(),
            nowMs = nowMs,
        )

    /** Records an already-completed stage against the runtime that is current at this call. */
    fun record(
        operation: HostPerformanceOperationFfi,
        durationMs: Long,
        outcome: HostPerformanceOutcomeFfi,
    ) {
        emitter()?.emit(operation, durationMs.coerceAtLeast(0L), outcome)
    }

    /** Measures one suspending block and maps every exit to a terminal MDK outcome. */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount") // The outer boundary must classify every terminal exit.
    suspend fun <T> measure(
        operation: HostPerformanceOperationFfi,
        block: suspend () -> T,
    ): T {
        val attempt = begin(operation)
        return try {
            block().also { attempt.success() }
        } catch (timeout: TimeoutCancellationException) {
            attempt.timeout()
            throw timeout
        } catch (cancel: CancellationException) {
            attempt.cancel()
            throw cancel
        } catch (throwable: Throwable) {
            attempt.failure()
            throw throwable
        }
    }
}

/** One generation-fenced timing attempt whose first terminal outcome wins. */
internal class HostPerformanceAttempt(
    private val operation: HostPerformanceOperationFfi,
    private val startedAtMs: Long,
    private val generation: Int,
    private val currentGeneration: () -> Int,
    private val emitter: HostPerformanceEmitter?,
    private val nowMs: () -> Long,
) {
    private val settled = AtomicBoolean(false)

    /** Completes the attempt successfully if its runtime owner is still current. */
    fun success(): Boolean = complete(HostPerformanceOutcomeFfi.SUCCESS)

    /** Completes the attempt as a failure without exposing exception details. */
    fun failure(): Boolean = complete(HostPerformanceOutcomeFfi.FAILURE)

    /** Completes the attempt as cancelled. */
    fun cancel(): Boolean = complete(HostPerformanceOutcomeFfi.CANCELLED)

    /** Completes the attempt as timed out. */
    fun timeout(): Boolean = complete(HostPerformanceOutcomeFfi.TIMEOUT)

    /** Completes the attempt when the requested host capability was not ready. */
    fun unavailable(): Boolean = complete(HostPerformanceOutcomeFfi.UNAVAILABLE)

    /** Emits at most once and fences a stale generation as cancellation. */
    fun complete(requestedOutcome: HostPerformanceOutcomeFfi): Boolean {
        if (!settled.compareAndSet(false, true)) return false
        val outcome =
            if (currentGeneration() == generation) {
                requestedOutcome
            } else {
                HostPerformanceOutcomeFfi.CANCELLED
            }
        emitter?.emit(
            operation,
            (nowMs() - startedAtMs).coerceAtLeast(0L),
            outcome,
        )
        return true
    }
}
