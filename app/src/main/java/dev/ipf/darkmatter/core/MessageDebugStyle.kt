package dev.ipf.darkmatter.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import org.json.JSONArray
import org.json.JSONObject

/**
 * Coarse category for the streaming-debug inline row (#315, iOS parity). Mirrors
 * iOS `MessageDebugCategory`: a category label is shown in the debug chrome so
 * cross-platform bug reports line up. The accent color mapping is intentionally
 * NOT here — this file is pure Kotlin (no Compose) so it stays JVM-unit-testable;
 * the Compose row owns the category -> Color mapping.
 */
enum class MessageDebugCategory(
    val label: String,
) {
    UserVisible("user-visible"),
    StreamSignaling("stream-signaling"),
    AgentChrome("agent-chrome"),
    GroupSystem("group-system"),
    Control("control"),
    Unknown("unknown"),
}

/**
 * Debug-row presentation derived from a timeline record. Mirrors iOS
 * `MessageDebugStyle`: a [category] label, a [kindLabel] such as
 * "kind 1200 · agent-stream-start", a multi-line [tagsSummary], and a
 * kind-specific [detailText]. [isUserVisibleBubble] lets the conversation skip
 * debug rendering for ordinary chat bubbles even when streaming debug is on.
 */
data class MessageDebugStyle(
    val category: MessageDebugCategory,
    val kindLabel: String,
    val tagsSummary: String,
    val detailText: String,
) {
    val isUserVisibleBubble: Boolean
        get() = category == MessageDebugCategory.UserVisible
}

/**
 * Classifies a timeline [AppMessageRecordFfi] into a [MessageDebugStyle] for the
 * inline streaming-debug row. Pure projection over [MessageProjector] predicates,
 * dependency-free apart from android-bundled `org.json` for the JSON pretty
 * fallback (which is exercised on the JVM by the existing
 * `GroupSystemEventsTest`).
 */
object MessageDebugClassifier {
    fun debugStyle(record: AppMessageRecordFfi): MessageDebugStyle {
        val category = categoryOf(record)
        return MessageDebugStyle(
            category = category,
            kindLabel = "kind ${record.kind} · ${kindName(record)}",
            tagsSummary = tagsSummary(record),
            detailText = detailText(record),
        )
    }

    private fun categoryOf(record: AppMessageRecordFfi): MessageDebugCategory =
        when {
            // User-visible bubbles: plain chat / reply / media / agent-stream final.
            MessageProjector.isStreamFinal(record) -> MessageDebugCategory.UserVisible
            MessageProjector.isChatKind(record.kind) -> MessageDebugCategory.UserVisible
            MessageProjector.isStreamStart(record) -> MessageDebugCategory.StreamSignaling
            MessageProjector.isGroupSystem(record) -> MessageDebugCategory.GroupSystem
            MessageProjector.isReaction(record) -> MessageDebugCategory.Control
            MessageProjector.isDelete(record) -> MessageDebugCategory.Control
            else -> MessageDebugCategory.Unknown
        }

    private fun kindName(record: AppMessageRecordFfi): String =
        when {
            MessageProjector.isStreamFinal(record) -> "agent-stream-final"
            MessageProjector.isChatKind(record.kind) -> "chat"
            MessageProjector.isStreamStart(record) -> "agent-stream-start"
            MessageProjector.isGroupSystem(record) -> "group-system"
            MessageProjector.isReaction(record) -> "reaction"
            MessageProjector.isDelete(record) -> "delete"
            MessageProjector.isEdit(record) -> "edit"
            else -> "unknown"
        }

    private fun tagsSummary(record: AppMessageRecordFfi): String {
        if (record.tags.isEmpty()) return "tags: (none)"
        return "tags:\n" + record.tags.joinToString("\n") { it.values.joinToString(" ") }
    }

    private fun detailText(record: AppMessageRecordFfi): String =
        when {
            MessageProjector.isStreamStart(record) ->
                MessageProjector.streamId(record)?.let { "stream id: $it" } ?: "stream id: (none)"
            MessageProjector.isStreamFinal(record) ->
                MessageProjector.streamId(record)?.let { "stream id: $it" }
                    ?: prettyBody(record)
            MessageProjector.isDelete(record) -> {
                val targets = MessageProjector.deletedTargetMessageIds(record)
                if (targets.isEmpty()) "delete target: (none)" else "delete target: ${targets.joinToString(", ")}"
            }
            MessageProjector.isReaction(record) -> {
                val target = MessageProjector.replyTargetMessageId(record) ?: firstEventRef(record)
                val emoji = record.plaintext.ifBlank { "(none)" }
                "reaction target: ${target ?: "(none)"}\nemoji: $emoji"
            }
            else -> {
                val replyTarget = MessageProjector.replyTargetMessageId(record)
                val body = prettyBody(record)
                if (replyTarget != null) "reply target: $replyTarget\n$body" else body
            }
        }

    private fun firstEventRef(record: AppMessageRecordFfi): String? =
        record.tags
            .firstOrNull { it.values.firstOrNull() == "e" }
            ?.values
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

    // Pretty-print the plaintext as JSON when it parses; otherwise return the
    // trimmed body verbatim (or "(empty)"). org.json is android-bundled and
    // resolves under the JVM unit-test classpath.
    private fun prettyBody(record: AppMessageRecordFfi): String {
        val trimmed = record.plaintext.trim()
        if (trimmed.isEmpty()) return "(empty)"
        return runCatching {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> trimmed
            }
        }.getOrDefault(trimmed)
    }
}
