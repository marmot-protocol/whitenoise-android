package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.RelayTelemetrySettingsFfi
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.marmotkit.UsageDiagnosticsSettingsFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Proxy

/** Guards the 0.9.20 consent migration at the native boundary used by the device-privacy toggle. */
class TelemetryConsentTest {
    /** Enabling and disabling both record an explicit choice and preserve the native export interval. */
    @Test
    fun explicitChoiceUsesCurrentConsentAndReturnsEffectiveSettings() {
        for (enabled in listOf(true, false)) {
            val calls = mutableListOf<String>()
            val settings = RelayTelemetrySettingsFfi(enabled, 120uL)
            val marmot =
                nativeBoundary { name, arguments ->
                    calls += name
                    when (name) {
                        "setUsageDiagnosticsConsent" -> {
                            assertEquals(enabled, arguments.single())
                            UsageDiagnosticsSettingsFfi(
                                if (enabled) {
                                    UsageDiagnosticsDecisionFfi.GRANTED
                                } else {
                                    UsageDiagnosticsDecisionFfi.DECLINED
                                },
                                "policy",
                                "registry",
                                0L,
                                false,
                            )
                        }
                        "relayTelemetrySettings" -> settings
                        else -> error("Unexpected native call: $name")
                    }
                }
            assertSame(settings, marmot.updateTelemetryConsent(enabled))
            assertEquals(listOf("setUsageDiagnosticsConsent", "relayTelemetrySettings"), calls)
        }
    }

    /** A rejected consent write propagates to the existing UI failure path without a misleading settings read. */
    @Test
    fun failedConsentDoesNotReportSuccessOrReadSettings() {
        val failure = IllegalStateException("consent storage unavailable")
        val marmot =
            nativeBoundary { name, _ ->
                assertEquals("setUsageDiagnosticsConsent", name)
                throw failure
            }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { marmot.updateTelemetryConsent(true) })
    }

    /** Rejects unexpected calls so deprecated consent setters cannot silently return to this path. */
    private fun nativeBoundary(onCall: (String, Array<out Any?>) -> Any?): MarmotInterface {
        val type = MarmotInterface::class.java
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
            onCall(method.name, arguments.orEmpty())
        } as MarmotInterface
    }
}
