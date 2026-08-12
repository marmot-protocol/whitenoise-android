package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupCoordinatorTest {
    @Test
    fun timedOutRetryReusesTheRunningBootstrapAttempt() =
        runTest {
            val coordinator = BootstrapAttemptCoordinator()
            val releaseBootstrap = CompletableDeferred<Unit>()
            var starts = 0
            var phase: AppPhase = AppPhase.Bootstrapping
            val start = {
                starts += 1
                async {
                    releaseBootstrap.await()
                    phase = AppPhase.Ready
                }
            }

            val first = coordinator.currentOrStart(start)
            if (!awaitBootstrapAttempt(first, timeoutMillis = 1L)) {
                phase = AppPhase.Failed(ErrorPresentation(AppText.Plain("safe"), "operation=TEST"))
            }
            assertTrue(phase is AppPhase.Failed)

            phase = AppPhase.Bootstrapping
            val retry = coordinator.currentOrStart(start)

            assertSame(first, retry)
            assertEquals(1, starts)
            releaseBootstrap.complete(Unit)
            assertTrue(awaitBootstrapAttempt(retry, timeoutMillis = 1L))
            assertTrue(phase is AppPhase.Ready)
        }

    @Test
    fun retryAfterLaterStageFailureDoesNotReconstructOrRestartRuntime() =
        runTest {
            val coordinator = BootstrapRuntimeCoordinator<Any>()
            var constructions = 0
            var configurations = 0
            var starts = 0
            val construct = {
                constructions += 1
                Any()
            }
            val configure: suspend (Any) -> Unit = { configurations += 1 }
            val start: suspend (Any) -> Unit = { starts += 1 }

            var constructed: Any? = null
            val firstAttempt =
                runCatching {
                    coordinator.open(
                        construct = { construct().also { constructed = it } },
                        configure = configure,
                        start = start,
                    )
                    error("later bootstrap stage failed")
                }
            val retry = coordinator.open(construct, configure, start)

            assertTrue(firstAttempt.isFailure)
            assertSame(constructed, retry)
            assertEquals(1, constructions)
            assertEquals(1, configurations)
            assertEquals(1, starts)
        }

    @Test
    fun accountRevisionChangeDuringUnreadFoldPreventsStalePublication() =
        runTest {
            var currentRevision = 4L
            val guard = StartupUnreadRevisionGuard(expectedRevision = 4L) { currentRevision }
            val foldStarted = CompletableDeferred<Unit>()
            val releaseFold = CompletableDeferred<Unit>()
            var published = emptyMap<String, ULong>()
            val fold =
                async {
                    foldStarted.complete(Unit)
                    releaseFold.await()
                    val staleCounts = mapOf("old-account" to 3uL)
                    if (guard.isCurrent()) published = staleCounts
                }

            foldStarted.await()
            currentRevision += 1L
            releaseFold.complete(Unit)
            fold.await()

            assertFalse(guard.isCurrent())
            assertTrue(published.isEmpty())
        }
}
