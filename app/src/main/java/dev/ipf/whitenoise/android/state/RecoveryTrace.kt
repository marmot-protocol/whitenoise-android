package dev.ipf.whitenoise.android.state

import androidx.tracing.Trace
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrigger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Fixed trace names for Android recovery resource measurements. */
internal object RecoveryTraceSection {
    private const val PREFIX = "WhiteNoise.recovery."

    const val NETWORK_RECOVERY_ATTEMPT = PREFIX + "network-attempt"
    const val PUSH_WAKE_LOCK = PREFIX + "push-wake-lock"

    /** Maps a closed trigger to its fixed catch-up section name. */
    fun catchUp(trigger: PerformanceTrigger): String =
        when (trigger) {
            PerformanceTrigger.FOREGROUND -> PREFIX + "catchUp.foreground"
            PerformanceTrigger.NETWORK_RECONNECT -> PREFIX + "catchUp.network-reconnect"
            PerformanceTrigger.PUSH_WAKE -> PREFIX + "catchUp.push-wake"
            PerformanceTrigger.CHAT_LIST_READINESS -> PREFIX + "catchUp.chat-list-readiness"
            PerformanceTrigger.EXPLICIT -> PREFIX + "catchUp.explicit"
        }
}

/** Opaque ownership token; its cookie never appears in a trace label. */
internal class RecoveryTraceToken(
    internal val sectionName: String,
    internal val cookie: Int,
) {
    private val open = AtomicBoolean(true)

    /** Grants exactly one caller ownership of the async-section close. */
    internal fun claimEnd(): Boolean = open.compareAndSet(true, false)
}

/** Privacy-safe async slices around recovery work that can cross coroutine threads. */
internal object RecoveryTrace {
    private const val NETWORK_ATTEMPT_SECTION = RecoveryTraceSection.NETWORK_RECOVERY_ATTEMPT
    private val cookieCounter = AtomicInteger()

    /** Measures one native catch-up execution under its fixed trigger class. */
    suspend fun <T> catchUp(
        trigger: PerformanceTrigger,
        block: suspend () -> T,
    ): T = trace(RecoveryTraceSection.catchUp(trigger), block)

    /** Measures one bounded network-recovery attempt. */
    suspend fun <T> networkRecoveryAttempt(block: suspend () -> T): T = trace(NETWORK_ATTEMPT_SECTION, block)

    /** Starts a slice only after a push wake lock was acquired. */
    fun beginPushWakeLock(): RecoveryTraceToken? = begin(RecoveryTraceSection.PUSH_WAKE_LOCK)

    /** Closes the exact push-wake-lock slice returned by [beginPushWakeLock]. */
    fun endPushWakeLock(token: RecoveryTraceToken?) = end(token)

    /** Runs [block] inside an async slice when system tracing is enabled. */
    private suspend fun <T> trace(
        sectionName: String,
        block: suspend () -> T,
    ): T {
        val token = begin(sectionName)
        return try {
            block()
        } finally {
            end(token)
        }
    }

    /** Opens a fixed-name async slice without allocating an identifier label. */
    private fun begin(sectionName: String): RecoveryTraceToken? {
        if (!runCatching(Trace::isEnabled).getOrDefault(false)) return null
        val cookie =
            cookieCounter.updateAndGet { current ->
                if (current == Int.MAX_VALUE) 1 else current + 1
            }
        return runCatching {
            Trace.beginAsyncSection(sectionName, cookie)
            RecoveryTraceToken(sectionName, cookie)
        }.getOrNull()
    }

    /** Closes an owned async slice; a disabled trace has no token and is a no-op. */
    private fun end(token: RecoveryTraceToken?) {
        if (token?.claimEnd() != true) return
        runCatching { Trace.endAsyncSection(token.sectionName, token.cookie) }
    }
}
