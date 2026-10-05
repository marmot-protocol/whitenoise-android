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

    /** Explicit account/group choices win; every new conversation starts with all messages. */
    fun mode(
        accountRef: String,
        groupIdHex: String,
    ): ChatNotifyMode =
        compositeKeyOrNull(accountRef, groupIdHex)?.let(_state.value.notificationModes::get)
            ?: ChatNotifyMode.ALL

    /** Returns the delivery preference retained beneath the independent native mute. */
    fun restoreNotifyMode(
        accountRef: String,
        groupIdHex: String,
    ): ChatNotifyMode = mode(accountRef, groupIdHex)

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

    /** Erases this identity's current and pending legacy choices together, retrying transient disk failures. */
    internal fun removeAccount(accountRef: String) {
        val account = accountRef.trim().takeIf(String::isNotEmpty) ?: return
        synchronized(mutationLock) {
            val updated =
                _state.value.notificationModes.filterKeys { it.substringBeforeLast(COMPOSITE_SEPARATOR) != account }
            val legacyMuted =
                readMutedSet(preferences).filterNot { it.substringBeforeLast(COMPOSITE_SEPARATOR) == account }
            val legacyExpiries =
                preferences.getStringSet(KEY_MUTE_EXPIRIES, emptySet()).orEmpty().filterNot { encoded ->
                    encoded
                        .split(EXPIRY_FIELD_SEPARATOR, limit = EXPIRY_FIELD_COUNT)
                        .getOrNull(EXPIRY_FIELD_COUNT - 1)
                        ?.substringBeforeLast(COMPOSITE_SEPARATOR) == account
                }
            // Recreate each transaction even when a failed commit already changed preference
            // memory. Bound retries so persistent disk failure cannot stall native-wipe cleanup.
            val persisted =
                (1..ACCOUNT_REMOVAL_ATTEMPTS).any {
                    persistModes(preferences.edit(), updated)
                        .putStringSet(KEY_MUTED_CONVERSATIONS, legacyMuted.toSet())
                        .putStringSet(KEY_MUTE_EXPIRIES, legacyExpiries.toSet())
                        .commit()
                }
            if (!persisted) {
                android.util.Log.w("ChatMutePreferences", "Could not persist erased account notification choices")
            }
            _state.value = ChatNotificationState(updated)
        }
    }

    /** Persists both explicit delivery choices independently of native mute state. */
    private fun persistModes(
        editor: SharedPreferences.Editor,
        modes: Map<String, ChatNotifyMode>,
    ): SharedPreferences.Editor {
        val mentions = modes.filterValues { it == ChatNotifyMode.MENTIONS_ONLY }.keys
        val all = modes.filterValues { it == ChatNotifyMode.ALL }.keys
        return editor.putStringSet(KEY_MENTION_ONLY_CONVERSATIONS, mentions).putStringSet(KEY_ALL_CONVERSATIONS, all)
    }

    /** Compatibility command entry point; NONE never overwrites the saved delivery choice. */
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
        private const val KEY_ALL_CONVERSATIONS = "allConversations"
        private const val KEY_MUTED_CONVERSATIONS = "mutedConversations"
        private const val KEY_MENTION_ONLY_CONVERSATIONS = "mentionOnlyConversations"
        private const val KEY_MUTE_EXPIRIES = "muteExpiries"
        private const val EXPIRY_FIELD_SEPARATOR = "\u0000"
        private const val EXPIRY_FIELD_COUNT = 3
        private const val COMPOSITE_SEPARATOR = '|'
        private const val ACCOUNT_REMOVAL_ATTEMPTS = 3

        /** Preserves the legacy expiry format, including an empty field for indefinite mute. */
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

        /** Joins a public local identity label and group id for a preference lookup; contains no credentials. */
        fun compositeKey(
            identityLabel: String,
            groupIdHex: String,
        ): String = "$identityLabel$COMPOSITE_SEPARATOR$groupIdHex"

        /** Normalizes the host label/id tuple while retaining the existing on-disk preference format. */
        fun compositeKeyOrNull(
            identityLabel: String?,
            groupIdHex: String?,
        ): String? {
            val label = identityLabel?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val group = groupIdHex?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return compositeKey(label, group)
        }

        /** Returns a defensive snapshot of legacy mutes awaiting authoritative native confirmation. */
        fun readMutedSet(preferences: SharedPreferences): Set<String> =
            preferences
                .getStringSet(KEY_MUTED_CONVERSATIONS, emptySet())
                ?.toSet()
                .orEmpty()

        /** Restores both explicit choices; a legacy mentions entry wins if a corrupt store lists both. */
        fun readNotificationModes(preferences: SharedPreferences): Map<String, ChatNotifyMode> =
            preferences.getStringSet(KEY_ALL_CONVERSATIONS, emptySet()).orEmpty().associateWith { ChatNotifyMode.ALL } +
                preferences
                    .getStringSet(KEY_MENTION_ONLY_CONVERSATIONS, emptySet())
                    .orEmpty()
                    .associateWith { ChatNotifyMode.MENTIONS_ONLY }
    }
}
