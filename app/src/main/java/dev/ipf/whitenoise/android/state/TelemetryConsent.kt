@file:Suppress("MatchingDeclarationName")

package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.RelayTelemetrySettingsFfi
import dev.ipf.marmotkit.UsageDiagnosticsSettingsFfi
import dev.ipf.marmotkit.UsageDiagnosticsStatusFfi

/** The unified consent decision, exporter status, and legacy OTLP interval read in one host operation. */
internal data class UsageDiagnosticsSnapshot(
    val settings: UsageDiagnosticsSettingsFfi,
    val status: UsageDiagnosticsStatusFfi,
    val relayTelemetry: RelayTelemetrySettingsFfi,
)

/**
 * Records a fresh opt-in or revocation, then reads the effective unified state without resetting exporter intervals.
 * MDK requires renewed acceptance when the configured Android product registry expands.
 */
internal fun MarmotInterface.updateTelemetryConsent(enabled: Boolean): UsageDiagnosticsSnapshot =
    UsageDiagnosticsSnapshot(
        settings = setUsageDiagnosticsConsent(enabled),
        status = usageDiagnosticsStatus(),
        relayTelemetry = relayTelemetrySettings(),
    )

/** Reads the 0.9.20 consent and exporter state without mutating it. */
internal fun MarmotInterface.usageDiagnosticsSnapshot(): UsageDiagnosticsSnapshot =
    UsageDiagnosticsSnapshot(
        settings = usageDiagnosticsSettings(),
        status = usageDiagnosticsStatus(),
        relayTelemetry = relayTelemetrySettings(),
    )
