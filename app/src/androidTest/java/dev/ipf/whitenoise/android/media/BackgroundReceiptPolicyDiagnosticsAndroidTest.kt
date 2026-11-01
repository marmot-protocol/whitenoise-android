package dev.ipf.whitenoise.android.media

import android.content.Context
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.state.AutomaticAttachmentPolicy
import dev.ipf.whitenoise.android.state.AutomaticPolicyAccount
import dev.ipf.whitenoise.android.state.MediaAutoDownloadMatrix
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the fixture's failure boundary without a native runtime, network change or installed account. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
class BackgroundReceiptPolicyDiagnosticsAndroidTest {
    @Test
    fun wifiShellDiagnosticsRetainOnlyClosedResults() {
        assertEquals(FixtureWifiRadioState.Enabled, fixtureWifiRadioState("Wifi is enabled"))
        assertEquals(FixtureWifiRadioState.Disabled, fixtureWifiRadioState("Wifi is disabled"))
        assertEquals(FixtureWifiRadioState.Unavailable, fixtureWifiRadioState("private-ssid private-bssid"))
        val summary =
            FixtureWifiCommandSnapshot(
                FixtureWifiCommandResult.Unavailable,
                fixtureWifiRadioState("private-ssid"),
            ).toString()
        assertFalse(summary.contains("private-"))
        assertTrue(summary.contains("Unavailable"))
    }

    @Test
    fun registeredWifiObservationExportsOnlyClosedCapabilityBooleans() {
        val facts = backgroundReceiptRegisteredWifiFacts(ApplicationProvider.getApplicationContext<Context>())
        assertEquals(5, facts.length())
        val expected =
            setOf(
                "platform_network_inventory_available",
                "platform_network_inventory_complete",
                "platform_registered_wifi_present",
                "platform_registered_wifi_validated",
                "platform_registered_wifi_unmetered",
            )
        assertEquals(expected, facts.keys().asSequence().toSet())
        facts.keys().forEach { key -> assertTrue(facts.get(key) is Boolean) }
    }

    @Test
    fun disappearingNetworkRetainsOtherWifiCapabilitiesAndMarksTheReadIncomplete() {
        val wifi =
            NetworkCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val facts = backgroundReceiptRegisteredWifiFacts { listOf(null, wifi) }
        assertTrue(facts.getBoolean("platform_network_inventory_available"))
        assertFalse(facts.getBoolean("platform_network_inventory_complete"))
        assertTrue(facts.getBoolean("platform_registered_wifi_present"))
        assertTrue(facts.getBoolean("platform_registered_wifi_validated"))
        assertTrue(facts.getBoolean("platform_registered_wifi_unmetered"))
        val unavailable = backgroundReceiptRegisteredWifiFacts { error("private-network") }
        assertFalse(unavailable.getBoolean("platform_network_inventory_available"))
        assertFalse(unavailable.getBoolean("platform_network_inventory_complete"))
        assertFalse(unavailable.toString().contains("private-network"))
    }

    /** Every wait reports its exact case/stage and current closed facts, even when no coroutine frames survive. */
    @Test
    fun timeoutReportsEachStageAndRethrowsTheOriginalFailure() =
        runBlocking {
            val timeout = timeout()
            val cases =
                BackgroundReceiptPolicyCase.entries.flatMap { case ->
                    BackgroundReceiptPolicyStage.entries.map { case to it }
                }
            cases.forEach { (case, stage) ->
                val reports = mutableListOf<JSONObject>()
                val failure =
                    runCatching {
                        withBackgroundReceiptPolicyDiagnostics(case, stage, ::snapshot, { reports += it }) {
                            throw timeout
                        }
                    }.exceptionOrNull()
                assertSame(timeout, failure)
                val report = reports.single()
                assertEquals("background-receipt-policy-timeout", report.getString("phase"))
                assertEquals(case.name, report.getString("case"))
                assertEquals(stage.name, report.getString("stage"))
                assertEquals("[]", report.getJSONArray("allowed_types").toString())
                assertEquals("[\"Video\"]", report.getJSONArray("expected_types").toString())
                assertEquals("[\"Video\"]", report.getJSONArray("wifi_only_types").toString())
                assertEquals("[\"WiFi\",\"Metered\"]", report.getJSONArray("networks").toString())
                assertEquals("SignedIn", report.getString("account_state"))
                assertTrue(report.getBoolean("validated_internet"))
                assertFalse(report.getBoolean("paused"))
                assertFalse(report.getBoolean("approval_applied"))
                assertEquals(11, report.length())
            }
        }

    /** A successful wait returns its result without invoking the snapshot or reporter. */
    @Test
    fun successfulWaitDoesNotCollectDiagnostics() =
        runBlocking {
            val result =
                withBackgroundReceiptPolicyDiagnostics(
                    BackgroundReceiptPolicyCase.NetworkHeld,
                    BackgroundReceiptPolicyStage.HostPolicy,
                    { error("snapshot must not run") },
                    { error("report must not run") },
                ) { 42 }
            assertEquals(42, result)
        }

    /** Cancellation and assertions retain their original meaning instead of being mislabeled as a timeout. */
    @Test
    fun otherFailuresDoNotCollectDiagnostics() =
        runBlocking {
            listOf(CancellationException("private-account"), AssertionError("private-url")).forEach { original ->
                val failure =
                    runCatching {
                        withBackgroundReceiptPolicyDiagnostics(
                            BackgroundReceiptPolicyCase.DisabledType,
                            BackgroundReceiptPolicyStage.NativeApproval,
                            { error("snapshot must not run") },
                            { error("report must not run") },
                        ) { throw original }
                    }.exceptionOrNull()
                assertSame(original, failure)
            }
        }

    /** A failing snapshot or status channel must not mask the timeout that made the fixture fail. */
    @Test
    fun diagnosticFailuresCannotReplaceTheOriginalTimeout() =
        runBlocking {
            val timeout = timeout()
            listOf(true, false).forEach { failSnapshot ->
                val failure =
                    runCatching {
                        withBackgroundReceiptPolicyDiagnostics(
                            BackgroundReceiptPolicyCase.ProcessStop,
                            BackgroundReceiptPolicyStage.HostPolicy,
                            { if (failSnapshot) error("private-snapshot") else snapshot() },
                            { error("private-reporter") },
                        ) { throw timeout }
                    }.exceptionOrNull()
                assertSame(timeout, failure)
            }
        }

    /** Each process-stop phase returns its original result and records elapsed monotonic time independently. */
    @Test
    fun processStopStagesKeepOriginalOutcomeAndTiming() =
        runBlocking {
            BackgroundReceiptProcessStopStage.entries.forEach { stage ->
                var now = 100L
                val reports = mutableListOf<JSONObject>()
                val diagnostics = BackgroundReceiptProcessStopDiagnostics(::snapshot, { reports += it }, { now })
                val result =
                    diagnostics.trace(stage) {
                        now = 125L
                        42
                    }
                assertEquals(42, result)
                assertEquals(listOf("entry", "complete"), reports.map { it.getString("event") })
                assertEquals(listOf(0L, 25L), reports.map { it.getLong("elapsed_ms") })
                reports.forEach { report ->
                    assertEquals("background-receipt-process-stop", report.getString("phase"))
                    assertEquals(stage.name, report.getString("stage"))
                    assertEquals(0, report.getInt("work_observations"))
                    assertTrue(report.isNull("work_observed_at_ms"))
                    assertEquals(0, report.getInt("ledger_observations"))
                    assertTrue(report.isNull("ledger_observed_at_ms"))
                }
            }
        }

    /** A timeout retains last-observed counts and their age without leaking ledger identities or rereading state. */
    @Test
    fun processStopTimeoutKeepsCachedClosedEvidence() =
        runBlocking {
            val timeout = timeout()
            var now = 10L
            val reports = mutableListOf<JSONObject>()
            val diagnostics = BackgroundReceiptProcessStopDiagnostics(::snapshot, { reports += it }, { now })
            val failure =
                runCatching {
                    diagnostics.trace(BackgroundReceiptProcessStopStage.HeldLedger) {
                        now = 12L
                        diagnostics.observeWork(listOf(WorkInfo.State.RUNNING, WorkInfo.State.SUCCEEDED))
                        now = 14L
                        diagnostics.observeLedger(
                            listOf(
                                JSONObject().put("kind", "get").put("fixture", "private-identity"),
                                JSONObject().put("kind", "private-path").put("request", "private-request"),
                            ),
                        )
                        now = 20L
                        throw timeout
                    }
                }.exceptionOrNull()
            assertSame(timeout, failure)
            assertEquals(listOf("entry", "timeout"), reports.map { it.getString("event") })
            val report = reports.last()
            assertEquals(10L, report.getLong("elapsed_ms"))
            assertEquals(12L, report.getLong("work_observed_at_ms"))
            assertEquals(14L, report.getLong("ledger_observed_at_ms"))
            assertEquals(1, report.getInt("work_observations"))
            assertEquals(1, report.getInt("ledger_observations"))
            assertEquals(1, report.getJSONObject("work_states").getInt("RUNNING"))
            assertEquals(1, report.getJSONObject("ledger_counts").getInt("get"))
            assertEquals(0, report.getJSONObject("ledger_counts").getInt("held"))
            assertEquals(5, report.getJSONObject("ledger_counts").length())
            assertTrue(report.getBoolean("policy_snapshot_available"))
            assertFalse(report.toString().contains("private-"))
        }

    /** Broken diagnostic sinks preserve success, timeout identity and unrelated failures without relabeling them. */
    @Test
    fun processStopDiagnosticsCannotChangeOutcomes() =
        runBlocking {
            val reports = mutableListOf<JSONObject>()
            val noSnapshot =
                BackgroundReceiptProcessStopDiagnostics({ error("snapshot unavailable") }, { reports += it })
            assertEquals(42, noSnapshot.trace(BackgroundReceiptProcessStopStage.ReceiptProcessing) { 42 })
            assertEquals(listOf("entry", "complete"), reports.map { it.getString("event") })
            assertTrue(reports.all { !it.getBoolean("policy_snapshot_available") })
            val brokenSink = BackgroundReceiptProcessStopDiagnostics(::snapshot, { error("report unavailable") })
            assertEquals(42, brokenSink.trace(BackgroundReceiptProcessStopStage.RunningWork) { 42 })
            listOf(timeout(), CancellationException("private-cancel"), AssertionError("private-assert")).forEach {
                val failure =
                    runCatching { brokenSink.trace(BackgroundReceiptProcessStopStage.HeldLedger) { throw it } }
                        .exceptionOrNull()
                assertSame(it, failure)
            }
        }

    /** Creates a real coroutine timeout once; each failure-boundary assertion then injects that same instance. */
    private suspend fun timeout(): TimeoutCancellationException =
        try {
            withTimeout(1L) { awaitCancellation() }
        } catch (timeout: TimeoutCancellationException) {
            timeout
        }

    /** A validated but metered Wi-Fi link is deliberately insufficient for this Wi-Fi-only video policy. */
    private fun snapshot(): JSONObject =
        backgroundReceiptPolicySnapshot(
            AutomaticAttachmentPolicy(
                account = AutomaticPolicyAccount.SignedIn,
                paused = false,
                matrix = MediaAutoDownloadMatrix(setOf(MediaAutoDownloadType.Video to MediaAutoDownloadNetwork.WiFi)),
                networks = setOf(MediaAutoDownloadNetwork.Metered, MediaAutoDownloadNetwork.WiFi),
                validatedInternet = true,
            ),
            expected = setOf(MediaAutoDownloadType.Video),
            wifiOnly = setOf(MediaAutoDownloadType.Video),
            approvalApplied = false,
        )
}
