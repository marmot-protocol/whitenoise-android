package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import dev.ipf.whitenoise.android.audio.ConversationDictationDeliveryMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ConversationDictationPreferenceState(
    val finishAfterSilenceMillis: Long?,
    val deliveryMode: ConversationDictationDeliveryMode,
)

/** Local-only endpointing and result behavior for composer dictation. */
internal class ConversationDictationPreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<ConversationDictationPreferenceState> = _state.asStateFlow()

    /** Returns the immutable values that a newly created dictation target must capture. */
    fun current(): ConversationDictationPreferenceState = _state.value

    /** Persists manual finish for null/unsupported values or one of the supported silence thresholds. */
    fun setFinishAfterSilenceMillis(value: Long?) {
        val normalized = value?.takeIf(ALLOWED_SILENCE_MILLIS::contains)
        if (_state.value.finishAfterSilenceMillis == normalized) return
        update(_state.value.copy(finishAfterSilenceMillis = normalized))
    }

    /** Persists the user's explicit paste-or-send terminal behavior. */
    fun setDeliveryMode(value: ConversationDictationDeliveryMode) {
        if (_state.value.deliveryMode == value) return
        update(_state.value.copy(deliveryMode = value))
    }

    /** Publishes and persists both fields as one coherent preference snapshot. */
    private fun update(value: ConversationDictationPreferenceState) {
        _state.value = value
        preferences
            .edit()
            .putLong(KEY_FINISH_AFTER_SILENCE, value.finishAfterSilenceMillis ?: MANUAL_FINISH)
            .putString(KEY_DELIVERY_MODE, value.deliveryMode.name)
            .apply()
    }

    /** Reads preferences fail-closed to manual finish and paste-to-draft. */
    private fun readState(): ConversationDictationPreferenceState {
        val silence =
            preferences
                .getLong(KEY_FINISH_AFTER_SILENCE, MANUAL_FINISH)
                .takeIf(ALLOWED_SILENCE_MILLIS::contains)
        val delivery =
            preferences
                .getString(KEY_DELIVERY_MODE, null)
                ?.let { stored -> ConversationDictationDeliveryMode.entries.firstOrNull { it.name == stored } }
                ?: ConversationDictationDeliveryMode.PasteIntoDraft
        return ConversationDictationPreferenceState(silence, delivery)
    }

    internal companion object {
        val ALLOWED_SILENCE_MILLIS = setOf(3_000L, 5_000L, 10_000L)
        private const val PREFERENCES_NAME = "whitenoise.composer_dictation"
        private const val KEY_FINISH_AFTER_SILENCE = "finishAfterSilenceMillis"
        private const val KEY_DELIVERY_MODE = "deliveryMode"
        private const val MANUAL_FINISH = -1L
    }
}
