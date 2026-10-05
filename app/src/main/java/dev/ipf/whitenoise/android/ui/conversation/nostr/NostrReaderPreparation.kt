package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class NostrReaderPreparation(
    val document: MarkdownDocumentFfi? = null,
    val blocks: List<MarkdownBlockFfi> = emptyList(),
    val parsing: Boolean = true,
)

/** Prepares the complete body off the main thread and discards cancelled reader work. */
@Composable
internal fun rememberNostrReaderPreparation(
    card: NostrEventCardModel,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): NostrReaderPreparation {
    var prepared by remember(card.eventIdHex, card.readerBody) { mutableStateOf(NostrReaderPreparation()) }
    LaunchedEffect(card.eventIdHex, card.readerBody) {
        val document =
            try {
                withContext(dispatcher) { parseMarkdown(card.readerBody.orEmpty()) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        val blocks =
            withContext(dispatcher) {
                document?.takeIf(::nostrReaderCanFormat)?.let(::nostrReaderBlocks).orEmpty()
            }
        prepared = NostrReaderPreparation(document, blocks, parsing = false)
    }
    return prepared
}
