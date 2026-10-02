package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListRowFfi

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

/** Only the visible unlocked conversation may consume a newly selected reminder. */
internal fun WhiteNoiseAppState.hasManualUnreadReminder(
    accountRef: String,
    groupIdHex: String,
    conversationRow: ChatListRowFfi?,
): Boolean {
    if (!isConversationReadVisible(accountRef, groupIdHex)) return false
    return boundChats(accountRef)?.hasManualUnreadReminder(groupIdHex)
        ?: (conversationRow?.manuallyMarkedUnread == true)
}
