package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AgentItemStateFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.agent.isAgentOwnThreadMessage
import dev.ipf.whitenoise.android.state.TimelineMessage
import org.json.JSONObject

/** The same visible rows back painting, navigation and viewport measurements. */
internal fun conversationTimelineRowVisible(row: TimelineMessage): Boolean {
    if (MessageProjector.isEdit(row.record)) return false
    val projected = row.projected ?: return true
    val item = projected.agentItem ?: return !projected.isAgentOwnThreadMessage()
    if (projected.deleted) return false
    return when (item.itemType) {
        "approval", "question" -> item.state == AgentItemStateFfi.WAITING
        "turn" -> item.state == AgentItemStateFfi.FAILED
        "notice" -> isAgentRefusal(item.fieldsJson)
        else -> false
    }
}

private fun isAgentRefusal(fieldsJson: String): Boolean =
    runCatching { JSONObject(fieldsJson).optJSONObject("body")?.optString("kind") == "refused" }
        .getOrDefault(false)
