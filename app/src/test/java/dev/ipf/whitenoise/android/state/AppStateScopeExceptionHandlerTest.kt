package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
}
