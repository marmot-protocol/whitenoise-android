package dev.ipf.whitenoise.android.translation

import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineTranslatorBinderCallTest {
    /** A stuck synchronous transaction cannot retain the caller's request/unbind lifetime. */
    @Test
    fun cancellationCompletesBeforeBlockingSubmissionReturns() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            try {
                val request =
                    async {
                        runTranslatorBinderCall {
                            started.complete(Unit)
                            try {
                                release.await(5, TimeUnit.SECONDS)
                            } finally {
                                finished.countDown()
                            }
                        }
                    }
                withTimeout(2_000L) { started.await() }
                withTimeout(2_000L) { request.cancelAndJoin() }
                assertTrue(request.isCancelled)
            } finally {
                release.countDown()
                assertTrue(finished.await(2, TimeUnit.SECONDS))
            }
        }

    /** Binder death has its actionable typed recovery result, rather than a generic connection error. */
    @Test
    fun synchronousBinderDeathUsesTheServiceDiedFailure() =
        runBlocking {
            val failure =
                runCatching { runTranslatorBinderCall<Unit> { throw RemoteException() } }
                    .exceptionOrNull() as TranslationException
            assertEquals(TranslationFailure.SERVICE_DIED, failure.failure)
        }

    @Test
    fun unexpectedOperationFailureReachesTheCallerWithoutAStalledContinuation() =
        runBlocking {
            val failure = IllegalStateException("Synthetic adapter failure")
            val observed =
                withTimeout(2_000L) {
                    runCatching { runTranslatorBinderCall<Unit> { throw failure } }.exceptionOrNull()
                }
            assertTrue(observed is IllegalStateException)
            assertEquals(failure.message, observed?.message)
            // Coroutine stack recovery can copy the exception while retaining its original cause.
            assertSame(failure, observed?.cause ?: observed)
        }
}
