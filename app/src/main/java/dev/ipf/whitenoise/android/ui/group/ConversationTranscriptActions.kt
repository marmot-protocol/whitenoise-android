package dev.ipf.whitenoise.android.ui.group

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.newchat.SettingsActionRow

/** Presents the real Share and SAF Save actions with their independent native progress and shared admission. */
@Suppress("FunctionNaming")
@Composable
internal fun ConversationTranscriptActions(
    shareInFlight: Boolean,
    saveInFlight: Boolean,
    sharePending: Boolean,
    accountAvailable: Boolean,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    SettingsActionRow(
        icon = Icons.Default.Description,
        title = stringResource(R.string.export_conversation_transcript),
        modifier = Modifier.testTag("chat_info.share_transcript"),
        enabled = !shareInFlight && !saveInFlight && accountAvailable,
        inProgress = shareInFlight,
        onClick = onShare,
    )
    SettingsActionRow(
        icon = Icons.Default.Download,
        title = stringResource(R.string.save_conversation_transcript),
        modifier = Modifier.testTag("chat_info.save_transcript"),
        enabled = !shareInFlight && !sharePending && !saveInFlight && accountAvailable,
        inProgress = saveInFlight,
        onClick = onSave,
    )
}
