package dev.ipf.whitenoise.android.state

/** A retained screen cannot consume manual attention after its visible host has left. */
internal fun WhiteNoiseAppState.isConversationReadVisible(
    accountRef: String,
    groupIdHex: String,
): Boolean {
    // Observe the same platform transitions used by notification suppression
    // so a settled screen retries when navigation/foreground ownership changes.
    val visibility = conversationReadVisibility.value
    return visibility.inForeground &&
        !appLockScreenVisible &&
        visibility.activeConversationAccountRef == accountRef &&
        visibility.activeConversationGroupIdHex?.equals(groupIdHex, ignoreCase = true) == true
}
