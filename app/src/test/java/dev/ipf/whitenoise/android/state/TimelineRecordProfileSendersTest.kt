package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineReplyPreviewFfi
import dev.ipf.marmotkit.TimelineUserReactionFfi
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression coverage for the bounded, newest-first first-presentation warm. */
class TimelineRecordProfileSendersTest {
    private fun emptyDoc() =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = emptyList(),
            blankLinesBefore = ByteArray(0),
        )

    private fun reaction(sender: String) =
        TimelineUserReactionFfi(
            reactionMessageIdHex = "r-$sender",
            targetMessageIdHex = "t",
            sender = sender,
            emoji = "👍",
            reactedAt = 0uL,
        )

    private fun replyPreview(sender: String) =
        TimelineReplyPreviewFfi(
            messageIdHex = "reply-target",
            sender = sender,
            plaintext = "quoted",
            contentTokens = emptyDoc(),
            kind = 9uL,
            mediaJson = null,
            media = emptyList(),
            agentTextStreamJson = null,
            deleted = false,
            invalidationStatus = null,
        )

    private fun record(
        id: String,
        sender: String,
        replyPreview: TimelineReplyPreviewFfi? = null,
        reactionSenders: List<String> = emptyList(),
    ) = TimelineMessageRecordFfi(
        messageIdHex = id,
        sourceMessageIdHex = id,
        direction = "received",
        groupIdHex = "g",
        sender = sender,
        plaintext = "hi",
        contentTokens = emptyDoc(),
        kind = 9uL,
        tags = emptyList(),
        timelineAt = 0uL,
        receivedAt = 0uL,
        replyToMessageIdHex = replyPreview?.messageIdHex,
        replyPreview = replyPreview,
        mediaJson = null,
        media = emptyList(),
        agentTextStreamJson = null,
        groupSystem = null,
        reactions =
            TimelineReactionSummaryFfi(
                byEmoji = emptyList(),
                userReactions = reactionSenders.map(::reaction),
            ),
        deleted = false,
        deletedByMessageIdHex = null,
        invalidationStatus = null,
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
    )

    @Test
    fun initialPresentationWarmPrioritizesNewestVisibleAuthorsWithinItsBudget() {
        val records =
            listOf(
                record("m1", sender = "oldest"),
                record("m2", sender = "bob", reactionSenders = listOf("offscreen-reaction")),
                record("m3", sender = "carol", replyPreview = replyPreview("dave")),
            )

        assertEquals(
            listOf("carol", "dave", "bob"),
            initialPresentationProfileSenders(records, maxProfiles = 3),
        )
    }

    @Test
    fun initialPresentationWarmExcludesBlankAndReactionOnlyAuthors() {
        val records =
            listOf(
                record("m1", sender = "", reactionSenders = listOf("old-reaction")),
                record(
                    "m2",
                    sender = "bob",
                    replyPreview = replyPreview("   "),
                    reactionSenders = listOf("new-reaction"),
                ),
            )

        assertEquals(
            listOf("bob"),
            initialPresentationProfileSenders(records, maxProfiles = 12),
        )
    }
}
