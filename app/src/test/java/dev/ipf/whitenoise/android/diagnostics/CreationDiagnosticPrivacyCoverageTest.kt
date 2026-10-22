package dev.ipf.whitenoise.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class CreationDiagnosticPrivacyCoverageTest {
    @Test
    fun groupFailuresReachReleaseLogWithoutPrivateRecordingOrExceptionText() {
        DmCreationDiagnostics.setEnabled(false)
        ShadowLog.clear()
        val attempt = DmCreationInteraction(operation = CreationOperation.GROUP_INVITE).nextAttempt()
        attempt.record(DmCreationPhase.CREATE, DmCreationOutcome.START)
        attempt.failed(DmCreationPhase.CREATE, IllegalStateException("npub-secret wss://private.example payload"))
        val records = ShadowLog.getLogsForTag("GroupCreation")
        assertEquals(2, records.size)
        assertTrue(records.last().msg.contains("operation=group_invite"))
        assertTrue(records.last().msg.contains("failure=unknown"))
        assertFalse(records.last().msg.contains("private.example"))
        assertFalse(records.last().msg.contains("npub-secret"))
        assertNull(records.last().throwable)
    }

    @Test
    fun anAllowlistedFieldNameCannotSmugglePrivateText() {
        val records = mutableListOf<Map<String, Any>>()
        val attempt = DmCreationInteraction(records::add).nextAttempt()
        attempt.record(DmCreationPhase.CREATE, DmCreationOutcome.START)
        val valid = records.single()
        assertTrue(creationDiagnosticFields(valid) != null)
        for (field in listOf("operation", "phase", "outcome", "failure", "interaction", "attempt", "elapsed_ms")) {
            assertNull(creationDiagnosticFields(valid + (field to "npub-private\nsecret")))
        }
    }

    @Test
    fun attemptOwnershipAndMonotonicPhaseDurationSurviveOverlappingCalls() {
        var time = 0L
        val records = mutableListOf<Map<String, Any>>()
        val interaction = DmCreationInteraction(records::add, { time }, CreationOperation.GROUP_CREATE)
        val first = interaction.nextAttempt()
        val second = interaction.nextAttempt()
        first.record(DmCreationPhase.CREATE, DmCreationOutcome.START)
        time = 5_000_000L
        second.record(DmCreationPhase.CREATE, DmCreationOutcome.START)
        time = 9_000_000L
        first.record(DmCreationPhase.CREATE, DmCreationOutcome.SUCCESS)
        assertEquals(9L, records.last()["phase_duration_ms"])
        assertEquals(1L, records.last()["attempt"])
        assertEquals("group_create", records.last()["operation"])
    }
}
