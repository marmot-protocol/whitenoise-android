package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AttachmentEntryFfi
import dev.ipf.marmotkit.AttachmentHistoryChangeFfi
import dev.ipf.marmotkit.AttachmentHistoryCursor
import dev.ipf.marmotkit.AttachmentHistoryVersion
import dev.ipf.marmotkit.AttachmentPageFfi
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
            if (previous == null || !state.initialized) {
                restoreRange()
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
                    clearCollection(keepBaseline = change == AttachmentHistoryChangeFfi.ADDITIONS)
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

    /**
     * Reads [slots] from MDK's attachment history with this owner's reader but none of its rows or cursor, which a
     * refresh empties and a restart cuts to the first page, and returns [project] of the entries found. Stops at the
     * page that completes the set. The read's version is held through [project] and compared again afterwards, so a
     * removal during the projection counts like one between pages: it restarts the read once from the head, and a
     * history that keeps changing fails rather than returning a slot that is gone. Additions never remove a slot, so
     * they don't restart it.
     */
    internal suspend fun <T : Any> projectSlots(
        slots: Set<Pair<String, UInt>>,
        project: suspend (List<AttachmentEntryFfi>) -> T,
    ): T {
        repeat(SLOT_READ_ATTEMPTS) { reader.readSlots(slots, ::checkCurrent, project)?.let { return it } }
        throw IOException("Attachment history changed during the slot read")
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
                    if (destructive && state.initialized) {
                        restart(restartAllowed)
                    } else {
                        check(page.hasMore == (page.nextCursor != null)) { "Invalid attachment page continuation" }
                        cursor?.close()
                        cursor = page.nextCursor
                        adoptHeadVersion(page.version, destructive)
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

    /** Replaces the head witness and stops searching for a tail deleted before the head arrived. */
    private fun adoptHeadVersion(
        version: AttachmentHistoryVersion,
        destructive: Boolean,
    ) {
        if (state.initialized) return
        if (destructive) restoreThrough = null
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

    /** Drops rows/cursor, optionally retaining a version witness until the replacement head arrives. */
    private fun clearCollection(keepBaseline: Boolean = false) {
        cursor?.close()
        cursor = null
        if (!keepBaseline) {
            baseline?.close()
            baseline = null
        }
        state = GroupAttachmentState()
    }

    /** Releases all handles exactly once, after any in-flight borrower has returned. */
    override fun close() {
        closed = true
        state = GroupAttachmentState()
        if (!busy) clearCollection()
    }
}

/** A slot read gets one fresh start after a removal before it gives up. */
private const val SLOT_READ_ATTEMPTS = 2

/** Where one page leaves a slot read: start over, finished, or continue from a cursor. */
private sealed interface SlotRead {
    data object Restart : SlotRead

    data object Done : SlotRead

    class More(
        val cursor: AttachmentHistoryCursor,
    ) : SlotRead
}

/**
 * One stable-generation read of [slots] from the head, projected while its version is still held, or null when a
 * removal invalidated it. Every cursor and version handle it receives is closed before it returns.
 */
private suspend fun <T : Any> GroupAttachmentReader.readSlots(
    slots: Set<Pair<String, UInt>>,
    checkCurrent: suspend () -> Unit,
    project: suspend (List<AttachmentEntryFfi>) -> T,
): T? {
    checkCurrent()
    val baseline = version()
    val found = mutableMapOf<Pair<String, UInt>, AttachmentEntryFfi>()
    var cursor: AttachmentHistoryCursor? = null
    try {
        while (true) {
            val previous = cursor
            cursor = null
            val read =
                try {
                    page(previous)
                } finally {
                    previous?.close()
                }
            val step =
                when (read) {
                    is AttachmentPageReadFfi.Page -> read.page.readSlotPage(slots, baseline, found, checkCurrent)
                    AttachmentPageReadFfi.RestartRequired, AttachmentPageReadFfi.CursorMismatch -> SlotRead.Restart
                    AttachmentPageReadFfi.InvalidLimit -> throw IOException("Attachment page limit rejected")
                }
            when (step) {
                SlotRead.Restart -> return null
                SlotRead.Done -> return projectUnchanged(found.values.toList(), baseline, checkCurrent, project)
                is SlotRead.More -> cursor = step.cursor
            }
        }
    } finally {
        cursor?.close()
        baseline.close()
    }
}

/** [project] of [entries], or null when a removal landed after [baseline] while it ran or the history was read. */
private suspend fun <T : Any> GroupAttachmentReader.projectUnchanged(
    entries: List<AttachmentEntryFfi>,
    baseline: AttachmentHistoryVersion,
    checkCurrent: suspend () -> Unit,
    project: suspend (List<AttachmentEntryFfi>) -> T,
): T? {
    val projected = project(entries)
    val change = version().use { it.changeSince(baseline) }
    checkCurrent()
    return projected.takeIf { change != AttachmentHistoryChangeFfi.RESTART_REQUIRED }
}

/** Adds this page's [slots] to [found]; only a returned [SlotRead.More] keeps the page's next cursor open. */
private suspend fun AttachmentPageFfi.readSlotPage(
    slots: Set<Pair<String, UInt>>,
    baseline: AttachmentHistoryVersion,
    found: MutableMap<Pair<String, UInt>, AttachmentEntryFfi>,
    checkCurrent: suspend () -> Unit,
): SlotRead {
    var kept = false
    try {
        checkCurrent()
        val step =
            if (version.changeSince(baseline) == AttachmentHistoryChangeFfi.RESTART_REQUIRED) {
                SlotRead.Restart
            } else {
                entries.filter { it.slotKey() in slots }.associateByTo(found) { it.slotKey() }
                if (found.size == slots.size || !hasMore) {
                    SlotRead.Done
                } else {
                    SlotRead.More(nextCursor ?: throw IOException("Attachment page omitted its cursor"))
                }
            }
        kept = step is SlotRead.More
        return step
    } finally {
        if (!kept) nextCursor?.close()
        version.close()
    }
}

/** Original slot identity also preserves rejected slots without renumbering accepted attachments. */
internal fun AttachmentEntryFfi.slotKey(): Pair<String, UInt> =
    messageIdHex to
        when (val outcome = attachment) {
            is MediaAttachmentOutcomeFfi.Accepted -> outcome.attachmentIndex
            is MediaAttachmentOutcomeFfi.Rejected -> outcome.attachmentIndex
        }
