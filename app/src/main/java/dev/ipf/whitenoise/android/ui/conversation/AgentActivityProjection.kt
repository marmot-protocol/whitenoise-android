package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AgentItemStateFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.TimelineMessage
import org.json.JSONObject

/** The same visible rows back painting, navigation and viewport measurements. */
internal fun conversationTimelineRowVisible(row: TimelineMessage): Boolean {
    if (MessageProjector.isEdit(row.record)) return false
    return row.projected?.let { projected ->
        projected.agentItem?.let { item ->
            !projected.deleted &&
                when (item.itemType) {
                    "approval", "question" -> item.state == AgentItemStateFfi.WAITING
                    "turn" -> item.state == AgentItemStateFfi.FAILED
                    "notice" -> isAgentRefusal(item.fieldsJson)
                    else -> false
                }
        }
    } ?: true
}

private fun isAgentRefusal(fieldsJson: String): Boolean =
    runCatching { JSONObject(fieldsJson).optJSONObject("body")?.optString("kind") == "refused" }
        .getOrDefault(false)
