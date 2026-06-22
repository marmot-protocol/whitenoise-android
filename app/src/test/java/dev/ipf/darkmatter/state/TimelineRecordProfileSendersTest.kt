package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineReplyPreviewFfi
import dev.ipf.marmotkit.TimelineUserReactionFfi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the #609 fix: the set of profile presentations the
 * conversation pre-warms when a timeline page lands must include every author
 * the rows render — message authors, the authors of quoted reply previews, and
 * reaction authors — deduped and in first-seen order. Missing any of these
 * leaves that author's row flickering its name/avatar in on first paint.
 */
class TimelineRecordProfileSendersTest {
    private fun emptyDoc() = MarkdownDocumentFfi(blocks = emptyList())

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
            agentTextStreamJson = null,
            deleted = false,
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
    )

    @Test
    fun collectsMessageReplyAndReactionAuthorsDistinctInOrder() {
        val records =
            listOf(
                record("m1", sender = "alice"),
                record(
                    "m2",
                    sender = "bob",
                    replyPreview = replyPreview("carol"),
                    reactionSenders = listOf("dave", "alice"),
                ),
            )

        // alice (msg), bob (msg), carol (reply author), dave (reaction);
        // alice's reaction is a dup and must not appear twice.
        assertEquals(listOf("alice", "bob", "carol", "dave"), timelineRecordProfileSenders(records))
    }

    @Test
    fun dropsBlankSenders() {
        val records =
            listOf(
                record("m1", sender = ""),
                record(
                    "m2",
                    sender = "bob",
                    replyPreview = replyPreview("   "),
                    reactionSenders = listOf("", "eve"),
                ),
            )

        // Blank message sender, blank reply author, and blank reaction author
        // are all skipped so we never warm (or render initials for) an empty id.
        assertEquals(listOf("bob", "eve"), timelineRecordProfileSenders(records))
    }

    @Test
    fun emptyPageYieldsNoSenders() {
        assertEquals(emptyList<String>(), timelineRecordProfileSenders(emptyList()))
    }
}
