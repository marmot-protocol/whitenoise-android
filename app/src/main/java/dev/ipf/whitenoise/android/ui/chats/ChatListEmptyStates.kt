package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.LinkChatProgressFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.share.activeAccountInvite
import dev.ipf.whitenoise.android.share.shareAppInvite
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isJoiningChats
import dev.ipf.whitenoise.android.ui.common.StateAction
import dev.ipf.whitenoise.android.ui.common.StatePage
import dev.ipf.whitenoise.android.ui.common.rememberMeasuredControlClick
import dev.ipf.whitenoise.android.ui.devices.linkChatProgressText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

/** Only the ordinary empty Chats view offers first-run actions. */
internal fun ChatsController.showsFirstRun(
    listEmpty: Boolean,
    otherScope: Boolean,
): Boolean = listEmpty && !otherScope && !isLoading && error == null && archivedItems.isEmpty()

/** The empty chat list for the profile in view, which says so when its chats are still arriving after linking. */
@Suppress("FunctionNaming")
@Composable
internal fun EmptyChats(
    appState: WhiteNoiseAppState,
    onCreate: () -> Unit,
    onFirstRunHelp: ((Boolean) -> Unit)? = null,
    canShareInvite: () -> Boolean = { true },
    scrollState: ScrollState = rememberScrollState(),
    onCreateOrigin: (Rect?) -> Unit = {},
) {
    val links = appState.deviceLinkRequests
    val snapshot = appState.activeAccountRef?.let { links.snapshots[it] }
    val progress = snapshot?.chatProgress?.firstOrNull { it.onNewDevice && it.isJoiningChats() }
    val awaiting = rememberAwaitingChatCount(appState.activeAccountRef?.let { links.linkedAwaitingCount[it] })
    val arriving = progress != null || awaiting
    if (onFirstRunHelp != null && !arriving) {
        FirstRunEmptyChatsWithInvite(appState, onCreate, onCreateOrigin, onFirstRunHelp, canShareInvite, scrollState)
    } else {
        EmptyChats(
            onCreate,
            progress = progress,
            arriving = arriving,
            scrollState = scrollState,
            onCreateOrigin = onCreateOrigin,
        )
    }
}

/** Keep the chooser scoped to the current account and uncovered Chats surface while the QR is staged. */
@Suppress("FunctionNaming")
@Composable
private fun FirstRunEmptyChatsWithInvite(
    appState: WhiteNoiseAppState,
    onCreate: () -> Unit,
    onCreateOrigin: (Rect?) -> Unit,
    onHelp: (Boolean) -> Unit,
    canShareInvite: () -> Boolean,
    scrollState: ScrollState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val invite = activeAccountInvite(appState)
    val accountRef = appState.activeAccountRef
    val runtimeGeneration = appState.runtimeGeneration
    val currentCanShareInvite by rememberUpdatedState(canShareInvite)
    var shareJob by remember(accountRef, runtimeGeneration) { mutableStateOf<Job?>(null) }

    fun isCurrent(): Boolean =
        accountRef != null &&
            appState.activeAccountRef == accountRef &&
            appState.runtimeGeneration == runtimeGeneration &&
            !appState.appLock.screenVisible &&
            !appState.signOutInProgress &&
            !appState.wipeInProgress &&
            currentCanShareInvite()

    LaunchedEffect(canShareInvite()) {
        if (!currentCanShareInvite()) shareJob?.cancel()
    }

    FirstRunEmptyChats(
        onCreate = {
            shareJob?.cancel()
            onCreate()
        },
        onCreateOrigin = onCreateOrigin,
        scrollState = scrollState,
        onHelp = {
            shareJob?.cancel()
            onHelp(it)
        },
        onInvite = {
            if (shareJob?.isActive != true && isCurrent()) {
                shareJob =
                    scope.launch {
                        appState.shareAppInvite(context, invite, "EMPTY_CHATS_INVITE_SHARE", ::isCurrent)
                    }
            }
        },
    )
}

/** A new profile's first empty Chats view leads with an invite and keeps the measured New chat entry. */
@Suppress("FunctionNaming")
@Composable
internal fun FirstRunEmptyChats(
    onCreate: () -> Unit,
    onCreateOrigin: (Rect?) -> Unit = {},
    onHelp: (Boolean) -> Unit,
    onInvite: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
) {
    val (origin, create) =
        rememberMeasuredControlClick {
            onCreateOrigin(it)
            onCreate()
        }
    StatePage(
        title = stringResource(R.string.first_run_chats_title),
        message = stringResource(R.string.first_run_chats_detail),
        scrollState = scrollState,
        primary = StateAction(stringResource(R.string.new_message_invite_friend), onInvite),
        secondary = StateAction(stringResource(R.string.first_run_new_chat), create, modifier = origin),
        footer = {
            TextButton(onClick = { onHelp(true) }) { Text(stringResource(R.string.chat_with_support)) }
            TextButton(onClick = { onHelp(false) }) { Text(stringResource(R.string.learn_white_noise)) }
        },
    )
}

/**
 * Keeps the existing actionable first-chat entry while adopting the prototype's title/detail presentation. While
 * [arriving], a newly linked device says its chats are on their way instead of claiming there are none, with
 * MDK's [progress] count once it has one.
 */
@Suppress("FunctionNaming")
@Composable
internal fun EmptyChats(
    onCreate: () -> Unit,
    progress: LinkChatProgressFfi? = null,
    arriving: Boolean = progress != null,
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
