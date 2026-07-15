package dev.ipf.whitenoise.android.updates

import androidx.work.ListenableWorker
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class AppUpdateWorkerTest {
    @Test
    fun retriesIoFailures() {
        assertTrue(AppUpdateWorker.resultForRefreshFailure(IOException("network")) is ListenableWorker.Result.Retry)
    }

    @Test
    fun failsRuntimeExceptionsWithoutRetryLoop() {
        assertTrue(AppUpdateWorker.resultForRefreshFailure(RuntimeException("bug")) is ListenableWorker.Result.Failure)
    }

    @Test
    fun rethrowsCancellation() {
        assertThrows(CancellationException::class.java) {
            AppUpdateWorker.resultForRefreshFailure(CancellationException("cancelled"))
        }
    }
}
