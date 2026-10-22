package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.marmotkit.MarkdownDocumentFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The parsed preview text and body media of one loaded card, prepared off the main thread. A read carries it inside
 * [NostrEventCardState.Loaded] so a card restored from MDK's projection shows its complete text on its first frame
 * instead of a summary-less pass followed by a remeasure (#2238).
 */
internal data class NostrEventPreparedPreview(
    val document: MarkdownDocumentFfi?,
    val media: NostrEventMediaCandidates,
    /** The summary and reader body this preparation describes, so a changed card never reuses it. */
    val summary: String?,
    val readerBody: String?,
)

/** True when this preparation was made from [card]'s current summary and reader body. */
internal fun NostrEventPreparedPreview.describes(card: NostrEventCardModel): Boolean =
    summary == card.summary &&
        readerBody == card.readerBody

/** Parses [card]'s preview text and body media with [parseMarkdown]; parse failures degrade to no document. */
internal suspend fun prepareNostrEventPreview(
    card: NostrEventCardModel,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
): NostrEventPreparedPreview {
    val document = parsePreviewDocument(card.summary, parseMarkdown)
    val body = boundedNostrImageBody(card.readerBody)
    val bodyDocument =
        if (body == card.summary) {
            document
        } else {
            bodyImageParsePermits.withPermit { parsePreviewDocument(body, parseMarkdown) }
        }
    return NostrEventPreparedPreview(
        document = document,
        media = nostrEventMediaCandidates(emptyList(), emptyList(), bodyDocument),
        summary = card.summary,
        readerBody = card.readerBody,
    )
}

/** Parses non-blank [text], degrading any parse failure to no document. */
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
