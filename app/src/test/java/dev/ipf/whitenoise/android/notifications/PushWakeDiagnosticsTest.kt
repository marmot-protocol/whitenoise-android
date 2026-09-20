package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PushWakeDiagnosticsTest {
    /** Starts each callback test with an empty, explicitly opted-in process session. */
    @Before
    fun startFreshSession() {
        PushWakeDiagnostics.complete()
        PerformanceDiagnostics.stop()
        assertTrue(PerformanceDiagnostics.start().active)
    }

    /** Releases singleton callback and diagnostic state after every assertion. */
    @After
    fun stopSession() {
        PushWakeDiagnostics.complete()
        PerformanceDiagnostics.stop()
    }

    /** Coalesced work fans out to a distinct anonymous trace for every callback. */
    @Test
    fun callbacksRetainDistinctCorrelationThroughPosting() {
        PushWakeDiagnostics.received(PushWakePriority.High, PushWakePriority.Normal, deleted = false)
        PushWakeDiagnostics.received(PushWakePriority.Normal, PushWakePriority.High, deleted = false)
        PushWakeDiagnostics.event(PushWakeEvent.AttemptStarted)
        PushWakeDiagnostics.event(PushWakeEvent.Candidate)
        PushWakeDiagnostics.event(PushWakeEvent.Posted)
        PushWakeDiagnostics.complete()

        val lines = PerformanceDiagnostics.exportLines()
        val byRecovery = lines.groupBy(::recoveryToken)
        assertEquals(2, byRecovery.keys.filterNotNull().size)
        byRecovery.filterKeys { it != null }.values.forEach { callbackLines ->
            assertEquals(5, callbackLines.size)
            assertTrue(callbackLines.any { "phase=push_candidate" in it && "count=2" in it })
            assertTrue(callbackLines.any { "phase=push_posted" in it })
            assertFalse(callbackLines.any { "phase=push_incomplete" in it })
        }
    }

    /** A callback arriving after work began stays pending and cannot inherit that work's success. */
    @Test
    fun lateCallbackDoesNotInheritAttemptCompletion() {
        PushWakeDiagnostics.received(PushWakePriority.High, PushWakePriority.High, deleted = false)
        PushWakeDiagnostics.event(PushWakeEvent.AttemptStarted)
        PushWakeDiagnostics.received(PushWakePriority.Normal, PushWakePriority.Normal, deleted = false)
        val lateToken = recoveryToken(PerformanceDiagnostics.exportLines().last())

        PushWakeDiagnostics.event(PushWakeEvent.AttemptSucceeded)
        PushWakeDiagnostics.event(PushWakeEvent.Posted)
        PushWakeDiagnostics.complete()

        val lateLines = PerformanceDiagnostics.exportLines().filter { recoveryToken(it) == lateToken }
        assertFalse(lateLines.any { "phase=push_attempt_succeeded" in it })
        assertFalse(lateLines.any { "phase=push_posted" in it })
        assertTrue(lateLines.any { "phase=push_incomplete" in it && "result=pending" in it })
    }

    /** A supervisor failure before runtime admission still belongs to callbacks already waiting for recovery. */
    @Test
    fun preAdmissionFailureClaimsCurrentCallbacks() {
        PushWakeDiagnostics.received(PushWakePriority.High, PushWakePriority.High, deleted = false)

        PushWakeDiagnostics.event(PushWakeEvent.AttemptFailed)
        PushWakeDiagnostics.complete()

        val lines = PerformanceDiagnostics.exportLines()
        assertTrue(lines.any { "phase=push_attempt_failed" in it && "result=failure" in it })
    }

    /** A callback without a posted or suppressed outcome closes with an explicit incomplete phase. */
    @Test
    fun missingPostingOutcomeIsMarkedIncomplete() {
        PushWakeDiagnostics.received(PushWakePriority.Unknown, PushWakePriority.Unknown, deleted = true)
        PushWakeDiagnostics.event(PushWakeEvent.Scheduled)
        PushWakeDiagnostics.complete()

        val lines = PerformanceDiagnostics.exportLines()
        assertTrue(lines.any { "phase=push_deleted" in it })
        assertTrue(lines.any { "phase=push_scheduled" in it })
        assertTrue(lines.any { "phase=push_incomplete" in it && "result=pending" in it })
    }

    /** Exported callback evidence contains only closed schema labels and bounded numbers. */
    @Test
    fun exportedCorrelationContainsNoMessageOrIdentityData() {
        PushWakeDiagnostics.received(PushWakePriority.High, PushWakePriority.High, deleted = false)
        PushWakeDiagnostics.event(PushWakeEvent.Suppressed)
        PushWakeDiagnostics.complete()

        val export = PerformanceDiagnostics.exportLines().joinToString("\n")
        listOf("payload=", "account=", "token=", "message=", "group=", "relay=").forEach { denied ->
            assertFalse(denied, export.contains(denied))
        }
    }

    /** Extracts only the anonymous recovery label from one closed-schema line. */
    private fun recoveryToken(line: String): String? =
        Regex("(?:^| )recovery=(r#[0-9]+)(?: |$)")
            .find(line)
            ?.groupValues
            ?.get(1)
}
