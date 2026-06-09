package dev.ipf.darkmatter.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class MediaPipelineBoundedReadTest {
    @Test
    fun readsExactPayload_whenUnderCap() {
        val source = ByteArray(1024) { i -> (i % 256).toByte() }
        val result = MediaPipeline.readBoundedBytes(ByteArrayInputStream(source), cap = 2048)
        assertArrayEquals(source, result)
    }

    @Test
    fun readsExactPayload_atCapBoundary() {
        // A payload exactly the size of the cap MUST go through. A
        // strict-greater-than check is what makes this work; a
        // greater-than-or-equal check would reject the boundary.
        val source = ByteArray(4096) { 0x42 }
        val result = MediaPipeline.readBoundedBytes(ByteArrayInputStream(source), cap = 4096)
        assertArrayEquals(source, result)
    }

    @Test
    fun returnsNull_oneByteOverCap() {
        val source = ByteArray(4097) { 0x42 }
        assertNull(MediaPipeline.readBoundedBytes(ByteArrayInputStream(source), cap = 4096))
    }

    @Test
    fun returnsNull_whenWildlyOverCap() {
        // Bounded loop must not buffer the whole payload before noticing. We
        // can't directly assert that here, but a `null` result on a payload
        // multiples of cap large at least proves the abort path runs.
        val source = ByteArray(1 * 1024 * 1024) { 0x42 }
        assertNull(MediaPipeline.readBoundedBytes(ByteArrayInputStream(source), cap = 8 * 1024))
    }

    @Test
    fun readsEmptyStream() {
        val result = MediaPipeline.readBoundedBytes(ByteArrayInputStream(ByteArray(0)), cap = 1024)
        assertArrayEquals(ByteArray(0), result)
    }
}
