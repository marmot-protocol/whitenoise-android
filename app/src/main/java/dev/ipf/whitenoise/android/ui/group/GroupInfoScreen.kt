@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.ui.common.CopyableValueRow
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.settings.SettingsPanel
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.settings.SettingsSection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Read-only technical identifiers of the group, each copyable in full; relays live on Group relays. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupInfoScreen(
    groupIdHex: String,
    nostrGroupIdHex: String,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    SettingsScaffold(title = stringResource(R.string.developer_tools), onBack = onBack) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .whiteNoiseVerticalScroll(rememberScrollState())
                    .padding(bottom = WhiteNoiseSpacing.Section),
        ) {
            GroupIdentifierGroup(groupIdHex, nostrGroupIdHex, clipboard)
        }
    }
}

/** Group and Nostr group ids with copy actions. */
@Composable
private fun GroupIdentifierGroup(
    groupIdHex: String,
    nostrGroupIdHex: String,
    clipboard: ClipboardManager,
) {
    SettingsSection(stringResource(R.string.group_identifiers))
    SettingsPanel {
        Column(Modifier.fillMaxWidth().padding(WhiteNoiseSpacing.FormField)) {
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
}
