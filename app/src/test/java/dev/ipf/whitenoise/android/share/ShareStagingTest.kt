package dev.ipf.whitenoise.android.share

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareStagingTest {
    private val image get() = Uri.parse("content://example/photo.jpg")
    private val video get() = Uri.parse("content://example/clip.mp4")
    private val pdf get() = Uri.parse("content://example/doc.pdf")

    @Test
    fun classifyShareStreams_splitsMediaAndDocuments() {
        val staging =
            classifyShareStreams(
                uris = listOf(image, video, pdf),
                resolveMime = { uri ->
                    when (uri) {
                        image -> "image/jpeg"
                        video -> "video/mp4"
                        else -> "application/pdf"
                    }
                },
            )
        assertEquals(listOf(image, video), staging.mediaUris)
        assertEquals(listOf(pdf), staging.documentUris)
    }

    @Test
    fun classifyShareStreams_usesIntentMimeWhenResolverSilent() {
        val staging =
            classifyShareStreams(
                uris = listOf(image),
                resolveMime = { "" },
                intentMimeType = "image/png",
            )
        assertEquals(listOf(image), staging.mediaUris)
    }

    @Test
    fun shareStagingStore_consumeIsOneShot() {
        val store = ShareStagingStore()
        val staging = ShareStreamStaging(listOf(image), emptyList())
        store.stage("acct", "group", staging)
        assertEquals(staging, store.consume("acct", "group"))
        assertNull(store.consume("acct", "group"))
    }

    @Test
    fun shareStreamStagingRevision_incrementsOnEachStreamStage() {
        val store = ShareStagingStore()
        assertEquals(0, store.revision)

        store.stage("acct", "group", ShareStreamStaging(listOf(image), emptyList()))
        assertEquals(1, store.revision)
        store.stage("acct", "group", ShareStreamStaging(listOf(video), emptyList()))

        assertEquals(2, store.revision)
        assertEquals(
            ShareStreamStaging(listOf(image, video), emptyList()),
            store.consume("acct", "group"),
        )
    }
}
