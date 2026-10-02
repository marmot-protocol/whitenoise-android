package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentUserActionsTest {
    /** Ten taps share one admission, while an unrelated file proceeds independently. */
    @Test
    fun repeatedTapsCoalesceWithoutBlockingAnotherFile() =
        runTest {
            val actions = AttachmentUserActions(backgroundScope)
            val release = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            assertTrue(
                actions.retry("file", {
                    events += "admit"
                    release.await()
                }, { events += "open" }, { error("failure") }),
            )
            repeat(10) { assertFalse(actions.retry("file", { error("duplicate") }, {}, {})) }
            assertTrue(actions.retry("other", {}, { events += "other" }, {}))
            runCurrent()
            assertEquals(listOf("admit", "other"), events)
            assertEquals(setOf("file"), actions.pendingRetries.value)
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf("admit", "other", "open"), events)
            assertTrue(actions.pendingRetries.value.isEmpty())
        }

    /** A Cancel queued before the retry starts skips native admission entirely. */
    @Test
    fun cancelBeforeAdmissionSkipsRetry() =
        runTest {
            val actions = AttachmentUserActions(backgroundScope)
            val events = mutableListOf<String>()
            actions.retry("file", { error("stale admission") }, { error("stale open") }, {})
            val cancellation =
                actions.cancel("file", {
                    events += "cancel"
                    true
                }, { events += "confirmed:$it" })
            runCurrent()
            assertTrue(cancellation.await())
            assertEquals(listOf("cancel", "confirmed:true"), events)
            assertTrue(actions.pendingRetries.value.isEmpty())
        }

    /** Native admission already underway is ordered before Cancel but cannot deliver a viewer afterwards. */
    @Test
    fun cancelDuringAdmissionFencesHandoff() =
        runTest {
            val actions = AttachmentUserActions(backgroundScope)
            val release = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            actions.retry("file", {
                events += "admit"
                release.await()
            }, { error("late open") }, {})
            runCurrent()
            actions.cancel("file", {
                events += "cancel"
                true
            }, { events += "confirmed:$it" })
            runCurrent()
            assertEquals(listOf("admit"), events)
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf("admit", "cancel", "confirmed:true"), events)
        }

    /** A new retry waits for cancellation; obsolete cancellation completion cannot overwrite its state. */
    @Test
    fun retryAfterCancelKeepsCommandOrderAndLatestCallback() =
        runTest {
            val actions = AttachmentUserActions(backgroundScope)
            val release = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            actions.cancel("file", {
                events += "cancel"
                release.await()
                true
            }, { error("stale cancel result") })
            runCurrent()
            actions.retry("file", { events += "retry" }, { events += "open" }, {})
            runCurrent()
            assertEquals(setOf("file"), actions.pendingRetries.value)
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf("cancel", "retry", "open"), events)
            assertTrue(actions.pendingRetries.value.isEmpty())
        }

    /** Failure never clears suppression or opens a viewer and releases the lane for a later deliberate action. */
    @Test
    fun admissionFailureAllowsANewDeliberateRetry() =
        runTest {
            val actions = AttachmentUserActions(backgroundScope)
            val failures = mutableListOf<String>()
            actions.retry(
                "file",
                { error("rejected") },
                { error("unexpected open") },
                { failures += it.message.orEmpty() },
            )
            runCurrent()
            assertEquals(listOf("rejected"), failures)
            assertTrue(actions.pendingRetries.value.isEmpty())
            var opened = false
            assertTrue(actions.retry("file", {}, { opened = true }, {}))
            runCurrent()
            assertTrue(opened)
        }

    /** Cancellation failure is explicit rather than being treated as a native acknowledgement. */
    @Test
    fun cancellationExceptionReportsFailure() =
        runTest {
            val actions = AttachmentUserActions(backgroundScope)
            val results = mutableListOf<Boolean>()
            val cancellation = actions.cancel("file", { error("engine unavailable") }, results::add)
            runCurrent()
            assertFalse(cancellation.await())
            assertEquals(listOf(false), results)
        }

    /** The deadline reports unconfirmed cancellation; a later native acknowledgement is still truthful. */
    @Test
    fun cancellationDeadlineDoesNotClaimSuccess() =
        runTest {
            val actions = AttachmentUserActions(backgroundScope)
            val release = CompletableDeferred<Unit>()
            val results = mutableListOf<Boolean>()
            actions.cancel("file", {
                release.await()
                true
            }, results::add)
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(listOf(false), results)
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf(false, true), results)
        }

    /** An old deadline cannot publish failure after a newer Retry has taken ownership. */
    @Test
    fun staleCancellationDeadlineDoesNotOverwriteRetry() =
        runTest {
            val actions = AttachmentUserActions(backgroundScope)
            val release = CompletableDeferred<Unit>()
            actions.cancel("file", {
                release.await()
                true
            }, { error("stale cancel callback") })
            runCurrent()
            actions.retry("file", {}, {}, {})
            advanceTimeBy(5_000)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertTrue(actions.pendingRetries.value.isEmpty())
        }
}
