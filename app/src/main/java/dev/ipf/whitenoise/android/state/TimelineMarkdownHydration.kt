package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Parse [text] into the same Markdown AST the Rust core attaches to projected
 * records, for the state Android synthesizes locally (optimistic sends,
 * finished agent streams, chat-list previews). `parseMarkdown` is a blocking
 * FFI call, so it rides [WhiteNoiseAppState.marmotIo]'s IO hop. Any failure
 * degrades to an empty document, which renders as plain text.
 */
internal suspend fun WhiteNoiseAppState.parseMarkdownOrEmpty(text: String): MarkdownDocumentFfi =
    try {
        marmotIo { parseMarkdown(text) }
    } catch (throwable: Throwable) {
        rethrowIfCancellation(throwable)
        MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0))
    }

/** Re-parse only ordinary, visible text rows whose projected Markdown is absent. */
internal fun needsTimelineMarkdownHydration(record: TimelineMessageRecordFfi): Boolean =
    record.kind == 9uL &&
        !record.deleted &&
        record.plaintext.isNotBlank() &&
        record.contentTokens.blocks.isEmpty()

internal fun TimelineMessageRecordFfi.withMarkdownTokens(document: MarkdownDocumentFfi) = copy(contentTokens = document)

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
