@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.ui.common.CopyableValueRow
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupInfoScreen(
    groupIdHex: String,
    nostrGroupIdHex: String,
    relays: List<String>,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    SettingsScaffold(title = stringResource(R.string.group_info), onBack = onBack) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .whiteNoiseVerticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
        ) {
            GroupIdentifierCard(groupIdHex, nostrGroupIdHex, clipboard)
            GroupRelayCard(relays, clipboard)
        }
    }
}

/** Read-only chat relays from the current native group record, with full-value copy actions. */
@Composable
internal fun ChatRelaysScreen(
    relays: List<String>,
    onBack: () -> Unit,
    feedback: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    SettingsScaffold(title = stringResource(R.string.relays), onBack = onBack, bottomBar = bottomBar) {
        Column(Modifier.fillMaxSize()) {
            feedback()
            Column(
                modifier = Modifier.weight(1f).whiteNoiseVerticalScroll(rememberScrollState()).padding(Dimens.spaceLg),
            ) {
                GroupRelayCard(relays, clipboard)
            }
        }
    }
}

@Composable
private fun GroupIdentifierCard(
    groupIdHex: String,
    nostrGroupIdHex: String,
    clipboard: ClipboardManager,
) {
    SectionCard(title = stringResource(R.string.group_identifiers)) {
        CopyableValueRow(
            label = stringResource(R.string.mls_group_id),
            value = groupIdHex,
            displayValue = IdentityFormatter.short(groupIdHex, prefix = 16, suffix = 12),
            clipboard = clipboard,
        )
        CopyableValueRow(
            label = stringResource(R.string.nostr_group_id),
            value = nostrGroupIdHex,
            displayValue = IdentityFormatter.short(nostrGroupIdHex, prefix = 16, suffix = 12),
            clipboard = clipboard,
        )
    }
}

@Composable
private fun GroupRelayCard(
    relays: List<String>,
    clipboard: ClipboardManager,
) {
    SectionCard(title = stringResource(R.string.group_relays)) {
        Text(
            stringResource(R.string.group_relays_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (relays.isEmpty()) {
            Text(
                stringResource(R.string.no_relays),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            relays.forEachIndexed { index, relay ->
                CopyableValueRow(
                    label = stringResource(R.string.relay_number, index + 1),
                    value = relay,
                    clipboard = clipboard,
                )
            }
        }
    }
}
