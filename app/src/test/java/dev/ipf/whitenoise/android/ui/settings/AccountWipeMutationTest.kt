package dev.ipf.whitenoise.android.ui.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AccountWipeMutationTest {
    @Test
    fun admittedFailurePresentsUnconfirmedOnceAndClearsProgressWithoutRetry() =
        runTest {
            val events = mutableListOf<String>()
            val failure = IllegalStateException("cleanup failed after removal")
            runAccountWipeMutation(
                onUnconfirmed = {
                    assertSame(failure, it)
                    events += "unconfirmed"
                },
                onFinished = { events += "finished" },
            ) {
                events += "account removed"
                throw failure
            }
            assertEquals(listOf("account removed", "unconfirmed", "finished"), events)
        }

    @Test
    fun cancellationPropagatesAndClearsProgressWithoutPresentation() =
        runTest {
            val events = mutableListOf<String>()
            val cancellation = CancellationException("cancelled")
            try {
                runAccountWipeMutation(
                    onUnconfirmed = { events += "unconfirmed" },
                    onFinished = { events += "finished" },
                ) { throw cancellation }
                throw AssertionError("Cancellation must propagate")
            } catch (error: CancellationException) {
                assertSame(cancellation, error)
            }
            assertEquals(listOf("finished"), events)
        }

    @Test
    fun normalPresentationCompletesWithoutUnconfirmedMessage() =
        runTest {
            for (result in listOf("null failure", "clean success", "partial report")) {
                val events = mutableListOf<String>()
                runAccountWipeMutation(
                    onUnconfirmed = { events += "unconfirmed" },
                    onFinished = { events += "finished" },
                ) { events += result }
                assertEquals(listOf(result, "finished"), events)
            }
        }
}
