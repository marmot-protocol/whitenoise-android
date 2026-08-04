package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineMarkdownHydrationTest {
    @Test
    fun projectedPlainMessageWithoutTokensRequestsAndroidHydration() {
        val empty = timelineRecord("confirmed", "See https://example.com")

        assertTrue(needsTimelineMarkdownHydration(empty))

        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(MarkdownInlineFfi.Text("See https://example.com")),
                        ),
                    ),
                blankLinesBefore = byteArrayOf(0),
            )
        val hydrated = empty.withMarkdownTokens(document)

        assertFalse(needsTimelineMarkdownHydration(hydrated))
        assertEquals(document, hydrated.contentTokens)
        assertEquals(empty.messageIdHex, hydrated.messageIdHex)
        assertEquals(empty.plaintext, hydrated.plaintext)
    }

    @Test
    fun derivedOrInvisibleTimelineRowsDoNotRequestMarkdownHydration() {
        assertFalse(needsTimelineMarkdownHydration(timelineRecord("blank", "")))
        assertFalse(needsTimelineMarkdownHydration(timelineRecord("reaction", "👍").copy(kind = 7uL)))
        val deleted = timelineRecord("deleted", "https://example.com").copy(deleted = true)
        assertFalse(needsTimelineMarkdownHydration(deleted))
    }

    private fun timelineRecord(
        messageIdHex: String,
        plaintext: String,
    ): TimelineMessageRecordFfi =
        TimelineMessageRecordFfi(
            messageIdHex = messageIdHex,
            sourceMessageIdHex = null,
            direction = "sent",
            groupIdHex = "group",
            sender = "alice",
            plaintext = plaintext,
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = 9uL,
            tags = emptyList(),
            timelineAt = 1uL,
            receivedAt = 1uL,
            replyToMessageIdHex = null,
            replyPreview = null,
            mediaJson = null,
            media = emptyList(),
            agentTextStreamJson = null,
            groupSystem = null,
            reactions = TimelineReactionSummaryFfi(emptyList(), emptyList()),
            deleted = false,
            deletedByMessageIdHex = null,
            invalidationStatus = null,
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
        )
}
