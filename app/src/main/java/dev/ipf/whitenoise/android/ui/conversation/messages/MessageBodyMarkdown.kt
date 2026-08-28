package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.ui.parseMarkdownOrEmptyDocument
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

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

/**
 * Keeps the rendered document tied to the exact body currently on screen.
 *
 * Confirming an optimistic send can replace its locally parsed record with a
 * timeline record whose Markdown hydration has not arrived yet. In that state
 * [messageMarkdownDocumentForDisplayedBody] correctly refuses stale tokens,
 * but a long message opened through Read More must not regress to literal
 * Markdown. Parse the displayed body as a body-keyed fallback and let the
 * authoritative stored/edited document take over as soon as it is available.
 */
@Composable
internal fun rememberMessageMarkdownDocumentForDisplayedBody(
    messageIdHex: String,
    bodyText: String?,
    recordPlaintext: String,
    storedDocument: MarkdownDocumentFfi,
    overrideDocument: MarkdownDocumentFfi?,
    deleted: Boolean,
    persistedFailure: Boolean,
    fallbackParsingEnabled: Boolean,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
): MarkdownDocumentFfi? {
    val authoritativeDocument =
        messageMarkdownDocumentForDisplayedBody(
            bodyText = bodyText,
            recordPlaintext = recordPlaintext,
            storedDocument = storedDocument,
            overrideDocument = overrideDocument,
            deleted = deleted,
            persistedFailure = persistedFailure,
        )
    val fallbackSource =
        bodyText?.takeIf {
            authoritativeDocument == null &&
                fallbackParsingEnabled &&
                !deleted &&
                !persistedFailure &&
                it.isNotBlank()
        }
    var fallbackDocument by remember(messageIdHex, fallbackSource) {
        mutableStateOf<MarkdownDocumentFfi?>(null)
    }
    LaunchedEffect(messageIdHex, fallbackSource) {
        if (fallbackSource == null) {
            fallbackDocument = null
            return@LaunchedEffect
        }
        val parsed = parseMarkdownOrEmptyDocument(fallbackSource, parseMarkdown)
        coroutineContext.ensureActive()
        fallbackDocument = parsed.takeIf { it.blocks.isNotEmpty() }
    }
    return authoritativeDocument ?: fallbackDocument
}
