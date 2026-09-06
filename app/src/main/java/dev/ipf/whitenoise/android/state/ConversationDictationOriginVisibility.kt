package dev.ipf.whitenoise.android.state

/** The immutable dictation origin owns the composer controls only while it is unobscured and foregrounded. */
internal fun conversationDictationOriginVisible(
    appInForeground: Boolean,
    appLockScreenVisible: Boolean,
    pendingProfileNpub: String?,
    activeAccountRef: String?,
    activeGroupIdHex: String?,
    accountRef: String,
    groupIdHex: String,
): Boolean =
    appInForeground &&
        !appLockScreenVisible &&
        pendingProfileNpub == null &&
        activeAccountRef.equals(accountRef, ignoreCase = true) &&
        activeGroupIdHex.equals(groupIdHex, ignoreCase = true)
