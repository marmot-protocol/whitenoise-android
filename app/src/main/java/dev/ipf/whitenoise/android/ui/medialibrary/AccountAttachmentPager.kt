package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AccountAttachmentCursor
import dev.ipf.marmotkit.AccountAttachmentEntryFfi
import dev.ipf.marmotkit.AccountAttachmentPageReadFfi
import dev.ipf.marmotkit.AccountAttachmentVersion
import dev.ipf.marmotkit.AttachmentHistoryChangeFfi
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Native page reads a visible batch may consume before it returns its last continuation. */
internal const val ACCOUNT_ATTACHMENT_BATCH_PAGES = 4

/** Admitted items after which a batch stops early, so a broad view never reads its whole page budget. */
internal const val ACCOUNT_ATTACHMENT_BATCH_TARGET = 30

/** Original slots per native read; MDK visits at most this many plus one look-ahead slot. */
internal const val ACCOUNT_ATTACHMENT_PAGE_LIMIT = 100u

/** The library reads MDK's account attachment index, never per-chat timelines or message bodies. */
internal interface AccountAttachmentReader {
    /** Reads at most [ACCOUNT_ATTACHMENT_PAGE_LIMIT] original slots after [cursor], newest first. */
    suspend fun page(cursor: AccountAttachmentCursor?): AccountAttachmentPageReadFfi

    /** Reads a constant-work change token without loading attachments. */
    suspend fun version(): AccountAttachmentVersion
}

/** Why the library cannot show more rows; each needs a different recovery. */
internal enum class AccountAttachmentFailure {
    /** A read failed or the cursor owner changed; retrying reads again from where it stopped. */
    READ,

    /** MDK rejected the filters themselves, so retrying the same query cannot succeed. */
    QUERY,
}

/**
 * Transient rendering state. Only admitted entries are kept; the native cursor and version decide
 * pagination and validity. [nextExpiry] is the earliest retention deadline the loaded pages reported.
 */
internal data class AccountAttachmentState(
    val entries: List<AccountAttachmentEntryFfi> = emptyList(),
    val loading: Boolean = true,
    val failure: AccountAttachmentFailure? = null,
    val hasMore: Boolean = false,
    val initialized: Boolean = false,
    val nextExpiry: ULong? = null,
)

/**
 * Owns one library session's native pages (#2000). Calls serialize so a cursor is never consumed
 * twice. Each visible batch reads at most [ACCOUNT_ATTACHMENT_BATCH_PAGES] pages and keeps the last
 * continuation, since a selective filter can return empty pages that are not exhaustion. The first
 * page's version stays the baseline: additions reload from the head as far as the old tail, and a
 * destructive change discards every loaded row. Closing fences late callbacks and releases handles
 * once any in-flight read returns.
 */
internal class AccountAttachmentPager(
    private val reader: AccountAttachmentReader,
    private val admits: (AccountAttachmentEntryFfi) -> Boolean,
    private val isCurrent: () -> Boolean = { true },
) : AutoCloseable {
    var state by mutableStateOf(AccountAttachmentState())
        private set

    private val mutex = Mutex()
    private var baseline: AccountAttachmentVersion? = null
    private var cursor: AccountAttachmentCursor? = null
    private var closed = false
    private var busy = false
    private var restoreThrough: Triple<String, String, UInt>? = null
    private var restoreCount = 0
    private var retryProbe = false

    /** Probes the change token, rebuilding the loaded range after native additions or invalidation. */
    suspend fun refresh() =
        operation {
            val previous = baseline
            if (previous == null || !state.initialized) {
                restoreRange()
            } else {
                retryProbe = true
                val change = reader.version().use { it.changeSince(previous) }
                checkCurrent()
                retryProbe = false
                if (change != AttachmentHistoryChangeFfi.UNCHANGED) {
                    // Additions keep every older row, so the old tail is still there to read back to.
                    restoreThrough =
                        state.entries
                            .lastOrNull()
                            ?.slotKey()
                            ?.takeIf { change == AttachmentHistoryChangeFfi.ADDITIONS }
                    restoreCount = state.entries.size
                    clearCollection()
                    restoreRange()
                }
            }
        }

    /** Reads one bounded batch, or resumes a failed refresh of the previously visible range. */
    suspend fun loadMore(): Unit =
        if (retryProbe) {
            refresh()
        } else {
            operation {
                if (restoreThrough != null || restoreCount > 0) {
                    restoreRange()
                } else if (!state.initialized || state.hasMore) {
                    readBatch()
                }
            }
        }

    /**
     * Rereads from the head, publishing each batch as it lands, until the previous tail row is back after
     * additions, or as many rows as before after a destructive change, or the history ends.
     */
    private suspend fun restoreRange() {
        do {
            readBatch()
            val target = restoreThrough
            val restored =
                if (target != null) state.entries.any { it.slotKey() == target } else state.entries.size >= restoreCount
            if (restored || !state.hasMore) {
                restoreThrough = null
                restoreCount = 0
            }
        } while (restoreThrough != null || restoreCount > 0)
    }

    /** Reads up to the batch's page budget, stopping early once enough rows were admitted. */
    private suspend fun readBatch() {
        val before = state.entries.size
        var pages = 0
        do {
            readNext()
            pages++
        } while (
            state.hasMore &&
            pages < ACCOUNT_ATTACHMENT_BATCH_PAGES &&
            state.entries.size - before < ACCOUNT_ATTACHMENT_BATCH_TARGET
        )
    }

    /** Applies one native outcome without retaining stale rows across a destructive change. */
    @Suppress("ThrowsCount") // Each typed native refusal maps to its own failure.
    private suspend fun readNext(restartAllowed: Boolean = true) {
        when (val read = reader.page(cursor)) {
            is AccountAttachmentPageReadFfi.Page -> {
                val page = read.page
                var adopted = false
                try {
                    checkCurrent()
                    val previous = baseline
                    val destructive =
                        previous != null &&
                            page.version.changeSince(previous) == AttachmentHistoryChangeFfi.RESTART_REQUIRED
                    if (destructive && state.initialized) {
                        restart(restartAllowed)
                    } else {
                        cursor?.close()
                        cursor = page.nextCursor
                        adoptHeadVersion(page.version)
                        adopted = true
                        state =
                            state.copy(
                                entries = (state.entries + page.entries.filter(admits)).distinctBy { it.slotKey() },
                                initialized = true,
                                hasMore = page.nextCursor != null,
                                nextExpiry = earliest(state.nextExpiry, page.nextExpiry),
                            )
                    }
                } finally {
                    if (!adopted) page.nextCursor?.close()
                    if (baseline !== page.version) page.version.close()
                }
            }
            AccountAttachmentPageReadFfi.RestartRequired -> restart(restartAllowed)
            AccountAttachmentPageReadFfi.CursorMismatch -> {
                clearCollection()
                throw IOException("Attachment cursor owner changed")
            }
            AccountAttachmentPageReadFfi.InvalidQuery -> throw InvalidAccountAttachmentQuery()
            AccountAttachmentPageReadFfi.InvalidLimit -> throw IOException("Attachment page limit rejected")
        }
    }

    /** The first page of a range becomes the comparison baseline for later pages and probes. */
    private fun adoptHeadVersion(version: AccountAttachmentVersion) {
        if (state.initialized) return
        baseline?.close()
        baseline = version
    }

    /** Bounds automatic retries when another destructive change races the replacement page. */
    private suspend fun restart(allowed: Boolean) {
        checkCurrent()
        restoreThrough = null
        restoreCount = 0
        clearCollection()
        if (!allowed) throw IOException("Attachment history changed during refresh")
        readNext(restartAllowed = false)
    }

    /**
     * Keeps cancellation distinct from retryable reads and releases handles on terminal disposal. The failure
     * kind is the whole outcome, the exception itself carries nothing the screen can show.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun operation(block: suspend () -> Unit) =
        mutex.withLock {
            if (closed || !isCurrent()) return@withLock
            val fail = { failure: AccountAttachmentFailure ->
                if (!closed && isCurrent()) state = state.copy(loading = false, failure = failure)
            }
            busy = true
            state = state.copy(loading = true, failure = null)
            try {
                block()
                checkCurrent()
                state = state.copy(loading = false)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: InvalidAccountAttachmentQuery) {
                fail(AccountAttachmentFailure.QUERY)
            } catch (_: Exception) {
                fail(AccountAttachmentFailure.READ)
            } finally {
                busy = false
                if (closed) clearCollection()
            }
        }

    /** Rejects completed native reads after cancellation, navigation, or an account/runtime switch. */
    private suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (closed || !isCurrent()) throw CancellationException("Attachment library owner ended")
    }

    /** Drops rows, cursor and baseline; a refreshed head is never appended to old pages. */
    private fun clearCollection() {
        cursor?.close()
        cursor = null
        baseline?.close()
        baseline = null
        state = AccountAttachmentState()
    }

    /** Releases all handles exactly once, after any in-flight borrower has returned. */
    override fun close() {
        closed = true
        state = AccountAttachmentState()
        if (!busy) clearCollection()
    }
}

/** MDK refused the query's IDs or dates; distinct from a read failure because a retry cannot help. */
private class InvalidAccountAttachmentQuery : IOException("Attachment query rejected")

/** The earlier of two optional deadlines. */
private fun earliest(
    first: ULong?,
    second: ULong?,
): ULong? =
    when {
        first == null -> second
        second == null -> first
        else -> minOf(first, second)
    }

/** Account-wide slot identity: the same message slot can only come from one group. */
internal fun AccountAttachmentEntryFfi.slotKey(): Triple<String, String, UInt> =
    Triple(
        groupIdHex,
        entry.messageIdHex,
        when (val outcome = entry.attachment) {
            is MediaAttachmentOutcomeFfi.Accepted -> outcome.attachmentIndex
            is MediaAttachmentOutcomeFfi.Rejected -> outcome.attachmentIndex
        },
    )
