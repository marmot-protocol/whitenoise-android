package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi

/** Collects distinct profile mentions from only the renderer-visible inline tree. */
internal fun markdownInlineMentionBech32s(inlines: List<MarkdownInlineFfi>): Set<String> =
    mutableSetOf<String>()
        .also { collectReferenceBech32s(inlines, it, depth = 0, includeProfileUris = false) }

/** Walks bounded inline containers without constructing any styled text. */
private fun collectReferenceBech32s(
    inlines: List<MarkdownInlineFfi>,
    out: MutableSet<String>,
    depth: Int,
    includeProfileUris: Boolean,
) {
    if (markdownInlineDepthExceeded(depth)) return
    markdownVisibleSiblings(inlines).forEach { inline ->
        when (inline) {
            is MarkdownInlineFfi.NostrMention ->
                if (!includeProfileUris || inline.entity.isProfileReference()) out += inline.entity.bech32
            is MarkdownInlineFfi.NostrUri ->
                if (includeProfileUris && inline.entity.isProfileReference()) out += inline.entity.bech32
            is MarkdownInlineFfi.Emph -> collectReferenceBech32s(inline.children, out, depth + 1, includeProfileUris)
            is MarkdownInlineFfi.Strong -> collectReferenceBech32s(inline.children, out, depth + 1, includeProfileUris)
            is MarkdownInlineFfi.Strikethrough -> collectReferenceBech32s(inline.children, out, depth + 1, includeProfileUris)
            is MarkdownInlineFfi.Link -> collectReferenceBech32s(inline.children, out, depth + 1, includeProfileUris)
            is MarkdownInlineFfi.Image -> collectReferenceBech32s(inline.alt, out, depth + 1, includeProfileUris)
            else -> Unit
        }
    }
}

/** Shares block, list, and table-area limits with the visible text projection. */
private fun collectBlockReferenceBech32s(
    blocks: List<MarkdownBlockFfi>,
    out: MutableSet<String>,
    depth: Int,
    includeProfileUris: Boolean,
) {
    if (markdownDepthExceeded(depth)) return
    markdownVisibleSiblings(blocks).forEach { block ->
        when (block) {
            is MarkdownBlockFfi.Paragraph -> collectReferenceBech32s(block.inlines, out, depth = 0, includeProfileUris = includeProfileUris)
            is MarkdownBlockFfi.Heading -> collectReferenceBech32s(block.inlines, out, depth = 0, includeProfileUris = includeProfileUris)
            is MarkdownBlockFfi.BlockQuote -> collectBlockReferenceBech32s(block.blocks, out, depth + 1, includeProfileUris)
            is MarkdownBlockFfi.ListBlock ->
                markdownVisibleSiblings(block.items).forEach { collectBlockReferenceBech32s(it.blocks, out, depth + 1, includeProfileUris) }
            is MarkdownBlockFfi.Details ->
                if (includeProfileUris) {
                    collectReferenceBech32s(block.summary, out, depth = 0, includeProfileUris = true)
                    collectBlockReferenceBech32s(block.body, out, depth + 1, includeProfileUris = true)
                }
            is MarkdownBlockFfi.Table -> {
                val visibleTable = markdownVisibleTable(block.header, block.rows)
                visibleTable.header.cells.forEach { cell -> collectReferenceBech32s(cell.inlines, out, depth = 0, includeProfileUris = includeProfileUris) }
                visibleTable.rows.forEach { row ->
                    row.cells.forEach { cell -> collectReferenceBech32s(cell.inlines, out, depth = 0, includeProfileUris = includeProfileUris) }
                }
            }
            else -> Unit
        }
    }
}

/** Collects each visible mention once before local name resolution. */
internal fun markdownDocumentMentionBech32s(document: MarkdownDocumentFfi): Set<String> =
    mutableSetOf<String>()
        .also { collectBlockReferenceBech32s(document.blocks, it, depth = 0, includeProfileUris = false) }

/** Includes parsed profile URIs for display hydration without broadening semantic mention detection. */
internal fun markdownInlineProfileReferenceBech32s(inlines: List<MarkdownInlineFfi>): Set<String> =
    mutableSetOf<String>()
        .also { collectReferenceBech32s(inlines, it, depth = 0, includeProfileUris = true) }

/** Shares renderer visibility limits and deduplicates profile name requests for one projection. */
internal fun markdownDocumentProfileReferenceBech32s(document: MarkdownDocumentFfi): Set<String> =
    mutableSetOf<String>()
        .also { collectBlockReferenceBech32s(document.blocks, it, depth = 0, includeProfileUris = true) }

/** Only native-parsed public-key entities can resolve to an in-app profile. */
internal fun MarkdownNostrEntityFfi.isProfileReference(): Boolean =
    hrp == MarkdownNostrHrpFfi.NPUB || hrp == MarkdownNostrHrpFfi.NPROFILE
