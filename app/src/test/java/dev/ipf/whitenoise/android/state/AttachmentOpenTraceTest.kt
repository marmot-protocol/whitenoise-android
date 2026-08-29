package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentOpenTraceTest {
    @Test
    fun phasesAreOrderedAndPrivacySafe() {
        var now = 1_000L
        val lines = mutableListOf<String>()
        val registry = AttachmentOpenTraceRegistry(nowMs = { now }, emit = lines::add)

        registry.begin(OPEN_REQUEST)
        now += 25L
        registry.phase(OPEN_REQUEST, AttachmentOpenPhase.RequestPersisted)
        now += 75L
        registry.finish(OPEN_REQUEST, "opened")

        assertEquals(listOf("tap", "request_persisted", "terminal_result"), lines.map(::phase))
        assertTrue(lines.last().contains("elapsed_ms=100"))
        assertTrue(lines.last().contains("outcome=opened"))
        val output = lines.joinToString("\n")
        assertFalse(output.contains(OPEN_REQUEST.transferRequest.accountRef))
        assertFalse(output.contains(OPEN_REQUEST.transferRequest.groupIdHex))
        assertFalse(output.contains(OPEN_REQUEST.transferRequest.messageIdHex))
    }

    @Test
    fun navigationCancelsOnlyTheSupersededSession() {
        val lines = mutableListOf<String>()
        val registry = AttachmentOpenTraceRegistry(nowMs = { 1_000L }, emit = lines::add)
        val nextSession = OPEN_REQUEST.copy(navigationGeneration = 8L)
        registry.begin(OPEN_REQUEST)
        registry.begin(nextSession)

        registry.cancelOutside(nextSession.destination)

        assertEquals(1, lines.count { phase(it) == "navigation_cancelled" })
        registry.phase(nextSession, AttachmentOpenPhase.VisibilityEligibility, "eligible")
        assertFalse(lines.last().contains("recovered=true"))
    }

    private fun phase(line: String): String = line.substringAfter("phase=").substringBefore(' ')

    private companion object {
        val OPEN_REQUEST =
            AttachmentOpenRequest(
                transferRequest =
                    AttachmentTransferRequest(
                        accountRef = "private-account-label",
                        groupIdHex = "ab".repeat(32),
                        messageIdHex = "cd".repeat(32),
                        attachmentIndex = 0,
                    ),
                navigationGeneration = 7L,
            )
    }
}
