package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.PrefetchScheduler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** Measures a transcript row the list has not laid out, without moving the list. */
internal fun interface ConversationRowPremeasurer {
    /**
     * Starts measuring the row at [index] off screen and reports its main-axis size to [onMeasured]. Returns the call
     * that cancels it and releases the row, or null when nothing can be measured yet.
     */
    fun premeasure(
        index: Int,
        onMeasured: (Int) -> Unit,
    ): (() -> Unit)?

    companion object {
        /** Measures nothing, for a list without a premeasuring prefetch strategy. */
        val None = ConversationRowPremeasurer { _, _ -> null }
    }
}

/**
 * The scheduler the transcript's prefetches run on, or null for the platform's. Compose turns prefetching off under
 * Robolectric, so a test that exercises premeasuring provides one here.
 */
@OptIn(ExperimentalFoundationApi::class) // The prefetch scheduler is still experimental.
internal val LocalConversationPrefetchScheduler = staticCompositionLocalOf<PrefetchScheduler?> { null }

/**
 * The transcript's default prefetch strategy, unchanged, that also lends its prefetch scope to [premeasure]. A row the
 * tail layout never reached is precomposed and measured with the list's own item constraints, so its height matches
 * what a layout would measure, and the visible rows are neither moved nor recomposed.
 */
@OptIn(ExperimentalFoundationApi::class) // The lazy list's prefetch strategy and scope are still experimental.
internal class ConversationTranscriptPrefetchStrategy(
    private val scheduler: PrefetchScheduler? = null,
    private val delegate: LazyListPrefetchStrategy = LazyListPrefetchStrategy(),
) : LazyListPrefetchStrategy by delegate,
    ConversationRowPremeasurer {
    private var scope: LazyListPrefetchScope? = null

    override val prefetchScheduler: PrefetchScheduler?
        get() = scheduler ?: delegate.prefetchScheduler

    /** Keeps the list's prefetch scope, then prefetches as the default strategy does. */
    override fun LazyListPrefetchScope.onScroll(
        delta: Float,
        layoutInfo: LazyListLayoutInfo,
    ) {
        scope = this
        with(delegate) { onScroll(delta, layoutInfo) }
    }

    /** Keeps the list's prefetch scope, then prefetches as the default strategy does. */
    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {
        scope = this
        with(delegate) { onVisibleItemsUpdated(layoutInfo) }
    }

    /** Schedules an urgent premeasure of [index], which runs within the next frame instead of waiting for idle time. */
    override fun premeasure(
        index: Int,
        onMeasured: (Int) -> Unit,
    ): (() -> Unit)? {
        val handle = scope?.schedulePrefetch(index) { onMeasured(mainAxisSize) } ?: return null
        handle.markAsUrgent()
        return handle::cancel
    }
}

/** One premeasuring strategy per transcript [key], on the scheduler [LocalConversationPrefetchScheduler] provides. */
@OptIn(ExperimentalFoundationApi::class) // The prefetch scheduler is still experimental.
@Composable
internal fun rememberConversationTranscriptPrefetch(key: Any): ConversationTranscriptPrefetchStrategy {
    val scheduler = LocalConversationPrefetchScheduler.current
    return remember(key) { ConversationTranscriptPrefetchStrategy(scheduler) }
}
