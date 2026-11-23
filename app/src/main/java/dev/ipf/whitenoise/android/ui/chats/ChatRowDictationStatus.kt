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
import dev.ipf.whitenoise.android.audio.ConversationDictationActivity
import dev.ipf.whitenoise.android.audio.chatListActivity
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Reads dictation passively: drawing the chat list never creates a speech controller. */
internal fun WhiteNoiseAppState.chatListDictationActivity(
    accountRef: String?,
    item: ChatListItem,
): ConversationDictationActivity? =
    accountRef?.let {
        initializedConversationDictation()?.chatListActivity(it, item.group.groupIdHex)
    }

/** Live dictation marker: the app's dictation mic, red while speech is captured and accent while it becomes text. */
@Suppress("FunctionNaming")
@Composable
internal fun ChatRowDictationStatus(activity: ConversationDictationActivity) {
    val listening = activity == ConversationDictationActivity.Listening
    Icon(
        painter = painterResource(R.drawable.ic_mic),
        contentDescription =
            stringResource(if (listening) R.string.dictation_listening else R.string.dictation_processing),
        modifier =
            Modifier
                .size(16.dp)
                .testTag(if (listening) "chat.row.dictation.listening" else "chat.row.dictation.transcribing"),
        tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}
