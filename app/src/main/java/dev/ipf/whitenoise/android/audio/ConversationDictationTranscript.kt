package dev.ipf.whitenoise.android.audio

private const val DICTATION_LEADING_PUNCTUATION = ".,!?;:%)]}。、，！？；：％）］｝】》〉」』؟،؛"

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
                expectedDraftRevision = target.capturedDraftRevision,
                expectedDraftText = target.capturedDraft.text,
                payload = text,
            )
        }
}
