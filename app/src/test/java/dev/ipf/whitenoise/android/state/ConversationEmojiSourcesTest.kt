package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.core.TimelineProjector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Received emoji sources ignore built-ins and reuse each row's native tag read until that row changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationEmojiSourcesTest {
    /** Built-in-only rows are never read, unrelated row updates reuse the first read, and departed rows are dropped. */
    @Test
    fun builtinRowsAreSkippedAndUnchangedRowsReuseTheirTagRead() =
        runBlocking {
            MediaDownloadIntegrationFixture().use { fixture ->
                val group = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP)
                val controller = ConversationController(fixture.state, group)
                val builtin = projected(BUILTIN_ID, "hi :marmot:", MARMOT_URL)
                val custom = projected(CUSTOM_ID, "hi :party: :marmot:", PARTY_URL)
                val customTags = listOf(emojiTag(":party:", PARTY_URL), emojiTag(":marmot:", PARTY_URL))
                val stored =
                    mapOf(
                        BUILTIN_ID to builtin.copy(tags = listOf(emojiTag(":marmot:", MARMOT_URL))),
                        CUSTOM_ID to custom.copy(tags = customTags),
                    )
                var reads = 0
                fixture.onTimelineMessages = { query ->
                    if (query.beforeMessageId != null) reads++
                    storedNeighbour(stored, query)
                }
                val definitions: EmojiSourceDefinitions = HashMap()

                controller.timeline = listOf(row(builtin), row(custom))
                val rows = controller.emojiSourceRows()
                val sources = controller.conversationEmojiSources(rows, definitions)
                assertEquals(listOf(CUSTOM_ID), rows.map { it.messageIdHex })
                assertEquals(setOf(":party:"), sources.keys)
                assertEquals(1, reads)

                controller.timeline = listOf(row(builtin), row(custom).copy(status = MessageStatus.Sent))
                controller.conversationEmojiSources(controller.emojiSourceRows(), definitions)
                assertEquals(1, reads)

                controller.timeline = listOf(row(builtin))
                assertTrue(controller.conversationEmojiSources(controller.emojiSourceRows(), definitions).isEmpty())
                assertTrue(definitions.isEmpty())
            }
        }

    /**
     * Answers the tag reader's neighbour steps: a step back from a stored row yields a synthetic predecessor, and
     * a step forward from that predecessor yields the stored row with its full tags.
     */
    private fun storedNeighbour(
        stored: Map<String, TimelineMessageRecordFfi>,
        query: TimelineMessageQueryFfi,
    ): TimelinePageFfi {
        val before = query.beforeMessageId
        val after = query.afterMessageId
        val rows =
            when {
                before != null -> listOfNotNull(stored[before]?.copy(messageIdHex = before + PREDECESSOR))
                after != null -> listOfNotNull(stored[after.removeSuffix(PREDECESSOR)])
                else -> emptyList()
            }
        return TimelinePageFfi(rows, hasMoreBefore = false, hasMoreAfter = false)
    }

    /** A window row for [projected], which carries no event tags, as MarmotKit window rows do not. */
    private fun row(projected: TimelineMessageRecordFfi): TimelineMessage {
        val record = TimelineProjector.toAppMessageRecord(projected)
        return TimelineMessage(projected.messageIdHex, record, MessageStatus.Received, projected)
    }

    /** One received kind-9 record whose single image is reachable at [url]. */
    private fun projected(
        messageId: String,
        text: String,
        url: String,
    ): TimelineMessageRecordFfi {
        val locator = MediaLocatorFfi("blossom", url)
        val reference = MediaDownloadIntegrationFixture.reference(0).copy(locators = listOf(locator))
        val tokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0))
        return TimelineMessageRecordFfi(
            clientToken = null,
            messageIdHex = messageId,
            sourceMessageIdHex = messageId,
            direction = "received",
            groupIdHex = MediaDownloadIntegrationFixture.GROUP,
            sender = "33".repeat(32),
            plaintext = text,
            contentTokens = tokens,
            kind = 9uL,
            tags = emptyList(),
            timelineAt = 11uL,
            receivedAt = 11uL,
            replyToMessageIdHex = null,
            replyPreview = null,
            mediaJson = null,
            media = MessageAttachments.acceptedOutcomes(listOf(reference)),
            thread = null,
            poll = null,
            groupSystem = null,
            hasReports = false,
            edit = null,
            reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
            deleted = false,
            deletionSource = DeletionSourceFfi.UNKNOWN,
            deletedByMessageIdHex = null,
            invalidationStatus = null,
            sourceEpoch = 1uL,
            retentionSeconds = null,
            retentionExpiresAt = null,
            sticker = null,
            importedFrom = null,
            recipientConfirmed = false,
            historyImportProvenance = null,
        )
    }

    /** The `emoji` tag a sender attaches to name [url]'s image as [code]. */
    private fun emojiTag(
        code: String,
        url: String,
    ) = MessageTagFfi(Nip30Emoji.tag(code, url))

    private companion object {
        val BUILTIN_ID = "a1".repeat(32)
        val CUSTOM_ID = "b2".repeat(32)
        const val PREDECESSOR = "-previous"
        const val MARMOT_URL = "https://blossom.example/marmot"
        const val PARTY_URL = "https://blossom.example/party"
    }
}
