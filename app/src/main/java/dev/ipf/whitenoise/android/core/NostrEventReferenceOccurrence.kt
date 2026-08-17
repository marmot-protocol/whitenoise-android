package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import java.net.URI

/**
 * Extract public-event pointers from the typed Markdown document. Message text
 * is never rescanned: only parser-recognized Nostr entities and HTTP(S) link
 * destinations are considered.
 */
internal data class NostrEventReferenceOccurrence(
    val reference: NostrEventReference,
    val authoredReference: String,
)

@Suppress("MaxLineLength")
internal fun nostrEventReferences(document: MarkdownDocumentFfi): List<NostrEventReferenceOccurrence> = NostrEventReferenceCollector().collect(document)

private class NostrEventReferenceCollector {
    val references = LinkedHashMap<String, NostrEventReferenceOccurrence>()

    fun collect(document: MarkdownDocumentFfi): List<NostrEventReferenceOccurrence> {
        walkBlocks(document.blocks)
        return references.values.toList()
    }

    private fun add(raw: String) {
        if (references.size >= MAX_EVENT_REFERENCES_PER_MESSAGE) return
        NostrProfileReference.eventReference(raw)?.let { reference ->
            references.putIfAbsent(
                reference.stableId,
                NostrEventReferenceOccurrence(reference, raw.trim()),
            )
        }
    }

    private fun addFromHttpDestination(destination: String) {
        val uri =
            destination
                .takeIf { it.length <= MAX_MARKDOWN_DESTINATION_LENGTH }
                ?.let { runCatching { URI(it.trim()) }.getOrNull() }
                ?.takeIf { it.isHttp() }
        if (uri != null) {
            addReferencesFrom(uri)
        }
    }

    private fun addReferencesFrom(uri: URI) {
        val searchable = listOfNotNull(uri.rawPath, uri.rawQuery, uri.rawFragment).joinToString("&")
        for (match in NIP19_EVENT_SEGMENT.findAll(searchable)) {
            add(match.value)
            if (references.size >= MAX_EVENT_REFERENCES_PER_MESSAGE) break
        }
    }

    private fun walkInlines(inlines: List<MarkdownInlineFfi>) {
        for (inline in inlines) {
            if (references.size >= MAX_EVENT_REFERENCES_PER_MESSAGE) break
            walkInline(inline)
        }
    }

    private fun walkInline(inline: MarkdownInlineFfi) {
        when (inline) {
            is MarkdownInlineFfi.NostrMention ->
                if (inline.entity.hrp.isPublicEventHrp()) add(inline.entity.bech32)
            is MarkdownInlineFfi.NostrUri ->
                if (inline.entity.hrp.isPublicEventHrp()) add(inline.entity.bech32)
            is MarkdownInlineFfi.Link -> {
                addFromHttpDestination(inline.dest)
                walkInlines(inline.children)
            }
            is MarkdownInlineFfi.Image -> {
                addFromHttpDestination(inline.dest)
                walkInlines(inline.alt)
            }
            is MarkdownInlineFfi.Autolink -> addFromHttpDestination(inline.url)
            is MarkdownInlineFfi.Emph -> walkInlines(inline.children)
            is MarkdownInlineFfi.Strong -> walkInlines(inline.children)
            is MarkdownInlineFfi.Strikethrough -> walkInlines(inline.children)
            is MarkdownInlineFfi.Text,
            is MarkdownInlineFfi.Code,
            is MarkdownInlineFfi.Math,
            MarkdownInlineFfi.SoftBreak,
            MarkdownInlineFfi.HardBreak,
            -> Unit
        }
    }

    private fun walkBlocks(blocks: List<MarkdownBlockFfi>) {
        for (block in blocks) {
            if (references.size >= MAX_EVENT_REFERENCES_PER_MESSAGE) break
            when (block) {
                is MarkdownBlockFfi.Paragraph -> walkInlines(block.inlines)
                is MarkdownBlockFfi.Heading -> walkInlines(block.inlines)
                is MarkdownBlockFfi.BlockQuote -> walkBlocks(block.blocks)
                is MarkdownBlockFfi.ListBlock -> block.items.forEach { walkBlocks(it.blocks) }
                is MarkdownBlockFfi.Table -> {
                    block.header.forEach { walkInlines(it.inlines) }
                    block.rows.flatten().forEach { walkInlines(it.inlines) }
                }
                is MarkdownBlockFfi.CodeBlock,
                is MarkdownBlockFfi.MathBlock,
                MarkdownBlockFfi.ThematicBreak,
                -> Unit
            }
        }
    }
}

private fun URI.isHttp(): Boolean =
    scheme?.equals("https", ignoreCase = true) == true ||
        scheme?.equals("http", ignoreCase = true) == true

private fun MarkdownNostrHrpFfi.isPublicEventHrp(): Boolean =
    this == MarkdownNostrHrpFfi.NOTE || this == MarkdownNostrHrpFfi.NEVENT || this == MarkdownNostrHrpFfi.NADDR

private const val MAX_MARKDOWN_DESTINATION_LENGTH = 8_192
private const val MAX_EVENT_REFERENCES_PER_MESSAGE = 3
private const val NIP19_BODY_CHARS = "ac-hj-np-z02-9"
private val NIP19_EVENT_SEGMENT =
    Regex(
        "(?i)(?<![$NIP19_BODY_CHARS])(?:note|nevent|naddr)1" +
            "[$NIP19_BODY_CHARS]+(?![$NIP19_BODY_CHARS])",
    )
