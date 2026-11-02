package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListBatchCommand
import dev.ipf.whitenoise.android.state.ChatListBatchOutcome
import dev.ipf.whitenoise.android.state.ChatListBatchResult
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.launch

/** Localized explicit destination used by folder dialogs and icon recovery status. */
internal fun ChatListBatchCommand.labelResource(): Int =
    when (this) {
        ChatListBatchCommand.READ -> R.string.chat_row_action_mark_read
        ChatListBatchCommand.UNREAD -> R.string.chat_row_action_mark_unread
        ChatListBatchCommand.MUTE -> R.string.chat_row_action_mute
        ChatListBatchCommand.UNMUTE -> R.string.chat_row_action_unmute
        ChatListBatchCommand.PIN -> R.string.chat_row_action_pin
        ChatListBatchCommand.UNPIN -> R.string.chat_row_action_unpin
        ChatListBatchCommand.ARCHIVE -> R.string.archive
        ChatListBatchCommand.RESTORE -> R.string.unarchive
        ChatListBatchCommand.EXCLUDE_FOLDER -> R.string.folder_exclude_chat
    }

/** Recovery retains the captured scope even when a mutation removes every selected filtered row. */
@Suppress("FunctionNaming")
@Composable
internal fun ChatListBatchDialog(
    request: PendingChatListBatch,
    controller: ChatsController,
    onDismiss: () -> Unit,
) {
    var targets by remember(request) { mutableStateOf(request.ids) }
    var attempt by remember(request) { mutableIntStateOf(0) }
    var completed by remember(request) { mutableIntStateOf(0) }
    var checking by remember(request) { mutableStateOf(false) }
    var result by remember(request) { mutableStateOf<ChatListBatchResult?>(null) }
    var previousResult by remember(request) { mutableStateOf(ChatListBatchResult(emptyMap())) }
    val current = controller.chatListActionSessionIsCurrent(request.owner)
    LaunchedEffect(request, attempt) {
        completed = 0
        val attemptResult =
            (
                if (checking) {
                    controller.reconcileChatListBatch(previousResult, request.command, request.owner)
                } else {
                    controller.runChatListBatch(targets, request.command, request.owner, request.folderId) { done, _ ->
                        completed = done
                    }
                }
            )
        result =
            if (attemptResult == null && previousResult.outcomes.isEmpty()) {
                ChatListBatchResult(targets.associateWith { ChatListBatchOutcome.NOT_STARTED })
            } else {
                previousResult.withAttempt(attemptResult)
            }
        previousResult = result!!
        checking = false
    }
    ChatListBatchStatusDialog(
        request.command,
        completed,
        targets.size,
        result,
        current,
        onRetry = {
            result?.let { ready ->
                targets = ready.retryIds
                result = null
                attempt++
            }
        },
        onDismiss = onDismiss,
        onCheck = {
            result?.let { ready ->
                targets =
                    ready.outcomes
                        .filterValues { it == ChatListBatchOutcome.UNCONFIRMED }
                        .keys
                        .toList()
                checking = true
                result = null
                attempt++
            }
        },
    )
}

/** Stateless rendering permits exact failure/progress visual and accessibility coverage. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun ChatListBatchStatusDialog(
    command: ChatListBatchCommand,
    completed: Int,
    total: Int,
    result: ChatListBatchResult?,
    current: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onCheck: () -> Unit = {},
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = { if (result != null) onDismiss() },
        title = { Text(stringResource(command.labelResource())) },
        text = {
            val outcome = result
            Text(
                if (outcome == null) {
                    stringResource(R.string.chat_batch_progress, completed, total)
                } else {
                    stringResource(
                        R.string.chat_batch_result,
                        outcome.changed,
                        outcome.satisfied,
                        outcome.skipped,
                        outcome.failed,
                        outcome.notStarted,
                        outcome.unconfirmed,
                    )
                },
            )
        },
        confirmButton = {
            val outcome = result
            if (outcome != null) {
                FlowRow(horizontalArrangement = Arrangement.End) {
                    val buttonSize = Modifier.heightIn(min = 48.dp)
                    if (current && outcome.unconfirmed > 0) {
                        TextButton(
                            onClick = onCheck,
                            modifier = buttonSize,
                        ) { Text(stringResource(R.string.chat_batch_check_results)) }
                    }
                    if (current && outcome.retryIds.isNotEmpty()) {
                        TextButton(onClick = onRetry, modifier = buttonSize) { Text(stringResource(R.string.retry)) }
                    }
                    TextButton(onClick = onDismiss, modifier = buttonSize) { Text(stringResource(R.string.close)) }
                }
            }
        },
    )
}

/** Recovery stays in the list after filtering removes selected rows; ordinary success has no surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
@Composable
internal fun ChatListBatchRecoveryControls(
    command: ChatListBatchCommand,
    result: ChatListBatchResult,
    current: Boolean,
    running: Boolean,
    onCheck: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val summary =
        stringResource(command.labelResource()) + "\n" +
            stringResource(
                R.string.chat_batch_result,
                result.changed,
                result.satisfied,
                result.skipped,
                result.failed,
                result.notStarted,
                result.unconfirmed,
            )
    val tooltip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    FlowRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)
                .testTag("chats.batchRecovery"),
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(summary) } },
            state = tooltip,
        ) {
            IconButton(
                onClick = { scope.launch { tooltip.show() } },
                enabled = !running,
                modifier = Modifier.size(48.dp).testTag("chats.batchRecovery.summary"),
            ) { Icon(painterResource(R.drawable.ic_error), contentDescription = summary) }
        }
        if (current && result.unconfirmed > 0) {
            DirectSelectionAction(
                label = stringResource(R.string.chat_batch_check_results),
                icon = R.drawable.ic_search,
                enabled = !running,
                modifier = Modifier.testTag("chats.batchRecovery.check"),
                onClick = onCheck,
            )
        }
        if (current && result.retryIds.isNotEmpty()) {
            DirectSelectionAction(
                label = stringResource(R.string.retry),
                icon = R.drawable.ic_refresh,
                enabled = !running,
                modifier = Modifier.testTag("chats.batchRecovery.retry"),
                onClick = onRetry,
            )
        }
        DirectSelectionAction(
            label = stringResource(R.string.dismiss),
            icon = R.drawable.ic_close,
            enabled = !running,
            modifier = Modifier.testTag("chats.batchRecovery.close"),
            onClick = onDismiss,
        )
    }
}
