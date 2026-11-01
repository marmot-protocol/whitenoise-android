@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GlobalAttachmentItem
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.medialibrary.AccountAttachmentFailure
import kotlinx.coroutines.launch

/**
 * The Files & media library as chat-list search shows it (#2000): the browser, its per-chat openers and
 * the full-screen viewer, with every exit to a message going through [onOpenMessage].
 */
@Composable
internal fun GlobalAttachmentLibraryHost(
    appState: WhiteNoiseAppState,
    account: String?,
    chats: List<ChatListItem>,
    library: GlobalAttachmentLibraryContent,
    viewport: GlobalSearchViewport?,
    onOpenMessage: (groupIdHex: String, messageIdHex: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val youLabel = stringResource(R.string.you)
    val viewInChat: (GlobalAttachmentItem) -> Unit = { onOpenMessage(it.groupIdHex, it.messageIdHex) }
    val opener = rememberGlobalAttachmentOpener(appState, account, chats, library.items, viewInChat)
    val pager = library.pager
    val loadMore: () -> Unit = { pager?.let { scope.launch { it.loadMore() } } }
    val failure = pager?.state?.failure
    GlobalAttachmentBrowser(
        items = library.items,
        kinds = library.kinds,
        loading = library.loading,
        bottomPadding = 0.dp,
        actions =
            GlobalAttachmentCardActions(
                senderLabel = { librarySenderLabel(appState, it.sender, youLabel) },
                thumbnail = { libraryThumbnail(appState, account, it) },
                onOpen = { item -> opener?.open(item) ?: viewInChat(item) },
                onViewInChat = viewInChat,
            ),
        paging =
            GlobalAttachmentPaging(
                hasMore = pager?.state?.hasMore == true,
                failed = failure != null,
                filtersUnsupported = failure == AccountAttachmentFailure.QUERY,
                onLoadMore = loadMore,
                onRetry = loadMore,
            ),
        selectionOwner = viewport?.selection,
        gridState = viewport?.attachmentGrid ?: rememberLazyGridState(),
    )
    opener?.let { GlobalAttachmentViewerHost(it, appState, onGoToMessage = onOpenMessage) }
}
