package dev.ipf.whitenoise.android.diagnostics

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fault injection for the independent export writer, without native startup or a real receiver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StartupDiagnosticExportCoordinatorTest {
    /** A stuck disk cannot stall the screen or turn repeated taps into more writers. */
    @Test fun timedOutWriterKeepsOwnershipUntilItReallyFinishes() =
        runTest {
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val exporter = StartupDiagnosticExportCoordinator(owner, waitMs = 50L)
            val held = CompletableDeferred<Unit>()
            var result: StartupDiagnosticExportResult? = null
            try {
                val caller =
                    launch {
                        result =
                            exporter.export {
                                held.await()
                                Intent(Intent.ACTION_SEND)
                            }
                    }
                runCurrent()
                advanceTimeBy(50L)
                runCurrent()
                assertTrue(caller.isCompleted)
                assertEquals(StartupDiagnosticExportResult.Failed, result)
                assertEquals(StartupDiagnosticExportResult.Busy, exporter.export { error("duplicate writer") })
                held.complete(Unit)
                runCurrent()
                assertTrue(exporter.export { Intent(Intent.ACTION_SEND) } is StartupDiagnosticExportResult.Ready)
            } finally {
                owner.cancel()
            }
        }

    /** A failed file operation releases ownership and carries no sensitive throwable into UI state. */
    @Test fun failedWriterCanBeRetriedWithoutLeakingItsException() =
        runTest {
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val exporter = StartupDiagnosticExportCoordinator(owner)
            try {
                val result = exporter.export { error("/private/path nsec1-sensitive") }
                assertEquals(StartupDiagnosticExportResult.Failed, result)
                assertTrue(exporter.export { Intent(Intent.ACTION_SEND) } is StartupDiagnosticExportResult.Ready)
            } finally {
                owner.cancel()
            }
        }
}
