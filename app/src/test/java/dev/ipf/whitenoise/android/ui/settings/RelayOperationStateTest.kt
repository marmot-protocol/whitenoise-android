package dev.ipf.whitenoise.android.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises suspended operations rather than relying on disabled buttons to serialize native relay writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayOperationStateTest {
    /** Claiming publication prevents add, restore and role edits until its deferred native work finishes. */
    @Test
    fun pendingPublicationRejectsConcurrentEdits() =
        runTest {
            val state = RelayOperationState()
            val completion = CompletableDeferred<Unit>()
            val writes = mutableListOf<String>()
            val launcher: (suspend () -> Unit) -> Unit = { block -> launch { block() } }
            state.launch(launcher) {
                writes += "publish"
                completion.await()
            }
            assertTrue(state.busy)
            // This request arrives before even the first coroutine has started.
            state.launch(launcher) { writes += "add" }
            runCurrent()
            state.launch(launcher) { writes += "restore" }
            state.launch(launcher) { writes += "role" }
            runCurrent()
            assertEquals(listOf("publish"), writes)
            completion.complete(Unit)
            advanceUntilIdle()
            assertFalse(state.busy)
            state.launch(launcher) { writes += "add-after-completion" }
            advanceUntilIdle()
            assertEquals(listOf("publish", "add-after-completion"), writes)
        }

    /** A cancelled in-flight operation releases the gate so a later explicit retry can run. */
    @Test
    fun cancellationReleasesTheOperation() =
        runTest {
            val state = RelayOperationState()
            var job: Job? = null
            val launcher: (suspend () -> Unit) -> Unit = { block -> job = launch { block() } }
            state.launch(launcher) { CompletableDeferred<Unit>().await() }
            runCurrent()
            job?.cancel()
            advanceUntilIdle()
            assertFalse(state.busy)
            var retried = false
            state.launch(launcher) { retried = true }
            advanceUntilIdle()
            assertTrue(retried)
        }

    /** A native failure propagates without leaving the UI permanently busy. */
    @Test
    fun operationFailureReleasesTheGate() =
        runTest {
            val state = RelayOperationState()
            var failure: Throwable? = null
            val launcher: (suspend () -> Unit) -> Unit = { block ->
                launch { failure = runCatching { block() }.exceptionOrNull() }
            }
            state.launch(launcher) { error("native relay failure") }
            advanceUntilIdle()
            assertEquals("native relay failure", failure?.message)
            assertFalse(state.busy)
        }

    /** A failed launcher does not strand a claim before the operation has even started. */
    @Test
    fun launchFailureReleasesTheGate() {
        val state = RelayOperationState()
        val result = runCatching { state.launch(launcher = { error("launch failed") }) {} }
        assertEquals("launch failed", result.exceptionOrNull()?.message)
        assertFalse(state.busy)
    }
}
