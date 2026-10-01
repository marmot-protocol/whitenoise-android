package dev.ipf.whitenoise.android.state

/** A retained screen cannot consume manual attention after its visible host has left. */
internal fun WhiteNoiseAppState.isConversationReadVisible(
    accountRef: String,
    groupIdHex: String,
): Boolean =
    appInForeground &&
        !appLockScreenVisible &&
        activeConversationAccountRef == accountRef &&
        activeConversationGroupIdHex?.equals(groupIdHex, ignoreCase = true) == true
