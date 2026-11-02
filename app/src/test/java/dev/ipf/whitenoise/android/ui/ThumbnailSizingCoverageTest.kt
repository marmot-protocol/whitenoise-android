package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Prevents a chat route from bypassing measured decode admission or coupling full-viewer detail to thumbnail size. */
class ThumbnailSizingCoverageTest {
    /** Both entry points must measure their physical layout and consult resolution-aware cache entries. */
    @Test
    fun singleAndAlbumImagesUseMeasuredRequests() {
        val source = mediaSource("MediaImageBubbles.kt")
        listOf("MediaImageBubble", "MediaImageGridTile").forEach { name ->
            val body = source.functionBody(name)
            assertTrue("$name must observe physical bounds", ".onSizeChanged(decodeRequest::onSizeChanged)" in body)
            assertTrue("$name must preserve request provenance", "presentationMaxEdgePx >= requestedMaxEdgePx" in body)
            assertTrue("$name must admit the requested decode", "staticMaxEdgePx = requestedMaxEdgePx" in body)
            assertTrue("$name must read compatible cache entries", "controller.thumbnailEntryFor(" in body)
            assertFalse("$name must not force the old maximum", "MediaPipeline.THUMBNAIL_MAX_EDGE_PX" in body)
        }
    }

    /** The viewer still decodes original bytes with its own limit; thumbnail cache hits are only placeholders. */
    @Test
    fun fullViewerKeepsItsIndependentDecodeLimit() {
        val body = mediaSource("MediaViewer.kt").functionBody("ViewerPage")
        assertTrue(
            "Viewer must retain its own decode limit",
            "staticMaxEdgePx = MediaPipeline.VIEWER_MAX_EDGE_PX" in body,
        )
        assertFalse("Viewer must not admit thumbnails as final output", "thumbnailEntryFor(" in body)
    }

    /** Resolves source identically in module-local and repository-root test runners. */
    private fun mediaSource(name: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/$name"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/$name"),
        ).firstOrNull(File::exists)?.readText() ?: error("Missing $name")
}
