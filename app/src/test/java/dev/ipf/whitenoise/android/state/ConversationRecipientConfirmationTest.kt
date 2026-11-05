package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionEmojiFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.marmotkit.TimelineUserReactionFfi
import dev.ipf.whitenoise.android.core.TimelineProjector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the native peer-receipt contract through the production conversation controller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationRecipientConfirmationTest {
    /** A delivery upsert can settle a local send and add its first reaction in the same change. */
    @Test
    fun peerConfirmationSettlesDeliveryAndRefreshesReactions() =
        runBlocking {
            val pending =
                timelineRecord(messageId = "local-send", timelineAt = 1uL).copy(
                    sourceMessageIdHex = null,
                    direction = "sent",
                    sender = ConversationTimelineTestIds.ACCOUNT_ID,
                )
            val subscription = ScriptedConversationTimelineSubscription(page(pending))
            val scripted =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = listOf(subscription),
                    group = conversationTimelineTestGroup(),
                )
            val controller =
                ConversationController(
                    appState = conversationTimelineTestAppState(scripted.subscriptions),
                    initialGroup = conversationTimelineTestGroup(),
                    initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                    groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                    startOnConstruction = true,
                )
            try {
                awaitConversationCondition { controller.timeline.size == 1 }
                assertEquals(MessageStatus.Pending, controller.timeline.single().status)
                assertTrue(controller.awaitingDelivery(controller.timeline.single()))

                val confirmed = confirmedRecord(pending)
                controller.testApplyLiveTimelineChangesAndRegisterStreams(
                    listOf(TimelineMessageChangeFfi.Upsert(TimelineUpdateTriggerFfi.DELIVERY_OR_SEND_STATE_CHANGED, confirmed)),
                )

                assertEquals(MessageStatus.Sent, controller.timeline.single().status)
                assertFalse(controller.awaitingDelivery(controller.timeline.single()))
                assertEquals("+", controller.reactions[pending.messageIdHex]?.single()?.emoji)
                assertEquals(1, controller.reactions[pending.messageIdHex]?.single()?.count)
            } finally {
                controller.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(scripted)
            }
        }

    /** A peer receipt replaces the retained failed bubble even when its legacy native row has no caller token. */
    @Test
    fun peerConfirmationReconcilesFailedLegacyOptimisticSend() =
        runBlocking {
            val failed =
                timelineRecord(messageId = "native-send", timelineAt = 1uL).copy(
                    sourceMessageIdHex = null,
                    direction = "sent",
                    sender = ConversationTimelineTestIds.ACCOUNT_ID,
                    invalidationStatus = "local_publish_failed",
                )
            val scripted =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = emptyList(),
                    group = conversationTimelineTestGroup(),
                )
            val state = conversationTimelineTestAppState(scripted.subscriptions)
            val optimistic =
                state.optimisticMessages(ConversationTimelineTestIds.ACCOUNT_REF, ConversationTimelineTestIds.GROUP_ID)
            optimistic["msg:temp-send"] =
                TimelineMessage(
                    id = "msg:temp-send",
                    record = TimelineProjector.toAppMessageRecord(failed).copy(messageIdHex = "temp-send"),
                    status = MessageStatus.Failed,
                )
            val controller =
                ConversationController(
                    appState = state,
                    initialGroup = conversationTimelineTestGroup(),
                    initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                )
            try {
                controller.testApplyLiveTimelineChangesAndRegisterStreams(
                    listOf(TimelineMessageChangeFfi.Upsert(TimelineUpdateTriggerFfi.DELIVERY_OR_SEND_STATE_CHANGED, failed)),
                )
                assertEquals(MessageStatus.Failed, controller.timeline.single().status)

                controller.testApplyLiveTimelineChangesAndRegisterStreams(
                    listOf(
                        TimelineMessageChangeFfi.Upsert(
                            TimelineUpdateTriggerFfi.DELIVERY_OR_SEND_STATE_CHANGED,
                            confirmedRecord(failed).copy(invalidationStatus = null),
                        ),
                    ),
                )

                assertTrue(optimistic.isEmpty())
                assertEquals("native-send", controller.timeline.single().record.messageIdHex)
                assertEquals(MessageStatus.Sent, controller.timeline.single().status)
                assertEquals("+", controller.reactions[failed.messageIdHex]?.single()?.emoji)
            } finally {
                controller.onCleared()
            }
        }

    /** An old peer receipt cannot acknowledge a distinct new submission with identical text and timestamp. */
    @Test
    fun legacyPeerConfirmationDoesNotConsumeAnUnrelatedPendingSend() =
        runBlocking {
            val historical =
                confirmedRecord(
                    timelineRecord(messageId = "old-send", timelineAt = 1uL).copy(
                        sourceMessageIdHex = null,
                        direction = "sent",
                        sender = ConversationTimelineTestIds.ACCOUNT_ID,
                    ),
                )
            val scripted =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = emptyList(),
                    group = conversationTimelineTestGroup(),
                )
            val state = conversationTimelineTestAppState(scripted.subscriptions)
            val optimistic =
                state.optimisticMessages(ConversationTimelineTestIds.ACCOUNT_REF, ConversationTimelineTestIds.GROUP_ID)
            optimistic["msg:new-send"] =
                TimelineMessage(
                    id = "msg:new-send",
                    record = TimelineProjector.toAppMessageRecord(historical).copy(messageIdHex = "new-send"),
                    status = MessageStatus.Pending,
                )
            val controller =
                ConversationController(
                    appState = state,
                    initialGroup = conversationTimelineTestGroup(),
                    initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                )
            try {
                controller.testApplyLiveTimelineChangesAndRegisterStreams(
                    listOf(TimelineMessageChangeFfi.Upsert(TimelineUpdateTriggerFfi.DELIVERY_OR_SEND_STATE_CHANGED, historical)),
                )

                assertEquals(MessageStatus.Pending, optimistic["msg:new-send"]?.status)
                assertEquals(2, controller.timeline.size)
                assertEquals(setOf(MessageStatus.Pending, MessageStatus.Sent), controller.timeline.map { it.status }.toSet())
            } finally {
                controller.onCleared()
            }
        }

    /** Models the single native update that both proves receipt and introduces the peer reaction. */
    private fun confirmedRecord(pending: TimelineMessageRecordFfi) =
        pending.copy(
            recipientConfirmed = true,
            reactions =
                TimelineReactionSummaryFfi(
                    byEmoji = listOf(TimelineReactionEmojiFfi("+", 1u, listOf(ConversationTimelineTestIds.SENDER_ID))),
                    userReactions =
                        listOf(
                            TimelineUserReactionFfi(
                                reactionMessageIdHex = "reaction",
                                targetMessageIdHex = pending.messageIdHex,
                                sender = ConversationTimelineTestIds.SENDER_ID,
                                emoji = "+",
                                reactedAt = 2uL,
                            ),
                        ),
                ),
        )

    /** Builds a complete native window containing only the send under test. */
    private fun page(record: TimelineMessageRecordFfi) =
        TimelinePageFfi(messages = listOf(record), hasMoreBefore = false, hasMoreAfter = false)
}
