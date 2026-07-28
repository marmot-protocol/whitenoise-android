package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppFont
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.EnterKeyBehavior
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.SettingsGroup
import dev.ipf.whitenoise.android.ui.theme.fontFamilyOrNull

private data class LanguageOption(
    val tag: String,
    @param:StringRes val labelRes: Int,
)

private val languageOptions =
    listOf(
        LanguageOption("", R.string.language_system),
        LanguageOption("en", R.string.language_english),
        LanguageOption("de", R.string.language_german),
        LanguageOption("es", R.string.language_spanish),
        LanguageOption("fr", R.string.language_french),
        LanguageOption("it", R.string.language_italian),
        LanguageOption("pt", R.string.language_portuguese),
        LanguageOption("ru", R.string.language_russian),
        LanguageOption("tr", R.string.language_turkish),
        LanguageOption("zh", R.string.language_chinese_simplified),
        LanguageOption("zh-Hant", R.string.language_chinese_traditional),
    )

internal val AppThemeMode.labelRes: Int
    @StringRes
    get() =
        when (this) {
            AppThemeMode.System -> R.string.theme_system
            AppThemeMode.Light -> R.string.theme_light
            AppThemeMode.Dark -> R.string.theme_dark
            AppThemeMode.Amoled -> R.string.theme_amoled
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

private val AppThemeMode.cardIcon: ImageVector
    get() =
        when (this) {
            AppThemeMode.System -> Icons.Filled.BrightnessAuto
            AppThemeMode.Light -> Icons.Filled.LightMode
            AppThemeMode.Dark -> Icons.Filled.DarkMode
            AppThemeMode.Amoled -> Icons.Filled.Contrast
        }

private val AppThemeMode.cardSubtitleRes: Int
    @StringRes
    get() =
        when (this) {
            AppThemeMode.System -> R.string.theme_system_subtitle
            AppThemeMode.Light -> R.string.theme_light_subtitle
            AppThemeMode.Dark -> R.string.theme_dark_subtitle
            AppThemeMode.Amoled -> R.string.amoled_mode_subtitle
        }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenActionColor: () -> Unit,
    onOpenChatBubbleColors: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appearance)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                ThemeAndColorSettings(appState, onOpenActionColor, onOpenChatBubbleColors)
            }
            item {
                // Standalone settings share one label-less group: their row titles
                // already say what they are, so per-row headers only fragment the page.
                SettingsGroup {
                    item { FontSizePickerRow(appState) }
                    item { AppFontPickerRow(appState) }
                    item { LanguagePickerSection(appState) }
                    item { EnterKeyBehaviorRow(appState) }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ThemeAndColorSettings(
    appState: WhiteNoiseAppState,
    onOpenActionColor: () -> Unit,
    onOpenChatBubbleColors: () -> Unit,
) {
    // Four equally-weighted modes: a separate AMOLED toggle read as
    // combinable with Light, which is not a real state.
    SettingsGroup(
        modifier = Modifier.selectableGroup(),
        title = stringResource(R.string.theme_mode),
        icon = Icons.Filled.Palette,
    ) {
        AppThemeMode.entries.forEach { mode ->
            item {
                ThemeModeCard(
                    mode = mode,
                    selected = appState.themeMode == mode,
                    onClick = { appState.updateThemeMode(mode) },
                )
            }
        }
        item {
            SettingsRow(
                title = stringResource(R.string.action_color),
                subtitle = stringResource(R.string.action_color_subtitle),
                icon = Icons.Filled.ColorLens,
                onClick = onOpenActionColor,
            )
        }
        item {
            SettingsRow(
                title = stringResource(R.string.chat_bubble_colors),
                subtitle = stringResource(R.string.chat_bubble_colors_global_subtitle),
                icon = Icons.Filled.ColorLens,
                onClick = onOpenChatBubbleColors,
            )
        }
    }
}

@Composable
private fun ThemeModeCard(
    mode: AppThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = mode.cardIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(mode.labelRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(mode.cardSubtitleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = null)
    }
}

// Font-size picker row: the four scale steps open in a sheet; picking one
// rescales the whole app live (the sheet included), so no separate preview
// page is needed.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontSizePickerRow(appState: WhiteNoiseAppState) {
    var showSheet by remember { mutableStateOf(false) }
    SettingsRow(
        title = stringResource(R.string.font_size),
        subtitle = stringResource(appState.fontScale.labelRes),
        icon = Icons.Filled.FormatSize,
        onClick = { showSheet = true },
    )
    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(Modifier.selectableGroup().padding(bottom = 24.dp)) {
                AppFontScale.entries.forEach { scale ->
                    SelectableSettingsRow(
                        title = stringResource(scale.labelRes),
                        selected = appState.fontScale == scale,
                        onClick = {
                            appState.updateFontScale(scale)
                            showSheet = false
                        },
                    )
                }
            }
        }
    }
}

// App-font picker row, shaped like the Language row: current family in the
// subtitle, a sheet of options on tap — each rendered in its own typeface so
// the list doubles as a live preview.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppFontPickerRow(appState: WhiteNoiseAppState) {
    var showSheet by remember { mutableStateOf(false) }
    SettingsRow(
        title = stringResource(R.string.app_font),
        subtitle = appState.appFont.pickerLabel(),
        icon = Icons.Filled.TextFields,
        onClick = { showSheet = true },
    )
    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(Modifier.selectableGroup().padding(bottom = 24.dp)) {
                AppFont.entries.forEach { font ->
                    ListItem(
                        modifier =
                            Modifier.clickable {
                                appState.updateAppFont(font)
                                showSheet = false
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(font.pickerLabel(), fontFamily = font.fontFamilyOrNull()) },
                        trailingContent = {
                            if (appState.appFont == font) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = stringResource(R.string.selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppFont.pickerLabel(): String = if (this == AppFont.System) stringResource(R.string.theme_system) else displayName

// One row showing the current Enter-key choice; the two options live in a
// radio dialog that applies on tap, matching the language picker's shape.
@Composable
private fun EnterKeyBehaviorRow(appState: WhiteNoiseAppState) {
    var showDialog by remember { mutableStateOf(false) }
    SettingsRow(
        title = stringResource(R.string.enter_key_behavior_title),
        subtitle = stringResource(appState.enterKeyBehavior.labelRes),
        icon = Icons.Filled.Keyboard,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.enter_key_behavior_title)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    EnterKeyBehavior.entries.forEach { behavior ->
                        SelectableSettingsRow(
                            title = stringResource(behavior.labelRes),
                            selected = appState.enterKeyBehavior == behavior,
                            onClick = {
                                appState.updateEnterKeyBehavior(behavior)
                                showDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private fun currentLanguageLabelRes(tag: String): Int = languageOptions.firstOrNull { it.tag == tag }?.labelRes ?: R.string.language_system

// Collapse the long language list to one row that opens a modal picker sheet.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePickerSection(appState: WhiteNoiseAppState) {
    var showSheet by remember { mutableStateOf(false) }
    SettingsRow(
        title = stringResource(R.string.language),
        subtitle = stringResource(currentLanguageLabelRes(appState.languageTag)),
        icon = Icons.Filled.Translate,
        onClick = { showSheet = true },
    )
    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(Modifier.selectableGroup().padding(bottom = 24.dp)) {
                languageOptions.forEach { option ->
                    SelectableSettingsRow(
                        title = stringResource(option.labelRes),
                        selected = appState.languageTag == option.tag,
                        onClick = {
                            appState.updateLanguageTag(option.tag)
                            showSheet = false
                        },
                    )
                }
            }
        }
    }
}
