package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.core.agent.AgentItemProjector
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** One version of a demo agent item: everything the carrier writes except the version bookkeeping. */
internal data class AgentDemoItem(
    val itemId: String,
    val type: String,
    val state: String,
    val title: String,
    val text: String = "",
    val body: JSONObject = JSONObject(),
    val turn: String? = null,
    val startedByEventId: String? = null,
)

/**
 * Writes demo timeline records in the marmot-agents carrier shape: an item's first event, then later
 * versions that name it, and the agent's words as ordinary chat messages with their turn tag.
 * The records stay on the device; nothing here is sent.
 */
internal class AgentDemoRecords(
    private val viewerId: String,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val newEventId: () -> String = ::randomDemoEventId,
) {
    // Read by answers arriving from the mutation scope while the script writes it.
    private val firstEvents = ConcurrentHashMap<String, String>()
    private val versions = HashMap<String, Long>()
    private var seq = 0L

    /** A chat message from [sender]: the viewer's prompt or the agent's reply. */
    fun chat(
        sender: String,
        text: String,
        document: MarkdownDocumentFfi,
        turn: String? = null,
    ): AppMessageRecordFfi = record(sender, CHAT_KIND, text, listOfNotNull(turn?.let { listOf("turn", it) }), document)

    /** The next version of [item], from the agent. */
    fun item(item: AgentDemoItem): AppMessageRecordFfi {
        val version = (versions[item.itemId] ?: 0L) + 1L
        versions[item.itemId] = version
        seq += 1
        val fields = JSONObject().put("type", item.type).put("body", item.body)
        val tags =
            buildList {
                add(listOf("v", version.toString()))
                add(listOf("state", item.state))
                add(listOf("title", item.title))
                add(listOf("harness", HARNESS))
                add(listOf("item_id", item.itemId))
                add(listOf("seq", seq.toString()))
                add(listOf("fields", fields.toString()))
                firstEvents[item.itemId]?.let { add(listOf("item", it)) }
                add(listOf("session", SESSION))
                item.turn?.let { add(listOf("turn", it)) }
                item.startedByEventId?.let { add(listOf("e", it)) }
            }
        val record = record(AGENT_ID, AgentItemProjector.ITEM_KIND, item.text, tags, EMPTY_MARKDOWN_DOCUMENT)
        firstEvents.putIfAbsent(item.itemId, record.messageIdHex)
        return record
    }

    /** The id of [itemId]'s first event, which requests name as their target. */
    fun firstEventId(itemId: String): String? = firstEvents[itemId]

    private fun record(
        sender: String,
        kind: ULong,
        text: String,
        tags: List<List<String>>,
        document: MarkdownDocumentFfi,
    ): AppMessageRecordFfi {
        val seconds = (clockMs() / MILLIS_PER_SECOND).toULong()
        return AppMessageRecordFfi(
            invalidated = false,
            messageIdHex = newEventId(),
            direction = if (sender == viewerId) "sent" else "received",
            groupIdHex = GROUP_ID,
            sender = sender,
            plaintext = text,
            contentTokens = document,
            kind = kind,
            tags = tags.map(::MessageTagFfi),
            sticker = null,
            sourceEpoch = 1uL,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = seconds,
            receivedAt = seconds,
        )
    }

    companion object {
        /** A fixed key no real account uses, so the demo agent can never match a contact. */
        const val AGENT_ID = "a9e7d3c1b5f2048e6d7c9a1b3e5f70824c6e8a0b2d4f61839e5c7a9b1d3f5e70"
        const val GROUP_ID = "a9e7d3c1b5f2048e6d7c9a1b3e5f7082"
        const val SESSION = "demo-session"
        private const val HARNESS = "demo"
        private const val CHAT_KIND = 9uL
        private const val MILLIS_PER_SECOND = 1_000L
    }
}

private val demoRandom = SecureRandom()

private fun randomDemoEventId(): String {
    val bytes = ByteArray(EVENT_ID_BYTES).also(demoRandom::nextBytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private const val EVENT_ID_BYTES = 32
