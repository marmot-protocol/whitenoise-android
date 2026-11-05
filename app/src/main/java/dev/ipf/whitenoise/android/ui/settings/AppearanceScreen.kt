package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppFont
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.BubbleSide
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.EnterKeyBehavior
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.quickAccountSwitching
import dev.ipf.whitenoise.android.state.updateQuickAccountSwitching
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
    onOpenCustomEmoji: () -> Unit,
    onOpenComposer: () -> Unit = {},
) {
    var themeOpen by rememberSaveable { mutableStateOf(false) }
    var fontFamilyOpen by rememberSaveable { mutableStateOf(false) }
    var fontSizeOpen by rememberSaveable { mutableStateOf(false) }
    var enterOpen by rememberSaveable { mutableStateOf(false) }
    val previewState = remember(appState.activeAccountRef, appState.runtimeGeneration) { AppearancePreviewState() }
    val familyThemeLabels = AppThemeMode.entries.associateWith { stringResource(it.labelRes) }
    val familyLabels = AppFont.entries.associateWith { it.pickerLabel() }
    val fontLabels = AppFontScale.entries.associateWith { stringResource(it.labelRes) }
    val enterLabels = EnterKeyBehavior.entries.associateWith { stringResource(it.labelRes) }
    val amoled = appState.themeMode == AppThemeMode.Amoled
    val language = languageOptions.firstOrNull { it.tag == appState.languageTag } ?: languageOptions.first()
    if (themeOpen) {
        ChoiceDialog(
            title = stringResource(R.string.appearance_theme),
            values = AppThemeMode.entries,
            selected = appState.themeMode,
            label = { mode -> familyThemeLabels.getValue(mode) },
            supportingText = stringResource(R.string.appearance_theme_help),
            onDismiss = { themeOpen = false },
            onSelect = {
                appState.updateThemeMode(it)
                themeOpen = false
            },
        )
    }
    if (fontFamilyOpen) {
        val sampleTheme = BubbleTheme.resolve(appState.themeMode, isSystemInDarkTheme())
        FontFamilyDialog(
            selected = appState.appFont,
            label = familyLabels::getValue,
            onSelect = appState::updateAppFont,
            onDismiss = { fontFamilyOpen = false },
            theme = sampleTheme,
            mineArgb = appState.globalBubbleColorArgb(sampleTheme, BubbleSide.Mine),
            otherArgb = appState.globalBubbleColorArgb(sampleTheme, BubbleSide.Other),
        )
    }
    if (fontSizeOpen) {
        val sampleTheme =
            BubbleTheme.resolve(
                appState.themeMode,
                isSystemInDarkTheme(),
            )
        FontSizeDialog(
            theme = sampleTheme,
            mineArgb = appState.globalBubbleColorArgb(sampleTheme, BubbleSide.Mine),
            otherArgb = appState.globalBubbleColorArgb(sampleTheme, BubbleSide.Other),
            selected = appState.fontScale,
            onSelect = appState::updateFontScale,
            onDismiss = { fontSizeOpen = false },
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
            item(key = "search.Appearance.1") { SettingsSection(stringResource(R.string.appearance_colors_heading)) }
            item(key = "search.Appearance.2") {
                SettingsGroup(modifier = Modifier.testTag("appearance.colors.group")) {
                    row("theme") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.appearance_theme),
                            value = familyThemeLabels.getValue(appState.themeMode),
                            subtitle = if (amoled) stringResource(R.string.appearance_amoled_detail) else null,
                            modifier = Modifier.testTag("appearance.theme.group"),
                            onClick = { themeOpen = true },
                        )
                    }
                    if (!amoled) {
                        row("action_color") { context ->
                            SettingsLink(
                                context = context,
                                title = stringResource(R.string.action_color),
                                onClick = onOpenActionColor,
                            )
                        }
                    }
                    row("chat_bubble_colors") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.chat_bubble_colors),
                            onClick = onOpenChatBubbleColors,
                        )
                    }
                }
            }
            item(key = "search.Appearance.3") { SettingsSection(stringResource(R.string.appearance_text_heading)) }
            item(key = "search.Appearance.4") {
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
                    row("language") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.language),
                            value = stringResource(language.labelRes),
                            modifier = Modifier.testTag("appearance.language.group"),
                            onClick = onOpenLanguage,
                        )
                    }
                }
            }
            item(key = "search.Appearance.5") {
                SettingsSection(stringResource(R.string.appearance_conversation_heading))
            }
            item(key = "search.Appearance.6") {
                SettingsGroup {
                    row("composer") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.composer_settings),
                            subtitle = stringResource(R.string.composer_settings_detail),
                            onClick = onOpenComposer,
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
                    row("custom_emoji") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.custom_emoji),
                            modifier = Modifier.testTag("appearance.custom_emoji.group"),
                            onClick = onOpenCustomEmoji,
                        )
                    }
                    row("quick_account_switching") { context ->
                        SettingsSwitch(
                            context = context,
                            title = stringResource(R.string.quick_account_switching),
                            checked = appState.quickAccountSwitching,
                            subtitle = stringResource(R.string.quick_account_switching_detail),
                            modifier = Modifier.testTag("appearance.quick_account_switching"),
                            onCheckedChange = appState::updateQuickAccountSwitching,
                        )
                    }
                }
            }
            item(key = "search.Appearance.7") {
                val previewTheme = BubbleTheme.resolve(appState.themeMode, isSystemInDarkTheme())
                AppearanceAccessibilitySample(
                    state = previewState,
                    theme = previewTheme,
                    mineArgb = appState.globalBubbleColorArgb(previewTheme, BubbleSide.Mine),
                    otherArgb = appState.globalBubbleColorArgb(previewTheme, BubbleSide.Other),
                )
            }
        }
    }
}
