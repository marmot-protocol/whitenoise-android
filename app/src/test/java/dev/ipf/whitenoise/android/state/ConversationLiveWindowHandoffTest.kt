package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.marmotkit.GroupRosterFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Locally projected replacements cannot wait behind independent roster enrichment. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationLiveWindowHandoffTest {
    /** A stopped/retained controller consumes a live window even while its roster read is held. */
    @Test
    fun liveWindowPublishesWhileTheOpeningRosterIsStillPending() =
        runBlocking {
            val rosterStarted = CompletableDeferred<Unit>()
            val rosterReply = CompletableDeferred<GroupRosterFfi>()
            val messageA = timelineRecord(ConversationTimelineTestIds.MESSAGE_A, 1uL)
            val messageB = timelineRecord(ConversationTimelineTestIds.MESSAGE_B, 2uL)
            val timeline = ScriptedConversationTimelineSubscription(timelinePage(messageA))
            val scripts =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = listOf(timeline),
                    group = conversationTimelineTestGroup(),
                )
            val controller =
                ConversationController(
                    appState = conversationTimelineTestAppState(scripts.subscriptions),
                    initialGroup = conversationTimelineTestGroup(),
                    initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                    groupRosterReader = { _, _ ->
                        rosterStarted.complete(Unit)
                        rosterReply.await()
                    },
                    startOnConstruction = true,
                )
            try {
                awaitConversationCondition {
                    rosterStarted.isCompleted &&
                        ConversationTimelineTestIds.MESSAGE_A in timelineMessageIds(controller)
                }
                timeline.emitWindow(timelinePage(messageA, messageB))

                awaitAdvancingTimelineClock {
                    ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(controller)
                }
                assertFalse("the live replacement must not release the roster read", rosterReply.isCompleted)
                assertEquals(
                    listOf(ConversationTimelineTestIds.MESSAGE_A, ConversationTimelineTestIds.MESSAGE_B),
                    timelineMessageIds(controller),
                )
                assertEquals(
                    "no foreground reopen or account catch-up is needed",
                    1,
                    scripts.timelineSubscriptionOpenCount,
                )
            } finally {
                rosterReply.complete(conversationTimelineGroupRoster())
                controller.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(scripts)
            }
        }

    /** Early live consumption does not bypass an unverified notification transcript's roster gate. */
    @Test
    fun pendingRosterStillGatesDisclosureWhileLiveWindowsAreConsumed() =
        runBlocking {
            val rosterReply = CompletableDeferred<GroupRosterFfi>()
            val messageA = timelineRecord(ConversationTimelineTestIds.MESSAGE_A, 1uL)
            val messageB = timelineRecord(ConversationTimelineTestIds.MESSAGE_B, 2uL)
            val timeline = ScriptedConversationTimelineSubscription(timelinePage(messageA))
            val scripts =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = listOf(timeline),
                    group = conversationTimelineTestGroup(),
                )
            val controller =
                ConversationController(
                    appState = conversationTimelineTestAppState(scripts.subscriptions),
                    initialGroup = conversationTimelineTestGroup(),
                    initialChatListRow = notificationChatListRow(),
                    groupRosterReader = { _, _ -> rosterReply.await() },
                    startOnConstruction = true,
                )
            try {
                awaitConversationCondition { timeline.nextWindowCallCount > 0 }
                timeline.emitWindow(timelinePage(messageA, messageB))
                awaitAdvancingTimelineClock {
                    ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(controller)
                }
                assertFalse(controller.membersVerified)
                assertFalse(
                    "consumption is not permission to render a transcript",
                    controller.hasKnownTranscriptPresentation,
                )

                rosterReply.complete(conversationTimelineGroupRoster())
                awaitConversationCondition { controller.membersVerified }
            } finally {
                rosterReply.complete(conversationTimelineGroupRoster())
                controller.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(scripts)
            }
        }

    /** Normal EOF cannot close the attempt while its initial loading/disclosure metadata is unsettled. */
    @Test
    fun normalTimelineEndWaitsForTheHeldInitialRosterToSettle() = assertNormalEndSettlesRoster(false)

    /** A final live window followed by EOF must retain the same initial-metadata settlement contract. */
    @Test
    fun timelineEndDuringFinalBatchWaitsForTheHeldInitialRosterToSettle() = assertNormalEndSettlesRoster(true)

    /** Holds initialization across normal producer end, with or without a final coalesced window. */
    private fun assertNormalEndSettlesRoster(finalWindow: Boolean) =
        runBlocking {
            val rosterStarted = CompletableDeferred<Unit>()
            val rosterReply = CompletableDeferred<GroupRosterFfi>()
            val timeline =
                ScriptedConversationTimelineSubscription(
                    timelinePage(timelineRecord(ConversationTimelineTestIds.MESSAGE_A, 1uL)),
                ).apply {
                    if (finalWindow) {
                        emitWindow(
                            timelinePage(
                                timelineRecord(ConversationTimelineTestIds.MESSAGE_A, 1uL),
                                timelineRecord(ConversationTimelineTestIds.MESSAGE_B, 2uL),
                            ),
                        )
                    }
                    endWindows()
                }
            val scripts =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = listOf(timeline),
                    group = conversationTimelineTestGroup(),
                )
            var firstCloseLoading: Boolean? = null
            var firstCloseVerified: Boolean? = null
            val controller =
                ConversationController(
                    appState = conversationTimelineTestAppState(scripts.subscriptions),
                    initialGroup = conversationTimelineTestGroup(),
                    initialChatListRow = notificationChatListRow(),
                    groupRosterReader = { _, _ ->
                        rosterStarted.complete(Unit)
                        rosterReply.await()
                    },
                    startOnConstruction = false,
                )
            timeline.onClose = {
                // Handles close on IO; sample Compose presentation state on its Main owner.
                runBlocking(Dispatchers.Main.immediate) {
                    if (firstCloseLoading == null) {
                        firstCloseLoading = controller.isLoading
                        firstCloseVerified = controller.membersVerified
                    }
                }
            }
            controller.start()
            try {
                awaitAdvancingTimelineClock {
                    rosterStarted.isCompleted && timeline.windowEndObserved.isCompleted &&
                        (!finalWindow || ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(controller))
                }
                assertEquals(
                    "normal EOF must not reopen while initialization is held",
                    1,
                    scripts.timelineSubscriptionOpenCount,
                )
                assertEquals(0, timeline.closeCallCount)
                rosterReply.complete(conversationTimelineGroupRoster())
                awaitConversationCondition { timeline.closeCallCount > 0 }
                assertEquals(false, firstCloseLoading)
                assertEquals(true, firstCloseVerified)
                assertTrue(ConversationTimelineTestIds.MESSAGE_A in timelineMessageIds(controller))
            } finally {
                rosterReply.complete(conversationTimelineGroupRoster())
                controller.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(scripts)
            }
        }

    /** Runs the production batch-drain timeout on Robolectric's paused main clock while IO remains live. */
    private fun awaitAdvancingTimelineClock(condition: () -> Boolean) {
        awaitConversationCondition {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            condition()
        }
    }
}
