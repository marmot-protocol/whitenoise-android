package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppFont
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.EnterKeyBehavior
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.quickProfileCycling
import dev.ipf.whitenoise.android.state.updateQuickProfileCycling
import dev.ipf.whitenoise.android.ui.common.ChoiceDialog

internal val AppThemeMode.labelRes: Int
    @StringRes
    get() =
        when (this) {
            AppThemeMode.System -> R.string.language_system
            AppThemeMode.Light -> R.string.theme_light
            AppThemeMode.Dark -> R.string.theme_dark
            AppThemeMode.Amoled -> R.string.appearance_amoled
        }

internal val EnterKeyBehavior.labelRes: Int
    @StringRes
    get() =
        when (this) {
            EnterKeyBehavior.SendMessage -> R.string.enter_key_behavior_send
            EnterKeyBehavior.NewLine -> R.string.enter_key_behavior_newline
        }

internal val AppFontScale.labelRes: Int
    @StringRes
    get() =
        when (this) {
            AppFontScale.Small -> R.string.font_scale_small
            AppFontScale.Default -> R.string.font_scale_default
            AppFontScale.Large -> R.string.font_scale_large
            AppFontScale.ExtraLarge -> R.string.font_scale_extra_large
        }

/** The System entry is labelled through resources; every other family shows its proper name. */
@Composable
private fun AppFont.pickerLabel(): String =
    if (this == AppFont.System) {
        stringResource(R.string.appearance_font_system)
    } else {
        displayName
    }

/**
 * Appearance preserves existing preference owners and adds the explicit app-wide profile-cycle opt-in.
 * Shared groups retain Theme, AMOLED color rules, typography, Enter-key choices and the language destination.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun AppearanceScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenActionColor: () -> Unit,
    onOpenChatBubbleColors: () -> Unit,
    onOpenLanguage: () -> Unit,
) {
    var fontFamilyOpen by rememberSaveable { mutableStateOf(false) }
    var fontSizeOpen by rememberSaveable { mutableStateOf(false) }
    var enterOpen by rememberSaveable { mutableStateOf(false) }
    val familyLabels = AppFont.entries.associateWith { it.pickerLabel() }
    val fontLabels = AppFontScale.entries.associateWith { stringResource(it.labelRes) }
    val enterLabels = EnterKeyBehavior.entries.associateWith { stringResource(it.labelRes) }
    val amoled = appState.themeMode == AppThemeMode.Amoled
    val language = languageOptions.firstOrNull { it.tag == appState.languageTag } ?: languageOptions.first()
    if (fontFamilyOpen) {
        ChoiceDialog(
            title = stringResource(R.string.app_font),
            values = AppFont.entries,
            selected = appState.appFont,
            label = familyLabels::getValue,
            onDismiss = { fontFamilyOpen = false },
            onSelect = {
                fontFamilyOpen = false
                appState.updateAppFont(it)
            },
        )
    }
    if (fontSizeOpen) {
        ChoiceDialog(
            title = stringResource(R.string.font_size),
            values = AppFontScale.entries,
            selected = appState.fontScale,
            label = fontLabels::getValue,
            supportingText = stringResource(R.string.appearance_font_size_help),
            onDismiss = { fontSizeOpen = false },
            onSelect = {
                fontSizeOpen = false
                appState.updateFontScale(it)
            },
        )
    }
    if (enterOpen) {
        ChoiceDialog(
            title = stringResource(R.string.enter_key_behavior_title),
            values = EnterKeyBehavior.entries,
            selected = appState.enterKeyBehavior,
            label = enterLabels::getValue,
            supportingText = stringResource(R.string.appearance_enter_help),
            onDismiss = { enterOpen = false },
            onSelect = {
                enterOpen = false
                appState.updateEnterKeyBehavior(it)
            },
        )
    }
    SettingsScaffold(title = stringResource(R.string.appearance), onBack = onBack) {
        SettingsList {
            item {
                SettingsGroup(modifier = Modifier.testTag("appearance.quick_account_switching")) {
                    row("quick_account_switching") { context ->
                        SettingsSwitch(
                            context = context,
                            title = stringResource(R.string.quick_account_switching),
                            checked = appState.quickProfileCycling,
                            subtitle = stringResource(R.string.quick_account_switching_detail),
                            onCheckedChange = appState::updateQuickProfileCycling,
                        )
                    }
                }
            }
            item { SettingsSection(stringResource(R.string.appearance_theme)) }
            item {
                SettingsGroup(modifier = Modifier.selectableGroup().testTag("appearance.theme.group")) {
                    AppThemeMode.entries.forEach { mode ->
                        row(mode.preferenceValue) { context ->
                            SettingsChoice(
                                context = context,
                                title = stringResource(mode.labelRes),
                                selected = appState.themeMode == mode,
                                subtitle =
                                    if (mode == AppThemeMode.Amoled) {
                                        stringResource(R.string.appearance_amoled_detail)
                                    } else {
                                        null
                                    },
                                highlightSelected = false,
                                onClick = { appState.updateThemeMode(mode) },
                            )
                        }
                    }
                }
            }
            item { SettingsExplainer(stringResource(R.string.appearance_theme_help)) }
            item {
                SettingsGroup {
                    row("action_color") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.action_color),
                            enabled = !amoled,
                            subtitle = if (amoled) stringResource(R.string.appearance_outline_colors_fixed) else null,
                            onClick = onOpenActionColor,
                        )
                    }
                    row("chat_bubble_colors") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.chat_bubble_colors),
                            enabled = !amoled,
                            onClick = onOpenChatBubbleColors,
                        )
                    }
                }
            }
            item {
                SettingsGroup {
                    row("font_family") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.app_font),
                            value = familyLabels.getValue(appState.appFont),
                            onClick = { fontFamilyOpen = true },
                        )
                    }
                    row("font_size") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.font_size),
                            value = fontLabels.getValue(appState.fontScale),
                            onClick = { fontSizeOpen = true },
                        )
                    }
                    row("enter_behavior") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.enter_key_behavior_title),
                            value = enterLabels.getValue(appState.enterKeyBehavior),
                            onClick = { enterOpen = true },
                        )
                    }
                }
            }
            item {
                SettingsGroup(modifier = Modifier.testTag("appearance.language.group")) {
                    row("language") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.language),
                            value = stringResource(language.labelRes),
                            onClick = onOpenLanguage,
                        )
                    }
                }
            }
        }
    }
}
