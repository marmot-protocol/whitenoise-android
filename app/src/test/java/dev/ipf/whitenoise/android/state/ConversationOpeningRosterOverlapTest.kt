package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        assertEquals(
            "a terminal read settles at once and names its branch",
            GroupRosterBlockReason.ReadFailed(GroupRosterReadFailureKind.TERMINAL, attempts = 1),
            controller.rosterBlockReason,
        )
    }

    /** A cold notification open with no trusted snapshot recovers from a busy worker without manual Retry. */
    @Test
    fun aTransientFailureConvergesAutomatically() {
        val rosterReads = AtomicInteger()
        val controller =
            notificationOpenedController { _, _ ->
                if (rosterReads.incrementAndGet() == 1) throw MarmotKitException.AccountWorkerBusy()
                conversationTimelineGroupRoster()
            }

        awaitConversationCondition { rosterReads.get() == 1 }
        assertFalse("backoff keeps the loading state, not the error", controller.transcriptPresentationNeedsRetry)
        awaitAdvancingMainClock { controller.membersVerified }
        assertEquals(2, rosterReads.get())
        assertNull(controller.rosterBlockReason)
        assertFalse(controller.transcriptPresentationNeedsRetry)
    }

    /** Repeated transient failures stop after the bounded backoff, then manual Retry still converges. */
    @Test
    fun repeatedFailuresAreBoundedAndManualRetryStillWorks() =
        runBlocking {
            val failing = AtomicBoolean(true)
            val rosterReads = AtomicInteger()
            val controller =
                notificationOpenedController { _, _ ->
                    rosterReads.incrementAndGet()
                    if (failing.get()) throw MarmotKitException.AccountWorkerBusy()
                    conversationTimelineGroupRoster()
                }

            awaitAdvancingMainClock { controller.memberRosterState == GroupRosterLoadState.FAILED }
            val bounded = GROUP_ROSTER_READ_RETRY_DELAYS_MS.size + 1
            assertEquals("one opening read plus each bounded re-read", bounded, rosterReads.get())
            assertEquals(
                GroupRosterBlockReason.ReadFailed(GroupRosterReadFailureKind.TRANSIENT, attempts = bounded),
                controller.rosterBlockReason,
            )
            assertTrue(controller.transcriptPresentationNeedsRetry)

            failing.set(false)
            controller.retryMembers()
            awaitConversationCondition { controller.membersVerified }
            assertNull(controller.rosterBlockReason)
            assertFalse(controller.transcriptPresentationNeedsRetry)
        }

    /** A wrong-group answer is never retried into disclosure; it keeps the gate and names the invariant. */
    @Test
    fun anInconsistentRosterKeepsTheGateWithoutRetrying() {
        val rosterReads = AtomicInteger()
        val controller =
            notificationOpenedController { _, _ ->
                rosterReads.incrementAndGet()
                conversationTimelineGroupRoster().copy(groupIdHex = "cd".repeat(32))
            }

        awaitConversationCondition { controller.memberRosterState == GroupRosterLoadState.INCONSISTENT }
        idleMainClockFor(GROUP_ROSTER_READ_RETRY_DELAYS_MS.sum())
        assertEquals("an invariant failure is not retried", 1, rosterReads.get())
        assertFalse(controller.membersVerified)
        assertTrue(controller.transcriptPresentationNeedsRetry)
        assertEquals(
            GroupRosterBlockReason.Inconsistent(GroupRosterInvariant.GROUP_ID_MISMATCH),
            controller.rosterBlockReason,
        )
    }

    /** A newer read during backoff supersedes the pending chain instead of stacking a second one. */
    @Test
    fun aNewerReadDuringBackoffAbandonsTheOlderChain() =
        runBlocking {
            val rosterReads = AtomicInteger()
            val controller =
                notificationOpenedController { _, _ ->
                    rosterReads.incrementAndGet()
                    throw MarmotKitException.AccountWorkerBusy()
                }

            awaitConversationCondition { rosterReads.get() == 1 }
            controller.retryMembers()
            assertEquals(2, rosterReads.get())
            idleMainClockFor(GROUP_ROSTER_READ_RETRY_DELAYS_MS.sum() * 2)
            awaitAdvancingMainClock { controller.memberRosterState == GroupRosterLoadState.FAILED }
            assertEquals(
                "only the newest chain spends its bounded re-reads",
                2 + GROUP_ROSTER_READ_RETRY_DELAYS_MS.size,
                rosterReads.get(),
            )
        }

    /** Disposing the controller during backoff abandons the pending re-read. */
    @Test
    fun disposalDuringBackoffStopsAutomaticRetry() {
        val rosterReads = AtomicInteger()
        val controller =
            notificationOpenedController { _, _ ->
                rosterReads.incrementAndGet()
                throw MarmotKitException.GroupHydrationPending(group.groupIdHex)
            }

        awaitConversationCondition { rosterReads.get() == 1 }
        controller.onCleared()
        idleMainClockFor(GROUP_ROSTER_READ_RETRY_DELAYS_MS.sum())
        assertEquals("no read replays after disposal", 1, rosterReads.get())
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

    /** Advances the paused main-looper clock until [condition] holds, so backoff delays elapse. */
    private fun awaitAdvancingMainClock(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLOCK_WAIT_SECONDS)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Condition not met while advancing the clock")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CLOCK_STEP_MS))
            Thread.sleep(CLOCK_STEP_REAL_MS)
        }
    }

    /** Lets [millis] of virtual main-looper time pass in small steps, running any due work. */
    private fun idleMainClockFor(millis: Long) {
        repeat((millis / CLOCK_STEP_MS + 2).toInt()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CLOCK_STEP_MS))
            Thread.sleep(CLOCK_STEP_REAL_MS)
        }
    }

    /** Opens the group the way notification routing does, without a trusted member snapshot. */
    private fun notificationOpenedController(rosterReader: suspend (String, String) -> GroupRosterFfi) =
        ConversationController(
            appState =
                conversationTimelineTestAppState(
                    ScriptedConversationLiveSubscriptions(
                        timelineScripts = listOf(ScriptedConversationTimelineSubscription(emptyTimelinePage())),
                        group = group,
                    ).subscriptions,
                ),
            initialGroup = group,
            initialChatListRow = notificationChatListRow(),
            groupRosterReader = rosterReader,
            startOnConstruction = true,
        )

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

    private companion object {
        const val CLOCK_WAIT_SECONDS = 20L
        const val CLOCK_STEP_MS = 50L
        const val CLOCK_STEP_REAL_MS = 2L
    }
}
