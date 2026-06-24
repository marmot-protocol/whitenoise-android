package dev.ipf.darkmatter.core

import dev.ipf.marmotkit.AgentStreamUpdateFfi

/**
 * The presentation pair for one live agent-stream update surfaced as a
 * streaming-debug row (#315, iOS parity). [eventKind] is the short label shown
 * in the row chrome ("chunk", "status", "progress", "record(<type>)", "finished",
 * "failed"); [detail] is the one-line payload summary. Mirrors iOS PR #137's
 * `appendStreamDebugEvent` formatting so cross-platform bug reports line up.
 */
data class StreamDebugEvent(
    val eventKind: String,
    val detail: String,
)

/**
 * Pure formatter for streaming-debug rows. Kept dependency-free (no Compose, no
 * controller state) so the Chunk / Status / Progress / Record / Finished /
 * Failed → row mapping is JVM-unit-testable. The controller calls [of] for every
 * live `AgentStreamUpdateFfi` while the developer toggle is on, then renders the
 * result as a synthetic timeline row.
 */
object StreamDebugEventFormatter {
    // Match iOS streamDebugTextSummary's 120-char preview cap.
    const val SUMMARY_MAX = 120

    fun of(update: AgentStreamUpdateFfi): StreamDebugEvent =
        when (update) {
            is AgentStreamUpdateFfi.Chunk ->
                StreamDebugEvent("chunk", textSummary(update.text))
            is AgentStreamUpdateFfi.Status ->
                StreamDebugEvent("status", "seq=${update.seq} ${update.status}")
            is AgentStreamUpdateFfi.Progress ->
                StreamDebugEvent("progress", "seq=${update.seq} ${textSummary(update.text)}")
            is AgentStreamUpdateFfi.Record ->
                StreamDebugEvent("record(${update.recordType})", textSummary(update.text))
            is AgentStreamUpdateFfi.Finished ->
                StreamDebugEvent(
                    "finished",
                    "chunks=${update.chunkCount} textLen=${update.text.length}B " +
                        "hashLen=${update.transcriptHashHex.length}",
                )
            is AgentStreamUpdateFfi.Failed ->
                StreamDebugEvent("failed", update.message)
        }

    /**
     * Trim/summarize a stream payload for a debug row: blank -> "(empty)", short
     * -> verbatim, long -> first [SUMMARY_MAX] chars + a length tag.
     */
    fun textSummary(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "(empty)"
        if (trimmed.length <= SUMMARY_MAX) return trimmed
        return "${trimmed.take(SUMMARY_MAX)}… (${trimmed.length} chars)"
    }
}
