package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.runtime.key
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnosticStatus
import dev.ipf.whitenoise.android.state.bootstrapRelayCount
import java.util.UUID

internal enum class DiagnosticsSection {
    Actions,
    Performance,
    RelayHealth,
    Runtime,
    EventLog,
}

internal enum class DiagnosticValueKey {
    Total,
    Connected,
    Connecting,
    Disconnected,
    Attempts,
    Successes,
    ActiveAccount,
    Accounts,
    BootstrapRelays,
}

/** Each relay-health and runtime value keeps its own glyph, so the two value groups never repeat one. */
@DrawableRes
internal fun diagnosticValueIcon(key: DiagnosticValueKey): Int =
    when (key) {
        DiagnosticValueKey.Total -> R.drawable.ic_dns
        DiagnosticValueKey.Connected -> R.drawable.ic_cable
        DiagnosticValueKey.Connecting -> R.drawable.ic_pending
        DiagnosticValueKey.Disconnected -> R.drawable.ic_block
        DiagnosticValueKey.Attempts -> R.drawable.ic_replay
        DiagnosticValueKey.Successes -> R.drawable.ic_check
        DiagnosticValueKey.ActiveAccount -> R.drawable.ic_person
        DiagnosticValueKey.Accounts -> R.drawable.ic_manage_accounts
        DiagnosticValueKey.BootstrapRelays -> R.drawable.ic_settings_cell_tower
    }

internal enum class DiagnosticsStreamStatus {
    Live,
    Idle,
}

internal data class DiagnosticsRelayHealth(
    val total: UInt,
    val connected: UInt,
    val connecting: UInt,
    val disconnected: UInt,
    val attempts: UInt,
    val successes: UInt,
)

internal data class DiagnosticValue(
    val key: DiagnosticValueKey,
    val value: String?,
)

internal data class DiagnosticsState(
    val sections: List<DiagnosticsSection>,
    val relayHealthValues: List<DiagnosticValue>,
    val runtimeValues: List<DiagnosticValue>,
    val showRelayHealthEmptyState: Boolean,
    val showEventLogEmptyState: Boolean,
    val sendToSelfEnabled: Boolean,
    val streamStatus: DiagnosticsStreamStatus,
    val performanceStatus: PerformanceDiagnosticStatus,
)

internal fun diagnosticsState(
    relayHealth: DiagnosticsRelayHealth?,
    activeAccountRef: String?,
    accountCount: Int,
    bootstrapRelayCount: Int,
    eventCount: Int,
    streaming: Boolean,
    sendingPing: Boolean,
    performanceStatus: PerformanceDiagnosticStatus,
): DiagnosticsState =
    DiagnosticsState(
        sections =
            DiagnosticsSection.entries.filter { section ->
                section != DiagnosticsSection.Performance || performanceStatus.available
            },
        relayHealthValues =
            relayHealth
                ?.let {
                    listOf(
                        DiagnosticValue(DiagnosticValueKey.Total, it.total.toString()),
                        DiagnosticValue(DiagnosticValueKey.Connected, it.connected.toString()),
                        DiagnosticValue(DiagnosticValueKey.Connecting, it.connecting.toString()),
                        DiagnosticValue(DiagnosticValueKey.Disconnected, it.disconnected.toString()),
                        DiagnosticValue(DiagnosticValueKey.Attempts, it.attempts.toString()),
                        DiagnosticValue(DiagnosticValueKey.Successes, it.successes.toString()),
                    )
                }.orEmpty(),
        runtimeValues =
            listOf(
                DiagnosticValue(DiagnosticValueKey.ActiveAccount, activeAccountRef),
                DiagnosticValue(DiagnosticValueKey.Accounts, accountCount.toString()),
                DiagnosticValue(DiagnosticValueKey.BootstrapRelays, bootstrapRelayCount.toString()),
            ),
        showRelayHealthEmptyState = relayHealth == null,
        showEventLogEmptyState = eventCount == 0,
        sendToSelfEnabled = !sendingPing && activeAccountRef != null,
        streamStatus = if (streaming) DiagnosticsStreamStatus.Live else DiagnosticsStreamStatus.Idle,
        performanceStatus = performanceStatus,
    )

internal data class DiagnosticLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: ULong = (System.currentTimeMillis() / 1000L).toULong(),
    val text: String,
)
