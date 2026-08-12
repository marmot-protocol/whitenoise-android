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

    fun mode(
        accountRef: String,
        groupIdHex: String,
    ): ChatNotifyMode =
        compositeKeyOrNull(accountRef, groupIdHex)?.let(_state.value.notificationModes::get)
            ?: ChatNotifyMode.ALL

    fun restoreNotifyMode(
        accountRef: String,
        groupIdHex: String,
    ): ChatNotifyMode = mode(accountRef, groupIdHex)

    fun setNotifyForMode(
        accountRef: String,
        groupIdHex: String,
        mode: ChatNotifyMode,
    ) {
        if (mode == ChatNotifyMode.NONE) return
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return
        synchronized(mutationLock) {
            val updated = _state.value.notificationModes.toMutableMap()
            if (mode == ChatNotifyMode.ALL) updated.remove(key) else updated[key] = mode
            if (updated == _state.value.notificationModes) return
            _state.value = ChatNotificationState(updated.toMap())
            preferences
                .edit()
                .putStringSet(KEY_MENTION_ONLY_CONVERSATIONS, updated.filterValues { it == ChatNotifyMode.MENTIONS_ONLY }.keys)
                .apply()
        }
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
            if (fields.size != EXPIRY_FIELD_COUNT) return null
            val expiry = fields[0].takeIf(String::isNotEmpty)?.toLongOrNull()
            if (fields[0].isNotEmpty() && expiry == null) return null
            val restore =
                ChatNotifyMode.entries.firstOrNull { it.name == fields[1] }
                    ?: fields[1].toIntOrNull()?.let(ChatNotifyMode.entries::getOrNull)
                    ?: return null
            return fields[2] to MuteExpiry(expiry, restore)
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
            preferences
                .getStringSet(KEY_MENTION_ONLY_CONVERSATIONS, emptySet())
                .orEmpty()
                .associateWith { ChatNotifyMode.MENTIONS_ONLY }
    }
}
