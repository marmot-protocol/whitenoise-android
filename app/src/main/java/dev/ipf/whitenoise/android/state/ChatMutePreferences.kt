package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-account, per-conversation mute state for local notification suppression
 * (#1179). Android notification preference — not White Noise protocol data.
 */
class ChatMutePreferences(
    context: Context,
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val _mutedConversations = MutableStateFlow(readMutedSet())
    val mutedConversations: StateFlow<Set<String>> = _mutedConversations.asStateFlow()

    fun isMuted(
        accountRef: String,
        groupIdHex: String,
    ): Boolean = compositeKey(accountRef, groupIdHex) in _mutedConversations.value

    fun setMuted(
        accountRef: String,
        groupIdHex: String,
        muted: Boolean,
    ) {
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return
        val updated =
            _mutedConversations.value.toMutableSet().apply {
                if (muted) add(key) else remove(key)
            }
        if (updated == _mutedConversations.value) return
        _mutedConversations.value = updated
        preferences.edit().putStringSet(KEY_MUTED_CONVERSATIONS, updated.toSet()).apply()
    }

    internal companion object {
        private const val PREFERENCES_NAME = "whitenoise.chat_mute"
        private const val KEY_MUTED_CONVERSATIONS = "mutedConversations"

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
    }

    private fun readMutedSet(): Set<String> = Companion.readMutedSet(preferences)
}
