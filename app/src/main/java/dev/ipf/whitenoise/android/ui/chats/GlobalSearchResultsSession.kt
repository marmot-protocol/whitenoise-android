package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.MarmotTraceSection
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import java.util.Locale

/**
 * Owns the account's global message-search session while message results are showing (#1944), and closes it on
 * account switch, runtime replacement or when results stop showing. Query changes reuse the session: they
 * cancel the batch in flight and start the next generation after the keystroke debounce.
 */
@Composable
internal fun rememberGlobalSearchResults(
    appState: WhiteNoiseAppState,
    account: String?,
    query: GlobalSearchResultsQuery,
    enabled: Boolean,
    extent: GlobalSearchResultsExtent? = null,
): GlobalSearchResultsController? {
    SideEffect { if (!enabled) extent?.clear() }
    val active = account?.takeIf { enabled && it == appState.activeAccountRef } ?: return null
    val generation = appState.runtimeGeneration
    val scope = rememberCoroutineScope()
    val controller =
        remember(active, generation) {
            GlobalSearchResultsController(
                reader = { pageQuery ->
                    appState.measureHostPerformance(HostPerformanceOperationFfi.MESSAGE_SEARCH) {
                        appState.marmotIo(MarmotTraceSection.MESSAGE_SEARCH) { timelineMessages(active, pageQuery) }
                    }
                },
                scope = scope,
                environment =
                    GlobalSearchResultsEnvironment(
                        hiddenMessageIds = { group -> appState.hiddenMessageIdsInGroup(active, group) },
                        isCurrent = { appState.runtimeGeneration == generation && appState.activeAccountRef == active },
                    ),
            )
        }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller, query) {
        val resume = extent?.resumeCount(query) ?: 0
        // A returning session rereads at once; a changed query waits out the keystroke debounce.
        val debounce = if (controller.query == null && resume > 0) 0L else CHAT_LIST_SEARCH_DEBOUNCE_MS
        controller.search(query, debounceMillis = debounce, resumeTarget = resume)
    }
    val settled = controller.state.takeIf { !it.loading && !it.resuming && controller.query == query }
    SideEffect { if (settled != null) extent?.record(query, settled.results.size) }
    return controller
}

/**
 * Names each loaded result for display: "You" or the sender's profile name, the chat titled as its chat row is,
 * and the sender's picture. A result whose chat is not resolved yet shows the unavailable-chat title until the
 * lookup lands. A null [state] — a query the session has not adopted yet — shows the loading boundary.
 */
@Composable
internal fun globalSearchResultsContent(
    appState: WhiteNoiseAppState,
    state: GlobalSearchResultsState?,
    chats: List<ChatListItem>,
    titleCopy: GroupTitleCopy,
    filterCount: Int,
    selected: (GlobalSearchResult) -> Boolean,
    actions: GlobalSearchResultsActions,
): GlobalSearchResultsContent {
    val you = stringResource(R.string.you)
    val accountRef = appState.activeAccountRef
    val profileRevision = appState.profileRevisionForCompose
    val results = state?.results.orEmpty()
    val rows =
        remember(results, chats, titleCopy, you, accountRef, profileRevision) {
            val titles = chats.associate { it.group.groupIdHex.lowercase(Locale.ROOT) to it }
            results.map { result ->
                val chat = titles[result.groupIdHex.lowercase(Locale.ROOT)]
                GlobalSearchResultPresentation(
                    result = result,
                    senderLabel = librarySenderLabel(appState, result.senderIdHex, you),
                    chatTitle =
                        chat?.let { chatListItemDisplayTitle(it, appState, titleCopy) }
                            ?: titleCopy.unavailableConversationTitle,
                    avatarUrl = appState.contactAvatarSource(result.senderIdHex, accountRef),
                )
            }
        }
    return GlobalSearchResultsContent(
        state = state ?: GlobalSearchResultsState(),
        rows = rows,
        filterCount = filterCount,
        selected = selected,
        actions = actions,
    )
}
