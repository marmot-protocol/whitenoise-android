@file:Suppress("MatchingDeclarationName") // The file owns the slice names and the two helpers that emit them.

package dev.ipf.whitenoise.android.state

import androidx.tracing.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * Perfetto slice names for one page of conversation history.
 *
 * `WNPerf` already times a page for a tester reading Logcat, but a Macrobenchmark can only aggregate
 * what is in the trace. These slices let `TraceSectionMetric` count the pages a scroll journey
 * crossed, sum the engine wait separately from the app's own work, and count the two events that
 * mean a reader saw paging happen: stopping on the edge with more history behind it, and a page
 * landing only after the reader had already reached the rows it replaced.
 */
internal object ConversationPagingTraceSection {
    /** The window command, including any not-ready wait — the engine's share of a page. */
    const val WINDOW = "WhiteNoise.conversation.page.window"

    /** Preparing the returned window off the main thread. */
    const val PREPARE = "WhiteNoise.conversation.page.prepare"

    /** Folding the prepared window into the timeline, preparation included. */
    const val APPLY = "WhiteNoise.conversation.page.apply"

    /** The reader is on the edge row with more history behind it and no page has landed. */
    const val EDGE_STOP = "WhiteNoise.conversation.page.edgeStop"

    /** A page landed while the reader still had rows between them and the old edge. */
    const val RUNWAY_KEPT = "WhiteNoise.conversation.page.runwayKept"

    /** A page landed after the reader had already reached the old edge. */
    const val EDGE_REACHED = "WhiteNoise.conversation.page.edgeReached"
}

private val pagingTraceCookies = AtomicInteger()

/**
 * Runs [block] inside an async Perfetto slice named [name].
 *
 * Async because a page suspends across dispatchers: the window command hops to IO and preparation
 * to Default, and a synchronous section would be attributed to whichever thread happened to end it.
 */
internal inline fun <T> tracedPagingSection(
    name: String,
    block: () -> T,
): T {
    val cookie = pagingTraceCookies.incrementAndGet()
    Trace.beginAsyncSection(name, cookie)
    try {
        return block()
    } finally {
        Trace.endAsyncSection(name, cookie)
    }
}

/** Records a zero-length slice so the event can be counted from the trace. */
internal fun markPagingEvent(name: String) {
    Trace.beginSection(name)
    Trace.endSection()
}
