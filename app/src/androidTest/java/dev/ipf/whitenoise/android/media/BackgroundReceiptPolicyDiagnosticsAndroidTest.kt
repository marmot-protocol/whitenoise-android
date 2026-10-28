package dev.ipf.whitenoise.android.media

import androidx.test.ext.junit.runners.AndroidJUnit4
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
