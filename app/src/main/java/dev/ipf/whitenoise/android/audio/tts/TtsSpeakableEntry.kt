package dev.ipf.whitenoise.android.audio.tts

/** One speakable backlog message, resolved for read-aloud scripting. */
data class TtsSpeakableEntry(
    val senderKey: String,
    val senderDisplayName: String,
    val text: String,
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
