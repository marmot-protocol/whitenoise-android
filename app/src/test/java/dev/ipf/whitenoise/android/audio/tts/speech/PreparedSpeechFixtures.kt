package dev.ipf.whitenoise.android.audio.tts.speech

import dev.ipf.whitenoise.android.audio.tts.TtsTextRange
import org.junit.Assert.assertTrue
import java.util.Locale

internal const val PRIMARY_LEAF_ID = "b0/n0"
internal const val SECOND_LEAF_ID = "b0/n1"

internal fun proseRun(
    text: String,
    leafId: String = PRIMARY_LEAF_ID,
): SpeechSourceRun = SpeechSourceRun(leafId = leafId, text = text, role = SpeechRole.Prose)

internal fun sourceRun(
    text: String,
    role: SpeechRole,
    leafId: String = PRIMARY_LEAF_ID,
    languageTag: String? = null,
): SpeechSourceRun = SpeechSourceRun(leafId = leafId, text = text, role = role, languageTag = languageTag)

internal fun preparationRequest(
    vararg runs: SpeechSourceRun,
    context: SpeechContext = SpeechContext(voiceLocale = Locale.US),
    messageIdHex: String = "m1",
    revisionId: String = "rev-1",
    senderAnnouncement: String? = null,
    maxOutputChars: Int = 32_000,
    cursor: SpeechPreparationCursor? = null,
): SpeechPreparationRequest =
    SpeechPreparationRequest(
        messageIdHex = messageIdHex,
        sourceRevisionId = revisionId,
        runs = runs.toList(),
        context = context,
        senderAnnouncement = senderAnnouncement,
        maxOutputChars = maxOutputChars,
        cursor = cursor,
    )

internal fun prepareMessage(
    vararg runs: SpeechSourceRun,
    context: SpeechContext = SpeechContext(voiceLocale = Locale.US),
    messageIdHex: String = "m1",
    revisionId: String = "rev-1",
    senderAnnouncement: String? = null,
): PreparedSpeechMessage {
    val result =
        SpeechPreparation.prepare(
            preparationRequest(
                *runs,
                context = context,
                messageIdHex = messageIdHex,
                revisionId = revisionId,
                senderAnnouncement = senderAnnouncement,
            ),
        )
    assertTrue("preparation must complete, got $result", result is SpeechPreparationResult.Complete)
    return (result as SpeechPreparationResult.Complete).message
}

/** The one sentence that speaks [fragment], failing loudly when it is ambiguous. */
internal fun PreparedSpeechMessage.sentenceSpeaking(fragment: String): PreparedSentence =
    sentences.singleOrNull { it.utterance.engineText.contains(fragment) }
        ?: error("expected exactly one sentence speaking '$fragment' in ${sentences.map { it.utterance.engineText }}")

internal fun PreparedUtterance.spokenRangeOf(fragment: String): TtsTextRange {
    val start = engineText.indexOf(fragment)
    check(start >= 0) { "'$fragment' is not spoken in '$engineText'" }
    check(engineText.indexOf(fragment, start + 1) < 0) { "'$fragment' is spoken more than once in '$engineText'" }
    return TtsTextRange(start, start + fragment.length)
}

internal fun PreparedUtterance.spansFor(fragment: String) = sourceSpansForSpoken(spokenRangeOf(fragment))

internal fun sourceSpan(
    source: String,
    fragment: String,
    leafId: String = PRIMARY_LEAF_ID,
    occurrence: Int = 0,
): SpeechSourceSpan {
    var index = source.indexOf(fragment)
    repeat(occurrence) { index = source.indexOf(fragment, index + 1) }
    check(index >= 0) { "'$fragment' occurrence $occurrence not found in '$source'" }
    return SpeechSourceSpan(leafId, index, index + fragment.length)
}
