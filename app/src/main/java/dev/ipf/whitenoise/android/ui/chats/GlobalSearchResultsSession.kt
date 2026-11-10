package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.MarmotEventFfi
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
    // Rechecks run only while the screen is visible; a return to it rechecks once.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    if (started) GlobalSearchResultsRecheckEffects(appState, active, controller)
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
 * Rechecks loaded rows on the files and media library's triggers, narrowed to what can change them: a projection
 * update that removes, deletes, edits or invalidates a listed row or brings a new match, a block-list change, and
 * the earliest disappearing deadline among the loaded rows. Wakeups coalesce and wait briefly, so a burst of
 * updates costs one bounded reread.
 */
@Composable
@Suppress("FunctionNaming")
private fun GlobalSearchResultsRecheckEffects(
    appState: WhiteNoiseAppState,
    account: String,
    controller: GlobalSearchResultsController,
) {
    // Composition can run before the runtime is installed; the observer waits for one instead of throwing.
    val runtime = appState.marmotOrNull()
    val wakeups = remember(controller) { Channel<Unit>(Channel.CONFLATED) }
    // The first launch before any page landed is not a change: that page reads current data. A later launch is a
    // real block change, or a return to a screen whose rows may be stale.
    val firstLaunch = remember(controller) { booleanArrayOf(true) }
    LaunchedEffect(controller, appState.runtimeMirrors.blocks.users) {
        val initialProbe = firstLaunch[0] && controller.state.batches == 0
        firstLaunch[0] = false
        if (!initialProbe) wakeups.send(Unit)
    }
    LaunchedEffect(controller) {
        for (ignored in wakeups) {
            delay(GLOBAL_SEARCH_RECHECK_COALESCE_MS)
            controller.refresh()
        }
    }
    // A fresh query whose first page found nothing rechecks once, so a match that arrived between that read and the
    // subscription opening is not left waiting for the next trigger.
    val emptyFirstPage = shouldProbeAfterFirstPage(controller.state)
    LaunchedEffect(controller, controller.state.generation, emptyFirstPage) {
        if (emptyFirstPage) wakeups.send(Unit)
    }
    LaunchedEffect(controller, runtime) {
        if (runtime == null) return@LaunchedEffect
        // A runtime that cannot open a subscription is retried a few times, then left to the other triggers.
        var failures = 0
        while (failures < GLOBAL_SEARCH_OBSERVER_ATTEMPTS) {
            val opened =
                observeLibraryChanges(
                    runtime = runtime,
                    account = account,
                    wakeups = wakeups,
                    touched = { event ->
                        val update = (event as? MarmotEventFfi.ProjectionUpdated)?.update
                        update?.accountLabel == account && controller.isTouchedBy(update.update)
                    },
                    // Asked after the subscription opened, so a returning session's resume, which the search effect
                    // starts after this one, is already visible. A fresh query skips the probe: in practice its first
                    // read starts only after the 275 ms keystroke debounce, so the subscription is usually open by
                    // then, though nothing orders the two. A returning session's undebounced reread can start
                    // first, so it wakes, as does any session with rows loaded.
                    wakeOnOpen = { controller.state.batches > 0 || controller.state.resuming },
                )
            failures = if (opened) 0 else failures + 1
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
