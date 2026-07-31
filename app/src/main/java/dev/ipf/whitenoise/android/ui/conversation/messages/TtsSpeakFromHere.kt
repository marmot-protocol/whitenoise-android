package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.audio.tts.TTS_AUTO_READ_MAX_MESSAGES
import dev.ipf.whitenoise.android.state.TimelineMessage

/** Selects the bounded timeline slice used by Speak aloud from here. */
internal fun ttsSpeakFromHereCandidates(
    timeline: List<TimelineMessage>,
    selected: AppMessageRecordFfi,
): List<AppMessageRecordFfi> {
    val startIndex = timeline.indexOfFirst { it.record.messageIdHex == selected.messageIdHex }
    if (startIndex < 0) return listOf(selected)
    return timeline
        .drop(startIndex)
        .take(TTS_AUTO_READ_MAX_MESSAGES * 2)
        .map(TimelineMessage::record)
}
