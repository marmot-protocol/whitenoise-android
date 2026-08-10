package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import org.json.JSONObject

/** UI-ready projection of a kind-1201 agent-activity payload. */
data class AgentActivityPresentation(
    val text: String,
    val status: String?,
)

object AgentActivityProjector {
    private const val StatusTag = "status"
    private const val MaxPayloadBytes = 64 * 1024
    private const val MaxTextLength = 140

    fun project(
        record: AppMessageRecordFfi,
        fallbackText: String,
    ): AgentActivityPresentation? =
        record.takeIf(MessageProjector::isAgentActivity)?.let { activityRecord ->
            val payload = parsePayload(activityRecord.plaintext)
            val status =
                (payload?.status ?: activityRecord.tagValue(StatusTag))
                    ?.let(ProfileSanitizer::displayName)
                    ?.lowercase()
            AgentActivityPresentation(
                text = payload?.text ?: fallbackText,
                status = status,
            )
        }

    fun previewText(plaintext: String?): String? = plaintext?.let(::parsePayload)?.text

    private fun parsePayload(plaintext: String): Payload? =
        plaintext
            .takeIf { it.length <= MaxPayloadBytes }
            ?.takeIf { it.toByteArray().size <= MaxPayloadBytes }
            ?.let { encoded -> runCatching { JSONObject(encoded) }.getOrNull() }
            ?.let { payload ->
                ProfileSanitizer
                    .compactSingleLine(
                        raw = payload.stringOrNull("text"),
                        maxLength = MaxTextLength,
                    )?.let { text -> Payload(text = text, status = payload.stringOrNull("status")) }
            }

    private fun AppMessageRecordFfi.tagValue(name: String): String? =
        tags
            .firstOrNull { it.values.firstOrNull() == name }
            ?.values
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun JSONObject.stringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) {
            opt(name)?.let { it as? String }?.trim()?.takeIf(String::isNotEmpty)
        } else {
            null
        }

    private data class Payload(
        val text: String,
        val status: String?,
    )
}
