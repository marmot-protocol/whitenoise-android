package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.marmotkit.SearchUpdateTriggerFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientUserSearchTest {
    /** Query, account, and follow revisions each replace the lifecycle-bound search producer. */
    @Test
    fun requestKeyChangesAcrossEveryStalenessBoundary() {
        val request =
            RecipientSearchRequestKey(
                query = "alice",
                activeAccountRef = "account-a",
                activeAccountIdHex = "aa",
                relationshipRevision = 7L,
            )

        assertEquals(
            5,
            setOf(
                request,
                request.copy(query = "bob"),
                request.copy(activeAccountRef = "account-b"),
                request.copy(activeAccountIdHex = "bb"),
                request.copy(relationshipRevision = 8L),
            ).size,
        )
    }

    /** A non-cancellable native read cannot publish after its query/account owner is cancelled. */
    @Test
    fun cancelledOwnerRejectsLateNativeValue() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var published: String? = null
            val job =
                launch {
                    published =
                        awaitCurrentRecipientSearchValue {
                            withContext(NonCancellable) {
                                started.complete(Unit)
                                release.await()
                                "stale result"
                            }
                        }
                }

            started.await()
            job.cancel()
            release.complete(Unit)
            job.join()

            assertNull(published)
        }

    /** A subscription returned after cancellation is closed without consuming stale updates. */
    @Test
    fun cancelledOpenClosesLateSubscriptionWithoutConsumption() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val subscription = RecordingCloseable()
            var consumed = false
            val job =
                launch {
                    withClosedRecipientSearchSubscription(
                        open = {
                            withContext(NonCancellable) {
                                started.complete(Unit)
                                release.await()
                            }
                            subscription
                        },
                        consume = {
                            consumed = true
                        },
                    )
                }

            started.await()
            job.cancel()
            release.complete(Unit)
            job.join()

            assertTrue(subscription.closed)
            assertFalse(consumed)
        }

    @Test
    fun subscriptionClosesAfterSuccessAndFailure() =
        runTest {
            val successful = RecordingCloseable()
            val result =
                withClosedRecipientSearchSubscription(
                    open = { successful },
                    consume = { "done" },
                )
            assertEquals("done", result)
            assertTrue(successful.closed)

            val failed = RecordingCloseable()
            var failure: Throwable? = null
            try {
                withClosedRecipientSearchSubscription(
                    open = { failed },
                    consume = { error("failed") },
                )
            } catch (error: Throwable) {
                failure = error
            }
            assertTrue(failure is IllegalStateException)
            assertTrue(failed.closed)
        }

    @Test
    fun subscriptionClosesAndRethrowsCancellation() =
        runTest {
            val subscription = RecordingCloseable()
            var failure: Throwable? = null
            try {
                withClosedRecipientSearchSubscription(
                    open = { subscription },
                    consume = { throw CancellationException("left screen") },
                )
            } catch (error: Throwable) {
                failure = error
            }
            assertTrue(failure is CancellationException)
            assertTrue(subscription.closed)
        }

    @Test
    fun closeFailureIsSuppressedWithoutReplacingCancellation() =
        runTest {
            val closeFailure = IllegalStateException("close failed")
            val subscription = RecordingCloseable(closeFailure)
            val cancellation = CancellationException("left screen")
            var failure: Throwable? = null
            try {
                withClosedRecipientSearchSubscription(
                    open = { subscription },
                    consume = { throw cancellation },
                )
            } catch (error: Throwable) {
                failure = error
            }

            val thrown = requireNotNull(failure)
            assertSame(cancellation, thrown)
            val suppressed = thrown.suppressed.single()
            assertTrue(suppressed is IllegalStateException)
            assertEquals(closeFailure.message, suppressed.message)
            assertTrue(subscription.closed)
        }

    @Test
    fun closeFailurePropagatesWhenConsumeSucceeds() =
        runTest {
            val closeFailure = IllegalStateException("close failed")
            var failure: Throwable? = null
            try {
                withClosedRecipientSearchSubscription(
                    open = { RecordingCloseable(closeFailure) },
                    consume = { "done" },
                )
            } catch (error: Throwable) {
                failure = error
            }

            val thrown = requireNotNull(failure)
            assertTrue(thrown is IllegalStateException)
            assertEquals(closeFailure.message, thrown.message)
        }

    @Test
    fun followReadFailureFallsBackButCancellationPropagates() =
        runTest {
            assertTrue(loadRecipientSearchFollowIds { error("cache unavailable") }.isEmpty())
            assertEquals(
                setOf("ab", "cd"),
                loadRecipientSearchFollowIds { listOf(" AB ", "cd") },
            )
            var failure: Throwable? = null
            try {
                loadRecipientSearchFollowIds { throw CancellationException("cancelled") }
            } catch (error: Throwable) {
                failure = error
            }
            assertTrue(failure is CancellationException)
        }

    @Test
    fun progressKeepsPartialResultsAndRecordsTerminalState() {
        val partial =
            RecipientSearchProgress()
                .withTrigger(SearchUpdateTriggerFfi.RadiusTimeout(2u))
                .withTrigger(SearchUpdateTriggerFfi.Error("relay unavailable"))

        assertTrue(partial.isIncomplete)
        assertTrue(partial.failed)
        assertFalse(partial.completed)
        assertTrue(partial.withTrigger(SearchUpdateTriggerFfi.SearchCompleted).completed)
    }

    private class RecordingCloseable(
        private val closeFailure: Throwable? = null,
    ) : AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }
}
