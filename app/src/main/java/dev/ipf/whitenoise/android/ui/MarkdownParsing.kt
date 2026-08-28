package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import kotlinx.coroutines.CancellationException

internal suspend fun parseMarkdownOrEmptyDocument(
    text: String,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
): MarkdownDocumentFfi =
    try {
        parseMarkdown(text)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        EMPTY_MARKDOWN_DOCUMENT
    }
