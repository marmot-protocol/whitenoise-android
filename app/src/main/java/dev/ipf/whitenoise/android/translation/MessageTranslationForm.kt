@file:Suppress("FunctionNaming") // Presentation-only Compose controls follow the app convention.

package dev.ipf.whitenoise.android.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

/**
 * One primary action; engine management appears only when a request needs recovery. [onOpenEngine] reports
 * whether any app could open the engine or its install route, so a failed hand-off stays visible.
 */
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
    onOpenEngine: () -> Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        WhiteNoiseSheetHeader(stringResource(R.string.translation_title), onClose = onDismiss)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .fadingVerticalScroll(rememberScrollState())
                .testTag("translation.form"),
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
            Text(
                text = stringResource(R.string.translation_privacy_warning),
                modifier = Modifier.padding(horizontal = WhiteNoiseSpacing.Section),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (failure != null) {
                Column(Modifier.padding(horizontal = WhiteNoiseSpacing.Section)) {
                    Text(
                        stringResource(translationFailureResource(failure)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OfflineTranslatorEngineAction(onOpen = onOpenEngine)
                }
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

/** A source row offers detection plus catalog sources; a target row offers only catalog targets. */
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
    val choices = rememberTranslationLanguageChoices(allowDetection)
    SettingsLink(context, title, onClick = { open = true }, value = choices.label(selected))
    if (open) {
        ChoiceDialog(
            title = title,
            values = choices.codes,
            selected = selected,
            label = choices.label,
            onDismiss = { open = false },
            onSelect = {
                onSelected(it)
                open = false
            },
        )
    }
}
