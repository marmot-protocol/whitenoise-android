package dev.ipf.whitenoise.android.audio

/** These closed labels describe UI lifecycle state without serializing its private target or text. */
internal fun ConversationDictationState.diagnosticPhase(): String =
    when (this) {
        ConversationDictationState.Idle -> "Idle"
        is ConversationDictationState.ProviderSelectionRequired -> "ProviderSelectionRequired"
        is ConversationDictationState.DisclosureRequired -> "DisclosureRequired"
        is ConversationDictationState.PermissionRequired -> "PermissionRequired"
        is ConversationDictationState.CheckingProvider -> "CheckingProvider"
        is ConversationDictationState.Starting -> "Starting"
        is ConversationDictationState.Listening -> "Listening"
        is ConversationDictationState.Processing -> "Processing"
        is ConversationDictationState.ProviderActivityRequired -> "ProviderActivityRequired"
        is ConversationDictationState.ProviderActivityActive -> "ProviderActivityActive"
        is ConversationDictationState.Failed -> "Failed"
    }
