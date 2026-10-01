package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.state.ChatListWindowHandle
import dev.ipf.whitenoise.android.state.ChatListWindowSet
import dev.ipf.whitenoise.android.state.presentedRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Forward-edge demand against a bounded window that behaves like MDK's (#2926): 50 rows initially, 50
 * per page, at most 200 retained, and a capped window that can only move forward past its anchored row.
 * The list is a real lazy list driven by the real paging and anchor effects from the chat list screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListWindowForwardPagingComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val model = ModelChatListWindow(ChatListViewFfi.CHATS, totalRows = TOTAL_ROWS)
    private val windows =
        runBlocking {
            ChatListWindowSet.open("acct") { _, view ->
                if (view == ChatListViewFfi.CHATS) model else ModelChatListWindow(view, totalRows = 0)
            }
        }
    private var rows by mutableStateOf(windows.rowIds())
    private lateinit var listState: LazyListState
    private lateinit var scope: CoroutineScope

    /**
     * A fling from the top never settles until it parks at the end of the capped 200-row window, where
     * the page issued mid-fling made no progress. The settled anchor report alone must resume paging,
     * and continued scrolling must then reach all 500 chats without a busy loop.
     */
    @Test
    fun parkedForwardDemandResumesAfterTheSettledAnchorAndReachesEveryChat() {
        mount()
        val fling = fling(steps = FLING_STEPS)
        composeRule.waitUntil(timeoutMillis = 20_000) { fling.isCompleted }
        composeRule.waitForIdle()
        assertEquals(CAPPED_ROWS, composeRule.runOnIdle { rows.size })
        assertTrue(windows.hasMoreAfter(ChatListViewFfi.CHATS))

        // No further user input: the anchor report after the fling settles is the only fresh state.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.runOnIdle { rows.contains(rowId(CAPPED_ROWS + 49)) }
        }

        var attempts = 0
        while (composeRule.runOnIdle { rows.last() } != rowId(TOTAL_ROWS - 1) && attempts < MAX_SCROLL_ATTEMPTS) {
            attempts += 1
            val reachedBefore = composeRule.runOnIdle { rows.last() }
            val jump = scrollToLastRow()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                jump.isCompleted && composeRule.runOnIdle { rows.last() } != reachedBefore
            }
        }
        assertEquals(rowId(TOTAL_ROWS - 1), composeRule.runOnIdle { rows.last() })
        assertFalse(windows.hasMoreAfter(ChatListViewFfi.CHATS))
        assertTrue("page commands: ${model.pageCalls.size}", model.pageCalls.size <= MAX_PAGE_COMMANDS)
        assertTrue(model.pageCalls.all { it == ChatListPageDirectionFfi.FORWARD })
    }

    /**
     * A reader who scrolls slowly, settling every few rows, must see every chat in order across the
     * first shift of the capped window. A forward page chained before the shifted rows were laid out
     * moves the window past the lazy list's key-retention range, so the viewport falls back to its old
     * index and skips the rows in between.
     */
    @Test
    fun slowScrollAcrossTheCapShowsEveryRowInOrder() {
        mount()
        val seen = sortedSetOf<Int>()
        var previous = visibleRowIndices().also { seen += it }
        var gestures = 0
        while (previous.last() < TOTAL_ROWS - 1 && gestures < MAX_SLOW_GESTURES) {
            gestures += 1
            val drag = fling(steps = SLOW_GESTURE_ROWS)
            composeRule.waitUntil(timeoutMillis = 10_000) { drag.isCompleted }
            awaitWindowCommandsSettled()
            val visible = visibleRowIndices()
            assertTrue(
                "gesture $gestures jumped from ${previous.last()} to ${visible.first()}",
                visible.first() <= previous.last() + 1,
            )
            seen += visible
            previous = visible
        }
        assertEquals(TOTAL_ROWS - 1, previous.last())
        assertEquals((0 until TOTAL_ROWS).toList(), seen.toList())
        assertTrue(model.pageCalls.all { it == ChatListPageDirectionFfi.FORWARD })
    }

    /** Absolute row positions of the chat rows currently laid out, in viewport order. */
    private fun visibleRowIndices(): List<Int> =
        composeRule.runOnIdle {
            listState.layoutInfo.visibleItemsInfo.map { rowIndex(it.key as String) }
        }

    /** Waits until no further window command lands, so a settle's anchor report and any page it triggers are in. */
    private fun awaitWindowCommandsSettled() {
        var last = -1
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.waitForIdle()
            val now = model.commandCount
            Thread.sleep(SETTLE_PROBE_MS)
            (now == last).also { last = now }
        }
    }

    /** Mounts a fixed-height lazy list fed by the window set, with the screen's forward and anchor effects. */
    private fun mount() {
        composeRule.setContent {
            listState = rememberLazyListState()
            scope = rememberCoroutineScope()
            rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
            LaunchedEffect(listState) {
                collectChatListForwardPaging(listState = listState) {
                    windows.pageForward(ChatListViewFfi.CHATS)?.also { publish() } != null
                }
            }
            LaunchedEffect(listState) {
                collectChatListVisibleAnchor(
                    listState = listState,
                    searchActive = { false },
                    chatRowKey = { key -> key as? String },
                ) { rowId -> if (windows.setVisibleAnchor(ChatListViewFfi.CHATS, rowId) != null) publish() }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(VIEWPORT_HEIGHT)) {
                items(rows, key = { it }) { id -> Box(Modifier.fillMaxWidth().height(ROW_HEIGHT)) { Text(id) } }
            }
        }
    }

    private var rowHeightPx = 0f

    private var publishScheduled = false

    /**
     * Mirrors the controller: installed frames reach the lazy list coalesced, two frames after the
     * first of them, because the controller debounces its projection rebuild by one frame and the
     * list measures the rebuilt rows on the frame after that.
     */
    private fun publish() {
        if (publishScheduled) return
        publishScheduled = true
        scope.launch {
            repeat(PUBLISH_LAG_FRAMES) { withFrameNanos { } }
            publishScheduled = false
            rows = windows.rowIds()
        }
    }

    /** One continuous drag of [steps] rows: the list reports scrolling until the gesture ends. */
    private fun fling(steps: Int): Job =
        composeRule.runOnIdle {
            scope.launch {
                listState.scroll {
                    repeat(steps) {
                        scrollBy(rowHeightPx)
                        withFrameNanos { }
                    }
                }
            }
        }

    /** A settled jump to the last retained row, as a reader who keeps pulling at the end would land. */
    private fun scrollToLastRow(): Job {
        val lastIndex = rows.lastIndex
        return composeRule.runOnIdle { scope.launch { listState.scrollToItem(lastIndex) } }
    }

    private companion object {
        const val TOTAL_ROWS = 500
        const val CAPPED_ROWS = 200
        const val FLING_STEPS = 420
        const val MAX_SCROLL_ATTEMPTS = 20
        const val MAX_PAGE_COMMANDS = 16
        const val SLOW_GESTURE_ROWS = 8
        const val MAX_SLOW_GESTURES = 120
        const val SETTLE_PROBE_MS = 40L
        const val PUBLISH_LAG_FRAMES = 2
        val ROW_HEIGHT = 48.dp
        val VIEWPORT_HEIGHT = 480.dp
    }
}

/** The merged retained rows as the lazy-list keys the screen would render. */
private fun ChatListWindowSet.rowIds(): List<String> = rows.map { it.row.groupIdHex }

/** Zero-padded row key for position [index] in the 500-row account. */
private fun rowId(index: Int): String = "row-%03d".format(index)

/** The position a [rowId] key stands for. */
private fun rowIndex(id: String): Int = id.removePrefix("row-").toInt()

/**
 * A window handle with MDK's positioning rules: `limit` rows starting `before` rows ahead of the anchor,
 * the limit growing by each page up to 200, and a forward page at the cap consuming `before` instead.
 * With no anchor the window is pinned to the top, so a capped forward page cannot move it.
 */
private class ModelChatListWindow(
    private val view: ChatListViewFfi,
    private val totalRows: Int,
) : ChatListWindowHandle {
    private val all = List(totalRows, ::rowId)
    private var limit = 50
    private var anchor: String? = null
    private var before = 0
    private var sequence = 0uL
    private var current = read()
    val pageCalls = mutableListOf<ChatListPageDirectionFfi>()

    /** Every command this window answered, pages and viewport moves alike. */
    @Volatile
    var commandCount = 0
        private set

    /** Resolves the retained rows for the current position, the way one storage read would. */
    private fun read(): ChatListWindowSnapshotFfi {
        val start = anchor?.let { id -> (all.indexOf(id) - before).coerceAtLeast(0) } ?: 0
        val end = (start + limit).coerceAtMost(totalRows)
        val rows = all.subList(start, end)
        anchor?.let { id -> before = rows.indexOf(id).coerceAtLeast(0) }
        return ChatListWindowSnapshotFfi(
            subscriptionGeneration = "gen",
            sequence = sequence,
            view = view,
            rows = rows.map(::presentedRow),
            hasMoreBefore = start > 0,
            hasMoreAfter = end < totalRows,
            anchor = ChatListAnchorOutcomeFfi.Top,
        )
    }

    /** Rejects a command quoting anything but the newest sequence, as the runtime does after a viewport move. */
    private fun requireCurrent(quoted: ULong) {
        if (quoted != sequence) throw MarmotKitException.ChatWindowStale()
    }

    /** Publishes the position reached by a command as the next replacement. */
    private fun commit(): ChatListWindowSnapshotFfi {
        commandCount += 1
        sequence += 1uL
        current = read()
        return current
    }

    /** The newest frame this window has produced. */
    override fun snapshot(): ChatListWindowSnapshotFfi = current

    /** No stream replacements arrive in this fixture; commands are the only source of new frames. */
    override suspend fun next(): ChatListWindowSnapshotFfi? = awaitCancellation()

    /** Grows the limit by [count] up to the cap; at the cap a forward page consumes the rows before the anchor. */
    override suspend fun page(
        sequence: ULong,
        direction: ChatListPageDirectionFfi,
        count: UInt,
    ): ChatListWindowSnapshotFfi {
        requireCurrent(sequence)
        pageCalls += direction
        if (anchor == null && direction == ChatListPageDirectionFfi.BACKWARD) {
            val firstRow = current.rows.firstOrNull()?.row
            anchor = firstRow?.groupIdHex
        }
        val oldLimit = limit
        limit = (oldLimit + count.toInt()).coerceAtMost(MAX_RETAINED_ROWS)
        before =
            when (direction) {
                ChatListPageDirectionFfi.FORWARD -> (before - (oldLimit + count.toInt() - limit)).coerceAtLeast(0)
                ChatListPageDirectionFfi.BACKWARD -> (before + count.toInt()).coerceAtMost(limit - 1)
            }
        return commit()
    }

    /** Anchors the window on a retained row, or on the true top when that row is the first row of the list. */
    override suspend fun setVisibleAnchor(
        sequence: ULong,
        groupIdHex: String,
    ): ChatListWindowSnapshotFfi {
        requireCurrent(sequence)
        val index = current.rows.indexOfFirst { it.row.groupIdHex.equals(groupIdHex, ignoreCase = true) }
        if (index < 0) throw MarmotKitException.ChatWindowAnchorOutside()
        anchor = if (index == 0 && !current.hasMoreBefore) null else current.rows[index].row.groupIdHex
        before = index
        return commit()
    }

    /** Drops the anchor so the window is pinned to the top of the list again. */
    override suspend fun returnToTop(sequence: ULong): ChatListWindowSnapshotFfi {
        requireCurrent(sequence)
        anchor = null
        before = 0
        return commit()
    }

    /** Nothing native to release in this fixture. */
    override fun close() = Unit

    private companion object {
        const val MAX_RETAINED_ROWS = 200
    }
}
