package dev.ipf.whitenoise.android.audio.tts

/** Half-open UTF-16 range. Android TTS callbacks use the same coordinate space. */
data class TtsTextRange(
    val start: Int,
    val end: Int,
) {
    init {
        require(start >= 0 && end >= start) { "text ranges must be ordered and non-negative" }
    }
}

/** Stable coordinate inside one visible rendered-text leaf. */
data class TtsVisibleTextSpan(
    val leafId: String,
    val start: Int,
    val end: Int,
) {
    init {
        require(leafId.isNotEmpty()) { "visible leaf ids must not be empty" }
        require(start >= 0 && end > start) { "visible spans must be non-empty and ordered" }
    }
}

/** Reversible mapping from submitted speech text to rendered visible text. */
data class TtsSpokenTextSpan(
    val spoken: TtsTextRange,
    val visible: TtsVisibleTextSpan,
) {
    init {
        require(spoken.end > spoken.start) { "spoken spans must be non-empty" }
        require(spoken.end - spoken.start == visible.end - visible.start) {
            "spoken and visible spans must cover the same UTF-16 length"
        }
    }
}

/**
 * Stable visible passage for the active queue position. An empty [visibleWord]
 * is the deterministic sentence fallback; a non-empty list identifies one
 * complete visible word, even when formatting splits it across several leaves.
 */
data class TtsPassage(
    val messageIdHex: String,
    val sentenceIndex: Int,
    /** Stable identity of the rendered projection these coordinates address. */
    val projectionId: String = "",
    /** Canonical conversation position used to remount a paged-out row. */
    val timelineAt: ULong = 0uL,
    val visibleWord: List<TtsVisibleTextSpan> = emptyList(),
)

/** One speakable backlog message, resolved for read-aloud scripting. */
data class TtsSpeakableEntry(
    val senderKey: String,
    val senderDisplayName: String,
    val text: String,
    // Stable conversation identity, empty for ad-hoc speech (previews, tests).
    val messageIdHex: String = "",
    // Timeline position of the source record, 0 for ad-hoc speech. Anchor
    // recovery pages toward this in either direction after the loaded window
    // drifted away from the queue.
    val timelineAt: ULong = 0uL,
    // Global offsets into [text]. Projection punctuation and removed URLs have
    // no mapping and therefore do not appear here.
    val spokenTextSpans: List<TtsSpokenTextSpan> = emptyList(),
    /** Changes whenever visible-leaf coordinates for this projection change. */
    val projectionId: String = "",
)

// A hazard bound, not a feature knob: an inflated unread count would anchor
// the backlog at ancient history and read it aloud — a louder failure than
// the misplaced scroll the same corruption already causes.
const val TTS_AUTO_READ_MAX_MESSAGES = 50

/** Filters blank entries and applies the auto-read ceiling. */
fun boundedSpeakableEntries(entries: List<TtsSpeakableEntry>): List<TtsSpeakableEntry> =
    entries
        .filter { it.text.isNotBlank() }
        .take(TTS_AUTO_READ_MAX_MESSAGES)
