package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stable, engine-scoped Android TTS voice choices; never protocol data. */
class TtsVoicePreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val _selectedVoices = MutableStateFlow(readAll())
    val selectedVoices: StateFlow<Map<String, TtsVoiceKey>> = _selectedVoices.asStateFlow()

    /** Returns only a key that still belongs to the requested engine. */
    fun selectedVoice(enginePackage: String): TtsVoiceKey? {
        val selected = _selectedVoices.value[enginePackage]
        return selected?.takeIf { it.enginePackage == enginePackage }
    }

    /** Saves or clears one engine without disturbing choices for other engines. */
    fun setSelectedVoice(
        enginePackage: String,
        voice: TtsVoiceKey?,
    ) {
        if (voice != null && voice.enginePackage != enginePackage) return
        val updated = _selectedVoices.value.toMutableMap()
        if (voice == null) updated.remove(enginePackage) else updated[enginePackage] = voice
        if (updated == _selectedVoices.value) return
        _selectedVoices.value = updated.toMap()
        val editor = preferences.edit()
        if (voice == null) {
            editor.remove(preferenceKey(enginePackage))
        } else {
            editor.putString(preferenceKey(enginePackage), encode(voice))
        }
        editor.apply()
    }

    /** Ignores malformed restored values rather than guessing a voice identity. */
    private fun readAll(): Map<String, TtsVoiceKey> =
        preferences.all
            .mapNotNull { (key, value) ->
                val enginePackage =
                    key.removePrefix(KEY_PREFIX).takeIf { key.startsWith(KEY_PREFIX) }
                        ?: return@mapNotNull null
                decode(enginePackage, value as? String)?.let { enginePackage to it }
            }.toMap()

    /** Keeps every selection physically scoped to its engine package. */
    private fun preferenceKey(enginePackage: String) = "$KEY_PREFIX$enginePackage"

    /** Length-prefixes the name so engine-controlled punctuation stays lossless. */
    private fun encode(voice: TtsVoiceKey): String = "${voice.voiceName.length}:${voice.voiceName}${voice.localeTag}"

    /** Decodes the length-prefixed voice name without delimiter ambiguity. */
    private fun decode(
        enginePackage: String,
        stored: String?,
    ): TtsVoiceKey? =
        stored?.let { value ->
            val separator = value.indexOf(':')
            val nameLength = value.take(separator.coerceAtLeast(0)).toIntOrNull()
            if (separator <= 0 || nameLength == null) {
                null
            } else {
                val payload = value.substring(separator + 1)
                val validLength = nameLength in 1 until payload.length
                val name = payload.take(nameLength.coerceIn(0, payload.length))
                val localeTag = payload.drop(nameLength.coerceIn(0, payload.length))
                TtsVoiceKey(enginePackage, name, localeTag)
                    .takeIf { validLength && name.isNotBlank() && localeTag.isNotBlank() }
            }
        }

    internal companion object {
        internal const val PREFERENCES_NAME = "whitenoise.tts_voice"
        private const val KEY_PREFIX = "voice."
    }
}
