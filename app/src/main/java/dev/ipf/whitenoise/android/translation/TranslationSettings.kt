@file:Suppress("FunctionNaming") // Unit-returning Compose controls use the established PascalCase convention.

package dev.ipf.whitenoise.android.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll

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
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.codes.forEach { code ->
                DropdownMenuItem(text = { Text(choices.label(code)) }, onClick = {
                    onSelected(code)
                    expanded = false
                })
            }
        }
    }
}

/** Global automatic translation: the switch, its target with recovery for an unusable one, and the engine. */
@Composable
internal fun TranslationGlobalSettingsContent(preferences: TranslationPreferences) {
    val revision by preferences.changes.collectAsState()
    val choice = remember(revision) { preferences.global() }
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.translation_engine_managed))
        Text(stringResource(R.string.translation_privacy_warning))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.translation_automatic), Modifier.weight(1f))
            Switch(
                checked = choice.enabled,
                onCheckedChange = { preferences.setGlobal(it, choice.target) },
                modifier = Modifier.testTag("translation.automatic"),
            )
        }
        TranslationLanguagePicker(
            stringResource(R.string.translation_target_language),
            choice.target.orEmpty(),
            { preferences.setGlobal(choice.enabled, it) },
        )
        if (choice.target == null) TranslationTargetRecovery()
        OfflineTranslatorEngineAction(onOpen = { openOfflineTranslator(context) })
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
    AlertDialog(
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Inherit, On and Off for one chat; changing the mode never touches the chat's target language. */
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected == option, onClick = { onSelected(option) })
            TextButton(onClick = { onSelected(option) }) { Text(stringResource(label)) }
        }
    }
}

/**
 * The chat target: inherited, a catalog override, or a saved code that needs a new choice. Leaving inherit
 * starts from a usable global target, and otherwise saves nothing until a language is chosen.
 */
@Composable
private fun TranslationChatTargetControls(
    preferences: TranslationPreferences,
    account: String,
    group: String,
) {
    var targetOverride by remember { mutableStateOf(preferences.targetOverride(account, group)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(targetOverride == null, onCheckedChange = { inherit ->
            val start = if (inherit) null else preferences.global().target
            targetOverride = if (inherit) null else start.orEmpty()
            if (inherit || start != null) preferences.setTargetOverride(account, group, start)
        })
        Text(stringResource(R.string.translation_inherit_language))
    }
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
