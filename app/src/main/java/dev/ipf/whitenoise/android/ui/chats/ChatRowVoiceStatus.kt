package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceNoteActivity
import dev.ipf.whitenoise.android.audio.VoiceNoteKey
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Reads voice notes passively: drawing the chat list never creates a recorder. */
internal fun WhiteNoiseAppState.chatListVoiceActivity(
    accountRef: String?,
    item: ChatListItem,
): VoiceNoteActivity {
    val account = accountRef ?: return VoiceNoteActivity.None
    return voiceNotes.activity(VoiceNoteKey(account, item.group.groupIdHex))
}

/** The chat's voice note outside the chat: the waveform glyph, red while recording and accent while it waits unsent. */
@Suppress("FunctionNaming")
@Composable
internal fun ChatRowVoiceStatus(activity: VoiceNoteActivity) {
    if (activity == VoiceNoteActivity.None) return
    val recording = activity == VoiceNoteActivity.Recording
    Icon(
        painter = painterResource(R.drawable.ic_voice_note),
        contentDescription =
            stringResource(if (recording) R.string.voice_row_recording else R.string.voice_row_unsent),
        modifier =
            Modifier
                .size(16.dp)
                .testTag(if (recording) "chat.row.voice.recording" else "chat.row.voice.unsent"),
        tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}
