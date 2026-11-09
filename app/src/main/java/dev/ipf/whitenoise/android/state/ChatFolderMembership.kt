package dev.ipf.whitenoise.android.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.ChatListViewQueryFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Coalesces the bursts of list changes a sync produces into one round of folder reads. */
private const val MEMBERSHIP_REFRESH_DELAY_MS = 150L

/** Retry spacing while MarmotKit is still preparing chat presentation for a text query. */
private const val MEMBERSHIP_RETRY_INITIAL_MS = 500L
private const val MEMBERSHIP_RETRY_MAX_MS = 5_000L

/**
 * One folder's native membership, read with [query]: every chat it matches across its views, and how many of those
 * show an unread badge. A reader whose folder changed since compares [query] and ignores a stale answer.
 */
internal data class NativeFolderMembership(
    val query: ChatFolderQuery,
    val ids: List<String>,
    val unreadCount: Int,
) {
    /** The matched chats as lowercase group IDs, the key folder membership sets use. */
    val idSet: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { ids.toSet() }
}

/**
 * The bound account's folder memberships as MarmotKit computes them over every chat, before any paging. A folder is
 * absent until its first read lands, and stays absent when it has no native query or its query is refused, so its
 * readers keep evaluating loaded rows. While MarmotKit is still preparing presentation for a text query, the last
 * answer stays and the read retries with backoff.
 */
internal class ChatFolderMembership(
    private val scope: CoroutineScope,
    private val queryIds: (suspend (account: String, query: ChatListViewQueryFfi) -> List<String>)?,
) {
    /** The account these memberships belong to, and each folder's latest answer. */
    var account: String? by mutableStateOf(null)
        private set
    var folders: Map<String, NativeFolderMembership> by mutableStateOf(emptyMap())
        private set
    private var job: Job? = null

    /** The native membership of [folderId] for [account], when it answers [query] and is current. */
    fun current(
        account: String?,
        folderId: String,
        query: ChatFolderQuery?,
    ): NativeFolderMembership? {
        val current = account != null && account == this.account
        return folders[folderId]?.takeIf { current && query != null && it.query == query }
    }

    /**
     * Reads every folder in [queries] again for [account], replacing any read still running. Switching accounts drops
     * the previous account's answers at once, and a superseded read can no longer publish.
     */
    fun refresh(
        account: String?,
        queries: Map<String, ChatFolderQuery>,
    ) {
        job?.cancel()
        if (account != this.account) {
            this.account = account
            folders = emptyMap()
        }
        val read = queryIds
        if (account == null || read == null) return
        folders = folders.filterKeys(queries::containsKey)
        job =
            scope.launch {
                delay(MEMBERSHIP_REFRESH_DELAY_MS)
                var pending = queries
                var backoff = MEMBERSHIP_RETRY_INITIAL_MS
                while (pending.isNotEmpty()) {
                    val next = folders.toMutableMap()
                    val retry = mutableMapOf<String, ChatFolderQuery>()
                    for ((id, query) in pending) {
                        when (val answer = readFolder(read, account, query)) {
                            is Answer.Read -> next[id] = answer.membership
                            Answer.Refused -> next.remove(id)
                            Answer.NotReady -> retry[id] = query
                        }
                    }
                    if (this@ChatFolderMembership.account != account) return@launch
                    folders = next
                    pending = retry
                    if (pending.isNotEmpty()) {
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(MEMBERSHIP_RETRY_MAX_MS)
                    }
                }
            }
    }

    /** Forgets every answer, for sign-out and teardown. */
    fun clear() = refresh(null, emptyMap())

    /** One folder's read outcome. */
    private sealed interface Answer {
        data class Read(
            val membership: NativeFolderMembership,
        ) : Answer

        /** Presentation is still being prepared, so the read is retried. */
        data object NotReady : Answer

        /** MarmotKit refused the query, so the folder keeps evaluating loaded rows. */
        data object Refused : Answer
    }

    /** Reads every view of [query] and its unread narrowing, in display order. */
    private suspend fun readFolder(
        read: suspend (String, ChatListViewQueryFfi) -> List<String>,
        account: String,
        query: ChatFolderQuery,
    ): Answer =
        try {
            val ids = query.views.flatMap { read(account, query.forView(it)) }
            val unread = query.unread()
            val unreadCount = unread.views.sumOf { read(account, unread.forView(it)).size }
            Answer.Read(NativeFolderMembership(query, ids.map { it.lowercase(Locale.ROOT) }.distinct(), unreadCount))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: MarmotKitException.ChatPresentationNotReady) {
            Answer.NotReady
        } catch (refused: MarmotKitException) {
            Log.w("DMChats", "CHAT_FOLDER_QUERY_REFUSED reason=${refused.javaClass.simpleName}")
            Answer.Refused
        }
}
