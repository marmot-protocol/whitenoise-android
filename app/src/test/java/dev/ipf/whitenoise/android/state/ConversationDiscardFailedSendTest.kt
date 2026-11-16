package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.DiscardFailedSendOutcomeFfi
import dev.ipf.marmotkit.DiscardFailedSendResultFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineRemoveReasonFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Discarding a failed own send goes through MDK's discard, never a published delete, through the real controller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationDiscardFailedSendTest {
    /** A failed own send is discardable and keeps both delete scopes, since its publish may have reached a relay. */
    @Test
    fun failedOwnSendIsDiscardableAndKeepsBothDeleteScopes() =
        withController(listOf(failedSend(), deliveredSend())) { controller ->
            val failed = controller.timeline.single { it.record.messageIdHex == FAILED }
            val delivered = controller.timeline.single { it.record.messageIdHex == DELIVERED }

            assertTrue(controller.isDurableFailedSend(failed))
            assertFalse(controller.isDurableFailedSend(delivered))
            val failedScope = controller.deleteCapabilityFor(failed.record)
            assertTrue(failedScope.canDeleteForMe)
            assertTrue(failedScope.canDeleteForEveryone)
            assertTrue(controller.deleteCapabilityFor(delivered.record).canDeleteForEveryone)
        }

    /** MDK's discard is called with the exact conversation identity, and the row leaves on MDK's cleared removal. */
    @Test
    fun discardedSendLeavesTheTimelineWithoutAPublishedDelete() =
        withController(listOf(failedSend())) { controller ->
            val calls = mutableListOf<Triple<String, String, String>>()

            val discarded =
                controller.discardDurableFailedSend(FAILED) { account, group, message ->
                    calls += Triple(account, group, message)
                    result(DiscardFailedSendOutcomeFfi.DISCARDED, listOf("AB12"))
                }
            controller.testApplyLiveTimelineChangesAndRegisterStreams(
                listOf(TimelineMessageChangeFfi.Remove(FAILED, TimelineRemoveReasonFfi.CLEARED)),
            )

            assertTrue(discarded)
            assertEquals(
                listOf(Triple(ConversationTimelineTestIds.ACCOUNT_REF, ConversationTimelineTestIds.GROUP_ID, FAILED)),
                calls,
            )
            assertTrue(controller.timeline.none { it.record.messageIdHex == FAILED })
        }

    /** A send MDK may still publish is left in place, so the user can try again once it settles. */
    @Test
    fun stillSendingKeepsTheRow() =
        withController(listOf(failedSend())) { controller ->
            val discarded =
                controller.discardDurableFailedSend(FAILED) { _, _, _ ->
                    result(DiscardFailedSendOutcomeFfi.STILL_SENDING)
                }

            assertFalse(discarded)
            assertTrue(controller.timeline.any { it.record.messageIdHex == FAILED })
        }

    /** A native failure reports no discard and leaves the row for another attempt. */
    @Test
    fun nativeFailureKeepsTheRow() =
        withController(listOf(failedSend())) { controller ->
            val discarded = controller.discardDurableFailedSend(FAILED) { _, _, _ -> error("runtime unavailable") }

            assertFalse(discarded)
            assertTrue(controller.timeline.any { it.record.messageIdHex == FAILED })
        }

    /** A second tap while the first discard is in flight makes no second native call. */
    @Test
    fun repeatedTapMakesOneNativeCall() =
        withController(listOf(failedSend())) { controller ->
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val discard: suspend (String, String, String) -> DiscardFailedSendResultFfi = { _, _, _ ->
                calls += 1
                release.await()
                result(DiscardFailedSendOutcomeFfi.DISCARDED)
            }

            // Undispatched, the first discard runs until it waits on MDK, so it is in flight for the second tap.
            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.discardDurableFailedSend(FAILED, discard = discard)
                }
            assertEquals(1, calls)
            val second = controller.discardDurableFailedSend(FAILED, discard = discard)
            release.complete(Unit)

            assertFalse(second)
            assertTrue(first.await())
            assertEquals(1, calls)
        }

    /** A row MDK no longer counts as a failed send is hidden on this device, as the user asked to remove it. */
    @Test
    fun aSendNoLongerFailedIsHiddenLocally() =
        withController(listOf(failedSend())) { controller ->
            val removed =
                controller.discardDurableFailedSend(FAILED) { _, _, _ ->
                    result(DiscardFailedSendOutcomeFfi.NOT_FAILED_SEND)
                }

            assertTrue(removed)
            assertTrue(controller.timeline.none { it.record.messageIdHex == FAILED })
        }

    /** Opens a controller on [records] and runs [block] once the initial timeline page has loaded. */
    private fun withController(
        records: List<TimelineMessageRecordFfi>,
        block: suspend kotlinx.coroutines.CoroutineScope.(ConversationController) -> Unit,
    ) = runBlocking {
        val scripted =
            ScriptedConversationLiveSubscriptions(
                timelineScripts =
                    listOf(
                        ScriptedConversationTimelineSubscription(
                            TimelinePageFfi(messages = records, hasMoreBefore = false, hasMoreAfter = false),
                        ),
                    ),
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
            awaitConversationCondition { controller.timeline.size == records.size }
            block(controller)
        } finally {
            controller.onCleared()
            awaitOpenedTimelineSubscriptionsClosed(scripted)
        }
    }

    /** An own send MDK recorded but whose local publish failed. */
    private fun failedSend(): TimelineMessageRecordFfi =
        ownSend(FAILED, timelineAt = 1uL).copy(sourceMessageIdHex = null, invalidationStatus = LOCAL_PUBLISH_FAILED)

    /** An own send the group received. */
    private fun deliveredSend(): TimelineMessageRecordFfi = ownSend(DELIVERED, timelineAt = 2uL)

    /** An own message by the conversation's account. */
    private fun ownSend(
        messageId: String,
        timelineAt: ULong,
    ): TimelineMessageRecordFfi =
        timelineRecord(messageId = messageId, timelineAt = timelineAt).copy(
            direction = "sent",
            sender = ConversationTimelineTestIds.ACCOUNT_ID,
        )

    /** MDK's discard answer with [hashes] of the discarded media. */
    private fun result(
        outcome: DiscardFailedSendOutcomeFfi,
        hashes: List<String> = emptyList(),
    ) = DiscardFailedSendResultFfi(outcome = outcome, mediaCiphertextSha256 = hashes, erasurePending = false)

    private companion object {
        const val FAILED = "failed-send"
        const val DELIVERED = "delivered-send"
    }
}
