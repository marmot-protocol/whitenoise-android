package dev.ipf.whitenoise.android.core.agent

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import org.json.JSONObject

/**
 * Reads the agent item MDK projected onto a timeline row.
 *
 * MDK parses kinds 1201 and 1009, keeps the highest version on the item's first-event row and drops
 * answers and controls (kind 1202) from the timeline, so a row needs no folding here. A kind 1201 row
 * without [TimelineMessageRecordFfi.agentItem] is an item MDK could not read, such as the retired
 * shape, and keeps the generic row.
 */
internal object AgentItemReader {
    /** Kind of an agent item's first event. */
    const val ITEM_KIND: ULong = 1201uL

    /** Kind of an answer or a control a person sends to an agent. */
    const val REQUEST_KIND: ULong = 1202uL

    /** The item on [projected], or null when the row holds none or its fields cannot be read. */
    fun read(
        record: AppMessageRecordFfi,
        projected: TimelineMessageRecordFfi?,
    ): AgentItem? {
        val item = projected?.agentItem?.takeUnless { projected.deleted } ?: return null
        val fields = runCatching { JSONObject(item.fieldsJson) }.getOrNull() ?: return null
        return AgentItem(
            rowId = record.messageIdHex,
            agentPubkeyHex = record.sender,
            version = item.version.toLong(),
            state = AgentItemState.of(item.state),
            title = item.title,
            text = record.plaintext,
            itemId = item.itemId,
            session = item.session,
            turn = item.turn,
            body = AgentItemBodyParser.parse(fields),
            outputCutLength = record.cutValue("output"),
            diffsCut = record.cutValue("diffs")?.toInt() ?: 0,
        )
    }
}

/** The number a `cut` tag gives for [name]: how much the agent left out of that field. */
private fun AppMessageRecordFfi.cutValue(name: String): Long? =
    tags
        .firstOrNull { it.values.getOrNull(0) == "cut" && it.values.getOrNull(1) == name }
        ?.values
        ?.getOrNull(2)
        ?.toLongOrNull()
