package dev.ipf.whitenoise.android.media.sandbox

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** One debug worker-canary reply: the worker's PID and any state or descriptor an earlier input left behind. */
internal data class WorkerCanary(
    val pid: Int,
    val previousState: String,
    val previousDescriptor: String,
) {
    companion object {
        /** Parses the `pid:state:descriptor` canary text, a fresh worker reports `-` for both. */
        fun parse(text: String?): WorkerCanary {
            val parts = requireNotNull(text) { "Worker canary returned no reply" }.split(':')
            assertEquals("Unexpected canary reply $text", 3, parts.size)
            return WorkerCanary(parts[0].toInt(), parts[1], parts[2])
        }

        /** Whether `ps`, run as the shell UID, lists [pid] — isolated workers' /proc is hidden from the app. */
        fun processAlive(pid: Int): Boolean {
            val descriptor =
                InstrumentationRegistry
                    .getInstrumentation()
                    .uiAutomation
                    .executeShellCommand("ps -A -o PID")
            val listing = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
            return listing.lineSequence().any { it.trim() == pid.toString() }
        }
    }
}

/** Device-side lifetime probes for worker generations: Binder records, exit records and dispatch order. */
internal object MediaWorkerGenerationProbes {
    /**
     * Waits for Android's own exit record of a worker [pid], independent of any Binder callback. Isolated
     * workers are recorded under the app's package, with the binding instance name in the process name.
     */
    fun awaitExitRecord(pid: Int): android.app.ApplicationExitInfo {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        while (true) {
            manager.getHistoricalProcessExitReasons(context.packageName, pid, 1).firstOrNull()?.let { return it }
            check(SystemClock.elapsedRealtime() < deadline) { "Android recorded no exit for worker $pid" }
            SystemClock.sleep(POLL_MS)
        }
    }

    /** Waits up to five seconds for the [generation]th binding attempt's kernel Binder death record. */
    fun awaitDeathRecord(
        context: MediaWorkerBaselineBinding,
        generation: Int,
    ): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        var endpoint = endpoint(context, generation)
        while (endpoint?.isNull("death_observed_elapsed_ns") != false && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_MS)
            endpoint = endpoint(context, generation)
        }
        return checkNotNull(endpoint) { "Generation $generation never connected" }
    }

    /** Waits until the parent has dispatched an input to some generation and returns that generation. */
    fun awaitDecodeSent(context: MediaWorkerBaselineBinding): Int {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val sent = context.inputEndpoints().firstOrNull()
            if (sent != null) return sent.getInt("generation")
            SystemClock.sleep(FAST_POLL_MS)
        }
        error("No input was dispatched")
    }

    /** Waits until the parent has sent RETIRE to an input-bearing generation and returns that generation. */
    fun awaitRetireSent(context: MediaWorkerBaselineBinding): Int {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val retired = context.inputEndpoints().firstOrNull { !it.isNull("retire_elapsed_ns") }
            if (retired != null) return retired.getInt("generation")
            SystemClock.sleep(FAST_POLL_MS)
        }
        error("No input-bearing generation was retired")
    }

    /** Each input-bearing generation received one input, and its Binder death preceded the next one's input. */
    fun assertDeathPrecedesEachNextInput(inputs: List<JSONObject>) {
        inputs.forEach { assertEquals("One input per generation", 1, it.getInt("decodes")) }
        inputs.zipWithNext { previous, next ->
            assertTrue("Generation ${previous.getInt("generation")} has no death record", !previous.isNull(DEATH))
            assertTrue(
                "Generation ${next.getInt("generation")} received input before its predecessor's death",
                previous.getLong(DEATH) < next.getLong("decode_elapsed_ns"),
            )
        }
    }

    private fun endpoint(
        context: MediaWorkerBaselineBinding,
        generation: Int,
    ): JSONObject? {
        val endpoints = context.snapshot().getJSONArray("endpoints")
        return (0 until endpoints.length())
            .map { endpoints.getJSONObject(it) }
            .firstOrNull { it.getInt("generation") == generation }
    }

    private const val DEATH = "death_observed_elapsed_ns"
    private const val WAIT_MS = 5_000L
    private const val POLL_MS = 20L
    private const val FAST_POLL_MS = 2L
}
