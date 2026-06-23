package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineReplyPreviewFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyNavigationTest {
    @Test
    fun typedReplyPreviewIdWinsOverLegacyQuoteTag() {
        assertEquals(
            "typed-parent",
            ReplyNavigation.targetMessageId(
                record = message(tags = listOf(MessageProjector.quoteTag("legacy-parent"))),
                projected = timelineRecord(replyPreviewMessageId = "typed-parent"),
            ),
        )
    }

    @Test
    fun legacyQuoteTagIsUsedWhenTypedPreviewIsUnavailable() {
        assertEquals(
            "legacy-parent",
            ReplyNavigation.targetMessageId(
                record = message(tags = listOf(MessageProjector.quoteTag("legacy-parent"))),
                projected = null,
            ),
        )
    }

    @Test
    fun typedReplyTargetIdIsUsedWhenFullPreviewIsUnavailable() {
        assertEquals(
            "typed-parent",
            ReplyNavigation.targetMessageId(
                record = message(tags = listOf(MessageProjector.quoteTag("legacy-parent"))),
                projected = timelineRecord(replyPreviewMessageId = null, replyToMessageIdHex = "typed-parent"),
            ),
        )
    }

    @Test
    fun olderPageLookupStopsWhenFoundExhaustedOrBounded() {
        assertTrue(ReplyNavigation.shouldLoadOlder(targetLoaded = false, hasMoreBefore = true, loadedPageCount = 0))
        assertFalse(ReplyNavigation.shouldLoadOlder(targetLoaded = true, hasMoreBefore = true, loadedPageCount = 0))
        assertFalse(ReplyNavigation.shouldLoadOlder(targetLoaded = false, hasMoreBefore = false, loadedPageCount = 0))
        assertFalse(
            ReplyNavigation.shouldLoadOlder(
                targetLoaded = false,
                hasMoreBefore = true,
                loadedPageCount = ReplyNavigation.MaxOlderPages,
            ),
        )
    }

    private fun message(tags: List<MessageTagFfi>) =
        AppMessageRecordFfi(
            messageIdHex = "reply",
            direction = "received",
            groupIdHex = "group",
            sender = "alice",
            plaintext = "reply",
            contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
            kind = 9uL,
            tags = tags,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    private fun timelineRecord(
        replyPreviewMessageId: String?,
        replyToMessageIdHex: String? = replyPreviewMessageId,
    ) = TimelineMessageRecordFfi(
        messageIdHex = "reply",
        sourceMessageIdHex = null,
        direction = "received",
        groupIdHex = "group",
        sender = "alice",
        plaintext = "reply",
        contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
        kind = 9uL,
        tags = emptyList(),
        timelineAt = 1uL,
        receivedAt = 1uL,
        invalidationStatus = null,
        replyToMessageIdHex = replyToMessageIdHex,
        replyPreview =
            replyPreviewMessageId?.let {
                TimelineReplyPreviewFfi(
                    messageIdHex = it,
                    sender = "bob",
                    plaintext = "parent",
                    contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
                    kind = 9uL,
                    mediaJson = null,
                    media = emptyList(),
                    agentTextStreamJson = null,
                    deleted = false,
                )
            },
        mediaJson = null,
        media = emptyList(),
        agentTextStreamJson = null,
        groupSystem = null,
        reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        deleted = false,
        deletedByMessageIdHex = null,
    )
}
