package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.DiagnosticsExporterStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.RelayTelemetrySettingsFfi
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.marmotkit.UsageDiagnosticsSettingsFfi
import dev.ipf.marmotkit.UsageDiagnosticsStatusFfi
import dev.ipf.whitenoise.android.ui.settings.StartupDiagnosticsPromptState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Behavioral receipt projection tests; the fake controls persistence failure and exporter capability.
 * Robolectric supplies `android.util.Log` for the controller's debug receipt line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UsageDiagnosticsControllerTest {
    /** A replacement Android projection reads the native decision without creating another grant. */
    @Test
    fun replacementControllerPreservesGrantedAndDeclinedNativeDecisions() =
        runBlocking {
            for (enabled in listOf(true, false)) {
                val native = NativeReceipt()
                assertTrue(UsageDiagnosticsController().choose(native.engine, enabled))
                repeat(3) {
                    val reopened = UsageDiagnosticsController()
                    reopened.refresh(native.engine)
                    assertFalse(reopened.requiresChoice)
                    assertEquals(enabled, reopened.granted)
                    assertEquals(1, native.writes)
                }
            }
        }

    /** Slow grants and revocations keep the last receipt visible while admitting no host observations. */
    @Test
    fun pendingSaveKeepsPresentationStableWithoutGrantingCollection() =
        runBlocking {
            val native = NativeReceipt()
            val state = UsageDiagnosticsController()
            state.setForeground(true)
            state.refresh(native.engine)
            for (enabled in listOf(true, false)) {
                val previous = state.snapshot
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                native.beforeWrite = {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
                val save = async { state.choose(native.engine, enabled) }
                try {
                    withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                    assertSame(previous, state.snapshot)
                    assertEquals(enabled, state.selected)
                    assertFalse(state.granted)
                    state.setForeground(true)
                    assertNull(state.observations.ticket())
                } finally {
                    release.countDown()
                }
                assertTrue(save.await())
                assertEquals(enabled, state.granted)
            }
        }

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

    /**
     * Reopening a screen re-reads the receipt, and a settled row must not present that read as
     * work: swapping the switch for an indicator and dimming its text is what makes the page look
     * like it assembles itself in front of the reader.
     */
    @Test
    fun passiveRereadLeavesASettledRowSettled() =
        runBlocking {
            val native = NativeReceipt()
            val state = UsageDiagnosticsController()
            state.refresh(native.engine)
            val settled = state.snapshot
            assertNotNull(settled)

            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            native.beforeRead = {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
            }
            val reread = async { state.refresh(native.engine) }
            try {
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                assertFalse("a re-read must not present a settled row as busy", state.busy)
                assertSame(settled, state.snapshot)
            } finally {
                release.countDown()
            }
            reread.await()
            assertFalse(state.busy)
        }

    /** With no receipt to present yet, the first read does show work rather than a settled control. */
    @Test
    fun firstReadPresentsWorkUntilAReceiptArrives() =
        runBlocking {
            val native = NativeReceipt()
            val state = UsageDiagnosticsController()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            native.beforeRead = {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
            }
            val load = async { state.refresh(native.engine) }
            try {
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                assertNull(state.snapshot)
                assertTrue("the first read has nothing to present yet", state.busy)
            } finally {
                release.countDown()
            }
            load.await()
            assertFalse(state.busy)
        }

    /** A choice the reader just made is still presented as work, settled receipt or not. */
    @Test
    fun writingAChoicePresentsWorkEvenOnASettledRow() =
        runBlocking {
            val native = NativeReceipt()
            val state = UsageDiagnosticsController()
            state.refresh(native.engine)
            assertNotNull(state.snapshot)

            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            native.beforeWrite = {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
            }
            val save = async { state.choose(native.engine, true) }
            try {
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                assertTrue(state.busy)
            } finally {
                release.countDown()
            }
            assertTrue(save.await())
            assertFalse(state.busy)
        }

    /**
     * An ordinary update or process restart keeps a saved choice (#2820): an unchanged granted or declined
     * receipt opens no startup sheet, and only MDK's own renewal verdict asks again.
     */
    @Test
    fun updateWithUnchangedReceiptOpensNoStartupSheetAndKeepsTheChoice() =
        runBlocking {
            for (enabled in listOf(true, false)) {
                val native = NativeReceipt()
                assertTrue(UsageDiagnosticsController().choose(native.engine, enabled))
                val updated = UsageDiagnosticsController()
                updated.refresh(native.engine)
                assertEquals(enabled, updated.granted)
                assertFalse("a declined user is never opted in by an update", !enabled && updated.selected)
                assertEquals(StartupDiagnosticsPromptState.CLOSED, startupPrompt(updated))
                assertEquals(1, native.writes)

                native.decision = UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED
                val renewed = UsageDiagnosticsController()
                renewed.refresh(native.engine)
                assertFalse(renewed.granted)
                assertEquals(StartupDiagnosticsPromptState.CHOICE, startupPrompt(renewed))
            }
        }

    /** A failed receipt read after an update is a visible retry, never a granted or settled receipt. */
    @Test
    fun failedReadAfterUpdateStaysRetryableUntilTheReceiptLoads() =
        runBlocking {
            val native = NativeReceipt()
            assertTrue(UsageDiagnosticsController().choose(native.engine, true))
            native.fail = true
            val updated = UsageDiagnosticsController()
            updated.refresh(native.engine)
            assertTrue(updated.failed)
            assertFalse(updated.granted)
            assertNull(updated.observations.ticket())
            val prompt = startupPrompt(updated)
            assertEquals(StartupDiagnosticsPromptState.READ_ERROR, prompt)
            native.fail = false
            updated.retry(native.engine)
            assertTrue(updated.granted)
            assertEquals(
                StartupDiagnosticsPromptState.CLOSED,
                prompt.refreshed(decisionLoaded = true, choiceRequired = updated.requiresChoice, readFailed = false),
            )
        }

    /** The upgrade-repro debug line carries the decision and short revision hashes, and nothing else. */
    @Test
    fun receiptLogCarriesOnlyDecisionAndShortRevisions() =
        runBlocking {
            val native = NativeReceipt()
            native.registryRevision = "0123456789abcdef".repeat(4)
            val state = UsageDiagnosticsController()
            state.refresh(native.engine)
            assertEquals(
                "usage_diagnostics_receipt op=read result=ok decision=ACCEPTANCE_REQUIRED " +
                    "policy=policy registry=0123456789ab exporter=READY",
                usageDiagnosticsReceiptLog(write = false, snapshot = state.snapshot),
            )
            assertEquals("usage_diagnostics_receipt op=write result=failed", usageDiagnosticsReceiptLog(true, null))
        }

    /** Replays the production startup prompt decision for one freshly loaded receipt projection. */
    private fun startupPrompt(state: UsageDiagnosticsController) =
        StartupDiagnosticsPromptState.AWAITING_RECEIPT.refreshed(
            decisionLoaded = state.snapshot != null || state.failed,
            choiceRequired = state.requiresChoice,
            readFailed = state.failed,
        )

    /** Tiny native boundary with independently controllable settings and delivery capability. */
    private class NativeReceipt {
        var writes = 0
        var fail = false
        var configured = true
        var beforeWrite: () -> Unit = {}
        var beforeRead: () -> Unit = {}
        var decision = UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED
        var registryRevision = "registry"
        val engine =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { _, method, args ->
                check(!fail) { "synthetic persistence failure" }
                when (method.name) {
                    "setUsageDiagnosticsConsent" -> {
                        beforeWrite()
                        writes++
                        decision =
                            if (args!![0] == true) {
                                UsageDiagnosticsDecisionFfi.GRANTED
                            } else {
                                UsageDiagnosticsDecisionFfi.DECLINED
                            }
                        settings()
                    }
                    "usageDiagnosticsSettings" -> {
                        beforeRead()
                        settings()
                    }
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
        private fun settings() = UsageDiagnosticsSettingsFfi(decision, "policy", registryRevision, 0L, false)
    }
}
