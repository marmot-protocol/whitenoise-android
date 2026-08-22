package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageTargetHighlightLifecycleTest {
    @Test
    fun targetIsActiveDuringOperationAndBoundedPostSettleDwell() =
        runTest {
            val lifecycle = MessageTargetHighlightLifecycle(dwellMillis = 100L)
            val started = CompletableDeferred<Unit>()
            val settled = CompletableDeferred<Unit>()
            var completed = false

            launch {
                completed =
                    lifecycle.highlightWhile("target") {
                        started.complete(Unit)
                        settled.await()
                        true
                    }
            }
            started.await()
            assertEquals("target", lifecycle.highlightedMessageId)

            settled.complete(Unit)
            runCurrent()
            assertEquals("target", lifecycle.highlightedMessageId)
            assertFalse(completed)

            advanceTimeBy(99L)
            runCurrent()
            assertEquals("target", lifecycle.highlightedMessageId)

            advanceTimeBy(1L)
            runCurrent()
            assertNull(lifecycle.highlightedMessageId)
            assertTrue(completed)
        }

    @Test
    fun cancellationClearsTheOwnedTargetImmediately() =
        runTest {
            val lifecycle = MessageTargetHighlightLifecycle(dwellMillis = 100L)
            val started = CompletableDeferred<Unit>()
            val neverCompletes = CompletableDeferred<Unit>()
            val job =
                launch {
                    lifecycle.highlightWhile("target") {
                        started.complete(Unit)
                        neverCompletes.await()
                        true
                    }
                }

            started.await()
            assertEquals("target", lifecycle.highlightedMessageId)
            job.cancelAndJoin()
            assertNull(lifecycle.highlightedMessageId)
        }

    @Test
    fun staleCompletionCannotClearANewerTarget() =
        runTest {
            val lifecycle = MessageTargetHighlightLifecycle(dwellMillis = 100L)
            val firstStarted = CompletableDeferred<Unit>()
            val firstSettled = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val secondSettled = CompletableDeferred<Unit>()

            launch {
                lifecycle.highlightWhile("first") {
                    firstStarted.complete(Unit)
                    firstSettled.await()
                    true
                }
            }
            firstStarted.await()
            launch {
                lifecycle.highlightWhile("second") {
                    secondStarted.complete(Unit)
                    secondSettled.await()
                    true
                }
            }
            secondStarted.await()
            assertEquals("second", lifecycle.highlightedMessageId)

            firstSettled.complete(Unit)
            runCurrent()
            assertEquals("second", lifecycle.highlightedMessageId)

            secondSettled.complete(Unit)
            advanceTimeBy(100L)
            runCurrent()
            assertNull(lifecycle.highlightedMessageId)
        }

    @Test
    fun unsuccessfulNavigationClearsWithoutDwell() =
        runTest {
            val lifecycle = MessageTargetHighlightLifecycle(dwellMillis = 100L)
            val completed = lifecycle.highlightWhile("missing") { false }

            assertFalse(completed)
            assertNull(lifecycle.highlightedMessageId)
        }

    @Test
    fun callSiteCanPreserveItsExistingTransientDwell() =
        runTest {
            val lifecycle = MessageTargetHighlightLifecycle(dwellMillis = 100L)
            var completed = false
            launch {
                completed =
                    lifecycle.highlightWhile(
                        messageId = "search-target",
                        postSettleDwellMillis = 250L,
                    ) { true }
            }

            advanceTimeBy(249L)
            runCurrent()
            assertEquals("search-target", lifecycle.highlightedMessageId)
            assertFalse(completed)

            advanceTimeBy(1L)
            runCurrent()
            assertNull(lifecycle.highlightedMessageId)
            assertTrue(completed)
        }
}
