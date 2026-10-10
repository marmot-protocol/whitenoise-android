package dev.ipf.whitenoise.android.ui.chats.newchat

/** Native identity decoding supplies canonical keys, including supported Nostr URI forms. */
internal fun isNoteToSelfIdentifier(
    query: String,
    accountHex: String?,
    decode: (String) -> String?,
): Boolean {
    if (accountHex == null || query.isBlank()) return false
    return decode(query.trim())?.equals(accountHex, ignoreCase = true) == true
}
