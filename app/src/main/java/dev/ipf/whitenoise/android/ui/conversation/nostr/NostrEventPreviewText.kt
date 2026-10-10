package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.ui.markdownDocumentToPreviewText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private data class NostrPreviewDocument(
    val document: MarkdownDocumentFfi?,
    val bodyImageUrls: List<String>,
)

/** Resolves profile names without briefly displaying stale or raw mention text. */
@Composable
internal fun rememberNostrEventPreviewState(
    state: NostrEventCardState,
    mentionDisplayName: (String) -> String?,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): NostrEventCardState {
    val card = (state as? NostrEventCardState.Loaded)?.card
    var prepared by remember(card?.eventIdHex, card?.summary, card?.readerBody) {
        mutableStateOf<NostrPreviewDocument?>(null)
    }
    LaunchedEffect(card?.eventIdHex, card?.summary, card?.readerBody) {
        val loaded = card ?: return@LaunchedEffect
        prepared =
            withContext(dispatcher) {
                val document = parsePreviewDocument(loaded.summary, parseMarkdown)
                val body = boundedNostrImageBody(loaded.readerBody)
                val bodyDocument =
                    if (body == loaded.summary) {
                        document
                    } else {
                        bodyImageParsePermits.withPermit { parsePreviewDocument(body, parseMarkdown) }
                    }
                NostrPreviewDocument(document, nostrEventImageCandidates(emptyList(), bodyDocument))
            }
    }
    val document = prepared?.document
    val imageUrls = remember(card?.imageUrls, prepared?.bodyImageUrls) {
        nostrEventImageCandidates(card?.imageUrls.orEmpty() + prepared?.bodyImageUrls.orEmpty(), null)
    }
    return when {
        card != null && prepared == null -> NostrEventCardState.Loaded(card.copy(summary = null, imageUrls = imageUrls))
        card != null && document != null && document.blocks.isNotEmpty() ->
            NostrEventCardState.Loaded(
                card.copy(
                    summary = markdownDocumentToPreviewText(document, PREVIEW_TEXT_BUDGET, mentionDisplayName),
                    imageUrls = imageUrls,
                ),
            )
        card != null -> NostrEventCardState.Loaded(card.copy(imageUrls = imageUrls))
        else -> state
    }
}

private const val PREVIEW_TEXT_BUDGET = 420

private suspend fun parsePreviewDocument(
    text: String?,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
): MarkdownDocumentFfi? =
    text?.takeIf(String::isNotBlank)?.let {
        try {
            parseMarkdown(it)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

private val bodyImageParsePermits = Semaphore(2)
