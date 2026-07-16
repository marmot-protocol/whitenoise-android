package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionDetectionCacheTest {
    @Test
    fun unchangedMessageAndTokensReuseDetection() {
        val cache = MentionDetectionCache(maxEntries = 4)
        val document = document("hello")
        var detections = 0

        assertTrue(
            cache.getOrCompute("message-1", document) {
                detections += 1
                true
            },
        )
        assertTrue(
            cache.getOrCompute("message-1", document.copy()) {
                detections += 1
                false
            },
        )

        assertEquals(1, detections)
    }

    @Test
    fun changedTokensForSameMessageAreReclassified() {
        val cache = MentionDetectionCache(maxEntries = 4)
        var detections = 0

        assertFalse(
            cache.getOrCompute("message-1", document("before")) {
                detections += 1
                false
            },
        )
        assertTrue(
            cache.getOrCompute("message-1", document("after")) {
                detections += 1
                true
            },
        )

        assertEquals(2, detections)
    }

    @Test
    fun leastRecentlyUsedEntriesAreEvictedAtBound() {
        val cache = MentionDetectionCache(maxEntries = 2)
        val one = document("one")
        val two = document("two")
        var detections = 0

        cache.getOrCompute("one", one) {
            detections += 1
            false
        }
        cache.getOrCompute("two", two) {
            detections += 1
            false
        }
        cache.getOrCompute("one", one) {
            detections += 1
            true
        }
        cache.getOrCompute("three", document("three")) {
            detections += 1
            false
        }
        cache.getOrCompute("two", two) {
            detections += 1
            true
        }

        assertEquals(4, detections)
        assertEquals(2, cache.sizeForTests())
    }

    private fun document(text: String) =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(text)))),
        )
}
