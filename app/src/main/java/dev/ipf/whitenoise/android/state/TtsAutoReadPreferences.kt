package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global default and per-chat overrides for auto-reading the unread backlog on
 * open. Device-local UI state — never engine or shared group data.
 */
@Suppress("TooManyFunctions") // Cohesive preference store + migration.
class TtsAutoReadPreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val mutationLock = Any()
    private val _state = MutableStateFlow(loadStateLocked())
    val state: StateFlow<TtsAutoReadPreferenceState> = _state.asStateFlow()

    fun isConversationAutoRead(
        accountRef: String,
        groupIdHex: String,
    ): Boolean {
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return false
        val snapshot = _state.value
        return resolveConversationAutoRead(snapshot.globalDefaultEnabled, snapshot.overrides[key])
    }

    fun overrideFor(
        accountRef: String,
        groupIdHex: String,
    ): TtsAutoReadOverride? {
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return null
        return _state.value.overrides[key]
    }

    fun setGlobalDefaultEnabled(enabled: Boolean) {
        synchronized(mutationLock) {
            val current = _state.value
            if (current.globalDefaultEnabled == enabled) return
            publishLocked(current.copy(globalDefaultEnabled = enabled))
            preferences.edit().putBoolean(KEY_GLOBAL_DEFAULT, enabled).apply()
        }
    }

    fun setConversationOverride(
        accountRef: String,
        groupIdHex: String,
        override: TtsAutoReadOverride,
    ) {
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return
        synchronized(mutationLock) {
            val current = _state.value
            if (current.overrides[key] == override) return
            val overrides = current.overrides.toMutableMap().apply { put(key, override) }
            publishLocked(current.copy(overrides = overrides))
            persistOverridesLocked(overrides)
        }
    }

    fun clearConversationOverride(
        accountRef: String,
        groupIdHex: String,
    ) {
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return
        synchronized(mutationLock) {
            val current = _state.value
            if (!current.overrides.containsKey(key)) return
            val overrides = current.overrides - key
            publishLocked(current.copy(overrides = overrides))
            persistOverridesLocked(overrides)
        }
    }

    internal fun preferencesForTest(): SharedPreferences = preferences

    private fun loadStateLocked(): TtsAutoReadPreferenceState {
        synchronized(mutationLock) {
            migrateLegacyBinaryLockedIfNeeded()
            val globalDefault = preferences.getBoolean(KEY_GLOBAL_DEFAULT, false)
            val overrides = readOverridesLocked()
            return TtsAutoReadPreferenceState(globalDefaultEnabled = globalDefault, overrides = overrides)
        }
    }

    private fun migrateLegacyBinaryLockedIfNeeded() {
        if (preferences.getBoolean(KEY_LEGACY_MIGRATED, false)) return
        val legacy = preferences.getStringSet(KEY_LEGACY_ENABLED, null)
        if (legacy == null) {
            preferences.edit().putBoolean(KEY_LEGACY_MIGRATED, true).apply()
            return
        }
        val migratedKeys = legacy.mapNotNull(::normalizeStoredKeyOrNull)
        val edit = preferences.edit()
        if (migratedKeys.isNotEmpty()) {
            val merged = readOverridesLocked().toMutableMap()
            migratedKeys.forEach { key -> merged.putIfAbsent(key, TtsAutoReadOverride.ON) }
            edit.putStringSet(
                KEY_OVERRIDE_ON,
                merged.filterValues { it == TtsAutoReadOverride.ON }.keys,
            )
            edit.putStringSet(
                KEY_OVERRIDE_OFF,
                merged.filterValues { it == TtsAutoReadOverride.OFF }.keys,
            )
        }
        edit.remove(KEY_LEGACY_ENABLED)
        edit.putBoolean(KEY_LEGACY_MIGRATED, true)
        edit.apply()
    }

    private fun readOverridesLocked(): Map<String, TtsAutoReadOverride> {
        val onKeys =
            preferences
                .getStringSet(KEY_OVERRIDE_ON, emptySet())
                .orEmpty()
                .mapNotNull { normalizeStoredKeyOrNull(it) }
        val offKeys =
            preferences
                .getStringSet(KEY_OVERRIDE_OFF, emptySet())
                .orEmpty()
                .mapNotNull { normalizeStoredKeyOrNull(it) }
        return buildMap {
            onKeys.forEach { put(it, TtsAutoReadOverride.ON) }
            // Corrupt prefs with the same key in both sets: OFF wins.
            offKeys.forEach { put(it, TtsAutoReadOverride.OFF) }
        }
    }

    private fun publishLocked(state: TtsAutoReadPreferenceState) {
        _state.value = state
    }

    private fun persistOverridesLocked(overrides: Map<String, TtsAutoReadOverride>) {
        val onKeys = overrides.filterValues { it == TtsAutoReadOverride.ON }.keys
        val offKeys = overrides.filterValues { it == TtsAutoReadOverride.OFF }.keys
        preferences
            .edit()
            .putStringSet(KEY_OVERRIDE_ON, onKeys)
            .putStringSet(KEY_OVERRIDE_OFF, offKeys)
            .apply()
    }

    private fun normalizeStoredKeyOrNull(encoded: String): String? {
        val separator = encoded.indexOf('|')
        if (separator <= 0 || separator == encoded.lastIndex) return null
        return compositeKeyOrNull(
            encoded.substring(0, separator),
            encoded.substring(separator + 1),
        )
    }

    internal companion object {
        internal const val PREFERENCES_NAME = "whitenoise.tts_auto_read"
        internal const val KEY_LEGACY_ENABLED = "autoReadConversations"
        private const val KEY_GLOBAL_DEFAULT = "globalDefaultEnabled"
        private const val KEY_OVERRIDE_ON = "overrideOnConversations"
        private const val KEY_OVERRIDE_OFF = "overrideOffConversations"
        private const val KEY_LEGACY_MIGRATED = "legacyBinaryMigrated"

        fun compositeKeyOrNull(
            accountRef: String,
            groupIdHex: String,
        ): String? {
            val account = accountRef.trim()
            val group = groupIdHex.trim().lowercase()
            return if (account.isEmpty() || group.isEmpty()) null else "$account|$group"
        }
    }
}
