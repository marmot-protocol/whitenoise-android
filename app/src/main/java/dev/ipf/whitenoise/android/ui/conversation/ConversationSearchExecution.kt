package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal const val CONVERSATION_SEARCH_DEBOUNCE_MS = 275L

/**
 * One keyed in-conversation search request. The caller owns replacement by
 * cancelling the previous request (Compose's LaunchedEffect does this for query
 * keys). The active check and [isCurrent] prevent a completed blocking read from
 * publishing after cancellation or state replacement was requested.
 */
internal suspend fun <T> runConversationSearchRequest(
    rawQuery: String,
    debounceMillis: Long = CONVERSATION_SEARCH_DEBOUNCE_MS,
    search: suspend (String) -> List<T>,
    isCurrent: () -> Boolean = { true },
    publish: (List<T>) -> Unit,
) {
    val query = rawQuery.trim()
    if (query.isEmpty()) {
        publish(emptyList())
        return
    }
    delay(debounceMillis)
    val matches = search(query)
    currentCoroutineContext().ensureActive()
    if (isCurrent()) publish(matches)
}

/** Load a bounded timeline window containing [target] before centering it. */
internal suspend fun <T> loadAndCenterConversationSearchMatch(
    target: T,
    load: suspend (T) -> Boolean,
    center: suspend (T) -> Unit,
): Boolean {
    if (!load(target)) return false
    currentCoroutineContext().ensureActive()
    center(target)
    return true
}
