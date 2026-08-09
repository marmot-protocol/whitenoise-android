package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PendingMediaSlotTest {
    @Test
    fun duplicateUrisReceiveIndependentOccurrenceIds() {
        val duplicate = Uri.parse("content://test/photo")
        var nextId = 0

        val slots =
            appendPendingMediaSlots(
                current = emptyList(),
                uris = listOf(duplicate, duplicate),
                maxItems = 10,
                createSlot = { uri -> PendingMediaSlot("slot-${nextId++}", uri) },
            )

        assertEquals(listOf(duplicate, duplicate), slots.map { it.uri })
        assertNotEquals(slots[0].id, slots[1].id)
    }

    @Test
    fun slotIdentityAndDuplicateUrisRoundTripThroughSavedStateCodec() {
        val tokens =
            listOf(
                "slot-a" to "content://test/photo?caption=a:b",
                "slot-b" to "content://test/photo?caption=a:b",
            )

        assertEquals(tokens, decodePendingMediaSlotTokens(encodePendingMediaSlotTokens(tokens)))
    }

    @Test
    fun malformedVersionedPayloadIsRejected() {
        assertNull(decodePendingMediaSlotTokens("media-slots-v1:4:abc"))
    }

    @Test
    fun legacyUriPayloadRestoresDeterministicMarkedOccurrenceIds() {
        val duplicate = "content://test/photo"
        val restored =
            requireNotNull(
                PendingMediaSlotListSaver.restore(encodeUriListTokens(listOf(duplicate, duplicate))),
            )

        assertEquals(listOf(Uri.parse(duplicate), Uri.parse(duplicate)), restored.map { it.uri })
        assertNotEquals(restored[0].id, restored[1].id)
        assertEquals(listOf(true, true), restored.map(PendingMediaSlot::isLegacyRestore))
    }

    @Test
    fun appendRespectsAlbumCapWithoutDroppingExistingSlots() {
        val existing = listOf(PendingMediaSlot("existing", Uri.parse("content://test/existing")))
        var nextId = 0

        val slots =
            appendPendingMediaSlots(
                current = existing,
                uris = listOf(Uri.parse("content://test/1"), Uri.parse("content://test/2")),
                maxItems = 2,
                createSlot = { uri -> PendingMediaSlot("new-${nextId++}", uri) },
            )

        assertEquals(listOf("existing", "new-0"), slots.map { it.id })
    }
}
