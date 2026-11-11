package dev.ipf.whitenoise.android.ui.conversation.share

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A held location reply that the test completes. */
private typealias Reply = CompletableDeferred<SharedLocation?>

/** Only the newest location lookup may move the picker or clear its progress (#3719). */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationLookupOwnerTest {
    /** A lookup retired by a manual pan cannot replace the selection when it finally returns. */
    @Test
    fun lateFixAfterAManualPanIsIgnored() =
        runTest {
            val published = mutableListOf<SharedLocation>()
            val reply = CompletableDeferred<SharedLocation?>()
            val owner = LocationLookupOwner(this)

            owner.start(lookup = { staleReply(reply) }, publish = published::add)
            runCurrent()
            owner.retire()
            reply.complete(A)
            runCurrent()

            assertTrue(published.isEmpty())
            assertFalse(owner.locating)
        }

    /** When two requests finish in reverse order, the older result is dropped and the newer one is kept. */
    @Test
    fun olderRequestCompletingLastCannotWin() =
        runTest {
            val published = mutableListOf<SharedLocation>()
            val first = CompletableDeferred<SharedLocation?>()
            val second = CompletableDeferred<SharedLocation?>()
            val owner = LocationLookupOwner(this)

            owner.start(lookup = { staleReply(first) }, publish = published::add)
            runCurrent()
            owner.start(lookup = { second.await() }, publish = published::add)
            runCurrent()
            second.complete(B)
            runCurrent()
            first.complete(A)
            runCurrent()

            assertEquals(listOf(B), published)
            assertFalse(owner.locating)
        }

    /** A retired request cannot clear the progress of the request that replaced it. */
    @Test
    fun retiredRequestKeepsTheReplacementInProgress() =
        runTest {
            val first = CompletableDeferred<SharedLocation?>()
            val second = CompletableDeferred<SharedLocation?>()
            val owner = LocationLookupOwner(this)

            owner.start(lookup = { staleReply(first) }, publish = {})
            runCurrent()
            owner.start(lookup = { second.await() }, publish = {})
            runCurrent()
            first.complete(null)
            runCurrent()

            assertTrue("the replacement is still looking", owner.locating)
            second.complete(null)
            runCurrent()
            assertFalse(owner.locating)
        }

    /** A current lookup publishes its fix and ends its progress; a null fix only ends progress. */
    @Test
    fun currentLookupPublishesAndSettles() =
        runTest {
            val published = mutableListOf<SharedLocation>()
            val owner = LocationLookupOwner(this)

            owner.start(lookup = { A }, publish = published::add)
            runCurrent()
            owner.start(lookup = { null }, publish = published::add)
            runCurrent()

            assertEquals(listOf(A), published)
            assertFalse(owner.locating)
        }

    /** A lookup that throws ends its progress without publishing, instead of leaving the spinner running. */
    @Test
    fun failedLookupEndsProgress() =
        runTest {
            val published = mutableListOf<SharedLocation>()
            val owner = LocationLookupOwner(this, initiallyLocating = true)

            owner.start(lookup = { throw SecurityException("permission revoked") }, publish = published::add)
            runCurrent()

            assertTrue(published.isEmpty())
            assertFalse(owner.locating)
        }

    /** A provider that ignores cancellation and still answers after its request was retired. */
    private suspend fun staleReply(reply: Reply): SharedLocation? = withContext(NonCancellable) { reply.await() }

    private companion object {
        val A = SharedLocation(1.0, 2.0, null)
        val B = SharedLocation(3.0, 4.0, null)
    }
}
