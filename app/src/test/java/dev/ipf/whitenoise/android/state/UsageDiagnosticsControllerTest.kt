package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.DiagnosticsExporterStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.RelayTelemetrySettingsFfi
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.marmotkit.UsageDiagnosticsSettingsFfi
import dev.ipf.marmotkit.UsageDiagnosticsStatusFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Behavioral receipt projection tests; the fake controls persistence failure and exporter capability. */
class UsageDiagnosticsControllerTest {
    /** A missing read or rejected write never displays a previous grant as a confirmed current decision. */
    @Test
    fun failureDisablesObservationUntilAnExplicitSuccessfulRetry() =
        runBlocking {
            val native = NativeReceipt()
            val state = UsageDiagnosticsController()
            state.setForeground(true)
            state.refresh(native.engine)
            assertTrue(state.requiresChoice)
            assertNull(state.observations.ticket())
            assertTrue(state.choose(native.engine, true))
            assertTrue(state.granted)
            assertNotNull(state.observations.ticket())
            native.fail = true
            assertFalse(state.choose(native.engine, false))
            assertTrue(state.failed)
            assertFalse(state.granted)
            assertNull(state.snapshot)
            assertNull(state.observations.ticket())
            native.fail = false
            state.retry(native.engine)
            assertFalse(state.failed)
            assertFalse(state.requiresChoice)
            assertFalse(state.granted)
        }

    /** Export requires both a configured exporter and current foreground permission. */
    @Test
    fun unconfiguredBackgroundAndReplacementRuntimeStaySilent() =
        runBlocking {
            val native = NativeReceipt()
            val state = UsageDiagnosticsController()
            native.configured = false
            state.setForeground(true)
            state.choose(native.engine, true)
            assertTrue(state.granted)
            assertNull(state.observations.ticket())
            native.configured = true
            state.refresh(native.engine)
            assertNotNull(state.observations.ticket())
            val ticket = state.observations.ticket()
            state.setForeground(false)
            assertNull(state.observations.ticket())
            state.setForeground(true)
            var recorded = false
            state.observations.record(ticket) { recorded = true }
            assertFalse(recorded)
            state.bind(NativeReceipt().engine)
            assertNull(state.snapshot)
            assertNull(state.observations.ticket())
        }

    /** Recovery refresh restores foreground collection without admitting pre-wipe observation tickets. */
    @Test
    fun foregroundRecoveryRefreshRetiresOldTicketsAndRestoresNewObservations() =
        runBlocking {
            val native = NativeReceipt()
            val state = UsageDiagnosticsController()
            state.setForeground(true)
            state.choose(native.engine, true)
            val oldTicket = state.observations.ticket()
            assertNotNull(oldTicket)
            state.observations.reset()
            assertNull(state.observations.ticket())
            state.refresh(native.engine)
            var staleRecorded = false
            state.observations.record(oldTicket) { staleRecorded = true }
            assertFalse(staleRecorded)
            var currentRecorded = false
            state.observations.record(state.observations.ticket()) { currentRecorded = true }
            assertTrue(currentRecorded)
        }

    /** Failed initial loading can be retried without storing Android-owned receipt state. */
    @Test
    fun initialReadFailureIsRetryable() =
        runBlocking {
            val native = NativeReceipt()
            native.fail = true
            val state = UsageDiagnosticsController()
            state.refresh(native.engine)
            assertTrue(state.failed)
            assertFalse(state.busy)
            native.fail = false
            state.refresh(native.engine)
            assertFalse(state.failed)
            assertTrue(state.requiresChoice)
        }

    /** Tiny native boundary with independently controllable settings and delivery capability. */
    private class NativeReceipt {
        var fail = false
        var configured = true
        private var decision = UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED
        val engine =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { _, method, args ->
                check(!fail) { "synthetic persistence failure" }
                when (method.name) {
                    "setUsageDiagnosticsConsent" -> {
                        decision =
                            if (args!![0] == true) {
                                UsageDiagnosticsDecisionFfi.GRANTED
                            } else {
                                UsageDiagnosticsDecisionFfi.DECLINED
                            }
                        settings()
                    }
                    "usageDiagnosticsSettings" -> settings()
                    "usageDiagnosticsStatus" ->
                        UsageDiagnosticsStatusFfi(
                            decision,
                            DiagnosticsExporterStatusFfi.UNCONFIGURED,
                            if (configured) {
                                DiagnosticsExporterStatusFfi.READY
                            } else {
                                DiagnosticsExporterStatusFfi.UNCONFIGURED
                            },
                            0uL,
                            0uL,
                            0uL,
                            0uL,
                        )
                    "relayTelemetrySettings" -> RelayTelemetrySettingsFfi(false, 60uL)
                    "setProductAnalyticsActivity" -> Unit
                    else -> error("Unexpected native call: ${method.name}")
                }
            } as MarmotInterface

        /** Returns a native-shaped receipt without exposing test identity or content. */
        private fun settings() = UsageDiagnosticsSettingsFfi(decision, "policy", "registry", 0L, false)
    }
}
