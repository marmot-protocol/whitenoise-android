package dev.ipf.whitenoise.android.diagnostics

import android.os.SystemClock
import android.util.Log
import dev.ipf.whitenoise.android.BuildConfig
import java.util.concurrent.atomic.AtomicLong

private const val ROUTINE_SPAN_THRESHOLD_MS = 5L

/** Operations accepted by the privacy-reviewed WNPerf schema. */
internal enum class PerformanceOperation(
    val wireName: String,
) {
    APP_START("app_start"),
    CHAT_OPEN("chat_open"),
    CHAT_LIST_REFRESH("chat_list_refresh"),
    TEXT_SEND("text_send"),
    MEDIA_SEND("media_send"),
    ATTACHMENT_FETCH("attachment_fetch"),
    SYNC_CATCH_UP("sync_catch_up"),
}

/** Source-confirmed triggers accepted by recovery diagnostics. */
internal enum class PerformanceTrigger(
    val wireName: String,
) {
    FOREGROUND("foreground"),
    NETWORK_RECONNECT("network_reconnect"),
    PUSH_WAKE("push_wake"),
    CHAT_LIST_READINESS("chat_list_readiness"),
    EXPLICIT("explicit"),
}

/**
 * Closed phase vocabulary. No phase can be supplied by a caller as a String,
 * and routine spans below the phase threshold are silently ignored.
 */
internal enum class PerformancePhase(
    val wireName: String,
    val minimumDurationMs: Long = 0L,
) {
    NOTIFICATION_PLATFORM_SETUP("notification_platform_setup", ROUTINE_SPAN_THRESHOLD_MS),
    ACCOUNT_REFRESH("account_refresh", ROUTINE_SPAN_THRESHOLD_MS),
    DRAFT_RECONCILIATION("draft_reconciliation", ROUTINE_SPAN_THRESHOLD_MS),
    EXTERNAL_SIGNER_REGISTRATION("external_signer_registration", ROUTINE_SPAN_THRESHOLD_MS),
    ACCOUNT_ACTIVATION("account_activation", ROUTINE_SPAN_THRESHOLD_MS),
    NOTIFICATION_PRIVACY_SETUP("notification_privacy_setup", ROUTINE_SPAN_THRESHOLD_MS),
    CLIENT_CONSTRUCTION("client_construction", ROUTINE_SPAN_THRESHOLD_MS),
    PRIVACY_RUNTIME_CONFIGURATION("privacy_runtime_configuration", ROUTINE_SPAN_THRESHOLD_MS),
    FAILED_RUNTIME_CLOSE("failed_runtime_close", ROUTINE_SPAN_THRESHOLD_MS),
    MARMOT_START("marmot_start", ROUTINE_SPAN_THRESHOLD_MS),
    CACHED_CHAT_ROWS_READY("cached_chat_rows_ready"),
    MEMBER_DERIVED_LOCAL_READY("member_derived_local_ready"),
    FIRST_LOCAL_FRAME("first_local_frame"),
    UNREAD_AGGREGATE_REFRESH("unread_aggregate_refresh", ROUTINE_SPAN_THRESHOLD_MS),
    UNREAD_AGGREGATE_READY("unread_aggregate_ready"),
    SYSTEM_SPLASH_HANDOFF("system_splash_handoff"),
    RELAY_CATCH_UP_READY("relay_catch_up_ready"),
    NETWORK_RESTORED("network_restored"),
    RECOVERY_ATTEMPT("recovery_attempt"),
    RECOVERY_RETRY_EXHAUSTED("recovery_retry_exhausted"),
    CONNECTIVITY_WAKE_READY("connectivity_wake_ready"),
    NOTIFICATION_RECEIVER_READY("notification_receiver_ready"),
    NOTIFICATION_RECEIVER_RETRY("notification_receiver_retry"),
    ACCOUNT_CATCH_UP_START("account_catch_up_start"),
    ACCOUNT_SUBSCRIPTION_ACTIVATED("account_subscription_activated"),
    CURRENT_REPLAY_COMPLETE("current_replay_complete"),
    DURABLE_INGEST_READY("durable_ingest_ready"),
    ACCOUNT_CATCH_UP_READY("account_catch_up_ready"),
    ACCOUNT_CATCH_UP_RETRY("account_catch_up_retry"),
    CHAT_LIST_SUBSCRIPTION_RECEIVED("chat_list_subscription_received"),
    TIMELINE_SUBSCRIPTION_RECEIVED("timeline_subscription_received"),
    CHAT_LIST_PROJECTION_PUBLISHED("chat_list_projection_published"),
    TIMELINE_PROJECTION_PUBLISHED("timeline_projection_published"),
    RECOVERY_FIRST_VISIBLE_FRAME("recovery_first_visible_frame"),
    ACCEPTED("accepted"),
    CHAT_LIST_PREVIEW_DROPPED("chat_list_preview_dropped"),
    OPTIMISTIC_SHOWN("optimistic_shown"),
    COMMIT_LOCK_ACQUIRED("commit_lock_acquired", ROUTINE_SPAN_THRESHOLD_MS),
    FFI_START("ffi_start"),
    FFI_RETURN("ffi_return", ROUTINE_SPAN_THRESHOLD_MS),
    TRANSPORT_COMPLETE("transport_complete"),
    FFI_ERROR("ffi_error"),
    TRANSIENT_RETRY("transient_retry"),
    DELIVERY_UNCERTAIN("delivery_uncertain"),
    SENT_FLIP("sent_flip"),
    SEND_COMPLETE("send_complete"),
    SEND_FAILED("send_failed"),
    MANUAL_RETRY("manual_retry"),
    ECHO_RECONCILE("echo_reconcile"),
    EVENTS_DROPPED("events_dropped"),
}

internal enum class PerformanceResult(
    val wireName: String,
) {
    PENDING("pending"),
    SUCCESS("success"),
    FAILURE("failure"),
    DROPPED("dropped"),
}

internal enum class PerformanceLayer(
    val wireName: String,
) {
    ANDROID("android"),
    FFI("ffi"),
    MDK("mdk"),
    STORAGE("storage"),
    TRANSPORT("transport"),
}

internal class PerformanceTrace internal constructor(
    val operation: PerformanceOperation,
    val trigger: PerformanceTrigger? = null,
    internal val sessionGeneration: Long,
    internal val operationId: Long,
    internal val startedAtMs: Long,
)

internal data class PerformanceDiagnosticStatus(
    val available: Boolean,
    val active: Boolean,
    val remainingMillis: Long,
    val emittedCount: Int,
    val droppedCount: Int,
) {
    companion object {
        val Unavailable = PerformanceDiagnosticStatus(false, false, 0L, 0, 0)
    }
}

/**
 * Pure bounded emitter used by [PerformanceDiagnostics]. Inputs are limited to
 * enums and bounded numbers, so identifiers and user-controlled text cannot
 * enter the serialized log line by construction.
 */
internal class PerformanceDiagnosticEmitter(
    private val available: Boolean,
    private val nowMs: () -> Long,
    private val sink: (String) -> Unit,
) {
    private var activeUntilMs = 0L
    private var sessionStartedAtMs = 0L
    private var emittedCount = 0
    private var droppedCount = 0
    private var lastTrace: PerformanceTrace? = null
    private var droppedSummaryEmitted = false
    private var sessionGeneration = 0L
    private val operationCounter = AtomicLong(0L)

    @Synchronized
    fun start(): PerformanceDiagnosticStatus {
        if (!available) return PerformanceDiagnosticStatus.Unavailable
        val now = nowMs()
        if (!isActiveAt(now)) {
            sessionGeneration = nextCounter(sessionGeneration)
            activeUntilMs = now.coerceAtMost(Long.MAX_VALUE - SESSION_DURATION_MS) + SESSION_DURATION_MS
            sessionStartedAtMs = now
            emittedCount = 0
            droppedCount = 0
            lastTrace = null
            droppedSummaryEmitted = false
        }
        return statusAt(now)
    }

    @Synchronized
    fun stop(): PerformanceDiagnosticStatus {
        if (!available) return PerformanceDiagnosticStatus.Unavailable
        finishSession()
        return statusAt(nowMs())
    }

    @Synchronized
    fun status(): PerformanceDiagnosticStatus {
        val now = nowMs()
        expireIfNeeded(now)
        return if (available) statusAt(now) else PerformanceDiagnosticStatus.Unavailable
    }

    /** Starts an active diagnostic operation with an optional closed semantic trigger. */
    @Synchronized
    fun begin(
        operation: PerformanceOperation,
        trigger: PerformanceTrigger? = null,
    ): PerformanceTrace? {
        val now = nowMs()
        expireIfNeeded(now)
        if (!isActiveAt(now)) return null
        return PerformanceTrace(
            operation = operation,
            trigger = trigger,
            sessionGeneration = sessionGeneration,
            operationId = operationCounter.updateAndGet(::nextCounter),
            startedAtMs = now,
        )
    }

    @Synchronized
    fun record(
        trace: PerformanceTrace?,
        phase: PerformancePhase,
        elapsedMs: Long,
        durationMs: Long = 0L,
        result: PerformanceResult = PerformanceResult.SUCCESS,
        layer: PerformanceLayer = PerformanceLayer.ANDROID,
        attempt: Int? = null,
        queueDepth: Int? = null,
        count: Int? = null,
    ) {
        val currentTrace = trace ?: return
        val now = nowMs()
        expireIfNeeded(now)
        val belongsToActiveSession =
            currentTrace.sessionGeneration == sessionGeneration && currentTrace.operationId > 0L
        if (
            !isActiveAt(now) ||
            !belongsToActiveSession ||
            durationMs.coerceAtLeast(0L) < phase.minimumDurationMs
        ) {
            return
        }
        lastTrace = currentTrace
        if (emittedCount >= DATA_EVENT_LIMIT) {
            droppedCount = (droppedCount + 1).coerceAtMost(NUMERIC_COUNT_LIMIT)
        } else {
            sink(
                formatLine(
                    trace = currentTrace,
                    phase = phase,
                    elapsedMs = elapsedMs,
                    durationMs = durationMs,
                    result = result,
                    layer = layer,
                    attempt = attempt,
                    queueDepth = queueDepth,
                    count = count,
                ),
            )
            emittedCount += 1
        }
    }

    private fun formatLine(
        trace: PerformanceTrace,
        phase: PerformancePhase,
        elapsedMs: Long,
        durationMs: Long,
        result: PerformanceResult,
        layer: PerformanceLayer,
        attempt: Int?,
        queueDepth: Int?,
        count: Int?,
    ): String =
        buildString {
            append("schema=1 session=p#")
            append(trace.sessionGeneration)
            append(" op=")
            append(trace.operation.wireName)
            trace.trigger?.let {
                append(" trigger=")
                append(it.wireName)
            }
            append(" phase=")
            append(phase.wireName)
            append(" elapsed_ms=")
            append(elapsedMs.coerceIn(0L, SESSION_DURATION_MS))
            append(" duration_ms=")
            append(durationMs.coerceIn(0L, SESSION_DURATION_MS))
            append(" result=")
            append(result.wireName)
            append(" layer=")
            append(layer.wireName)
            attempt?.let {
                append(" attempt=")
                append(it.coerceIn(0, ATTEMPT_LIMIT))
            }
            queueDepth?.let {
                append(" queue_depth=")
                append(it.coerceIn(0, NUMERIC_COUNT_LIMIT))
            }
            count?.let {
                append(" count=")
                append(it.coerceIn(0, NUMERIC_COUNT_LIMIT))
            }
        }

    private fun expireIfNeeded(now: Long) {
        if (activeUntilMs != 0L && now >= activeUntilMs) finishSession()
    }

    private fun finishSession() {
        if (activeUntilMs == 0L) return
        val trace = lastTrace
        if (droppedCount > 0 && !droppedSummaryEmitted) {
            if (trace != null && emittedCount < SESSION_EVENT_LIMIT) {
                sink(
                    formatLine(
                        trace = trace,
                        phase = PerformancePhase.EVENTS_DROPPED,
                        elapsedMs = (nowMs() - sessionStartedAtMs).coerceAtLeast(0L),
                        durationMs = 0L,
                        result = PerformanceResult.DROPPED,
                        layer = PerformanceLayer.ANDROID,
                        attempt = null,
                        queueDepth = null,
                        count = droppedCount,
                    ),
                )
                emittedCount += 1
                droppedSummaryEmitted = true
            }
        }
        activeUntilMs = 0L
        sessionStartedAtMs = 0L
    }

    private fun isActiveAt(now: Long): Boolean = available && activeUntilMs != 0L && now < activeUntilMs

    private fun statusAt(now: Long): PerformanceDiagnosticStatus =
        PerformanceDiagnosticStatus(
            available = available,
            active = isActiveAt(now),
            remainingMillis = if (isActiveAt(now)) (activeUntilMs - now).coerceAtLeast(0L) else 0L,
            emittedCount = emittedCount,
            droppedCount = droppedCount,
        )

    private fun nextCounter(value: Long): Long = if (value == Long.MAX_VALUE) 1L else value + 1L

    internal companion object {
        const val SESSION_DURATION_MS = 30L * 60L * 1_000L
        const val SESSION_EVENT_LIMIT = 256
        const val DATA_EVENT_LIMIT = SESSION_EVENT_LIMIT - 1
        const val ATTEMPT_LIMIT = 100
        const val NUMERIC_COUNT_LIMIT = 1_000_000
    }
}

/** Process-local facade. It has no disk, preference, clipboard, or network sink. */
internal object PerformanceDiagnostics {
    private val emitter =
        PerformanceDiagnosticEmitter(
            available = BuildConfig.ENABLE_LOCAL_PERFORMANCE_DIAGNOSTICS,
            nowMs = SystemClock::elapsedRealtime,
            sink = { line -> Log.i(LOG_TAG, line) },
        )

    init {
        // Building a benchmark-selector variant is the explicit local opt-in
        // used by the state-preserving performance runner. Ordinary debug,
        // preview, and staging builds remain inactive until the UI toggle.
        if (BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS) emitter.start()
    }

    fun start(): PerformanceDiagnosticStatus = emitter.start()

    fun stop(): PerformanceDiagnosticStatus = emitter.stop()

    fun status(): PerformanceDiagnosticStatus = emitter.status()

    fun isActive(): Boolean = status().active

    /** Starts a trace only while the local, time-bounded diagnostics session is active. */
    fun begin(
        operation: PerformanceOperation,
        trigger: PerformanceTrigger? = null,
    ): PerformanceTrace? = emitter.begin(operation, trigger)

    fun record(
        trace: PerformanceTrace?,
        phase: PerformancePhase,
        elapsedMs: Long,
        durationMs: Long = 0L,
        result: PerformanceResult = PerformanceResult.SUCCESS,
        layer: PerformanceLayer = PerformanceLayer.ANDROID,
        attempt: Int? = null,
        queueDepth: Int? = null,
        count: Int? = null,
    ) {
        emitter.record(trace, phase, elapsedMs, durationMs, result, layer, attempt, queueDepth, count)
    }

    private const val LOG_TAG = "WNPerf"
}
