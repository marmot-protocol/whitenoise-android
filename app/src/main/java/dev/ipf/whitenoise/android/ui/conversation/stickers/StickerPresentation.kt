package dev.ipf.whitenoise.android.ui.conversation.stickers

import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.ui.isEmojiPresentation
import java.net.URLEncoder
import java.text.BreakIterator

internal object StickerMetrics {
    const val MESSAGE_SIDE_DP = 150
    const val GRID_MINIMUM_DP = 76
    const val PACK_TAB_SIDE_DP = 36

    /**
     * Sharp for a timeline sticker on common phone densities (about 2.6x), and small enough that a whole large pack
     * stays decoded while the grid scrolls.
     */
    const val DECODE_EDGE_PX = 400
}

/** Presentation rules for peer-controlled sticker and pack text. */
internal object StickerPresentation {
    private const val MAX_LABEL_GRAPHEMES = 4
    private const val MAX_ALT_CHARS = 120
    private const val MAX_TITLE_CHARS = 80
    private const val MAX_DESCRIPTION_CHARS = 500
    private const val MAX_LOOKUP_BYTES = 2_048
    private const val SHARE_LINK_BASE = "https://sonarprivacy.xyz/stickers?a="

    /**
     * A sticker replaces the bubble only when its kind-9 carries nothing else.
     * MDK reads the sticker tag independently of the content, so a caption,
     * reply quote or attachment beside it must still show.
     */
    fun standsAlone(
        plaintext: String,
        hasReply: Boolean,
        hasMedia: Boolean,
    ): Boolean = !hasReply && !hasMedia && plaintext.isBlank()

    /**
     * Whether a sticker in the timeline loads without a tap. Loading contacts the
     * pack author's server, so a received sticker loads by itself only from a pack
     * the person installed, and only while image auto-download allows it. Their own
     * stickers were already loaded to send them. Null while the installed packs
     * ([packInstalled] null) are still being read and they would decide it.
     */
    fun loadsAutomatically(
        isFromMe: Boolean,
        packInstalled: Boolean?,
        autoDownloadImages: Boolean,
    ): Boolean? =
        when {
            isFromMe -> true
            !autoDownloadImages -> false
            else -> packInstalled
        }

    /** The pack's emoji for this sticker when it is a short emoji run, else null. */
    fun labelEmoji(emoji: String?): String? =
        clean(emoji)?.takeIf { text ->
            graphemeCount(text) <= MAX_LABEL_GRAPHEMES && text.codePoints().anyMatch { it.isEmojiPresentation() }
        }

    /** The sticker's alt text, sanitized and bounded, or null when it has none. */
    fun accessibleAlt(alt: String?): String? = bounded(alt, MAX_ALT_CHARS)

    /** A pasted link, coordinate or `naddr`, trimmed and bounded; MDK parses and validates it. */
    fun lookupInput(raw: String): String? {
        val input = raw.trim()
        return input.takeIf { it.isNotEmpty() && it.toByteArray().size <= MAX_LOOKUP_BYTES }
    }

    /** A link to [pack] that opens on the web and that MDK reads back when pasted into "Add by link". */
    fun shareLink(pack: StickerPackFfi): String = "$SHARE_LINK_BASE${URLEncoder.encode(pack.coordinate, "UTF-8")}"

    /** The pack's title, or null when it has none and the caller shows the generic name. */
    fun title(pack: StickerPackFfi): String? = bounded(pack.title, MAX_TITLE_CHARS)

    fun description(pack: StickerPackFfi): String? = bounded(pack.description, MAX_DESCRIPTION_CHARS)

    sealed interface DiscoveryState {
        data object Loading : DiscoveryState

        data object Packs : DiscoveryState

        /** The relays answered and hold no packs. */
        data object NoneOnRelays : DiscoveryState

        data object NoMatches : DiscoveryState

        /** Relays couldn't be reached and nothing is cached to show. */
        data object Unreachable : DiscoveryState
    }

    /**
     * What Discover shows. Cached packs stay listed when a refresh fails;
     * a failed refresh never reads as "there are none".
     */
    fun discoveryState(
        isLoading: Boolean,
        packCount: Int,
        search: String,
        syncFailed: Boolean,
    ): DiscoveryState =
        when {
            packCount > 0 -> DiscoveryState.Packs
            isLoading -> DiscoveryState.Loading
            syncFailed -> DiscoveryState.Unreachable
            search.isNotBlank() -> DiscoveryState.NoMatches
            else -> DiscoveryState.NoneOnRelays
        }

    private fun clean(raw: String?): String? = ProfileSanitizer.messageBody(raw.orEmpty()).trim().ifEmpty { null }

    private fun graphemeCount(text: String): Int {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    /** [raw] sanitized and cut to at most [max] chars without splitting a surrogate pair. */
    private fun bounded(
        raw: String?,
        max: Int,
    ): String? {
        val text = clean(raw) ?: return null
        val end = if (text.length > max && Character.isHighSurrogate(text[max - 1])) max - 1 else max
        return text.take(end)
    }
}
