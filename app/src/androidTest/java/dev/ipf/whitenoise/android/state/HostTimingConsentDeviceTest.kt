package dev.ipf.whitenoise.android.state

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.HostPerformanceOutcomeFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.ProductAnalyticsMetadataFfi
import dev.ipf.marmotkit.ProductAnalyticsRuntimeConfigFfi
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HostTimingConsentDeviceTest {
    @Test
    fun nativeConsentGatesTimings() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "host-timing-consent-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                // An isolated empty store and loopback-only destination never use app data or operator credentials.
                ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                    Marmot(root.absolutePath, emptyList()).use { marmot ->
                        val config =
                            ProductAnalyticsRuntimeConfigFfi(
                                eventsEndpoint = null,
                                appKey = null,
                                metadata = ProductAnalyticsMetadataFfi("1.0", "android", "15", "other", "native", "development", true),
                                registry = MarmotTraceSection.hostTimingRegistry,
                                allowLoopback = true,
                                operator = "test_operator",
                            )
                        marmot.setProductAnalyticsRuntimeConfig(config)
                        marmot.start()
                        try {
                            val name = MarmotTraceSection.hostTimingNames.getValue(MarmotTraceSection.TEXT_SEND)
                            assertEquals(ProductRecordResultFfi.IGNORED_DISABLED, marmot.recordHostTiming(name, 25uL, HostPerformanceOutcomeFfi.SUCCESS))
                            val relaySettings = marmot.relayTelemetrySettings()
                            assertThrows(MarmotKitException.ConsentRequired::class.java) {
                                runBlocking { marmot.setRelayTelemetrySettings(relaySettings.copy(exportEnabled = true)) }
                            }
                            marmot.setUsageDiagnosticsConsent(true)
                            assertEquals(ProductRecordResultFfi.IGNORED_UNCONFIGURED, marmot.recordHostTiming(name, 25uL, HostPerformanceOutcomeFfi.SUCCESS))
                            marmot.setProductAnalyticsRuntimeConfig(
                                config.copy(eventsEndpoint = "http://127.0.0.1:${server.localPort}/api/v0/events", appKey = "A-SH-test"),
                            )
                            assertEquals(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED, marmot.usageDiagnosticsSettings().decision)
                            assertEquals(ProductRecordResultFfi.IGNORED_DISABLED, marmot.recordHostTiming(name, 25uL, HostPerformanceOutcomeFfi.SUCCESS))
                            marmot.setUsageDiagnosticsConsent(true)
                            for (stage in MarmotTraceSection.hostTimingNames.values) {
                                assertEquals(ProductRecordResultFfi.RECORDED, marmot.recordHostTiming(stage, 25uL, HostPerformanceOutcomeFfi.SUCCESS))
                            }
                            marmot.setUsageDiagnosticsConsent(false)
                            assertEquals(0uL, marmot.usageDiagnosticsStatus().queuedEvents)
                            assertEquals(ProductRecordResultFfi.IGNORED_DISABLED, marmot.recordHostTiming(name, 25uL, HostPerformanceOutcomeFfi.FAILURE))
                        } finally {
                            marmot.shutdown()
                        }
                    }
                }
            } finally {
                root.deleteRecursively()
            }
        }
}
