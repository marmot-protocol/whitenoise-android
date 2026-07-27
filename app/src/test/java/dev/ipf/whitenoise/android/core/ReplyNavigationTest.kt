package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineReplyPreviewFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun scrollTargetMapsReactionSourceToParent() {
        val reaction =
            message(
                kind = 7uL,
                tags = listOf(MessageProjector.eventTag("parent-message")),
            )
        assertEquals(
            "parent-message",
            ReplyNavigation.scrollTargetMessageId("reaction-event", reaction),
        )
    }

    @Test
    fun scrollTargetKeepsNonReactionSourceId() {
        val chat = message(kind = 9uL, tags = emptyList())
        assertEquals("reply", ReplyNavigation.scrollTargetMessageId("reply", chat))
    }

    @Test
    fun reactionNavigationUsesHigherOlderPageBudget() {
        val reaction =
            message(
                kind = 7uL,
                tags = listOf(MessageProjector.eventTag("parent-message")),
            )
        assertEquals(ReplyNavigation.MaxOlderPagesForReaction, ReplyNavigation.maxOlderPagesForRecord(reaction))
        assertEquals(ReplyNavigation.MaxOlderPages, ReplyNavigation.maxOlderPagesForRecord(message(tags = emptyList(), kind = 9uL)))
    }

    @Test
    fun loadScrollNavigationTargetRetargetsReactionFoundAfterSourcePagination() =
        runBlocking {
            var sourceLoaded = false
            val loads = mutableListOf<Pair<String, Int>>()

            val target =
                ReplyNavigation.loadScrollNavigationTarget(
                    sourceMessageIdHex = "reaction-event",
                    lookupSourceRecord = {
                        if (sourceLoaded) {
                            message(
                                kind = 7uL,
                                tags = listOf(MessageProjector.eventTag("parent-message")),
                            )
                        } else {
                            null
                        }
                    },
                    loadUntilMessageAvailable = { messageIdHex, maxOlderPages ->
                        loads += messageIdHex to maxOlderPages
                        when (messageIdHex) {
                            "reaction-event" -> {
                                sourceLoaded = true
                                true
                            }
                            "parent-message" -> true
                            else -> false
                        }
                    },
                )

            assertEquals("parent-message", target)
            assertEquals(
                listOf(
                    "reaction-event" to ReplyNavigation.MaxOlderPages,
                    "parent-message" to ReplyNavigation.MaxOlderPagesForReaction,
                ),
                loads,
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

    @Test
    fun centeredScrollOffsetCentersTopWhenItemHeightIsUnknown() {
        assertEquals(-500, ReplyNavigation.centeredScrollOffset(viewportHeightPx = 1_000))
    }

    @Test
    fun centeredScrollOffsetCentersMeasuredItem() {
        assertEquals(-350, ReplyNavigation.centeredScrollOffset(viewportHeightPx = 1_000, itemHeightPx = 300))
    }

    @Test
    fun centeredScrollOffsetDoesNotPushOversizedItemPastViewportStart() {
        assertEquals(0, ReplyNavigation.centeredScrollOffset(viewportHeightPx = 300, itemHeightPx = 500))
        assertEquals(0, ReplyNavigation.centeredScrollOffset(viewportHeightPx = 0, itemHeightPx = 100))
    }

    @Test
    fun itemHeightForScrollPrefersVisibleTargetMeasurement() {
        assertEquals(
            480,
            ReplyNavigation.itemHeightForScrollPx(
                targetMessageId = "target",
                measuredItemHeightsByMessageId = mapOf("target" to 320),
                visibleTargetHeightPx = 480,
                visibleTimelineItemHeightsPx = listOf(200, 300, 400),
            ),
        )
    }

    @Test
    fun itemHeightForScrollUsesCachedTargetMeasurementBeforeMedianFallback() {
        assertEquals(
            900,
            ReplyNavigation.itemHeightForScrollPx(
                targetMessageId = "target",
                measuredItemHeightsByMessageId = mapOf("target" to 900),
                visibleTargetHeightPx = null,
                visibleTimelineItemHeightsPx = listOf(200, 300, 400),
            ),
        )
    }

    @Test
    fun itemHeightForScrollFallsBackToVisibleTimelineMedianForNeverMeasuredRows() {
        assertEquals(
            300,
            ReplyNavigation.itemHeightForScrollPx(
                targetMessageId = "target",
                measuredItemHeightsByMessageId = emptyMap(),
                visibleTargetHeightPx = null,
                visibleTimelineItemHeightsPx = listOf(200, 300, 400),
            ),
        )
    }

    @Test
    fun estimateItemHeightReturnsMedianOfVisibleRows() {
        assertEquals(300, ReplyNavigation.estimateItemHeightPx(listOf(200, 300, 400)))
    }

    @Test
    fun estimateItemHeightAveragesMiddlePairForEvenCounts() {
        assertEquals(250, ReplyNavigation.estimateItemHeightPx(listOf(400, 200, 300, 200)))
    }

    @Test
    fun estimateItemHeightIgnoresNonPositiveRowsAndOutliers() {
        // A tall media bubble (5_000) sits at the edge, so the median still
        // reflects the typical text-row height; zero/negative sizes are dropped.
        // Measurable rows after filtering: [300, 310, 320, 5_000] -> median 315.
        assertEquals(315, ReplyNavigation.estimateItemHeightPx(listOf(0, -10, 300, 310, 320, 5_000)))
    }

    @Test
    fun estimateItemHeightIsNullWhenNoRowsAreMeasurable() {
        assertNull(ReplyNavigation.estimateItemHeightPx(emptyList()))
        assertNull(ReplyNavigation.estimateItemHeightPx(listOf(0, -5)))
    }

    @Test
    fun estimatedHeightFeedsCenteredOffsetForSinglePassScroll() {
        // The #999 path: one estimate -> one centeredScrollOffset call.
        val estimate = ReplyNavigation.estimateItemHeightPx(listOf(280, 300, 320))
        assertEquals(-350, ReplyNavigation.centeredScrollOffset(viewportHeightPx = 1_000, itemHeightPx = estimate))
    }

    private fun message(
        tags: List<MessageTagFfi> = emptyList(),
        kind: ULong = 9uL,
    ) = AppMessageRecordFfi(
        messageIdHex = "reply",
        direction = "received",
        groupIdHex = "group",
        sender = "alice",
        plaintext = "reply",
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
        kind = kind,
        tags = tags,
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
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
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
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
                    contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                    kind = 9uL,
                    mediaJson = null,
                    media = emptyList(),
                    agentTextStreamJson = null,
                    deleted = false,
                    invalidationStatus = null,
                )
            },
        mediaJson = null,
        media = emptyList(),
        agentTextStreamJson = null,
        groupSystem = null,
        reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        deleted = false,
        deletedByMessageIdHex = null,
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
    )
}
