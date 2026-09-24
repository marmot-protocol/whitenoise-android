package dev.ipf.whitenoise.android.core.nostr

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Same-device comparison against the exact production and legacy source files. */
@RunWith(AndroidJUnit4::class)
class Bip340PhysicalBenchmark {
    @Test
    fun compare() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val before = deviceState(context)
        val results =
            listOf(
                measure("legacySignature", Bip340ComparisonBridge::legacySignature, Bip340ComparisonBridge::legacyRejectsInvalidSignature),
                measure(
                    "replacementSignature",
                    Bip340ComparisonBridge::replacementSignature,
                    Bip340ComparisonBridge::replacementRejectsInvalidSignature,
                ),
                measure("legacyFullEvent", Bip340ComparisonBridge::legacyFullEvent, Bip340ComparisonBridge::legacyRejectsMutatedEvent),
                measure(
                    "replacementFullEvent",
                    Bip340ComparisonBridge::replacementFullEvent,
                    Bip340ComparisonBridge::replacementRejectsMutatedEvent,
                ),
            )
        val report =
            JSONObject()
                .put("schema", 1)
                .put("buildFingerprint", Build.FINGERPRINT)
                .put("abi", Build.SUPPORTED_ABIS.first())
                .put("warmupOperations", WARMUP_OPERATIONS)
                .put("operationsPerSample", OPERATIONS_PER_SAMPLE)
                .put("sampleCount", SAMPLE_COUNT)
                .put("before", before)
                .put("after", deviceState(context))
                .put("results", JSONArray(results))
        val json = report.toString()
        Log.i("BIP340_BENCH", json)
        instrumentation.sendStatus(0, Bundle().apply { putString("bench_json", json) })
    }

    private fun measure(
        name: String,
        operation: () -> Boolean,
        rejectsInvalid: () -> Boolean,
    ): JSONObject {
        assertTrue("valid $name", operation())
        assertTrue("invalid $name", rejectsInvalid())
        repeat(WARMUP_OPERATIONS) { assertTrue("warmup $name", operation()) }
        val timeNs =
            LongArray(SAMPLE_COUNT) {
                var failures = 0
                val start = SystemClock.elapsedRealtimeNanos()
                repeat(OPERATIONS_PER_SAMPLE) { if (!operation()) failures++ }
                val elapsed = SystemClock.elapsedRealtimeNanos() - start
                assertEquals("measured $name", 0, failures)
                elapsed / OPERATIONS_PER_SAMPLE
            }
        val sorted = timeNs.sorted()
        val median = (sorted[SAMPLE_COUNT / 2 - 1] + sorted[SAMPLE_COUNT / 2]) / 2
        return JSONObject()
            .put("name", name)
            .put("timeNs", JSONArray(timeNs.toList()))
            .put("medianNs", median)
            .put("operationsPerSecond", 1_000_000_000.0 / median)
    }

    private fun deviceState(context: Context): JSONObject {
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("batteryPercent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("thermalStatus", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1)
    }

    private companion object {
        private const val WARMUP_OPERATIONS = 10
        private const val OPERATIONS_PER_SAMPLE = 8
        private const val SAMPLE_COUNT = 20
    }
}
