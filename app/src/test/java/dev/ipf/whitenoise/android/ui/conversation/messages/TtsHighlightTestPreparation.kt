package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsSpokenTextSpan
import dev.ipf.whitenoise.android.audio.tts.TtsTextRange
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import dev.ipf.whitenoise.android.audio.tts.prepareSpeech
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedSpeechMessage
import dev.ipf.whitenoise.android.audio.tts.speech.SpeechContext
import dev.ipf.whitenoise.android.ui.SpeakableTextProjection
import java.util.Locale

internal fun preparedHighlightSpeech(
    projection: SpeakableTextProjection,
    locale: Locale = Locale.US,
    messageIdHex: String = "",
): PreparedSpeechMessage =
    requireNotNull(
        TtsSpeakableEntry(
            senderKey = "",
            senderDisplayName = "",
            text = projection.text,
            messageIdHex = messageIdHex,
            projectionId = projection.projectionId,
            spokenTextSpans =
                projection.spans
                    .filter {
                        it.spokenEnd > it.spokenStart &&
                            it.visibleEnd > it.visibleStart &&
                            it.spokenEnd - it.spokenStart == it.visibleEnd - it.visibleStart
                    }.map { span ->
                        TtsSpokenTextSpan(
                            TtsTextRange(span.spokenStart, span.spokenEnd),
                            TtsVisibleTextSpan(span.leafId, span.visibleStart, span.visibleEnd),
                        )
                    },
            speechRoles = projection.speechRoles,
            visibleLeaves = projection.visibleLeaves,
        ).prepareSpeech(SpeechContext(locale)),
    )
