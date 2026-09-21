package dev.ipf.whitenoise.android.audio

private const val DICTATION_LEADING_PUNCTUATION = ".,!?;:%)]}。、，！？；：％）］｝】》〉」』؟،؛"

/**
 * Removes an explicit spoken send command only when it is the terminal phrase of one provider-final segment.
 *
 * Provider punctuation is not stable, so punctuation and whitespace may separate command words and may trail the
 * phrase. The distinctive wake-prefixed phrase keeps this permissive punctuation handling from turning ordinary
 * dictated prose into an accidental send command.
 */
internal fun stripTerminalConversationDictationVoiceCommand(
    segment: String,
    command: String,
): String? {
    val words = command.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    if (words.isEmpty()) return null
    val phrase = words.joinToString("[\\s\\p{P}]+") { Regex.escape(it) }
    val match = Regex("(?iu)(?:^|(?<=[\\s\\p{P}]))$phrase[\\s\\p{P}]*$").find(segment) ?: return null
    return segment.substring(0, match.range.first).trimEnd()
}

/** Joins provider-final segments without corrupting provider-supplied punctuation. */
internal fun appendConversationDictationSegment(
    accumulated: String,
    segment: String,
): String {
    val current = accumulated.trimEnd()
    val next = segment.trim()
    return when {
        next.isEmpty() -> current
        current.isEmpty() -> next
        else -> {
            val punctuationLeading = next.first() in DICTATION_LEADING_PUNCTUATION
            val separator = if (punctuationLeading) "" else " "
            current + separator + next
        }
    }
}

/** Builds the immutable, conditionally committed request for opt-in send-on-finish delivery. */
internal fun conversationDictationSendRequest(
    target: ConversationDictationTarget,
    transcript: String,
): ConversationDictationSendRequest? {
    val payload =
        (
            mergeConversationDictationTranscript(target.capturedDraft, target.capturedDraft, transcript)
                as? ConversationDictationMerge.Applied
        )?.value
            ?.text
            ?.trim()
            .orEmpty()
    return payload
        .takeIf(String::isNotBlank)
        ?.let { text ->
            ConversationDictationSendRequest(
                accountRef = target.accountRef,
                groupIdHex = target.groupIdHex,
                replyToMessageIdHex = target.replyToMessageIdHex,
                expectedDraftRevision = target.capturedDraftRevision,
                expectedDraftText = target.capturedDraft.text,
                payload = text,
            )
        }
}
