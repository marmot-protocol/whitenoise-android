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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    // The extent belongs to one account and runtime; a switch never resumes another owner's row count.
    val owner = active to generation
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
    GlobalSearchResultsRecheckEffects(appState, active, controller)
    LaunchedEffect(controller, query) {
        val resume = extent?.resumeCount(owner, query) ?: 0
        // A returning session rereads at once; a changed query waits out the keystroke debounce.
        val debounce = if (controller.query == null && resume > 0) 0L else CHAT_LIST_SEARCH_DEBOUNCE_MS
        controller.search(query, debounceMillis = debounce, resumeTarget = resume)
    }
    val settled = controller.state.takeIf { !it.loading && !it.resuming && controller.query == query }
    SideEffect { if (settled != null) extent?.record(owner, query, settled.results.size) }
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
            // Names resolve once per distinct chat and sender, not once per result row.
            val chatsById = chats.associateBy { it.group.groupIdHex.lowercase(Locale.ROOT) }
            val titles = HashMap<String, String>()
            val senders = HashMap<String, Pair<String, String?>>()
            results.map { result ->
                val chatTitle =
                    titles.getOrPut(result.groupIdHex.lowercase(Locale.ROOT)) {
                        chatsById[result.groupIdHex.lowercase(Locale.ROOT)]
                            ?.let { chatListItemDisplayTitle(it, appState, titleCopy) }
                            ?: titleCopy.unavailableConversationTitle
                    }
                val (senderLabel, avatarUrl) =
                    senders.getOrPut(result.senderIdHex.lowercase(Locale.ROOT)) {
                        librarySenderLabel(appState, result.senderIdHex, you) to
                            appState.contactAvatarSource(result.senderIdHex, accountRef)
                    }
                GlobalSearchResultPresentation(result, senderLabel, chatTitle, avatarUrl)
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

/**
 * Rechecks loaded rows on the same triggers the files and media library uses: any projection update of the
 * account (a deletion, an edit, a new match), a block-list change, and the earliest disappearing deadline among
 * the loaded rows. Wakeups coalesce and wait briefly, so a burst of updates costs one bounded reread.
 */
@Composable
@Suppress("FunctionNaming")
private fun GlobalSearchResultsRecheckEffects(
    appState: WhiteNoiseAppState,
    account: String,
    controller: GlobalSearchResultsController,
) {
    val runtime = remember(appState.runtimeGeneration) { appState.marmot() }
    val wakeups = remember(controller) { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(controller, appState.runtimeMirrors.blocks.users) { wakeups.send(Unit) }
    LaunchedEffect(controller) {
        for (ignored in wakeups) {
            delay(GLOBAL_SEARCH_RECHECK_COALESCE_MS)
            controller.refresh()
        }
    }
    LaunchedEffect(controller) {
        // A runtime that cannot open a subscription is retried a few times, then left to the other triggers.
        var failures = 0
        while (failures < GLOBAL_SEARCH_OBSERVER_ATTEMPTS) {
            failures = if (observeLibraryChanges(runtime, account, wakeups)) 0 else failures + 1
            delay(GLOBAL_SEARCH_OBSERVER_RETRY_MS)
        }
    }
    val expiry = controller.state.nextExpiry
    LaunchedEffect(controller, expiry) {
        val deadline = expiry ?: return@LaunchedEffect
        delay((deadline.toLong() * MILLIS_PER_SECOND - System.currentTimeMillis()).coerceAtLeast(0L))
        wakeups.send(Unit)
    }
}

private const val GLOBAL_SEARCH_RECHECK_COALESCE_MS = 750L
private const val GLOBAL_SEARCH_OBSERVER_RETRY_MS = 5_000L
private const val GLOBAL_SEARCH_OBSERVER_ATTEMPTS = 3
private const val MILLIS_PER_SECOND = 1_000L
