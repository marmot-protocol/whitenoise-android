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
class ShareStagingCapTest {
    private fun uri(index: Int) = Uri.parse("content://example/$index.jpg")

    @Test
    fun capShareStreamStaging_acceptsExactlyTenItems() {
        val uris = (1..10).map(::uri)
        val staging = ShareStreamStaging(mediaUris = uris, documentUris = emptyList())
        val capped = capShareStreamStaging(staging, maxItems = 10)
        assertEquals(10, capped.accepted.mediaUris.size)
        assertEquals(0, capped.droppedCount)
    }

    @Test
    fun capShareStreamStaging_dropsEleventhItemWithFeedbackCount() {
        val uris = (1..11).map(::uri)
        val staging = ShareStreamStaging(mediaUris = uris, documentUris = emptyList())
        val capped = capShareStreamStaging(staging, maxItems = 10)
        assertEquals(10, capped.accepted.mediaUris.size)
        assertEquals(1, capped.droppedCount)
    }

    @Test
    fun capShareStreamStaging_reportsEveryItemWhenShelfHasNoCapacity() {
        val staging = ShareStreamStaging(mediaUris = listOf(uri(1), uri(2)), documentUris = emptyList())
        val capped = capShareStreamStaging(staging, maxItems = 0)
        assertEquals(0, capped.accepted.mediaUris.size)
        assertEquals(2, capped.droppedCount)
    }

    @Test
    fun consumeCapped_isOneShotAndAppliesCap() {
        val store = ShareStagingStore()
        val uris = (1..11).map(::uri)
        store.stage("acct", "group", ShareStreamStaging(uris, emptyList()))
        val capped =
            store.consumeCapped(
                "acct",
                "group",
                existingMediaCount = 0,
                existingDocumentCount = 0,
                maxItems = 10,
            )
        assertEquals(10, checkNotNull(capped).accepted.mediaUris.size)
        assertEquals(1, capped.droppedCount)
        assertNull(store.consumeCapped("acct", "group", 0, 0, 10))
    }
}
