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

/**
 * Builds the single read-aloud script for an unread backlog: oldest first,
 * each message prefixed with its sender's name exactly when the speaker
 * changes from the previous message — a run of unattributed sentences from
 * three people is unfollowable, but re-announcing one sender per message is
 * noise. Messages are joined as their own sentences so the chunker treats
 * boundaries correctly.
 */
fun ttsAutoReadScript(entries: List<TtsSpeakableEntry>): String {
    val bounded = entries.take(TTS_AUTO_READ_MAX_MESSAGES)
    val parts = mutableListOf<String>()
    var previousSender: String? = null
    for (entry in bounded) {
        val text = entry.text.trim()
        if (text.isEmpty()) continue
        val changedSpeaker = !entry.senderKey.equals(previousSender, ignoreCase = true)
        parts +=
            if (changedSpeaker && entry.senderDisplayName.isNotBlank()) {
                "${entry.senderDisplayName}: $text"
            } else {
                text
            }
        previousSender = entry.senderKey
    }
    return parts.joinToString(separator = "\n")
}
