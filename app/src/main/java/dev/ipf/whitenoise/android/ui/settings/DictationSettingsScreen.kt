@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationDeliveryMode
import dev.ipf.whitenoise.android.state.ConversationDictationPreferenceState
import dev.ipf.whitenoise.android.state.ConversationDictationPreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.SettingsGroup

/** Local endpointing and delivery preferences for app-owned composer dictation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DictationSettingsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val preferences by appState.conversationDictationPreferences.state.collectAsState()
    var finishSheetOpen by remember { mutableStateOf(false) }
    var resultSheetOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dictation_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        DictationSettingsContent(
            preferences = preferences,
            onFinishClick = { finishSheetOpen = true },
            onResultClick = { resultSheetOpen = true },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }

    if (finishSheetOpen) {
        DictationFinishSheet(
            selectedMillis = preferences.finishAfterSilenceMillis,
            onSelect = { millis ->
                appState.setConversationDictationFinishAfterSilence(millis)
                finishSheetOpen = false
            },
            onDismiss = { finishSheetOpen = false },
        )
    }
    if (resultSheetOpen) {
        DictationResultSheet(
            selected = preferences.deliveryMode,
            onSelect = { mode ->
                appState.setConversationDictationDeliveryMode(mode)
                resultSheetOpen = false
            },
            onDismiss = { resultSheetOpen = false },
        )
    }
}

/** Renders the scrollable explanation, preference rows, and conditional send warning. */
@Composable
private fun DictationSettingsContent(
    preferences: ConversationDictationPreferenceState,
    onFinishClick: () -> Unit,
    onResultClick: () -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.dictation_settings_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item { DictationPreferenceGroup(preferences, onFinishClick, onResultClick) }
        if (preferences.deliveryMode == ConversationDictationDeliveryMode.SendOnFinish) {
            item {
                Text(
                    text = stringResource(R.string.dictation_send_safety_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** Presents the two settings without making their modal-selection state part of the row model. */
@Composable
private fun DictationPreferenceGroup(
    preferences: ConversationDictationPreferenceState,
    onFinishClick: () -> Unit,
    onResultClick: () -> Unit,
) {
    val finishSubtitle =
        preferences.finishAfterSilenceMillis?.let { millis ->
            stringResource(R.string.dictation_finish_after_silence, millis / MILLIS_PER_SECOND)
        } ?: stringResource(R.string.dictation_finish_manual)
    val resultSubtitle =
        stringResource(
            when (preferences.deliveryMode) {
                ConversationDictationDeliveryMode.PasteIntoDraft -> R.string.dictation_result_paste
                ConversationDictationDeliveryMode.SendOnFinish -> R.string.dictation_result_send
            },
        )
    SettingsGroup {
        item {
            SettingsRow(
                title = stringResource(R.string.dictation_finish_title),
                subtitle = finishSubtitle,
                icon = Icons.Filled.Timer,
                onClick = onFinishClick,
            )
        }
        item {
            SettingsRow(
                title = stringResource(R.string.dictation_result_title),
                subtitle = resultSubtitle,
                icon = Icons.AutoMirrored.Filled.Send,
                onClick = onResultClick,
            )
        }
    }
}

/** Offers manual completion plus the supported conservative silence thresholds. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictationFinishSheet(
    selectedMillis: Long?,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.selectableGroup().padding(bottom = 24.dp)) {
            SelectableSettingsRow(
                title = stringResource(R.string.dictation_finish_manual),
                selected = selectedMillis == null,
                onClick = { onSelect(null) },
            )
            ConversationDictationPreferences.ALLOWED_SILENCE_MILLIS.sorted().forEach { millis ->
                SelectableSettingsRow(
                    title =
                        stringResource(
                            R.string.dictation_finish_after_silence,
                            millis / MILLIS_PER_SECOND,
                        ),
                    selected = selectedMillis == millis,
                    onClick = { onSelect(millis) },
                )
            }
        }
    }
}

/** Offers paste-by-default and the explicit send-on-finish opt-in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictationResultSheet(
    selected: ConversationDictationDeliveryMode,
    onSelect: (ConversationDictationDeliveryMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.selectableGroup().padding(bottom = 24.dp)) {
            SelectableSettingsRowWithSubtitle(
                title = stringResource(R.string.dictation_result_paste),
                subtitle = stringResource(R.string.dictation_result_paste_description),
                selected = selected == ConversationDictationDeliveryMode.PasteIntoDraft,
                onClick = { onSelect(ConversationDictationDeliveryMode.PasteIntoDraft) },
            )
            SelectableSettingsRowWithSubtitle(
                title = stringResource(R.string.dictation_result_send),
                subtitle = stringResource(R.string.dictation_result_send_description),
                selected = selected == ConversationDictationDeliveryMode.SendOnFinish,
                onClick = { onSelect(ConversationDictationDeliveryMode.SendOnFinish) },
            )
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000L
