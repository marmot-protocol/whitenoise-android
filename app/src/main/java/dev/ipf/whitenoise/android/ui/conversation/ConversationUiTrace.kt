package dev.ipf.whitenoise.android.ui.conversation

import android.os.Trace
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics

/** Closed, operation-only labels for attributing the CPU cost of an explicitly captured transcript. */
internal enum class ConversationUiTrace(
    val label: String,
) {
    TRANSCRIPT_MEASURE("WhiteNoise.conversation.measure.transcript"),
    ROW("WhiteNoise.conversation.compose.row"),
    BUBBLE("WhiteNoise.conversation.compose.bubble"),
    TEXT("WhiteNoise.conversation.compose.text"),
}

/**
 * Attributes synchronous UI work only while both system tracing and the bounded local session are active.
 * Admission is captured once so expiry or an exception cannot leave an open section. Nested durations
 * are inclusive; these sections add no per-row log events or retained diagnostic collections.
 */
internal inline fun <T> traceConversationUiSection(
    section: ConversationUiTrace,
    block: () -> T,
): T {
    val admitted =
        BuildConfig.ENABLE_LOCAL_PERFORMANCE_DIAGNOSTICS && Trace.isEnabled() && PerformanceDiagnostics.isActive()
    if (!admitted) return block()
    Trace.beginSection(section.label)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
