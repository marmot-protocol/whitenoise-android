package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
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

/**
 * Regression for #2233: the production subscription retry loop must apply each
 * replacement [ConversationTimelineSubscriptionHandle] snapshot even when the bounded
 * window was retained from the prior attempt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationTimelineReconnectIntegrationTest {
    /** A recovered replacement snapshot publishes the durable target under one generation. */
    @Test
    fun recoveryGenerationReachesTheAuthoritativeReplacementRow() =
        runBlocking {
            val diagnostics =
                NotificationNetworkRecoveryDiagnostics(
                    traceFactory = { null },
                    traceRecorder = { _, _, _, _, _, _, _ -> },
                )
            val fixtures = conversationTimelineReconnectFixtures()
            val appState = conversationTimelineTestAppState(fixtures.scriptedSubscriptions.subscriptions, diagnostics)
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = conversationTimelineTestGroup(),
                    initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                    groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                    startOnConstruction = true,
                )
            try {
                awaitConversationCondition {
                    ConversationTimelineTestIds.MESSAGE_A in timelineMessageIds(controller)
                }
                awaitConversationCondition { fixtures.firstSubscription.nextWindowCallCount == 1 }
                fixtures.firstSubscription.endWindows()
                awaitConversationCondition { fixtures.firstSubscription.closeCallCount == 1 }

                diagnostics.networkRestored(9L)
                diagnostics.attemptStarted(9L, 1)
                diagnostics.catchUpSucceeded(9L, 1)
                controller.retryLoadFailure()

                awaitConversationCondition {
                    ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(controller) &&
                        controller.recoveryProjectionGeneration == 9L
                }
                assertEquals(
                    listOf(ConversationTimelineTestIds.MESSAGE_A, ConversationTimelineTestIds.MESSAGE_B),
                    timelineMessageIds(controller),
                )
                val phases = diagnostics.samples().filter { it.generation == 9L }.map { it.phase }
                assertTrue(
                    phases.indexOf(PerformancePhase.CURRENT_REPLAY_COMPLETE) <
                        phases.indexOf(PerformancePhase.TIMELINE_SUBSCRIPTION_RECEIVED),
                )
                assertTrue(
                    phases.indexOf(PerformancePhase.TIMELINE_SUBSCRIPTION_RECEIVED) <
                        phases.indexOf(PerformancePhase.TIMELINE_PROJECTION_PUBLISHED),
                )
            } finally {
                controller.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(fixtures.scriptedSubscriptions)
            }
        }

    /** A replacement subscription snapshot closes a reconnect gap without a later window. */
    @Test
    fun replacementSubscriptionSnapshotReconcilesGapMessageWithoutLaterDelta() =
        runBlocking {
            val fixtures = conversationTimelineReconnectFixtures()
            val controller = conversationController(fixtures.scriptedSubscriptions.subscriptions)
            try {
                awaitConversationCondition {
                    fixtures.firstSubscription.snapshotCallCount == 1 &&
                        ConversationTimelineTestIds.MESSAGE_A in timelineMessageIds(controller)
                }
                assertEquals(1, fixtures.scriptedSubscriptions.timelineSubscriptionOpenCount)
                assertFalse(ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(controller))

                awaitConversationCondition { fixtures.firstSubscription.nextWindowCallCount == 1 }
                assertTimelineSubscriptionSnapshotBeforeFirstNextWindow(fixtures.firstSubscription)

                fixtures.firstSubscription.endWindows()
                awaitConversationCondition { fixtures.firstSubscription.closeCallCount == 1 }
                controller.retryLoadFailure()

                awaitConversationCondition {
                    fixtures.scriptedSubscriptions.timelineSubscriptionOpenCount == 2 &&
                        ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(controller)
                }
                awaitConversationCondition { fixtures.replacementSubscription.nextWindowCallCount >= 1 }
                assertTimelineSubscriptionSnapshotBeforeFirstNextWindow(fixtures.replacementSubscription)
            } finally {
                controller.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(fixtures.scriptedSubscriptions)
            }
        }

    /** A null replacement snapshot retains the last authoritative window on screen. */
    @Test
    fun nullReplacementSnapshotKeepsRetainedTimelineVisible() =
        runBlocking {
            val firstSubscription =
                ScriptedConversationTimelineSubscription(
                    snapshotPage =
                        timelinePage(
                            timelineRecord(
                                messageId = ConversationTimelineTestIds.MESSAGE_A,
                                timelineAt = 1uL,
                            ),
                        ),
                )
            val replacementSubscription =
                ScriptedConversationTimelineSubscription(snapshotPage = null)
            val scriptedSubscriptions =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = listOf(firstSubscription, replacementSubscription),
                    group = conversationTimelineTestGroup(),
                )
            val controller = conversationController(scriptedSubscriptions.subscriptions)
            try {
                awaitConversationCondition {
                    ConversationTimelineTestIds.MESSAGE_A in timelineMessageIds(controller)
                }
                awaitConversationCondition { firstSubscription.nextWindowCallCount == 1 }

                firstSubscription.endWindows()
                awaitConversationCondition { firstSubscription.closeCallCount == 1 }
                controller.retryLoadFailure()

                awaitConversationCondition {
                    scriptedSubscriptions.timelineSubscriptionOpenCount == 2 &&
                        replacementSubscription.nextWindowCallCount >= 1
                }
                assertTimelineSubscriptionSnapshotBeforeFirstNextWindow(replacementSubscription)
                assertEquals(
                    listOf(ConversationTimelineTestIds.MESSAGE_A),
                    timelineMessageIds(controller),
                )
            } finally {
                controller.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(scriptedSubscriptions)
            }
        }

    /** A replacement snapshot resets pagination mode before the next live window. */
    @Test
    fun replacementSnapshotResetsPaginationModeForLaterAuthoritativeRefresh() =
        runBlocking {
            val oldMessage = timelineRecord(MESSAGE_OLD, 0uL)
            val messageA = timelineRecord(ConversationTimelineTestIds.MESSAGE_A, 1uL)
            val messageB = timelineRecord(ConversationTimelineTestIds.MESSAGE_B, 2uL)
            val firstSubscription =
                ScriptedConversationTimelineSubscription(
                    snapshotPage = timelinePageWithFlags(listOf(messageA), hasMoreBefore = true),
                    backwardsPage = timelinePageWithFlags(listOf(oldMessage, messageA), hasMoreAfter = true),
                )
            val replacementSubscription =
                ScriptedConversationTimelineSubscription(
                    snapshotPage = timelinePageWithFlags(listOf(messageA, messageB), hasMoreBefore = true),
                )
            val scriptedSubscriptions =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = listOf(firstSubscription, replacementSubscription),
                    group = conversationTimelineTestGroup(),
                )
            val controller = conversationController(scriptedSubscriptions.subscriptions)
            try {
                awaitConversationCondition { controller.hasMoreBefore }
                assertTrue(controller.loadOlderTimelinePage())
                assertEquals(
                    listOf(MESSAGE_OLD, ConversationTimelineTestIds.MESSAGE_A),
                    timelineMessageIds(controller),
                )

                awaitConversationCondition { firstSubscription.nextWindowCallCount == 1 }
                firstSubscription.endWindows()
                awaitConversationCondition { firstSubscription.closeCallCount == 1 }
                controller.retryLoadFailure()
                awaitConversationCondition {
                    scriptedSubscriptions.timelineSubscriptionOpenCount == 2 &&
                        ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(controller)
                }

                replacementSubscription.emitWindow(
                    timelinePageWithFlags(listOf(messageB), hasMoreBefore = false),
                )
                awaitConversationCondition { replacementSubscription.nextWindowCallCount >= 2 }
                awaitConversationCondition {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
                    !controller.hasMoreBefore
                }
                assertEquals(
                    listOf(ConversationTimelineTestIds.MESSAGE_B),
                    timelineMessageIds(controller),
                )
                assertFalse(controller.hasMoreBefore)
            } finally {
                controller.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(scriptedSubscriptions)
            }
        }

    /** Builds a bounded test page with explicit pagination flags. */
    private fun timelinePageWithFlags(
        messages: List<dev.ipf.marmotkit.TimelineMessageRecordFfi>,
        hasMoreBefore: Boolean = false,
        hasMoreAfter: Boolean = false,
    ) = TimelinePageFfi(
        messages = messages,
        hasMoreBefore = hasMoreBefore,
        hasMoreAfter = hasMoreAfter,
    )

    private fun conversationController(subscriptions: ConversationLiveSubscriptions) =
        ConversationController(
            appState = conversationTimelineTestAppState(subscriptions),
            initialGroup = conversationTimelineTestGroup(),
            initialMemberSnapshot = conversationTimelineMemberSnapshot(),
            groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
            startOnConstruction = true,
        )

    private companion object {
        val MESSAGE_OLD = "d0".repeat(32)
    }
}
