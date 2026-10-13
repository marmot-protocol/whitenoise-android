package dev.ipf.whitenoise.android.ui.conversation.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrEventMediaActionsTest {
    @Test
    fun explicitMediaLinksUseTypedMetadataOrPathHints() {
        val image = "https://media.example/opaque-image"
        val video = "https://media.example/opaque-video"
        val media = NostrEventMediaCandidates(listOf(image), listOf(video))
        val images = mutableListOf<String>()
        val videos = mutableListOf<String>()
        assertTrue(activateNostrMediaLink(image, media, { images += it }, { videos += it }))
        assertTrue(activateNostrMediaLink(video, media, { images += it }, { videos += it }))
        assertTrue(activateNostrMediaLink("https://cdn.example/file.MP4?version=1", media, null, { videos += it }))
        assertEquals(listOf(image), images)
        assertEquals(listOf(video, "https://cdn.example/file.MP4?version=1"), videos)
        assertFalse(activateNostrMediaLink("https://example.org/page", media, { images += it }, { videos += it }))
    }

    @Test
    fun rendererCanonicalizationPreservesTypedOpaqueMediaAndItsOriginalLocator() {
        val original = "HTTPS://CDN.EXAMPLE/opaque-stream"
        val media = NostrEventMediaCandidates(emptyList(), listOf(original))
        var opened: String? = null
        assertTrue(activateNostrMediaLink("https://cdn.example/opaque-stream", media, null, { opened = it }))
        assertEquals(original, opened)
    }

    @Test
    fun unsafeDestinationsNeverReachEitherViewer() {
        val urls =
            listOf(
                "http://cdn.example/image.jpg",
                "https://127.0.0.1/movie.mp4",
                "https://user:pass@cdn.example/a.png",
            )
        val media = NostrEventMediaCandidates(urls, urls)
        var opened = 0
        urls.forEach { assertFalse(activateNostrMediaLink(it, media, { opened++ }, { opened++ })) }
        assertEquals(0, opened)
    }
}
