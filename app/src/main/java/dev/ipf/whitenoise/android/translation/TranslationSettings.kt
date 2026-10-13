@file:Suppress("FunctionNaming") // Unit-returning Compose controls use the established PascalCase convention.

package dev.ipf.whitenoise.android.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import java.util.Locale

/** Language choices are not a claim that the corresponding pack is installed. */
@Composable
internal fun TranslationLanguagePicker(
    title: String,
    selected: String,
    onSelected: (String) -> Unit,
    allowDetection: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val detection = stringResource(R.string.translation_detect_language)

    fun name(code: String): String = if (code.isEmpty()) detection else Locale.forLanguageTag(code).displayLanguage
    Box {
        TextButton(onClick = { expanded = true }) { Text("$title: ${name(selected)}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            val codes = if (allowDetection) listOf("") + offlineTranslatorLanguages else offlineTranslatorLanguages
            codes.forEach { code ->
                DropdownMenuItem(text = { Text(name(code)) }, onClick = {
                    onSelected(code)
                    expanded = false
                })
            }
        }
    }
}

@Composable
internal fun TranslationGlobalSettingsContent(preferences: TranslationPreferences) {
    val revision by preferences.changes.collectAsState()
    val choice = remember(revision) { preferences.global() }
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.translation_engine_managed))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.translation_automatic), Modifier.weight(1f))
            Switch(choice.enabled, { preferences.setGlobal(it, choice.target) })
        }
        TranslationLanguagePicker(
            stringResource(R.string.translation_target_language),
            choice.target,
            { preferences.setGlobal(choice.enabled, it) },
        )
        TextButton(onClick = { openOfflineTranslator(context) }) {
            Text(stringResource(R.string.translation_open_engine))
        }
    }
}

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
    var targetOverride by remember { mutableStateOf(preferences.targetOverride(account, group)) }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translation_automatic)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslationOverride.entries.forEach { option ->
                    val label =
                        when (option) {
                            TranslationOverride.INHERIT -> R.string.translation_inherit
                            TranslationOverride.ON -> R.string.translation_on
                            TranslationOverride.OFF -> R.string.translation_off
                        }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(currentOverride == option, onClick = {
                            preferences.setOverride(account, group, option, targetOverride)
                        })
                        TextButton(onClick = { preferences.setOverride(account, group, option, targetOverride) }) {
                            Text(stringResource(label))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(targetOverride == null, onCheckedChange = { inherit ->
                        targetOverride = if (inherit) null else preferences.global().target
                        preferences.setOverride(account, group, currentOverride, targetOverride)
                    })
                    Text(stringResource(R.string.translation_inherit_language))
                }
                targetOverride?.let { target ->
                    TranslationLanguagePicker(stringResource(R.string.translation_target_language), target, {
                        targetOverride = it
                        preferences.setOverride(account, group, currentOverride, it)
                    })
                }
                Text(stringResource(R.string.translation_engine_managed))
                TextButton(onClick = { openOfflineTranslator(context) }) {
                    Text(stringResource(R.string.translation_open_engine))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
