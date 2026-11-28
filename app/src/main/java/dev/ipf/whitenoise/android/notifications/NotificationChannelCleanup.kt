package dev.ipf.whitenoise.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.pm.ShortcutInfoCompat
import dev.ipf.whitenoise.android.R
import java.util.WeakHashMap

/*
 * Destructive cleanup of app-owned Android notification channels.
 *
 * The channel-retention policy still holds for routine preference resets and vibration-pattern
 * changes: resetting a contact or chat category back to defaults leaves user-owned OS channels
 * dormant so Android-controlled sound, importance, lights, and DND settings survive.
 *
 * Account Sign Out, Sign Out & Wipe, and local conversation deletion are explicit destructive
 * exceptions to that retention policy: per-contact (`profile_messages_v1:`) and per-conversation
 * (`:conv:`) channels carry plaintext nicknames, display names, or chat titles in OS Settings, so
 * deleting those owned channels prevents private labels from surviving after the account or chat is
 * removed. Other signing accounts' channels and global channels are never deleted.
 *
 * Known limit: a `:conv:` channel created before ownership was recorded, with no recoverable ownership
 * evidence left (no chat, shortcut, owner record, vibration preference, pending local deletion or listed
 * group), cannot be traced to its account (its id hashes the account with the group). While another
 * signing account remains, it survives that account's sign-out and is removed only when the last signing
 * account leaves. Any channel one of those identifies is deleted with its account.
 */
internal const val LEGACY_SWEEP_PENDING_KEY = "legacy_conversation_sweep_pending"
internal const val CONVERSATION_CHANNEL_OWNERS_PREFS = "whitenoise.conversation_channel_owners"
private const val CONVERSATION_CHANNEL_INFIX = ":conv:"
private const val VIBRATION_SUFFIX_MARKER = ":vibration:"

/** Tracks owner keys whose latest in-memory mutation has not yet been durably committed to disk. */
private object UncommittedChannelOwners {
    private val byContext = WeakHashMap<Context, MutableSet<String>>()

    /** Returns the mutable uncommitted-key set for [appContext] under `UserEventNotificationGroup.mutationLock`. */
    fun keys(appContext: Context): MutableSet<String> = byContext.getOrPut(appContext) { mutableSetOf() }
}

/**
 * Returns the fixed-width `profile_messages_v1:<sha256(account)>.` prefix for [accountRef], or null
 * when [accountRef] is blank or untrimmed so cleanup cannot match an unintended account.
 */
internal fun profileNotificationChannelAccountPrefix(accountRef: String): String? =
    channelOwnerAccountHash(accountRef)?.let { "${ProfileNotificationChannels.PREFIX}$it." }

/**
 * Resolves the owned conversation shortcut id for [channel], preferring its deterministic id format
 * and falling back to Android's `conversationId` link for legacy parent ids.
 */
internal fun conversationChannelShortcutId(channel: NotificationChannel): String? {
    val channelId = channel.id
    val activeParent =
        NotificationChannelSpec.entries.firstOrNull { spec ->
            channelId.startsWith("${spec.id}$CONVERSATION_CHANNEL_INFIX$CONVERSATION_SHORTCUT_PREFIX")
        }
    val parentId =
        activeParent?.id ?: LEGACY_AGENT_ACTIVITY_CHANNEL_ID.takeIf { legacy ->
            channelId.startsWith("$legacy$CONVERSATION_CHANNEL_INFIX$CONVERSATION_SHORTCUT_PREFIX")
        }
    val fromId =
        parentId?.let {
            channelId
                .removePrefix("$it$CONVERSATION_CHANNEL_INFIX")
                .substringBefore(VIBRATION_SUFFIX_MARKER)
                .takeIf(::isConversationShortcutId)
        }
    return fromId ?: channel.conversationId?.takeIf(::isConversationShortcutId)
}

/**
 * Records a hashed `(account, conversationShortcutId)` link so multi-account sign-out or wipe can
 * still delete owned `:conv:` channels even if Android evicted the corresponding cached shortcut.
 */
internal fun recordConversationChannelOwner(
    context: Context,
    accountRef: String?,
    conversationShortcutId: String,
) {
    recordConversationChannelOwners(context, accountRef, listOf(conversationShortcutId))
}

/** Persists [accountRef]'s owner links and any last-signer sweep intent in one transaction. */
internal fun recordConversationChannelOwners(
    context: Context,
    accountRef: String?,
    conversationShortcutIds: Collection<String>,
    includeUnscopedLegacy: Boolean = false,
): Boolean {
    val ownerKey = channelOwnerAccountHash(accountRef) ?: return false
    val shortcutIds = conversationShortcutIds.filter(::isConversationShortcutId)
    return synchronized(UserEventNotificationGroup.mutationLock) {
        runCatching {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(CONVERSATION_CHANNEL_OWNERS_PREFS, Context.MODE_PRIVATE)
            val uncommitted = UncommittedChannelOwners.keys(appContext)
            val owned = prefs.getStringSet(ownerKey, emptySet()).orEmpty()
            // Every routed post lands here, so skip the synchronous commit once the links are durably stored.
            if (!includeUnscopedLegacy && ownerKey !in uncommitted && owned.containsAll(shortcutIds)) {
                true
            } else {
                uncommitted.add(ownerKey)
                val editor = prefs.edit().putStringSet(ownerKey, owned + shortcutIds)
                if (includeUnscopedLegacy) editor.putStringSet(LEGACY_SWEEP_PENDING_KEY, emptySet())
                val committed = editor.commit()
                if (committed) uncommitted.remove(ownerKey)
                committed
            }
        }.getOrDefault(false)
    }
}

/**
 * Deletes every `:conv:` notification channel owned by ([accountRef], [groupIdHex]) across all
 * parent categories and vibration variants, leaving other conversations, other accounts, per-profile
 * channels, and global channels untouched. Returns true only when enumeration, redaction, and
 * deletion succeeded for every matched channel.
 */
internal fun deleteConversationNotificationChannels(
    context: Context,
    accountRef: String,
    groupIdHex: String,
): Boolean {
    val targetShortcutIds = groupChannelShortcutIds(accountRef, listOf(groupIdHex))
    if (targetShortcutIds.isEmpty()) return true
    return synchronized(UserEventNotificationGroup.mutationLock) {
        NotificationChannelWriteGuard.recordConversationCleanup(targetShortcutIds)
        val appContext = context.applicationContext
        val allMatchedDeleted =
            deleteMatchingNotificationChannels(appContext) { channel ->
                conversationChannelShortcutId(channel) in targetShortcutIds
            }
        val ownerKey = channelOwnerAccountHash(accountRef)
        if (allMatchedDeleted && ownerKey != null) {
            runCatching {
                val prefs = appContext.getSharedPreferences(CONVERSATION_CHANNEL_OWNERS_PREFS, Context.MODE_PRIVATE)
                val remaining =
                    prefs
                        .getStringSet(ownerKey, emptySet())
                        .orEmpty()
                        .filterNotTo(mutableSetOf()) { storedId ->
                            channelShortcutIdsFromShortcutId(storedId).any { it in targetShortcutIds }
                        }
                val editor = prefs.edit()
                if (remaining.isEmpty()) editor.remove(ownerKey) else editor.putStringSet(ownerKey, remaining)
                val uncommitted = UncommittedChannelOwners.keys(appContext)
                uncommitted.add(ownerKey)
                if (editor.commit()) uncommitted.remove(ownerKey)
            }
        }
        allMatchedDeleted
    }
}

/**
 * Deletes all `profile_messages_v1:` and `:conv:` notification channels owned by the leaving
 * [accountRef] during Sign Out or Sign Out & Wipe, leaving other accounts' customized channels and
 * global channels untouched.
 *
 * Matches channels owned via stored records, platform shortcuts, captured [knownGroupIds], and
 * local vibration preferences so upgrade-era channels without shortcuts or ownership records are
 * still removed in multi-account setups. When cleanup is incomplete, the departing account's owner
 * record is persisted and retained so account-refresh retention retries until cleanup succeeds.
 */
@Suppress("LongParameterList")
// Destructive account cleanup merges shortcut, owner-store, vibration, and known-group inventories under one lock.
internal fun deleteAccountNotificationChannels(
    context: Context,
    accountRef: String,
    shortcuts: List<ShortcutInfoCompat>,
    accountShortcutIds: Collection<String>,
    includeUnscopedLegacy: Boolean,
    knownGroupIds: Collection<String> = emptySet(),
): Boolean =
    synchronized(UserEventNotificationGroup.mutationLock) {
        val appContext = context.applicationContext
        val prefs =
            runCatching {
                appContext.getSharedPreferences(CONVERSATION_CHANNEL_OWNERS_PREFS, Context.MODE_PRIVATE)
            }.getOrNull()
        val leavingShortcutIds = prefs?.let { storedAccountShortcutIds(it, accountRef) }.orEmpty().toMutableSet()
        accountShortcutIds.flatMapTo(leavingShortcutIds, ::channelShortcutIdsFromShortcutId)
        val vibrationGroupIds =
            runCatching { ConversationVibrationPreferences(appContext).groupIdsForAccount(accountRef) }
                .getOrDefault(emptySet())
        leavingShortcutIds += groupChannelShortcutIds(accountRef, knownGroupIds + vibrationGroupIds)
        NotificationChannelWriteGuard.recordAccountCleanup(accountRef, includeUnscopedLegacy, leavingShortcutIds)
        val otherShortcutIds = otherAccountShortcutIds(shortcuts, accountRef)
        // Only this account's contact channels: a remaining read-only account can still own customized ones.
        val leavingProfilePrefix = profileNotificationChannelAccountPrefix(accountRef)
        val allMatchedDeleted =
            deleteMatchingNotificationChannels(appContext) { channel ->
                val isLeavingProfile = leavingProfilePrefix != null && channel.id.startsWith(leavingProfilePrefix)
                val convShortcut = conversationChannelShortcutId(channel)
                val isLeavingConversation =
                    when {
                        convShortcut == null -> false
                        convShortcut in leavingShortcutIds -> true
                        else -> includeUnscopedLegacy && convShortcut !in otherShortcutIds
                    }
                isLeavingProfile || isLeavingConversation
            }
        if (prefs != null) {
            updateOwnerStoreAfterAccountCleanup(
                appContext = appContext,
                prefs = prefs,
                accountRef = accountRef,
                includeUnscopedLegacy = includeUnscopedLegacy,
                allMatchedDeleted = allMatchedDeleted,
                leavingShortcutIds = leavingShortcutIds,
            )
        }
        allMatchedDeleted
    }

/** Updates or retains the departed account's owner record based on whether channel cleanup succeeded. */
@Suppress("LongParameterList")
// Persisting or clearing the owner record under mutationLock requires context, prefs, and cleanup flags together.
private fun updateOwnerStoreAfterAccountCleanup(
    appContext: Context,
    prefs: SharedPreferences,
    accountRef: String,
    includeUnscopedLegacy: Boolean,
    allMatchedDeleted: Boolean,
    leavingShortcutIds: Set<String>,
) {
    runCatching {
        val ownerKey = channelOwnerAccountHash(accountRef)
        val uncommitted = UncommittedChannelOwners.keys(appContext)
        when {
            allMatchedDeleted && ownerKey != null -> {
                val editor = prefs.edit().remove(ownerKey)
                // Retention clears this only after both the legacy channels and launcher shortcuts are gone.
                if (includeUnscopedLegacy) editor.putStringSet(LEGACY_SWEEP_PENDING_KEY, emptySet())
                if (editor.commit()) uncommitted.remove(ownerKey)
            }
            !allMatchedDeleted && ownerKey != null -> {
                val canonicalLeaving = leavingShortcutIds.mapTo(mutableSetOf(), ::canonicalConversationShortcutId)
                val editor = prefs.edit().putStringSet(ownerKey, canonicalLeaving)
                // A failed last-signer sweep cannot record ownerless legacy channels; retention retries it.
                if (includeUnscopedLegacy) editor.putStringSet(LEGACY_SWEEP_PENDING_KEY, emptySet())
                uncommitted.add(ownerKey)
                if (editor.commit()) uncommitted.remove(ownerKey)
            }
        }
    }
}

/**
 * Retries channel deletion for each departed account key in [staleKeys] and removes only keys whose
 * channels were enumerated, redacted, and deleted without error.
 */
internal fun pruneStaleAccountChannelsAndOwners(
    appContext: Context,
    prefs: SharedPreferences,
    staleKeys: List<String>,
): Boolean {
    val clearedKeys = mutableListOf<String>()
    var allCleared = true
    staleKeys.forEach { staleKey ->
        val staleProfilePrefix = "${ProfileNotificationChannels.PREFIX}$staleKey."
        val staleShortcutIds =
            prefs
                .getStringSet(staleKey, emptySet())
                .orEmpty()
                .flatMapTo(mutableSetOf(), ::channelShortcutIdsFromShortcutId)
        val deleted =
            deleteMatchingNotificationChannels(appContext) { channel ->
                val isStaleProfile = channel.id.startsWith(staleProfilePrefix)
                val convShortcut = conversationChannelShortcutId(channel)
                isStaleProfile || (convShortcut != null && convShortcut in staleShortcutIds)
            }
        if (deleted) clearedKeys += staleKey else allCleared = false
    }
    val committed =
        if (clearedKeys.isEmpty()) {
            true
        } else {
            val editor = prefs.edit()
            clearedKeys.forEach(editor::remove)
            val saved = editor.commit()
            if (saved) UncommittedChannelOwners.keys(appContext).removeAll(clearedKeys)
            saved
        }
    return allCleared && committed
}

/**
 * Deletes every Android notification channel matching [shouldDelete], isolating per-channel platform
 * errors so one failure cannot abort remaining deletions. Each channel is first relabeled with the
 * generic hidden-content name, because Android keeps soft-deleted channels in its notification policy.
 * Returns true only when [NotificationManager] was available, channel enumeration succeeded, and every
 * matched deletion succeeded.
 */
internal fun deleteMatchingNotificationChannels(
    appContext: Context,
    shouldDelete: (NotificationChannel) -> Boolean,
): Boolean {
    val manager = appContext.getSystemService(NotificationManager::class.java)
    val channels = manager?.let { runCatching { it.notificationChannels.toList() }.getOrNull() }
    return if (manager == null || channels == null) {
        false
    } else {
        val genericName = appContext.getString(R.string.notifications)
        val genericDescription = appContext.getString(R.string.notification_hidden_content)
        channels
            .filter(shouldDelete)
            .fold(true) { allSucceeded, channel ->
                val deleted =
                    runCatching {
                        val redacted =
                            NotificationChannel(channel.id, genericName, channel.importance).apply {
                                description = genericDescription
                            }
                        manager.createNotificationChannel(redacted)
                        manager.deleteNotificationChannel(channel.id)
                    }.isSuccess
                allSucceeded && deleted
            }
    }
}

/** Collects conversation shortcut ids explicitly owned by accounts other than [accountRef]. */
private fun otherAccountShortcutIds(
    shortcuts: List<ShortcutInfoCompat>,
    accountRef: String,
): Set<String> {
    val accountScope = conversationShortcutAccountScope(accountRef)
    return shortcuts
        .filter { shortcut ->
            val ownerScope = shortcut.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA)
            val ownedByOther = !ownerScope.isNullOrBlank() && ownerScope != accountScope
            ownedByOther && isConversationShortcutId(shortcut.id)
        }.flatMapTo(mutableSetOf()) { channelShortcutIdsFromShortcutId(it.id) }
}

/** Returns every conversation shortcut id variant recorded for [accountRef] in the hashed owner store. */
private fun storedAccountShortcutIds(
    preferences: SharedPreferences,
    accountRef: String,
): Set<String> {
    val ownerKey = channelOwnerAccountHash(accountRef) ?: return emptySet()
    return preferences
        .getStringSet(ownerKey, emptySet())
        .orEmpty()
        .flatMapTo(mutableSetOf(), ::channelShortcutIdsFromShortcutId)
}
