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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.DateTimeFormatState
import dev.ipf.whitenoise.android.state.AppFont
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.BubbleSide
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.EnterKeyBehavior
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.ChoiceDialog
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail

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
 * Groups cover Theme, AMOLED color rules and typography; chat and language preferences live on their own pages.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun AppearanceScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenActionColor: () -> Unit,
    onOpenChatBubbleColors: () -> Unit,
) {
    var themeOpen by rememberSaveable { mutableStateOf(false) }
    var fontFamilyOpen by rememberSaveable { mutableStateOf(false) }
    var fontSizeOpen by rememberSaveable { mutableStateOf(false) }
    var dateTimeOpen by rememberSaveable { mutableStateOf(false) }
    val previewState = remember(appState.activeAccountRef, appState.runtimeGeneration) { AppearancePreviewState() }
    val familyThemeLabels = AppThemeMode.entries.associateWith { stringResource(it.labelRes) }
    val familyLabels = AppFont.entries.associateWith { it.pickerLabel() }
    val fontLabels = AppFontScale.entries.associateWith { stringResource(it.labelRes) }
    val amoled = appState.themeMode == AppThemeMode.Amoled
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
    if (dateTimeOpen) {
        DateTimeFormatDialog(
            timeFormat = DateTimeFormatState.timeFormat,
            dateFormat = DateTimeFormatState.dateFormat,
            system24Hour = DateTimeFormatState.system24Hour,
            onSelectTime = appState::updateTimeFormat,
            onSelectDate = appState::updateDateFormat,
            onDismiss = { dateTimeOpen = false },
        )
    }
    val locale = LocalConfiguration.current.locales[0]
    val dateTimeSample = dateTimeFormatSample(locale, DateTimeFormatState.display)
    SettingsScaffold(title = stringResource(R.string.appearance), onBack = onBack) {
        SettingsList {
            item(key = "search.Appearance.1") { SettingsSection(stringResource(R.string.appearance_colors_heading)) }
            item(key = "search.Appearance.2") {
                SettingsGroup(
                    modifier = Modifier.testTag("appearance.colors.group"),
                    destination = SettingsDetail.Appearance,
                    surface = "appearance-colors",
                ) {
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
                                routeKey = SettingsDetail.ActionColor,
                                onClick = onOpenActionColor,
                            )
                        }
                    }
                    row("chat_bubble_colors") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.chat_bubble_colors),
                            routeKey = SettingsDetail.ChatBubbleColors,
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
                    row("date_time_format") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.date_time_format),
                            value = dateTimeSample,
                            modifier = Modifier.testTag("appearance.date_time_format"),
                            onClick = { dateTimeOpen = true },
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
