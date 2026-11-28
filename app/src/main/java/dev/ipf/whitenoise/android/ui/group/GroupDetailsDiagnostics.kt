package dev.ipf.whitenoise.android.ui.group

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.GroupPushDebugInfoFfi
import dev.ipf.marmotkit.GroupPushTokenDebugEntryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.AppDivider
import dev.ipf.whitenoise.android.ui.settings.DiagnosticRow
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsPanel
import dev.ipf.whitenoise.android.ui.settings.SettingsSection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** A developer-only fact panel: the section label over free-form diagnostic rows on the group surface. */
@Suppress("FunctionNaming")
@Composable
internal fun DeveloperInfoPanel(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        SettingsSection(title)
        SettingsPanel {
            Column(
                Modifier.fillMaxWidth().padding(WhiteNoiseSpacing.FormField),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                content = content,
            )
        }
    }
}

/** A lifecycle row's glyph, tinted by its row, replaced by progress while its mutation runs. */
@Suppress("FunctionNaming")
@Composable
internal fun DangerLeading(
    @DrawableRes icon: Int,
    inProgress: Boolean,
) {
    if (inProgress) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    } else {
        SettingsLeadingIcon(icon)
    }
}

/** Developer-mode push delivery diagnostics for the group. */
@Composable
internal fun PushDeliveryDebugSection(
    info: GroupPushDebugInfoFfi?,
    loading: Boolean,
    appState: WhiteNoiseAppState,
) {
    DeveloperInfoPanel(title = stringResource(R.string.push_delivery)) {
        when {
            loading ->
                Text(
                    stringResource(R.string.loading_push_debug_info),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            info == null ->
                Text(
                    stringResource(R.string.push_debug_info_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else -> {
                val yesText = stringResource(R.string.yes)
                val noText = stringResource(R.string.no)
                val yesNo: (Boolean) -> String = { value -> if (value) yesText else noText }

                DiagnosticRow(stringResource(R.string.push_debug_total_tokens), info.totalTokenCount.toString())
                DiagnosticRow(stringResource(R.string.push_debug_active_tokens), info.activeTokenCount.toString())
                DiagnosticRow(stringResource(R.string.push_debug_stale_tokens), info.staleTokenCount.toString())
                DiagnosticRow(
                    stringResource(R.string.push_debug_missing_relay_hints),
                    info.missingRelayHintCount.toString(),
                )
                info.lastTokenListUpdatedAtMs?.let { updatedAtMs ->
                    val updatedAtText = updatedAtMs.toString()
                    DiagnosticRow(
                        stringResource(R.string.push_debug_last_token_list_update),
                        updatedAtText,
                        copyValue = updatedAtText,
                    )
                }

                Spacer(Modifier.heightIn(min = 4.dp))
                Text(
                    stringResource(R.string.push_debug_local_registration),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val local = info.localRegistration
                DiagnosticRow(stringResource(R.string.push_debug_registered), yesNo(local.registered))
                DiagnosticRow(stringResource(R.string.push_debug_shareable), yesNo(local.shareable))
                DiagnosticRow(
                    stringResource(R.string.push_debug_local_notifications_enabled),
                    yesNo(local.localNotificationsEnabled),
                )
                DiagnosticRow(stringResource(R.string.native_push), yesNo(local.nativePushEnabled))
                local.localLeafIndex?.let { leafIndex ->
                    DiagnosticRow(stringResource(R.string.push_debug_local_leaf_index), leafIndex.toString())
                }
                DiagnosticRow(stringResource(R.string.push_debug_local_token_cached), yesNo(local.localTokenCached))

                if (info.tokens.isNotEmpty()) {
                    Spacer(Modifier.heightIn(min = 4.dp))
                    Text(
                        stringResource(R.string.push_debug_member_tokens),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    info.tokens.forEachIndexed { index, token ->
                        if (index > 0) {
                            AppDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }
                        PushTokenDebugRows(token, appState, yesNo)
                    }
                }
            }
        }
    }
}

@Composable
private fun PushTokenDebugRows(
    token: GroupPushTokenDebugEntryFfi,
    appState: WhiteNoiseAppState,
    yesNo: (Boolean) -> String,
) {
    val memberName = appState.chatMemberTitle(token.memberIdHex)
    EmojiLabel(
        memberName,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth(),
    )
    val identityCopy =
        pushDebugPublicIdentityCopy(
            memberIdHex = token.memberIdHex,
            serverPubkeyHex = token.serverPubkeyHex,
            npubForDisplay = appState::npubForDisplay,
        )
    identityCopy.memberNpub?.let { memberNpub ->
        DiagnosticRow(
            stringResource(R.string.push_debug_member),
            IdentityFormatter.short(memberNpub),
            copyValue = memberNpub,
        )
    }
    DiagnosticRow(stringResource(R.string.push_debug_leaf_index), token.leafIndex.toString())
    DiagnosticRow(stringResource(R.string.push_debug_platform), token.platform.name)
    DiagnosticRow(
        stringResource(R.string.push_debug_token_fingerprint),
        IdentityFormatter.short(token.tokenFingerprint),
        copyValue = token.tokenFingerprint,
    )
    identityCopy.serverNpub?.let { serverNpub ->
        DiagnosticRow(
            stringResource(R.string.push_debug_push_server_pubkey),
            IdentityFormatter.short(serverNpub),
            copyValue = serverNpub,
        )
    }
    DiagnosticRow(stringResource(R.string.push_debug_relay_hint), yesNo(token.hasRelayHint))
    DiagnosticRow(stringResource(R.string.push_debug_active_leaf), yesNo(token.activeLeaf))
    DiagnosticRow(stringResource(R.string.push_debug_member_matches_active_leaf), yesNo(token.memberMatchesActiveLeaf))
    DiagnosticRow(stringResource(R.string.push_debug_is_local_member), yesNo(token.isLocalMember))
    val updatedAtText = token.updatedAtMs.toString()
    DiagnosticRow(
        stringResource(R.string.push_debug_updated_at),
        updatedAtText,
        copyValue = updatedAtText,
    )
}
