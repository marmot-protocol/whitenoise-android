package dev.ipf.whitenoise.android.audio

/** What a conversation's dictation is doing, as shown outside the conversation. */
internal enum class ConversationDictationActivity {
    Listening,
    Transcribing,
}

/** Passive chat-list status: whether this conversation is capturing speech or turning it into text. */
internal fun ConversationDictationController.chatListActivity(
    accountRef: String,
    groupIdHex: String,
): ConversationDictationActivity? {
    if (!isOwnedBy(accountRef, groupIdHex)) return null
    return when (state) {
        is ConversationDictationState.Starting,
        is ConversationDictationState.Listening,
        is ConversationDictationState.Processing,
        ->
            if (captureInProgress && !deliveryInProgress) {
                ConversationDictationActivity.Listening
            } else {
                ConversationDictationActivity.Transcribing
            }
        // Provider checks and pending launches have not opened capture yet.
        is ConversationDictationState.ProviderActivityActive -> ConversationDictationActivity.Listening
        else -> null
    }
}
