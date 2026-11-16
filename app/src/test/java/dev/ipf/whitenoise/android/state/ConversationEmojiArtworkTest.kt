package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.media.AttachmentTooLargeToPresentException
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Received originals obey a single tag and retained-byte budget before another original is copied. */
class ConversationEmojiArtworkTest {
    private val source = ConversationEmojiSource("message", 0, reference(0))

    /** Existing artwork is reserved before the first received original, including when it appears later in text. */
    @Test
    fun receivedReadsUseTheRemainingAggregateBudget() =
        runTest {
            val known = LocalEmojiArtwork(":local:", "local.png", "image/png", ByteArray(4))
            val sources = mapOf(":first:" to source, ":second:" to source.copy(attachmentIndex = 1))
            val budgets = mutableListOf<Long>()
            var copied = 0
            val result =
                runCatching {
                    resolveConversationEmojiArtwork(
                        listOf(":first:", ":local:", ":second:"),
                        mapOf(known.shortcode to known),
                        sources,
                        10,
                    ) { code, _, remaining ->
                        budgets += remaining
                        if (remaining < 4) throw AttachmentTooLargeToPresentException(remaining, 4)
                        copied++
                        LocalEmojiArtwork(code, "received.png", "image/png", ByteArray(4))
                    }
                }
            assertTrue(result.exceptionOrNull() is EmojiSendLimitException)
            assertEquals(listOf(6L, 2L), budgets)
            assertEquals(1, copied)
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
