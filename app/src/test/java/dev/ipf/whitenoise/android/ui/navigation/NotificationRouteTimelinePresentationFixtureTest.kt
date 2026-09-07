package dev.ipf.whitenoise.android.ui.navigation

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.awaitConversationCondition
import dev.ipf.whitenoise.android.state.awaitOpenedTimelineSubscriptionsClosed
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.hasKnownTranscriptPresentation
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_INITIAL_LOADING_TEST_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Regression coverage for the notification-route fixture's real-worker startup boundary. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class NotificationRouteTimelinePresentationFixtureTest : NotificationRouteTimelinePresentationFixture() {
    /** A fresh request on the retained ready screen must publish its own visibility report. */
    @Test
    fun sameReadyConversationReportsEveryNotificationRequest() {
        val directHarness = DirectNotificationConversationHarness(composeRule)
        val timelineSubscription = ScriptedConversationTimelineSubscription(timelinePage())
        val scripted =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(timelineSubscription),
                group = conversationTimelineTestGroup(),
            )
        val fixture = directHarness.create(scripted.subscriptions)
        val requestId = mutableLongStateOf(7L)
        val mounted = mutableStateOf(true)
        val reports = CopyOnWriteArrayList<Pair<Long, Boolean>>()
        try {
            awaitConversationCondition {
                fixture.controller.hasPublishedAuthoritativeTimeline && !fixture.controller.isLoading
            }
            directHarness.mount(
                fixture = fixture,
                mounted = mounted,
                notificationOpenRequestId = { requestId.longValue },
                onTimelineVisibilityChanged = { presentedRequestId, visible ->
                    reports += presentedRequestId to visible
                },
            )
            awaitCondition { 7L to true in reports }

            composeRule.runOnIdle { requestId.longValue = 8L }

            awaitCondition(
                failureMessage = {
                    "replacement request never published visibility: reports=$reports " +
                        "authoritative=${fixture.controller.hasPublishedAuthoritativeTimeline} " +
                        "loading=${fixture.controller.isLoading} " +
                        "timeline=${fixture.controller.timeline.size} " +
                        "known=${fixture.controller.hasKnownTranscriptPresentation}"
                },
            ) {
                8L to true in reports
            }
        } finally {
            try {
                directHarness.dispose(fixture, mounted)
            } finally {
                awaitOpenedTimelineSubscriptionsClosed(scripted)
            }
        }
    }

    /** Ordinary load failures retain their retry surface instead of falling through to terminal content. */
    @Test
    fun notificationRoute_loadFailureRetainsItsErrorSurface() {
        val directHarness = DirectNotificationConversationHarness(composeRule)
        val fixture =
            directHarness.create(
                ConversationLiveSubscriptions(
                    openTimeline = { _, _, _ -> error("temporary timeline failure") },
                    openGroupState = { _, _ -> error("failed timeline open must not bind group state") },
                ),
            )
        val mounted = mutableStateOf(true)
        try {
            awaitConversationCondition { fixture.controller.error != null }
            assertFalse(fixture.controller.terminalConversationUnavailable)
            directHarness.mount(fixture, mounted, notificationOpenRequestId = { 43L })
            composeRule.waitForIdle()

            composeRule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertDoesNotExist()
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            composeRule.onNodeWithText(context.getString(R.string.error_try_again)).assertIsDisplayed()
            composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        } finally {
            directHarness.dispose(fixture, mounted)
        }
    }

    /** Yields to immediate worker work without firing an unrelated future main-loop deadline. */
    @Test
    fun initialRouteWaitDoesNotAdvanceFutureMainDeadline() {
        val handler = Handler(Looper.getMainLooper())
        val delayedDeadline = CountDownLatch(1)
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val workerCompleted = CountDownLatch(1)
        val polls = AtomicInteger(0)
        val delayedCallback = Runnable(delayedDeadline::countDown)
        val worker =
            Thread {
                workerStarted.countDown()
                releaseWorker.await()
                workerCompleted.countDown()
            }
        handler.postDelayed(delayedCallback, 20L)
        worker.start()
        try {
            check(workerStarted.await(1L, TimeUnit.SECONDS)) { "real worker did not start" }
            awaitInitialRouteCondition(
                failureMessage = { "real worker did not complete before the route deadline" },
            ) {
                if (polls.incrementAndGet() == 3) releaseWorker.countDown()
                workerCompleted.count == 0L
            }
            assertEquals("startup wait advanced a future main deadline", 1L, delayedDeadline.count)
        } finally {
            releaseWorker.countDown()
            handler.removeCallbacks(delayedCallback)
            worker.join(1_000L)
        }
    }
}
