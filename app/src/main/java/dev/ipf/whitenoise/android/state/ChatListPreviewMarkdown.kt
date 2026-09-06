package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.core.MessageProjector

/**
 * The last-message text a chat row should run through the markdown parser,
 * or null when the row's preview line will show fallback copy instead of
 * the message body. Mirrors [ChatListItem.projectedPreviewText]'s generic
 * message-body arm exactly: a non-deleted row whose plaintext is non-blank
 * and whose kind is not one of the special-cased arms is rendered verbatim,
 * so its body — and only its body — may be parsed into preview tokens.
 * Edit (1009), agent-stream-start (1200), and group-system (1210) rows —
 * plus deleted/blank rows — surface derived copy, so their payloads must
 * never be parsed into preview tokens and styled in their place (issue #577).
 * Body kinds beyond plain chat (kind-1 legacy notes, kind-1209 agent-stream
 * finals, and any future body kind) still display their plaintext via
 * `projectedPreviewText`, so they keep markdown/mention/code rendering here.
 * Delegating the kind test to [MessageProjector.rendersRawBodyPreview] ties
 * this parse gate to the same plaintext `projectedPreviewText` would surface.
 */
internal fun chatRowPreviewMarkdownSource(row: ChatListRowFfi): String? {
    val preview = row.lastMessage ?: return null
    return preview.plaintext.takeIf {
        !preview.deleted && MessageProjector.rendersRawBodyPreview(preview.kind) && it.isNotBlank()
    }
}

/**
 * Selects the Markdown document that describes the body a chat row will display.
 * MDK's document is available on the first projection; Android's exact-text cache
 * remains a fallback for optimistic or legacy rows whose projected AST is empty.
 */
internal fun chatRowPreviewTokens(
    row: ChatListRowFfi,
    cachedTokensByText: Map<String, MarkdownDocumentFfi> = emptyMap(),
): MarkdownDocumentFfi? {
    val source = chatRowPreviewMarkdownSource(row) ?: return null
    return row.lastMessage
        ?.contentTokens
        ?.takeIf { it.blocks.isNotEmpty() }
        ?: cachedTokensByText[source]
}

/** Returns the exact preview source that still needs Android's parser fallback. */
internal fun chatRowPreviewMarkdownFallbackSource(row: ChatListRowFfi): String? =
    chatRowPreviewMarkdownSource(row)
        ?.takeIf {
            row.lastMessage
                ?.contentTokens
                ?.blocks
                ?.isEmpty() == true
        }
