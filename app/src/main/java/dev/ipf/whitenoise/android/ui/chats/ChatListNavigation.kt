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

/**
 * Opens chats and profiles from the chat list and remembers the list head the return scroll compares against.
 *
 * The screen builds one instance per composition, as it built the local functions this replaces, so each callback
 * captures the visible rows, flags and callbacks of the composition that created it. Only [newChatFlowOriginHeadState]
 * lasts longer; the screen remembers it on the account and runtime generation, as it did as a local.
 */
internal class ChatListNavigation(
    private val newChatFlowOriginHeadState: MutableState<ChatListReturnHead?>,
    private val visibleItems: List<ChatListItem>,
    private val showArchived: Boolean,
    private val advancedFolder: Boolean,
    private val sourceList: List<ChatListItem>,
    private val appState: WhiteNoiseAppState,
    private val searchRecents: GlobalSearchRecents?,
    private val globalSearchState: GlobalSearchState,
    private val onOpenGroup: (ChatListItem, String?, Boolean, visibleActiveListHead: ChatListReturnHead?) -> Unit,
    private val onPresentProfile: (npub: String, visibleActiveListHead: ChatListReturnHead?) -> Unit,
) {
    private var newChatFlowOriginHead by newChatFlowOriginHeadState

    /** The visible filtered head the return scroll compares against, or null for archived and advanced views. */
    fun visibleReturnHead(): ChatListReturnHead? {
        val ordinaryView = !showArchived && !advancedFolder
        return if (ordinaryView) chatListReturnHead(visibleItems) else null
    }

    /** Opens a chat from the visible list, remembering the list head for the return scroll. */
    fun openGroupFromVisibleList(
        item: ChatListItem,
        focusMessageId: String?,
        justCreated: Boolean,
    ) {
        onOpenGroup(item, focusMessageId, justCreated, visibleReturnHead())
    }

    /** Holds the visible head as New Message opens, while the list is still on screen. */
    fun captureNewChatFlowOriginHead() {
        newChatFlowOriginHead = visibleReturnHead()
    }

    /** The head a chat created through New Message returns against, preferring the one held when it opened. */
    fun newChatFlowReturnHead(): ChatListReturnHead? = newChatFlowOriginHead ?: visibleReturnHead()

    /** Opens the chat a search result came from, among [chats], focused on that result's message. */
    fun openSearchMessage(
        groupIdHex: String,
        messageIdHex: String,
        chats: List<ChatListItem> = sourceList,
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

    /** Remembers a chat or profile opened from global search for the blank search page (#4004). */
    fun recordSearchOpen(entry: GlobalSearchRecent) {
        searchRecents?.record(globalSearchState.accountScopeToken, entry)
    }

    /** Presents a profile from the visible list, remembering the list head for the return scroll. */
    fun presentProfileFromVisibleList(npub: String) {
        val visibleHead =
            if (showArchived || advancedFolder) null else chatListReturnHead(visibleItems)
        onPresentProfile(npub, visibleHead)
    }
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
