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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState

/** Empty filtering remains specific to query, unread folder, or the selected folder's contents. */
@Suppress("FunctionNaming")
@Composable
internal fun ChatListNoResults(
    query: String,
    unreadFolderSelected: Boolean,
) {
    val title =
        when {
            query.isNotEmpty() -> stringResource(R.string.no_results)
            unreadFolderSelected -> stringResource(R.string.chat_rows_no_unread_title)
            else -> stringResource(R.string.chat_rows_no_chats_title)
        }
    val detail =
        when {
            query.isNotEmpty() -> stringResource(R.string.chat_rows_no_results_detail)
            unreadFolderSelected -> stringResource(R.string.chat_rows_no_unread_detail)
            else -> stringResource(R.string.chat_rows_empty_folder_detail)
        }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        WhiteNoiseEmptyState(title, detail)
    }
}

/** Keeps the existing actionable first-chat entry while adopting the prototype's title/detail presentation. */
@Suppress("FunctionNaming")
@Composable
internal fun EmptyChats(onCreate: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WhiteNoiseEmptyState(
                title = stringResource(R.string.chat_rows_no_chats_title),
                detail = stringResource(R.string.chat_rows_no_chats_detail),
            )
            Button(onClick = onCreate, modifier = Modifier.padding(horizontal = 24.dp)) {
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
