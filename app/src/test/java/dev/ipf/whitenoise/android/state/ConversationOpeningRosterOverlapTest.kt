package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opening a conversation issues its roster read alongside the timeline and group-state opens
 * instead of after them (#586). A notification-routed group hides its transcript until the roster
 * lands, so the reads must overlap; the roster is still applied in its original order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationOpeningRosterOverlapTest {
    private val group = conversationTimelineTestGroup()

    /** The roster read is in flight while the timeline open is still blocked. */
    @Test
    fun rosterReadOverlapsTheTimelineOpen() {
        val timelineOpenGate = CompletableDeferred<Unit>()
        val rosterReads = AtomicInteger()
        val subscriptions =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(ScriptedConversationTimelineSubscription(emptyTimelinePage())),
                group = group,
            )
        val controller =
            ConversationController(
                appState =
                    conversationTimelineTestAppState(
                        gatedTimelineOpen(subscriptions.subscriptions, timelineOpenGate),
                    ),
                initialGroup = group,
                initialChatListRow = notificationChatListRow(),
                groupRosterReader = { _, _ ->
                    rosterReads.incrementAndGet()
                    conversationTimelineGroupRoster()
                },
                startOnConstruction = true,
            )

        awaitConversationCondition { rosterReads.get() == 1 }
        assertFalse("the timeline open is still blocked", timelineOpenGate.isCompleted)
        assertTrue("the opening read is not applied before the group state", controller.isLoading)

        timelineOpenGate.complete(Unit)
        awaitConversationCondition { controller.membersVerified }
        assertEquals("the overlapped read is reused, not repeated", 1, rosterReads.get())
        assertTrue(controller.hasKnownTranscriptPresentation)
    }

    /** A roster read that fails still reports the failure through the ordinary refresh path. */
    @Test
    fun aFailedOverlappedReadKeepsTheOrdinaryFailureHandling() {
        val subscriptions =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(ScriptedConversationTimelineSubscription(emptyTimelinePage())),
                group = group,
            )
        val controller =
            ConversationController(
                appState = conversationTimelineTestAppState(subscriptions.subscriptions),
                initialGroup = group,
                initialChatListRow = notificationChatListRow(),
                groupRosterReader = { _, _ -> error("roster unavailable") },
                startOnConstruction = true,
            )

        awaitConversationCondition { controller.memberRosterState == GroupRosterLoadState.FAILED }
        assertFalse(controller.membersVerified)
        assertTrue("a notification-routed group offers recovery", controller.transcriptPresentationNeedsRetry)
    }

    /** An explicit retry reads again rather than replaying the opening answer. */
    @Test
    fun aRetryReadsAgainInsteadOfReplayingTheOpeningAnswer() =
        runBlocking {
            val rosterReads = AtomicInteger()
            val subscriptions =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = listOf(ScriptedConversationTimelineSubscription(emptyTimelinePage())),
                    group = group,
                )
            val controller =
                ConversationController(
                    appState = conversationTimelineTestAppState(subscriptions.subscriptions),
                    initialGroup = group,
                    initialChatListRow = notificationChatListRow(),
                    groupRosterReader = { _, _ ->
                        rosterReads.incrementAndGet()
                        conversationTimelineGroupRoster()
                    },
                    startOnConstruction = true,
                )

            awaitConversationCondition { controller.membersVerified }
            controller.retryMembers()
            awaitConversationCondition { rosterReads.get() >= 2 }
        }

    /** Wraps the scripted subscriptions so the timeline open waits for the test's gate. */
    private fun gatedTimelineOpen(
        subscriptions: ConversationLiveSubscriptions,
        gate: CompletableDeferred<Unit>,
    ): ConversationLiveSubscriptions =
        ConversationLiveSubscriptions(
            openTimeline = { account, groupIdHex, limit ->
                gate.await()
                subscriptions.openTimeline(account, groupIdHex, limit)
            },
            openGroupState = subscriptions.openGroupState,
        )
}
