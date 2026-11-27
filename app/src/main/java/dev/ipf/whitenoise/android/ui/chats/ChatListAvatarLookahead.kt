package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.media.DisplayAvatarKey
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.FIRST_FRAME_DURABLE_AVATAR_ROWS
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.backfillChatListDisplayAvatars
import dev.ipf.whitenoise.android.state.hydrateChatListDisplayAvatars
import dev.ipf.whitenoise.android.state.prepareFolderDisplayAvatars
import dev.ipf.whitenoise.android.ui.common.RetainedGroupAvatarPreparation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val AVATAR_ROWS_AHEAD = 32
private const val AVATAR_ROWS_BEHIND = 12
private const val IDLE_AVATAR_START_MS = 400L

@Composable
internal fun WhiteNoiseAppState.observeChatListAvatarLookahead(
    rows: List<ChatListItem>,
    listState: LazyListState,
    rowKey: (ChatListItem) -> String,
    accountRef: String?,
    preparation: RetainedGroupAvatarPreparation?,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentRows by rememberUpdatedState(rows)
    val rowIndices = remember(rows, rowKey) { rows.withIndex().associate { (index, row) -> rowKey(row) to index } }
    val indicesByKey by rememberUpdatedState(rowIndices)
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    val runtime = runtimeGeneration
    LaunchedEffect(this, runtime, accountRef, listState, lifetime, lifecycle, preparation) {
        val account = accountRef ?: return@LaunchedEffect
        val retained = preparation?.pixels?.takeIf { it.matchesOwner(account, runtime, lifetime) }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val attempted = mutableSetOf<DisplayAvatarKey>()
            snapshotFlow {
                val visible =
                    listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                        (info.key as? String)?.let(indicesByKey::get)
                    }
                AvatarLookaheadWindow(
                    currentRows,
                    visible.minOrNull() ?: -1,
                    visible.maxOrNull() ?: -1,
                    listState.isScrollInProgress,
                    listState.lastScrolledForward,
                )
            }.collectLatest { window ->
                if (window.first < 0 || activeAccountRef != account) return@collectLatest
                val generation = displayAvatars.generation(account)
                val outside = avatarLookaheadIndices(window.first, window.last, window.rows.size, window.forward)
                hydrateChatListDisplayAvatars(account, outside.map(window.rows::get))
                if (!window.scrolling) {
                    delay(IDLE_AVATAR_START_MS)
                    backfillChatListDisplayAvatars(generation, window.rows, attempted, retained) {
                        activeAccountRef == account &&
                            runtimeGeneration == runtime &&
                            AvatarImageLoader.currentCacheLifetime() == lifetime
                    }
                }
            }
        }
    }
}

private data class AvatarLookaheadWindow(
    val rows: List<ChatListItem>,
    val first: Int,
    val last: Int,
    val scrolling: Boolean,
    val forward: Boolean,
)

internal fun avatarLookaheadIndices(
    first: Int,
    last: Int,
    rowCount: Int,
    forward: Boolean,
): List<Int> {
    val before = if (forward) AVATAR_ROWS_BEHIND else AVATAR_ROWS_AHEAD
    val after = if (forward) AVATAR_ROWS_AHEAD else AVATAR_ROWS_BEHIND
    val backward = first - 1 downTo (first - before).coerceAtLeast(0)
    val forwardRows = last + 1 until (last + 1 + after).coerceAtMost(rowCount)
    return if (forward) forwardRows + backward else backward + forwardRows
}

/** New chip taps supersede pending disk reads. Leaving this account or closing the chip row cancels the handoff. */
@Composable
internal fun rememberChatFolderAvatarSelection(
    appState: WhiteNoiseAppState,
    accountRef: String,
    select: (String?) -> Unit,
    candidates: (String?) -> List<ChatListItem>,
): (String?) -> Unit {
    val scope = rememberCoroutineScope()
    val handoff = remember { FolderAvatarHandoff() }
    return { id ->
        handoff.job?.cancel()
        handoff.job =
            scope.launch {
                appState.prepareFolderDisplayAvatars(accountRef, candidates(id))
                if (appState.activeAccountRef == accountRef) select(id)
            }
    }
}

private class FolderAvatarHandoff {
    var job: Job? = null
}

/** Compose can keep a shared key or fall back to the prior index when its key map misses. */
internal fun folderAvatarViewport(
    rows: List<ChatListItem>,
    viewport: LazyListState?,
): List<ChatListItem> {
    if (viewport == null) return rows
    val layout = viewport.layoutInfo.visibleItemsInfo
    val previous = viewport.firstVisibleItemIndex
    val anchor = layout.firstOrNull { it.index == previous }?.key
    val keyed = rows.indexOfFirst { it.id == anchor }.takeIf { it >= 0 }
    val lastStart = (rows.size - layout.size).coerceAtLeast(0)
    val starts = listOfNotNull(keyed, previous).map { it.coerceAtMost(lastStart) }.distinct()
    val rowsPerStart = FIRST_FRAME_DURABLE_AVATAR_ROWS / starts.size
    return starts.flatMap { rows.subList(it, minOf(it + rowsPerStart, rows.size)) }.distinctBy(ChatListItem::id)
}
