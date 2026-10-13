@file:Suppress("FunctionNaming") // Presentation-only Compose controls follow the app convention.

package dev.ipf.whitenoise.android.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.ChoiceDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsRowContext
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import java.util.Locale

/** One primary action; engine management appears only when a request needs recovery. */
@Suppress("LongParameterList")
@Composable
internal fun MessageTranslationForm(
    from: String,
    target: String,
    failure: TranslationFailure?,
    enabled: Boolean,
    onFromChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onTranslate: () -> Unit,
    onDismiss: () -> Unit,
    onOpenEngine: () -> Unit,
) {
    WhiteNoiseSheetHeader(stringResource(R.string.translation_title), onClose = onDismiss)
    Column(
        Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()).testTag("translation.form"),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
    ) {
        SettingsGroup {
            row("source") { context ->
                TranslationLanguageRow(
                    context = context,
                    title = stringResource(R.string.translation_source_language),
                    selected = from,
                    onSelected = onFromChange,
                    allowDetection = true,
                )
            }
            row("target") { context ->
                TranslationLanguageRow(
                    context = context,
                    title = stringResource(R.string.translation_target_language),
                    selected = target,
                    onSelected = onTargetChange,
                )
            }
        }
        if (failure != null) {
            Column(Modifier.padding(horizontal = WhiteNoiseSpacing.Section)) {
                Text(
                    stringResource(translationFailureResource(failure)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onOpenEngine) { Text(stringResource(R.string.translation_open_engine)) }
            }
        }
        TranslationConfirmation(enabled, onTranslate)
    }
}

@Composable
private fun TranslationConfirmation(
    enabled: Boolean,
    onTranslate: () -> Unit,
) {
    WhiteNoiseButton(
        onClick = onTranslate,
        enabled = enabled,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = WhiteNoiseSpacing.Section,
                    vertical = WhiteNoiseSpacing.Related,
                ).testTag("translation.confirm"),
    ) { Text(stringResource(R.string.translation_title)) }
}

@Suppress("LongParameterList")
@Composable
private fun TranslationLanguageRow(
    context: SettingsRowContext,
    title: String,
    selected: String,
    onSelected: (String) -> Unit,
    allowDetection: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val detection = stringResource(R.string.translation_detect_language)
    val label: (String) -> String = { if (it.isEmpty()) detection else Locale.forLanguageTag(it).displayLanguage }
    SettingsLink(context, title, onClick = { open = true }, value = label(selected))
    if (open) {
        ChoiceDialog(
            title = title,
            values = if (allowDetection) listOf("") + offlineTranslatorLanguages else offlineTranslatorLanguages,
            selected = selected,
            label = label,
            onDismiss = { open = false },
            onSelect = {
                onSelected(it)
                open = false
            },
        )
    }
}
