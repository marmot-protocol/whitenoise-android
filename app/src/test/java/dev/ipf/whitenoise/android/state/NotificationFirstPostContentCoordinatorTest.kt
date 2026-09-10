package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.LocalNotificationContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic scheduler coverage for the absolute deadline and single-correction contract. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationFirstPostContentCoordinatorTest {
    /** Keeps every observable coordinator outcome fixed and free of user content. */
    @Test
    fun timingOutcomesAreFixedAndPrivacySafe() {
        assertEquals(
            "resolved_before_deadline",
            NotificationFirstPostContentResult.Resolved("content").timingOutcome(),
        )
        assertEquals("timeout_fallback", NotificationFirstPostContentResult.TimedOut.timingOutcome())
        assertEquals("failed_fallback", NotificationFirstPostContentResult.Failed.timingOutcome())
        assertEquals("busy_fallback", NotificationFirstPostContentResult.Busy.timingOutcome())
    }

    /** Lets a failed content claimant hand the sole write slot to a waiting invite correction. */
    @Test
    fun failedContentClaimHandsTheSlotToAWaitingInvite() =
        runTest {
            val permit = NotificationLateCorrectionPermit()
            assertTrue(permit.acquire())
            val invite = async { permit.acquire() }
            runCurrent()

            assertFalse(invite.isCompleted)
            permit.complete(written = false)
            runCurrent()

            assertTrue(invite.await())
            permit.complete(written = true)
            assertFalse(permit.acquire())
        }

    /** Lets a failed invite claimant hand the sole write slot to waiting content enrichment. */
    @Test
    fun failedInviteClaimHandsTheSlotToWaitingContent() =
        runTest {
            val permit = NotificationLateCorrectionPermit()
            assertTrue(permit.acquire())
            val content = async { permit.acquire() }
            runCurrent()

            assertFalse(content.isCompleted)
            permit.complete(written = false)
            runCurrent()

            assertTrue(content.await())
            permit.complete(written = true)
            assertFalse(permit.acquire())
        }

    /** Wakes a waiting loser after success without allowing a second or third correction. */
    @Test
    fun successfulClaimConsumesTheSlotForEveryWaitingLane() =
        runTest {
            val permit = NotificationLateCorrectionPermit()
            assertTrue(permit.acquire())
            val waiting = async { permit.acquire() }
            runCurrent()

            assertFalse(waiting.isCompleted)
            permit.complete(written = true)
            runCurrent()

            assertFalse(waiting.await())
            assertFalse(permit.acquire())
        }

    /** Removes a cancelled waiter without consuming or stranding the restored slot. */
    @Test
    fun cancelledWaiterLeavesTheRestoredSlotAvailable() =
        runTest {
            val permit = NotificationLateCorrectionPermit()
            assertTrue(permit.acquire())
            val waiter = async { permit.acquire() }
            runCurrent()
            assertFalse(waiter.isCompleted)

            waiter.cancelAndJoin()
            permit.complete(written = false)

            assertTrue(permit.acquire())
            permit.complete(written = true)
            assertFalse(permit.acquire())
        }

    /** Reserves the sole correction for changed text rather than cosmetic imagery. */
    @Test
    fun lateCorrectionRequiresChangedContent() {
        val complete = presentation(body = "final")

        assertEquals(
            NotificationLateCorrectionPlan.None,
            notificationLateCorrectionPlan(complete, complete),
        )
        assertEquals(
            NotificationLateCorrectionPlan.Content,
            notificationLateCorrectionPlan(presentation(body = "fallback"), complete),
        )
        assertEquals(
            NotificationLateCorrectionPlan.Content,
            notificationLateCorrectionPlan(
                complete,
                presentation(body = "final", recipientAccountSubtext = "Work"),
            ),
        )
    }

    /** Publishes the complete local projection when all work fits one deadline. */
    @Test
    fun fastResolversShareOneFirstPostBudgetAndReturnCompleteContent() =
        runTest {
            val coordinator = coordinator()
            val stages = mutableListOf<String>()
            val stage = coordinator.startStage()

            val result =
                coordinator.resolve(stage) {
                    delay(35L)
                    stages += "sender"
                    delay(35L)
                    stages += "markdown"
                    "Alice: hello"
                }

            assertEquals(
                NotificationFirstPostContentResult.Resolved("Alice: hello"),
                result,
            )
            assertEquals(listOf("sender", "markdown"), stages)
            assertEquals(70L, testScheduler.currentTime)
        }

    /** Prevents sequential resolvers from renewing the absolute deadline. */
    @Test
    fun deadlineIsAbsoluteAcrossResolversInsteadOfRenewedPerResolver() =
        runTest {
            val coordinator = coordinator()
            val completedStages = mutableListOf<String>()
            val stage = coordinator.startStage()

            val result =
                coordinator.resolve(stage) {
                    delay(60L)
                    completedStages += "sender"
                    delay(60L)
                    completedStages += "markdown"
                    "late"
                }

            assertEquals(NotificationFirstPostContentResult.TimedOut, result)
            assertEquals(listOf("sender"), completedStages)
            assertEquals(100L, testScheduler.currentTime)
        }

    /** Charges caller-side coordinator setup to the same exact content deadline. */
    @Test
    fun callerSetupCannotRenewTheContentStageDeadline() =
        runTest {
            val coordinator = coordinator()
            val stage = coordinator.startStage()
            advanceTimeBy(40L)

            val result =
                coordinator.resolve(stage) {
                    delay(60L)
                    "late"
                }

            assertEquals(NotificationFirstPostContentResult.TimedOut, result)
            assertEquals(100L, testScheduler.currentTime)
        }

    /** Accepts completion one millisecond before the exact caller-started deadline. */
    @Test
    fun completionBeforeTheDeadlineDoesNotUseAnEarlyMargin() =
        runTest {
            val coordinator = coordinator()
            val stage = coordinator.startStage()
            advanceTimeBy(40L)

            val result =
                coordinator.resolve(stage) {
                    delay(59L)
                    "on time"
                }

            assertEquals(NotificationFirstPostContentResult.Resolved("on time"), result)
            assertEquals(99L, testScheduler.currentTime)
        }

    /** Rejects an immediate result stamped at the deadline even if its await wins scheduling. */
    @Test
    fun completionAtTheDeadlineCannotResolve() =
        runTest {
            var now = 0L
            val coordinator = coordinator(elapsedRealtimeMillis = { now })
            val stage = coordinator.startStage()

            val result =
                coordinator.resolve(stage) {
                    now = 100L
                    "late"
                }

            assertEquals(NotificationFirstPostContentResult.TimedOut, result)
        }

    /** Rejects a spent stage without invoking work and leaves the permit reusable. */
    @Test
    fun expiredStageDoesNotInvokeTheResolverOrLeakItsPermit() =
        runTest {
            val calls = AtomicInteger(0)
            val coordinator = coordinator()
            val expired = coordinator.startStage()
            advanceTimeBy(100L)

            assertEquals(
                NotificationFirstPostContentResult.TimedOut,
                coordinator.resolve(expired) {
                    calls.incrementAndGet()
                    "must not run"
                },
            )
            assertEquals(0, calls.get())
            val fresh = coordinator.startStage()
            assertEquals(
                NotificationFirstPostContentResult.Resolved("fresh"),
                coordinator.resolve(fresh) {
                    calls.incrementAndGet()
                    "fresh"
                },
            )
            assertEquals(1, calls.get())
        }

    /** Rechecks the deadline after coordinator setup before starting lazy resolver work. */
    @Test
    fun coordinatorSetupCannotStartWorkAfterSpendingTheLastMillisecond() =
        runTest {
            val calls = AtomicInteger(0)
            val clockReads = AtomicInteger(0)
            val coordinator =
                coordinator(
                    elapsedRealtimeMillis = {
                        when (clockReads.getAndIncrement()) {
                            0 -> 0L
                            1, 2 -> 99L
                            else -> 100L
                        }
                    },
                )
            val stage = coordinator.startStage()

            assertEquals(
                NotificationFirstPostContentResult.TimedOut,
                coordinator.resolve(stage) {
                    calls.incrementAndGet()
                    "must not run"
                },
            )
            assertEquals(0, calls.get())
            val fresh = coordinator.startStage()
            assertEquals(
                NotificationFirstPostContentResult.Resolved("fresh"),
                coordinator.resolve(fresh) { "fresh" },
            )
        }

    /** Rejects a foreign clock token before acquiring a reusable permit or invoking work. */
    @Test
    fun stageCannotCrossCoordinatorClockDomains() =
        runTest {
            val calls = AtomicInteger(0)
            val source = coordinator()
            val destination = coordinator()
            val failure =
                runCatching {
                    destination.resolve(source.startStage()) {
                        calls.incrementAndGet()
                        "must not run"
                    }
                }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertEquals(0, calls.get())
            val ownStage = destination.startStage()
            assertEquals(
                NotificationFirstPostContentResult.Resolved("own"),
                destination.resolve(ownStage) { "own" },
            )
        }

    /** Leaves caller-return headroom inside the unchanged observed one-hundred-millisecond ceiling. */
    @Test
    fun defaultResolverDeadlineLeavesCallerReturnHeadroom() =
        runTest {
            val coordinator = defaultCoordinator()
            val stage = coordinator.startStage()

            assertEquals(
                NotificationFirstPostContentResult.TimedOut,
                coordinator.resolve(stage) {
                    delay(76L)
                    "late"
                },
            )
            assertEquals(75L, testScheduler.currentTime)
        }

    /** Keeps complete local content eligible until the last millisecond before the default cutoff. */
    @Test
    fun defaultDeadlineAcceptsContentAtSeventyFourMilliseconds() =
        runTest {
            val coordinator = defaultCoordinator()
            val result =
                coordinator.resolve(coordinator.startStage()) {
                    delay(74L)
                    "complete"
                }

            assertEquals(NotificationFirstPostContentResult.Resolved("complete"), result)
            assertEquals(74L, testScheduler.currentTime)
        }

    /** Selects fallback for work reaching the exact default cutoff rather than consuming return headroom. */
    @Test
    fun defaultDeadlineRejectsContentAtSeventyFiveMilliseconds() =
        runTest {
            val coordinator = defaultCoordinator()
            val result =
                coordinator.resolve(coordinator.startStage()) {
                    delay(75L)
                    "late"
                }

            assertEquals(NotificationFirstPostContentResult.TimedOut, result)
            assertEquals(75L, testScheduler.currentTime)
        }

    /** Counts caller setup toward the same default deadline instead of granting a fresh resolver budget. */
    @Test
    fun defaultDeadlineIncludesCallerSetup() =
        runTest {
            val coordinator = defaultCoordinator()
            val stage = coordinator.startStage()
            advanceTimeBy(40L)

            val result =
                coordinator.resolve(stage) {
                    delay(35L)
                    "late"
                }

            assertEquals(NotificationFirstPostContentResult.TimedOut, result)
            assertEquals(75L, testScheduler.currentTime)
        }

    /** Converts a resolver exception into a safe fallback outcome. */
    @Test
    fun resolverFailureSelectsFallbackWithoutEscapingTheCoordinator() =
        runTest {
            val coordinator = coordinator()
            val stage = coordinator.startStage()
            val result =
                coordinator.resolve<String>(stage) {
                    throw IllegalStateException("local projection unavailable")
                }

            assertEquals(NotificationFirstPostContentResult.Failed, result)
        }

    /** Propagates caller cancellation and frees the concurrency permit. */
    @Test
    fun callerCancellationCancelsTheAttemptAndReleasesItsPermit() =
        runTest {
            val coordinator = coordinator()
            val attemptStage = coordinator.startStage()
            val attempt = async { coordinator.resolve<String>(attemptStage) { awaitCancellation() } }
            runCurrent()

            attempt.cancelAndJoin()
            runCurrent()

            assertTrue(attempt.isCancelled)
            assertEquals(
                NotificationFirstPostContentResult.Resolved("next"),
                coordinator.resolve(coordinator.startStage()) { "next" },
            )
        }

    /** Keeps a timed-out blocking binding call from admitting unbounded queued work. */
    @Test
    fun timedOutBlockingBindingCannotQueueMoreFirstPostWork() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger(0)
            val coordinator = coordinator()

            val first =
                async {
                    coordinator.resolve(coordinator.startStage()) {
                        if (calls.incrementAndGet() == 1) {
                            withContext(NonCancellable) { release.await() }
                        }
                        "Alice"
                    }
                }
            try {
                runCurrent()
                advanceTimeBy(100L)
                runCurrent()

                assertEquals(NotificationFirstPostContentResult.TimedOut, first.await())
                assertEquals(
                    NotificationFirstPostContentResult.Busy,
                    coordinator.resolve(coordinator.startStage()) { "must not queue" },
                )
                assertEquals(1, calls.get())
            } finally {
                release.complete(Unit)
                runCurrent()
                first.cancelAndJoin()
            }

            assertEquals(
                NotificationFirstPostContentResult.Resolved("Alice"),
                coordinator.resolve(coordinator.startStage()) {
                    calls.incrementAndGet()
                    "Alice"
                },
            )
            assertEquals(2, calls.get())
        }

    /** Uses the production deadline policy with a deterministic scheduler clock. */
    private fun TestScope.defaultCoordinator(): NotificationFirstPostContentCoordinator =
        NotificationFirstPostContentCoordinator(
            scope = this,
            dispatcher = StandardTestDispatcher(testScheduler),
            elapsedRealtimeMillis = { testScheduler.currentTime },
        )

    /** Binds coordinator work to the test scheduler for deterministic deadlines. */
    private fun TestScope.coordinator(): NotificationFirstPostContentCoordinator {
        val schedulerClock = { testScheduler.currentTime }
        return coordinator(schedulerClock)
    }

    /** Builds a coordinator against the caller-selected monotonic clock for boundary cases. */
    private fun TestScope.coordinator(elapsedRealtimeMillis: () -> Long): NotificationFirstPostContentCoordinator =
        NotificationFirstPostContentCoordinator(
            scope = this,
            dispatcher = StandardTestDispatcher(testScheduler),
            elapsedRealtimeMillis = elapsedRealtimeMillis,
            timeoutMillis = 100L,
        )

    /** Creates a complete synthetic presentation for coordinator assertions. */
    private fun presentation(
        body: String,
        recipientAccountSubtext: String? = null,
    ): NotificationContentPresentation =
        NotificationContentPresentation(
            content =
                LocalNotificationContent(
                    notificationTag = "account|group",
                    notificationId = 0,
                    title = "Alice in General",
                    body = body,
                    senderName = "Alice",
                    senderKey = "alice",
                    selfName = "Me",
                    selfKey = "me",
                    isGroupConversation = true,
                    conversationTitle = "General",
                ),
            recipientAccountSubtext = recipientAccountSubtext,
        )
}
