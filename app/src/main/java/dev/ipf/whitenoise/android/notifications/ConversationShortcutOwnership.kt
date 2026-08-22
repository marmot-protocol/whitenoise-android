package dev.ipf.whitenoise.android.notifications

import android.os.PersistableBundle

internal const val CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA =
    "dev.ipf.whitenoise.android.shortcut.ACCOUNT_SCOPE"
private const val ACCOUNT_SCOPE_DIGEST_LENGTH = 32

internal fun conversationShortcutAccountScope(accountRef: String): String? {
    if (accountRef.isBlank()) return null
    return sha256Hex("account\u0000$accountRef").take(ACCOUNT_SCOPE_DIGEST_LENGTH)
}

internal fun conversationShortcutAccountExtras(accountRef: String): PersistableBundle? =
    conversationShortcutAccountScope(accountRef)?.let(::conversationShortcutAccountScopeExtras)

internal fun conversationShortcutAccountScopeExtras(accountScope: String): PersistableBundle =
    PersistableBundle().apply {
        putString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA, accountScope)
    }
