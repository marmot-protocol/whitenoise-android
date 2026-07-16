package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageOverlayWindowPruneTest {
    @Test
    fun keepsOnlyTimelineAndOptimisticOverlayTargets() {
        assertEquals(
            setOf("timeline", "optimistic"),
            retainedMessageOverlayTargets(
                timelineMessageIds = setOf("timeline", "other"),
                optimisticMessageIds = setOf("optimistic"),
                overlayTargetIds = setOf("evicted", "timeline", "optimistic"),
            ),
        )
    }

    @Test
    fun emptyWindowDropsEveryOverlayTarget() {
        assertEquals(
            emptySet<String>(),
            retainedMessageOverlayTargets(
                timelineMessageIds = emptySet(),
                optimisticMessageIds = emptySet(),
                overlayTargetIds = setOf("old-delete", "old-reaction"),
            ),
        )
    }
}
