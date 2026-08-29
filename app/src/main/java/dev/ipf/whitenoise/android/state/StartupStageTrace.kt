package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import androidx.tracing.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * Section names for the cold-start stages, mirroring
 * [dev.ipf.whitenoise.android.notifications.NotificationRouteTraceSection].
 *
 * `StartupBenchmark` measures how long a cold start takes but could not say
 * which stage moved, because the stage timings existed only as logcat lines
 * and Macrobenchmark reads trace sections. These names give each stage a slice
 * a `TraceSectionMetric` can attribute. The benchmark module repeats the
 * strings by convention, the same way it repeats the notification-route names.
 *
 * A stage name never carries account, group, or message identity — it is one
 * of the fixed constants below.
 */
internal object StartupStageTraceSection {
    private const val PREFIX = "WhiteNoise.startup."

    const val CLIENT_CONSTRUCTION = PREFIX + "client-construction"
    const val PRIVACY_RUNTIME_CONFIGURATION = PREFIX + "privacy-runtime-configuration"
    const val MARMOT_START = PREFIX + "marmot-start"
    const val NOTIFICATION_PLATFORM_SETUP = PREFIX + "notification-platform-setup"
    const val NOTIFICATION_PRIVACY_SETUP = PREFIX + "notification-privacy-setup"
    const val ACCOUNT_REFRESH = PREFIX + "account-refresh"
    const val ACCOUNT_ACTIVATION = PREFIX + "account-activation"
    const val DRAFT_RECONCILIATION = PREFIX + "draft-reconciliation"
    const val EXTERNAL_SIGNER_REGISTRATION = PREFIX + "external-signer-registration"
    const val UNREAD_AGGREGATE_REFRESH = PREFIX + "unread-aggregate-refresh"
    const val FAILED_RUNTIME_CLOSE = PREFIX + "failed-runtime-close"

    /** The section a stage name maps to, or null for a stage with no reserved name. */
    fun sectionFor(stage: String): String? = SECTIONS[stage]

    private val SECTIONS =
        listOf(
            CLIENT_CONSTRUCTION,
            PRIVACY_RUNTIME_CONFIGURATION,
            MARMOT_START,
            NOTIFICATION_PLATFORM_SETUP,
            NOTIFICATION_PRIVACY_SETUP,
            ACCOUNT_REFRESH,
            ACCOUNT_ACTIVATION,
            DRAFT_RECONCILIATION,
            EXTERNAL_SIGNER_REGISTRATION,
            UNREAD_AGGREGATE_REFRESH,
            FAILED_RUNTIME_CLOSE,
        ).associateBy { it.removePrefix(PREFIX) }
}

/**
 * Async trace slices for the bootstrap stages.
 *
 * Async rather than nested: a stage can suspend across dispatcher hops, and
 * stages nest (`marmot-start` runs inside the runtime open that
 * `client-construction` began), so a begin and end pair on one thread would
 * not describe them. Every call is guarded by [Trace.isEnabled] so an
 * untraced process does no work beyond that check.
 */
internal object StartupStageTrace {
    private val cookies = AtomicInteger()

    /**
     * Runs [block] inside the stage's trace slice, then reports how long it
     * took to [onFinished] — including when the block throws or is cancelled,
     * so a failed bootstrap still records where it stopped.
     */
    suspend fun <T> trace(
        stage: String,
        block: suspend () -> T,
        onFinished: (durationMs: Long) -> Unit,
    ): T {
        val startedAtMs = SystemClock.elapsedRealtime()
        val section = StartupStageTraceSection.sectionFor(stage)?.takeIf { Trace.isEnabled() }
        val cookie = section?.let { cookies.incrementAndGet() }
        if (section != null && cookie != null) Trace.beginAsyncSection(section, cookie)
        return try {
            block()
        } finally {
            if (section != null && cookie != null) Trace.endAsyncSection(section, cookie)
            onFinished(SystemClock.elapsedRealtime() - startedAtMs)
        }
    }
}
