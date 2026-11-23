package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.privateContactAvatarSource
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import kotlinx.coroutines.launch
import java.util.Locale

internal const val GLOBAL_SEARCH_RECENTS_TAG = "chats.search.recents"
internal const val GLOBAL_SEARCH_RECENTS_CLEAR_TAG = "chats.search.recents.clear"

internal fun globalSearchRecentTag(entry: GlobalSearchRecent): String = "chats.search.recent." + entry.key

/**
 * Recently opened chats and profiles on the blank search page (#4004). Opening one records it again, so it
 * moves to the front. Chats outside the loaded Chats window are read by ID; unreadable ones are omitted.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun GlobalSearchRecentStrip(
    recents: GlobalSearchRecents?,
    scopeToken: String,
    visible: Boolean,
    controller: ChatsController,
    appState: WhiteNoiseAppState,
    onOpenChat: (ChatListItem) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val entries = recents?.entries.orEmpty().takeIf { visible }.orEmpty()
    val chats = rememberGlobalSearchRecentChats(controller, entries)
    val destinations =
        remember(entries, chats) { resolveGlobalSearchRecents(entries, chats, appState::profileReferenceAccountIdHex) }
    if (recents == null || destinations.isEmpty()) return
    val accountRef = controller.boundAccountRef
    val groupTitleCopy = rememberGroupTitleCopy()
    val byKey = destinations.associateBy { it.entry.key }
    val openScope = rememberCoroutineScope()
    GlobalSearchRecentSection(
        entries = destinations.map { it.entry },
        onOpen = { entry ->
            when (val destination = byKey[entry.key]) {
                is GlobalSearchRecentDestination.Chat ->
                    openScope.launch {
                        // The shown row can be stale; a chat that is no longer readable leaves the list instead.
                        val owner = controller.captureChatListActionOwner() ?: return@launch
                        val item = controller.resolveChatListActionItem(destination.entry.groupIdHex, owner)
                        // Remove or Clear during the read wins: the entry is neither restored nor opened.
                        val stillListed = recents.entries.any { it.key == entry.key }
                        if (item == null) {
                            recents.remove(entry)
                        } else if (stillListed && recents.record(scopeToken, entry)) {
                            onOpenChat(item)
                        }
                    }
                is GlobalSearchRecentDestination.Profile -> {
                    recents.record(scopeToken, entry)
                    onOpenProfile(destination.entry.npub)
                }
                null -> Unit
            }
        },
        onRemove = recents::remove,
        onClear = recents::clear,
    ) { entry ->
        when (val destination = byKey[entry.key]) {
            is GlobalSearchRecentDestination.Chat -> {
                val title = chatListItemDisplayTitle(destination.item, appState, groupTitleCopy)
                ChatListItemAvatar(destination.item, appState, accountRef, title, GlobalSearchRecentAvatarSize)
                GlobalSearchRecentTitle(title)
            }
            is GlobalSearchRecentDestination.Profile -> {
                val account = destination.accountIdHex
                val name = appState.displayName(account)
                val picture = appState.privateContactAvatarSource(account, accountRef) ?: appState.avatarUrl(account)
                Avatar(name, account, GlobalSearchRecentAvatarSize, pictureUrl = picture)
                GlobalSearchRecentTitle(name)
            }
            null -> Unit
        }
    }
}

/** The Recent heading with Clear, then one tile per entry drawn by [face], newest first. */
@Suppress("FunctionNaming")
@Composable
internal fun GlobalSearchRecentSection(
    entries: List<GlobalSearchRecent>,
    onOpen: (GlobalSearchRecent) -> Unit,
    onRemove: (GlobalSearchRecent) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    face: @Composable (GlobalSearchRecent) -> Unit,
) {
    Column(modifier.fillMaxWidth().testTag(GLOBAL_SEARCH_RECENTS_TAG)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chat_list_search_recent),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton(onClick = onClear, modifier = Modifier.testTag(GLOBAL_SEARCH_RECENTS_CLEAR_TAG)) {
                Text(stringResource(R.string.chat_list_search_recent_clear))
            }
        }
        // A plain row of at most eight tiles: no lazy saveable item state, so no destination ID reaches saved state.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            entries.forEach { entry ->
                key(entry.key) {
                    GlobalSearchRecentTile(
                        tag = globalSearchRecentTag(entry),
                        onOpen = { onOpen(entry) },
                        onRemove = { onRemove(entry) },
                    ) {
                        face(entry)
                    }
                }
            }
        }
    }
}

internal val GlobalSearchRecentAvatarSize = 52.dp
private val RecentTileWidth = 76.dp

/** One destination: tap opens it, long press offers Remove, which screen readers also get as an action. */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("FunctionNaming")
@Composable
private fun GlobalSearchRecentTile(
    tag: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val removeLabel = stringResource(R.string.chat_list_search_recent_remove)
    Box {
        Column(
            modifier =
                Modifier
                    .width(RecentTileWidth)
                    .clip(MaterialTheme.shapes.medium)
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClickLabel = removeLabel,
                        onLongClick = { menuOpen = true },
                    ).semantics {
                        customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove().let { true } })
                    }.testTag(tag)
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
        WhiteNoiseDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            items =
                listOf(
                    WhiteNoiseMenuItem(
                        label = removeLabel,
                        icon = R.drawable.ic_close,
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    ),
                ),
        )
    }
}

@Suppress("FunctionNaming")
@Composable
internal fun GlobalSearchRecentTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Current rows for the recent chat IDs, keyed by lowercase group ID. Loaded rows come from the Chats window;
 * the rest are read by ID and read again whenever the window changes, so a deleted chat drops out.
 */
@Composable
private fun rememberGlobalSearchRecentChats(
    controller: ChatsController,
    entries: List<GlobalSearchRecent>,
): Map<String, ChatListItem> {
    val ids =
        remember(entries) {
            entries.filterIsInstance<GlobalSearchRecent.Chat>().mapTo(HashSet()) { it.groupIdHex.lowercase(Locale.ROOT) }
        }
    val window = remember(controller.items, controller.archivedItems) { controller.items + controller.archivedItems }
    val loaded =
        remember(window, ids) {
            window.filter { it.group.groupIdHex.lowercase(Locale.ROOT) in ids }.associateBy {
                it.group.groupIdHex.lowercase(Locale.ROOT)
            }
        }
    val outside = ids - loaded.keys
    var read by remember(controller.boundAccountRef) { mutableStateOf<Map<String, ChatListItem>>(emptyMap()) }
    LaunchedEffect(outside, window, controller.materializedGroupsRevision) {
        if (outside.isEmpty()) {
            read = emptyMap()
            return@LaunchedEffect
        }
        val owner = controller.captureChatListActionOwner() ?: return@LaunchedEffect
        read = outside.mapNotNull { id -> controller.resolveChatListActionItem(id, owner)?.let { id to it } }.toMap()
    }
    return remember(loaded, read, outside) { loaded + read.filterKeys { it in outside } }
}
