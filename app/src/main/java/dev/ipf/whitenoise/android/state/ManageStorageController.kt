package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable

/** Usage polling while attachments move between storage layouts, and while freed space is returned. */
private const val MIGRATION_POLL_MILLIS = 15_000L
private const val COMPACTION_POLL_MILLIS = 2_000L

/** One user action that changes what is stored. */
internal sealed interface StorageAction {
    /** Saves a Keep media window, removing what it no longer keeps. */
    data class SetKeepMedia(
        val period: KeepMediaPeriod,
    ) : StorageAction

    /** Removes attachments received before [cutoffSeconds], in one chat or across the account. */
    data class RemoveOlder(
        val cutoffSeconds: Long,
        val groupIdHex: String?,
    ) : StorageAction

    /** Removes every attachment in one chat. */
    data class RemoveChat(
        val groupIdHex: String,
    ) : StorageAction

    /** Removes the attachments the user selected. */
    data class RemoveAttachments(
        val items: List<StoredAttachment>,
    ) : StorageAction

    /** Clears the picture cache and Android's temporary copies. */
    data object ClearCaches : StorageAction
}

/** What the last action did, announced once. [freedBytes] is measured from usage before and after. */
internal sealed interface StorageOutcome {
    /** [count] attachments were removed. */
    data class Removed(
        val count: Long,
        val freedBytes: Long,
    ) : StorageOutcome

    /** The action ran but nothing matched it. */
    data object NothingRemoved : StorageOutcome

    /** Keep media was saved without removing anything. */
    data object KeepMediaSaved : StorageOutcome

    /** Caches were cleared. */
    data class CacheCleared(
        val freedBytes: Long,
    ) : StorageOutcome

    /** The action could not finish. */
    data object Failed : StorageOutcome
}

/** The Manage Storage overview: usage, Keep media, chats by size and the action in progress. */
internal data class ManageStorageUiState(
    val available: Boolean = true,
    val usage: StorageUsage? = null,
    val usageLoading: Boolean = false,
    val usageFailed: Boolean = false,
    val keepMedia: KeepMediaPeriod? = null,
    val keepMediaFailed: Boolean = false,
    val chats: List<ChatStorageEntry> = emptyList(),
    val chatsComplete: Boolean = false,
    val chatsLoading: Boolean = false,
    val chatsFailed: Boolean = false,
    val action: StorageAction? = null,
    val outcome: StorageOutcome? = null,
    /** Advances after every finished action so open attachment lists reload. */
    val revision: Long = 0,
) {
    /** True while an action runs, every control is disabled meanwhile. */
    val busy: Boolean get() = action != null
}

/**
 * One screen's transient storage presentation, called only on the Main dispatcher. Results that arrive after an
 * account or runtime change are dropped, an admitted removal still finishes in MarmotKit.
 */
internal class ManageStorageController(
    private val access: ManageStorageAccess?,
    private val scope: CoroutineScope,
) : Closeable {
    private val mutable = MutableStateFlow(ManageStorageUiState(available = access != null))
    val state = mutable.asStateFlow()
    private var closed = false
    private var chatCursor: StorageCursor? = null
    private var loadJob: Job? = null
    private var pollJob: Job? = null

    private val current: Boolean get() = !closed && access?.isCurrent() == true

    /** Reads Keep media and the first page of chats again, and usage too unless [readUsage] is false. */
    fun refresh(readUsage: Boolean = true) {
        if (!current) return retire()
        loadJob?.cancel()
        replaceChatCursor(null)
        mutable.value = state.value.copy(chats = emptyList(), chatsComplete = false, chatsFailed = false)
        loadJob =
            scope.launch {
                if (readUsage) reloadUsage()
                reloadKeepMedia()
                loadChatPage()
            }
    }

    /** Loads the next page of chats when one exists and none is loading. */
    fun loadMoreChats() {
        val value = state.value
        val idle = !value.chatsLoading && !value.chatsComplete
        if (!current || !idle || chatCursor == null) return
        // Tracked with the refresh, so a refresh cancels a page still loading instead of letting it land after.
        loadJob = scope.launch { loadChatPage() }
    }

    /** Runs [action] when nothing else is running, then reports what it did and refreshes everything. */
    fun perform(action: StorageAction) {
        if (!current || state.value.busy) return
        val before = state.value.usage
        mutable.value = state.value.copy(action = action, outcome = null)
        scope.launch {
            val outcome = runCatchingAction { requireNotNull(access).execute(action) }
            if (!current) return@launch retire()
            reloadUsage()
            mutable.value =
                state.value.copy(
                    action = null,
                    outcome = outcome?.let { describe(action, it, before, state.value.usage) } ?: StorageOutcome.Failed,
                    revision = state.value.revision + 1,
                )
            refresh(readUsage = false)
        }
    }

    /** Forgets the announced outcome once the screen has shown it. */
    fun outcomeShown() {
        mutable.value = state.value.copy(outcome = null)
    }

    /** Reads usage again, keeping the last figures on failure, and schedules a poll while a migration runs. */
    private suspend fun reloadUsage() {
        if (!current) return
        mutable.value = state.value.copy(usageLoading = true)
        val usage = runCatchingAction { requireNotNull(access).usage() }
        if (!current) return
        mutable.value =
            state.value.copy(
                usage = usage ?: state.value.usage,
                usageLoading = false,
                usageFailed = usage == null,
            )
        schedulePoll(usage?.migration)
    }

    /** Reads the account's Keep media window. */
    private suspend fun reloadKeepMedia() {
        if (!current) return
        val keep = runCatchingAction { requireNotNull(access).keepMedia() }
        if (current) mutable.value = state.value.copy(keepMedia = keep, keepMediaFailed = keep == null)
    }

    /** Loads the page after the current cursor and appends chats not already listed. */
    private suspend fun loadChatPage() {
        if (!current) return
        mutable.value = state.value.copy(chatsLoading = true)
        val page = runCatchingAction { requireNotNull(access).chats(chatCursor) }
        if (!current) {
            page?.next?.close()
            return
        }
        // A failed page keeps its cursor, so Try again resumes from the same place.
        if (page != null) replaceChatCursor(page.next)
        val known = state.value.chats.mapTo(HashSet()) { it.groupIdHex }
        mutable.value =
            state.value.copy(
                chats = state.value.chats + page?.items.orEmpty().filter { known.add(it.groupIdHex) },
                chatsComplete = page != null && page.next == null,
                chatsLoading = false,
                chatsFailed = page == null,
            )
    }

    /** Polls usage while a storage migration runs, so its progress and the freed space stay current. */
    private fun schedulePoll(migration: StorageMigration?) {
        pollJob?.cancel()
        val wait =
            when (migration) {
                null -> return
                StorageMigration.Compacting -> COMPACTION_POLL_MILLIS
                StorageMigration.Moving, StorageMigration.Paused -> MIGRATION_POLL_MILLIS
            }
        pollJob =
            scope.launch {
                delay(wait)
                reloadUsage()
            }
    }

    /** Closes the held chat cursor and keeps [next] in its place. */
    private fun replaceChatCursor(next: StorageCursor?) {
        chatCursor?.close()
        chatCursor = next
    }

    /** Losing the owner retires this controller's presentation, MarmotKit keeps any admitted change. */
    private fun retire() {
        if (!closed) mutable.value = ManageStorageUiState(available = false)
    }

    /** Releases the chat cursor and stops loading and polling. */
    override fun close() {
        closed = true
        loadJob?.cancel()
        pollJob?.cancel()
        replaceChatCursor(null)
    }
}

/** Runs one action against MarmotKit and returns how many attachments it removed. */
internal suspend fun ManageStorageAccess.execute(action: StorageAction): Long =
    when (action) {
        is StorageAction.SetKeepMedia -> setKeepMedia(action.period)
        is StorageAction.RemoveOlder -> removeOlderThan(action.cutoffSeconds, action.groupIdHex)
        is StorageAction.RemoveChat -> removeChat(action.groupIdHex)
        is StorageAction.RemoveAttachments -> removeAttachments(action.items)
        StorageAction.ClearCaches -> clearCaches().let { 0L }
    }

/** Turns a finished action's count into what the screen announces. */
internal fun describe(
    action: StorageAction,
    removed: Long,
    before: StorageUsage?,
    after: StorageUsage?,
): StorageOutcome {
    // Measured on the total, so bytes that only become free database pages are not announced as freed.
    val freed = ((before?.totalBytes ?: 0) - (after?.totalBytes ?: Long.MAX_VALUE)).coerceAtLeast(0)
    return when {
        action == StorageAction.ClearCaches -> StorageOutcome.CacheCleared(freed)
        removed > 0 -> StorageOutcome.Removed(removed, freed)
        action is StorageAction.SetKeepMedia -> StorageOutcome.KeepMediaSaved
        else -> StorageOutcome.NothingRemoved
    }
}

/** Runs [block], turning a failure into null while letting this coroutine's own cancellation through. */
internal suspend fun <T> runCatchingAction(block: suspend () -> T): T? =
    try {
        block()
    } catch (_: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    } catch (_: Exception) {
        null
    }
