package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ChatNotifyMode {
    ALL,
    MENTIONS_ONLY,

    /** Command-only compatibility value. MDK, never Android preferences, owns active mute state. */
    NONE,
}

data class MuteExpiry(
    val expiryMillis: Long?,
    val restoreMode: ChatNotifyMode,
)

data class ChatNotificationState(
    val notificationModes: Map<String, ChatNotifyMode>,
)

internal data class LegacyMuteEntry(
    val key: String,
    val accountRef: String,
    val groupIdHex: String,
    val expiryMillis: Long?,
    val restoreMode: ChatNotifyMode,
)

/** Android-owned ALL/MENTIONS delivery preference plus a read-once legacy mute migration source. */
class ChatMutePreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val mutationLock = Any()
    private val _state = MutableStateFlow(ChatNotificationState(readNotificationModes(preferences)))
    val state: StateFlow<ChatNotificationState> = _state.asStateFlow()

    /** Explicit choices win; only newly discovered non-DM groups default to mentions. */
    fun mode(
        accountRef: String,
        groupIdHex: String,
        isDm: Boolean = true,
    ): ChatNotifyMode =
        compositeKeyOrNull(accountRef, groupIdHex)?.let(_state.value.notificationModes::get)
            ?: if (isDm) ChatNotifyMode.ALL else ChatNotifyMode.MENTIONS_ONLY

    /** Returns the delivery preference retained beneath the independent native mute. */
    fun restoreNotifyMode(
        accountRef: String,
        groupIdHex: String,
        isDm: Boolean = true,
    ): ChatNotifyMode = mode(accountRef, groupIdHex, isDm)

    /** Persists explicit opt-in or mentions-only without modifying native mute state. */
    fun setNotifyForMode(
        accountRef: String,
        groupIdHex: String,
        mode: ChatNotifyMode,
    ) {
        val key = compositeKeyOrNull(accountRef, groupIdHex)
        if (mode != ChatNotifyMode.NONE && key != null) {
            synchronized(mutationLock) {
                val updated = _state.value.notificationModes.toMutableMap()
                updated[key] = mode
                if (updated != _state.value.notificationModes) {
                    _state.value = ChatNotificationState(updated.toMap())
                    persistModes(preferences.edit(), updated).apply()
                }
            }
        }
    }

    /** One installation-wide barrier, completed before native workers can discover new groups. */
    internal val needsDefaultsMigration: Boolean
        get() = !preferences.getBoolean(KEY_DEFAULTS_MIGRATED, false)

    /** Atomically preserves every old implicit All choice; a failed write leaves migration retryable. */
    internal fun preserveExistingModes(existingGroups: Map<String, List<String>>) {
        synchronized(mutationLock) {
            if (!needsDefaultsMigration) return
            val updated = _state.value.notificationModes.toMutableMap()
            existingGroups.forEach { (account, groups) ->
                groups.forEach { group ->
                    compositeKeyOrNull(account, group)?.let { updated.putIfAbsent(it, ChatNotifyMode.ALL) }
                }
            }
            val persisted = persistModes(preferences.edit(), updated).putBoolean(KEY_DEFAULTS_MIGRATED, true).commit()
            if (!persisted) {
                // commit() mutates SharedPreferences memory even on disk failure. Do not let a
                // same-process bootstrap retry mistake that unpersisted marker for success.
                preferences.edit().remove(KEY_DEFAULTS_MIGRATED).apply()
                error("Could not preserve existing notification preferences")
            }
            _state.value = ChatNotificationState(updated.toMap())
        }
    }

    /** Erasing an identity removes only that account's host notification choices. */
    internal fun removeAccount(accountRef: String) {
        synchronized(mutationLock) {
            val updated =
                _state.value.notificationModes.filterKeys { !it.startsWith("$accountRef$COMPOSITE_SEPARATOR") }
            persistModes(preferences.edit(), updated).apply()
            _state.value = ChatNotificationState(updated)
        }
    }

    /** Writes both explicit modes together; All must no longer collapse into an absent override. */
    private fun persistModes(
        editor: SharedPreferences.Editor,
        modes: Map<String, ChatNotifyMode>,
    ): SharedPreferences.Editor {
        val mentions = modes.filterValues { it == ChatNotifyMode.MENTIONS_ONLY }.keys
        val all = modes.filterValues { it == ChatNotifyMode.ALL }.keys
        return editor.putStringSet(KEY_MENTION_ONLY_CONVERSATIONS, mentions).putStringSet(KEY_ALL_CONVERSATIONS, all)
    }

    fun setMode(
        accountRef: String,
        groupIdHex: String,
        mode: ChatNotifyMode,
    ) = setNotifyForMode(accountRef, groupIdHex, mode)

    /** Legacy values are inputs only; callers must confirm the MDK result before clearing one. */
    internal fun legacyMuteEntries(): List<LegacyMuteEntry> {
        val expiries = readMuteExpiries(preferences)
        return readMutedSet(preferences).mapNotNull { key ->
            val separator = key.lastIndexOf(COMPOSITE_SEPARATOR)
            if (separator <= 0 || separator == key.lastIndex) return@mapNotNull null
            val expiry = expiries[key]
            LegacyMuteEntry(
                key = key,
                accountRef = key.substring(0, separator),
                groupIdHex = key.substring(separator + 1),
                expiryMillis = expiry?.expiryMillis,
                restoreMode = expiry?.restoreMode?.takeUnless { it == ChatNotifyMode.NONE } ?: ChatNotifyMode.ALL,
            )
        }
    }

    internal fun confirmLegacyMuteMigrated(key: String) {
        synchronized(mutationLock) {
            val remainingMuted = readMutedSet(preferences) - key
            val remainingExpiries = readMuteExpiries(preferences) - key
            preferences
                .edit()
                .putStringSet(KEY_MUTED_CONVERSATIONS, remainingMuted)
                .putStringSet(KEY_MUTE_EXPIRIES, remainingExpiries.map(::encodeMuteExpiry).toSet())
                .apply()
        }
    }

    internal companion object {
        private const val PREFERENCES_NAME = "whitenoise.chat_mute"
        private const val KEY_DEFAULTS_MIGRATED = "groupDefaultsMigrated"
        private const val KEY_ALL_CONVERSATIONS = "allConversations"
        private const val KEY_MUTED_CONVERSATIONS = "mutedConversations"
        private const val KEY_MENTION_ONLY_CONVERSATIONS = "mentionOnlyConversations"
        private const val KEY_MUTE_EXPIRIES = "muteExpiries"
        private const val EXPIRY_FIELD_SEPARATOR = "\u0000"
        private const val EXPIRY_FIELD_COUNT = 3
        private const val COMPOSITE_SEPARATOR = '|'

        fun encodeMuteExpiry(entry: Map.Entry<String, MuteExpiry>): String {
            val expiryField = entry.value.expiryMillis?.toString() ?: ""
            return listOf(expiryField, entry.value.restoreMode.name, entry.key).joinToString(EXPIRY_FIELD_SEPARATOR)
        }

        fun readMuteExpiries(preferences: SharedPreferences): Map<String, MuteExpiry> =
            preferences
                .getStringSet(KEY_MUTE_EXPIRIES, emptySet())
                .orEmpty()
                .mapNotNull(::decodeMuteExpiry)
                .toMap()

        private fun decodeMuteExpiry(encoded: String): Pair<String, MuteExpiry>? {
            val fields = encoded.split(EXPIRY_FIELD_SEPARATOR, limit = EXPIRY_FIELD_COUNT)
            return fields.takeIf { it.size == EXPIRY_FIELD_COUNT }?.let { validFields ->
                val expiry = validFields[0].takeIf(String::isNotEmpty)?.toLongOrNull()
                val expiryIsValid = validFields[0].isEmpty() || expiry != null
                val restore =
                    ChatNotifyMode.entries.firstOrNull { it.name == validFields[1] }
                        ?: validFields[1].toIntOrNull()?.let(ChatNotifyMode.entries::getOrNull)
                if (expiryIsValid && restore != null) {
                    validFields[2] to MuteExpiry(expiry, restore)
                } else {
                    null
                }
            }
        }

        fun compositeKey(
            accountRef: String,
            groupIdHex: String,
        ): String = "$accountRef$COMPOSITE_SEPARATOR$groupIdHex"

        fun compositeKeyOrNull(
            accountRef: String?,
            groupIdHex: String?,
        ): String? {
            val account = accountRef?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val group = groupIdHex?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return compositeKey(account, group)
        }

        fun readMutedSet(preferences: SharedPreferences): Set<String> = preferences.getStringSet(KEY_MUTED_CONVERSATIONS, emptySet())?.toSet().orEmpty()

        fun readNotificationModes(preferences: SharedPreferences): Map<String, ChatNotifyMode> =
            preferences.getStringSet(KEY_ALL_CONVERSATIONS, emptySet()).orEmpty().associateWith { ChatNotifyMode.ALL } +
                preferences
                    .getStringSet(KEY_MENTION_ONLY_CONVERSATIONS, emptySet())
                    .orEmpty()
                    .associateWith { ChatNotifyMode.MENTIONS_ONLY }
    }
}
