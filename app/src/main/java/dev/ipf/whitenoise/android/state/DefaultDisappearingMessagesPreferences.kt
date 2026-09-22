package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import dev.ipf.whitenoise.android.ui.group.disappearingCustomUnits
import dev.ipf.whitenoise.android.ui.group.disappearingPresetSecs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device-local, account-scoped defaults used only when founding a new chat.
 * MDK remains authoritative for every created group's current retention policy.
 */
internal class DefaultDisappearingMessagesPreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val mutationLock = Any()
    private val _durations = MutableStateFlow(loadDurations())
    val durations: StateFlow<Map<String, Long>> = _durations.asStateFlow()

    fun durationFor(
        accountRef: String?,
        snapshot: Map<String, Long> = _durations.value,
    ): Long {
        val key = preferenceKey(accountRef) ?: return OFF_SECONDS
        return snapshot[key] ?: OFF_SECONDS
    }

    /** Saves Off explicitly and rejects values the shared picker cannot produce. */
    fun setDuration(
        accountRef: String?,
        seconds: Long,
    ): Boolean {
        val key = preferenceKey(accountRef)
        if (key == null || !isValidDisappearingMessageDurationSeconds(seconds)) return false
        synchronized(mutationLock) {
            if (_durations.value[key] != seconds || !preferences.contains(key)) {
                preferences.edit().putLong(key, seconds).apply()
                _durations.value = _durations.value + (key to seconds)
            }
        }
        return true
    }

    /** Removes only this device-local account setting after a destructive account wipe. */
    fun removeAccount(accountRef: String?) {
        val key = preferenceKey(accountRef) ?: return
        synchronized(mutationLock) {
            preferences.edit().remove(key).apply()
            _durations.value = _durations.value - key
        }
    }

    private fun loadDurations(): Map<String, Long> =
        preferences.all
            .mapNotNull { (key, value) ->
                val seconds = value as? Long
                if (
                    key.startsWith(KEY_PREFIX) &&
                    seconds != null &&
                    isValidDisappearingMessageDurationSeconds(seconds)
                ) {
                    key to seconds
                } else {
                    null
                }
            }.toMap()

    internal companion object {
        internal const val PREFERENCES_NAME = "whitenoise"
        internal const val OFF_SECONDS = 0L
        private const val KEY_PREFIX = "default_disappearing_messages:"

        internal fun preferenceKey(accountRef: String?): String? {
            val account = accountRef?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return "$KEY_PREFIX${account.length}:$account"
        }
    }
}

/** True for Off or a positive duration representable by the existing preset/custom picker. */
internal fun isValidDisappearingMessageDurationSeconds(seconds: Long): Boolean =
    seconds in disappearingPresetSecs ||
        disappearingCustomUnits.any { unit ->
            seconds > 0L &&
                seconds % unit.seconds == 0L &&
                seconds / unit.seconds in 1L..unit.max.toLong()
        }
