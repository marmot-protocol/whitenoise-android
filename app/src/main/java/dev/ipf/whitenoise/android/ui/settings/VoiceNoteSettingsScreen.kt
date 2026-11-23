package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail

@Composable
@Suppress("FunctionNaming")
internal fun VoiceNoteSettingsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val preferences = appState.voiceNotePreferences
    val dictation by appState.conversationDictationPreferences.state.collectAsState()
    SettingsScaffold(title = stringResource(R.string.settings_voice_notes), onBack = onBack) {
        SettingsList {
            item(key = "search.VoiceNotes.1") {
                SettingsGroup(
                    modifier = Modifier.testTag("settings.voice_notes.group"),
                    destination = SettingsDetail.VoiceNotes,
                    surface = "voice-note-controls",
                ) {
                    row("voice_send_on_release") { context ->
                        SettingsSwitch(
                            context = context,
                            title = stringResource(R.string.voice_send_on_release),
                            checked = preferences.sendHeldOnRelease,
                            onCheckedChange = preferences::updateSendHeldOnRelease,
                            leading = { SettingsLeadingIcon(R.drawable.ic_send) },
                        )
                    }
                    row("voice_transcribe_automatically") { context ->
                        SettingsSwitch(
                            context = context,
                            title = stringResource(R.string.voice_transcribe_automatically),
                            subtitle = stringResource(R.string.dictation_transcribe_voice_messages_subtitle),
                            explanation =
                                stringResource(R.string.explanation_appearance_dictation_transcribe_voice_messages),
                            checked = dictation.transcribeVoiceMessages,
                            onCheckedChange = appState.conversationDictationPreferences::setTranscribeVoiceMessages,
                            leading = { SettingsLeadingIcon(R.drawable.ic_mic) },
                        )
                    }
                }
            }
        }
    }
}

internal fun voiceNoteSettingsSearchEntries(): List<SettingsSearchEntry> =
    listOf(
        hubEntry(
            "home.VoiceNotes",
            R.string.settings_voice_notes,
            R.string.settings_voice_language,
            SettingsDetail.VoiceNotes,
        ),
        hubEntry(
            "VoiceNotes.voice_send_on_release",
            R.string.voice_send_on_release,
            R.string.settings_voice_notes,
            SettingsDetail.VoiceNotes,
            "search.VoiceNotes.1",
            "voice_send_on_release",
        ),
        hubEntry(
            "VoiceNotes.voice_transcribe_automatically",
            R.string.voice_transcribe_automatically,
            R.string.settings_voice_notes,
            SettingsDetail.VoiceNotes,
            "search.VoiceNotes.1",
            "voice_transcribe_automatically",
        ),
    )
