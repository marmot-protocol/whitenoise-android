package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.notifications.departedChatConversationShortcutIds
import dev.ipf.whitenoise.android.notifications.groupChannelShortcutIds
import dev.ipf.whitenoise.android.notifications.hasDepartedChatClaims
import dev.ipf.whitenoise.android.notifications.pruneDepartedChatClaims
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shortcut ids of `:conv:` channels whose chat no account lists any more, such as a chat an older build deleted
 * after its vibration was reset to Default. Every other signing account's chats are read first, so none of their
 * channels is ever counted. Null when any of them cannot be listed, or Android's channels cannot be read, since such a
 * channel could still be theirs.
 */
internal suspend fun WhiteNoiseAppState.departedChatChannelShortcutIds(
    accountRef: String,
    groupIds: Set<String>,
): Set<String>? {
    val others = accounts.filter { it.label != accountRef && it.isSignedInSigningAccount() }.map { it.label }
    val kept = keptChatShortcutIds(others) ?: return null
    return withContext(Dispatchers.IO) {
        departedChatConversationShortcutIds(appContext, accountRef, groupIds, kept)
    }
}

/**
 * Shortcut ids of every chat [accountRefs] list now, from each account's full inventory, or null when any of them
 * cannot be listed. Only signing accounts should be passed: a signed-out or read-only profile cannot list chats
 * natively, and its own sign-out already cleaned its channels.
 */
internal suspend fun WhiteNoiseAppState.keptChatShortcutIds(accountRefs: Collection<String>): Set<String>? {
    val kept = HashSet<String>()
    for (account in accountRefs.distinct()) {
        val inventory = readAccountKnownGroupIds(account)
        if (!inventory.nativeInventoryComplete) {
            appStateDebug { "could not list a signing account's chats for unlisted chat channels" }
            return null
        }
        kept += groupChannelShortcutIds(account, inventory.groupIds)
    }
    return kept
}

/**
 * Before retention deletes the channels a sign-out inferred from chats no account listed, reads every signing
 * account in [accounts] again and drops from those claims any chat listed now, such as one restored since. Returns
 * whether the claims are revalidated: true with no claims at all, false when an inventory or the save failed, so
 * retention keeps the claims for the next account refresh.
 */
internal suspend fun WhiteNoiseAppState.revalidateDepartedChatClaims(accounts: List<AccountSummaryFfi>): Boolean {
    if (!withContext(Dispatchers.IO) { hasDepartedChatClaims(appContext) }) return true
    val signers = accounts.filter { it.isSignedInSigningAccount() }.map { it.label }
    val kept = keptChatShortcutIds(signers)
    return kept != null && withContext(Dispatchers.IO) { pruneDepartedChatClaims(appContext, kept) }
}
