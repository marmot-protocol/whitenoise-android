package dev.ipf.whitenoise.android.media.sandbox

import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Exercise the actual bounded log reader with traffic from a different process than its worker. */
@RunWith(AndroidJUnit4::class)
@MediaContainmentAcceptance
class AvCapabilityLogCaptureDeviceTest {
    /** Parent log traffic must not evict the retired worker's begin and terminal records. */
    @Test
    fun workerDiagnosticsSurviveUnrelatedProcessLogTraffic() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Initialize the shell connection before the worker and the timed noise capture.
        val automation = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        val token = UUID.randomUUID().toString().replace("-", "")
        val fixture = File(context.cacheDir, "av-log-capture-$token.bin")
        val canary = File(context.filesDir, "media-containment-canary")
        check(canary.createNewFile()) { "A different fixture owns the private canary" }
        try {
            fixture.writeBytes(verifiedVideoFixture())
            canary.writeBytes(byteArrayOf(1))
            MediaAvCapabilityBinding(context).use { binding ->
                val observed = binding.run(fixture, request(token, fixture.length(), canary))
                check(observed is AvCapabilityCase.Terminal) { "Worker terminal evidence required" }
                val evidence = observed.observation
                val workerPid = evidence.getInt("kernel_ready_pid")
                check(workerPid != Process.myPid()) { "Noise must originate outside the worker" }
                repeat(512) { index ->
                    Log.i("AvCaptureNoise", "$token|$index")
                    if (index % 32 == 31) SystemClock.sleep(10)
                }
                awaitParentNoise(automation, token)
                val rows =
                    AvCapabilityLogs.read(
                        AvProbeTuple(token, "log_capture_noise", 1),
                        evidence.getInt("kernel_ready_uid"),
                        workerPid,
                        false,
                    )
                check(rows.length() >= 2) { "Begin and terminal diagnostics required after noise" }
            }
        } finally {
            fixture.delete()
            canary.delete()
        }
    }

    /** Prove enough distinct foreign records exist to evict the worker from an unfiltered 256-line tail. */
    private fun awaitParentNoise(
        automation: UiAutomation,
        token: String,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 3000
        do {
            val prefix = "$token|"
            val observed =
                parentNoise(automation, deadline)
                    .lineSequence()
                    .filter { it.startsWith(prefix) }
                    .mapNotNull { it.removePrefix(prefix).toIntOrNull() }
                    .filter { it in 0..511 }
                    .toSet()
            if (observed.size >= 256) return
            SystemClock.sleep(10)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Fewer than 256 distinct parent noise records were observed within the capture bound")
    }

    /** Read a finite parent-only tail with headroom for the 256-record proof and the same 32KiB ceiling. */
    private fun parentNoise(
        automation: UiAutomation,
        deadline: Long,
    ): String {
        val command = "logcat --pid=${Process.myPid()} -d -t 512 -v raw -s AvCaptureNoise:I '*:S'"
        val shell = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(shell).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            val poll =
                android.system.StructPollfd().apply {
                    fd = shell.fileDescriptor
                    events = (android.system.OsConstants.POLLIN or android.system.OsConstants.POLLHUP).toShort()
                }
            while (output.size() <= 32768) {
                val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0).toInt()
                check(android.system.Os.poll(arrayOf(poll), remaining) > 0) { "Parent noise capture deadline" }
                val count = input.read(buffer, 0, minOf(buffer.size, 32769 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            check(output.size() <= 32768) { "Parent noise capture exceeded its byte bound" }
            output.toString(Charsets.UTF_8.name())
        }
    }

    /** Use the existing finite isolated-worker protocol with only test-owned descriptors and canary. */
    private fun request(
        token: String,
        length: Long,
        canary: File,
    ): Bundle =
        Bundle().apply {
            putString("runToken", token)
            putString("caseId", "log_capture_noise")
            putString("probeStage", "retriever_construct")
            putInt("requestId", 1)
            putLong("offset", 0)
            putLong("length", length)
            putString("canaryPath", canary.absolutePath)
        }
}
