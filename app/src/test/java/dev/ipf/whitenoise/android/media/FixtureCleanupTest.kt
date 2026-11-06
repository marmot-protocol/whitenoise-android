package dev.ipf.whitenoise.android.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Exercises the same failure-ordering and cancellation adapter used by the native attachment probe. */
class FixtureCleanupTest {
    /** Account removal, native shutdown and file deletion all run without replacing the original assertion. */
    @Test
    fun bodyFailureKeepsAllCleanupFailuresAsSuppressed() =
        runTest {
            val bodyFailure = AssertionError("original body assertion")
            val accountFailure = IOException("account cleanup failed")
            val nativeFailure = IllegalStateException("shutdown failed")
            val fileFailure = IOException("file cleanup failed")
            val steps = mutableListOf<String>()
            val result =
                runCatching {
                    withFixtureCleanup(
                        cleanup = {
                            runFixtureCleanupSteps(
                                {
                                    steps += "accounts"
                                    throw accountFailure
                                },
                                {
                                    steps += "native"
                                    throw nativeFailure
                                },
                                {
                                    steps += "files"
                                    throw fileFailure
                                },
                            )
                        },
                        body = { throw bodyFailure },
                    )
                }
            assertSame(bodyFailure, result.exceptionOrNull())
            assertEquals(listOf("accounts", "native", "files"), steps)
            assertEquals(listOf(accountFailure), bodyFailure.suppressed.toList())
            assertEquals(listOf(nativeFailure, fileFailure), accountFailure.suppressed.toList())
        }

    /** A successful test body cannot pass when generated-file cleanup fails. */
    @Test
    fun cleanupFailureFailsAnOtherwiseSuccessfulBody() =
        runTest {
            val failure = IOException("partial file deletion")
            val result =
                runCatching {
                    withFixtureCleanup(cleanup = { throw failure }, body = { "passed" })
                }
            assertSame(failure, result.exceptionOrNull())
        }

    /** A canceled test still completes suspending cleanup before the fixture lifetime ends. */
    @Test
    fun canceledBodyStillFinishesSuspendingCleanup() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var cleaned = false
            val job =
                launch {
                    withFixtureCleanup(
                        cleanup = {
                            delay(1)
                            cleaned = true
                        },
                        body = {
                            started.complete(Unit)
                            awaitCancellation()
                        },
                    )
                }
            started.await()
            job.cancelAndJoin()
            assertTrue(cleaned)
        }

    /** Reusing the same exception object cannot turn teardown into an illegal self-suppression failure. */
    @Test
    fun repeatedFailureInstanceDoesNotMaskTheOriginal() =
        runTest {
            val failure = AssertionError("original")
            val result =
                runCatching {
                    withFixtureCleanup(
                        cleanup = { runFixtureCleanupSteps({ throw failure }, { throw failure }) },
                        body = { throw failure },
                    )
                }
            assertSame(failure, result.exceptionOrNull())
            assertTrue(failure.suppressed.isEmpty())
        }

    /** Successful cleanup preserves the result and runs in the declared resource-release order. */
    @Test
    fun successfulBodyRetainsItsResult() =
        runTest {
            val steps = mutableListOf<String>()
            val result =
                withFixtureCleanup(
                    cleanup = { runFixtureCleanupSteps({ steps += "native" }, { steps += "files" }) },
                    body = { "result" },
                )
            assertEquals("result", result)
            assertEquals(listOf("native", "files"), steps)
        }
}
