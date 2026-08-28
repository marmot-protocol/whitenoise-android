package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MessageBodyMarkdownTest {
    @Test
    fun originalBodyUsesItsStoredDocument() {
        val stored = document("original")

        assertSame(
            stored,
            messageMarkdownDocumentForDisplayedBody(
                bodyText = "original",
                recordPlaintext = "original",
                storedDocument = stored,
                overrideDocument = null,
                deleted = false,
                persistedFailure = false,
            ),
        )
    }

    @Test
    fun editedBodyUsesOnlyItsParsedOverride() {
        val edited = document("edited")

        assertSame(
            edited,
            messageMarkdownDocumentForDisplayedBody(
                bodyText = "edited",
                recordPlaintext = "original",
                storedDocument = document("original"),
                overrideDocument = edited,
                deleted = false,
                persistedFailure = false,
            ),
        )
    }

    @Test
    fun editedBodyNeverReusesOriginalDocumentWhileItsParseIsPending() {
        assertNull(
            messageMarkdownDocumentForDisplayedBody(
                bodyText = "edited",
                recordPlaintext = "original",
                storedDocument = document("original"),
                overrideDocument = null,
                deleted = false,
                persistedFailure = false,
            ),
        )
    }

    @Test
    fun emptyEditedParseFallsBackToCurrentPlainBody() {
        assertNull(
            messageMarkdownDocumentForDisplayedBody(
                bodyText = "edited",
                recordPlaintext = "original",
                storedDocument = document("original"),
                overrideDocument = emptyDocument(),
                deleted = false,
                persistedFailure = false,
            ),
        )
    }

    @Test
    fun tombstonesNeverExposeMessageMarkdown() {
        val stored = document("original")

        assertNull(
            messageMarkdownDocumentForDisplayedBody(
                bodyText = "deleted",
                recordPlaintext = "original",
                storedDocument = stored,
                overrideDocument = null,
                deleted = true,
                persistedFailure = false,
            ),
        )
        assertNull(
            messageMarkdownDocumentForDisplayedBody(
                bodyText = "failed",
                recordPlaintext = "original",
                storedDocument = stored,
                overrideDocument = null,
                deleted = false,
                persistedFailure = true,
            ),
        )
    }

    private fun document(text: String) =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(text)))),
            blankLinesBefore = byteArrayOf(),
        )

    private fun emptyDocument() =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = emptyList(),
            blankLinesBefore = byteArrayOf(),
        )
}
