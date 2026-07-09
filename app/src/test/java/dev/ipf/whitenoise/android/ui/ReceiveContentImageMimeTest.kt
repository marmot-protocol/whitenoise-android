package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.conversation.media.receiveContentMimeIsImage
import org.junit.Assert.assertFalse
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
}
