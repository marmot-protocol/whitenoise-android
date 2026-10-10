package dev.ipf.whitenoise.android.benchmark

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/** External-process qualification using an authenticated, populated fixture retained in MDK's real store. */
@RunWith(AndroidJUnit4::class)
class PopulatedProcessDeathStartupBenchmark {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val fixtureUser = Process.myUid() / 100_000
    private val manager = instrumentation.context.getSystemService(ConnectivityManager::class.java)
    private val networkAppeared = AtomicBoolean()
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkAppeared.set(true)
            }
        }

    /** An ordinary background-process kill must retain interactive local rows while every network is unavailable. */
    @Test
    fun populatedLocalRowsRemainInteractiveBeforeNetworkRelease() {
        require(InstrumentationRegistry.getArguments().getString("requireOfflineStartup") == "true") {
            "The host must explicitly prepare and restore an offline populated startup fixture."
        }
        val groupName = BenchmarkConfig.requireGeneratorFixture(BenchmarkConfig.groupName, "groupName")
        val budgetMs = reviewedLocalFrameBudget()
        requireOfflineFixture()
        manager.registerNetworkCallback(
            NetworkRequest.Builder().clearCapabilities().build(),
            networkCallback,
            Handler(Looper.getMainLooper()),
        )
        try {
            measureOfflineProcessRestoration(groupName, budgetMs)
            instrumentation.waitForIdleSync()
            requireOfflineFixture()
        } finally {
            manager.unregisterNetworkCallback(networkCallback)
        }
    }

    /** Measures only launches whose ordinary dead process and continuously offline host have been verified. */
    private fun measureOfflineProcessRestoration(
        groupName: String,
        budgetMs: Long,
    ) {
        val journeys = WhiteNoiseJourneys()
        var previousPid = 0
        benchmarkRule.measureRepeated(
            packageName = BenchmarkConfig.TARGET_PACKAGE,
            metrics = startupMetrics(),
            compilationMode = CompilationMode.None(),
            iterations = 3,
            setupBlock = {
                requireOfflineFixture()
                journeys.run { resumeToChatList() }
                journeys.requirePopulatedLocalList(groupName)
                previousPid = requireNotNull(targetPid()) { "The prepared fixture process is absent." }
                terminateOriginalBackgroundProcess(previousPid)
            },
            measureBlock = {
                requireOfflineFixture()
                check(targetPid() == null) { "A background owner prewarmed the replacement process." }
                val launchStartedAt = SystemClock.elapsedRealtime()
                journeys.run { launchRestoredTaskAndWait(BenchmarkUsefulSurface.ChatList) }
                val currentPid = requireNotNull(targetPid()) { "The restored fixture process is absent." }
                check(currentPid != previousPid) { "The fixture process was retained rather than restored." }
                journeys.requirePopulatedLocalList(groupName)
                check(SystemClock.elapsedRealtime() - launchStartedAt <= budgetMs) {
                    "The populated local checkpoint exceeded the reviewed device budget."
                }
                journeys.openLocalTranscript(groupName)
                requireOfflineFixture()
            },
        )
    }

    /** Retries ordinary background kills while the observed PID remains original as Home finishes backgrounding. */
    private fun terminateOriginalBackgroundProcess(originalPid: Int) {
        check(device.pressHome()) { "Home did not background the prepared fixture." }
        // MacrobenchmarkScope.killProcess() uses force-stop. am kill preserves the task and
        // stopped-package state, but can be refused until Android updates process importance.
        val deadline = SystemClock.elapsedRealtime() + PROCESS_EXIT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val currentPid = targetPid() ?: return
            check(currentPid == originalPid) { "A background owner replaced the fixture during process death." }
            device.executeShellCommand("am kill --user $fixtureUser ${BenchmarkConfig.TARGET_PACKAGE}")
            SystemClock.sleep(50L)
        }
        check(targetPid() == null) { "Ordinary process death did not terminate the fixture." }
    }

    /** Requires the same pre-reviewed positive device budget supplied to the package-replacement reporter. */
    private fun reviewedLocalFrameBudget(): Long {
        val value = InstrumentationRegistry.getArguments().getString("localFrameBudgetMs").orEmpty()
        require(value.matches(Regex("[1-9][0-9]{0,8}"))) { "Pass the reviewed device localFrameBudgetMs." }
        return value.toLong()
    }

    /** Rejects a profile switch, available LAN/relay link or any network edge observed during qualification. */
    private fun requireOfflineFixture() {
        check(device.executeShellCommand("am get-current-user").trim().toIntOrNull() == fixtureUser) {
            "The authenticated fixture user is no longer foreground."
        }
        check(manager.allNetworks.isEmpty() && !networkAppeared.get()) {
            "Network work is not continuously blocked: an available transport was observed."
        }
    }

    /** Resolves only the main target process belonging to the instrumentation's Android user. */
    private fun targetPid(): Int? =
        device
            .executeShellCommand("ps -A -o UID,PID,NAME")
            .lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { fields ->
                fields.size == 3 &&
                    fields[2] == BenchmarkConfig.TARGET_PACKAGE &&
                    fields[0].toIntOrNull()?.div(100_000) == fixtureUser
            }?.get(1)
            ?.toIntOrNull()

    private companion object {
        const val PROCESS_EXIT_TIMEOUT_MS = 5_000L
    }
}
