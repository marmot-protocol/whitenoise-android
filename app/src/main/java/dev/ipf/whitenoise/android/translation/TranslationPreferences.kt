package dev.ipf.whitenoise.android.translation

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class TranslationOverride { INHERIT, ON, OFF }

/**
 * [target] is a reviewed engine target, or null when the saved target is outside the catalog. A null target
 * pauses automatic translation until the person chooses again, instead of substituting another language.
 */
internal data class TranslationChoice(
    val enabled: Boolean,
    val target: String?,
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

    /** The global choice: English until someone chooses, and null once a saved target leaves the catalog. */
    fun global(): TranslationChoice {
        val enabled = runCatching { preferences.getBoolean("global", false) }.getOrDefault(false)
        return TranslationChoice(enabled, usableTarget(savedTarget("target"), DEFAULT_TARGET))
    }

    /**
     * The chat's saved target exactly as stored, or null when the chat inherits the global target. The value
     * can be outside the catalog, so the chat dialog can show it needs recovery rather than hiding it.
     */
    fun targetOverride(
        account: String,
        group: String,
    ): String? = savedTarget(key(account, group) + ":target")

    fun override(
        account: String,
        group: String,
    ): TranslationOverride =
        runCatching { TranslationOverride.valueOf(preferences.getString(key(account, group), null).orEmpty()) }
            .getOrDefault(TranslationOverride.INHERIT)

    /**
     * The choice automatic translation uses for one chat. A saved chat target outside the catalog yields a
     * null target, never the global one, so an unusable override is not silently replaced.
     */
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
        return TranslationChoice(enabled, usableTarget(savedTarget(key(account, group) + ":target"), global.target))
    }

    /** Saves the global switch, and [target] when given; null keeps the saved target, even one needing recovery. */
    fun setGlobal(
        enabled: Boolean,
        target: String?,
    ) {
        require(target == null || OfflineTranslatorLanguageCatalog.supportsTarget(target))
        val edit = preferences.edit().putBoolean("global", enabled)
        if (target != null) edit.putString("target", target)
        edit.apply()
    }

    /** Saves the chat's automatic mode without touching its target language. */
    fun setOverride(
        account: String,
        group: String,
        override: TranslationOverride,
    ) {
        val edit = preferences.edit()
        val key = key(account, group)
        if (override == TranslationOverride.INHERIT) edit.remove(key) else edit.putString(key, override.name)
        edit.apply()
    }

    /** Saves a reviewed target for one chat, or null to inherit the global target again. */
    fun setTargetOverride(
        account: String,
        group: String,
        target: String?,
    ) {
        require(target == null || OfflineTranslatorLanguageCatalog.supportsTarget(target))
        val key = key(account, group) + ":target"
        val edit = preferences.edit()
        if (target == null) edit.remove(key) else edit.putString(key, target)
        edit.apply()
    }

    /** The raw saved code, `""` for a value of the wrong type, or null when nothing is saved. */
    private fun savedTarget(key: String): String? = runCatching { preferences.getString(key, null) }.getOrElse { "" }

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
        const val DEFAULT_TARGET = "en"
    }
}

/** An unsaved target inherits [fallback]; a saved one counts only while the catalog can still translate into it. */
private fun usableTarget(
    saved: String?,
    fallback: String?,
): String? = if (saved == null) fallback else saved.takeIf(OfflineTranslatorLanguageCatalog::supportsTarget)
