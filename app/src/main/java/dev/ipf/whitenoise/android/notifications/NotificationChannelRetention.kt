package dev.ipf.whitenoise.android.notifications

import android.app.NotificationChannel
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat

private const val SHA256_HEX_LENGTH = 64

/**
 * Retains conversation-channel owner records only for [retainedAccountRefs], retrying channel cleanup for
 * departed accounts before removing their owner records. It also deletes contact channels whose account hash
 * belongs to no retained account, and the channels and then shortcuts of departed accounts that sign-out left
 * relabeled. Ownerless `:conv:` channels are swept once no account remains, or while a failed last-signer
 * sweep is pending and no signing account has joined since. Any failed enumeration, redaction, or deletion
 * keeps its record and returns false so account refresh retries until cleanup succeeds.
 */
internal fun retainConversationChannelOwners(
    context: Context,
    retainedAccountRefs: Collection<String>,
    signingAccountRemains: Boolean = retainedAccountRefs.isNotEmpty(),
    canRetain: () -> Boolean = { true },
): Boolean {
    val retainedKeys = retainedAccountRefs.mapNotNull(::channelOwnerAccountHash).toSet()
    val retainedScopes = retainedAccountRefs.mapNotNullTo(HashSet(), ::conversationShortcutAccountScope)
    return synchronized(UserEventNotificationGroup.mutationLock) {
        canRetain().not() ||
            retainLocked(context.applicationContext, retainedKeys, retainedScopes, signingAccountRemains)
    }
}

/** Runs retention under [UserEventNotificationGroup.mutationLock]; see [retainConversationChannelOwners]. */
private fun retainLocked(
    appContext: Context,
    retainedKeys: Set<String>,
    retainedScopes: Set<String>,
    signingAccountRemains: Boolean,
): Boolean {
    val prefs = appContext.getSharedPreferences(CONVERSATION_CHANNEL_OWNERS_PREFS, Context.MODE_PRIVATE)
    val sweepPending = prefs.contains(LEGACY_SWEEP_PENDING_KEY)
    val staleKeys = prefs.all.keys.filter { key -> key !in retainedKeys && key != LEGACY_SWEEP_PENDING_KEY }
    val ownersPruned = staleKeys.isEmpty() || pruneStaleAccountChannelsAndOwners(appContext, prefs, staleKeys)
    val retainedIds = retainedConversationShortcutIds(prefs, retainedKeys)
    // A remaining read-only account owns no conversation channels; a signer that joined since may own
    // unrecorded ones, so a pending sweep waits until no signing account is present.
    val sweepUnowned = !signingAccountRemains && (retainedKeys.isEmpty() || sweepPending)
    val departedShortcuts =
        runCatching {
            departedAccountShortcuts(appContext, retainedScopes, sweepUnowned)
        }.getOrNull()
    val departedIds = departedShortcuts.orEmpty().flatMapTo(HashSet()) { channelShortcutIdsFromShortcutId(it.id) }
    val orphansDeleted =
        deleteMatchingNotificationChannels(appContext) { channel ->
            isOrphanProfileChannel(channel, retainedKeys) ||
                isOrphanConversationChannel(channel, sweepUnowned, retainedIds, departedIds)
        }
    // Remove shortcuts only after their channels are gone, and keep retrying if inventory or removal fails.
    val shortcutsRemoved =
        orphansDeleted &&
            departedShortcuts != null &&
            runCatching { removeDepartedShortcuts(appContext, departedShortcuts) }.isSuccess
    val complete = ownersPruned && shortcutsRemoved
    return if (complete && sweepPending && sweepUnowned) {
        runCatching { prefs.edit().remove(LEGACY_SWEEP_PENDING_KEY).commit() }.getOrDefault(false)
    } else {
        complete
    }
}

/** Every conversation shortcut id variant recorded under the retained accounts' owner keys. */
private fun retainedConversationShortcutIds(
    prefs: SharedPreferences,
    retainedKeys: Set<String>,
): Set<String> =
    retainedKeys.flatMapTo(HashSet()) { key ->
        prefs.getStringSet(key, emptySet()).orEmpty().flatMap(::channelShortcutIdsFromShortcutId)
    }

/** Contact channel ids embed sha256(account), so a channel of an account no longer retained is an orphan. */
private fun isOrphanProfileChannel(
    channel: NotificationChannel,
    retainedKeys: Set<String>,
): Boolean {
    val rest = channel.id.removePrefix(ProfileNotificationChannels.PREFIX)
    val accountHash = rest.substringBefore('.', missingDelimiterValue = "")
    return rest != channel.id && accountHash.length == SHA256_HEX_LENGTH && accountHash !in retainedKeys
}

/** A `:conv:` channel of a departed account's shortcut, or an unowned one while [sweepUnowned] holds. */
private fun isOrphanConversationChannel(
    channel: NotificationChannel,
    sweepUnowned: Boolean,
    retainedIds: Set<String>,
    departedIds: Set<String>,
): Boolean {
    val convShortcut = conversationChannelShortcutId(channel) ?: return false
    return convShortcut in departedIds || (sweepUnowned && convShortcut !in retainedIds)
}

/** Finds shortcuts from departed accounts and, during a last-signer sweep, unscoped legacy shortcuts. */
private fun departedAccountShortcuts(
    appContext: Context,
    retainedScopes: Set<String>,
    includeUnscopedLegacy: Boolean,
): List<ShortcutInfoCompat> {
    val flags =
        ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or
            ShortcutManagerCompat.FLAG_MATCH_CACHED or
            ShortcutManagerCompat.FLAG_MATCH_PINNED
    val shortcuts = ShortcutManagerCompat.getShortcuts(appContext, flags)
    return shortcuts.filter { shortcut ->
        val scope = shortcut.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA)
        val departed = if (scope.isNullOrBlank()) includeUnscopedLegacy else scope !in retainedScopes
        isConversationShortcutId(shortcut.id) && departed
    }
}

/** Disables pinned copies and removes dynamic and cached copies of [shortcuts]. */
private fun removeDepartedShortcuts(
    appContext: Context,
    shortcuts: List<ShortcutInfoCompat>,
) {
    if (shortcuts.isEmpty()) return
    val ids = shortcuts.map { it.id }
    val pinned = shortcuts.filter { it.isPinned }.map { it.id }
    if (pinned.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(appContext, pinned, "")
    ShortcutManagerCompat.removeDynamicShortcuts(appContext, ids)
    ShortcutManagerCompat.removeLongLivedShortcuts(appContext, ids)
}
