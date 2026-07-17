package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ChatNotifyMode {
    ALL,
    MENTIONS_ONLY,
    NONE,
}

data class ChatNotificationState(
    val notificationModes: Map<String, ChatNotifyMode>,
) {
    val mutedConversations: Set<String> = ChatMutePreferences.mutedKeysOf(notificationModes)
}

/**
 * Per-account, per-conversation notification mode (#1179, #1252).
 * Android notification preference — not White Noise protocol data.
 */
class ChatMutePreferences(
    context: Context,
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val mutationLock = Any()
    private val _state = MutableStateFlow(ChatNotificationState(readNotificationModes(preferences)))
    val state: StateFlow<ChatNotificationState> = _state.asStateFlow()

    fun mode(
        accountRef: String,
        groupIdHex: String,
    ): ChatNotifyMode {
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return ChatNotifyMode.ALL
        return _state.value.notificationModes[key] ?: ChatNotifyMode.ALL
    }

    fun isMuted(
        accountRef: String,
        groupIdHex: String,
    ): Boolean = mode(accountRef, groupIdHex) == ChatNotifyMode.NONE

    fun setMode(
        accountRef: String,
        groupIdHex: String,
        mode: ChatNotifyMode,
    ) {
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return
        synchronized(mutationLock) {
            val current = _state.value.notificationModes
            val updated =
                current.toMutableMap().apply {
                    if (mode == ChatNotifyMode.ALL) remove(key) else put(key, mode)
                }
            if (updated == current) return
            val immutableModes = updated.toMap()
            _state.value = ChatNotificationState(immutableModes)
            preferences
                .edit()
                .putStringSet(
                    KEY_MUTED_CONVERSATIONS,
                    immutableModes.filterValues { it == ChatNotifyMode.NONE }.keys,
                ).putStringSet(
                    KEY_MENTION_ONLY_CONVERSATIONS,
                    immutableModes.filterValues { it == ChatNotifyMode.MENTIONS_ONLY }.keys,
                ).apply()
        }
    }

    fun setMuted(
        accountRef: String,
        groupIdHex: String,
        muted: Boolean,
    ) {
        setMode(
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            mode = if (muted) ChatNotifyMode.NONE else ChatNotifyMode.ALL,
        )
    }

    internal companion object {
        private const val PREFERENCES_NAME = "whitenoise.chat_mute"
        private const val KEY_MUTED_CONVERSATIONS = "mutedConversations"
        private const val KEY_MENTION_ONLY_CONVERSATIONS = "mentionOnlyConversations"

        fun compositeKey(
            accountRef: String,
            groupIdHex: String,
        ): String = "$accountRef|$groupIdHex"

        fun compositeKeyOrNull(
            accountRef: String?,
            groupIdHex: String?,
        ): String? {
            val account = accountRef?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val group = groupIdHex?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return compositeKey(account, group)
        }

        fun readMutedSet(preferences: SharedPreferences): Set<String> = preferences.getStringSet(KEY_MUTED_CONVERSATIONS, emptySet())?.toSet() ?: emptySet()

        fun mutedKeysOf(modes: Map<String, ChatNotifyMode>): Set<String> = modes.filterValues { it == ChatNotifyMode.NONE }.keys.toSet()

        fun readNotificationModes(preferences: SharedPreferences): Map<String, ChatNotifyMode> =
            buildMap {
                preferences
                    .getStringSet(KEY_MENTION_ONLY_CONVERSATIONS, emptySet())
                    .orEmpty()
                    .forEach { put(it, ChatNotifyMode.MENTIONS_ONLY) }
                // The existing mute set remains the migration source of truth.
                // If corrupt preferences contain a key in both sets, NONE wins.
                readMutedSet(preferences).forEach { put(it, ChatNotifyMode.NONE) }
            }
    }
}
