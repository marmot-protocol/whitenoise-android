package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AttachmentEntryFfi
import dev.ipf.marmotkit.AttachmentHistoryChangeFfi
import dev.ipf.marmotkit.AttachmentHistoryCursor
import dev.ipf.marmotkit.AttachmentHistoryVersion
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** The route reads MDK's local index, never the bounded conversation window or raw messages. */
internal interface GroupAttachmentReader {
    /** Reads at most 100 original slots, including rejected slots, in canonical order. */
    suspend fun page(cursor: AttachmentHistoryCursor?): AttachmentPageReadFfi

    /** Reads an authoritative change marker without loading attachments. */
    suspend fun version(): AttachmentHistoryVersion
}

/** Transient rendering state; only native cursor/version handles determine pagination and validity. */
internal data class GroupAttachmentState(
    val entries: List<AttachmentEntryFfi> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val hasMore: Boolean = false,
    val initialized: Boolean = false,
)

/**
 * Owns one screen's loaded native pages. Calls serialize so cursors cannot be consumed twice. The
 * initial version remains the baseline until a refresh, including after later pages and exhaustion.
 * Closing fences late callbacks immediately and defers borrowed-handle disposal until the call ends.
 */
internal class GroupAttachmentPager(
    private val reader: GroupAttachmentReader,
    private val isCurrent: () -> Boolean = { true },
) : AutoCloseable {
    var state by mutableStateOf(GroupAttachmentState())
        private set

    private val mutex = Mutex()
    private var baseline: AttachmentHistoryVersion? = null
    private var cursor: AttachmentHistoryCursor? = null
    private var closed = false
    private var busy = false
    private var restoreThrough: Pair<String, UInt>? = null
    private var restoreCount = 0
    private var retryProbe = false

    /** Probes even empty/exhausted collections, rebuilding the loaded range after native changes. */
    suspend fun refresh() =
        operation {
            val previous = baseline
            if (previous == null) {
                readNext()
            } else {
                retryProbe = true
                val change = reader.version().use { it.changeSince(previous) }
                checkCurrent()
                retryProbe = false
                if (change != AttachmentHistoryChangeFfi.UNCHANGED) {
                    restoreThrough =
                        if (change == AttachmentHistoryChangeFfi.ADDITIONS) {
                            state.entries.lastOrNull()?.slotKey()
                        } else {
                            null
                        }
                    restoreCount = state.entries.size
                    clearCollection()
                    restoreRange()
                }
            }
        }

    /** Reads one bounded page, or resumes a failed refresh of the previously visible range. */
    suspend fun loadMore(): Unit =
        if (retryProbe) {
            refresh()
        } else {
            operation {
                if (restoreThrough != null || restoreCount > 0) {
                    restoreRange()
                } else if (!state.initialized || state.hasMore) {
                    readNext()
                }
            }
        }

    /** Refreshes only as far as the previous tail, publishing every bounded page progressively. */
    private suspend fun restoreRange() {
        do {
            readNext()
            val target = restoreThrough
            val restored =
                if (target != null) state.entries.any { it.slotKey() == target } else state.entries.size >= restoreCount
            if (restored || !state.hasMore) {
                restoreThrough = null
                restoreCount = 0
            }
        } while (restoreThrough != null || restoreCount > 0)
    }

    /** Applies native outcomes without retaining stale rows on a destructive generation change. */
    private suspend fun readNext(restartAllowed: Boolean = true) {
        val read = reader.page(cursor)
        when (read) {
            is AttachmentPageReadFfi.Page -> {
                val page = read.page
                var adopted = false
                try {
                    checkCurrent()
                    val previous = baseline
                    val destructive =
                        previous != null &&
                            page.version.changeSince(previous) == AttachmentHistoryChangeFfi.RESTART_REQUIRED
                    if (destructive) {
                        restart(restartAllowed)
                    } else {
                        check(page.hasMore == (page.nextCursor != null)) { "Invalid attachment page continuation" }
                        cursor?.close()
                        cursor = page.nextCursor
                        if (previous == null) baseline = page.version
                        adopted = true
                        state =
                            state.copy(
                                entries = (state.entries + page.entries).distinctBy { it.slotKey() },
                                initialized = true,
                                hasMore = page.hasMore,
                            )
                    }
                } finally {
                    if (!adopted) page.nextCursor?.close()
                    if (baseline !== page.version) page.version.close()
                }
            }
            AttachmentPageReadFfi.RestartRequired -> restart(restartAllowed)
            AttachmentPageReadFfi.CursorMismatch -> {
                clearCollection()
                throw IOException("Attachment cursor owner changed")
            }
            AttachmentPageReadFfi.InvalidLimit -> throw IOException("Attachment page limit rejected")
        }
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

    /** Keeps cancellation distinct from retryable reads and releases handles on terminal disposal. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun operation(block: suspend () -> Unit) =
        mutex.withLock {
            if (closed || !isCurrent()) return@withLock
            busy = true
            state = state.copy(loading = true, failed = false)
            try {
                block()
                checkCurrent()
                state = state.copy(loading = false)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (!closed && isCurrent()) state = state.copy(loading = false, failed = true)
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

    /** Drops the entire obsolete generation before any replacement read can suspend. */
    private fun clearCollection() {
        cursor?.close()
        baseline?.close()
        cursor = null
        baseline = null
        state = GroupAttachmentState()
    }

    /** Releases all handles exactly once, after any in-flight borrower has returned. */
    override fun close() {
        closed = true
        state = GroupAttachmentState()
        if (!busy) clearCollection()
    }
}

/** Original slot identity also preserves rejected slots without renumbering accepted attachments. */
internal fun AttachmentEntryFfi.slotKey(): Pair<String, UInt> =
    messageIdHex to
        when (val outcome = attachment) {
            is MediaAttachmentOutcomeFfi.Accepted -> outcome.attachmentIndex
            is MediaAttachmentOutcomeFfi.Rejected -> outcome.attachmentIndex
        }
