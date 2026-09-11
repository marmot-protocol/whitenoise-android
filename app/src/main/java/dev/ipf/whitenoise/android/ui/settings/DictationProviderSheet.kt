@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationProvider
import dev.ipf.whitenoise.android.audio.ConversationDictationProviderCapability
import dev.ipf.whitenoise.android.audio.ConversationDictationProviderChoice
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Re-enumerates on entry and return from provider setup. This list belongs only to this UI. */
@Composable
internal fun DictationProviderChooser(
    appState: WhiteNoiseAppState,
    onSelected: () -> Unit,
    onDismiss: () -> Unit,
) {
    var providers by remember { mutableStateOf<List<ConversationDictationProvider>?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshToken by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshToken++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(appState, refreshToken) { providers = appState.discoverDictationProviders() }
    val preferences by appState.conversationDictationPreferences.state.collectAsState()
    DictationProviderSheet(
        providers = providers,
        selected = preferences.providerSelection,
        onSelect = {
            appState.conversationDictationPreferences.setProviderSelection(it)
            onSelected()
        },
        onDismiss = onDismiss,
    )
}

/**
 * Wrapped text, scrolling and radio semantics also serve large fonts and RTL.
 * One short tree keeps loading, empty, and selectable states structurally aligned.
 */
@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DictationProviderSheet(
    providers: List<ConversationDictationProvider>?,
    selected: ConversationDictationProviderChoice?,
    onSelect: (ConversationDictationProviderChoice?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.selectableGroup().testTag("dictation_provider_choices").padding(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.dictation_provider_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.dictation_provider_explanation))
                }
            }
            item {
                SelectableSettingsRowWithSubtitle(
                    title = stringResource(R.string.dictation_provider_automatic),
                    subtitle = stringResource(R.string.dictation_provider_precedence),
                    selected = selected == null,
                    onClick = { onSelect(null) },
                )
            }
            if (providers == null) {
                item { Text(stringResource(R.string.dictation_provider_loading), Modifier.padding(16.dp)) }
            } else if (providers.isEmpty()) {
                item { Text(stringResource(R.string.dictation_provider_empty), Modifier.padding(16.dp)) }
            } else {
                providers.forEach { provider ->
                    item(key = provider.packageName) {
                        Text(provider.appName, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    provider.choices.forEach { choice ->
                        item(key = choice.component.flattenToString()) {
                            val capability =
                                stringResource(
                                    when (choice.capability) {
                                        ConversationDictationProviderCapability.InApp ->
                                            R.string.dictation_provider_in_app
                                        ConversationDictationProviderCapability.Activity ->
                                            R.string.dictation_provider_window
                                        ConversationDictationProviderCapability.KeyboardOnly ->
                                            R.string.dictation_provider_keyboard
                                        ConversationDictationProviderCapability.Unknown ->
                                            R.string.dictation_provider_unknown
                                    },
                                )
                            // Include the component when multiple engines share the same human label.
                            val engine =
                                if (provider.choices.size > 1) {
                                    "${choice.engineName}\n${choice.component.className}"
                                } else {
                                    choice.engineName
                                }
                            SelectableSettingsRowWithSubtitle(
                                title = engine,
                                subtitle = capability,
                                accessibilityLabel = "${provider.appName}, $engine, $capability",
                                selected = selected?.sameInstallation(choice) == true,
                                onClick = { onSelect(choice) },
                            )
                        }
                    }
                }
            }
        }
    }
}
