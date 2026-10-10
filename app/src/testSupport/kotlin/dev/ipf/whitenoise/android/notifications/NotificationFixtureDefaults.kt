package dev.ipf.whitenoise.android.notifications

/** Stable identity defaults shared by typed notification fixtures, so a call site names only what it varies. */
object NotificationFixtureDefaults {
    const val ACCOUNT_REF = "account-a"
    const val GROUP_ID_HEX = "group-a"
    const val MESSAGE_ID_HEX = "message"
    const val GROUP_NAME = "General"
    const val SENDER_ACCOUNT_ID_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    const val RECEIVER_ACCOUNT_ID_HEX = "self"
    const val SENDER_NAME = "Alice"
    const val RECEIVER_NAME = "Me"
    const val PREVIEW_TEXT = "hi"
    const val TIMESTAMP_MS = 1_234L
}
