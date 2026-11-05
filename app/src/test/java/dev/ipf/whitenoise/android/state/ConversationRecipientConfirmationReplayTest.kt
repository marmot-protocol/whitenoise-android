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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Keeps native peer-confirmed delivery authoritative across controller recreation and history paging. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationRecipientConfirmationReplayTest {
    /** A new controller must reconstruct receipt-backed delivery without a process-local optimistic bridge. */
    @Test
    fun aReopenedSnapshotRetainsConfirmedDeliveryWithoutASourceEvent() =
        runBlocking {
            val failed = failedRecord()
            val confirmed = receipt(failed).copy(recipientConfirmed = true, invalidationStatus = null)
            withSnapshot(page(failed)) { controller ->
                assertEquals(
                    "Delivery not confirmed",
                    TimelineProjector.invalidationWarning(controller.timelineRecords.getValue(ID)),
                )
                controller.testApplyLiveTimelineChangesAndRegisterStreams(listOf(upsert(confirmed)))
                assertSettled(controller)
            }
            withSnapshot(page(confirmed)) { controller -> assertSettled(controller) }
        }

    /** Backward paging obtains the same settled native state as an initial snapshot or live upsert. */
    @Test
    fun anOlderPageRestoresTheConfirmedMessageAndItsReaction() =
        runBlocking {
            val confirmed = receipt(failedRecord()).copy(recipientConfirmed = true, invalidationStatus = null)
            val tail = timelineRecord(messageId = "newer-message", timelineAt = 2uL)
            val subscription =
                ScriptedConversationTimelineSubscription(
                    snapshotPage = page(tail).copy(hasMoreBefore = true),
                    backwardsPage = page(confirmed, tail),
                )
            withSubscription(subscription) { controller ->
                awaitConversationCondition { controller.hasMoreBefore }
                assertTrue(controller.loadOlderTimelinePage())
                assertSettled(controller)
                assertEquals(setOf(ID, tail.messageIdHex), controller.timeline.map { it.record.messageIdHex }.toSet())
            }
        }

    /** A withdrawal restores uncertainty; Android cannot retain a receipt the authoritative row no longer carries. */
    @Test
    fun withdrawingNativeConfirmationRestoresTheUnconfirmedWarning() =
        runBlocking {
            val failed = failedRecord()
            val confirmed = receipt(failed).copy(recipientConfirmed = true, invalidationStatus = null)
            withSnapshot(page(confirmed)) { controller ->
                assertSettled(controller)
                controller.testApplyLiveTimelineChangesAndRegisterStreams(listOf(upsert(failed)))
                val item = controller.timeline.single()
                assertEquals(MessageStatus.Pending, item.status)
                assertTrue(controller.awaitingDelivery(item))
                assertEquals(
                    "Delivery not confirmed",
                    TimelineProjector.invalidationWarning(requireNotNull(item.projected)),
                )
                assertTrue(controller.reactions[ID].isNullOrEmpty())
            }
        }

    /** Self, unrelated and malformed reaction metadata never substitute for MDK's explicit delivery decision. */
    @Test
    fun reactionMetadataWithoutNativeConfirmationDoesNotSettleTheSend() =
        runBlocking {
            val failed = failedRecord()
            val reactions =
                listOf(
                    receipt(failed, sender = failed.sender),
                    receipt(failed, target = "another-message"),
                    receipt(failed, target = ""),
                )
            withSnapshot(page(failed)) { controller ->
                reactions.forEach { record ->
                    controller.testApplyLiveTimelineChangesAndRegisterStreams(listOf(upsert(record)))
                    val item = controller.timeline.single()
                    assertEquals(MessageStatus.Pending, item.status)
                    assertTrue(controller.awaitingDelivery(item))
                    assertEquals(
                        "Delivery not confirmed",
                        TimelineProjector.invalidationWarning(requireNotNull(item.projected)),
                    )
                }
            }
        }

    /** Native receipt retention may outlive the reaction artwork without regressing delivery. */
    @Test
    fun retainedNativeConfirmationDoesNotRequireVisibleReactions() =
        runBlocking {
            val retained = failedRecord().copy(recipientConfirmed = true, invalidationStatus = null)
            withSnapshot(page(retained)) { controller ->
                val item = controller.timeline.single()
                assertEquals(MessageStatus.Sent, item.status)
                assertFalse(controller.awaitingDelivery(item))
                assertNull(TimelineProjector.invalidationWarning(requireNotNull(item.projected)))
                assertTrue(controller.reactions[ID].isNullOrEmpty())
            }
        }

    /** Exercises production subscription bootstrap and always retires the exact handle opened by this controller. */
    private suspend fun withSubscription(
        subscription: ScriptedConversationTimelineSubscription,
        check: suspend (ConversationController) -> Unit,
    ) {
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
            awaitConversationCondition { controller.timeline.isNotEmpty() }
            check(controller)
        } finally {
            controller.onCleared()
            awaitOpenedTimelineSubscriptionsClosed(scripted)
        }
    }

    /** Reopening uses a fresh controller, app state and subscription rather than retaining the old timeline. */
    private suspend fun withSnapshot(
        snapshot: TimelinePageFfi,
        check: suspend (ConversationController) -> Unit,
    ) = withSubscription(ScriptedConversationTimelineSubscription(snapshotPage = snapshot), check)

    /** The same native row must be settled, render its reaction and have no relay source fabricated by Android. */
    private fun assertSettled(controller: ConversationController) {
        val item = controller.timeline.single { it.record.messageIdHex == ID }
        assertEquals(MessageStatus.Sent, item.status)
        assertFalse(controller.awaitingDelivery(item))
        assertNull(item.projected?.sourceMessageIdHex)
        assertNull(TimelineProjector.invalidationWarning(requireNotNull(item.projected)))
        assertEquals("+", controller.reactions[ID]?.single()?.emoji)
    }

    /** A persisted historical failure has no process-local send identity or published relay event. */
    private fun failedRecord() =
        timelineRecord(messageId = ID, timelineAt = 1uL).copy(
            sourceMessageIdHex = null,
            direction = "sent",
            sender = ConversationTimelineTestIds.ACCOUNT_ID,
            invalidationStatus = "local_publish_failed",
        )

    /** Raw reaction metadata deliberately leaves the native confirmation decision unchanged. */
    private fun receipt(
        record: TimelineMessageRecordFfi,
        sender: String = ConversationTimelineTestIds.SENDER_ID,
        target: String = record.messageIdHex,
    ) =
        record.copy(
            reactions =
                TimelineReactionSummaryFfi(
                    byEmoji = listOf(TimelineReactionEmojiFfi("+", 1u, listOf(sender))),
                    userReactions =
                        listOf(
                            TimelineUserReactionFfi(
                                reactionMessageIdHex = "reaction",
                                targetMessageIdHex = target,
                                sender = sender,
                                emoji = "+",
                                reactedAt = 2uL,
                            ),
                        ),
                ),
        )

    /** Uses the native delivery trigger, which can also introduce or withdraw a reaction. */
    private fun upsert(record: TimelineMessageRecordFfi) =
        TimelineMessageChangeFfi.Upsert(TimelineUpdateTriggerFfi.DELIVERY_OR_SEND_STATE_CHANGED, record)

    /** An authoritative bounded page with no implicit Android reconstruction of delivery fields. */
    private fun page(vararg records: TimelineMessageRecordFfi) = TimelinePageFfi(records.toList(), false, false)

    private companion object {
        const val ID = "historical-send"
    }
}
