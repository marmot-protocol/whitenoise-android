package dev.ipf.whitenoise.android.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.ChatListViewQueryFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
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

    /** These members plus the loaded departed groups the folder matches, which show no unread badge. */
    fun withDeparted(departed: Set<String>): NativeFolderMembership {
        val extra = departed.filterNot(idSet::contains)
        return if (extra.isEmpty()) this else copy(ids = ids + extra)
    }
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
    private var pending: Map<String, ChatFolderQuery> = emptyMap()
    private var dirty = false

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
     * Reads every folder in [queries] again for [account]. Requests that arrive while a round is waiting or reading
     * fold into one more round with the newest queries, so a steady stream of list changes cannot postpone the reads.
     * Switching accounts drops the previous account's answers at once, and a superseded read can no longer publish.
     */
    fun refresh(
        account: String?,
        queries: Map<String, ChatFolderQuery>,
    ) {
        if (account != this.account) {
            job?.cancel()
            job = null
            this.account = account
            folders = emptyMap()
        }
        pending = queries
        val read = queryIds
        if (account == null || read == null) return
        folders = folders.filterKeys(queries::containsKey)
        if (job?.isActive == true) {
            dirty = true
            return
        }
        job =
            scope.launch {
                do {
                    dirty = false
                    delay(MEMBERSHIP_REFRESH_DELAY_MS)
                    readAll(read, account)
                } while (dirty && this@ChatFolderMembership.account == account)
            }
    }

    /** One round: reads every pending folder, retrying those MarmotKit is not ready for, with backoff. */
    private suspend fun readAll(
        read: suspend (String, ChatListViewQueryFfi) -> List<String>,
        account: String,
    ) {
        var queries = pending
        var backoff = MEMBERSHIP_RETRY_INITIAL_MS
        while (queries.isNotEmpty()) {
            val next = folders.toMutableMap()
            val retry = mutableMapOf<String, ChatFolderQuery>()
            for ((id, query) in queries) {
                when (val answer = readFolder(read, account, query)) {
                    is Answer.Read -> next[id] = answer.membership
                    Answer.Refused -> next.remove(id)
                    Answer.NotReady -> retry[id] = query
                }
            }
            if (this.account != account) return
            folders = next.filterKeys(pending::containsKey)
            queries = retry.filterKeys(pending::containsKey)
            if (queries.isNotEmpty() && !dirty) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MEMBERSHIP_RETRY_MAX_MS)
            } else {
                return
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

    /**
     * Reads every view of [query], then its unread narrowing. An unread count MarmotKit cannot answer leaves the
     * members standing with no unread badge, and any failure other than not-ready leaves the folder on loaded rows.
     */
    @Suppress("TooGenericExceptionCaught") // A count is advisory: any failure must degrade, never crash the scope.
    private suspend fun readFolder(
        read: suspend (String, ChatListViewQueryFfi) -> List<String>,
        account: String,
        query: ChatFolderQuery,
    ): Answer =
        try {
            val ids = query.views.flatMap { read(account, query.forView(it)) }
            val unread = query.unread()
            val unreadCount =
                try {
                    unread.views.sumOf { read(account, unread.forView(it)).size }
                } catch (_: MarmotKitException.InvalidChatListQuery) {
                    0
                }
            Answer.Read(NativeFolderMembership(query, ids.map { it.lowercase(Locale.ROOT) }.distinct(), unreadCount))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: MarmotKitException.ChatPresentationNotReady) {
            Answer.NotReady
        } catch (refused: Exception) {
            Log.w("DMChats", "CHAT_FOLDER_QUERY_REFUSED reason=${refused.javaClass.simpleName}")
            Answer.Refused
        }
}

/** [folderId]'s native query for [accountRef], from its stored rule, manual members and exclusions. */
internal fun ChatFolderPreferences.folderQuery(
    accountRef: String,
    folderId: String,
): ChatFolderQuery? {
    val rule = folderRule(accountRef, folderId)
    return chatFolderQuery(rule, membershipFor(accountRef, folderId), excludedChats(accountRef, folderId))
}

/** Every folder of [accountRef] that has a native query, keyed by folder ID. */
internal fun ChatFolderPreferences.folderQueries(accountRef: String): Map<String, ChatFolderQuery> =
    foldersFor(accountRef).mapNotNull { folder -> folderQuery(accountRef, folder.id)?.let { folder.id to it } }.toMap()

/**
 * [folderId]'s member IDs for [accountRef]: MarmotKit's answer over every chat once it is current, otherwise [loaded],
 * the evaluation over loaded rows that participant folders and not-yet-read folders keep using.
 */
internal fun WhiteNoiseAppState.folderMemberIds(
    accountRef: String,
    folderId: String,
    loaded: () -> Set<String>,
): Set<String> = nativeFolderMembership(accountRef, folderId)?.idSet ?: loaded()

/** [folderId]'s current native membership for [accountRef], or null when its readers must evaluate loaded rows. */
internal fun WhiteNoiseAppState.nativeFolderMembership(
    accountRef: String,
    folderId: String,
): NativeFolderMembership? {
    val preferences = chatFolderPreferences
    val query = preferences.folderQuery(accountRef, folderId)
    val native = chatFolderMembership?.current(accountRef, folderId, query) ?: return null
    val departed =
        departedFolderMatches(
            rule = preferences.folderRule(accountRef, folderId),
            manualChatIds = preferences.membershipFor(accountRef, folderId),
            excludedChatIds = preferences.excludedChats(accountRef, folderId),
            active = chatListItems,
            archived = archivedChatListItems,
            activeAccountIdHex = accounts.firstOrNull { it.label == accountRef }?.accountIdHex,
            displayTitle = { chatListItemDisplayTitle(it, this, notificationGroupTitleCopy(appContext)) },
        )
    return native.withDeparted(departed)
}

/**
 * Whether MarmotKit files this chat in its Left scope: departed or removed, disbanded, or with a leave or disband
 * outstanding. That scope carries no archive side, so the folder queries leave it out and [departedFolderMatches]
 * answers for these rows instead.
 */
internal fun ChatListItem.inLeftScope(): Boolean {
    val row = projection
    val groupLeft =
        group.selfMembership != SelfMembershipFfi.MEMBER ||
            group.disbanded ||
            group.disbanding ||
            group.leaveRequestPending
    val rowLeft =
        row != null &&
            (
                row.selfMembership != SelfMembershipFfi.MEMBER ||
                    row.leaveRequestPending ||
                    row.disbanding ||
                    row.lifecycleState == GroupLifecycleStateFfi.DISBANDED
            )
    return groupLeft || rowLeft
}

/**
 * A folder's matches among loaded departed groups, by the loaded-rows evaluator over the same side of the archive
 * split the chat list draws the folder from. The chat list shows these rows among active or archived chats by their
 * own archive flag, which MarmotKit's Left scope does not carry, so this keeps them exactly where they were.
 */
internal fun departedFolderMatches(
    rule: ChatFolderRule?,
    manualChatIds: Set<String>,
    excludedChatIds: Set<String>,
    active: List<ChatListItem>,
    archived: List<ChatListItem>,
    activeAccountIdHex: String?,
    displayTitle: (ChatListItem) -> String = { it.projection?.title.orEmpty() },
): Set<String> {
    val departed = chatFolderSource(rule, active, archived).filter(ChatListItem::inLeftScope)
    if (departed.isEmpty()) return emptySet()
    val departedIds = departed.mapTo(HashSet()) { it.foldedId }
    val matched =
        chatFolderChatIds(
            items = departed,
            manualChatIds = manualChatIds,
            rule = rule,
            activeAccountIdHex = activeAccountIdHex,
            isMuted = { id -> departed.any { it.group.groupIdHex == id && it.engineMuted() } },
            displayTitle = displayTitle,
            excludedChatIds = excludedChatIds,
        )
    return matched.filterTo(HashSet()) { it in departedIds }
}
