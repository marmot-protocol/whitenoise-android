package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MediaUploadAttachmentResultFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomEmojiSendTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A reference whose first locator is [url], as the upload would return it. */
    private fun reference(url: String) =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi("blossom", url), MediaLocatorFfi("blossom", "https://mirror/$url")),
            ciphertextSha256 = "",
            plaintextSha256 = "",
            nonceHex = "",
            fileName = "emoji.png",
            mediaType = "image/png",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 7uL,
            dim = null,
            thumbhash = null,
        )

    private val sent =
        SendSummaryFfi(1u, listOf("event"), SendAcceptDispositionFfi.PUBLISHED, SendMaintenanceDispositionFfi.READY)

    /** A recording native boundary that uploads each image to `https://blob/<fileName>`. */
    private fun recordingEngine(calls: MutableList<Pair<String, List<Any?>>>) =
        nativeBoundary { method, args ->
            calls += method to args.toList()
            when (method) {
                "uploadMedia" ->
                    MediaUploadResultFfi(
                        (args[2] as MediaUploadRequestFfi).attachments.map {
                            MediaUploadAttachmentResultFfi(reference("https://blob/${it.fileName}"), 1uL)
                        },
                        null,
                    )
                "sendTaggedMedia" -> sent
                else -> error(method)
            }
        }

    /** The user's `:code:` emoji as a small PNG-named file. */
    private fun artwork(code: String) = LocalEmojiArtwork(":$code:", "$code.png", "image/png", byteArrayOf(1, 2, 3))

    /** Only the user's real, supported, bounded image files are returned, in the order asked. */
    @Test
    fun readsOnlyUsableEmojiFilesInRequestedOrder() {
        val directory = folder.newFolder("emoji")
        directory.resolve("party.png").writeBytes(byteArrayOf(1, 2))
        directory.resolve("wave.webp").writeBytes(byteArrayOf(3))
        directory.resolve("odd.img").writeBytes(byteArrayOf(4))
        directory.resolve("huge.png").writeBytes(ByteArray(CustomEmojiStore.MAX_BYTES + 1))
        directory.resolve(".staged.tmp").writeBytes(byteArrayOf(5))
        val read =
            readLocalEmojiArtwork(directory, listOf(":wave:", ":odd:", ":huge:", ":absent:", ":party:", ":staged:"))
        assertEquals(listOf(":wave:", ":party:"), read.map { it.shortcode })
        assertEquals(listOf("image/webp", "image/png"), read.map { it.mediaType })
        assertArrayEquals(byteArrayOf(1, 2), read.last().bytes)
        assertTrue(readLocalEmojiArtwork(folder.root.resolve("missing"), listOf(":party:")).isEmpty())
    }

    /** Restarting reads the same files again, so the next send carries the same artwork. */
    @Test
    fun rereadingAfterRestartGivesTheSameArtwork() {
        val directory = folder.newFolder("emoji")
        directory.resolve("party.png").writeBytes(byteArrayOf(9, 9))
        val first = readLocalEmojiArtwork(directory, listOf(":party:")).single()
        val second = readLocalEmojiArtwork(directory, listOf(":party:")).single()
        assertEquals(first.fileName, second.fileName)
        assertArrayEquals(first.bytes, second.bytes)
    }

    /** A chat uploads without sending, then sends one tagged media message naming each image by its first locator. */
    @Test
    fun chatUploadsUnsentThenSendsTaggedMedia() =
        runTest {
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            val result =
                recordingEngine(calls).sendTextWithCustomEmoji(
                    "account",
                    "group",
                    "hi :party: :wave:",
                    listOf(artwork("party"), artwork("wave")),
                )
            assertEquals(sent, result)
            assertEquals(listOf("uploadMedia", "sendTaggedMedia"), calls.map { it.first })
            val upload = calls[0].second[2] as MediaUploadRequestFfi
            assertFalse(upload.send)
            assertEquals(listOf("party.png", "wave.png"), upload.attachments.map { it.fileName })
            val tagged = calls[1].second
            assertEquals("hi :party: :wave:", tagged[3])
            assertEquals(
                listOf(
                    listOf("emoji", "party", "https://blob/party.png"),
                    listOf("emoji", "wave", "https://blob/wave.png"),
                ),
                tagged[4],
            )
            assertEquals(2, (tagged[2] as List<*>).size)
        }

    /** A failed upload sends nothing, so no message or reaction can reference an image that is not stored. */
    @Test
    fun failedUploadSendsNothing() =
        runTest {
            val calls = mutableListOf<String>()
            val engine =
                nativeBoundary { method, _ ->
                    calls += method
                    error("upload failed")
                }
            val failure = runCatching { engine.sendTextWithCustomEmoji("a", "g", ":party:", listOf(artwork("party"))) }
            assertTrue(failure.isFailure)
            assertEquals(listOf("uploadMedia"), calls)
        }

    /** A connection loss during upload is a definite failure, never an uncertain delivery that stays pending. */
    @Test
    fun uploadStageTransportLossIsADefiniteFailure() =
        runTest {
            val engine = nativeBoundary { _, _ -> throw MarmotKitException.TransportClosed() }
            val failure =
                runCatching { engine.sendTextWithCustomEmoji("a", "g", ":party:", listOf(artwork("party"))) }
                    .exceptionOrNull()
            assertTrue(failure is EmojiUploadFailure)
            assertFalse(isAmbiguousRelayDeliveryError(failure!!))
        }

    /** Loss of the publish call itself stays ambiguous, because the event may already have reached a relay. */
    @Test
    fun publicationStageTransportLossStaysUncertain() =
        runTest {
            val engine =
                nativeBoundary { method, args ->
                    when (method) {
                        "uploadMedia" ->
                            MediaUploadResultFfi(
                                (args[2] as MediaUploadRequestFfi).attachments.map {
                                    MediaUploadAttachmentResultFfi(reference("https://blob/${it.fileName}"), 1uL)
                                },
                                null,
                            )
                        else -> throw MarmotKitException.TransportClosed()
                    }
                }
            val failure =
                runCatching { engine.sendTextWithCustomEmoji("a", "g", ":party:", listOf(artwork("party"))) }
                    .exceptionOrNull()
            assertFalse(failure is EmojiUploadFailure)
            assertTrue(isAmbiguousRelayDeliveryError(failure!!))
        }

    /** The tag always names the first locator, so a peer that joined before an epoch change resolves the same blob. */
    @Test
    fun tagsNameTheFirstLocatorRegardlessOfEpoch() {
        val tags = emojiTags(listOf(artwork("party")), listOf(reference("https://blob/party.png")))
        assertEquals(listOf(listOf("emoji", "party", "https://blob/party.png")), tags)
    }
}
