package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi

// Which parts of a message only repeat an event its cards already show (#3317). Matching uses the typed Markdown
// tree and the shared event decoder, never a rescan of the message text.

/**
 * The stable identity of the public event [raw] points at, bare or with a `nostr:` prefix, so every alias of one
 * event maps to the same key. An HTTP(S) address never decodes, so a web link is never treated as a repeat.
 */
internal fun nostrEventStableId(raw: String): String? {
    val trimmed = raw.trim()
    val bare = if (trimmed.startsWith(NOSTR_SCHEME, ignoreCase = true)) trimmed.drop(NOSTR_SCHEME.length) else trimmed
    return NostrEventReferenceDecoder.eventReference(bare)?.stableId
}

/**
 * Whether [this] only repeats an event a card already represents, keyed by [represented] stable ids (#3317): a bare
 * or `nostr:` reference, an autolink to it, a link to it labelled only with the reference itself, or emphasis around
 * nothing but such a repeat. A meaningful label, an HTTP(S) link and a profile mention are never repeats.
 */
internal fun MarkdownInlineFfi.repeatsRepresentedEvent(represented: Set<String>): Boolean {
    if (represented.isEmpty()) return false
    return repeats(represented, depth = 0)
}

/**
 * Whether [this] holds a repeat of a represented event and nothing else but whitespace, line breaks and the
 * punctuation that wraps a reference, so removing the repeats leaves no text. Code, words and other references keep
 * it visible.
 */
internal fun List<MarkdownInlineFfi>.onlyRepeatsRepresentedEvents(represented: Set<String>): Boolean =
    any { it.repeatsRepresentedEvent(represented) } && all { it.repeatsRepresentedEvent(represented) || it.isFiller() }

/** Whether every block of [this] is a paragraph that only repeats represented events, so the cards say it all. */
internal fun MarkdownDocumentFfi.onlyRepeatsRepresentedEvents(represented: Set<String>): Boolean =
    blocks.isNotEmpty() &&
        blocks.all { it is MarkdownBlockFfi.Paragraph && it.inlines.onlyRepeatsRepresentedEvents(represented) }

/** The repeat check, bounded by [depth] against a peer-crafted deep emphasis tree. */
private fun MarkdownInlineFfi.repeats(
    represented: Set<String>,
    depth: Int,
): Boolean =
    when (this) {
        is MarkdownInlineFfi.NostrMention -> entity.isRepresentedEvent(represented)
        is MarkdownInlineFfi.NostrUri -> entity.isRepresentedEvent(represented)
        is MarkdownInlineFfi.Autolink -> nostrEventStableId(url) in represented
        is MarkdownInlineFfi.Link ->
            nostrEventStableId(dest) in represented &&
                children.all { child -> child.repeats(represented, depth + 1) || child.isLabelRepeat(represented) }
        is MarkdownInlineFfi.Emph -> children.onlyWraps(represented, depth)
        is MarkdownInlineFfi.Strong -> children.onlyWraps(represented, depth)
        is MarkdownInlineFfi.Strikethrough -> children.onlyWraps(represented, depth)
        else -> false
    }

/** Whether emphasis children are a repeat with nothing but whitespace around it. */
private fun List<MarkdownInlineFfi>.onlyWraps(
    represented: Set<String>,
    depth: Int,
): Boolean =
    depth < MAX_REPEAT_WRAPPER_DEPTH &&
        any { it.repeats(represented, depth + 1) } &&
        all { it.repeats(represented, depth + 1) || (it is MarkdownInlineFfi.Text && it.content.isBlank()) }

/** Whether a parsed public event entity points at one of the [represented] events. */
private fun MarkdownNostrEntityFfi.isRepresentedEvent(represented: Set<String>): Boolean {
    if (!hrp.isPublicEventHrp()) return false
    return nostrEventStableId(bech32) in represented
}

/** Whether a link label's text is blank or spells one of the [represented] references itself. */
private fun MarkdownInlineFfi.isLabelRepeat(represented: Set<String>): Boolean =
    this is MarkdownInlineFfi.Text && (content.isBlank() || nostrEventStableId(content) in represented)

/** Whether [this] is whitespace, a line break or the punctuation that wraps a reference. */
private fun MarkdownInlineFfi.isFiller(): Boolean =
    isSoftOrHardBreak() ||
        (this is MarkdownInlineFfi.Text && content.all { it.isWhitespace() || it in WRAPPING_PUNCTUATION })

/** Whether [this] is a soft or hard line break. */
internal fun MarkdownInlineFfi.isSoftOrHardBreak(): Boolean =
    when (this) {
        MarkdownInlineFfi.SoftBreak, MarkdownInlineFfi.HardBreak -> true
        else -> false
    }

private const val NOSTR_SCHEME = "nostr:"

// Brackets, quotes and separators that only wrap or trail a reference, so a paragraph of only these and repeats
// reads as empty. Expressive punctuation such as "!!!" or "??" is the author's own and keeps the paragraph.
private const val WRAPPING_PUNCTUATION = ".,;:()[]{}<>\"'«»“”‘’"
private const val MAX_REPEAT_WRAPPER_DEPTH = 8
