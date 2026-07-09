package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import java.util.Locale

/**
 * Local UI preference for per-account, per-group message hides ("Delete for me").
 * Stores only user-hidden message ids — not protocol data (AGENTS.md).
 */
internal object MessageHidePreferences {
    private const val KEY_PREFIX = "hidden_messages:"

    fun normalizedAccountRef(accountRef: String?): String? =
        accountRef
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun normalizedGroupId(groupIdHex: String): String? =
        groupIdHex
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() }

    fun normalizedMessageId(messageIdHex: String): String? =
        messageIdHex
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() }

    fun preferenceKey(
        accountRef: String?,
        groupIdHex: String,
    ): String? {
        val account = normalizedAccountRef(accountRef) ?: return null
        val group = normalizedGroupId(groupIdHex) ?: return null
        return "$KEY_PREFIX$account:$group"
    }

    fun accountKeyPrefix(accountRef: String?): String? {
        val account = normalizedAccountRef(accountRef) ?: return null
        return "$KEY_PREFIX$account:"
    }

    fun readHiddenMessageIds(
        preferences: SharedPreferences,
        accountRef: String?,
        groupIdHex: String,
    ): Set<String> {
        val key = preferenceKey(accountRef, groupIdHex) ?: return emptySet()
        return readHiddenMessageIdsByKey(preferences, key)
    }

    fun readHiddenMessageIdsByKey(
        preferences: SharedPreferences,
        key: String,
    ): Set<String> =
        preferences
            .getStringSet(key, null)
            ?.mapNotNull(::normalizedMessageId)
            ?.toSet()
            ?: emptySet()

    fun hideMessage(
        preferences: SharedPreferences,
        accountRef: String?,
        groupIdHex: String,
        messageIdHex: String,
    ): Set<String> {
        val key = preferenceKey(accountRef, groupIdHex) ?: return emptySet()
        val messageId = normalizedMessageId(messageIdHex) ?: return readHiddenMessageIdsByKey(preferences, key)
        val updated = readHiddenMessageIdsByKey(preferences, key) + messageId
        writeHiddenMessageIdsByKey(preferences, key, updated)
        return updated
    }

    fun writeHiddenMessageIdsByKey(
        preferences: SharedPreferences,
        key: String,
        ids: Set<String>,
    ) {
        val edit = preferences.edit()
        if (ids.isEmpty()) {
            edit.remove(key)
        } else {
            edit.putStringSet(key, HashSet(ids))
        }
        edit.apply()
    }

    fun clearAccount(
        preferences: SharedPreferences,
        accountRef: String?,
    ) {
        val prefix = accountKeyPrefix(accountRef) ?: return
        val edit = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(prefix) }
            .forEach(edit::remove)
        edit.apply()
    }
}

internal fun isTimelineMessageVisible(
    messageIdHex: String,
    hiddenIds: Set<String>,
): Boolean {
    if (hiddenIds.isEmpty()) return true
    val normalized = MessageHidePreferences.normalizedMessageId(messageIdHex)
    return normalized == null || normalized !in hiddenIds
}

internal fun filterHiddenTimelineMessageIds(
    messageIds: Collection<String>,
    hiddenIds: Set<String>,
): List<String> = messageIds.filter { id -> isTimelineMessageVisible(id, hiddenIds) }
