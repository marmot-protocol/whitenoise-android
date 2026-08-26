package dev.ipf.whitenoise.android.state

/**
 * What the Enter key does in the message composer (#404).
 * [NewLine] is the default. Users who prefer a bare Enter to submit can opt in
 * to [SendMessage]; Shift+Enter still inserts a line break in that mode.
 */
enum class EnterKeyBehavior(
    val preferenceValue: String,
) {
    SendMessage("send"),
    NewLine("newline"),
    ;

    companion object {
        val DEFAULT = NewLine

        fun fromPreference(value: String?): EnterKeyBehavior = entries.firstOrNull { it.preferenceValue == value } ?: DEFAULT
    }
}
