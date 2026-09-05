package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Regression coverage for preserving MDK's authoritative timeline order (#1578). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationAuthoritativeTimelineOrderingTest {
    /** A later wall-clock membership event retains MDK's position above its app row. */
    @Test
    fun snapshotKeepsMembershipBeforeTheMessageItAuthorizes() =
        runBlocking {
            val system = membershipRecord(timelineAt = 200uL)
            val app = appRecord(timelineAt = 100uL)
            val subscription = ScriptedConversationTimelineSubscription(timelinePage(system, app))
            withController(subscription) { controller, _ ->
                awaitConversationCondition { controller.timeline.size == 2 }

                assertAuthoritativePair(controller)
            }
        }

    /** Equal timestamps still defer completely to the authoritative MDK ordinal. */
    @Test
    fun equalTimestampsStillKeepMembershipBeforeTheAppMessage() =
        runBlocking {
            val subscription =
                ScriptedConversationTimelineSubscription(
                    timelinePage(
                        membershipRecord(timelineAt = 100uL),
                        appRecord(timelineAt = 100uL),
                    ),
                )
            withController(subscription) { controller, _ ->
                awaitConversationCondition { controller.timeline.size == 2 }

                assertAuthoritativePair(controller)
            }
        }

    /** An old unresolved local send keeps its wall-time position instead of becoming the live head. */
    @Test
    fun oldUnconfirmedLocalSendDoesNotOccupyTheLiveHead() =
        runBlocking {
            val subscription =
                ScriptedConversationTimelineSubscription(
                    timelinePage(
                        membershipRecord(timelineAt = 200uL),
                        appRecord(timelineAt = 100uL),
                        unconfirmedLocalRecord(timelineAt = 50uL),
                    ),
                )
            withController(subscription) { controller, _ ->
                awaitConversationCondition { controller.timeline.size == 3 }

                assertEquals(
                    listOf(UNCONFIRMED_MESSAGE_ID, SYSTEM_MESSAGE_ID, APP_MESSAGE_ID),
                    timelineMessageIds(controller),
                )
            }
        }

    /** An unresolved row inside an inverted authoritative timestamp range stays historical. */
    @Test
    fun intermediateUnconfirmedLocalSendDoesNotOccupyTheLiveHead() =
        runBlocking {
            val subscription =
                ScriptedConversationTimelineSubscription(
                    timelinePage(
                        membershipRecord(timelineAt = 200uL),
                        appRecord(timelineAt = 100uL),
                        unconfirmedLocalRecord(timelineAt = 150uL),
                    ),
                )
            withController(subscription) { controller, _ ->
                awaitConversationCondition { controller.timeline.size == 3 }

                assertEquals(
                    listOf(UNCONFIRMED_MESSAGE_ID, SYSTEM_MESSAGE_ID, APP_MESSAGE_ID),
                    timelineMessageIds(controller),
                )
            }
        }

    /** A same-second terminal row cannot win the live-head tie by message id. */
    @Test
    fun sameTimestampUnconfirmedLocalSendDoesNotOccupyTheLiveHead() =
        runBlocking {
            val subscription =
                ScriptedConversationTimelineSubscription(
                    timelinePage(
                        appRecord(timelineAt = 100uL),
                        unconfirmedLocalRecord(timelineAt = 100uL),
                    ),
                )
            withController(subscription) { controller, _ ->
                awaitConversationCondition { controller.timeline.size == 2 }

                assertEquals(
                    listOf(UNCONFIRMED_MESSAGE_ID, APP_MESSAGE_ID),
                    timelineMessageIds(controller),
                )
            }
        }

    /** A fresh unresolved projection keeps MDK's optimistic-head position under clock skew. */
    @Test
    fun pendingLocalSendStaysAtTheLiveHeadDespiteAFutureAuthoritativeTimestamp() =
        runBlocking {
            val subscription =
                ScriptedConversationTimelineSubscription(
                    timelinePage(
                        appRecord(timelineAt = 300uL),
                        pendingLocalRecord(timelineAt = 100uL),
                    ),
                )
            withController(subscription) { controller, _ ->
                awaitConversationCondition { controller.timeline.size == 2 }

                assertEquals(
                    listOf(APP_MESSAGE_ID, PENDING_MESSAGE_ID),
                    timelineMessageIds(controller),
                )
                assertEquals(MessageStatus.Pending, controller.timeline.last().status)
            }
        }

    /** A newly confirmed message stays at the live head above an older unconfirmed row. */
    @Test
    fun liveConfirmationDoesNotDisappearAboveAnOldUnconfirmedRow() =
        runBlocking {
            val oldUnconfirmed = unconfirmedLocalRecord(timelineAt = 50uL)
            val subscription = ScriptedConversationTimelineSubscription(timelinePage(oldUnconfirmed))
            withController(subscription) { controller, _ ->
                awaitConversationCondition { subscription.nextWindowCallCount >= 1 }
                subscription.emitWindow(
                    // MDK's accepted-history class precedes its unresolved-local
                    // class even though this accepted message is newer.
                    timelinePage(appRecord(timelineAt = 300uL), oldUnconfirmed),
                )
                awaitConversationCondition { subscription.nextWindowCallCount >= 2 }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
                awaitConversationCondition(timeoutMs = 15_000) { controller.timeline.size == 2 }

                assertEquals(
                    listOf(UNCONFIRMED_MESSAGE_ID, APP_MESSAGE_ID),
                    timelineMessageIds(controller),
                )
            }
        }

    /** Earlier-timestamp stream rows remain chained below their MDK-ranked prompt. */
    @Test
    fun durableStreamChainCannotCrossPrecedingMembershipRow() =
        runBlocking {
            val subscription =
                ScriptedConversationTimelineSubscription(
                    timelinePage(
                        membershipRecord(timelineAt = 200uL),
                        appRecord(timelineAt = 100uL),
                        streamStartRecord(timelineAt = 98uL),
                        streamFinalRecord(timelineAt = 99uL),
                    ),
                )
            withController(subscription) { controller, _ ->
                awaitConversationCondition { controller.timeline.size == 4 }

                assertEquals(
                    listOf(SYSTEM_MESSAGE_ID, APP_MESSAGE_ID, STREAM_START_MESSAGE_ID, STREAM_FINAL_MESSAGE_ID),
                    timelineMessageIds(controller),
                )
            }
        }

    /** An app-first live delivery settles when the next full MDK window arrives. */
    @Test
    fun liveWindowSettlesAppFirstArrivalIntoAuthoritativeOrder() =
        runBlocking {
            val app = appRecord(timelineAt = 100uL)
            val subscription = ScriptedConversationTimelineSubscription(timelinePage(app))
            withController(subscription) { controller, _ ->
                awaitConversationCondition { subscription.nextWindowCallCount >= 1 }
                subscription.emitWindow(timelinePage(membershipRecord(timelineAt = 200uL), app))
                awaitConversationCondition { subscription.nextWindowCallCount >= 2 }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
                awaitConversationCondition(timeoutMs = 15_000) { controller.timeline.size == 2 }

                assertAuthoritativePair(controller)
            }
        }

    /** A system-first live delivery remains stable when the app row arrives. */
    @Test
    fun liveWindowKeepsAuthoritativeOrderAfterSystemFirstArrival() =
        runBlocking {
            val system = membershipRecord(timelineAt = 200uL)
            val subscription = ScriptedConversationTimelineSubscription(timelinePage(system))
            withController(subscription) { controller, _ ->
                awaitConversationCondition { subscription.nextWindowCallCount >= 1 }
                subscription.emitWindow(timelinePage(system, appRecord(timelineAt = 100uL)))
                awaitConversationCondition { subscription.nextWindowCallCount >= 2 }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
                awaitConversationCondition(timeoutMs = 15_000) { controller.timeline.size == 2 }

                assertAuthoritativePair(controller)
            }
        }

    /** Backward pagination renders the full order returned by the subscription. */
    @Test
    fun backwardPaginationKeepsTheReturnedWindowOrder() =
        runBlocking {
            val app = appRecord(timelineAt = 100uL)
            val subscription =
                ScriptedConversationTimelineSubscription(
                    snapshotPage = page(listOf(app), hasMoreBefore = true),
                    backwardsPage = page(listOf(membershipRecord(timelineAt = 200uL), app)),
                )
            withController(subscription) { controller, _ ->
                awaitConversationCondition { controller.hasMoreBefore }

                assertTrue(controller.loadOlderTimelinePage())
                assertAuthoritativePair(controller)
            }
        }

    /** Forward pagination renders the full order returned by the subscription. */
    @Test
    fun forwardPaginationKeepsTheReturnedWindowOrder() =
        runBlocking {
            val system = membershipRecord(timelineAt = 200uL)
            val subscription =
                ScriptedConversationTimelineSubscription(
                    snapshotPage = page(listOf(system), hasMoreAfter = true),
                    forwardsPage = page(listOf(system, appRecord(timelineAt = 100uL))),
                )
            withController(subscription) { controller, _ ->
                awaitConversationCondition { controller.hasMoreAfterTimeline }

                assertTrue(controller.loadNewerTimelinePage())
                assertAuthoritativePair(controller)
            }
        }

    /** Reconnecting replaces the retained window without losing authoritative order. */
    @Test
    fun replacementSubscriptionSnapshotKeepsAuthoritativeOrder() =
        runBlocking {
            val first = ScriptedConversationTimelineSubscription(timelinePage(appRecord(timelineAt = 100uL)))
            val replacement =
                ScriptedConversationTimelineSubscription(
                    timelinePage(
                        membershipRecord(timelineAt = 200uL),
                        appRecord(timelineAt = 100uL),
                    ),
                )
            withController(first, replacement) { controller, scripted ->
                awaitConversationCondition { first.nextWindowCallCount >= 1 }
                first.endWindows()
                awaitConversationCondition { first.closeCallCount == 1 }

                controller.retryLoadFailure()
                awaitConversationCondition {
                    scripted.timelineSubscriptionOpenCount == 2 && controller.timeline.size == 2
                }
                assertAuthoritativePair(controller)
            }
        }

    /** Builds the source-epoch membership event used as the ordering boundary. */
    private fun membershipRecord(timelineAt: ULong) =
        timelineRecord(
            messageId = SYSTEM_MESSAGE_ID,
            timelineAt = timelineAt,
            plaintext = "member added",
        ).copy(
            sourceMessageIdHex = null,
            direction = "system",
            kind = 1210uL,
            sourceEpoch = SOURCE_EPOCH,
            groupSystem =
                GroupSystemEventFfi(
                    systemType = "member_added",
                    text = "member added",
                    actorAccountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                    subjectAccountIdHex = ConversationTimelineTestIds.SENDER_ID,
                    name = null,
                    oldName = null,
                    oldRetentionSeconds = null,
                    newRetentionSeconds = null,
                ),
        )

    /** Builds the authorized application message and durable stream prompt. */
    private fun appRecord(timelineAt: ULong) =
        timelineRecord(
            messageId = APP_MESSAGE_ID,
            timelineAt = timelineAt,
        ).copy(sourceEpoch = SOURCE_EPOCH)

    /** Builds the persisted local-only row MDK places after accepted group history. */
    private fun unconfirmedLocalRecord(timelineAt: ULong) =
        timelineRecord(
            messageId = UNCONFIRMED_MESSAGE_ID,
            timelineAt = timelineAt,
            plaintext = "old unconfirmed send",
        ).copy(
            sourceMessageIdHex = null,
            direction = "sent",
            sender = ConversationTimelineTestIds.ACCOUNT_ID,
            invalidationStatus = "local_publish_failed",
        )

    /** Builds an accepted but not-yet-delivered local projection. */
    private fun pendingLocalRecord(timelineAt: ULong) =
        timelineRecord(
            messageId = PENDING_MESSAGE_ID,
            timelineAt = timelineAt,
            plaintext = "pending send",
        ).copy(
            sourceMessageIdHex = null,
            direction = "sent",
            sender = ConversationTimelineTestIds.ACCOUNT_ID,
        )

    /** Builds the durable stream start linked to the authoritative prompt. */
    private fun streamStartRecord(timelineAt: ULong) =
        timelineRecord(
            messageId = STREAM_START_MESSAGE_ID,
            timelineAt = timelineAt,
        ).copy(
            kind = 1200uL,
            tags =
                listOf(
                    MessageTagFfi(listOf("stream", STREAM_ID)),
                    MessageTagFfi(listOf("parent", APP_MESSAGE_ID)),
                ),
        )

    /** Builds the durable stream final linked through the stream-start row. */
    private fun streamFinalRecord(timelineAt: ULong) =
        timelineRecord(
            messageId = STREAM_FINAL_MESSAGE_ID,
            timelineAt = timelineAt,
        ).copy(
            tags =
                listOf(
                    MessageTagFfi(listOf("stream", STREAM_ID)),
                    MessageTagFfi(listOf("stream-start", STREAM_START_MESSAGE_ID)),
                ),
        )

    /** Builds an authoritative page with explicit pagination flags. */
    private fun page(
        messages: List<TimelineMessageRecordFfi>,
        hasMoreBefore: Boolean = false,
        hasMoreAfter: Boolean = false,
    ) = TimelinePageFfi(
        messages = messages,
        hasMoreBefore = hasMoreBefore,
        hasMoreAfter = hasMoreAfter,
    )

    /** Asserts the membership/application ordering central to issue #1578. */
    private fun assertAuthoritativePair(controller: ConversationController) {
        assertEquals(
            listOf(SYSTEM_MESSAGE_ID, APP_MESSAGE_ID),
            timelineMessageIds(controller),
        )
    }

    /** Owns a controller and closes every scripted subscription after the assertion. */
    private suspend fun withController(
        vararg subscriptions: ScriptedConversationTimelineSubscription,
        block: suspend (ConversationController, ScriptedConversationLiveSubscriptions) -> Unit,
    ) {
        val scripted =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = subscriptions.toList(),
                group = conversationTimelineTestGroup(),
            )
        val controller = conversationController(scripted.subscriptions)
        try {
            block(controller, scripted)
        } finally {
            controller.onCleared()
            awaitOpenedTimelineSubscriptionsClosed(scripted)
        }
    }

    /** Creates the production controller around deterministic subscription seams. */
    private fun conversationController(subscriptions: ConversationLiveSubscriptions) =
        ConversationController(
            appState = conversationTimelineTestAppState(subscriptions),
            initialGroup = conversationTimelineTestGroup(),
            initialMemberSnapshot = conversationTimelineMemberSnapshot(),
            groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
            startOnConstruction = true,
        )

    private companion object {
        const val SOURCE_EPOCH = 7uL
        const val STREAM_ID = "reply"
        val SYSTEM_MESSAGE_ID = "ff".repeat(32)
        val APP_MESSAGE_ID = "00".repeat(32)
        val UNCONFIRMED_MESSAGE_ID = "33".repeat(32)
        val PENDING_MESSAGE_ID = "44".repeat(32)
        val STREAM_START_MESSAGE_ID = "11".repeat(32)
        val STREAM_FINAL_MESSAGE_ID = "22".repeat(32)
    }
}
