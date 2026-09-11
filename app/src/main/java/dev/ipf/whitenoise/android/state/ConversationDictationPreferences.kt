package dev.ipf.whitenoise.android.state

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.pm.PackageInfoCompat
import dev.ipf.whitenoise.android.audio.ConversationDictationDeliveryMode
import dev.ipf.whitenoise.android.audio.ConversationDictationProviderChoice
import dev.ipf.whitenoise.android.audio.conversationDictationRecognitionServiceComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal data class ConversationDictationPreferenceState(
    val finishAfterSilenceMillis: Long?,
    val deliveryMode: ConversationDictationDeliveryMode,
    val recognitionServiceOverride: ComponentName? = null,
    val providerSelection: ConversationDictationProviderChoice? = null,
)

/** Local-only endpointing and result behavior for composer dictation. */
internal class ConversationDictationPreferences(
    private val context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<ConversationDictationPreferenceState> = _state.asStateFlow()

    /** Returns the immutable values that a newly created dictation target must capture. */
    fun current(): ConversationDictationPreferenceState = readState().also { _state.value = it }

    fun setProviderSelection(value: ConversationDictationProviderChoice?) {
        val stored =
            value?.copy(
                callerAudio = dev.ipf.whitenoise.android.audio.ConversationDictationCallerAudioRequirement.Unknown,
            )
        update(current().copy(providerSelection = stored, recognitionServiceOverride = stored?.service))
    }

    /** Persists manual finish for null/unsupported values or one of the supported silence thresholds. */
    fun setFinishAfterSilenceMillis(value: Long?) {
        val normalized = value?.takeIf(ALLOWED_SILENCE_MILLIS::contains)
        if (current().finishAfterSilenceMillis == normalized) return
        update(current().copy(finishAfterSilenceMillis = normalized))
    }

    /** Persists the user's explicit paste-or-send terminal behavior. */
    fun setDeliveryMode(value: ConversationDictationDeliveryMode) {
        if (current().deliveryMode == value) return
        update(current().copy(deliveryMode = value))
    }

    /** Persists an explicit service identity, or clears it to follow Android's system default. */
    fun setRecognitionServiceOverride(value: ComponentName?) {
        val selection =
            value?.let { component ->
                runCatching {
                    ConversationDictationProviderChoice(
                        packageName = component.packageName,
                        versionCode =
                            PackageInfoCompat.getLongVersionCode(
                                context.packageManager.getPackageInfo(component.packageName, 0),
                            ),
                        appName = component.packageName,
                        engineName = component.className,
                        service = component,
                    )
                }.getOrNull()
            }
        setProviderSelection(selection)
    }

    /** Publishes and persists both fields as one coherent preference snapshot. */
    private fun update(value: ConversationDictationPreferenceState) {
        _state.value = value
        preferences
            .edit()
            .putLong(KEY_FINISH_AFTER_SILENCE, value.finishAfterSilenceMillis ?: MANUAL_FINISH)
            .putString(KEY_DELIVERY_MODE, value.deliveryMode.name)
            .remove(KEY_RECOGNITION_SERVICE_OVERRIDE)
            .putString(KEY_PROVIDER_SELECTION, value.providerSelection?.let(::encodeSelection))
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
        val selection = decodeSelection(preferences.getString(KEY_PROVIDER_SELECTION, null))
        return ConversationDictationPreferenceState(silence, delivery, selection?.service, selection)
    }

    private fun encodeSelection(value: ConversationDictationProviderChoice): String =
        JSONObject()
            .apply {
                put("package", value.packageName)
                put("version", value.versionCode)
                put("appName", value.appName)
                put("engineName", value.engineName)
                value.service?.let { put("service", it.flattenToString()) }
                value.activity?.let { put("activity", it.flattenToString()) }
                value.keyboard?.let { put("keyboard", it.flattenToString()) }
            }.toString()

    private fun decodeSelection(value: String?): ConversationDictationProviderChoice? =
        runCatching {
            val json = JSONObject(value ?: return@runCatching null)
            val pkg = json.getString("package")
            require(pkg.isNotBlank())
            require(json.get("version") is Int || json.get("version") is Long)
            val version = json.getLong("version")
            require(version >= 0)

            fun component(key: String): ComponentName? {
                if (!json.has(key)) return null
                return requireNotNull(conversationDictationRecognitionServiceComponent(json.getString(key))).also {
                    require(it.packageName == pkg)
                }
            }
            val service = component("service")
            val activity = component("activity")
            val keyboard = component("keyboard")
            require(service != null || activity != null || keyboard != null)
            ConversationDictationProviderChoice(
                pkg,
                version,
                json.getString("appName"),
                json.getString("engineName"),
                service,
                activity,
                keyboard,
            )
        }.getOrNull()

    internal companion object {
        val ALLOWED_SILENCE_MILLIS = setOf(3_000L, 5_000L, 10_000L)
        private const val PREFERENCES_NAME = "whitenoise.composer_dictation"
        private const val KEY_FINISH_AFTER_SILENCE = "finishAfterSilenceMillis"
        private const val KEY_DELIVERY_MODE = "deliveryMode"
        private const val KEY_PROVIDER_SELECTION = "providerSelection"
        private const val KEY_RECOGNITION_SERVICE_OVERRIDE = "recognitionServiceOverride"
        private const val MANUAL_FINISH = -1L
    }
}
