package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.layout.PrefetchRequest
import androidx.compose.foundation.lazy.layout.PrefetchRequestScope
import androidx.compose.foundation.lazy.layout.PrefetchScheduler
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.channels.Channel

/**
 * Runs queued prefetches to completion once per frame, between frames as the platform scheduler does after a draw.
 * Compose installs a scheduler that does nothing under Robolectric, so tests of premeasuring provide this one and
 * launch [run] in the composition whose frames should drive it.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class FramePrefetchScheduler : PrefetchScheduler {
    private val pending = ArrayDeque<PrefetchRequest>()
    private val queued = Channel<Unit>(Channel.CONFLATED)
    private val unbounded =
        object : PrefetchRequestScope {
            /** No frame deadline, so a request always finishes in the frame it runs in. */
            override fun availableTimeNanos(): Long = Long.MAX_VALUE
        }

    /** Queues [prefetchRequest] for the next frame. */
    override fun schedulePrefetch(prefetchRequest: PrefetchRequest) {
        pending += prefetchRequest
        queued.trySend(Unit)
    }

    /** Drains the queue on the frame after work arrives, never asking for a frame while idle, until cancelled. */
    suspend fun run(): Nothing {
        while (true) {
            queued.receive()
            withFrameNanos { }
            while (pending.isNotEmpty()) {
                val request = pending.removeFirst()
                while (with(request) { unbounded.execute() }) Unit
            }
        }
    }
}
