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
import kotlinx.coroutines.withContext

private data class NostrPreviewDocument(
    val document: MarkdownDocumentFfi?,
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
    var prepared by remember(card?.eventIdHex, card?.summary) { mutableStateOf<NostrPreviewDocument?>(null) }
    LaunchedEffect(card?.eventIdHex, card?.summary) {
        val loaded = card ?: return@LaunchedEffect
        val document =
            loaded.summary?.takeIf(String::isNotBlank)?.let { summary ->
                try {
                    withContext(dispatcher) { parseMarkdown(summary) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        prepared = NostrPreviewDocument(document)
    }
    val document = prepared?.document
    return when {
        card != null && prepared == null -> NostrEventCardState.Loaded(card.copy(summary = null))
        card != null && document != null && document.blocks.isNotEmpty() ->
            NostrEventCardState.Loaded(
                card.copy(summary = markdownDocumentToPreviewText(document, PREVIEW_TEXT_BUDGET, mentionDisplayName)),
            )
        else -> state
    }
}

private const val PREVIEW_TEXT_BUDGET = 420
