package dev.ipf.whitenoise.android.ui.conversation.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoAttachmentCacheProbePublicationTest {
    /** A delayed cache miss cannot erase a file published by the resolver after the probe began. */
    @Test
    fun staleCacheMissPreservesResolverPublication() {
        val cachedFile = newVideoAttachmentCacheFileState()
        val expectedFile = cachedFile.value
        val resolvedFile = File("resolved-video.mp4")

        cachedFile.value = resolvedFile
        publishVideoAttachmentCacheProbe(cachedFile, expectedFile, null)

        assertEquals(resolvedFile, cachedFile.value)
    }

    /** A delayed cache hit also cannot replace a newer resolver publication. */
    @Test
    fun staleCacheHitPreservesResolverPublication() {
        val originalFile = File("original-video.mp4")
        val cachedFile = newVideoAttachmentCacheFileState().apply { value = originalFile }
        val expectedFile = cachedFile.value
        val resolvedFile = File("resolved-video.mp4")

        cachedFile.value = resolvedFile
        publishVideoAttachmentCacheProbe(cachedFile, expectedFile, File("probed-video.mp4"))

        assertEquals(resolvedFile, cachedFile.value)
    }

    /** Republishing the same path replaces its observed identity, so a stale cache miss cannot clear it. */
    @Test
    fun staleCacheMissPreservesSamePathResolverPublication() {
        val originalFile = File("reused-video.mp4")
        val cachedFile = newVideoAttachmentCacheFileState().apply { value = originalFile }
        val expectedFile = cachedFile.value
        val resolvedFile = File(originalFile.path)

        cachedFile.value = resolvedFile
        publishVideoAttachmentCacheProbe(cachedFile, expectedFile, null)

        assertTrue(cachedFile.value === resolvedFile)
    }

    /** Without a concurrent resolver, a validated probe is adopted and an invalid cache entry is cleared. */
    @Test
    fun unchangedCacheStateAcceptsProbeAndClear() {
        val cachedFile = newVideoAttachmentCacheFileState()
        val probedFile = File("probed-video.mp4")

        publishVideoAttachmentCacheProbe(cachedFile, null, probedFile)
        assertEquals(probedFile, cachedFile.value)
        publishVideoAttachmentCacheProbe(cachedFile, probedFile, null)
        assertNull(cachedFile.value)
    }
}
