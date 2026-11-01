package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace

/**
 * The closed WNPerf phase for one account-switch stage label, or null for a stage the schema does not carry.
 * The labels are the debug trace's own, so a release diagnostic session sees the same stages (#3496).
 */
internal fun accountSwitchPerformancePhase(stage: String): PerformancePhase? =
    when (stage) {
        "cached-chat-rows-ready" -> PerformancePhase.CACHED_CHAT_ROWS_READY
        "member-derived-local-ready" -> PerformancePhase.MEMBER_DERIVED_LOCAL_READY
        "member-derived-local-deferred" -> PerformancePhase.ACCOUNT_SWITCH_MEMBER_DERIVED_DEFERRED
        "top-bar-profiles-ready" -> PerformancePhase.ACCOUNT_SWITCH_TOP_BAR_PROFILES_READY
        "persisted-profiles-ready" -> PerformancePhase.ACCOUNT_SWITCH_PERSISTED_PROFILES_READY
        "previous-shortcuts-hide-finished" -> PerformancePhase.ACCOUNT_SWITCH_SHORTCUTS_HIDDEN
        "published" -> PerformancePhase.ACCOUNT_SWITCH_PUBLISHED
        "bind-start" -> PerformancePhase.ACCOUNT_SWITCH_BIND_START
        "seed-bound" -> PerformancePhase.ACCOUNT_SWITCH_SEED_BOUND
        "first-local-frame" -> PerformancePhase.FIRST_LOCAL_FRAME
        else -> null
    }

/**
 * Records one account-switch stage as elapsed time since the switch was requested, with an optional
 * row count. It carries no account identifier, and nothing is emitted unless a WNPerf diagnostic session
 * began [trace].
 */
internal fun recordAccountSwitchPerformance(
    trace: PerformanceTrace?,
    stage: String,
    elapsedMs: Long,
    rowCount: Int? = null,
) {
    val phase = accountSwitchPerformancePhase(stage) ?: return
    PerformanceDiagnostics.record(trace, phase, elapsedMs = elapsedMs, count = rowCount)
}
