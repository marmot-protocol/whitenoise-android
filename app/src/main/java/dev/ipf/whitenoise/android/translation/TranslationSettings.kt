@file:Suppress("FunctionNaming") // Unit-returning Compose controls use the established PascalCase convention.

package dev.ipf.whitenoise.android.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.DialogToggleControl
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDialogChoiceRow
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDialogToggleRow
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.settings.SettingsExplainer
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsSwitch
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/**
 * The one source of picker entries: detection plus catalog sources, or catalog targets only. Empty or
 * unsupported values read as "Detect language" or "Choose a language" rather than as a raw engine code.
 */
@Composable
internal fun rememberTranslationLanguageChoices(allowDetection: Boolean): TranslationLanguageChoices {
    val locale = LocalConfiguration.current.locales[0]
    val placeholder =
        when {
            allowDetection -> stringResource(R.string.translation_detect_language)
            else -> stringResource(R.string.translation_choose_language)
        }
    return remember(locale, placeholder, allowDetection) {
        val catalog = OfflineTranslatorLanguageCatalog
        val codes = catalog.sortedByLabel(if (allowDetection) catalog.sources else catalog.targets, locale)
        TranslationLanguageChoices(if (allowDetection) listOf("") + codes else codes) { code ->
            catalog.label(code, locale) ?: placeholder
        }
    }
}

/** Language choices are not a claim that the corresponding pack is installed. */
@Composable
internal fun TranslationLanguagePicker(
    title: String,
    selected: String,
    onSelected: (String) -> Unit,
    allowDetection: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val choices = rememberTranslationLanguageChoices(allowDetection)
    Box {
        TextButton(onClick = { expanded = true }) { Text("$title: ${choices.label(selected)}") }
        if (expanded) {
            TranslationLanguagePickerSheet(
                title = title,
                choices = choices,
                selected = selected,
                onDismiss = { expanded = false },
                onSelect = {
                    onSelected(it)
                    expanded = false
                },
            )
        }
    }
}

/**
 * Global automatic translation as settings rows: the engine and provider explainers, then one group with the
 * automatic switch and a target-language link whose value names the current target. The link opens the same
 * language sheet as the chat picker; recovery for an unusable target and the engine action follow the group.
 */
@Composable
internal fun TranslationGlobalSettingsContent(preferences: TranslationPreferences) {
    val revision by preferences.changes.collectAsState()
    val choice = remember(revision) { preferences.global() }
    val context = LocalContext.current
    val choices = rememberTranslationLanguageChoices(allowDetection = false)
    val targetTitle = stringResource(R.string.translation_target_language)
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related)) {
        SettingsExplainer(stringResource(R.string.translation_engine_managed))
        SettingsExplainer(stringResource(R.string.translation_privacy_warning))
        SettingsGroup {
            row("automatic") { rowContext ->
                SettingsSwitch(
                    context = rowContext,
                    title = stringResource(R.string.translation_automatic),
                    checked = choice.enabled,
                    onCheckedChange = { preferences.setGlobal(it, choice.target) },
                    modifier = Modifier.testTag("translation.automatic"),
                    leading = { SettingsLeadingIcon(R.drawable.ic_translate) },
                )
            }
            row("target") { rowContext ->
                SettingsLink(
                    context = rowContext,
                    title = targetTitle,
                    value = choices.label(choice.target.orEmpty()),
                    onClick = { picking = true },
                    modifier = Modifier.testTag("translation.target"),
                    leading = { SettingsLeadingIcon(R.drawable.ic_language) },
                )
            }
        }
        Column(Modifier.padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)) {
            if (choice.target == null) TranslationTargetRecovery()
            OfflineTranslatorEngineAction(onOpen = { openOfflineTranslator(context) })
        }
    }
    if (picking) {
        TranslationLanguagePickerSheet(
            title = targetTitle,
            choices = choices,
            selected = choice.target.orEmpty(),
            onDismiss = { picking = false },
            onSelect = {
                preferences.setGlobal(choice.enabled, it)
                picking = false
            },
        )
    }
}

/** One chat's automatic mode and target, scoped to the owning account and dismissed once it is stale. */
@Composable
internal fun TranslationChatSettingsDialog(
    account: String,
    group: String,
    onDismiss: () -> Unit,
    ownerCurrent: () -> Boolean = { true },
) {
    if (!ownerCurrent()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val preferences = rememberTranslationPreferences()
    val revision by preferences.changes.collectAsState()
    val currentOverride = remember(revision) { preferences.override(account, group) }
    val effectiveTarget = remember(revision) { preferences.effective(account, group).target }
    val context = LocalContext.current
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translation_automatic)) },
        text = {
            Column(
                modifier = Modifier.fadingVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.translation_privacy_warning))
                TranslationOverrideOptions(currentOverride) { preferences.setOverride(account, group, it) }
                TranslationChatTargetControls(preferences, account, group)
                if (effectiveTarget == null) TranslationTargetRecovery()
                Text(stringResource(R.string.translation_engine_managed))
                OfflineTranslatorEngineAction(onOpen = { openOfflineTranslator(context) })
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) } },
    )
}

/**
 * Inherit, On and Off for one chat as the shared dialog radio rows; changing the mode never touches the chat's
 * target language.
 */
@Composable
private fun TranslationOverrideOptions(
    selected: TranslationOverride,
    onSelected: (TranslationOverride) -> Unit,
) {
    TranslationOverride.entries.forEach { option ->
        val label =
            when (option) {
                TranslationOverride.INHERIT -> R.string.translation_inherit
                TranslationOverride.ON -> R.string.translation_on
                TranslationOverride.OFF -> R.string.translation_off
            }
        WhiteNoiseDialogChoiceRow(
            title = stringResource(label),
            selected = selected == option,
            onClick = { onSelected(option) },
        )
    }
}

/**
 * The chat target: inherited, a catalog override, or a saved code that needs a new choice. Leaving inherit
 * starts from a usable global target, and otherwise saves nothing until a language is chosen. A change to the
 * saved target, from this dialog or elsewhere, reloads it so the checkbox and picker match storage, while an
 * unrelated preference change, such as the chat's mode, keeps a choice that is still pending. The inherit choice is
 * the shared dialog check row.
 */
@Composable
private fun TranslationChatTargetControls(
    preferences: TranslationPreferences,
    account: String,
    group: String,
) {
    val revision by preferences.changes.collectAsState()
    val savedTarget = remember(revision, account, group) { preferences.targetOverride(account, group) }
    var targetOverride by remember(account, group, savedTarget) { mutableStateOf(savedTarget) }
    WhiteNoiseDialogToggleRow(
        title = stringResource(R.string.translation_inherit_language),
        checked = targetOverride == null,
        onCheckedChange = { inherit ->
            val start = if (inherit) null else preferences.global().target
            targetOverride = if (inherit) null else start.orEmpty()
            if (inherit || start != null) preferences.setTargetOverride(account, group, start)
        },
        control = DialogToggleControl.Checkbox,
    )
    targetOverride?.let { target ->
        TranslationLanguagePicker(stringResource(R.string.translation_target_language), target, {
            targetOverride = it
            preferences.setTargetOverride(account, group, it)
        })
    }
}

/** Explicit recovery for a saved target outside the catalog, shown instead of a silent substitute. */
@Composable
private fun TranslationTargetRecovery() {
    Text(
        stringResource(R.string.translation_unsupported_target),
        modifier = Modifier.testTag("translation.target_recovery"),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}
