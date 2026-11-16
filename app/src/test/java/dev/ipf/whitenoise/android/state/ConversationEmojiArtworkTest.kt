package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.media.AttachmentTooLargeToPresentException
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/** Reads one received original within a byte limit, as the send path does. */
private typealias ReceivedReader = suspend (String, ConversationEmojiSource, Long) -> LocalEmojiArtwork

/** Received originals obey a single tag and retained-byte budget before another original is copied. */
class ConversationEmojiArtworkTest {
    private val source = ConversationEmojiSource("message", 0, reference(0))

    /** Existing artwork is reserved first, and a received original beyond the remaining budget stays literal. */
    @Test
    fun receivedOriginalsBeyondTheRemainingBudgetStayLiteral() =
        runTest {
            val known = LocalEmojiArtwork(":local:", "local.png", "image/png", ByteArray(4))
            val sources = mapOf(":first:" to source, ":second:" to source.copy(attachmentIndex = 1))
            val budgets = mutableListOf<Long>()
            val result =
                resolveConversationEmojiArtwork(
                    listOf(":first:", ":local:", ":second:"),
                    mapOf(known.shortcode to known),
                    sources,
                    10,
                ) { code, _, remaining ->
                    budgets += remaining
                    if (remaining < 4) throw AttachmentTooLargeToPresentException(remaining, 4)
                    LocalEmojiArtwork(code, "received.png", "image/png", ByteArray(4))
                }
            assertEquals(listOf(":first:", ":local:"), result.map { it.shortcode })
            assertEquals(listOf(6L, 2L), budgets)
        }

    /** Each send-side read is capped at the picker's preview limit, or at the smaller remaining budget. */
    @Test
    fun receivedReadsUseThePickerImageCap() =
        runTest {
            val budgets = mutableListOf<Long>()
            val sources = mapOf(":first:" to source, ":second:" to source.copy(attachmentIndex = 1))
            resolveConversationEmojiArtwork(listOf(":first:"), emptyMap(), sources) { code, _, limit ->
                budgets += limit
                LocalEmojiArtwork(code, "received.png", "image/png", ByteArray(1))
            }
            val both = listOf(":first:", ":second:")
            resolveConversationEmojiArtwork(both, emptyMap(), sources, 10, 6) { code, _, limit ->
                budgets += limit
                LocalEmojiArtwork(code, "received.png", "image/png", ByteArray(5))
            }
            assertEquals(listOf(CustomEmojiStore.MAX_BYTES.toLong(), 6L, 5L), budgets)
        }

    /** An unverifiable or oversized received original stays literal text instead of failing the send. */
    @Test
    fun unreadableReceivedOriginalsStayLiteral() =
        runTest {
            val known = LocalEmojiArtwork(":local:", "local.png", "image/png", ByteArray(1))
            val sources = mapOf(":forged:" to source, ":huge:" to source.copy(attachmentIndex = 1))
            val result =
                resolveConversationEmojiArtwork(
                    listOf(":forged:", ":local:", ":huge:"),
                    mapOf(known.shortcode to known),
                    sources,
                    maxImageBytes = 4,
                ) { code, _, _ ->
                    if (code == ":forged:") error("emoji plaintext hash mismatch")
                    LocalEmojiArtwork(code, "huge.png", "image/png", ByteArray(5))
                }
            assertEquals(listOf(":local:"), result.map { it.shortcode })
        }

    /** Only an offline device or a closed transport fails the lookup, so the send retries it on reconnection. */
    @Test
    fun onlyLostConnectivityFailsTheLookupForRetry() =
        runTest {
            val sources = mapOf(":remote:" to source)

            /** The failure a resolution with [offline] connectivity throws for [failure], or null when it resolved. */
            suspend fun outcome(
                failure: Exception,
                offline: Boolean,
            ): Throwable? {
                val codes = listOf(":remote:")
                val read = failingWith(failure)
                val offlineNow = { offline }
                return runCatching {
                    resolveConversationEmojiArtwork(codes, emptyMap(), sources, deviceOffline = offlineNow, read = read)
                }.exceptionOrNull()
            }

            val unresolved = UnknownHostException("Unable to resolve host")
            assertTrue(outcome(unresolved, offline = true) is EmojiArtworkConnectionLost)
            val closed = MarmotKitException.TransportClosed()
            assertTrue(outcome(IllegalStateException("upload", closed), offline = false) is EmojiArtworkConnectionLost)
        }

    /** Artwork that is too large or forged stays literal even offline, since a reconnection can never fix it. */
    @Test
    fun contentFailuresStayLiteralOffline() =
        runTest {
            val sources = mapOf(":huge:" to source, ":forged:" to source.copy(attachmentIndex = 1))
            val skipped = mutableListOf<Throwable>()
            val result =
                resolveConversationEmojiArtwork(
                    listOf(":huge:", ":forged:"),
                    emptyMap(),
                    sources,
                    deviceOffline = { true },
                    onUnreadable = { skipped += it },
                ) { code, _, limit ->
                    if (code == ":huge:") throw AttachmentTooLargeToPresentException(limit, limit + 1)
                    throw ReceivedEmojiMismatchException()
                }
            assertTrue(result.isEmpty())
            assertEquals(
                listOf(AttachmentTooLargeToPresentException::class.java, ReceivedEmojiMismatchException::class.java),
                skipped.map { it.javaClass },
            )
        }

    /** A typed lost connection is retryable after the send wraps it, whatever the underlying error text says. */
    @Test
    fun lostConnectionIsRetryableAtTheSendBoundary() {
        val lost = EmojiArtworkConnectionLost(UnknownHostException("Unable to resolve host"))
        assertTrue(isRetryableEmojiUploadFailure(EmojiUploadFailure(lost)))
        assertFalse(isRetryableEmojiUploadFailure(EmojiUploadFailure(UnknownHostException("Unable to resolve host"))))
        assertFalse(isRetryableEmojiUploadFailure(lost))
    }

    /** A slow or dead image host on a working connection leaves the code literal instead of holding the send. */
    @Test
    fun deadImageHostOnlineStaysLiteral() =
        runTest {
            val skipped = mutableListOf<Throwable>()
            val result =
                resolveConversationEmojiArtwork(
                    listOf(":remote:"),
                    emptyMap(),
                    mapOf(":remote:" to source),
                    deviceOffline = { false },
                    onUnreadable = { skipped += it },
                ) { _, _, _ -> throw IOException("connection timed out") }
            assertTrue(result.isEmpty())
            assertEquals(listOf(IOException::class.java), skipped.map { it.javaClass })
        }

    /** An exhausted budget skips the read entirely instead of requesting zero bytes. */
    @Test
    fun exhaustedBudgetSkipsTheRead() =
        runTest {
            val known = LocalEmojiArtwork(":local:", "local.png", "image/png", ByteArray(4))
            var reads = 0
            val result =
                resolveConversationEmojiArtwork(
                    listOf(":local:", ":remote:"),
                    mapOf(known.shortcode to known),
                    mapOf(":remote:" to source),
                    maxBytes = 4,
                ) { code, _, _ ->
                    reads++
                    LocalEmojiArtwork(code, "remote.png", "image/png", ByteArray(0))
                }
            assertEquals(listOf(":local:"), result.map { it.shortcode })
            assertEquals(0, reads)
        }

    /** A received-original reader that always fails with [failure]. */
    private fun failingWith(failure: Exception): ReceivedReader = { _, _, _ -> throw failure }

    /** Too many resolved emoji fail before any received original is read. */
    @Test
    fun tagOverflowDoesNotReadOriginals() =
        runTest {
            val sources = (0..Nip30Emoji.MAX_TAGS).associate { ":code$it:" to source }
            var reads = 0
            val result =
                runCatching {
                    resolveConversationEmojiArtwork(sources.keys.toList(), emptyMap(), sources) { _, _, _ ->
                        reads++
                        error("an oversized request must be rejected before this read")
                    }
                }
            assertTrue(result.exceptionOrNull() is EmojiSendLimitException)
            assertEquals(0, reads)
        }

    /** Duplicated tokens share a definition and unknown tokens remain literal text. */
    @Test
    fun unknownAndRepeatedCodesDoNotAddReads() =
        runTest {
            var reads = 0
            val result =
                resolveConversationEmojiArtwork(
                    listOf(":remote:", ":unknown:", ":remote:"),
                    emptyMap(),
                    mapOf(":remote:" to source),
                ) { code, _, _ ->
                    reads++
                    LocalEmojiArtwork(code, "remote.png", "image/png", byteArrayOf(1))
                }
            assertEquals(listOf(":remote:"), result.map { it.shortcode })
            assertEquals(1, reads)
        }
}
