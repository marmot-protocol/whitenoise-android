package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Records held by message id; aliased so the helpers below fit the column limit. */
private typealias HeldRecords = Map<String, TimelineMessageRecordFfi>

/**
 * Parse [text] into the same Markdown AST the Rust core attaches to projected
 * records, for the state Android synthesizes locally (optimistic sends,
 * finished agent streams, chat-list previews). `parseMarkdown` is a blocking
 * FFI call, so it rides [WhiteNoiseAppState.marmotIo]'s IO hop. Any failure
 * degrades to an empty document, which renders as plain text.
 */
@Suppress("TooGenericExceptionCaught") // The FFI boundary can raise unchecked failures; cancellation is rethrown first.
internal suspend fun WhiteNoiseAppState.parseMarkdownOrEmpty(text: String): MarkdownDocumentFfi =
    try {
        marmotIo { parseMarkdown(text) }
    } catch (throwable: Throwable) {
        rethrowIfCancellation(throwable)
        EMPTY_MARKDOWN_DOCUMENT
    }

/** Re-parse only ordinary, visible text rows whose projected Markdown is absent. */
internal fun needsTimelineMarkdownHydration(record: TimelineMessageRecordFfi): Boolean =
    record.kind == CHAT_MESSAGE_KIND &&
        !record.deleted &&
        record.plaintext.isNotBlank() &&
        record.contentTokens.blocks.isEmpty()

internal fun TimelineMessageRecordFfi.withMarkdownTokens(document: MarkdownDocumentFfi) = copy(contentTokens = document)

/**
 * Tokens already parsed for the rows in [ids], keyed by message id.
 *
 * Records arrive from the window with empty tokens, and the window replacement that follows clears
 * the map they would otherwise have been read back from. Capturing them first is what stops a page
 * from re-parsing Markdown for rows whose text has not changed.
 */
internal fun HeldRecords.markdownTokensFor(ids: Collection<String>): Map<String, MarkdownDocumentFfi> =
    ids
        .mapNotNull { id ->
            this[id]?.contentTokens?.takeIf { document -> document.blocks.isNotEmpty() }?.let { id to it }
        }.toMap()

/**
 * The record with previously parsed tokens restored, when they still describe its text.
 *
 * Only an unchanged [TimelineMessageRecordFfi.plaintext] may reuse a document — the same rule
 * `hydrateTimelineMarkdown` applies — so an edited row is still re-parsed.
 */
internal fun TimelineMessageRecordFfi.withCarriedMarkdownTokens(
    captured: Map<String, MarkdownDocumentFfi>,
    previous: HeldRecords,
): TimelineMessageRecordFfi {
    val reusable =
        contentTokens.blocks.isEmpty() &&
            previous[messageIdHex]?.plaintext == plaintext
    val document = if (reusable) captured[messageIdHex] else null
    return document?.let(::withMarkdownTokens) ?: this
}

/** Publish local rows first, then lifecycle-bound Markdown enrichment. */
internal fun publishTimelineBeforeMarkdownHydration(
    scope: CoroutineScope,
    records: List<TimelineMessageRecordFfi>,
    publish: () -> Unit,
    hydrate: suspend (List<TimelineMessageRecordFfi>) -> List<TimelineMessageRecordFfi>,
    applyHydrated: (List<TimelineMessageRecordFfi>) -> Unit,
): Job? {
    publish()
    val pending = records.filter(::needsTimelineMarkdownHydration)
    if (pending.isEmpty()) return null
    return scope.launch { applyHydrated(hydrate(pending)) }
}

private const val CHAT_MESSAGE_KIND = 9uL
