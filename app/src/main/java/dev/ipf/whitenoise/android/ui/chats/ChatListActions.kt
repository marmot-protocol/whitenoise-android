package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListActionOwner
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatListPinMoveResult
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.TransientNoticeKind
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.state.setConversationMuted
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The chat list's single-chat actions: archive or restore, add to a folder, mark read or unread, mute, pin, and
 * move a pinned chat or the selected pinned chats.
 *
 * An instance lasts as long as the screen keeps the owners passed to the constructor: the app state, controller,
 * the action and batch scopes, the pin follower, the folder handoff and the head-demotion state. Every other value
 * comes from [latest], the screen's most recent composition. Each action reads it once when it runs and uses that
 * one set of values throughout, including inside the mutation it launches, as the screen-local functions it
 * replaces did with the values they captured.
 */
@Suppress("LongParameterList") // Explicit screen owners, so a stale capture cannot hide in a provider.
internal class ChatListActions(
    private val appState: WhiteNoiseAppState,
    private val controller: ChatsController,
    private val actionScope: AtomicBoolean,
    private val batchScope: AtomicBoolean,
    private val pinFollow: ChatPinFollowOwner,
    private val folderHandoff: ChatFolderHandoffState,
    pendingHeadDemotionState: MutableState<ChatListHeadDemotion?>,
    nextHeadDemotionTransactionIdState: MutableLongState,
    private val latest: State<Inputs>,
) {
    private var pendingHeadDemotion by pendingHeadDemotionState
    private var nextHeadDemotionTransactionId by nextHeadDemotionTransactionIdState

    /**
     * The values one composition of the screen computed for these actions. A plain class, compared by identity, so
     * updating it never compares the visible rows.
     */
    @Suppress("LongParameterList") // One field per value the old local functions captured.
    internal class Inputs(
        val context: Context,
        val resources: Resources,
        val ordinaryActiveList: Boolean,
        val visibleItems: List<ChatListItem>,
        val visibleChatIds: Set<String>,
        val chatListState: LazyListState,
        val viewportGeneration: Long,
        val pinnedIndex: (ChatListItem) -> Int?,
        val clearSelection: () -> Unit,
    )

    /**
     * This composition's values. A swipe commit reads them before it suspends on the native row lookup and passes
     * them to the toggle it then runs, which therefore sees the values of the commit, as the captured locals did.
     */
    fun currentInputs(): Inputs = latest.value

    fun archiveChats(
        items: List<ChatListItem>,
        archive: Boolean,
    ) {
        with(latest.value) {
            if (items.isEmpty()) return
            val owner = controller.captureChatListActionOwner { actionScope.get() } ?: return
            clearSelection()
            appState.launchMutation {
                val succeeded =
                    controller.setArchivedForOwner(
                        groupIds = items.map { it.group.groupIdHex },
                        archived = archive,
                        notify = false,
                        owner = owner,
                    )
                if (succeeded > 0 && controller.chatListActionIsCurrent(owner)) {
                    val pluralRes =
                        if (archive) {
                            R.plurals.toast_chat_list_chats_archived
                        } else {
                            R.plurals.toast_chat_list_chats_restored
                        }
                    appState.presentTransient(context.resources.getQuantityString(pluralRes, succeeded, succeeded))
                }
            }
        }
    }

    fun openFolderPicker(items: List<ChatListItem>) {
        folderHandoff.pickerChatIds =
            items
                .map { it.group.groupIdHex.lowercase(Locale.ROOT) }
                .takeIf { it.isNotEmpty() }
    }

    fun markChatRead(
        item: ChatListItem,
        unread: Boolean,
        resolvedOwner: ChatListActionOwner? = null,
        inputs: Inputs = latest.value,
    ) {
        with(inputs) {
            val owner = resolvedOwner ?: controller.captureChatListActionOwner { actionScope.get() } ?: return
            clearSelection()
            appState.launchMutation {
                val current = if (resolvedOwner != null) item else controller.resolveChatListActionItem(item.id, owner)
                val allowed =
                    if (unread) current?.actions?.canMarkUnread != false else current?.actions?.canMarkRead != false
                if (current != null && allowed) {
                    if (unread) controller.markUnread(current, owner) else controller.markAllRead(current, owner)
                }
            }
        }
    }

    fun toggleChatMute(
        item: ChatListItem,
        muted: Boolean,
        resolvedOwner: ChatListActionOwner? = null,
        inputs: Inputs = latest.value,
    ) {
        with(inputs) {
            val owner = resolvedOwner ?: controller.captureChatListActionOwner { actionScope.get() } ?: return
            clearSelection()
            appState.launchMutation {
                val current = if (resolvedOwner != null) item else controller.resolveChatListActionItem(item.id, owner)
                val allowed = if (muted) current?.actions?.canUnmute != false else current?.actions?.canMute != false
                if (current != null && allowed && controller.chatListActionIsCurrent(owner)) {
                    appState.setConversationMuted(
                        current.group.groupIdHex,
                        !muted,
                        onCommitted = { controller.refreshChatListActionProjection(owner) },
                        isCurrent = { controller.chatListActionIsCurrent(owner) },
                        completionIsCurrent = { controller.chatListActionSessionIsCurrent(owner) },
                    )
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // Moved unchanged: head demotion and the pin follow stay with the toggle.
    fun toggleChatPin(
        item: ChatListItem,
        resolvedOwner: ChatListActionOwner? = null,
        inputs: Inputs = latest.value,
    ) {
        with(inputs) {
            val owner = resolvedOwner ?: controller.captureChatListActionOwner { actionScope.get() } ?: return
            if (item.group.archived) return
            val nextPinned = !item.pinned()
            val headDemotion =
                if (!nextPinned && ordinaryActiveList && visibleItems.firstOrNull()?.id == item.id) {
                    nextHeadDemotionTransactionId += 1L
                    ChatListHeadDemotion(
                        chatId = item.id,
                        transactionId = nextHeadDemotionTransactionId,
                        viewportAnchor = chatListState.chatListViewportAnchor(visibleChatIds),
                        viewportGeneration = viewportGeneration,
                    )
                } else {
                    null
                }
            if (headDemotion != null) {
                pendingHeadDemotion = headDemotion
            } else if (nextPinned && pendingHeadDemotion?.chatId == item.id) {
                pendingHeadDemotion = null
            }
            // Captured at the gesture: a pin that commits after a folder/query/account change follows nothing.
            val followScope = pinFollow.currentScope.takeIf { nextPinned }
            clearSelection()
            appState.launchMutation {
                val current = if (resolvedOwner != null) item else controller.resolveChatListActionItem(item.id, owner)
                val allowed =
                    if (nextPinned) current?.actions?.canPin != false else current?.actions?.canUnpin != false
                val eligible = current != null && !current.group.archived && allowed
                val succeeded = eligible && current != null && controller.setPinned(current, nextPinned, owner)
                if (!succeeded && pendingHeadDemotion == headDemotion) {
                    pendingHeadDemotion = null
                }
                if (succeeded && nextPinned && controller.chatListActionSessionIsCurrent(owner)) {
                    pinFollow.issue(followScope, setOf(item.id))
                }
            }
        }
    }

    /**
     * Moves [ids] by [delta] within MDK's complete pin order, never the loaded window, and tells the
     * user when the order did not change. Selection and single-row moves share this path (#3709).
     */
    fun movePinnedChats(
        ids: Set<String>,
        delta: Int,
        fromSelection: Boolean = true,
    ) {
        with(latest.value) {
            val owner = controller.captureChatListActionOwner { batchScope.get() } ?: return
            appState.launchMutation {
                val result =
                    runCatchingCancellable { controller.movePinnedSelection(ids, delta, owner) }
                        .getOrDefault(ChatListPinMoveResult.UNAVAILABLE)
                // The unchanged notice describes a selection; a single row already at the end just stays put.
                val quiet =
                    result == ChatListPinMoveResult.MOVED ||
                        (!fromSelection && result == ChatListPinMoveResult.UNCHANGED)
                if (controller.chatListActionIsCurrent(owner) && !quiet) {
                    val message =
                        when (result) {
                            ChatListPinMoveResult.UNCHANGED -> R.string.chat_batch_order_unchanged
                            ChatListPinMoveResult.UNCONFIRMED -> R.string.chat_batch_order_unconfirmed
                            else -> R.string.chat_batch_order_failed
                        }
                    appState.presentTransient(resources.getString(message), kind = TransientNoticeKind.Information)
                }
            }
        }
    }

    /**
     * Moves one pinned row from its long-press menu through the complete-order path. [movePinnedChats] reads
     * [latest] again within the same main-thread call and therefore sees the same set of values.
     */
    fun movePinnedChat(
        item: ChatListItem,
        delta: Int,
    ) {
        with(latest.value) {
            if (pinnedIndex(item) == null) return
            clearSelection()
            movePinnedChats(setOf(item.projection?.groupIdHex ?: item.group.groupIdHex), delta, fromSelection = false)
        }
    }
}

/**
 * Remembers [ChatListActions] on the owners its actions capture directly. Each of them is itself remembered by the
 * screen, keyed on the controller, account, runtime generation and list filters, so the instance changes exactly
 * when the old local functions would have captured a different owner. [inputs] is updated on every composition like
 * `rememberUpdatedState`, but in a state remembered on the same keys: an instance sees the latest values while it is
 * current and keeps its last ones once a new owner replaces it, as the local functions kept the values they
 * captured. Nothing here reads [inputs] during composition.
 */
@Composable
@Suppress("LongParameterList") // Explicit keys and dependencies, so a stale capture cannot hide in a provider.
internal fun rememberChatListActions(
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    actionScope: AtomicBoolean,
    batchScope: AtomicBoolean,
    pinFollow: ChatPinFollowOwner,
    folderHandoff: ChatFolderHandoffState,
    pendingHeadDemotion: MutableState<ChatListHeadDemotion?>,
    inputs: ChatListActions.Inputs,
): ChatListActions {
    // Screen lifetime, as before: transaction ids keep increasing across accounts and filters.
    val nextHeadDemotionTransactionId = remember { mutableLongStateOf(0L) }
    val latest =
        remember(appState, controller, actionScope, batchScope, pinFollow, folderHandoff, pendingHeadDemotion) {
            mutableStateOf(inputs)
        }
    latest.value = inputs
    return remember(appState, controller, actionScope, batchScope, pinFollow, folderHandoff, pendingHeadDemotion) {
        ChatListActions(
            appState = appState,
            controller = controller,
            actionScope = actionScope,
            batchScope = batchScope,
            pinFollow = pinFollow,
            folderHandoff = folderHandoff,
            pendingHeadDemotionState = pendingHeadDemotion,
            nextHeadDemotionTransactionIdState = nextHeadDemotionTransactionId,
            latest = latest,
        )
    }
}
