package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.EnterKeyBehavior
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.SectionCard

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenFontSize: () -> Unit,
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
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.theme)) {
                    AppThemeMode.entries.forEach { mode ->
                        SelectableSettingsRow(
                            title = stringResource(mode.labelRes),
                            selected = appState.themeMode == mode,
                            onClick = { appState.updateThemeMode(mode) },
                        )
                    }
                    SettingsRow(
                        title = stringResource(R.string.chat_bubble_colors),
                        subtitle = stringResource(R.string.chat_bubble_colors_global_subtitle),
                        onClick = onOpenChatBubbleColors,
                    )
                }
            }
            item {
                SectionCard(title = stringResource(R.string.font_size)) {
                    SettingsRow(
                        stringResource(appState.fontScale.labelRes),
                        stringResource(R.string.font_size_settings_subtitle),
                    ) { onOpenFontSize() }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.language)) {
                    languageOptions.forEach { option ->
                        SelectableSettingsRow(
                            title = stringResource(option.labelRes),
                            selected = appState.languageTag == option.tag,
                            onClick = { appState.updateLanguageTag(option.tag) },
                        )
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.enter_key_behavior_title)) {
                    EnterKeyBehavior.entries.forEach { behavior ->
                        SelectableSettingsRow(
                            title = stringResource(behavior.labelRes),
                            selected = appState.enterKeyBehavior == behavior,
                            onClick = { appState.updateEnterKeyBehavior(behavior) },
                        )
                    }
                }
            }
        }
    }
}
