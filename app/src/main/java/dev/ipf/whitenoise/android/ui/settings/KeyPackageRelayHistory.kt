package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import dev.ipf.marmotkit.AccountKeyPackageRelayEventFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import java.text.DateFormat
import java.util.Date

/** Current slot winners first, then superseded events newest first, so a stale relay copy is easy to spot. */
internal fun relayHistoryRows(events: List<AccountKeyPackageRelayEventFfi>): List<AccountKeyPackageRelayEventFfi> {
    val order = compareByDescending<AccountKeyPackageRelayEventFfi> { it.isCurrent }.thenByDescending { it.createdAt }
    return events.sortedWith(order)
}

/** Relay history section of the Key packages screen (MDK 0.10.0 `accountKeyPackageRelayEvents`). */
@Suppress("FunctionNaming")
@Composable
internal fun KeyPackageRelayHistory(events: List<AccountKeyPackageRelayEventFfi>) {
    SettingsSection(stringResource(R.string.key_package_relay_history))
    if (events.isEmpty()) {
        SettingsExplainer(stringResource(R.string.developer_not_published))
        return
    }
    relayHistoryRows(events).forEach { event -> KeyPackageRelayEventRow(event) }
}

/** One observed relay event: short KeyPackage reference, publication time and relays, current or superseded. */
@Suppress("FunctionNaming")
@Composable
internal fun KeyPackageRelayEventRow(event: AccountKeyPackageRelayEventFfi) {
    val publishedAt = DateFormat.getDateTimeInstance().format(Date(event.createdAt.toLong() * MILLIS_PER_SECOND))
    val relays = event.sourceRelays.joinToString().ifEmpty { "—" }
    ListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)
                .testTag("key_packages.relay_event.${event.eventIdHex}"),
        headlineContent = {
            val shortRef = IdentityFormatter.short(event.keyPackageRefHex, prefix = 12, suffix = 8)
            Text(shortRef, fontFamily = FontFamily.Monospace)
        },
        supportingContent = { Text(stringResource(R.string.key_package_relay_event_summary, publishedAt, relays)) },
        trailingContent = {
            Text(
                stringResource(if (event.isCurrent) R.string.key_package_current else R.string.key_package_superseded),
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (event.isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        },
    )
}

private const val MILLIS_PER_SECOND = 1_000L
