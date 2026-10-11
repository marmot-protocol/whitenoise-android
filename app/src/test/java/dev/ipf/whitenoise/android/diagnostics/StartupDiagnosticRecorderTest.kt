package dev.ipf.whitenoise.android.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stalled native work remains observable independently of timeout, retries and persistence failures. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StartupDiagnosticRecorderTest {
    /** A native call that never returns still has an entry and remains the same operation after Retry. */
    @Test fun heldConstructionReportsItsInnermostStageWithoutCancellingOrDuplicatingWork() =
        runTest {
            var clock = 0L
            val recorder = StartupDiagnosticRecorder({ clock })
            val held = CompletableDeferred<Unit>()
            var calls = 0
            recorder.beginAttempt()
            val work =
                launch {
                    recorder.stage(StartupDiagnosticStage.CLIENT_CONSTRUCTION) {
                        recorder.stage(StartupDiagnosticStage.NATIVE_CONSTRUCT) {
                            calls++
                            held.await()
                        }
                    }
                }
            runCurrent()
            clock = 15_000L
            repeat(3) {
                val detail = recorder.timeoutDetail()
                assertTrue(detail.contains("stage=NATIVE_CONSTRUCT stage_elapsed_ms=15000"))
                assertTrue(detail.contains("attempt=1 attempt_elapsed_ms=15000 active_count=2"))
                assertTrue(work.isActive)
            }
            assertEquals(1, calls)
            held.complete(Unit)
            work.join()
            assertTrue(recorder.snapshot().contains("stage=BETWEEN_STAGES"))
            assertTrue(recorder.snapshot().contains("active_count=0"))
        }

    /** Native start can be distinguished from construction even when its FFI call is still suspended. */
    @Test fun heldNativeStartRemainsObservableAndSuccessfulCompletionClosesIt() =
        runTest {
            val recorder = StartupDiagnosticRecorder({ testScheduler.currentTime })
            val held = CompletableDeferred<Unit>()
            recorder.beginAttempt()
            val work = launch { recorder.stage(StartupDiagnosticStage.MARMOT_START) { held.await() } }
            runCurrent()
            assertTrue(recorder.timeoutDetail().contains("stage=MARMOT_START"))
            held.complete(Unit)
            work.join()
            assertTrue(recorder.snapshot().contains("result=SUCCESS"))
            assertTrue(recorder.snapshot().contains("active_count=0"))
        }

    /** An older concurrent completion cannot erase the newer active stage. */
    @Test fun concurrentStagesHaveIndependentTokens() {
        var clock = 0L
        val recorder = StartupDiagnosticRecorder({ clock })
        recorder.beginAttempt()
        val first = recorder.enter(StartupDiagnosticStage.MARMOT_START)
        clock = 5L
        val second = recorder.enter(StartupDiagnosticStage.ACCOUNT_REFRESH)
        clock = 12L
        recorder.exit(first, PerformanceResult.SUCCESS)
        assertTrue(recorder.timeoutDetail().contains("stage=ACCOUNT_REFRESH stage_elapsed_ms=7"))
        recorder.exit(second, PerformanceResult.SUCCESS)
        assertTrue(recorder.timeoutDetail().contains("stage=BETWEEN_STAGES"))
    }

    /** Native exceptions propagate unchanged; only the innermost fixed stage survives the unwind. */
    @Test fun failedStageSurvivesUnwindWithoutRetainingSensitiveExceptionText() {
        val recorder = StartupDiagnosticRecorder({ 0L })
        val secret = "nsec1-secret /private/database.db https://private.example/token"
        val error = IllegalStateException(secret)
        recorder.beginAttempt()
        val actual =
            runCatching {
                recorder.measure(StartupDiagnosticStage.CLIENT_CONSTRUCTION) {
                    recorder.measure(StartupDiagnosticStage.NATIVE_CONSTRUCT) { throw error }
                }
            }.exceptionOrNull()
        assertSame(error, actual)
        assertTrue(recorder.failureDetail().contains("failed_stage=NATIVE_CONSTRUCT"))
        assertFalse(recorder.snapshot().contains(secret))
        assertFalse(recorder.snapshot().contains("IllegalStateException"))
        recorder.beginAttempt()
        assertFalse(recorder.snapshot().contains("failed_stage="))
    }

    /** Writer faults are diagnostic failures, never application failures or barriers to memory export. */
    @Test fun brokenSinkCannotChangeNativeResultOrPreventSnapshot() {
        val recorder = StartupDiagnosticRecorder({ 0L }) { error("disk unavailable") }
        recorder.beginAttempt()
        assertEquals(42, recorder.measure(StartupDiagnosticStage.NATIVE_INITIALIZE) { 42 })
        assertTrue(recorder.snapshot().contains("result=SUCCESS"))
        assertFalse(recorder.snapshot().contains("disk unavailable"))
    }

    /** Repeated user actions and stages stay within the event and export ceilings. */
    @Test fun retryAndStageHistoryRemainBounded() {
        val recorder = StartupDiagnosticRecorder({ 1L })
        recorder.beginAttempt()
        repeat(300) {
            recorder.measure(StartupDiagnosticStage.ACCOUNT_REFRESH) { Unit }
            recorder.timeoutDetail()
        }
        val snapshot = recorder.snapshot()
        val eventCount = snapshot.lineSequence().count { it.startsWith("elapsed_ms=") }
        assertEquals(StartupDiagnosticRecorder.MAX_EVENTS, eventCount)
        assertTrue(snapshot.toByteArray().size < MAX_STARTUP_REPORT_BYTES)
        assertTrue(snapshot.contains("attempt=1"))
    }
}
