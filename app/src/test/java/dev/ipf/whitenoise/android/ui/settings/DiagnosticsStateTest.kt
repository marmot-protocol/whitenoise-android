package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnosticStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsStateTest {
    @Test
    fun emptyDiagnosticsStateShowsAllSectionsAndEmptyPlaceholders() {
        val state =
            diagnosticsState(
                relayHealth = null,
                activeAccountRef = null,
                accountCount = 0,
                bootstrapRelayCount = 0,
                eventCount = 0,
                streaming = false,
                sendingPing = false,
                performanceStatus = PerformanceDiagnosticStatus.Unavailable,
            )

        assertEquals(
            listOf(
                DiagnosticsSection.Actions,
                DiagnosticsSection.RelayHealth,
                DiagnosticsSection.Runtime,
                DiagnosticsSection.EventLog,
            ),
            state.sections,
        )
        assertTrue(state.showRelayHealthEmptyState)
        assertTrue(state.showEventLogEmptyState)
        assertFalse(state.sendToSelfEnabled)
        assertEquals(DiagnosticsStreamStatus.Idle, state.streamStatus)
        assertEquals(
            listOf(
                DiagnosticValue(DiagnosticValueKey.ActiveAccount, null),
                DiagnosticValue(DiagnosticValueKey.Accounts, "0"),
                DiagnosticValue(DiagnosticValueKey.BootstrapRelays, "0"),
            ),
            state.runtimeValues,
        )
    }

    @Test
    fun relaySnapshotFormatsCountsAndAccountStateForDisplay() {
        val state =
            diagnosticsState(
                relayHealth =
                    DiagnosticsRelayHealth(
                        total = 5u,
                        connected = 3u,
                        connecting = 1u,
                        disconnected = 1u,
                        attempts = 8u,
                        successes = 6u,
                    ),
                activeAccountRef = "personal",
                accountCount = 2,
                bootstrapRelayCount = 4,
                eventCount = 7,
                streaming = true,
                sendingPing = false,
                performanceStatus = PerformanceDiagnosticStatus.Unavailable,
            )

        assertFalse(state.showRelayHealthEmptyState)
        assertFalse(state.showEventLogEmptyState)
        assertTrue(state.sendToSelfEnabled)
        assertEquals(DiagnosticsStreamStatus.Live, state.streamStatus)
        assertEquals(
            listOf("5", "3", "1", "1", "8", "6"),
            state.relayHealthValues.map { it.value },
        )
        assertEquals(listOf("personal", "2", "4"), state.runtimeValues.map { it.value })
    }

    @Test
    fun availablePerformanceDiagnosticsShowsLocalOptInAndStatus() {
        val performanceStatus =
            PerformanceDiagnosticStatus(
                available = true,
                active = true,
                remainingMillis = 60_000L,
                emittedCount = 3,
                droppedCount = 0,
            )

        val state =
            diagnosticsState(
                relayHealth = null,
                activeAccountRef = null,
                accountCount = 0,
                bootstrapRelayCount = 0,
                eventCount = 0,
                streaming = false,
                sendingPing = false,
                performanceStatus = performanceStatus,
            )

        assertTrue(DiagnosticsSection.Performance in state.sections)
        assertEquals(performanceStatus, state.performanceStatus)
    }
}
