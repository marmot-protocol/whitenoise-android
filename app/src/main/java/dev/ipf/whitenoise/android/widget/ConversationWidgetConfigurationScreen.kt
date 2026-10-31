@file:OptIn(ExperimentalMaterial3Api::class)
@file:Suppress("FunctionNaming") // Standard Compose presentation controls.

package dev.ipf.whitenoise.android.widget

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.settings.SettingsBottomAction
import dev.ipf.whitenoise.android.ui.settings.SettingsExplainer
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsList
import dev.ipf.whitenoise.android.ui.settings.SettingsRowContext
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.settings.SettingsSection
import dev.ipf.whitenoise.android.ui.settings.SettingsSwitch
import dev.ipf.whitenoise.android.ui.settings.settingsRowBorder
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

@Composable
internal fun ConversationWidgetConfigurationContent(
    accounts: List<WidgetAccountPresentation>,
    onOpenApp: () -> Unit,
    onSave: (ConversationWidgetChoice) -> Unit,
    onCancel: () -> Unit = {},
) {
    var account by rememberSaveable { mutableStateOf<String?>(null) }
    var all by rememberSaveable { mutableStateOf(false) }
    var unread by rememberSaveable { mutableStateOf(false) }
    val references = accounts.map { it.reference }
    LaunchedEffect(references) {
        if (account == null) {
            account = accounts.firstOrNull { it.active }?.reference ?: accounts.firstOrNull()?.reference
        }
    }
    SettingsScaffold(
        title = stringResource(R.string.widget_title),
        onBack = onCancel,
        modifier = Modifier.testTag("widget.configuration"),
        bottomBar = {
            WidgetConfigurationAction(
                available = accounts.isNotEmpty(),
                canSave = account in references,
                onOpenApp = onOpenApp,
                onSave = {
                    account?.takeIf { it in references }?.let {
                        onSave(ConversationWidgetChoice(it, all, false, unread))
                    }
                },
            )
        },
    ) {
        SettingsList {
            item { SettingsExplainer(stringResource(R.string.widget_privacy)) }
            if (accounts.isNotEmpty()) {
                item { SettingsSection(stringResource(R.string.account)) }
                item {
                    WidgetAccountGroup(accounts, account) { account = it }
                }
                item {
                    SettingsGroup {
                        row("all") { context ->
                            SettingsSwitch(context, stringResource(R.string.widget_all_accounts), all, { all = it })
                        }
                        row("unread") { context ->
                            SettingsSwitch(
                                context,
                                stringResource(R.string.widget_unread_only),
                                unread,
                                { unread = it },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetConfigurationAction(
    available: Boolean,
    canSave: Boolean,
    onOpenApp: () -> Unit,
    onSave: () -> Unit,
) {
    SettingsBottomAction {
        if (available) {
            WhiteNoiseButton(
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().testTag("widget.save"),
                onClick = onSave,
            ) { Text(stringResource(R.string.save)) }
        } else {
            WhiteNoiseButton(onOpenApp, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.widget_open_app))
            }
        }
    }
}

@Composable
private fun WidgetAccountGroup(
    accounts: List<WidgetAccountPresentation>,
    selected: String?,
    onSelected: (String) -> Unit,
) {
    SettingsGroup(Modifier.selectableGroup()) {
        accounts.forEach { profile ->
            row(profile.reference) { context ->
                WidgetAccountChoice(context, profile, selected == profile.reference) {
                    onSelected(profile.reference)
                }
            }
        }
    }
}

@Composable
private fun WidgetAccountChoice(
    context: SettingsRowContext,
    profile: WidgetAccountPresentation,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        shapes = context.shapes,
        modifier =
            Modifier
                .fillMaxWidth()
                .settingsRowBorder(context, true)
                .testTag("widget.profile.${profile.reference}")
                .semantics { role = Role.RadioButton },
        leadingContent = {
            Avatar(
                profile.name,
                profile.seed,
                48.dp,
                pictureUrl = profile.pictureUrl,
                picture = profile.picture,
                pictureDomain = AvatarImageLoader.publicProfiles(),
            )
        },
        supportingContent = if (profile.active) ({ Text(stringResource(R.string.active)) }) else null,
        trailingContent = {
            RadioButton(selected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
        },
        colors =
            ListItemDefaults.colors(
                containerColor = context.containerColor,
                selectedContainerColor =
                    if (isAmoledSurfaceTheme()) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
            ),
        content = { EmojiLabel(profile.name, style = MaterialTheme.typography.titleMedium) },
    )
}
