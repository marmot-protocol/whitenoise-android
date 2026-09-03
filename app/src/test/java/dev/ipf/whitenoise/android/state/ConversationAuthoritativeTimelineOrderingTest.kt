package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.marmotkit.GroupSystemEventFfi
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

    private fun appRecord(timelineAt: ULong) =
        timelineRecord(
            messageId = APP_MESSAGE_ID,
            timelineAt = timelineAt,
        ).copy(sourceEpoch = SOURCE_EPOCH)

    private fun page(
        messages: List<TimelineMessageRecordFfi>,
        hasMoreBefore: Boolean = false,
        hasMoreAfter: Boolean = false,
    ) = TimelinePageFfi(
        messages = messages,
        hasMoreBefore = hasMoreBefore,
        hasMoreAfter = hasMoreAfter,
    )

    private fun assertAuthoritativePair(controller: ConversationController) {
        assertEquals(
            listOf(SYSTEM_MESSAGE_ID, APP_MESSAGE_ID),
            timelineMessageIds(controller),
        )
    }

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
        val SYSTEM_MESSAGE_ID = "ff".repeat(32)
        val APP_MESSAGE_ID = "00".repeat(32)
    }
}
