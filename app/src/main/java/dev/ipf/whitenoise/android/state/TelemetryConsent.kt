package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.RelayTelemetrySettingsFfi

/**
 * Records a fresh opt-in or revocation, then reads the effective exporter state without resetting its interval.
 * Upgraded legacy consent is never promoted automatically. Android configures only its existing OTLP exporter.
 */
internal fun MarmotInterface.updateTelemetryConsent(enabled: Boolean): RelayTelemetrySettingsFfi {
    setUsageDiagnosticsConsent(enabled)
    return relayTelemetrySettings()
}
