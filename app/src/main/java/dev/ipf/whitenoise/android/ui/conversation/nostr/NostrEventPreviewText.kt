package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.ui.markdownDocumentToPreviewText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves profile names without briefly displaying stale or raw mention text. */
@Composable
internal fun rememberNostrEventPreviewState(
    state: NostrEventCardState,
    mentionDisplayName: (String) -> String?,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): NostrEventCardState {
    val loadedState = state as? NostrEventCardState.Loaded
    val card = loadedState?.card
    // A read that already prepared this card's text is used on the first frame, with no second parse.
    val carried = loadedState?.preview?.takeIf { card != null && it.describes(card) }
    var parsed by remember(card?.eventIdHex, card?.summary, card?.readerBody) {
        mutableStateOf<NostrEventPreparedPreview?>(null)
    }
    LaunchedEffect(card?.eventIdHex, card?.summary, card?.readerBody, carried == null) {
        val loaded = card ?: return@LaunchedEffect
        if (carried != null) return@LaunchedEffect
        parsed = withContext(dispatcher) { prepareNostrEventPreview(loaded, parseMarkdown) }
    }
    val prepared = carried ?: parsed
    val document = prepared?.document
    val media =
        remember(card?.imageUrls, card?.mediaUrl, prepared?.media) {
            nostrEventMediaCandidates(
                card?.imageUrls.orEmpty() + prepared?.media?.imageUrls.orEmpty(),
                listOfNotNull(card?.mediaUrl) + prepared?.media?.videoUrls.orEmpty(),
                null,
            )
        }
    val selectedVideo = media.videoUrls.firstOrNull()
    val selectedMimeType = card?.mediaMimeType?.takeIf { selectedVideo == card.mediaUrl }
    return when {
        card != null && prepared == null ->
            NostrEventCardState.Loaded(
                card.copy(
                    summary = null,
                    imageUrls = media.imageUrls,
                    mediaUrl = selectedVideo,
                    mediaMimeType = selectedMimeType,
                ),
            )
        card != null && document != null && document.blocks.isNotEmpty() ->
            NostrEventCardState.Loaded(
                card.copy(
                    summary = markdownDocumentToPreviewText(document, PREVIEW_TEXT_BUDGET, mentionDisplayName),
                    imageUrls = media.imageUrls,
                    mediaUrl = selectedVideo,
                    mediaMimeType = selectedMimeType,
                ),
            )
        card != null ->
            NostrEventCardState.Loaded(
                card.copy(imageUrls = media.imageUrls, mediaUrl = selectedVideo, mediaMimeType = selectedMimeType),
            )
        else -> state
    }
}

private const val PREVIEW_TEXT_BUDGET = 420
