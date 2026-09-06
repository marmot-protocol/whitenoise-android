package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            var closes = 0
            val construct = {
                constructions += 1
                Any()
            }
            val configure: suspend (Any) -> Unit = { configurations += 1 }
            val start: suspend (Any) -> Unit = { starts += 1 }
            val closeAfterFailure: suspend (Any) -> Unit = { closes += 1 }

            var constructed: Any? = null
            val firstAttempt =
                runCatching {
                    coordinator.open(
                        construct = { construct().also { constructed = it } },
                        configure = configure,
                        start = start,
                        closeAfterFailure = closeAfterFailure,
                    )
                    error("later bootstrap stage failed")
                }
            val retry = coordinator.open(construct, configure, start, closeAfterFailure)

            assertTrue(firstAttempt.isFailure)
            assertSame(constructed, retry)
            assertEquals(1, constructions)
            assertEquals(1, configurations)
            assertEquals(1, starts)
            assertEquals(0, closes)
        }

    @Test
    fun retryAfterConfigureFailureClosesRuntimeBeforeReconstructing() =
        runTest {
            val coordinator = BootstrapRuntimeCoordinator<Any>()
            val constructed = mutableListOf<Any>()
            val closed = mutableListOf<Any>()
            var configurations = 0
            var starts = 0
            val construct = {
                Any().also { constructed += it }
            }
            val configure: suspend (Any) -> Unit = {
                configurations += 1
                if (configurations == 1) error("configure failed")
            }
            val start: suspend (Any) -> Unit = { starts += 1 }
            val closeAfterFailure: suspend (Any) -> Unit = { closed += it }

            val firstAttempt =
                runCatching { coordinator.open(construct, configure, start, closeAfterFailure) }
            val retry = coordinator.open(construct, configure, start, closeAfterFailure)

            assertTrue(firstAttempt.isFailure)
            assertEquals(2, constructed.size)
            assertSame(constructed[0], closed.single())
            assertSame(constructed[1], retry)
            assertEquals(2, configurations)
            assertEquals(1, starts)
        }

    @Test
    fun retryAfterStartFailureClosesRuntimeBeforeReconstructing() =
        runTest {
            val coordinator = BootstrapRuntimeCoordinator<Any>()
            val constructed = mutableListOf<Any>()
            val configured = mutableListOf<Any>()
            val closed = mutableListOf<Any>()
            var starts = 0
            val construct = { Any().also { constructed += it } }
            val configure: suspend (Any) -> Unit = { configured += it }
            val start: suspend (Any) -> Unit = {
                starts += 1
                if (starts == 1) error("start failed")
            }
            val closeAfterFailure: suspend (Any) -> Unit = { closed += it }

            val firstAttempt =
                runCatching { coordinator.open(construct, configure, start, closeAfterFailure) }
            val retry = coordinator.open(construct, configure, start, closeAfterFailure)

            assertTrue(firstAttempt.isFailure)
            assertEquals(2, constructed.size)
            assertSame(constructed[0], closed.single())
            assertSame(constructed[1], retry)
            assertEquals(1, configured.count { it === constructed[0] })
            assertEquals(1, configured.count { it === retry })
            assertEquals(2, starts)
        }

    @Test
    fun cleanupFailurePreventsAnotherRuntimeConstruction() =
        runTest {
            val coordinator = BootstrapRuntimeCoordinator<Any>()
            val startupFailure = IllegalStateException("start failed")
            val cleanupFailure = IllegalStateException("cleanup failed")
            var constructions = 0
            var configurations = 0
            var starts = 0
            var closes = 0
            val construct = {
                constructions += 1
                Any()
            }
            val configure: suspend (Any) -> Unit = { configurations += 1 }
            val start: suspend (Any) -> Unit = {
                starts += 1
                throw startupFailure
            }
            val closeAfterFailure: suspend (Any) -> Unit = {
                closes += 1
                throw cleanupFailure
            }

            val firstAttempt =
                runCatching { coordinator.open(construct, configure, start, closeAfterFailure) }
            val retry =
                runCatching { coordinator.open(construct, configure, start, closeAfterFailure) }

            assertSame(startupFailure, firstAttempt.exceptionOrNull())
            assertSame(startupFailure, retry.exceptionOrNull())
            assertEquals(cleanupFailure::class, startupFailure.suppressed.single()::class)
            assertEquals(cleanupFailure.message, startupFailure.suppressed.single().message)
            assertEquals(1, constructions)
            assertEquals(1, configurations)
            assertEquals(1, starts)
            assertEquals(1, closes)
        }
}
