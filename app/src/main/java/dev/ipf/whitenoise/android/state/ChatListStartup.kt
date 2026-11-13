package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.marmotkit.AppGroupMemberIdsFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

/** How long the chat list may sit on a bare spinner before it says the update is still finishing. */
internal const val CHAT_LIST_SLOW_START_MILLIS = 4_000L

/** Presentation deadline, matching bootstrap recovery; this does not time out native work. */
internal const val CHAT_LIST_ACTIONABLE_START_MILLIS = 15_000L

/**
 * Whether a member snapshot failure can never succeed on retry. MarmotKit answers `UnknownGroup` for a
 * chat row whose group its member API no longer knows; retrying only repeats the refusal on the backoff
 * schedule, and the row must not keep tripping the batched initial projection either.
 */
internal fun isTerminalMemberFetchFailure(throwable: Throwable): Boolean {
    val variant = DiagnosticFormatter.marmotVariant(throwable)
    return variant == "UnknownGroup"
}

/**
 * Marks bind [epoch] as slow when the list is still loading after [delayMillis]. The chat list then swaps
 * its bare spinner for copy saying the update is still finishing, and one release-safe marker records that
 * the first window took longer than the threshold, because the release log otherwise says nothing here.
 */
internal suspend fun ChatsController.watchSlowChatListStartup(
    epoch: Long,
    delayMillis: Long = CHAT_LIST_SLOW_START_MILLIS,
) {
    delay(delayMillis)
    if (!isLoading || !isActiveBindEpoch(epoch)) return
    slowStartupEpoch = epoch
    Log.i("DMChats", "chat_list_first_window_slow ms=$delayMillis")
}

/** Exposes recovery for an unfinished first projection without cancelling or duplicating its read. */
internal suspend fun ChatsController.watchActionableChatListStartup(
    epoch: Long,
    delayMillis: Long = CHAT_LIST_ACTIONABLE_START_MILLIS,
) {
    delay(delayMillis)
    publishInitialLoadTimeout(epoch)
}

/** Debug-only so operational INFO logs don't ship in release logcat. See #39. */
internal inline fun chatsDebug(message: () -> String) {
    if (BuildConfig.DEBUG) Log.i("DMChats", message())
}

/** Logs a chat-list failure with its exception in debug builds and as a release-safe marker otherwise. */
internal inline fun chatsDebug(
    error: Throwable,
    message: () -> String,
) {
    if (BuildConfig.DEBUG) {
        Log.e("DMChats", message(), error)
    } else {
        Log.e("DMChats", releaseFailureMarker("CHATS", error, message()))
    }
}

internal suspend fun loadGroupMemberIdsPages(
    groupIds: Iterable<String>,
    loadPage: suspend (List<String>) -> List<AppGroupMemberIdsFfi>,
): List<AppGroupMemberIdsFfi> {
    val requested =
        groupIds
            .asSequence()
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .toList()
    val loaded = ArrayList<AppGroupMemberIdsFfi>(requested.size)
    requested.chunked(GROUP_MEMBER_IDS_PAGE_SIZE).forEach { page ->
        val projections = loadPage(page)
        check(projections.size == page.size) {
            "member-id page returned ${projections.size} rows for ${page.size} groups"
        }
        page.zip(projections).forEach { (requestedGroupId, projection) ->
            check(projection.groupIdHex.equals(requestedGroupId, ignoreCase = true)) {
                "member-id page returned a different group than requested"
            }
            loaded += projection.copy(groupIdHex = requestedGroupId)
        }
    }
    return loaded
}

internal data class FirstFrameMemberFallbackResult<T>(
    val groupIdHex: String,
    val result: Result<T>,
)

internal data class FirstFrameMemberFallbackBatch<T>(
    val firstFrameResults: List<FirstFrameMemberFallbackResult<T>>,
    val remainingResults: Channel<FirstFrameMemberFallbackResult<T>>,
    val remainingCount: Int,
)

internal fun initialMemberFallbackGenerationIsCurrent(
    expectedAccount: String,
    expectedBindEpoch: Long,
    expectedCacheEpoch: Long,
    currentAccount: String?,
    currentBindEpoch: Long,
    currentCacheEpoch: Long,
    lifecycleActive: Boolean,
): Boolean =
    lifecycleActive &&
        currentAccount == expectedAccount &&
        currentBindEpoch == expectedBindEpoch &&
        currentCacheEpoch == expectedCacheEpoch

/**
 * Start one lifecycle-child local read per distinct group, bounded by
 * [maxConcurrent], and return the results available within [cutoffMillis].
 * Workers that miss the cutoff keep running under the caller's coroutine job;
 * [remainingResults] lets the owner fold those late answers in without
 * restarting any group read.
 */
internal suspend fun <T> loadFirstFrameMemberFallback(
    groupIds: Iterable<String>,
    cutoffMillis: Long,
    maxConcurrent: Int,
    nowMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
    load: suspend (String) -> T,
): FirstFrameMemberFallbackBatch<T> {
    require(cutoffMillis >= 0L)
    require(maxConcurrent > 0)
    val requested =
        groupIds
            .asSequence()
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .toList()
    val results = Channel<FirstFrameMemberFallbackResult<T>>(requested.size)
    if (requested.isEmpty()) {
        return FirstFrameMemberFallbackBatch(emptyList(), results, 0)
    }

    val gate = Semaphore(maxConcurrent)
    val lifecycleScope = CoroutineScope(coroutineContext)
    requested.forEach { groupIdHex ->
        lifecycleScope.launch {
            val result = runCatchingCancellable { gate.withPermit { load(groupIdHex) } }
            results.send(FirstFrameMemberFallbackResult(groupIdHex, result))
        }
    }

    val cutoffStartedAtMillis = nowMillis()
    val firstFrame = mutableListOf<FirstFrameMemberFallbackResult<T>>()
    var remaining = requested.size
    while (remaining > 0) {
        val completed = results.tryReceive().getOrNull()
        if (completed != null) {
            firstFrame += completed
            remaining -= 1
        } else {
            val elapsedMillis = (nowMillis() - cutoffStartedAtMillis).coerceAtLeast(0L)
            val waitMillis = cutoffMillis - elapsedMillis
            if (waitMillis <= 0L) break
            // Never suspend inside a channel receive: prompt cancellation may
            // consume an element before the caller records it. A short bounded
            // delay leaves every result either synchronously drained above or
            // available to the lifecycle-bound late collector.
            delay(minOf(waitMillis, FIRST_FRAME_FALLBACK_POLL_MILLIS))
        }
    }
    return FirstFrameMemberFallbackBatch(firstFrame, results, remaining)
}

/**
 * Return the projected counterparty ids whose locally persisted presentation
 * must be materialized before the first cached chat-list frame. The caller
 * supplies the direct-conversation classifier so this stays independent from
 * the heavier chat-list row model and remains straightforward to test.
 */
internal fun initialDirectPeerProfileIds(
    projections: Iterable<AppGroupMemberIdsFfi>,
    activeAccountIdHex: String?,
    isDirectConversation: (groupIdHex: String, memberCount: Int) -> Boolean,
): List<String> {
    val active = activeAccountIdHex?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    return projections
        .asSequence()
        .mapNotNull { projection ->
            val memberIds =
                projection.memberIdsHex
                    .asSequence()
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase() }
                    .toList()
            if (!isDirectConversation(projection.groupIdHex, memberIds.size)) return@mapNotNull null
            memberIds.singleOrNull { !it.equals(active, ignoreCase = true) }
        }.distinctBy { it.lowercase() }
        .toList()
}

private const val FIRST_FRAME_FALLBACK_POLL_MILLIS = 5L

private const val GROUP_MEMBER_IDS_PAGE_SIZE = 100

private const val NANOS_PER_MILLISECOND = 1_000_000L
