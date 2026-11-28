package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.canonicalChatListGroupId
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** The visible filtered head the return scroll compares against, or null for archived and advanced views. */
internal fun chatListVisibleReturnHead(
    visibleItems: List<ChatListItem>,
    showArchived: Boolean,
    advancedFolder: Boolean,
): ChatListReturnHead? {
    val ordinaryView = !showArchived && !advancedFolder
    return if (ordinaryView) chatListReturnHead(visibleItems) else null
}

/**
 * A remember key equal only to a key for the same instance. The visible rows are a [List], which the Compose
 * compiler treats as unstable, so a lambda capturing them is renewed whenever a new list instance arrives, even an
 * equal one; keying a holder on this keeps that renewal.
 */
private class SameInstanceKey(
    private val value: Any,
) {
    override fun equals(other: Any?): Boolean = other is SameInstanceKey && other.value === value

    override fun hashCode(): Int = System.identityHashCode(value)
}

/**
 * Opens chats from the chat list with the list head the return scroll compares against.
 *
 * [rememberChatListNavigation] keys the instance on every value it holds, the visible rows by instance, so a callback
 * still sees the visible rows, flags and callbacks of the composition that created it, and a row lambda that captures
 * it is renewed and memoized exactly as the lambdas calling the old local functions were. [appState] is the screen's
 * single instance.
 */
internal class ChatListNavigation(
    private val visibleItems: List<ChatListItem>,
    private val showArchived: Boolean,
    private val advancedFolder: Boolean,
    private val appState: WhiteNoiseAppState,
    private val onOpenGroup: (ChatListItem, String?, Boolean, visibleActiveListHead: ChatListReturnHead?) -> Unit,
) {
    private fun visibleReturnHead() = chatListVisibleReturnHead(visibleItems, showArchived, advancedFolder)

    /** Opens a chat from the visible list, remembering the list head for the return scroll. */
    fun openGroupFromVisibleList(
        item: ChatListItem,
        focusMessageId: String?,
        justCreated: Boolean,
    ) {
        onOpenGroup(item, focusMessageId, justCreated, visibleReturnHead())
    }

    /** Opens the chat a search result came from, among [chats], focused on that result's message. */
    fun openSearchMessage(
        groupIdHex: String,
        messageIdHex: String,
        chats: List<ChatListItem>,
    ) {
        val canonical = canonicalChatListGroupId(groupIdHex)
        val item =
            chats.firstOrNull { canonicalChatListGroupId(it.group.groupIdHex) == canonical }
                ?: run {
                    appState.present(R.string.toast_original_message_unavailable)
                    return
                }
        openGroupFromVisibleList(item, messageIdHex, false)
    }
}

@Composable
internal fun rememberChatListNavigation(
    visibleItems: List<ChatListItem>,
    showArchived: Boolean,
    advancedFolder: Boolean,
    appState: WhiteNoiseAppState,
    onOpenGroup: (ChatListItem, String?, Boolean, visibleActiveListHead: ChatListReturnHead?) -> Unit,
): ChatListNavigation =
    remember(SameInstanceKey(visibleItems), showArchived, advancedFolder, appState, onOpenGroup) {
        ChatListNavigation(
            visibleItems = visibleItems,
            showArchived = showArchived,
            advancedFolder = advancedFolder,
            appState = appState,
            onOpenGroup = onOpenGroup,
        )
    }

/**
 * Holds the list head New Message returns against. It is separate from [ChatListNavigation] so the New Message
 * callback depends only on the visible rows, the folder flags and the held head, as the old local functions did,
 * and a change of the shell's open callback does not renew it.
 *
 * [rememberChatListNewChatFlowHead] keys the instance on every value it holds, the visible rows by instance.
 * [newChatFlowOriginHeadState] is remembered on the account and runtime generation, as it was as a local.
 */
internal class ChatListNewChatFlowHead(
    newChatFlowOriginHeadState: MutableState<ChatListReturnHead?>,
    private val visibleItems: List<ChatListItem>,
    private val showArchived: Boolean,
    private val advancedFolder: Boolean,
) {
    private var newChatFlowOriginHead by newChatFlowOriginHeadState

    private fun visibleReturnHead() = chatListVisibleReturnHead(visibleItems, showArchived, advancedFolder)

    /** Holds the visible head as New Message opens, while the list is still on screen. */
    fun captureNewChatFlowOriginHead() {
        newChatFlowOriginHead = visibleReturnHead()
    }

    /** The head a chat created through New Message returns against, preferring the one held when it opened. */
    fun newChatFlowReturnHead(): ChatListReturnHead? = newChatFlowOriginHead ?: visibleReturnHead()
}

@Composable
internal fun rememberChatListNewChatFlowHead(
    newChatFlowOriginHeadState: MutableState<ChatListReturnHead?>,
    visibleItems: List<ChatListItem>,
    showArchived: Boolean,
    advancedFolder: Boolean,
): ChatListNewChatFlowHead =
    remember(newChatFlowOriginHeadState, SameInstanceKey(visibleItems), showArchived, advancedFolder) {
        ChatListNewChatFlowHead(
            newChatFlowOriginHeadState = newChatFlowOriginHeadState,
            visibleItems = visibleItems,
            showArchived = showArchived,
            advancedFolder = advancedFolder,
        )
    }

/**
 * Remembers the list head held while New Message is open, on the keys it always used. Not saved: after process
 * death the open falls back to the current head.
 */
@Composable
internal fun rememberNewChatFlowOriginHead(
    accountRef: String?,
    runtimeGeneration: Int,
): MutableState<ChatListReturnHead?> =
    remember(accountRef, runtimeGeneration) {
        mutableStateOf<ChatListReturnHead?>(null)
    }
