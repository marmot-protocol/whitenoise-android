package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test

class RawAvPlaybackEdgeTest {
    @Test
    fun playbackFramesFollowTheDisplayWithinTheProtocolRange() {
        assertEquals(2400, playbackEdge(2400))
        assertEquals(1280, playbackEdge(0))
        assertEquals(1280, playbackEdge(720))
        assertEquals(AvStreamProtocol.MAX_EDGE, playbackEdge(7680))
    }
}
