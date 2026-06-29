package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext

interface ConversationTranscriptTimelineReader {
    suspend fun timelineMessages(
        accountRef: String,
        query: TimelineMessageQueryFfi,
    ): TimelinePageFfi
}

/** Builds a chronological JSON transcript of a conversation's inner Marmot/Nostr app events. */
object ConversationTranscriptExport {
    val PageLimit: UInt = 200u
    const val CacheDirName = "transcripts"
    private const val FilePrefix = "whitenoise-transcript"

    suspend fun fetchAllMessages(
        timelineReader: ConversationTranscriptTimelineReader,
        accountRef: String,
        groupIdHex: String,
    ): List<TimelineMessageRecordFfi> {
        val collected = linkedMapOf<String, TimelineMessageRecordFfi>()
        var before: ULong? = null
        var beforeMessageId: String? = null

        fun appendUnique(messages: List<TimelineMessageRecordFfi>) {
            messages.forEach { message ->
                if (!collected.containsKey(message.messageIdHex)) {
                    collected[message.messageIdHex] = message
                }
            }
        }

        while (true) {
            coroutineContext.ensureActive()
            val page =
                timelineReader.timelineMessages(
                    accountRef = accountRef,
                    query =
                        TimelineMessageQueryFfi(
                            groupIdHex = groupIdHex,
                            search = null,
                            before = before,
                            beforeMessageId = beforeMessageId,
                            after = null,
                            afterMessageId = null,
                            limit = PageLimit,
                        ),
                )
            coroutineContext.ensureActive()

            if (!page.hasMoreBefore || page.messages.isEmpty()) {
                appendUnique(page.messages)
                break
            }

            val oldest = oldestMessage(page.messages)
            val nextBefore = oldest.timelineAt
            val nextBeforeMessageId = oldest.messageIdHex
            if (before == nextBefore && beforeMessageId == nextBeforeMessageId) {
                break
            }

            appendUnique(page.messages)
            before = nextBefore
            beforeMessageId = nextBeforeMessageId
        }

        return sortChronologically(collected.values.toList())
    }

    fun makeDocument(
        group: AppGroupRecordFfi,
        messages: List<TimelineMessageRecordFfi>,
        exportedAt: Instant = Instant.now(),
    ): JSONObject {
        val ordered = sortChronologically(messages)
        val events = JSONArray()
        ordered.forEachIndexed { index, record ->
            events.put(eventJson(index, record))
        }
        val groupName = ProfileSanitizer.displayName(group.name) ?: IdentityFormatter.short(group.groupIdHex)
        return JSONObject()
            .put("v", 1)
            .put("exported_at", iso8601Timestamp(exportedAt))
            .put("group_id_hex", group.groupIdHex)
            .put("group_name", groupName)
            .put("event_count", ordered.size)
            .put("events", events)
    }

    fun encodeJson(document: JSONObject): ByteArray = (document.toString(2) + "\n").toByteArray(StandardCharsets.UTF_8)

    /**
     * Delete any transcript files left in [transcriptsDir] from earlier exports.
     * The share flow already deletes its file deterministically once the share
     * sheet returns, so this only reaps an export orphaned by a process kill
     * mid-share — bounding the decrypted-transcript cache to at most the current
     * export instead of waiting on a later age-based startup sweep. Returns the
     * number of files removed. Best-effort: unreadable/locked entries are skipped.
     */
    fun sweepStaleTranscripts(transcriptsDir: File): Int =
        transcriptsDir
            .listFiles()
            .orEmpty()
            .count { it.isFile && runCatching { it.delete() }.getOrDefault(false) }

    fun writeTemporaryFile(
        cacheDir: File,
        data: ByteArray,
        groupIdHex: String,
        exportedAt: Instant = Instant.now(),
    ): File {
        val dir = File(cacheDir, CacheDirName).apply { mkdirs() }
        // Reap any earlier decrypted transcript before writing a new one. The
        // exports are serialized behind a modal share sheet, so a leftover file
        // here is a prior export's orphan, never one being actively shared.
        // (No deleteOnExit fallback: its JVM shutdown hook effectively never
        // runs on a process-killed Android app, so it left cleartext behind.)
        sweepStaleTranscripts(dir)
        val file = File.createTempFile(fileNamePrefix(groupIdHex, exportedAt), ".json", dir)
        file.writeBytes(data)
        file.setExecutable(false, false)
        return file
    }

    private fun eventJson(
        index: Int,
        record: TimelineMessageRecordFfi,
    ): JSONObject =
        JSONObject()
            .put("index", index)
            .put("message_id_hex", record.messageIdHex)
            .putNullable("source_message_id_hex", record.sourceMessageIdHex)
            .put("kind", record.kind.jsonNumber())
            .put("content", record.plaintext)
            .put("tags", tagsJson(record.tags))
            .put("direction", record.direction)
            .put("sender", record.sender)
            .put("timeline_at", record.timelineAt.jsonNumber())
            .put("received_at", record.receivedAt.jsonNumber())
            .putNullable("reply_to_message_id_hex", record.replyToMessageIdHex)
            .putNullable("media_json", record.mediaJson)
            .putNullable("agent_text_stream_json", record.agentTextStreamJson)
            .put("reactions", reactionsJson(record.reactions))
            .putNullable("group_system", record.groupSystem?.let(::groupSystemJson))
            .put("deleted", record.deleted)
            .putNullable("deleted_by_message_id_hex", record.deletedByMessageIdHex)
            .putNullable("invalidation_status", record.invalidationStatus)

    private fun tagsJson(tags: List<MessageTagFfi>): JSONArray =
        JSONArray().apply {
            tags.forEach { tag ->
                put(
                    JSONArray().apply {
                        tag.values.forEach { value -> put(value) }
                    },
                )
            }
        }

    private fun reactionsJson(reactions: TimelineReactionSummaryFfi): JSONObject =
        JSONObject()
            .put(
                "by_emoji",
                JSONArray().apply {
                    reactions.byEmoji.forEach { entry ->
                        put(
                            JSONObject()
                                .put("emoji", entry.emoji)
                                .put("senders", JSONArray().apply { entry.senders.forEach { sender -> put(sender) } }),
                        )
                    }
                },
            ).put(
                "user_reactions",
                JSONArray().apply {
                    reactions.userReactions.forEach { reaction ->
                        put(
                            JSONObject()
                                .put("reaction_message_id_hex", reaction.reactionMessageIdHex)
                                .put("target_message_id_hex", reaction.targetMessageIdHex)
                                .put("sender", reaction.sender)
                                .put("emoji", reaction.emoji)
                                .put("reacted_at", reaction.reactedAt.jsonNumber()),
                        )
                    }
                },
            )

    private fun groupSystemJson(event: GroupSystemEventFfi): JSONObject =
        JSONObject()
            .put("system_type", event.systemType)
            .put("text", event.text)
            .putNullable("actor_account_id_hex", event.actorAccountIdHex)
            .putNullable("subject_account_id_hex", event.subjectAccountIdHex)
            .putNullable("name", event.name)

    private fun oldestMessage(messages: List<TimelineMessageRecordFfi>): TimelineMessageRecordFfi =
        messages.minWith(compareBy<TimelineMessageRecordFfi> { it.timelineAt }.thenBy { it.messageIdHex })

    private fun sortChronologically(messages: List<TimelineMessageRecordFfi>): List<TimelineMessageRecordFfi> =
        messages.sortedWith(compareBy<TimelineMessageRecordFfi> { it.timelineAt }.thenBy { it.messageIdHex })

    private fun fileNamePrefix(
        groupIdHex: String,
        exportedAt: Instant,
    ): String {
        val prefix = groupIdHex.take(8).ifBlank { "unknown" }
        val stamp = iso8601Timestamp(exportedAt).replace(":", "-")
        return "$FilePrefix-$prefix-$stamp-"
    }

    private fun iso8601Timestamp(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)
}

private fun JSONObject.putNullable(
    name: String,
    value: Any?,
): JSONObject = put(name, value ?: JSONObject.NULL)

private fun ULong.jsonNumber(): BigInteger = BigInteger(toString())
