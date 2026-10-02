package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MediaUploadAttachmentResultFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

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

    /**
     * A recording native boundary that uploads each image to `https://blob/<fileName>`. [publish]
     * decides what `sendTaggedMedia` does for the n-th call (starting at 1).
     */
    private fun recordingEngine(
        calls: MutableList<Pair<String, List<Any?>>>,
        publish: (Int) -> SendSummaryFfi = { sent },
    ): MarmotInterface {
        var publishes = 0
        return nativeBoundary { method, args ->
            calls += method to args.toList()
            when (method) {
                "uploadMedia" ->
                    MediaUploadResultFfi(
                        (args[2] as MediaUploadRequestFfi).attachments.map {
                            MediaUploadAttachmentResultFfi(reference("https://blob/${it.fileName}"), 1uL)
                        },
                        null,
                    )
                "sendTaggedMedia" -> publish(++publishes)
                else -> error(method)
            }
        }
    }

    /** The user's `:code:` emoji, distinguished by [bytes], as a small PNG-named file. */
    private fun artwork(
        code: String,
        bytes: ByteArray = byteArrayOf(1, 2, 3, code.length.toByte()),
    ) = LocalEmojiArtwork(":$code:", "$code.png", "image/png", bytes)

    /** Sends `hi :party:` with [artwork] through [cache], reporting [epoch] as the group's current epoch. */
    private suspend fun MarmotInterface.send(
        artwork: List<LocalEmojiArtwork>,
        cache: EmojiUploadCache = EmojiUploadCache(),
        epoch: ULong = 7uL,
        ensureCurrent: () -> Unit = {},
    ) = sendTextWithCustomEmoji("a", "g", "hi :party:", artwork, cache, { epoch }, ensureCurrent)

    /** Only the user's real, supported, bounded, sendable image files are returned, in the order asked. */
    @Test
    fun readsOnlyUsableEmojiFilesInRequestedOrder() {
        val directory = folder.newFolder("emoji")
        directory.resolve("party.png").writeBytes(byteArrayOf(1, 2))
        directory.resolve("wave.webp").writeBytes(byteArrayOf(3))
        directory.resolve("odd.img").writeBytes(byteArrayOf(4))
        directory.resolve("huge.png").writeBytes(ByteArray(CustomEmojiStore.MAX_BYTES + 1))
        directory.resolve("bad-code.png").writeBytes(byteArrayOf(6))
        directory.resolve(".staged.tmp").writeBytes(byteArrayOf(5))
        val read =
            readLocalEmojiArtwork(
                directory,
                listOf(":wave:", ":odd:", ":huge:", ":absent:", ":party:", ":staged:", ":bad-code:"),
            )
        assertEquals(listOf(":wave:", ":party:"), read.map { it.shortcode })
        assertEquals(listOf("image/webp", "image/png"), read.map { it.mediaType })
        assertArrayEquals(byteArrayOf(1, 2), read.last().bytes)
        assertTrue(readLocalEmojiArtwork(folder.root.resolve("missing"), listOf(":party:")).isEmpty())
    }

    /** A directory that lists [names] in exactly that order, whatever order the filesystem would use. */
    private fun directoryListing(
        directory: File,
        vararg names: String,
    ): File =
        object : File(directory.path) {
            /** The fixed listing, so the test never depends on the filesystem's own order. */
            override fun listFiles(): Array<File> = names.map { File(directory, it) }.toTypedArray()
        }

    /** A legacy `party.img` listed ahead of `party.png` never shadows it, whatever order the files list in. */
    @Test
    fun staleUnsendableFileDoesNotShadowTheSendableOne() {
        val directory = folder.newFolder("emoji")
        directory.resolve("party.img").writeBytes(byteArrayOf(8))
        directory.resolve("party.png").writeBytes(byteArrayOf(1, 2))
        val listed = directoryListing(directory, "party.img", "party.png")
        assertEquals("party.img", listed.listFiles()!!.first().name)
        val read = readLocalEmojiArtwork(listed, listOf(":party:")).single()
        assertEquals("party.png", read.fileName)
        assertArrayEquals(byteArrayOf(1, 2), read.bytes)
    }

    /** An oversized first match falls through to the next sendable file, as the picker does. */
    @Test
    fun oversizedFirstMatchFallsThroughToTheNextSendableFile() {
        val directory = folder.newFolder("emoji")
        directory.resolve("party.gif").writeBytes(ByteArray(CustomEmojiStore.MAX_BYTES + 1))
        directory.resolve("party.png").writeBytes(byteArrayOf(1, 2))
        val listed = directoryListing(directory, "party.png", "party.gif")
        val read = readLocalEmojiArtwork(listed, listOf(":party:")).single()
        assertEquals("party.png", read.fileName)
        directory.resolve("party.png").writeBytes(ByteArray(CustomEmojiStore.MAX_BYTES + 1))
        assertTrue(readLocalEmojiArtwork(listed, listOf(":party:")).isEmpty())
    }

    /** A second read after the file changed returns the new bytes and digest, so nothing stale is sent. */
    @Test
    fun rereadingAfterAnEditReturnsTheCurrentImage() {
        val directory = folder.newFolder("emoji")
        directory.resolve("party.png").writeBytes(byteArrayOf(9, 9))
        val first = readLocalEmojiArtwork(directory, listOf(":party:")).single()
        directory.resolve("party.png").writeBytes(byteArrayOf(7, 7, 7))
        val second = readLocalEmojiArtwork(directory, listOf(":party:")).single()
        assertArrayEquals(byteArrayOf(9, 9), first.bytes)
        assertArrayEquals(byteArrayOf(7, 7, 7), second.bytes)
        assertFalse(first.sha256 == second.sha256)
        assertEquals(64, first.sha256.length)
    }

    /** The image identity is the SHA-256 of its bytes, so aliases of one image can share an upload. */
    @Test
    fun digestIsTheSha256OfTheBytes() {
        assertEquals(
            "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb",
            LocalEmojiArtwork(":a:", "a.png", "image/png", "a".toByteArray()).sha256,
        )
    }

    /** A chat uploads without sending, then sends one tagged media message naming each image. */
    @Test
    fun chatUploadsUnsentThenSendsTaggedMedia() =
        runTest {
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            val result = recordingEngine(calls).send(listOf(artwork("party"), artwork("wave")))
            assertEquals(sent, result)
            assertEquals(listOf("uploadMedia", "sendTaggedMedia"), calls.map { it.first })
            val upload = calls[0].second[2] as MediaUploadRequestFfi
            assertFalse(upload.send)
            assertEquals(listOf("party.png", "wave.png"), upload.attachments.map { it.fileName })
            val tagged = calls[1].second
            assertEquals("hi :party:", tagged[3])
            assertEquals(
                listOf(
                    listOf("emoji", "party", "https://blob/party.png"),
                    listOf("emoji", "wave", "https://blob/wave.png"),
                ),
                tagged[4],
            )
            assertEquals(2, (tagged[2] as List<*>).size)
        }

    /** Two shortcodes for one image send one attachment and one tag each. */
    @Test
    fun aliasesOfOneImageShareOneAttachment() =
        runTest {
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            val same = byteArrayOf(5, 5)
            recordingEngine(calls).send(listOf(artwork("party", same), artwork("parrot", same)))
            val upload = calls[0].second[2] as MediaUploadRequestFfi
            assertEquals(1, upload.attachments.size)
            val tagged = calls[1].second
            assertEquals(1, (tagged[2] as List<*>).size)
            assertEquals(2, (tagged[4] as List<*>).size)
            // The receiver maps both aliases onto the one attachment the sender attached.
            @Suppress("UNCHECKED_CAST")
            val receiverTags = (tagged[4] as List<List<String>>).map { MessageTagFfi(it) }

            @Suppress("UNCHECKED_CAST")
            val receiverAttachments = (tagged[2] as List<MediaAttachmentReferenceFfi>).withIndex().toList()
            assertEquals(
                mapOf(0 to listOf(":party:", ":parrot:")),
                Nip30Emoji.attachmentShortcodes(receiverTags, receiverAttachments),
            )
        }

    /** A second send of the same image under the same epoch reuses the upload, and a new epoch re-uploads. */
    @Test
    fun uploadsAreReusedWithinAnEpochAndRepeatedAfterOne() =
        runTest {
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            val engine = recordingEngine(calls)
            val cache = EmojiUploadCache()
            engine.send(listOf(artwork("party")), cache)
            engine.send(listOf(artwork("party")), cache)
            assertEquals(1, calls.count { it.first == "uploadMedia" })
            engine.send(listOf(artwork("party")), cache, epoch = 8uL)
            assertEquals(2, calls.count { it.first == "uploadMedia" })
        }

    /** A reference MDK rejects is dropped and re-uploaded once, then the send reports a changed chat. */
    @Test
    fun rejectedReferenceIsReuploadedOnceThenReportedAsAChangedChat() =
        runTest {
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            val stale = MarmotKitException.InvalidMediaReference(STALE_EPOCH_DETAILS)
            val recovered = recordingEngine(calls) { n -> if (n == 1) throw stale else sent }
            assertEquals(sent, recovered.send(listOf(artwork("party"))))
            assertEquals(2, calls.count { it.first == "uploadMedia" })

            val failing = mutableListOf<Pair<String, List<Any?>>>()
            val rejection = MarmotKitException.InvalidMediaReference(STALE_EPOCH_DETAILS)
            val engine = recordingEngine(failing) { throw rejection }
            val failure = runCatching { engine.send(listOf(artwork("party"))) }.exceptionOrNull()
            assertTrue(failure is EmojiChatChangedException)
            assertEquals(2, failing.count { it.first == "sendTaggedMedia" })
        }

    /**
     * Chat A uses cached X plus new Y. While A's upload of Y is held, chat B fills the 64-entry cache and
     * evicts X. A must still publish exactly once more, because it captured X before the upload began.
     */
    @Test
    fun anotherChatEvictingACachedReferenceDoesNotFailAnInFlightSend() =
        runTest {
            val sends = mutableListOf<String>()
            val held = mutableListOf<Continuation<Any?>>()
            val engine =
                nativeBoundary { method, args ->
                    when (method) {
                        "uploadMedia" -> {
                            val request = args[2] as MediaUploadRequestFfi
                            if (args[1] == "chat-a" && request.attachments.any { it.fileName == "y.png" }) {
                                @Suppress("UNCHECKED_CAST")
                                held += args.last() as Continuation<Any?>
                                COROUTINE_SUSPENDED
                            } else {
                                uploaded(request)
                            }
                        }
                        "sendTaggedMedia" -> {
                            sends += args[1] as String
                            sent
                        }
                        else -> error(method)
                    }
                }
            val cache = EmojiUploadCache()
            val x = artwork("x", byteArrayOf(1, 1))
            val y = artwork("y", byteArrayOf(2, 2))

            /** Sends `hi` with [emoji] in [group] through the shared cache. */
            suspend fun sendIn(
                group: String,
                emoji: List<LocalEmojiArtwork>,
            ) = engine.sendTextWithCustomEmoji("a", group, "hi", emoji, cache, { 7uL })

            sendIn("chat-a", listOf(x))
            val inFlight = async { sendIn("chat-a", listOf(x, y)) }
            runCurrent()
            assertEquals(1, held.size)

            sendIn("chat-b", (0 until 64).map { artwork("b$it", byteArrayOf(it.toByte(), 9)) })
            held.single().resume(
                MediaUploadResultFfi(
                    listOf(MediaUploadAttachmentResultFfi(reference("https://blob/y.png"), 1uL)),
                    null,
                ),
            )

            assertEquals(sent, inFlight.await())
            assertEquals(listOf("chat-a", "chat-b", "chat-a"), sends)
        }

    /** The upload result the fake blob server returns for [request]: one stored reference per image. */
    private fun uploaded(request: MediaUploadRequestFfi) =
        MediaUploadResultFfi(
            request.attachments.map { MediaUploadAttachmentResultFfi(reference("https://blob/${it.fileName}"), 1uL) },
            null,
        )

    /** Eviction never removes a reference the current send needs, even when it is the oldest cached one. */
    @Test
    fun evictionNeverRemovesAReferenceTheSendNeeds() =
        runTest {
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            val engine = recordingEngine(calls)
            val cache = EmojiUploadCache()
            val first = (0 until 64).map { artwork("old$it", byteArrayOf(it.toByte(), 1)) }
            engine.send(first, cache)
            val next = listOf(first.first(), artwork("fresh", byteArrayOf(99, 99)))
            assertEquals(sent, engine.send(next, cache))
            assertEquals(2, calls.count { it.first == "sendTaggedMedia" })
            assertEquals(2, calls.count { it.first == "uploadMedia" })
        }

    /** Only an epoch rejection is stale: a locator-policy rejection is not re-uploaded or called a changed chat. */
    @Test
    fun onlyEpochRejectionsAreTreatedAsStale() =
        runTest {
            val stale =
                MarmotKitException.InvalidMediaReference(
                    "media reference was encrypted at epoch 3 but the group is at epoch 4; upload it again",
                )
            val policy = MarmotKitException.InvalidMediaReference("no locator this client may fetch")
            assertTrue(isStaleEmojiReference(stale))
            assertFalse(isStaleEmojiReference(policy))
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            val engine = recordingEngine(calls) { throw policy }
            val failure = runCatching { engine.send(listOf(artwork("party"))) }.exceptionOrNull()
            assertFalse(failure is EmojiChatChangedException)
            assertEquals(1, calls.count { it.first == "uploadMedia" })
            assertEquals(1, calls.count { it.first == "sendTaggedMedia" })
        }

    /** Each failure kind has its own notice: over the limit and a changed chat are not the generic failure. */
    @Test
    fun limitAndChangedChatHaveSpecificNotices() {
        val generic = sendFailureMessageRes(IllegalStateException("x"))
        val limit = sendFailureMessageRes(EmojiSendLimitException("too many"))
        val changed = sendFailureMessageRes(EmojiChatChangedException(IllegalStateException("x")))
        assertFalse(limit == generic)
        assertFalse(changed == generic)
        assertFalse(limit == changed)
    }

    /** A send over MDK's tag limits, or with an unsendable code, fails before any native call. */
    @Test
    fun limitsAreCheckedBeforeAnyUpload() =
        runTest {
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            val engine = recordingEngine(calls)
            val tooMany = (0..64).map { artwork("c$it") }
            assertTrue(runCatching { engine.send(tooMany) }.exceptionOrNull() is EmojiSendLimitException)
            val unsendable = listOf(LocalEmojiArtwork(":bad-code:", "x.png", "image/png", byteArrayOf(1)))
            assertTrue(runCatching { engine.send(unsendable) }.exceptionOrNull() is EmojiSendLimitException)
            assertEquals(emptyList<String>(), calls.map { it.first })
            engine.send((0 until 64).map { artwork("c$it") })
            assertEquals(2, calls.size)
        }

    /** Tag values over the byte limit are refused once the URLs are known, before publishing. */
    @Test
    fun oversizedTagValuesAreRefused() {
        val tags = listOf(listOf("emoji", "party", "u".repeat(17 * 1024)))
        assertTrue(runCatching { checkEmojiTagBytes(tags) }.exceptionOrNull() is EmojiSendLimitException)
        checkEmojiTagBytes(listOf(listOf("emoji", "party", "https://blob/party.png")))
    }

    /** When the account, chat or send changed during the upload, nothing is published. */
    @Test
    fun scopeChangeAfterUploadStopsBeforePublishing() =
        runTest {
            val calls = mutableListOf<Pair<String, List<Any?>>>()
            val failure =
                runCatching {
                    recordingEngine(calls).send(listOf(artwork("party")), ensureCurrent = { error("account changed") })
                }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(listOf("uploadMedia"), calls.map { it.first })
        }

    /** A failed upload sends nothing, so no message can reference an image that is not stored. */
    @Test
    fun failedUploadSendsNothing() =
        runTest {
            val calls = mutableListOf<String>()
            val engine =
                nativeBoundary { method, _ ->
                    calls += method
                    error("upload failed")
                }
            val failure = runCatching { engine.send(listOf(artwork("party"))) }
            assertTrue(failure.exceptionOrNull() is EmojiUploadFailure)
            assertEquals(listOf("uploadMedia"), calls)
        }

    /** A connection loss during upload is a definite failure, never an uncertain delivery. */
    @Test
    fun uploadStageTransportLossIsADefiniteFailure() =
        runTest {
            val engine = nativeBoundary { _, _ -> throw MarmotKitException.TransportClosed() }
            val failure = runCatching { engine.send(listOf(artwork("party"))) }.exceptionOrNull()
            assertTrue(failure is EmojiUploadFailure)
            assertFalse(isAmbiguousRelayDeliveryError(failure!!))
            assertTrue(isRetryableEmojiUploadFailure(failure))
        }

    /** Loss of the publish call itself stays ambiguous and is never retried. */
    @Test
    fun publicationStageTransportLossStaysUncertain() =
        runTest {
            val engine =
                recordingEngine(mutableListOf()) { throw MarmotKitException.TransportClosed() }
            val failure = runCatching { engine.send(listOf(artwork("party"))) }.exceptionOrNull()
            assertFalse(failure is EmojiUploadFailure)
            assertTrue(isAmbiguousRelayDeliveryError(failure!!))
            assertFalse(isRetryableEmojiUploadFailure(failure))
        }

    /** Only upload failures that look like connectivity are retried by the recovery loop. */
    @Test
    fun retryPolicyAcceptsConnectivityUploadFailuresOnly() {
        assertTrue(isRetryableEmojiUploadFailure(EmojiUploadFailure(MarmotKitException.TransportClosed())))
        assertTrue(isRetryableEmojiUploadFailure(EmojiUploadFailure(java.io.IOException("connection reset"))))
        assertFalse(isRetryableEmojiUploadFailure(EmojiUploadFailure(IllegalStateException("blob rejected"))))
        assertFalse(isRetryableEmojiUploadFailure(MarmotKitException.TransportClosed()))
        assertFalse(isRetryableEmojiUploadFailure(EmojiSendLimitException("too many")))
    }

    /** Replies and injected publishers never carry emoji, and a plain send does. */
    @Test
    fun emojiSendAppliesOnlyToPlainTextWithoutAnInjectedPublisher() {
        assertTrue(emojiSendApplies(replyTarget = null, customPublisherInjected = false))
        assertFalse(emojiSendApplies(replyTarget = "parent", customPublisherInjected = false))
        assertFalse(emojiSendApplies(replyTarget = null, customPublisherInjected = true))
    }

    /** The tag always names the first locator, so peers resolve the same blob across epochs. */
    @Test
    fun tagsNameTheFirstLocatorRegardlessOfEpoch() {
        val art = artwork("party")
        val tags = emojiTags(listOf(art), mapOf(art.sha256 to reference("https://blob/party.png")))
        assertEquals(listOf(listOf("emoji", "party", "https://blob/party.png")), tags)
    }
}

/** The text MDK gives an `InvalidMediaReference` for a reference from an earlier epoch. */
internal const val STALE_EPOCH_DETAILS =
    "media reference was encrypted at epoch 3 but the group is at epoch 4; upload it again"
