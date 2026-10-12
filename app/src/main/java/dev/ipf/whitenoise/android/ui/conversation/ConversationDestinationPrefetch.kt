package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.lazy.layout.PrefetchScheduler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.withFrameNanos

/** Frames a far jump waits for its destination rows before it snaps without them. */
internal const val DESTINATION_PREFETCH_FRAME_BUDGET = 4

/**
 * Transcript prefetch: Compose's ordinary scroll prefetch, plus the rows a far programmatic jump is
 * about to land on. A snap onto rows nobody had composed paid their whole composition and
 * measurement in that one frame (#3150); preparing them first moves the work into idle frame time.
 *
 * Main-thread only: strategy callbacks run during measurement and requests come from scroll commands.
 */
@OptIn(ExperimentalFoundationApi::class)
@Stable
internal class ConversationDestinationPrefetch(
    private val scrollPrefetch: LazyListPrefetchStrategy = LazyListPrefetchStrategy(),
    override val prefetchScheduler: PrefetchScheduler? = null,
) : LazyListPrefetchStrategy {
    private var scope: LazyListPrefetchScope? = null
    private val prepared = mutableMapOf<Int, LazyLayoutPrefetchState.PrefetchHandle>()

    /** Keeps the default scroll prefetch and the list's current prefetch scope. */
    override fun LazyListPrefetchScope.onScroll(
        delta: Float,
        layoutInfo: LazyListLayoutInfo,
    ) {
        scope = this
        with(scrollPrefetch) { this@onScroll.onScroll(delta, layoutInfo) }
    }

    /** Keeps the default scroll prefetch and the list's current prefetch scope. */
    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {
        scope = this
        with(scrollPrefetch) { this@onVisibleItemsUpdated.onVisibleItemsUpdated(layoutInfo) }
    }

    /** Nested lists inside rows keep the default behaviour. */
    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {
        with(scrollPrefetch) { this@onNestedPrefetch.onNestedPrefetch(firstVisibleItemIndex) }
    }

    /**
     * Composes and measures [indices] in idle frame time, skipping rows in [laidOut], and waits at
     * most [maxFrames] frames for them. Returns whether every requested row was ready; on false the
     * caller snaps exactly as it would have without preparation. Before the list has measured once
     * there is nothing to prepare against.
     */
    suspend fun prepare(
        indices: IntRange,
        laidOut: Set<Int>,
        maxFrames: Int = DESTINATION_PREFETCH_FRAME_BUDGET,
    ): Boolean {
        release(laidOut)
        val prefetchScope = scope ?: return false
        var pending = 0
        for (index in indices) {
            if (index in laidOut) continue
            pending++
            prepared[index] = prefetchScope.schedulePrefetch(index) { pending-- }
        }
        var frames = 0
        while (pending > 0 && frames < maxFrames) {
            withFrameNanos { }
            frames++
        }
        return pending == 0
    }

    /**
     * Cancels prepared rows outside [inUse] and forgets the rest: rows the snap brought into view now
     * belong to the list, and cancelling an unused request releases its precomposition.
     */
    fun release(inUse: Set<Int>) {
        prepared.forEach { (index, handle) -> if (index !in inUse) handle.cancel() }
        prepared.clear()
    }
}

/** The transcript's list state, with [prefetch] as its prefetch strategy. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberConversationTranscriptListState(
    prefetch: ConversationDestinationPrefetch,
    initialFirstVisibleItemIndex: Int,
    initialFirstVisibleItemScrollOffset: Int,
): LazyListState = rememberLazyListState(initialFirstVisibleItemIndex, initialFirstVisibleItemScrollOffset, prefetch)
