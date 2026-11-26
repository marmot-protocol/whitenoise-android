package dev.ipf.whitenoise.android.notifications

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat

// Prefixes an owner hash for the channels a departing account's sign-out inferred, from chats no account lists.
// They are kept apart from the account's own record and replaced on every capture, so a retried sign-out claims
// only what is still unlisted then. Retention deletes them only once every signing account's chats were read
// again and none lists them (see [pruneDepartedChatClaims]).
internal const val DEPARTED_CHAT_KEY_PREFIX = "departed_chats:"

/** The owner-store key for the departed-chat channels inferred for this owner hash; see [DEPARTED_CHAT_KEY_PREFIX]. */
internal val String.departedChatOwnerKey: String
    get() = "$DEPARTED_CHAT_KEY_PREFIX$this"

/**
 * Replaces [accountRef]'s inferred departed-chat claims with [shortcutIds], or leaves them as they are when
 * [shortcutIds] is null, because they could not be worked out. A write is skipped only when the stored set
 * already matches and its last commit did not fail, since Android updates the set in memory before a failed disk
 * write. Returns whether the claims are durably saved.
 */
internal fun recordDepartedChatClaims(
    context: Context,
    accountRef: String,
    shortcutIds: Set<String>?,
): Boolean {
    val key = channelOwnerAccountHash(accountRef)?.departedChatOwnerKey ?: return false
    val claims = shortcutIds?.filterTo(HashSet(), ::isConversationShortcutId)
    return claims == null ||
        synchronized(UserEventNotificationGroup.mutationLock) {
            runCatching {
                val appContext = context.applicationContext
                val prefs = appContext.getSharedPreferences(CONVERSATION_CHANNEL_OWNERS_PREFS, Context.MODE_PRIVATE)
                val uncommitted = UncommittedChannelOwners.keys(appContext)
                if (key !in uncommitted && prefs.getStringSet(key, emptySet()).orEmpty() == claims) {
                    true
                } else {
                    uncommitted.add(key)
                    val editor = prefs.edit()
                    if (claims.isEmpty()) editor.remove(key) else editor.putStringSet(key, claims)
                    editor.commit().also { committed -> if (committed) uncommitted.remove(key) }
                }
            }.getOrDefault(false)
        }
}

/** Whether any sign-out left inferred departed-chat claims for retention to revalidate and delete. */
internal fun hasDepartedChatClaims(context: Context): Boolean =
    synchronized(UserEventNotificationGroup.mutationLock) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(CONVERSATION_CHANNEL_OWNERS_PREFS, Context.MODE_PRIVATE)
                .all
                .keys
                .any { it.startsWith(DEPARTED_CHAT_KEY_PREFIX) }
        }.getOrDefault(true)
    }

/**
 * Drops from every inferred departed-chat claim the channels of chats a signing account lists now, [keptShortcutIds],
 * such as a chat restored after the sign-out inferred its channel. Returns whether every changed claim was saved;
 * retention deletes the remaining claims only then.
 */
internal fun pruneDepartedChatClaims(
    context: Context,
    keptShortcutIds: Set<String>,
): Boolean {
    val kept = keptShortcutIds.mapTo(HashSet(), ::canonicalConversationShortcutId)
    return synchronized(UserEventNotificationGroup.mutationLock) {
        runCatching {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(CONVERSATION_CHANNEL_OWNERS_PREFS, Context.MODE_PRIVATE)
            val uncommitted = UncommittedChannelOwners.keys(appContext)
            val editor = prefs.edit()
            val changed =
                prefs.all.filterKeys { it.startsWith(DEPARTED_CHAT_KEY_PREFIX) }.mapNotNull { (key, value) ->
                    val claims = (value as? Set<*>)?.filterIsInstance<String>().orEmpty().toSet()
                    val remaining = claims.filterNot { canonicalConversationShortcutId(it) in kept }.toSet()
                    key.takeIf { remaining != claims || key in uncommitted }?.also {
                        if (remaining.isEmpty()) editor.remove(key) else editor.putStringSet(key, remaining)
                    }
                }
            uncommitted.addAll(changed)
            changed.isEmpty() || editor.commit().also { committed -> if (committed) uncommitted.removeAll(changed) }
        }.getOrDefault(false)
    }
}

/**
 * Every conversation shortcut id variant of the platform shortcuts scoped to an account in [retainedScopes]: chats a
 * retained account shows now, whose channels an inferred departed-chat claim must never delete. Null when the
 * shortcuts cannot be read, so no claim is deleted unchecked.
 */
internal fun retainedScopedShortcutIds(
    appContext: Context,
    retainedScopes: Set<String>,
): Set<String>? =
    runCatching {
        val flags =
            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or
                ShortcutManagerCompat.FLAG_MATCH_CACHED or
                ShortcutManagerCompat.FLAG_MATCH_PINNED
        ShortcutManagerCompat
            .getShortcuts(appContext, flags)
            .filter { it.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA) in retainedScopes }
            .flatMapTo(HashSet()) { channelShortcutIdsFromShortcutId(it.id) }
    }.getOrNull()

/**
 * The owner-store [keys] retention prunes: records of accounts not in [retainedKeys], and their inferred
 * departed-chat claims only when [claimsDeletable], after every signing account's chats were read again.
 */
internal fun staleOwnerKeys(
    keys: Collection<String>,
    retainedKeys: Set<String>,
    claimsDeletable: Boolean,
): List<String> =
    keys.filter { key ->
        val claims = key.startsWith(DEPARTED_CHAT_KEY_PREFIX)
        key != LEGACY_SWEEP_PENDING_KEY &&
            key.removePrefix(DEPARTED_CHAT_KEY_PREFIX) !in retainedKeys &&
            (!claims || claimsDeletable)
    }
