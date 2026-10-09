package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.ReceivedEmoji
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** All duplicate slots are hidden, but each shortcode keeps its original first-image acquisition. */
class ReceivedEmojiArtworkTest {
    /** Aliases share one load and all classified slots stay excluded even when acquisition fails. */
    @Test
    fun duplicateArtworkIsNotDownloadedOrUsedAsFallback() =
        runTest {
            for (available in listOf(false, true)) {
                val defined = mapOf(0 to listOf(":x:", ":alias:"), 2 to listOf(":x:", ":alias:"))
                val attachments = listOf(attachment(0), attachment(2), attachment(5))
                val loads = mutableListOf<Int>()
                val image = EmojiArt.Bundled(1)
                val art =
                    loadReceivedEmojiArtwork(defined, attachments) {
                        loads += it.index
                        if (available) image else null
                    }
                assertEquals(listOf(0), loads)
                assertEquals(if (available) mapOf(":x:" to image, ":alias:" to image) else emptyMap(), art)
                val visible =
                    MessageAttachmentSet(attachments, emptyList()).withoutEmoji(ReceivedEmoji(art, defined.keys))
                assertEquals(listOf(5), visible.accepted.map { it.index })
            }
        }

    /** A prior tag can insert a later slot first in the map without changing another code's image. */
    @Test
    fun attachmentOrderWinsOverDefinitionMapOrder() =
        runTest {
            val defined = linkedMapOf(4 to listOf(":unique:", ":shared:"), 2 to listOf(":shared:"))
            val loads = mutableListOf<Int>()
            val art =
                loadReceivedEmojiArtwork(defined, listOf(attachment(2), attachment(4))) {
                    loads += it.index
                    EmojiArt.Bundled(it.index)
                }
            assertEquals(listOf(2, 4), loads)
            assertEquals(EmojiArt.Bundled(2), art[":shared:"])
            assertEquals(EmojiArt.Bundled(4), art[":unique:"])
        }

    /** A message without definitions performs no image acquisition. */
    @Test
    fun ordinarySharedImagesAreNeverLoadedAsEmoji() =
        runTest {
            val art = loadReceivedEmojiArtwork(emptyMap(), listOf(attachment(3))) { error("unexpected acquisition") }
            assertTrue(art.isEmpty())
        }

    /** Accepted slots use their authored indexes; artwork identity is supplied by native-validated references. */
    private fun attachment(index: Int) =
        IndexedValue(
            index,
            MediaAttachmentReferenceFfi(
                emptyList(),
                "11".repeat(32),
                "22".repeat(32),
                "33".repeat(12),
                "emoji.png",
                "image/png",
                EncryptedMediaVersionFfi.V2,
                1u,
                null,
                null,
            ),
        )
}
