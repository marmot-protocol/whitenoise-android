package dev.ipf.whitenoise.android.benchmark

import androidx.test.uiautomator.UiDevice

/** Rejects personal-user navigation, screen-on samples and dead or replaced fixture processes. */
internal class BackgroundFixtureGuard(
    private val device: UiDevice,
) {
    /** Requires the explicitly authorized instrumentation profile to remain foreground. */
    fun requireForeground(): Int = BenchmarkConfig.requireQualificationUser(device.executeShellCommand("am get-current-user"))

    /** Captures one live target process after sleep; radio or permission changes may have killed it. */
    fun requireScreenOffProcess(): Int {
        val user = requireForeground()
        check(!device.isScreenOn) { "The fixture screen is on during background measurement." }
        val processes =
            device
                .executeShellCommand("ps -A -o UID,PID,NAME")
                .lineSequence()
                .map { it.trim().split(Regex("\\s+")) }
                .filter { it.size == 3 && it[2] == BenchmarkConfig.TARGET_PACKAGE }
                .filter { it[0].toIntOrNull()?.div(100_000) == user }
                .mapNotNull { it[1].toIntOrNull() }
                .toList()
        check(processes.size == 1) { "A single live fixture process is required for the baseline." }
        return processes.single()
    }

    /** Prevents attributing a process-death or foreground sample to the controlled live baseline. */
    fun verify(originalPid: Int) {
        check(requireScreenOffProcess() == originalPid) { "The fixture process changed during measurement." }
    }
}
