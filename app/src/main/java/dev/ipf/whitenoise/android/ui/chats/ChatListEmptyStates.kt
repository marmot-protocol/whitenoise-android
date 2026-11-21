package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.LinkChatProgressFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isJoiningChats
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState
import dev.ipf.whitenoise.android.ui.devices.linkChatProgressText
import dev.ipf.whitenoise.android.ui.settings.rememberMeasuredControlClick

/** Empty filtering remains specific to query, unread folder, or the selected folder's contents. */
@Suppress("FunctionNaming")
@Composable
internal fun ChatListNoResults(
    query: String,
    unreadFolderSelected: Boolean,
    filtersActive: Boolean = false,
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        WhiteNoiseEmptyState(title, detail)
    }
}

/** The empty chat list for the profile in view, which says so when its chats are still arriving after linking. */
@Suppress("FunctionNaming")
@Composable
internal fun EmptyChats(
    appState: WhiteNoiseAppState,
    onCreate: () -> Unit,
    onCreateOrigin: (Rect?) -> Unit = {},
) {
    val snapshot = appState.activeAccountRef?.let { appState.deviceLinkRequests.snapshots[it] }
    val progress = snapshot?.chatProgress?.firstOrNull { it.onNewDevice && it.isJoiningChats() }
    EmptyChats(onCreate, progress = progress, onCreateOrigin = onCreateOrigin)
}

/**
 * Keeps the existing actionable first-chat entry while adopting the prototype's title/detail presentation. With native
 * [progress], a newly linked device says its chats are on their way instead of claiming there are none.
 */
@Suppress("FunctionNaming")
@Composable
internal fun EmptyChats(
    onCreate: () -> Unit,
    progress: LinkChatProgressFfi? = null,
    onCreateOrigin: (Rect?) -> Unit = {},
) {
    val title = if (progress != null) R.string.chats_arriving_title else R.string.chat_rows_no_chats_title
    val detail = progress?.let { linkChatProgressText(it) } ?: stringResource(R.string.chat_rows_no_chats_detail)
    val (origin, create) =
        rememberMeasuredControlClick {
            onCreateOrigin(it)
            onCreate()
        }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WhiteNoiseEmptyState(title = stringResource(title), detail = detail)
            Button(onClick = create, modifier = Modifier.padding(horizontal = 24.dp).then(origin)) {
                Text(stringResource(R.string.new_chat))
            }
        }
    }
}

/** Archived scope uses its own native empty condition; it does not claim that every conversation is absent. */
@Suppress("FunctionNaming")
@Composable
internal fun EmptyArchivedChats() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        WhiteNoiseEmptyState(
            title = stringResource(R.string.chat_rows_no_archived_title),
            detail = stringResource(R.string.chat_rows_no_archived_detail),
        )
    }
}
