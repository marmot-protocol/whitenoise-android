package dev.ipf.whitenoise.android.state

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in 20-cycle real-device acceptance scenario for issue #2395.
 *
 * A local-relay harness supplies 20 unique plaintexts through the
 * `offlineRecoveryMessages` instrumentation argument. On each `publish_now`
 * status event it publishes the matching message while this device is
 * offline. The device must begin in that conversation, with cellular disabled
 * when Wi-Fi is not its only validated transport. The test verifies the exact
 * text once in durable history and Compose, exercises foreground, background,
 * and repeated-flap recovery, and reports numeric phase percentiles.
 *
 * Each cycle's five-second ceiling runs from its final validated reconnect to
 * the moment exactly one controlled row is visible (#3723). Generic
 * first-frame markers are reported separately and cannot complete a cycle.
 */
@ManualDeviceFixture
@RunWith(AndroidJUnit4::class)
class OfflineRecoveryLatencyDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val targetContext = instrumentation.targetContext
    private val connectivity = targetContext.getSystemService(ConnectivityManager::class.java)

    /** Runs the externally coordinated relay scenario and enforces every latency ceiling. */
    @Test
    fun twentyOfflineRecoveryCyclesReachDurableAndVisibleStateExactlyOnce() {
        val messages = requiredMessages()
        val accountRef = requiredArgument(ARG_ACCOUNT_REF)
        val groupIdHex = requiredArgument(ARG_GROUP_ID_HEX)
        val application = composeRule.activity.application as WhiteNoiseApplication
        val diagnostics = application.appState.recoveryDiagnostics
        assumeTrue(
            "Local performance diagnostics are unavailable in this variant",
            PerformanceDiagnostics.start().active,
        )
        composeRule.waitForIdle()
        val suiteBaselineGeneration = latestGeneration(diagnostics)
        val messageLatencies = mutableListOf<Long>()

        try {
            messages.forEachIndexed { index, expectedText ->
                goOffline()
                reportCycle(index, "publish_now", expectedText)
                SystemClock.sleep(argumentLong(ARG_PUBLISH_WAIT_MILLIS, DEFAULT_PUBLISH_WAIT_MILLIS))
                val observations = mutableListOf(observe(expectedText, offline = true))

                val backgroundCycle = index % SCENARIO_COUNT == BACKGROUND_SCENARIO
                if (backgroundCycle) instrumentation.runOnMainSync { composeRule.activity.moveTaskToBack(true) }
                if (index % SCENARIO_COUNT == REPEATED_FLAP_SCENARIO) repeatRapidFlap()
                // A row delivered during the flap is seen before the edge and fails the cycle. A stopped
                // background task shows no rows, so it is only observed once it returns.
                if (!backgroundCycle) observations += observe(expectedText, offline = true)
                // Only the final validated reconnect owns this cycle, never an earlier flap.
                val baseline = latestGeneration(diagnostics)
                val edge =
                    OfflineReconnectEdge(
                        baseline,
                        // Every offline poll's observation is kept, even one that overlaps validation: a row
                        // visible then fails the cycle rather than passing as a fast completion.
                        restoreWifi { if (!backgroundCycle) observations += observe(expectedText, offline = true) },
                    )

                if (backgroundCycle) {
                    awaitPhaseAfter(diagnostics, edge, PerformancePhase.ACCOUNT_CATCH_UP_READY)
                    foregroundApp()
                }
                val latency = awaitControlledMessage(expectedText, edge, observations)
                composeRule.onAllNodesWithText(expectedText).assertCountEquals(1)
                assertDurableMessageExactlyOnce(application, accountRef, groupIdHex, expectedText)
                val generation = awaitVisibleGenerationAfter(diagnostics, edge)
                assertGenerationBudgets(diagnostics.performanceSamples(), generation)
                messageLatencies += latency
                reportCycle(index, "verified", expectedText, generation, latency)
            }

            val report =
                offlineRecoveryLatencyReport(
                    diagnostics.performanceSamples().filter { it.generation > suiteBaselineGeneration },
                )
            assertEquals(REQUIRED_CYCLES, report.completedCycles)
            reportFinal(report, phaseLatency(messageLatencies))
        } finally {
            restoreWifi()
            PerformanceDiagnostics.stop()
        }
    }

    /** Requires the harness to provide exactly 20 non-empty, unique messages. */
    private fun requiredMessages(): List<String> {
        val encoded = requiredArgument(ARG_MESSAGES)
        val messages = encoded.split(MESSAGE_SEPARATOR).map(String::trim).filter(String::isNotEmpty)
        assumeTrue("$ARG_MESSAGES must contain exactly $REQUIRED_CYCLES entries", messages.size == REQUIRED_CYCLES)
        assumeTrue("Every recovery message must be unique", messages.distinct().size == messages.size)
        return messages
    }

    /** Reads a required instrumentation argument or skips the opt-in scenario. */
    private fun requiredArgument(name: String): String {
        val value = arguments.getString(name).orEmpty().trim()
        assumeTrue("Missing opt-in instrumentation argument: $name", value.isNotEmpty())
        return value
    }

    /** Disables Wi-Fi and rejects a false offline cycle when another transport remains validated. */
    private fun goOffline() {
        shell("svc wifi disable")
        assertTrue(
            "A validated transport remained active; disable cellular before this local-relay scenario",
            waitUntil(NETWORK_TRANSITION_TIMEOUT_MILLIS) { !hasValidatedInternet() },
        )
    }

    /**
     * Enables Wi-Fi, waits until Android validates the restored transport and returns the reconnect edge.
     * The edge is the monotonic time the platform delivered validation to this process, recorded by a
     * network callback independently of polling, so neither the poll interval nor a slow [whileOffline]
     * observation is counted. The last poll that found the device offline is the fallback, which can only
     * overstate latency. [whileOffline] runs on each poll that finds the device offline.
     */
    private fun restoreWifi(whileOffline: () -> Unit = {}): Long {
        val validatedAt = AtomicLong(0L)
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        validatedAt.compareAndSet(0L, SystemClock.elapsedRealtime())
                    }
                }
            }
        var lastOfflineAt = SystemClock.elapsedRealtime()
        connectivity.registerDefaultNetworkCallback(callback)
        try {
            shell("svc wifi enable")
            val validated =
                waitUntil(NETWORK_TRANSITION_TIMEOUT_MILLIS) {
                    val now = SystemClock.elapsedRealtime()
                    hasValidatedInternet().also { online ->
                        if (!online) {
                            lastOfflineAt = now
                            whileOffline()
                        }
                    }
                }
            assertTrue("Wi-Fi did not regain validated internet", validated)
        } finally {
            connectivity.unregisterNetworkCallback(callback)
        }
        return validatedAt.get().takeIf { it > 0L } ?: lastOfflineAt
    }

    /** Exercises a short failed restore before the final validated edge owns recovery. */
    private fun repeatRapidFlap() {
        shell("svc wifi enable")
        SystemClock.sleep(RAPID_FLAP_MILLIS)
        shell("svc wifi disable")
        assertTrue(
            "The rapid flap did not return offline",
            waitUntil(NETWORK_TRANSITION_TIMEOUT_MILLIS) { !hasValidatedInternet() },
        )
    }

    /** Brings the existing task forward without recreating or clearing app state. */
    private fun foregroundApp() {
        val launchIntent =
            checkNotNull(targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(launchIntent)
        composeRule.waitForIdle()
    }

    /** Returns the newest recovery generation recorded so far. */
    private fun latestGeneration(diagnostics: NotificationNetworkRecoveryCoordinator): Long =
        diagnostics.performanceSamples().maxOfOrNull { it.generation } ?: 0L

    /** Records how many visible rows show the controlled message right now, marking observations made offline. */
    private fun observe(
        expectedText: String,
        offline: Boolean = false,
    ): ControlledMessageObservation {
        val count = composeRule.onAllNodesWithText(expectedText).fetchSemanticsNodes().size
        return ControlledMessageObservation(SystemClock.elapsedRealtime(), count, offline)
    }

    /**
     * Polls the controlled message until exactly one row is visible or the cycle timeout passes,
     * then requires that completion within five seconds of [edge] and returns its latency.
     */
    private fun awaitControlledMessage(
        expectedText: String,
        edge: OfflineReconnectEdge,
        observations: MutableList<ControlledMessageObservation>,
    ): Long {
        // A timeout leaves the observations for the oracle, which names the failure.
        try {
            composeRule.waitUntil(timeoutMillis = cycleTimeoutMillis()) {
                observe(expectedText).also(observations::add).visibleCount != 0
            }
        } catch (_: ComposeTimeoutException) {
        }
        return when (val outcome = controlledMessageOutcome(edge, observations, MESSAGE_CEILING_MILLIS)) {
            is ControlledMessageOutcome.Completed -> outcome.latencyMillis
            is ControlledMessageOutcome.Failed -> throw AssertionError(outcome.reason)
        }
    }

    /** Waits for the visible-frame marker of the generation started by the cycle's final reconnect. */
    private fun awaitVisibleGenerationAfter(
        diagnostics: NotificationNetworkRecoveryCoordinator,
        edge: OfflineReconnectEdge,
    ): Long {
        var generation: Long? = null
        composeRule.waitUntil(timeoutMillis = cycleTimeoutMillis()) {
            generation = recoveryGenerationAfter(diagnostics.performanceSamples(), edge)
            generation != null
        }
        return requireNotNull(generation)
    }

    /** Waits for a recovery phase of the final reconnect before foregrounding a background cycle. */
    private fun awaitPhaseAfter(
        diagnostics: NotificationNetworkRecoveryCoordinator,
        edge: OfflineReconnectEdge,
        phase: PerformancePhase,
    ) {
        assertTrue(
            "Recovery never reached ${phase.wireName}",
            waitUntil(cycleTimeoutMillis()) {
                diagnostics.performanceSamples().any { it.generation > edge.generationBaseline && it.phase == phase }
            },
        )
    }

    /** Confirms that durable history contains one and only one exact target row. */
    private fun assertDurableMessageExactlyOnce(
        application: WhiteNoiseApplication,
        accountRef: String,
        groupIdHex: String,
        expectedText: String,
    ) {
        val exactCount =
            runBlocking {
                application.appState.marmotIo {
                    timelineMessages(
                        accountRef,
                        TimelineMessageQueryFfi.page(
                            groupIdHex = groupIdHex,
                            search = null,
                            before = null,
                            beforeMessageId = null,
                            after = null,
                            afterMessageId = null,
                            limit = DURABLE_QUERY_LIMIT,
                        ),
                    ).messages.count { it.plaintext == expectedText }
                }
            }
        assertEquals("Expected one durable row for the controlled relay message", 1, exactCount)
    }

    /**
     * Enforces the activation and post-replay projection ceilings for one cycle. The end-to-end
     * ceiling belongs to the controlled message, not to a generic visible-frame marker.
     */
    private fun assertGenerationBudgets(
        samples: List<NotificationNetworkRecoverySample>,
        generation: Long,
    ) {
        val cycle = samples.filter { it.generation == generation }
        val activation = cycle.phaseElapsed(PerformancePhase.ACCOUNT_SUBSCRIPTION_ACTIVATED)
        val replay = cycle.phaseElapsed(PerformancePhase.CURRENT_REPLAY_COMPLETE)
        val projection =
            cycle.phaseElapsedOrNull(PerformancePhase.TIMELINE_PROJECTION_PUBLISHED)
                ?: cycle.phaseElapsed(PerformancePhase.CHAT_LIST_PROJECTION_PUBLISHED)
        assertTrue("Subscription activation exceeded 3 seconds: $activation ms", activation <= 3_000L)
        assertTrue("Post-replay projection exceeded 1 second: ${projection - replay} ms", projection - replay <= 1_000L)
    }

    /** Returns the elapsed marker for a required phase. */
    private fun List<NotificationNetworkRecoverySample>.phaseElapsed(phase: PerformancePhase): Long =
        requireNotNull(phaseElapsedOrNull(phase)) { "Missing ${phase.wireName} marker" }

    /** Returns the elapsed marker for an optional phase. */
    @Suppress("MaxLineLength") // Keeping the typed extension expression together follows the project's Kotlin style.
    private fun List<NotificationNetworkRecoverySample>.phaseElapsedOrNull(phase: PerformancePhase): Long? = firstOrNull { it.phase == phase }?.elapsedMillis

    /** Reports machine-readable cycle progress without message bodies or identifiers. */
    private fun reportCycle(
        index: Int,
        state: String,
        expectedText: String,
        generation: Long? = null,
        messageLatencyMillis: Long? = null,
    ) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putInt("offline_recovery_cycle", index + 1)
                putString("offline_recovery_state", state)
                putInt("offline_recovery_expected_length", expectedText.length)
                generation?.let { putLong("offline_recovery_generation", it) }
                messageLatencyMillis?.let { putLong("offline_recovery_message_latency_ms", it) }
            },
        )
    }

    /** Reports device/build context, the controlled-message latency and p50/p95/max for every observed phase. */
    private fun reportFinal(
        report: OfflineRecoveryLatencyReport,
        messageLatency: OfflineRecoveryPhaseLatency,
    ) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                putInt("api", Build.VERSION.SDK_INT)
                putString("build_fingerprint", Build.FINGERPRINT)
                putInt("completed_cycles", report.completedCycles)
                putLong("controlled_message_p50_ms", messageLatency.p50Millis)
                putLong("controlled_message_p95_ms", messageLatency.p95Millis)
                putLong("controlled_message_max_ms", messageLatency.maximumMillis)
                report.phaseLatencies.forEach { (phase, latency) ->
                    putLong("${phase.wireName}_p50_ms", latency.p50Millis)
                    putLong("${phase.wireName}_p95_ms", latency.p95Millis)
                    putLong("${phase.wireName}_max_ms", latency.maximumMillis)
                }
            },
        )
    }

    /** Returns whether Android currently exposes a validated default network. */
    private fun hasValidatedInternet(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        return connectivity
            .getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    /** Executes a bounded platform command and drains its output. */
    private fun shell(command: String) {
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    /** Polls a device condition using monotonic time. */
    private fun waitUntil(
        timeoutMillis: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return condition()
    }

    /** Reads a positive long argument while preserving a bounded default. */
    private fun argumentLong(
        name: String,
        defaultValue: Long,
    ): Long = arguments.getString(name)?.toLongOrNull()?.takeIf { it > 0L } ?: defaultValue

    /** Returns the per-cycle fixture timeout. */
    private fun cycleTimeoutMillis(): Long = argumentLong(ARG_CYCLE_TIMEOUT_MILLIS, DEFAULT_CYCLE_TIMEOUT_MILLIS)

    private companion object {
        const val ARG_MESSAGES = "offlineRecoveryMessages"
        const val ARG_ACCOUNT_REF = "offlineRecoveryAccountRef"
        const val ARG_GROUP_ID_HEX = "offlineRecoveryGroupIdHex"
        const val ARG_PUBLISH_WAIT_MILLIS = "offlineRecoveryPublishWaitMillis"
        const val ARG_CYCLE_TIMEOUT_MILLIS = "offlineRecoveryCycleTimeoutMillis"
        const val MESSAGE_SEPARATOR = "|"
        const val REQUIRED_CYCLES = 20
        const val SCENARIO_COUNT = 4
        const val BACKGROUND_SCENARIO = 1
        const val REPEATED_FLAP_SCENARIO = 2
        const val DEFAULT_PUBLISH_WAIT_MILLIS = 1_000L
        const val DEFAULT_CYCLE_TIMEOUT_MILLIS = 15_000L
        const val NETWORK_TRANSITION_TIMEOUT_MILLIS = 15_000L
        const val RAPID_FLAP_MILLIS = 150L
        const val POLL_MILLIS = 50L
        const val MESSAGE_CEILING_MILLIS = 5_000L
        const val DURABLE_QUERY_LIMIT = 500u
    }
}
