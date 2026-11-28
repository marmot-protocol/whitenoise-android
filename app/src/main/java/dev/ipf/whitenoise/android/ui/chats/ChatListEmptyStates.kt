package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.LinkChatProgressFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isJoiningChats
import dev.ipf.whitenoise.android.ui.common.StateAction
import dev.ipf.whitenoise.android.ui.common.StatePage
import dev.ipf.whitenoise.android.ui.common.rememberMeasuredControlClick
import dev.ipf.whitenoise.android.ui.devices.linkChatProgressText

/** Empty filtering remains specific to query, unread folder, or the selected folder's contents. */
@Suppress("FunctionNaming")
@Composable
internal fun ChatListNoResults(
    query: String,
    unreadFolderSelected: Boolean,
    filtersActive: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
) {
    val searching = query.isNotEmpty() || filtersActive
    val title =
        when {
            searching -> stringResource(R.string.no_results)
            unreadFolderSelected -> stringResource(R.string.chat_rows_no_unread_title)
            else -> stringResource(R.string.chat_rows_no_chats_title)
        }
    val detail =
        when {
            searching -> stringResource(R.string.chat_rows_no_results_detail)
            unreadFolderSelected -> stringResource(R.string.chat_rows_no_unread_detail)
            else -> stringResource(R.string.chat_rows_empty_folder_detail)
        }
    StatePage(title = title, message = detail, scrollState = scrollState)
}

/** The empty chat list for the profile in view, which says so when its chats are still arriving after linking. */
@Suppress("FunctionNaming")
@Composable
internal fun EmptyChats(
    appState: WhiteNoiseAppState,
    onCreate: () -> Unit,
    onFirstRunHelp: ((Boolean) -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
    onCreateOrigin: (Rect?) -> Unit = {},
) {
    val links = appState.deviceLinkRequests
    val snapshot = appState.activeAccountRef?.let { links.snapshots[it] }
    val progress = snapshot?.chatProgress?.firstOrNull { it.onNewDevice && it.isJoiningChats() }
    val awaiting = rememberAwaitingChatCount(appState.activeAccountRef?.let { links.linkedAwaitingCount[it] })
    EmptyChats(
        onCreate,
        progress = progress,
        arriving = progress != null || awaiting,
        onFirstRunHelp = onFirstRunHelp,
        scrollState = scrollState,
        onCreateOrigin = onCreateOrigin,
    )
}

/**
 * Keeps the existing actionable first-chat entry while adopting the prototype's title/detail presentation. While
 * [arriving], a newly linked device says its chats are on their way instead of claiming there are none, with
 * MDK's [progress] count once it has one.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun EmptyChats(
    onCreate: () -> Unit,
    progress: LinkChatProgressFfi? = null,
    arriving: Boolean = progress != null,
    onFirstRunHelp: ((Boolean) -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
    onCreateOrigin: (Rect?) -> Unit = {},
) {
    val title = if (arriving) R.string.chats_arriving_title else R.string.chat_rows_no_chats_title
    val detail = if (arriving) R.string.chats_arriving_detail else R.string.chat_rows_no_chats_detail
    val (origin, create) =
        rememberMeasuredControlClick {
            onCreateOrigin(it)
            onCreate()
        }
    StatePage(
        title = stringResource(title),
        message = stringResource(detail),
        scrollState = scrollState,
        extra = { progress?.let { Text(linkChatProgressText(it)) } },
        primary = StateAction(stringResource(R.string.new_chat), create, modifier = origin),
        footer = {
            if (!arriving && onFirstRunHelp != null) {
                TextButton(onClick = { onFirstRunHelp(false) }) {
                    Text(stringResource(R.string.learn_white_noise))
                }
                TextButton(onClick = { onFirstRunHelp(true) }) {
                    Text(stringResource(R.string.chat_with_support))
                }
            }
        },
    )
}

/** Archived scope uses its own native empty condition; it does not claim that every conversation is absent. */
@Suppress("FunctionNaming")
@Composable
internal fun EmptyArchivedChats(scrollState: ScrollState = rememberScrollState()) {
    StatePage(
        title = stringResource(R.string.chat_rows_no_archived_title),
        message = stringResource(R.string.chat_rows_no_archived_detail),
        scrollState = scrollState,
    )
}
