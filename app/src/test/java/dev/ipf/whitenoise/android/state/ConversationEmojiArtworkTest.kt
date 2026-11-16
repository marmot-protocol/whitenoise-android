package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.media.AttachmentTooLargeToPresentException
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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

    /** A lost connection fails the lookup, so the send retries it rather than dropping the artwork. */
    @Test
    fun lostConnectionFailsTheLookupForRetry() =
        runTest {
            val sources = mapOf(":remote:" to source)
            val failure =
                runCatching {
                    resolveConversationEmojiArtwork(listOf(":remote:"), emptyMap(), sources) { _, _, _ ->
                        throw IOException("connection reset")
                    }
                }.exceptionOrNull()
            assertTrue(failure is IOException)
        }

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
