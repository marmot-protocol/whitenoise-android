package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.AttachmentTooLargeToPresentException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.OutputStream

/** Pins the text reader's read to one byte past its preview limit and its over-limit state to too large. */
class TextAttachmentReaderBoundedReadTest {
    /** A file declaring more than the reader may hold is rejected before a single byte is copied. */
    @Test
    fun aFileDeclaredOverThePreviewLimitIsRejectedBeforeAnyCopy() =
        runTest {
            val source = StreamingPlaintext(declaredSize = 33L * 1024L * 1024L, streamedBytes = 0L)

            val failure = runCatching { readForTextReader(source) }.exceptionOrNull()

            assertTrue("expected a too-large failure, got $failure", failure is AttachmentTooLargeToPresentException)
            assertEquals("the oversized file was copied", 0L, source.copied)
            assertEquals(
                TextAttachmentReaderState.Unavailable(TextAttachmentUnavailableReason.TooLarge),
                textAttachmentLoadFailureState(checkNotNull(failure)),
            )
        }

    /** A source that understates its size stops streaming at the limit instead of filling the heap. */
    @Test
    fun aSourceThatUnderstatesItsSizeStopsAtTheLimit() =
        runTest {
            val source = StreamingPlaintext(declaredSize = 16L, streamedBytes = 4L * 1024L * 1024L)

            val failure = runCatching { readForTextReader(source) }.exceptionOrNull()

            assertTrue("expected a too-large failure, got $failure", failure is AttachmentTooLargeToPresentException)
            assertNull((failure as AttachmentTooLargeToPresentException).declaredBytes)
            assertTrue(
                "streamed ${source.copied} bytes past the reader limit",
                source.copied <= TEXT_ATTACHMENT_PREVIEW_MAX_BYTES + 1L + CHUNK_BYTES,
            )
            assertEquals(
                TextAttachmentReaderState.Unavailable(TextAttachmentUnavailableReason.TooLarge),
                textAttachmentLoadFailureState(failure),
            )
        }

    /** One byte past the limit is still read, so the decoder can show the file as too large rather than failed. */
    @Test
    fun aFileJustOverThePreviewLimitDecodesAsTooLarge() =
        runTest {
            val bytes = ByteArray(TEXT_ATTACHMENT_PREVIEW_MAX_BYTES + 1) { 'a'.code.toByte() }

            val read = readForTextReader(AttachmentPlaintext.Bytes(bytes))
            val state = loadTextAttachmentPreview(CANDIDATE, read) { error("plain text is not parsed") }

            assertEquals(bytes.size, read.size)
            assertEquals(TextAttachmentReaderState.Unavailable(TextAttachmentUnavailableReason.TooLarge), state)
        }

    /** Any other load failure remains a failed download the reader offers to retry. */
    @Test
    fun otherLoadFailuresStayRetryableDownloadFailures() {
        assertEquals(
            TextAttachmentReaderState.Unavailable(TextAttachmentUnavailableReason.DownloadFailed),
            textAttachmentLoadFailureState(IOException("relay unavailable")),
        )
    }

    /** Declares [declaredSize] and streams [streamedBytes] in fixed chunks, counting what was actually copied. */
    private class StreamingPlaintext(
        private val declaredSize: Long,
        private val streamedBytes: Long,
    ) : AttachmentPlaintext {
        var copied = 0L
            private set

        override val size: Long
            get() = declaredSize

        /** Writes chunks until [streamedBytes] or until the sink refuses one. */
        override fun copyTo(output: OutputStream) {
            val chunk = ByteArray(CHUNK_BYTES) { 'b'.code.toByte() }
            while (copied < streamedBytes) {
                val length = minOf(CHUNK_BYTES.toLong(), streamedBytes - copied).toInt()
                output.write(chunk, 0, length)
                copied += length
            }
        }

        /** The fixture owns no external resource. */
        override fun close() = Unit
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
        val CANDIDATE = TextAttachmentCandidate("notes.txt", "text/plain", TextAttachmentFormat.PlainText)
    }
}
