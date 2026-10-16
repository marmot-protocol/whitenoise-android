package dev.ipf.whitenoise.android.ui.chats

import androidx.annotation.StringRes
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
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatDepartureStage
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.LocalChatDeleteObserver
import dev.ipf.whitenoise.android.state.LocalRemovalWording
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEntityPickerSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.group.GroupAbandonmentDialog
import dev.ipf.whitenoise.android.ui.group.GroupAbandonmentTarget
import dev.ipf.whitenoise.android.ui.group.groupAbandonmentBatchLabel
import kotlinx.coroutines.CompletableDeferred

/**
 * One reviewed selection for the batch dialog, captured with its account and runtime owner. When [abandon] is set the
 * batch abandons each chat on this device through MDK's forget (#3310) instead of leaving or deleting it, and
 * [abandonmentTargets] holds the identities its confirmation names.
 */
internal class PendingChatDeparture(
    items: List<ChatListItem>,
    controller: ChatsController,
    appState: WhiteNoiseAppState,
    titleCopy: GroupTitleCopy,
    val abandon: Boolean = false,
) {
    val owner = PendingLocalChatDelete.capture(items, controller, appState)
    val targets =
        items.distinctBy { it.group.groupIdHex.lowercase() }.map {
            ChatDepartureTarget(it.group.groupIdHex, chatListItemDisplayTitle(it, appState, titleCopy), it.isDm())
        }
    val abandonmentTargets: List<GroupAbandonmentTarget> =
        controller.accountRef
            ?.takeIf { abandon }
            ?.let { account ->
                val accountName = abandonmentAccountName(appState, account)
                targets.map { GroupAbandonmentTarget(account, accountName, it.groupId, it.title, it.isDm) }
            }.orEmpty()
}

internal class ChatDepartureUi {
    var confirmed by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var progress by mutableStateOf("")
    var result by mutableStateOf<ChatDepartureBatchResult?>(null)
    var decision by mutableStateOf<ChatDepartureDecision?>(null)
    var selected by mutableStateOf<AppGroupMemberRecordFfi?>(null)
    val stages = mutableStateMapOf<String, ChatDepartureStage>()
    var active = true
}

internal class ChatDepartureDecision(
    val target: ChatDepartureTarget,
    val members: List<AppGroupMemberRecordFfi>,
) {
    val answer = CompletableDeferred<AppGroupMemberRecordFfi?>()
}

internal class ChatDepartureActions(
    val onDismiss: () -> Unit,
    val onAccepted: () -> Unit = {},
    val canStart: () -> Boolean = { true },
    val onBusyChange: (Boolean) -> Unit = {},
)

/** The same captured selection drives single-row and bulk departure, with per-group skip and scoped retry. */
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun ChatDepartureBatchDialog(
    request: PendingChatDeparture,
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    actions: ChatDepartureActions,
) {
    val ui = remember(request) { ChatDepartureUi() }
    val actionLabel = stringResource(request.actionLabel())
    val isCurrent = { request.owner.isCurrent(appState, controller) }
    DisposableEffect(ui) {
        onDispose {
            ui.active = false
            // A navigation cannot strand the batch waiting for a vanished handover dialog.
            ui.decision?.answer?.complete(null)
        }
    }

    fun start(targets: List<ChatDepartureTarget>) {
        if (ui.busy || !isCurrent() || !actions.canStart()) return
        if (!ui.confirmed) actions.onAccepted()
        ui.confirmed = true
        ui.busy = true
        actions.onBusyChange(true)
        ui.result = null
        appState.launchMutation {
            try {
                val callbacks = departureCallbacks(request, controller, ui, isCurrent)
                val result = runChatDepartureBatch(targets, isCurrent, callbacks)
                if (isCurrent()) ui.result = result
            } finally {
                ui.busy = false
                actions.onBusyChange(false)
                ui.decision?.answer?.complete(null)
                ui.decision = null
            }
        }
    }
    if (!ui.confirmed) {
        ChatDepartureReview(request, actionLabel, onConfirm = { start(request.targets) }, onDismiss = actions.onDismiss)
    } else if (ui.decision != null) {
        ChatDepartureSuccessor(ui, appState)
    } else {
        WhiteNoiseAlertDialog(
            onDismissRequest = actions.onDismiss,
            modifier = Modifier.testTag("chat.departure.progress"),
            title = { Text(actionLabel) },
            text = {
                val result = ui.result
                if (result == null) {
                    Text(ui.progress)
                } else {
                    ChatDepartureResultContent(result, request.targets, ui.stages, abandon = request.abandon)
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
            dismissButton = { TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.close)) } },
        )
    }
}

/** The batch's action label: abandonment, a direct chat's local delete, or a group's leave and delete. */
@StringRes
private fun PendingChatDeparture.actionLabel(): Int =
    when {
        abandon -> groupAbandonmentBatchLabel(targets.map { it.isDm })
        targets.all { it.isDm } -> R.string.delete_from_device
        else -> R.string.leave_and_delete
    }

/**
 * The confirmation before anything is submitted. An abandonment uses the shared review that names every chat and the
 * acting account (#3282), and a departure keeps its handover-aware confirmation.
 */
@Composable
@Suppress("FunctionNaming")
private fun ChatDepartureReview(
    request: PendingChatDeparture,
    actionLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (request.abandon) {
        GroupAbandonmentDialog(targets = request.abandonmentTargets, onConfirm = onConfirm, onDismiss = onDismiss)
    } else {
        ConfirmDialog(
            title = actionLabel,
            message = stringResource(R.string.chat_departure_confirm, request.targets.size),
            confirmLabel = actionLabel,
            destructive = true,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The batch steps. An abandonment takes the same journaled forget as the row action, never a leave or a receiving
 * delete (#3310), and a departure prepares and confirms any handover before it leaves.
 */
private fun departureCallbacks(
    request: PendingChatDeparture,
    controller: ChatsController,
    ui: ChatDepartureUi,
    isCurrent: () -> Boolean,
): ChatDepartureCallbacks {
    val onProgress = { title: String, completed: Int, total: Int -> ui.progress = "$completed / $total · $title" }
    if (request.abandon) return abandonmentDepartureCallbacks(controller, onProgress)
    return ChatDepartureCallbacks(
        controller::prepareChatListDeparture,
        choose = { target, members -> chooseDepartureSuccessor(ui, target, members) },
        remove = { target, successor -> removeChatDepartureTarget(target, successor, controller, ui, isCurrent) },
        onProgress = onProgress,
    )
}

private suspend fun chooseDepartureSuccessor(
    ui: ChatDepartureUi,
    target: ChatDepartureTarget,
    members: List<AppGroupMemberRecordFfi>,
): AppGroupMemberRecordFfi? {
    if (!ui.active) return null
    val decision = ChatDepartureDecision(target, members)
    ui.selected = members.singleOrNull()
    ui.decision = decision
    return try {
        decision.answer.await()
    } finally {
        if (ui.decision === decision) ui.decision = null
    }
}

private suspend fun removeChatDepartureTarget(
    target: ChatDepartureTarget,
    successor: AppGroupMemberRecordFfi?,
    controller: ChatsController,
    ui: ChatDepartureUi,
    isCurrent: () -> Boolean,
): Boolean =
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
                            if (isCurrent()) ui.stages[target.groupId] = ChatDepartureStage.CLEANUP_PENDING
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

@Composable
@Suppress("FunctionNaming")
internal fun ChatDepartureSuccessor(
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

/**
 * Readable per-group partial stages stay scrollable without pushing the dialog actions out of reach. An [abandon]
 * batch reports each unfinished chat in abandonment wording, since it has no leave or handover stages.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ChatDepartureResultContent(
    result: ChatDepartureBatchResult,
    targets: List<ChatDepartureTarget>,
    stages: Map<String, ChatDepartureStage>,
    abandon: Boolean = false,
) {
    Column(Modifier.fadingVerticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.chat_departure_result, result.completed, result.outstanding.size, result.skipped))
        targets.filter { it.groupId in result.outstanding }.forEach { target ->
            val detail = departureOutcomeDetail(target, stages[target.groupId], abandon)
            Text("${target.title}: ${stringResource(detail)}")
        }
    }
}

/** The unfinished chat's detail: abandonment wording in an abandon batch, otherwise the departure stage it reached. */
@StringRes
private fun departureOutcomeDetail(
    target: ChatDepartureTarget,
    stage: ChatDepartureStage?,
    abandon: Boolean,
): Int =
    when {
        abandon -> LocalRemovalWording.abandonment(target.isDm).failureTitle
        stage == ChatDepartureStage.ADMIN_GRANTED -> R.string.chat_departure_admin_granted
        stage == ChatDepartureStage.ADMIN_DEMOTED -> R.string.toast_demoted_but_couldnt_leave
        stage == ChatDepartureStage.LEFT || stage == ChatDepartureStage.CLEANUP_PENDING ->
            R.string.toast_left_chat_delete_failed
        target.isDm -> R.string.toast_couldnt_delete_chat
        else -> R.string.toast_leave_not_confirmed_history_kept
    }
