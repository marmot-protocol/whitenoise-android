package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.historyBackupHints
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCallout
import dev.ipf.whitenoise.android.ui.theme.Dimens

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
    // Aligned with the overview's other notices under its error banner.
    if (hinted) ChatHistoryBackupHintCard(modifier.padding(horizontal = Dimens.spaceLg).padding(top = 8.dp))
}

@Suppress("FunctionNaming")
@Composable
internal fun ChatHistoryBackupHintCard(modifier: Modifier = Modifier) {
    WhiteNoiseCallout(
        text = stringResource(R.string.history_backups_chat_hint),
        icon = R.drawable.ic_settings_backup_restore,
        modifier = modifier.testTag("chat_info.history_backup_hint"),
    )
}
