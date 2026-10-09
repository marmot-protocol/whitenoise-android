package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatDepartureStage
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.LocalChatDeleteObserver
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEntityPickerSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import kotlinx.coroutines.CompletableDeferred

internal class PendingChatDeparture(
    items: List<ChatListItem>,
    controller: ChatsController,
    appState: WhiteNoiseAppState,
) {
    val owner = PendingLocalChatDelete.capture(items, controller, appState)
    val targets =
        items.distinctBy { it.group.groupIdHex.lowercase() }.map {
            ChatDepartureTarget(it.group.groupIdHex, it.group.name.orEmpty(), it.isDm())
        }
}

private class ChatDepartureUi {
    var confirmed by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var progress by mutableStateOf("")
    var result by mutableStateOf<ChatDepartureBatchResult?>(null)
    var decision by mutableStateOf<ChatDepartureDecision?>(null)
    var selected by mutableStateOf<AppGroupMemberRecordFfi?>(null)
    val stages = mutableStateMapOf<String, ChatDepartureStage>()
    var active = true
}

private class ChatDepartureDecision(
    val target: ChatDepartureTarget,
    val members: List<AppGroupMemberRecordFfi>,
) {
    val answer = CompletableDeferred<AppGroupMemberRecordFfi?>()
}

/** The same captured selection drives single-row and bulk departure, with per-group skip and scoped retry. */
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun ChatDepartureBatchDialog(
    request: PendingChatDeparture,
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    onDismiss: () -> Unit,
    onAccepted: () -> Unit = {},
    canStart: () -> Boolean = { true },
    onBusyChange: (Boolean) -> Unit = {},
) {
    val ui = remember(request) { ChatDepartureUi() }
    val actionLabel =
        stringResource(
            if (request.targets.all { it.isDm }) R.string.delete_from_device else R.string.leave_and_delete,
        )
    val isCurrent = { request.owner.isCurrent(appState, controller) }
    DisposableEffect(ui) {
        onDispose {
            ui.active = false
            // A navigation cannot strand the batch waiting for a vanished handover dialog.
            ui.decision?.answer?.complete(null)
        }
    }

    fun start(targets: List<ChatDepartureTarget>) {
        if (ui.busy || !isCurrent() || !canStart()) return
        if (!ui.confirmed) onAccepted()
        ui.confirmed = true
        ui.busy = true
        onBusyChange(true)
        ui.result = null
        appState.launchMutation {
            try {
                val result =
                    runChatDepartureBatch(
                        targets,
                        isCurrent,
                        ChatDepartureCallbacks(
                            controller::prepareChatListDeparture,
                            choose = { target, members ->
                                if (!ui.active) {
                                    null
                                } else {
                                    val decision = ChatDepartureDecision(target, members)
                                    ui.selected = members.singleOrNull()
                                    ui.decision = decision
                                    decision.answer.await().also { ui.decision = null }
                                }
                            },
                            remove = { target, successor ->
                                when {
                                    target.isDm -> {
                                        var deferred = false
                                        val deleted =
                                            controller.deleteGroupLocalFromChatList(
                                                target.groupId,
                                                notify = false,
                                                observer =
                                                    LocalChatDeleteObserver(onCleanupDeferred = {
                                                        deferred = true
                                                        if (isCurrent()) {
                                                            ui.stages[target.groupId] =
                                                                ChatDepartureStage.CLEANUP_PENDING
                                                        }
                                                    }),
                                            )
                                        deleted && !deferred
                                    }
                                    successor != null ->
                                        controller.transferAdminThenDeleteFromChatList(target.groupId, successor) {
                                            if (isCurrent()) ui.stages[target.groupId] = it
                                        }
                                    else ->
                                        controller.leaveAndDeleteFromChatList(target.groupId) {
                                            if (isCurrent()) ui.stages[target.groupId] = it
                                        }
                                }
                            },
                            onProgress = { title, completed, total -> ui.progress = "$completed / $total · $title" },
                        ),
                    )
                if (isCurrent()) ui.result = result
            } finally {
                ui.busy = false
                onBusyChange(false)
                ui.decision?.answer?.complete(null)
                ui.decision = null
            }
        }
    }
    if (!ui.confirmed) {
        ConfirmDialog(
            title = actionLabel,
            message = stringResource(R.string.chat_departure_confirm, request.targets.size),
            confirmLabel = actionLabel,
            destructive = true,
            onConfirm = { start(request.targets) },
            onDismiss = onDismiss,
        )
    } else if (ui.decision != null) {
        ChatDepartureSuccessor(ui, appState)
    } else {
        WhiteNoiseAlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag("chat.departure.progress"),
            title = { Text(actionLabel) },
            text = {
                val result = ui.result
                if (result == null) {
                    Text(ui.progress)
                } else {
                    ChatDepartureResultContent(result, request.targets, ui.stages)
                }
            },
            icon = if (ui.busy) ({ CircularProgressIndicator() }) else null,
            confirmButton = {
                ui.result?.takeIf { it.outstanding.isNotEmpty() }?.let { result ->
                    TextButton(onClick = { start(request.targets.filter { it.groupId in result.outstanding }) }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ChatDepartureSuccessor(
    ui: ChatDepartureUi,
    appState: WhiteNoiseAppState,
) {
    val decision = ui.decision ?: return
    val selected = ui.selected
    if (selected == null) {
        WhiteNoiseEntityPickerSheet(
            title = stringResource(R.string.transfer_admin),
            description = decision.target.title,
            items =
                decision.members.map {
                    WhiteNoisePickerItem(it.memberIdHex, appState.displayName(it.memberIdHex))
                },
            onSelect = { id -> ui.selected = decision.members.firstOrNull { it.memberIdHex == id } },
            onDismiss = { decision.answer.complete(null) },
        )
    } else {
        ConfirmDialog(
            title = stringResource(R.string.confirm_transfer_admin_title),
            message =
                stringResource(
                    R.string.confirm_sole_admin_transfer_then_leave_message,
                    appState.displayName(selected.memberIdHex),
                ),
            confirmLabel = stringResource(R.string.leave_and_delete),
            destructive = true,
            onConfirm = { decision.answer.complete(selected) },
            onDismiss = { decision.answer.complete(null) },
        )
    }
}

/** Readable per-group partial stages stay scrollable without pushing the dialog actions out of reach. */
@Composable
internal fun ChatDepartureResultContent(
    result: ChatDepartureBatchResult,
    targets: List<ChatDepartureTarget>,
    stages: Map<String, ChatDepartureStage>,
) {
    Column(Modifier.fadingVerticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.chat_departure_result, result.completed, result.outstanding.size, result.skipped))
        targets.filter { it.groupId in result.outstanding }.forEach { target ->
            val detail =
                when (stages[target.groupId]) {
                    ChatDepartureStage.ADMIN_GRANTED -> R.string.chat_departure_admin_granted
                    ChatDepartureStage.ADMIN_DEMOTED -> R.string.toast_demoted_but_couldnt_leave
                    ChatDepartureStage.LEFT, ChatDepartureStage.CLEANUP_PENDING ->
                        R.string.toast_left_chat_delete_failed
                    else ->
                        if (target.isDm) {
                            R.string.toast_couldnt_delete_chat
                        } else {
                            R.string.toast_leave_not_confirmed_history_kept
                        }
                }
            Text("${target.title}: ${stringResource(detail)}")
        }
    }
}
