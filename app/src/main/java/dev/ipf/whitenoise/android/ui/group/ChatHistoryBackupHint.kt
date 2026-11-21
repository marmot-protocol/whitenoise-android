package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.historyBackupHints
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

/**
 * Chat Info's words-again hint (#3655): a restore found backed-up history for this chat before this device joined it,
 * so entering the same recovery words again can bring that history in. Read once per account, chat and runtime.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ChatHistoryBackupHint(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    modifier: Modifier = Modifier,
) {
    val accountRef = controller.boundAccountRef
    val groupIdHex = controller.group.groupIdHex
    val hinted by produceState(false, accountRef, groupIdHex, appState.runtimeGeneration) {
        value = appState.historyBackupHints(accountRef, groupIdHex).isNotEmpty()
    }
    if (hinted) ChatHistoryBackupHintCard(modifier)
}

@Suppress("FunctionNaming")
@Composable
internal fun ChatHistoryBackupHintCard(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = modifier.fillMaxWidth().testTag("chat_info.history_backup_hint"),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_settings_backup_restore),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(stringResource(R.string.history_backups_chat_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}
