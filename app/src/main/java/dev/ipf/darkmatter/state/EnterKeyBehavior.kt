package dev.ipf.darkmatter.state

/**
 * What the Enter key does in the message composer (#404).
 * [SendMessage] is the default — matches most chat composers. Power users who
 * write multi-line messages can switch to [NewLine]. In [SendMessage] mode
 * Shift+Enter still inserts a line break.
 */
enum class EnterKeyBehavior(
    val preferenceValue: String,
) {
    SendMessage("send"),
    NewLine("newline"),
    ;

    companion object {
        fun fromPreference(value: String?): EnterKeyBehavior = entries.firstOrNull { it.preferenceValue == value } ?: SendMessage
    }
}
