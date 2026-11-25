package dev.ipf.whitenoise.android.ui.conversation.agent

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownCodeBlockKindFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.agent.AgentItemProjector
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import org.json.JSONObject

/**
 * Real marmot-agents sessions as the timeline records an app receives.
 *
 * Each recording line is one agent event. A `message` becomes the agent's kind 9 chat message with
 * its turn tag; every other type becomes a kind 41201 item whose later versions name the first
 * event, exactly as the agent core's carrier writes them.
 */
internal object AgentRecordingFixtures {
    val VIEWER_ID = "11".repeat(32)
    val AGENT_ID = "22".repeat(32)
    val GROUP_ID = "44".repeat(32)

    /**
     * Loads [name] from `src/test/resources/agent-recordings`. [events] keeps only the first events,
     * to show a turn mid-way; [prompt] adds the viewer's message that started the turn.
     */
    fun load(
        name: String,
        events: Int = Int.MAX_VALUE,
        prompt: String? = null,
        firstId: Int = 1,
    ): List<AppMessageRecordFfi> {
        val lines =
            checkNotNull(javaClass.classLoader?.getResourceAsStream("agent-recordings/$name.jsonl")) { name }
                .bufferedReader()
                .readLines()
                .filter(String::isNotBlank)
                .take(events)
        val builder = Builder(firstId)
        prompt?.let { builder.chat(VIEWER_ID, it, turn = null) }
        lines.forEach { builder.event(JSONObject(it)) }
        return builder.records
    }

    fun timeline(records: List<AppMessageRecordFfi>): List<TimelineMessage> =
        records.map { record ->
            TimelineMessage(
                id = "msg:${record.messageIdHex}",
                record = record,
                status = if (record.sender == VIEWER_ID) MessageStatus.Sent else MessageStatus.Received,
            )
        }

    private class Builder(
        private var next: Int,
    ) {
        val records = mutableListOf<AppMessageRecordFfi>()
        private val firstEvents = HashMap<String, String>()
        private val versions = HashMap<String, Long>()

        fun event(event: JSONObject) {
            val type = event.getString("type")
            val itemId = event.getString("item")
            val turn = event.optString("turn").takeIf(String::isNotEmpty)
            if (type == "message") {
                if (itemId !in firstEvents) firstEvents[itemId] = chat(AGENT_ID, event.getString("text"), turn)
                return
            }
            val version = (versions[itemId] ?: 0L) + 1L
            versions[itemId] = version
            val tags =
                buildList {
                    add(listOf("v", version.toString()))
                    add(listOf("state", event.getString("state")))
                    add(listOf("title", event.getString("title")))
                    add(listOf("harness", event.getString("harness")))
                    add(listOf("item_id", itemId))
                    add(listOf("seq", event.getLong("seq").toString()))
                    val fields = JSONObject().put("type", type).put("body", event.optJSONObject("body") ?: JSONObject())
                    add(listOf("fields", fields.toString()))
                    firstEvents[itemId]?.let { add(listOf("item", it)) }
                    event.optString("session").takeIf(String::isNotEmpty)?.let { add(listOf("session", it)) }
                    turn?.let { add(listOf("turn", it)) }
                }
            val id = add(AGENT_ID, AgentItemProjector.ITEM_KIND, event.getString("text"), tags, plainDocument(""))
            firstEvents.putIfAbsent(itemId, id)
        }

        fun chat(
            sender: String,
            text: String,
            turn: String?,
        ): String = add(sender, 9uL, text, listOfNotNull(turn?.let { listOf("turn", it) }), markdownDocument(text))

        private fun add(
            sender: String,
            kind: ULong,
            text: String,
            tags: List<List<String>>,
            document: MarkdownDocumentFfi,
        ): String {
            val id = "%064x".format(next)
            records +=
                AppMessageRecordFfi(
                    invalidated = false,
                    messageIdHex = id,
                    direction = if (sender == VIEWER_ID) "sent" else "received",
                    groupIdHex = GROUP_ID,
                    sender = sender,
                    plaintext = text,
                    contentTokens = document,
                    kind = kind,
                    tags = tags.map { MessageTagFfi(it) },
                    sticker = null,
                    sourceEpoch = 1uL,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = BASE_TIME + next.toULong(),
                    receivedAt = BASE_TIME + next.toULong(),
                )
            next += 1
            return id
        }
    }

    private fun plainDocument(text: String) =
        MarkdownDocumentFfi(
            truncated = false,
            blankLinesBefore = byteArrayOf(),
            blocks =
                if (text.isEmpty()) {
                    emptyList()
                } else {
                    listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(text))))
                },
        )

    /** Enough Markdown for the recorded replies: paragraphs with `code` and **bold**, and fenced blocks. */
    private fun markdownDocument(text: String): MarkdownDocumentFfi {
        val blocks = mutableListOf<MarkdownBlockFfi>()
        val parts = text.trim().split("```")
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                val info = part.substringBefore('\n')
                val code = part.substringAfter('\n').trimEnd() + "\n"
                blocks += MarkdownBlockFfi.CodeBlock(MarkdownCodeBlockKindFfi.FENCED, info, code)
            } else {
                part.split("\n\n").map(String::trim).filter(String::isNotEmpty).forEach { paragraph ->
                    blocks += MarkdownBlockFfi.Paragraph(inlines(paragraph.replace("\n", " ")))
                }
            }
        }
        return MarkdownDocumentFfi(truncated = false, blankLinesBefore = byteArrayOf(), blocks = blocks)
    }

    private fun inlines(text: String): List<MarkdownInlineFfi> {
        val result = mutableListOf<MarkdownInlineFfi>()
        var last = 0
        INLINE.findAll(text).forEach { match ->
            if (match.range.first > last) result += MarkdownInlineFfi.Text(text.substring(last, match.range.first))
            val token = match.value
            result +=
                if (token.startsWith("`")) {
                    MarkdownInlineFfi.Code(token.trim('`'))
                } else {
                    MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text(token.removeSurrounding("**"))))
                }
            last = match.range.last + 1
        }
        if (last < text.length) result += MarkdownInlineFfi.Text(text.substring(last))
        return result
    }

    private val INLINE = Regex("`[^`]+`|\\*\\*[^*]+\\*\\*")
    private const val BASE_TIME = 1_795_000_000uL
}
