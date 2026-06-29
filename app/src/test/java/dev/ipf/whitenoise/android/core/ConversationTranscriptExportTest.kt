package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionEmojiFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineUserReactionFfi
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.Instant

class ConversationTranscriptExportTest {
    @Test
    fun documentEncodesTimelineMetadataInChronologicalOrder() {
        val groupId = "aa".repeat(32)
        val firstId = "11".repeat(32)
        val secondId = "22".repeat(32)
        val records =
            listOf(
                timelineRecord(
                    messageIdHex = secondId,
                    plaintext = "final answer",
                    kind = 9uL,
                    tags = listOf(MessageTagFfi(listOf("e", firstId, "reply"))),
                    timelineAt = 2uL,
                    reactions =
                        TimelineReactionSummaryFfi(
                            byEmoji = listOf(TimelineReactionEmojiFfi("👍", 2u, listOf("alice", "bob"))),
                            userReactions =
                                listOf(
                                    TimelineUserReactionFfi(
                                        reactionMessageIdHex = "33".repeat(32),
                                        targetMessageIdHex = secondId,
                                        sender = "alice",
                                        emoji = "👍",
                                        reactedAt = 3uL,
                                    ),
                                ),
                        ),
                ),
                timelineRecord(
                    messageIdHex = firstId,
                    plaintext = "{\"system_type\":\"group_renamed\"}",
                    kind = 1210uL,
                    timelineAt = 1uL,
                    groupSystem =
                        GroupSystemEventFfi(
                            systemType = "group_renamed",
                            text = "Group renamed",
                            actorAccountIdHex = "alice",
                            subjectAccountIdHex = null,
                            name = "Hermes 2",
                            oldName = null,
                            oldRetentionSeconds = null,
                            newRetentionSeconds = null,
                        ),
                ),
            )

        val document =
            ConversationTranscriptExport.makeDocument(
                group = testExportGroup(name = "Hermes 2", groupIdHex = groupId),
                messages = records,
                exportedAt = Instant.parse("2023-11-14T22:13:20Z"),
            )
        val json = JSONObject(String(ConversationTranscriptExport.encodeJson(document), StandardCharsets.UTF_8))

        assertEquals(1, json.getInt("v"))
        assertEquals("2023-11-14T22:13:20Z", json.getString("exported_at"))
        assertEquals(groupId, json.getString("group_id_hex"))
        assertEquals("Hermes 2", json.getString("group_name"))
        assertEquals(2, json.getInt("event_count"))

        val events = json.getJSONArray("events")
        assertEquals(firstId, events.getJSONObject(0).getString("message_id_hex"))
        assertEquals(1210L, events.getJSONObject(0).getLong("kind"))
        assertEquals("group_renamed", events.getJSONObject(0).getJSONObject("group_system").getString("system_type"))
        val secondEvent = events.getJSONObject(1)
        assertEquals(secondId, secondEvent.getString("message_id_hex"))
        assertEquals("final answer", secondEvent.getString("content"))
        assertEquals("e", secondEvent.getJSONArray("tags").getJSONArray(0).getString(0))
        val reactions = secondEvent.getJSONObject("reactions")
        assertEquals("👍", reactions.getJSONArray("by_emoji").getJSONObject(0).getString("emoji"))
        val reactionSenders = reactions.getJSONArray("by_emoji").getJSONObject(0).getJSONArray("senders")
        assertEquals(2, reactionSenders.length())
        assertEquals("alice", reactions.getJSONArray("user_reactions").getJSONObject(0).getString("sender"))
    }

    @Test
    fun documentSanitizesGroupNameAndFallsBackToShortGroupId() {
        val groupId = "ab".repeat(32)
        val sanitized =
            ConversationTranscriptExport.makeDocument(
                group = testExportGroup(name = "\u202E  Secret\n\tRoom \u200B", groupIdHex = groupId),
                messages = emptyList(),
                exportedAt = Instant.parse("2023-11-14T22:13:20Z"),
            )
        assertEquals("Secret Room", sanitized.getString("group_name"))

        val fallback =
            ConversationTranscriptExport.makeDocument(
                group = testExportGroup(name = "\u202E\u200B\n\t", groupIdHex = groupId),
                messages = emptyList(),
                exportedAt = Instant.parse("2023-11-14T22:13:20Z"),
            )
        assertEquals(IdentityFormatter.short(groupId), fallback.getString("group_name"))
    }

    @Test
    fun fetchAllMessagesPaginatesByOldestMessageAndSortsChronologically() =
        runBlocking {
            val newestId = "33".repeat(32)
            val middleId = "22".repeat(32)
            val oldestId = "11".repeat(32)
            val groupId = "aa".repeat(32)
            val reader =
                FakeTranscriptTimelineReader(
                    pages =
                        mutableListOf(
                            TimelinePageFfi(
                                messages =
                                    listOf(
                                        timelineRecord(messageIdHex = newestId, timelineAt = 3uL),
                                        timelineRecord(messageIdHex = middleId, timelineAt = 2uL),
                                    ),
                                hasMoreBefore = true,
                                hasMoreAfter = false,
                            ),
                            TimelinePageFfi(
                                messages = listOf(timelineRecord(messageIdHex = oldestId, timelineAt = 1uL)),
                                hasMoreBefore = false,
                                hasMoreAfter = false,
                            ),
                        ),
                )

            val messages =
                ConversationTranscriptExport.fetchAllMessages(
                    timelineReader = reader,
                    accountRef = "account-1",
                    groupIdHex = groupId,
                )

            assertEquals(listOf(oldestId, middleId, newestId), messages.map { it.messageIdHex })
            assertEquals(listOf("account-1", "account-1"), reader.accountRefs)
            assertEquals(2, reader.queries.size)
            assertEquals(groupId, reader.queries.first().groupIdHex)
            assertNull(reader.queries.first().before)
            assertNull(reader.queries.first().beforeMessageId)
            assertEquals(ConversationTranscriptExport.PageLimit, reader.queries.first().limit)
            assertEquals(2uL, reader.queries.last().before)
            assertEquals(middleId, reader.queries.last().beforeMessageId)
        }

    @Test
    fun fetchAllMessagesDeduplicatesOverlappingPageBoundary() =
        runBlocking {
            val newestId = "33".repeat(32)
            val boundaryId = "22".repeat(32)
            val oldestId = "11".repeat(32)
            val groupId = "aa".repeat(32)
            val reader =
                FakeTranscriptTimelineReader(
                    pages =
                        mutableListOf(
                            TimelinePageFfi(
                                messages =
                                    listOf(
                                        timelineRecord(messageIdHex = newestId, timelineAt = 3uL),
                                        timelineRecord(messageIdHex = boundaryId, timelineAt = 2uL),
                                    ),
                                hasMoreBefore = true,
                                hasMoreAfter = false,
                            ),
                            TimelinePageFfi(
                                messages =
                                    listOf(
                                        timelineRecord(messageIdHex = boundaryId, timelineAt = 2uL),
                                        timelineRecord(messageIdHex = oldestId, timelineAt = 1uL),
                                    ),
                                hasMoreBefore = false,
                                hasMoreAfter = false,
                            ),
                        ),
                )

            val messages =
                ConversationTranscriptExport.fetchAllMessages(
                    timelineReader = reader,
                    accountRef = "account-1",
                    groupIdHex = groupId,
                )

            assertEquals(listOf(oldestId, boundaryId, newestId), messages.map { it.messageIdHex })
            assertEquals(3, messages.map { it.messageIdHex }.toSet().size)
            assertEquals(2, reader.queries.size)
            assertNull(reader.queries.first().before)
            assertNull(reader.queries.first().beforeMessageId)
            assertEquals(2uL, reader.queries.last().before)
            assertEquals(boundaryId, reader.queries.last().beforeMessageId)
        }

    @Test
    fun fetchAllMessagesStopsWithoutDuplicatingWhenCursorStalls() =
        runBlocking {
            val newestId = "44".repeat(32)
            val oldestId = "22".repeat(32)
            val page =
                TimelinePageFfi(
                    messages =
                        listOf(
                            timelineRecord(messageIdHex = newestId, timelineAt = 4uL),
                            timelineRecord(messageIdHex = oldestId, timelineAt = 2uL),
                        ),
                    hasMoreBefore = true,
                    hasMoreAfter = false,
                )
            val reader = RepeatingTranscriptTimelineReader(page = page, maximumReads = 2)

            val messages =
                ConversationTranscriptExport.fetchAllMessages(
                    timelineReader = reader,
                    accountRef = "account-1",
                    groupIdHex = "aa".repeat(32),
                )

            assertEquals(listOf(oldestId, newestId), messages.map { it.messageIdHex })
            assertEquals(2, reader.queries.size)
            assertNull(reader.queries[0].before)
            assertNull(reader.queries[0].beforeMessageId)
            assertEquals(2uL, reader.queries[1].before)
            assertEquals(oldestId, reader.queries[1].beforeMessageId)
        }

    @Test
    fun writeTemporaryFileUsesTranscriptCacheDirAndStableName() {
        val root =
            kotlin.io.path
                .createTempDirectory("wn-transcript-test-")
                .toFile()
        try {
            val data = "{\"private\":true}\n".toByteArray(StandardCharsets.UTF_8)
            val file =
                ConversationTranscriptExport.writeTemporaryFile(
                    cacheDir = root,
                    data = data,
                    groupIdHex = "ab".repeat(32),
                    exportedAt = Instant.parse("2023-11-14T22:13:20Z"),
                )

            assertEquals("transcripts", file.parentFile?.name)
            assertTrue(file.name.startsWith("whitenoise-transcript-abababab-2023-11-14T22-13-20Z"))
            assertTrue(file.name.endsWith(".json"))
            assertEquals("{\"private\":true}\n", file.readText())
            assertFalse(file.canExecute())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeTemporaryFileReapsAnEarlierTranscriptSoCleartextDoesNotAccumulate() {
        // A prior export orphaned by a process kill mid-share must not survive
        // alongside a fresh one: each export leaves at most its own decrypted
        // transcript in cache (#841).
        val root =
            kotlin.io.path
                .createTempDirectory("wn-transcript-test-")
                .toFile()
        try {
            val first =
                ConversationTranscriptExport.writeTemporaryFile(
                    cacheDir = root,
                    data = "{\"old\":true}\n".toByteArray(StandardCharsets.UTF_8),
                    groupIdHex = "ab".repeat(32),
                    exportedAt = Instant.parse("2023-11-14T22:13:20Z"),
                )
            val second =
                ConversationTranscriptExport.writeTemporaryFile(
                    cacheDir = root,
                    data = "{\"new\":true}\n".toByteArray(StandardCharsets.UTF_8),
                    groupIdHex = "cd".repeat(32),
                    exportedAt = Instant.parse("2023-11-15T22:13:20Z"),
                )

            assertFalse("earlier transcript should be reaped", first.exists())
            assertTrue(second.exists())
            assertEquals(listOf(second.name), second.parentFile?.listFiles()?.map { it.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sweepStaleTranscriptsRemovesEveryFileAndReportsTheCount() {
        val dir =
            kotlin.io.path
                .createTempDirectory("wn-transcript-sweep-")
                .toFile()
        try {
            java.io.File(dir, "whitenoise-transcript-a.json").writeText("{}")
            java.io.File(dir, "whitenoise-transcript-b.json").writeText("{}")

            assertEquals(2, ConversationTranscriptExport.sweepStaleTranscripts(dir))
            assertEquals(emptyList<String>(), dir.listFiles()?.map { it.name })
            // A missing directory is a no-op, not a crash.
            assertEquals(0, ConversationTranscriptExport.sweepStaleTranscripts(java.io.File(dir, "missing")))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun testExportGroup(
        name: String,
        groupIdHex: String = "aa".repeat(32),
    ) = AppGroupRecordFfi(
        groupIdHex = groupIdHex,
        endpoint = "",
        name = name,
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "bb".repeat(32),
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        encryptedMedia =
            AppGroupEncryptedMediaComponentFfi(
                componentId = 0x8008u,
                component = "marmot.group.encrypted-media.v1",
                required = true,
                mediaFormat = "encrypted-media-v1",
                allowedLocatorKinds = listOf("blossom-v1"),
                defaultBlobEndpoints = listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net")),
            ),
        archived = false,
        pendingConfirmation = false,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
        disappearingMessageSecs = 0uL,
    )

    private fun timelineRecord(
        messageIdHex: String,
        direction: String = "received",
        sender: String = "99".repeat(32),
        plaintext: String = "hello",
        kind: ULong = 9uL,
        tags: List<MessageTagFfi> = emptyList(),
        timelineAt: ULong,
        reactions: TimelineReactionSummaryFfi = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        groupSystem: GroupSystemEventFfi? = null,
    ) = TimelineMessageRecordFfi(
        messageIdHex = messageIdHex,
        sourceMessageIdHex = null,
        direction = direction,
        groupIdHex = "aa".repeat(32),
        sender = sender,
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
        kind = kind,
        tags = tags,
        timelineAt = timelineAt,
        receivedAt = timelineAt,
        replyToMessageIdHex = null,
        replyPreview = null,
        mediaJson = null,
        media = emptyList(),
        agentTextStreamJson = null,
        groupSystem = groupSystem,
        reactions = reactions,
        deleted = false,
        deletedByMessageIdHex = null,
        invalidationStatus = null,
    )
}

private class FakeTranscriptTimelineReader(
    private val pages: MutableList<TimelinePageFfi>,
) : ConversationTranscriptTimelineReader {
    val accountRefs = mutableListOf<String>()
    val queries = mutableListOf<TimelineMessageQueryFfi>()

    override suspend fun timelineMessages(
        accountRef: String,
        query: TimelineMessageQueryFfi,
    ): TimelinePageFfi {
        accountRefs += accountRef
        queries += query
        return pages.removeFirst()
    }
}

private class RepeatingTranscriptTimelineReader(
    private val page: TimelinePageFfi,
    private val maximumReads: Int,
) : ConversationTranscriptTimelineReader {
    val queries = mutableListOf<TimelineMessageQueryFfi>()

    override suspend fun timelineMessages(
        accountRef: String,
        query: TimelineMessageQueryFfi,
    ): TimelinePageFfi {
        queries += query
        if (queries.size > maximumReads) error("exceeded maximum reads")
        return page
    }
}
