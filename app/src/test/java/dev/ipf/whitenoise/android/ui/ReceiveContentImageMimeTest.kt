package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.conversation.media.receiveContentImageCacheSuffix
import dev.ipf.whitenoise.android.ui.conversation.media.receiveContentImageValueOrNull
import dev.ipf.whitenoise.android.ui.conversation.media.receiveContentMimeIsImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveContentImageMimeTest {
    @Test
    fun acceptsConcreteImageMime() {
        assertTrue(receiveContentMimeIsImage("image/png", clipDeclaresImage = false))
        assertTrue(receiveContentMimeIsImage("IMAGE/JPEG", clipDeclaresImage = false))
    }

    @Test
    fun acceptsBlankResolverMimeOnlyWhenClipDeclaresImage() {
        assertTrue(receiveContentMimeIsImage("", clipDeclaresImage = true))
        assertFalse(receiveContentMimeIsImage("", clipDeclaresImage = false))
    }

    @Test
    fun rejectsNonImageConcreteMimeEvenWhenClipDeclaresImage() {
        assertFalse(receiveContentMimeIsImage("text/plain", clipDeclaresImage = true))
        assertFalse(receiveContentMimeIsImage("video/mp4", clipDeclaresImage = true))
        assertFalse(receiveContentMimeIsImage("application/pdf", clipDeclaresImage = true))
    }

    @Test
    fun receiveContentImageValueRejectsNullUri() {
        assertNull(receiveContentImageValueOrNull<String>(null, clipDeclaresImage = true) { "image/png" })
    }

    @Test
    fun receiveContentImageValueAcceptsBlankResolverMimeWhenClipDeclaresImage() {
        val value = "content://clipboard/image"

        assertEquals(
            value,
            receiveContentImageValueOrNull(value, clipDeclaresImage = true) { "" },
        )
    }

    @Test
    fun receiveContentImageValueRejectsConcreteNonImageEvenWhenClipDeclaresImage() {
        assertNull(
            receiveContentImageValueOrNull("content://clipboard/text", clipDeclaresImage = true) { "text/plain" },
        )
    }

    @Test
    fun receiveContentImageValueSeparatesMixedImageAndTextItems() {
        val items = listOf("image", "text")
        val accepted =
            items.mapNotNull { item ->
                receiveContentImageValueOrNull(item, clipDeclaresImage = true) {
                    if (it == "image") "image/png" else "text/plain"
                }
            }
        val remaining = items - accepted.toSet()

        assertEquals(listOf("image"), accepted)
        assertEquals(listOf("text"), remaining)
    }

    @Test
    fun receiveContentImageCacheSuffixFallsBackForUnknownImageMime() {
        assertEquals(".jpg", receiveContentImageCacheSuffix("image/jpeg"))
        assertEquals(".img", receiveContentImageCacheSuffix(""))
    }
}
