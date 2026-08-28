package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarkdownDocumentFfi

/**
 * The single empty Markdown AST every locally synthesized record shares.
 *
 * [MarkdownDocumentFfi] is a UniFFI data class whose `blankLinesBefore` field
 * is a `ByteArray`, and generated `equals` compares an array by identity. A
 * freshly allocated `ByteArray(0)` per projection therefore makes two
 * value-identical records unequal, which in turn makes the enclosing
 * [dev.ipf.whitenoise.android.state.ChatListItem] unequal and defeats Compose
 * skipping for every row the projection rebuilds. Sharing one instance keeps
 * repeated projections of unchanged rows structurally equal.
 *
 * MDK records are treated as immutable values across the app (rebuilt with
 * `copy`, never mutated in place), so a shared instance is safe to hand to any
 * number of records.
 */
internal val EMPTY_MARKDOWN_DOCUMENT: MarkdownDocumentFfi =
    MarkdownDocumentFfi(
        truncated = false,
        blocks = emptyList(),
        blankLinesBefore = ByteArray(0),
    )
