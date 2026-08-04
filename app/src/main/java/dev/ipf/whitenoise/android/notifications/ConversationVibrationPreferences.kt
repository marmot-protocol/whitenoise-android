package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** A bounded set of portable notification vibration choices. */
enum class ConversationVibrationPattern(
    internal val channelToken: String,
    internal val waveform: LongArray?,
) {
    SYSTEM_DEFAULT(channelToken = "default", waveform = null),
    SHORT(channelToken = "short", waveform = longArrayOf(0L, 100L)),
    DOUBLE(channelToken = "double", waveform = longArrayOf(0L, 100L, 100L, 100L)),
    LONG(channelToken = "long", waveform = longArrayOf(0L, 400L)),
}

/** Device-local, per-account/per-conversation vibration selection. */
class ConversationVibrationPreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val _state = MutableStateFlow(readSelections(preferences))
    val state: StateFlow<Map<String, ConversationVibrationPattern>> = _state.asStateFlow()

    fun pattern(
        accountRef: String,
        groupIdHex: String,
    ): ConversationVibrationPattern {
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return ConversationVibrationPattern.SYSTEM_DEFAULT
        // Other notification entry points (workers and the long-lived local
        // presenter) may own a different store instance. SharedPreferences is
        // already memory-backed, so read it here to observe cross-instance UI
        // changes without a process restart.
        return readSelections(preferences)[key] ?: ConversationVibrationPattern.SYSTEM_DEFAULT
    }

    fun setPattern(
        accountRef: String,
        groupIdHex: String,
        pattern: ConversationVibrationPattern,
    ) {
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return
        synchronized(mutationLock) {
            // Multiple process-lifetime entry points own store instances. Use
            // the shared lock and latest persisted snapshot for this whole-set
            // read-modify-write so one instance cannot erase another's update.
            val current = readSelections(preferences)
            val updated =
                if (pattern == ConversationVibrationPattern.SYSTEM_DEFAULT) {
                    current - key
                } else {
                    current + (key to pattern)
                }
            if (updated == current) {
                _state.value = current
                return
            }
            _state.value = updated
            preferences.edit().putStringSet(KEY_SELECTIONS, updated.map(::encodeSelection).toSet()).apply()
        }
    }

    internal companion object {
        private const val PREFERENCES_NAME = "whitenoise.conversation_vibration"
        private const val KEY_SELECTIONS = "selections"
        private const val FIELD_SEPARATOR = "\u0000"
        private val mutationLock = Any()

        fun compositeKeyOrNull(
            accountRef: String?,
            groupIdHex: String?,
        ): String? {
            val account = accountRef?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val group =
                groupIdHex
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.lowercase(Locale.ROOT)
                    ?: return null
            return "$account|$group"
        }

        private fun encodeSelection(entry: Map.Entry<String, ConversationVibrationPattern>): String = "${entry.value.name}$FIELD_SEPARATOR${entry.key}"

        private fun readSelections(preferences: SharedPreferences): Map<String, ConversationVibrationPattern> =
            preferences
                .getStringSet(KEY_SELECTIONS, emptySet())
                .orEmpty()
                .mapNotNull { encoded ->
                    val fields = encoded.split(FIELD_SEPARATOR, limit = 2)
                    if (fields.size != 2) return@mapNotNull null
                    val pattern = ConversationVibrationPattern.entries.firstOrNull { it.name == fields[0] }
                    pattern?.takeUnless { it == ConversationVibrationPattern.SYSTEM_DEFAULT }?.let { fields[1] to it }
                }.toMap()
    }
}
