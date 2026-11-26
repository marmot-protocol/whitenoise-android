package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.LocalChatDeleteObserver
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.deleteLocalChatsBatch
import dev.ipf.whitenoise.android.state.localDeleteBatchRetryNotice
import dev.ipf.whitenoise.android.state.presentStoppedLocalChatDeleteBatch
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupActingAccountIdentity
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupReviewDestinationIdentity
import dev.ipf.whitenoise.android.ui.chats.newchat.MemberReviewGroup
import dev.ipf.whitenoise.android.ui.common.rememberChatListGroupAvatar

/** Hosts the local-delete review for the request captured by the chat list. */
@Composable
@Suppress("FunctionNaming", "LongParameterList") // Compose naming and explicit caller-owned state.
internal fun ChatListLocalDeleteConfirmation(
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    pendingRequest: MutableState<PendingLocalChatDelete?>,
    busyChats: Set<String>,
    groupTitleCopy: GroupTitleCopy,
    onAccepted: () -> Unit,
) {
    val request = pendingRequest.value ?: return
    val context = LocalContext.current
    val isCurrent = { request.isCurrent(appState, controller) }
    ChatDeleteConfirmationDialog(
        ownerKey = request,
        count = request.groupIds.size,
        confirmEnabled = isCurrent(),
        identity = {
            GroupActingAccountIdentity(appState, request.actingAccountRef)
            request.reviewItems.forEach { item ->
                val avatar = rememberChatListGroupAvatar(appState, item)
                GroupReviewDestinationIdentity(
                    MemberReviewGroup(
                        item.group.groupIdHex,
                        chatListItemDisplayTitle(item, appState, groupTitleCopy),
                        avatar.pictureUrl,
                        avatar.image,
                        groupAnimation = avatar.groupAnimation,
                    ),
                    actionLabel = stringResource(R.string.delete_from_device),
                )
            }
        },
        onConfirm = {
            if (pendingRequest.value !== request) return@ChatDeleteConfirmationDialog
            pendingRequest.value = null
            if (!isCurrent()) return@ChatDeleteConfirmationDialog
            if (request.groupIds.any { it in busyChats }) {
                return@ChatDeleteConfirmationDialog
            }
            onAccepted()
            appState.launchMutation {
                deleteConfirmedLocalChats(appState, controller, request, context, isCurrent)
            }
        },
        onDismiss = { if (pendingRequest.value === request) pendingRequest.value = null },
    )
}

private suspend fun deleteConfirmedLocalChats(
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    request: PendingLocalChatDelete,
    context: Context,
    isCurrent: () -> Boolean,
) {
    var failure: Throwable? = null
    var cleanupDeferred = false
    val observer =
        LocalChatDeleteObserver(
            onFailure = { failure = it },
            onCleanupDeferred = { cleanupDeferred = true },
        )
    val result =
        deleteLocalChatsBatch(request.groupIds, isCurrent) { groupId ->
            controller.deleteGroupLocalFromChatList(
                groupId,
                notify = false,
                observer = observer,
                requireNativeEligibility = request.requireNativeEligibility,
            )
        }
    if (isCurrent() && result.deleted < result.total) {
        val remaining = request.remaining(result.deleted)
        appState.presentStoppedLocalChatDeleteBatch(
            result,
            failure,
            notice =
                appState.localDeleteBatchRetryNotice(
                    controller,
                    remaining.groupIds,
                    isCurrent,
                    requireNativeEligibility = request.requireNativeEligibility,
                ),
        )
    } else if (isCurrent() && result.deleted > 0 && !cleanupDeferred) {
        appState.presentTransient(
            context.resources.getQuantityString(
                R.plurals.toast_chat_list_chats_deleted,
                result.deleted,
                result.deleted,
            ),
        )
    }
}
