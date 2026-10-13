package dev.ipf.whitenoise.android.translation

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class TranslationOverride { INHERIT, ON, OFF }

internal data class TranslationChoice(
    val enabled: Boolean,
    val target: String,
)

/** Platform choices only: no source text, rendition, error, revision or request identity is persisted. */
internal class TranslationPreferences(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            "translation_choices",
            Context.MODE_PRIVATE,
        )
    private val revision = MutableStateFlow(0)
    val changes = revision.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision.value += 1 }

    init {
        resetLegacyAutomaticChoices()
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Earlier previews enabled automatic translation without the provider logging notice. */
    private fun resetLegacyAutomaticChoices() {
        val noticeVersion = runCatching { preferences.getInt(PRIVACY_NOTICE_VERSION, 0) }.getOrDefault(0)
        if (noticeVersion >= CURRENT_PRIVACY_NOTICE) return
        val edit = preferences.edit().putInt(PRIVACY_NOTICE_VERSION, CURRENT_PRIVACY_NOTICE)
        if (preferences.contains("global")) edit.putBoolean("global", false)
        preferences.all.forEach { (key, value) ->
            if (key.startsWith("chat:") && !key.endsWith(":target") && value == TranslationOverride.ON.name) {
                edit.putString(key, TranslationOverride.OFF.name)
            }
        }
        edit.apply()
    }

    fun close() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun global(): TranslationChoice = TranslationChoice(boolean("global"), language("target") ?: "en")

    fun targetOverride(
        account: String,
        group: String,
    ): String? = language(key(account, group) + ":target")

    fun override(
        account: String,
        group: String,
    ): TranslationOverride =
        runCatching { TranslationOverride.valueOf(preferences.getString(key(account, group), null).orEmpty()) }
            .getOrDefault(TranslationOverride.INHERIT)

    fun effective(
        account: String,
        group: String,
    ): TranslationChoice {
        val global = global()
        val enabled =
            when (override(account, group)) {
                TranslationOverride.INHERIT -> global.enabled
                TranslationOverride.ON -> true
                TranslationOverride.OFF -> false
            }
        return TranslationChoice(enabled, language(key(account, group) + ":target") ?: global.target)
    }

    fun setGlobal(
        enabled: Boolean,
        target: String,
    ) {
        require(translationLanguageValid(target))
        preferences
            .edit()
            .putBoolean("global", enabled)
            .putString("target", target)
            .apply()
    }

    fun setOverride(
        account: String,
        group: String,
        override: TranslationOverride,
        target: String?,
    ) {
        require(target == null || translationLanguageValid(target))
        val edit = preferences.edit()
        val key = key(account, group)
        if (override == TranslationOverride.INHERIT) edit.remove(key) else edit.putString(key, override.name)
        if (target == null) edit.remove("$key:target") else edit.putString("$key:target", target)
        edit.apply()
    }

    private fun boolean(key: String): Boolean = runCatching { preferences.getBoolean(key, false) }.getOrDefault(false)

    private fun language(key: String): String? =
        runCatching {
            preferences.getString(key, null)?.takeIf(::translationLanguageValid)
        }.getOrNull()

    private fun key(
        account: String,
        group: String,
    ): String {
        require(account.isNotBlank() && group.isNotBlank())
        return "chat:${account.length}:$account:$group"
    }

    private companion object {
        const val PRIVACY_NOTICE_VERSION = "privacy_notice_version"
        const val CURRENT_PRIVACY_NOTICE = 1
    }
}
