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
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineProjectionUpdateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.MarmotTraceSection
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The account's global message-search session while message results are showing (#1944). With a shell [holder]
 * the session outlives the chat list, so opening a result and pressing Back returns to the same loaded pages
 * (#1945); without one it lives as long as this composition. Account or runtime changes and leaving message
 * results close it. Query changes reuse it: they cancel the batch in flight and start the next generation after
 * the keystroke debounce, while recomposing with the query it already answers changes nothing.
 */
@Composable
internal fun rememberGlobalSearchResults(
    appState: WhiteNoiseAppState,
    account: String?,
    query: GlobalSearchResultsQuery,
    enabled: Boolean,
    holder: GlobalSearchResultsHolder? = null,
): GlobalSearchResultsController? {
    SideEffect { if (!enabled) holder?.close() }
    val active = account?.takeIf { enabled && it == appState.activeAccountRef } ?: return null
    val generation = appState.runtimeGeneration
    val create: (CoroutineScope) -> GlobalSearchResultsController = { scope ->
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
    val controller = rememberHeldOrLocalSession(holder, active to generation, create)
    // Rechecks run only while the screen is visible; a return to it rechecks once.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    if (started) GlobalSearchResultsRecheckEffects(appState, active, controller)
    LaunchedEffect(controller, query) { controller.search(query, debounceMillis = CHAT_LIST_SEARCH_DEBOUNCE_MS) }
    // A return to a held session rechecks its rows at once, without the lifecycle wakeup's coalescing delay.
    LaunchedEffect(controller) { if (controller.stale) controller.refresh() }
    return controller
}

/**
 * The shell-held session for [owner] when a [holder] exists, else one owned and closed by this composition. A
 * held session is closed by its holder, never when the chat list leaves composition.
 */
@Composable
private fun rememberHeldOrLocalSession(
    holder: GlobalSearchResultsHolder?,
    owner: Any,
    create: (CoroutineScope) -> GlobalSearchResultsController,
): GlobalSearchResultsController {
    val localScope = rememberCoroutineScope()
    val controller =
        remember(holder, owner) { holder?.obtain(owner, create) ?: create(localScope) }
    DisposableEffect(controller, holder) {
        onDispose { if (holder == null) controller.close() else holder.detach() }
    }
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
 * the earliest disappearing deadline among the loaded rows. Removed and deleted rows, and rows past their
 * deadline, leave at once without a reread. Wakeups coalesce and wait briefly, so a burst of updates costs one
 * recheck chain.
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
    // Every key MDK reports removed, collected on the event thread without reading list state, then withdrawn on the
    // main thread, so a row a page or reread in flight still holds never lists either.
    val removals = remember(controller) { Channel<Set<String>>(Channel.UNLIMITED) }
    LaunchedEffect(controller) {
        for (keys in removals) controller.revoke(keys)
    }
    // The first launch before any page landed is not a change: that page reads current data. A later launch is a
    // real block change, or a return to a screen whose rows may be stale.
    val firstLaunch = remember(controller) { booleanArrayOf(true) }
    LaunchedEffect(controller, appState.runtimeMirrors.blocks.users) {
        // A return to a held session already rechecks at once, so its first launch is not another probe.
        val initialProbe = firstLaunch[0] && (controller.state.batches == 0 || controller.stale)
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
    // A reattached stale session already rechecks at once, so it does not probe again.
    val emptyFirstPage = shouldProbeAfterFirstPage(controller.state) && !controller.stale
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
                    touched = { event -> touchesLoadedRows(event, account, controller) { removals.trySend(it) } },
                    // Asked after the subscription opened. A fresh query skips the probe: in practice its first read
                    // starts only after the 275 ms keystroke debounce, so the subscription is usually open by then,
                    // though nothing orders the two. A session with rows loaded — a return to a held session — wakes,
                    // so rows changed while the reader was away are rechecked.
                    wakeOnOpen = { controller.state.batches > 0 },
                )
            failures = if (opened) 0 else failures + 1
            delay(GLOBAL_SEARCH_OBSERVER_RETRY_MS)
        }
    }
    val expiry = controller.state.nextExpiry
    LaunchedEffect(controller, expiry) { controller.revokeAtDeadline(expiry) }
}

/**
 * Waits for [deadline], the earliest disappearing deadline among the loaded rows, then drops every row past it.
 * An expiry changes no other row, so it never starts a reread.
 */
internal suspend fun GlobalSearchResultsController.revokeAtDeadline(deadline: ULong?) {
    if (deadline == null) return
    delay((deadline.toLong() * MILLIS_PER_SECOND - environment.nowMillis()).coerceAtLeast(0L))
    revoke()
}

private const val GLOBAL_SEARCH_RECHECK_COALESCE_MS = 750L
private const val GLOBAL_SEARCH_OBSERVER_RETRY_MS = 5_000L
private const val GLOBAL_SEARCH_OBSERVER_ATTEMPTS = 3
private const val MILLIS_PER_SECOND = 1_000L

/**
 * Whether [event] is a projection update of [account] that can change [controller]'s loaded rows. Every key it
 * removes or deletes goes to [withdraw] first, so a loaded row leaves before the recheck lands and a held one never
 * lists.
 */
internal fun touchesLoadedRows(
    event: MarmotEventFfi,
    account: String,
    controller: GlobalSearchResultsController,
    withdraw: (Set<String>) -> Unit,
): Boolean {
    val update = (event as? MarmotEventFfi.ProjectionUpdated)?.update?.takeIf { it.accountLabel == account }
    val removed = update?.let { globalSearchRemovedKeys(it.update) }.orEmpty()
    if (removed.isNotEmpty()) withdraw(removed)
    return update != null && controller.isTouchedBy(update.update)
}

/**
 * Result keys of every message [update] removes or reports deleted by MDK's own deleted flag (a delete for
 * everyone arrives as an upsert), loaded or not. Reads no list state, so it is safe on the event thread; a removed
 * or deleted message never returns, so withdrawing a key no row holds is harmless.
 */
internal fun globalSearchRemovedKeys(update: TimelineProjectionUpdateFfi): Set<String> =
    update.changes.mapNotNullTo(HashSet()) { change ->
        when (change) {
            is TimelineMessageChangeFfi.Remove -> change.messageIdHex
            is TimelineMessageChangeFfi.Upsert -> change.message.messageIdHex.takeIf { change.message.deleted }
        }?.let { globalSearchKey(update.groupIdHex, it) }
    }
