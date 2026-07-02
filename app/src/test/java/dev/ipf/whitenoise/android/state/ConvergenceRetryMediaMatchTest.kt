package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers [committedButUnpublishedProjectionForOptimistic]'s media arm and the
 * near-timestamp tolerance shared with the text arm. A failed `_media_pending`
 * optimistic can't shape-match on body/tags (the committed projection carries
 * the final `imeta` tag instead), so it pairs by near timestamp — and that
 * tolerance must stay tight (±1s) or a retry could latch onto an unrelated
 * committed send and skip publishing the user's message.
 */
class ConvergenceRetryMediaMatchTest {
    @Test
    fun mediaPendingMatchesImetaProjectionByNearTimestampIgnoringBody() {
        val failedOptimistic = mediaPendingOptimistic(recordedAt = 10uL)
        val projected = imetaProjection("engine-id", timelineAt = 11uL)

        val match =
            committedButUnpublishedProjectionForOptimistic(
                mapOf(projected.messageIdHex to projected),
                failedOptimistic,
                "alice",
            )

        assertEquals(projected, match)
    }

    @Test
    fun mediaPendingTwoSecondSkewIsNotMatched() {
        val failedOptimistic = mediaPendingOptimistic(recordedAt = 10uL)
        val projected = imetaProjection("engine-id", timelineAt = 12uL)

        assertNull(
            committedButUnpublishedProjectionForOptimistic(
                mapOf(projected.messageIdHex to projected),
                failedOptimistic,
                "alice",
            ),
        )
    }

    @Test
    fun textSendToleratesExactlyOneSecondOfSkew() {
        val failedOptimistic = textOptimistic(plaintext = "retry me", recordedAt = 10uL)
        val projected = textProjection("engine-id", plaintext = "retry me", timelineAt = 11uL)

        val match =
            committedButUnpublishedProjectionForOptimistic(
                mapOf(projected.messageIdHex to projected),
                failedOptimistic,
                "alice",
            )

        assertEquals(projected, match)
    }

    @Test
    fun mediaPendingDoesNotMatchATextProjectionWithDifferentBody() {
        // Without an `imeta` tag on the projection the media fast-path doesn't
        // apply, and the text arm then requires the exact body/tags — a nearby
        // unrelated text send must not be captured by a failed media retry.
        val failedOptimistic = mediaPendingOptimistic(recordedAt = 10uL)
        val projected = textProjection("engine-id", plaintext = "unrelated text", timelineAt = 10uL)

        assertNull(
            committedButUnpublishedProjectionForOptimistic(
                mapOf(projected.messageIdHex to projected),
                failedOptimistic,
                "alice",
            ),
        )
    }

    @Test
    fun projectionFromAnotherGroupIsNeverMatched() {
        val failedOptimistic = mediaPendingOptimistic(recordedAt = 10uL)
        val projected = imetaProjection("engine-id", timelineAt = 10uL, groupIdHex = "other-group")

        assertNull(
            committedButUnpublishedProjectionForOptimistic(
                mapOf(projected.messageIdHex to projected),
                failedOptimistic,
                "alice",
            ),
        )
    }

    // ---- helpers ------------------------------------------------------------

    private fun mediaPendingOptimistic(recordedAt: ULong): AppMessageRecordFfi =
        record(
            messageIdHex = "uuid-temp",
            plaintext = "📎 a.pdf",
            tags = listOf(MessageTagFfi(listOf("_media_pending", "a.pdf", "application/pdf"))),
            recordedAt = recordedAt,
        )

    private fun textOptimistic(
        plaintext: String,
        recordedAt: ULong,
    ): AppMessageRecordFfi =
        record(
            messageIdHex = "uuid-temp",
            plaintext = plaintext,
            tags = emptyList(),
            recordedAt = recordedAt,
        )

    private fun record(
        messageIdHex: String,
        plaintext: String,
        tags: List<MessageTagFfi>,
        recordedAt: ULong,
    ): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = messageIdHex,
            direction = "sent",
            groupIdHex = "group",
            sender = "alice",
            plaintext = plaintext,
            contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
            kind = 9uL,
            tags = tags,
            recordedAt = recordedAt,
            receivedAt = recordedAt,
        )

    private fun imetaProjection(
        messageIdHex: String,
        timelineAt: ULong,
        groupIdHex: String = "group",
    ): TimelineMessageRecordFfi =
        projection(
            messageIdHex = messageIdHex,
            plaintext = "",
            tags = listOf(MessageTagFfi(listOf("imeta", "url https://example/a.pdf", "m application/pdf"))),
            timelineAt = timelineAt,
            groupIdHex = groupIdHex,
        )

    private fun textProjection(
        messageIdHex: String,
        plaintext: String,
        timelineAt: ULong,
    ): TimelineMessageRecordFfi =
        projection(
            messageIdHex = messageIdHex,
            plaintext = plaintext,
            tags = emptyList(),
            timelineAt = timelineAt,
            groupIdHex = "group",
        )

    private fun projection(
        messageIdHex: String,
        plaintext: String,
        tags: List<MessageTagFfi>,
        timelineAt: ULong,
        groupIdHex: String,
    ): TimelineMessageRecordFfi =
        TimelineMessageRecordFfi(
            messageIdHex = messageIdHex,
            sourceMessageIdHex = null,
            direction = "sent",
            groupIdHex = groupIdHex,
            sender = "alice",
            plaintext = plaintext,
            contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
            kind = 9uL,
            tags = tags,
            timelineAt = timelineAt,
            receivedAt = timelineAt,
            replyToMessageIdHex = null,
            replyPreview = null,
            mediaJson = null,
            media = emptyList(),
            agentTextStreamJson = null,
            groupSystem = null,
            reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
            deleted = false,
            deletedByMessageIdHex = null,
            invalidationStatus = null,
        )
}
