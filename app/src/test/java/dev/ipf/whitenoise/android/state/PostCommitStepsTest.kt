package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PostCommitStepsTest {
    @Test
    fun failureDoesNotSkipRemainingPostCommitSteps() =
        runBlocking {
            val attempted = mutableListOf<String>()
            val failures = mutableListOf<String>()

            runBestEffortPostCommitSteps(
                steps =
                    listOf(
                        "members" to {
                            attempted += "members"
                            error("members unavailable")
                        },
                        "timeline" to { attempted += "timeline" },
                        "read-state" to { attempted += "read-state" },
                    ),
                onFailure = { name, _ -> failures += name },
            )

            assertEquals(listOf("members", "timeline", "read-state"), attempted)
            assertEquals(listOf("members"), failures)
        }

    @Test
    fun cancellationStillStopsPostCommitWork() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                runBestEffortPostCommitSteps(
                    steps =
                        listOf(
                            "members" to { throw CancellationException("account changed") },
                            "timeline" to { error("must not run") },
                        ),
                    onFailure = { _, _ -> error("cancellation must not be downgraded") },
                )
            }
        }
    }
}
