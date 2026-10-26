package dev.ipf.whitenoise.android.audio

/** Host refusal provenance is distinct from an unlocked conditional-write conflict. */
internal sealed interface ConversationDictationDraftWrite {
    data class Written(
        val revision: Long,
    ) : ConversationDictationDraftWrite

    data object DeferredByHost : ConversationDictationDraftWrite

    data object Refused : ConversationDictationDraftWrite
}
