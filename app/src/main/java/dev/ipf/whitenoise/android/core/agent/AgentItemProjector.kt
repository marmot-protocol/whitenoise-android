package dev.ipf.whitenoise.android.core.agent

import dev.ipf.marmotkit.AppMessageRecordFfi
import org.json.JSONObject

/**
 * Reads marmot-agents items and requests from timeline records.
 *
 * Until MDK takes the new shape of kinds 1201 and 1202, agents send items under kind 41201 and
 * people send answers and controls under kind 41202 (marmot-agents D-069). Kind 1201 is read too
 * once it carries the new tags, so the switch needs no app release. The older kind 1201 activity
 * shape has no `fields` tag and is left to the existing renderers.
 */
object AgentItemProjector {
    const val ITEM_KIND: ULong = 41201uL
    const val REQUEST_KIND: ULong = 41202uL
    private const val MDK_ITEM_KIND: ULong = 1201uL
    private const val MDK_REQUEST_KIND: ULong = 1202uL

    /** True for every version of an agent item, the first and the later ones. */
    fun isAgentItem(record: AppMessageRecordFfi): Boolean =
        when (record.kind) {
            ITEM_KIND -> true
            MDK_ITEM_KIND -> record.tagValue(FIELDS_TAG) != null && record.tagValue(ITEM_ID_TAG) != null
            else -> false
        }

    /** True for an answer or a control a person sent to an agent. These never get a row of their own. */
    fun isAgentRequest(record: AppMessageRecordFfi): Boolean =
        when (record.kind) {
            REQUEST_KIND -> true
            MDK_REQUEST_KIND -> record.tagValue(DETAILS_TAG) != null
            else -> false
        }

    /** The first event of the item this record is a version of: itself for the first version. */
    fun firstEventId(record: AppMessageRecordFfi): String = record.tagValue(ITEM_TAG) ?: record.messageIdHex

    /**
     * True for a session item, read from the start of its `fields` tag without parsing the rest, so
     * sender grouping can ask it for every row cheaply. Sessions draw as dividers, outside any run.
     */
    fun isSessionItem(record: AppMessageRecordFfi): Boolean =
        isAgentItem(record) && record.tagValue(FIELDS_TAG)?.let { SESSION_FIELDS_PREFIX.containsMatchIn(it) } == true

    /** The item's version, or null when the record is not a readable item. */
    fun version(record: AppMessageRecordFfi): Long? = record.tagValue(VERSION_TAG)?.toLongOrNull()

    /**
     * Projects the item. [firstEventId] is the row identity; pass the first event's id when [record]
     * is a later version shown in the first event's place.
     */
    fun project(
        record: AppMessageRecordFfi,
        firstEventId: String = firstEventId(record),
    ): AgentItem? {
        val header = if (isAgentItem(record)) readHeader(record) else null
        val fields = record.tagValue(FIELDS_TAG)?.let { runCatching { JSONObject(it) }.getOrNull() }
        return if (header == null || fields == null) {
            null
        } else {
            AgentItem(
                firstEventId = firstEventId,
                version = header.version,
                state = header.state,
                title = record.tagValue(TITLE_TAG).orEmpty(),
                text = record.plaintext,
                harness = record.tagValue(HARNESS_TAG).orEmpty(),
                itemId = header.itemId,
                seq = record.tagValue(SEQ_TAG)?.toLongOrNull() ?: 0L,
                session = record.tagValue(SESSION_TAG),
                turn = record.tagValue(TURN_TAG),
                startedByEventId = record.tagValue(EVENT_TAG),
                body = AgentItemBodyParser.parse(fields),
                outputCutLength = record.cutValue("output"),
                diffsCut = record.cutValue("diffs")?.toInt() ?: 0,
            )
        }
    }

    /** The tags an item cannot be read without. */
    private class Header(
        val version: Long,
        val state: AgentItemState,
        val itemId: String,
    )

    private fun readHeader(record: AppMessageRecordFfi): Header? {
        val version = version(record)
        val state = AgentItemState.parse(record.tagValue(STATE_TAG))
        val itemId = record.tagValue(ITEM_ID_TAG)
        return if (version != null && state != null && itemId != null) Header(version, state, itemId) else null
    }

    private fun AppMessageRecordFfi.cutValue(name: String): Long? =
        tags
            .firstOrNull { it.values.getOrNull(0) == CUT_TAG && it.values.getOrNull(1) == name }
            ?.values
            ?.getOrNull(2)
            ?.toLongOrNull()

    internal fun AppMessageRecordFfi.tagValue(name: String): String? {
        val tag = tags.firstOrNull { it.values.firstOrNull() == name }
        return tag?.values?.getOrNull(1)
    }

    private val SESSION_FIELDS_PREFIX = Regex("^\\s*\\{\\s*\"type\"\\s*:\\s*\"session\"")
    private const val VERSION_TAG = "v"
    private const val STATE_TAG = "state"
    private const val TITLE_TAG = "title"
    private const val HARNESS_TAG = "harness"
    private const val ITEM_ID_TAG = "item_id"
    private const val SEQ_TAG = "seq"
    private const val FIELDS_TAG = "fields"
    private const val ITEM_TAG = "item"
    private const val SESSION_TAG = "session"
    private const val TURN_TAG = "turn"
    private const val EVENT_TAG = "e"
    private const val CUT_TAG = "cut"
    private const val DETAILS_TAG = "details"
}
