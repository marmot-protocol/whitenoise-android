package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.MarkdownDocumentFfi

/**
 * Returns only the Markdown document that belongs to [bodyText]. An edited
 * message supplies [overrideDocument]; while that parse is pending or empty,
 * the current edited body must stay plain rather than reusing the original
 * message's stale document.
 */
internal fun messageMarkdownDocumentForDisplayedBody(
    bodyText: String?,
    recordPlaintext: String,
    storedDocument: MarkdownDocumentFfi,
    overrideDocument: MarkdownDocumentFfi?,
    deleted: Boolean,
    persistedFailure: Boolean,
): MarkdownDocumentFfi? =
    (overrideDocument ?: storedDocument).takeIf { document ->
        bodyText != null &&
            !deleted &&
            !persistedFailure &&
            document.blocks.isNotEmpty() &&
            (bodyText == recordPlaintext || overrideDocument != null)
    }
