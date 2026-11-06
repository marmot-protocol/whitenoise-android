package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MediaUploadAttachmentResultFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Encrypted custom reactions must carry their definition; retry never republishes an uncertain accepted event. */
class CustomEmojiReactionWireCoverageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sent = SendSummaryFfi(1u, listOf("reaction"), SendAcceptDispositionFfi.PUBLISHED, SendMaintenanceDispositionFfi.READY)
    private val artwork = LocalEmojiArtwork(":party:", "party.png", "image/png", byteArrayOf(1, 2, 3))

    /** A native upload result with a stable original epoch and an opaque locator. */
    private fun reference() =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi("blossom", "https://fixture.invalid/art")),
            ciphertextSha256 = "ciphertext",
            plaintextSha256 = "plaintext",
            nonceHex = "nonce",
            fileName = "party.png",
            mediaType = "image/png",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 7uL,
            dim = null,
            thumbhash = null,
        )

    /** Records actual binding calls and refuses unrelated native work. */
    private fun engine(
        calls: MutableList<Pair<String, List<Any?>>>,
        onPublish: () -> SendSummaryFfi = { sent },
        onUpload: (MediaUploadRequestFfi) -> Unit = {},
    ): MarmotInterface =
        nativeBoundary { method, arguments ->
            val args = arguments.toList()
            calls += method to args
            when (method) {
                "uploadMedia" -> {
                    val request = args[2] as MediaUploadRequestFfi
                    onUpload(request)
                    MediaUploadResultFfi(listOf(MediaUploadAttachmentResultFfi(reference(), 3uL)), null)
                }
                "reactWithMedia", "reactToMessage" -> onPublish()
                else -> error("unexpected native call $method")
            }
        }

    /** A known image uploads without sending, then the exact target receives its matching tag and attachment. */
    @Test
    fun customReactionCarriesTheDefinitionAndKeepsTheOriginalTarget() =
        runTest {
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            var locked = false
            val native =
                engine(calls, onPublish = {
                    assertTrue(locked)
                    sent
                })
            native.sendCustomEmojiReaction(
                "a",
                "g",
                "target",
                ":party:",
                artwork,
                EmojiUploadCache(),
                currentEpoch = { 7uL },
                publishLock = { publish ->
                    locked = true
                    try {
                        publish()
                    } finally {
                        locked = false
                    }
                },
            )
            assertEquals(listOf("uploadMedia", "reactWithMedia"), calls.map { it.first })
            val upload = calls.first().second[2] as MediaUploadRequestFfi
            assertFalse(upload.send)
            assertArrayEquals(artwork.bytes, upload.attachments.single().plaintext)
            val publish = calls.last().second
            assertEquals(listOf("a", "g", "target", ":party:"), publish.take(4))
            assertEquals(listOf(listOf("emoji", "party", "https://fixture.invalid/art")), publish[4])
            assertEquals(listOf(reference()), publish[5])
        }

    /** Unicode and unknown literal shortcodes retain the plain reaction path without uploads. */
    @Test
    fun literalReactionsNeverInventAnImageDefinition() =
        runTest {
            for (emoji in listOf("👍", ":unknown:")) {
                val calls = mutableListOf<Pair<String, List<Any?>>>()
                engine(calls).sendCustomEmojiReaction("a", "g", "target", emoji, null, EmojiUploadCache())
                assertEquals(listOf("reactToMessage"), calls.map { it.first })
                assertEquals(emoji, calls.single().second[3])
            }
        }

    /** Changing the selected local file during upload cannot rewrite the captured definition. */
    @Test
    fun selectedArtworkIsFixedBeforeAnAsynchronousUpload() =
        runTest {
            val directory = folder.newFolder()
            val file = directory.resolve("party.png").apply { writeBytes(artwork.bytes) }
            val captured = readLocalEmojiArtwork(directory, listOf(":party:")).single()
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            engine(calls, onUpload = { file.writeBytes(byteArrayOf(9, 9)) })
                .sendCustomEmojiReaction("a", "g", "target", ":party:", captured, EmojiUploadCache(), currentEpoch = { 7uL })
            assertArrayEquals(artwork.bytes, (calls.first().second[2] as MediaUploadRequestFfi).attachments.single().plaintext)
        }

    /** A typed stale-epoch refusal permits exactly one fresh upload of the same captured bytes. */
    @Test
    fun staleReferenceIsReuploadedOnce() =
        runTest {
            var publishes = 0
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            engine(calls, onPublish = { if (++publishes == 1) throw staleEpochRejection() else sent })
                .sendCustomEmojiReaction("a", "g", "target", ":party:", artwork, EmojiUploadCache(), currentEpoch = { 7uL })
            assertEquals(listOf("uploadMedia", "reactWithMedia", "uploadMedia", "reactWithMedia"), calls.map { it.first })
        }

    /** Neither unknown acknowledgement nor a cancellation is retried as a new high-level reaction. */
    @Test
    fun uncertainPublicationAndCancellationAreNeverResent() =
        runTest {
            for (failure in listOf(IllegalStateException("acknowledgement unknown"), CancellationException("cancelled"))) {
                val calls = mutableListOf<Pair<String, List<Any?>>>()
                val result =
                    runCatching {
                        engine(calls, onPublish = { throw failure })
                            .sendCustomEmojiReaction("a", "g", "target", ":party:", artwork, EmojiUploadCache(), currentEpoch = { 7uL })
                    }
                assertSame(failure, result.exceptionOrNull())
                assertEquals(1, calls.count { it.first == "reactWithMedia" })
            }
        }

    /** A definition for another code is rejected before either upload or publication. */
    @Test
    fun mismatchedDefinitionCannotPublishABareReaction() =
        runTest {
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            val result =
                runCatching {
                    engine(calls).sendCustomEmojiReaction("a", "g", "target", ":other:", artwork, EmojiUploadCache())
                }
            assertTrue(result.isFailure)
            assertTrue(calls.isEmpty())
        }

    /** A cancelled or replaced owner cannot enter publication after its upload completes. */
    @Test
    fun ownerChangeDuringUploadPreventsPublication() =
        runTest {
            var current = true
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            val result =
                runCatching {
                    engine(calls, onUpload = { current = false }).sendCustomEmojiReaction(
                        "a",
                        "g",
                        "target",
                        ":party:",
                        artwork,
                        EmojiUploadCache(),
                        currentEpoch = { 7uL },
                        ensureCurrent = { check(current) },
                    )
                }
            assertTrue(result.isFailure)
            assertEquals(listOf("uploadMedia"), calls.map { it.first })
        }
}
