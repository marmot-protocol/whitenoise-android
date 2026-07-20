package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import org.json.JSONArray
import org.json.JSONObject

/** UI-ready projection of a kind-1202 agent-operation payload. */
data class AgentOperationPresentation(
    val eventType: String?,
    val name: String?,
    val text: String,
    val preview: String?,
    val argumentsJson: String?,
    val status: String?,
    val ok: Boolean?,
    val durationMs: Long?,
) {
    /** Keeps the tool name out of the payload's width budget in the collapsed row. */
    val collapsedText: String
        get() = preview ?: text.ifBlank { name ?: eventType ?: "Tool call" }

    val canExpand: Boolean
        get() = preview != null || argumentsJson != null || ok != null || durationMs != null
}

object AgentOperationProjector {
    private const val OperationNameTag = "operation-name"
    private const val OperationStatusTag = "operation-status"
    private const val OperationTypeTag = "operation"

    fun project(record: AppMessageRecordFfi): AgentOperationPresentation? {
        if (!MessageProjector.isAgentOperation(record)) return null

        val payload = runCatching { JSONObject(record.plaintext) }.getOrNull()
        val eventType = payload.stringOrNull("event_type") ?: record.tagValue(OperationTypeTag)
        val name = payload.stringOrNull("name") ?: record.tagValue(OperationNameTag)
        val status = payload.stringOrNull("status") ?: record.tagValue(OperationStatusTag)
        val text = payload.stringOrNull("text") ?: record.plaintext.trim()
        val preview = payload.stringOrNull("preview")
        val argumentsJson = payload?.optJSONObject("details")?.jsonValueOrNull("args")?.prettyJson()
        val ok = payload?.jsonValueOrNull("ok") as? Boolean
        val durationMs = payload?.jsonValueOrNull("duration_ms")?.nonNegativeLongOrNull()

        return AgentOperationPresentation(
            eventType = eventType,
            name = name,
            text = text,
            preview = preview,
            argumentsJson = argumentsJson,
            status = status,
            ok = ok,
            durationMs = durationMs,
        )
    }

    private fun AppMessageRecordFfi.tagValue(name: String): String? =
        tags
            .firstOrNull { it.values.firstOrNull() == name }
            ?.values
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun JSONObject?.stringOrNull(name: String): String? =
        this
            ?.jsonValueOrNull(name)
            ?.let { it as? String }
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun JSONObject.jsonValueOrNull(name: String): Any? = if (has(name) && !isNull(name)) opt(name) else null

    private fun Any.prettyJson(): String =
        when (this) {
            is JSONObject -> toString(2)
            is JSONArray -> toString(2)
            else -> toString()
        }

    private fun Any.nonNegativeLongOrNull(): Long? =
        when (this) {
            is Number -> toLong()
            is String -> toLongOrNull()
            else -> null
        }?.takeIf { it >= 0L }
}
