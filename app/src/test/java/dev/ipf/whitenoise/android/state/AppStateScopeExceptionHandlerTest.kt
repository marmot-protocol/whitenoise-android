package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateScopeExceptionHandlerTest {
    @Test
    fun launchedFailureIsReportedWithoutCancellingSiblingWork() {
        val failure = IllegalStateException("boom")
        var reported: Throwable? = null
        var siblingCompleted = false
        val scope =
            CoroutineScope(
                SupervisorJob() +
                    Dispatchers.Unconfined +
                    appStateScopeExceptionHandler { reported = it },
            )

        try {
            scope.launch { throw failure }
            scope.launch { siblingCompleted = true }

            assertSame(failure, reported)
            assertTrue("a failed mutation must not cancel later sibling work", siblingCompleted)
        } finally {
            scope.cancel()
        }
    }

    /** A failed process-owned bootstrap can be observed and retried without cancelling sibling mutations. */
    @Test
    fun bootstrapFailureAllowsRetryOnTheSupervisedMutationScope() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + appStateScopeExceptionHandler {})
            val attempts = BootstrapAttemptCoordinator()
            val failure = IllegalStateException("bootstrap failed")
            try {
                val failed = attempts.currentOrStart { scope.async { throw failure } }
                assertSame(failure, runCatching { failed.await() }.exceptionOrNull())
                var siblingCompleted = false
                scope.launch { siblingCompleted = true }
                val retry = attempts.currentOrStart { scope.async { Unit } }
                retry.await()
                assertTrue(siblingCompleted)
            } finally {
                scope.cancel()
            }
        }
}
