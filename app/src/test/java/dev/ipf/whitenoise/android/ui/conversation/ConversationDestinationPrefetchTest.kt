package dev.ipf.whitenoise.android.ui.conversation

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchResultScope
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.lazy.layout.PrefetchRequest
import androidx.compose.foundation.lazy.layout.PrefetchRequestScope
import androidx.compose.foundation.lazy.layout.PrefetchScheduler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A far mention snap used to compose its whole destination viewport in one frame (#3150). These pin
 * that the destination is composed ahead of the snap, reused by it, released when unused, and that
 * only the mention button's jump asks for it.
 */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDestinationPrefetchTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Prepared rows are composed before the snap, and the snap reuses them instead of composing again. */
    @Test
    fun preparedDestinationRowsComposeBeforeTheSnapAndAreReusedByIt() {
        val fixture = PrefetchFixture()
        fixture.show()

        val ready = fixture.prepare()

        composeRule.runOnUiThread {
            assertEquals(true, ready[0])
            assertEquals("the list has not moved yet", 0, fixture.listState.firstVisibleItemIndex)
            DESTINATION.forEach { assertEquals("row $it composed ahead of the snap", 1, fixture.compositions[it]) }
        }
        composeRule.runOnUiThread { fixture.scope.launch { fixture.listState.scrollToItem(DESTINATION.first) } }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            val visible = fixture.listState.layoutInfo.visibleItemsInfo.map { it.index }
            assertTrue("the snap shows prepared rows", visible.any { it in DESTINATION })
            visible.filter { it in DESTINATION }.forEach {
                assertEquals("prepared row $it reused by the snap", 1, fixture.compositions[it])
            }
        }
    }

    /** Rows prepared for a snap that never happened are released rather than kept composed. */
    @Test
    fun releaseDisposesPreparedRowsTheSnapDidNotUse() {
        val fixture = PrefetchFixture()
        fixture.show()
        fixture.prepare()

        composeRule.runOnUiThread { fixture.prefetch.release(inUse = emptySet()) }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            DESTINATION.forEach { assertEquals("prepared row $it released", 1, fixture.disposals[it]) }
        }
    }

    /** Before the list has measured there is no prefetch scope, so preparation ends at once. */
    @Test
    fun withoutAMeasuredListNothingIsPrepared() {
        val prepared = runBlocking { ConversationDestinationPrefetch().prepare(DESTINATION, emptySet()) }
        assertFalse(prepared)
    }

    /** Ordinary scroll prefetch keeps going through the default strategy. */
    @Test
    fun scrollPrefetchDelegatesToTheDefaultStrategy() {
        val delegate = RecordingStrategy()
        val prefetch = ConversationDestinationPrefetch(scrollPrefetch = delegate)
        val scope = NoopPrefetchScope()
        val layoutInfo = LazyListState().layoutInfo

        with(prefetch) {
            scope.onScroll(4f, layoutInfo)
            scope.onVisibleItemsUpdated(layoutInfo)
        }

        assertEquals(listOf("scroll 4.0", "visible"), delegate.calls)
    }

    /** The mention button's far jump prepares its snap destination, snaps, and then releases it. */
    @Test
    fun mentionJumpPreparesTheSnapDestinationBeforeSnapping() =
        runTest {
            val writer = PreparingWriter()
            ConversationScrollCoordinator(writer).programmaticJump(
                targetMessageId = "mention",
                reason = ConversationScrollReason.Mention,
                resultingMode = ConversationScrollMode.ReadingHistory("mention", 0),
            ) { animateScrollToItem(200, 0, mentionJump = true) }

            assertEquals(listOf("prepare 190", "snap 190", "release", "animate 200"), writer.events)
        }

    /** Other far jumps keep their existing writes and never ask for destination rows. */
    @Test
    fun otherFarJumpsDoNotPrepareADestination() =
        runTest {
            val writer = PreparingWriter()
            ConversationScrollCoordinator(writer).programmaticJump(
                targetMessageId = "reply",
                reason = ConversationScrollReason.Reply,
                resultingMode = ConversationScrollMode.ReadingHistory("reply", 0),
            ) { animateScrollToItem(200, 0) }

            assertEquals(listOf("snap 190", "animate 200"), writer.events)
        }

    /** A long transcript laid out newest first, counting each row's compositions and disposals. */
    private inner class PrefetchFixture {
        val prefetch = ConversationDestinationPrefetch(prefetchScheduler = LooperPrefetchScheduler())
        val listState = LazyListState(0, 0, prefetch)
        val compositions = mutableMapOf<Int, Int>()
        val disposals = mutableMapOf<Int, Int>()
        lateinit var scope: CoroutineScope

        /** Shows the transcript and waits for its first measure. */
        fun show() {
            composeRule.setContent {
                scope = rememberCoroutineScope()
                LazyColumn(modifier = Modifier.height(VIEWPORT), state = listState, reverseLayout = true) {
                    items((0 until ROW_COUNT).toList(), key = { it }) { index ->
                        compositions[index] = (compositions[index] ?: 0) + 1
                        DisposableEffect(index) { onDispose { disposals[index] = (disposals[index] ?: 0) + 1 } }
                        Box(Modifier.fillMaxWidth().height(ROW_HEIGHT))
                    }
                }
            }
            composeRule.waitForIdle()
        }

        /** Prepares [DESTINATION] from the composition's own scope and returns its outcome holder. */
        fun prepare(): Array<Boolean?> {
            val ready = arrayOf<Boolean?>(null)
            composeRule.runOnUiThread {
                scope.launch {
                    val laidOut = listState.layoutInfo.visibleItemsInfo.mapTo(HashSet()) { it.index }
                    ready[0] = prefetch.prepare(DESTINATION, laidOut)
                }
            }
            composeRule.waitForIdle()
            return ready
        }
    }

    /** Runs each prefetch request on the main looper with unlimited time, so tests need no frame idle. */
    private class LooperPrefetchScheduler : PrefetchScheduler {
        private val handler = Handler(Looper.getMainLooper())

        /** Executes [prefetchRequest] to completion once the main looper next runs. */
        override fun schedulePrefetch(prefetchRequest: PrefetchRequest) {
            handler.post { runToCompletion(prefetchRequest) }
        }

        /** Repeats [request] until it reports no remaining work. */
        private tailrec fun runToCompletion(request: PrefetchRequest) {
            if (request.execute(UnlimitedTime)) runToCompletion(request)
        }
    }

    /** A request scope that never runs out of time. */
    private object UnlimitedTime : PrefetchRequestScope {
        /** Reports unlimited time so every request finishes in one pass. */
        override fun availableTimeNanos(): Long = Long.MAX_VALUE
    }

    /** Records the strategy callbacks the destination prefetch forwards. */
    private class RecordingStrategy : LazyListPrefetchStrategy {
        val calls = mutableListOf<String>()

        /** Records a forwarded scroll. */
        override fun LazyListPrefetchScope.onScroll(
            delta: Float,
            layoutInfo: LazyListLayoutInfo,
        ) {
            calls += "scroll $delta"
        }

        /** Records a forwarded visible-items update. */
        override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {
            calls += "visible"
        }

        /** Records a forwarded nested prefetch. */
        override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {
            calls += "nested $firstVisibleItemIndex"
        }
    }

    /** A prefetch scope that schedules nothing. */
    private class NoopPrefetchScope : LazyListPrefetchScope {
        /** Returns a handle for a request that is never run. */
        override fun schedulePrefetch(
            index: Int,
            onPrefetchFinished: (LazyListPrefetchResultScope.() -> Unit)?,
        ): LazyLayoutPrefetchState.PrefetchHandle = NoopHandle
    }

    /** A handle with nothing to cancel. */
    private object NoopHandle : LazyLayoutPrefetchState.PrefetchHandle {
        /** Nothing was scheduled. */
        override fun cancel() = Unit

        /** Nothing was scheduled. */
        override fun markAsUrgent() = Unit
    }

    /** Records writes and destination preparation in order. */
    private class PreparingWriter : ConversationScrollWriter {
        override var firstVisibleItemIndex = 0
        val events = mutableListOf<String>()

        /** Records an instant write. */
        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            firstVisibleItemIndex = index
            events += "snap $index"
        }

        /** Records an animated write. */
        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            firstVisibleItemIndex = index
            events += "animate $index"
        }

        /** Records the destination a snap asked for. */
        override suspend fun prepareSnapDestination(index: Int) {
            events += "prepare $index"
        }

        /** Records the release after the snap. */
        override fun releaseSnapDestination() {
            events += "release"
        }
    }
}

private const val ROW_COUNT = 200
private val DESTINATION = 120..130
private val VIEWPORT = 400.dp
private val ROW_HEIGHT = 40.dp
