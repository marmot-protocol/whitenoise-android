package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** App language as one radio group; a choice applies immediately and the screen stays open. */
@Suppress("FunctionNaming")
@Composable
internal fun LanguageScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.language), onBack = onBack) {
        SettingsList {
            item {
                SettingsGroup(
                    modifier =
                        Modifier
                            .selectableGroup()
                            .padding(top = WhiteNoiseSpacing.Section)
                            .testTag("language.choices.group"),
                ) {
                    languageOptions.forEach { option ->
                        row(option.tag.ifEmpty { "system" }) { context ->
                            SettingsChoice(
                                context = context,
                                title = stringResource(option.labelRes),
                                selected = appState.languageTag == option.tag,
                                highlightSelected = false,
                                onClick = { appState.updateLanguageTag(option.tag) },
                            )
                        }
                    }
                }
            }
        }
    }
}
