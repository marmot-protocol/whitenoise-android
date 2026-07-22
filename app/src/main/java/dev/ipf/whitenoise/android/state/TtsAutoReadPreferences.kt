package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-chat auto-read of the unread backlog on open, keyed per account and per
 * group like mute. Default off. Device-local UI state — never engine or
 * shared group data.
 */
class TtsAutoReadPreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val mutationLock = Any()
    private val _enabledKeys =
        MutableStateFlow(preferences.getStringSet(KEY_ENABLED, emptySet()).orEmpty().toSet())
    val enabledKeys: StateFlow<Set<String>> = _enabledKeys.asStateFlow()

    fun isEnabled(
        accountRef: String,
        groupIdHex: String,
    ): Boolean = compositeKeyOrNull(accountRef, groupIdHex)?.let { it in _enabledKeys.value } == true

    fun setEnabled(
        accountRef: String,
        groupIdHex: String,
        enabled: Boolean,
    ) {
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return
        synchronized(mutationLock) {
            val current = _enabledKeys.value
            val updated = if (enabled) current + key else current - key
            if (updated == current) return
            _enabledKeys.value = updated
            preferences.edit().putStringSet(KEY_ENABLED, updated).apply()
        }
    }

    internal companion object {
        private const val PREFERENCES_NAME = "whitenoise.tts_auto_read"
        private const val KEY_ENABLED = "autoReadConversations"

        fun compositeKeyOrNull(
            accountRef: String,
            groupIdHex: String,
        ): String? {
            val account = accountRef.trim().takeIf { it.isNotEmpty() } ?: return null
            val group = groupIdHex.trim().lowercase().takeIf { it.isNotEmpty() } ?: return null
            return "$account|$group"
        }
    }
}
