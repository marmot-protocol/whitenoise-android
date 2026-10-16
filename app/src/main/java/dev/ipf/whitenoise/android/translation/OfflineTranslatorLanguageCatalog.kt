package dev.ipf.whitenoise.android.translation

import java.text.Collator
import java.util.Locale

/**
 * One language of Offline Translator's bundled catalog. [code] is the engine identifier and reaches Binder
 * unchanged — `zh_hant` is not a BCP 47 tag — while [displayTag] only names it. The engine pivots through
 * English, so [source] means the catalog lists a `translate-<code>-en` model and [target] a `translate-en-<code>`
 * model. Neither promises that the pack is downloaded. [since] is the oldest reviewed release that lists it.
 */
internal data class OfflineTranslatorLanguage(
    val code: String,
    val displayTag: String,
    val source: Boolean,
    val target: Boolean,
    val since: String,
)

/** Picker codes from the reviewed engine catalog, sorted by [label] for the current display locale. */
internal class TranslationLanguageChoices(
    val codes: List<String>,
    val label: (String) -> String,
)

/**
 * The reviewed capability catalog of the Offline Translator releases White Noise accepts. It mirrors each
 * release's bundled `index_v6.json`, pinned with its provenance in the test fixture
 * `translation/offline-translator-catalogs.json`, and every picker, saved choice and request check reads it.
 * The engine matches codes exactly and substitutes its own default for an unknown target, so no other code
 * may reach it. Nothing here is fetched at runtime.
 */
internal object OfflineTranslatorLanguageCatalog {
    /** Engine releases whose bundled catalog and Binder contract were reviewed, oldest first. */
    val engineVersions: List<String> = listOf("0.8.3", "0.8.4", "0.8.5")

    val languages: List<OfflineTranslatorLanguage> =
        listOf(
            language("ar"),
            language("az"),
            language("be", target = false),
            language("bg"),
            language("bn"),
            language("bs"),
            language("ca"),
            language("cs"),
            language("da"),
            language("de"),
            language("el"),
            language("en"),
            language("es"),
            language("et"),
            language("eu"),
            language("fa"),
            language("fi"),
            language("fr"),
            language("gl"),
            language("gu"),
            language("he"),
            language("hi"),
            language("hr"),
            language("hu"),
            language("id"),
            language("is"),
            language("it"),
            language("ja"),
            language("ka", since = "0.8.4"),
            language("kn"),
            language("ko"),
            language("lt"),
            language("lv"),
            language("ml"),
            language("mr", target = false),
            language("ms"),
            language("nb"),
            language("nl"),
            language("nn", target = false),
            language("no"),
            language("pl"),
            language("pt"),
            language("ro"),
            language("ru"),
            language("sk"),
            language("sl"),
            language("sq"),
            language("sr"),
            language("sv"),
            language("sw"),
            language("ta"),
            language("te"),
            language("th"),
            language("tl"),
            language("tr"),
            language("ug"),
            language("uk"),
            language("ur", target = false),
            language("vi"),
            language("zh", displayTag = "zh-Hans"),
            language("zh_hant", displayTag = "zh-Hant"),
        )

    private val byCode = languages.associateBy { it.code }

    /** Every code a reviewed release can translate from, in catalog order. */
    val sources: List<String> = languages.filter { it.source }.map { it.code }

    /** Every code a reviewed release can translate into, in catalog order. */
    val targets: List<String> = languages.filter { it.target }.map { it.code }

    /** Whether some reviewed release can translate from [code]. */
    fun supportsSource(code: String): Boolean = byCode[code]?.source == true

    /** Whether some reviewed release can translate into [code], the rule for every saved or selected target. */
    fun supportsTarget(code: String): Boolean = byCode[code]?.target == true

    /** Whether a request may be submitted: detection (`""`) or a reviewed source, and a reviewed target. */
    fun supportsRequest(
        source: String,
        target: String,
    ): Boolean = (source.isEmpty() || supportsSource(source)) && supportsTarget(target)

    /** The codes [engineVersion]'s own bundled catalog lists, or none for a release nobody reviewed. */
    fun availableIn(engineVersion: String): Set<String> {
        val release = engineVersions.indexOf(engineVersion)
        return languages
            .filter { release >= 0 && engineVersions.indexOf(it.since) <= release }
            .mapTo(mutableSetOf()) { it.code }
    }

    /**
     * Why the installed [engineVersion] must not receive this pair, or null when it may. A code that only a
     * newer reviewed release lists needs an engine update, since the older engine would silently swap it for
     * its default. Anything outside the catalog is invalid and is never passed through.
     */
    fun requestFailure(
        engineVersion: String,
        source: String,
        target: String,
    ): TranslationFailure? {
        val available = availableIn(engineVersion)
        return when {
            !supportsRequest(source, target) -> TranslationFailure.INVALID_REQUEST
            engineVersion !in engineVersions -> TranslationFailure.INCOMPATIBLE_ENGINE
            listOf(source, target).any { it.isNotEmpty() && it !in available } ->
                TranslationFailure.ENGINE_UPDATE_REQUIRED
            else -> null
        }
    }

    /**
     * The localized name of [code], or null outside the catalog. Labels come from the explicit display tag,
     * so Simplified and Traditional Chinese, and Norwegian, Bokmål and Nynorsk, stay distinguishable.
     */
    fun label(
        code: String,
        locale: Locale,
    ): String? =
        byCode[code]?.let { language ->
            Locale.forLanguageTag(language.displayTag).getDisplayName(locale).replaceFirstChar {
                it.titlecase(locale)
            }
        }

    /** [codes] in the reading order of their localized labels. */
    fun sortedByLabel(
        codes: List<String>,
        locale: Locale,
    ): List<String> {
        val collator = Collator.getInstance(locale)
        return codes.sortedWith(compareBy(collator) { label(it, locale).orEmpty() })
    }

    /** Every reviewed language translates into English; [target] and [since] record the exceptions. */
    private fun language(
        code: String,
        displayTag: String = code,
        target: Boolean = true,
        since: String = engineVersions.first(),
    ): OfflineTranslatorLanguage = OfflineTranslatorLanguage(code, displayTag, source = true, target, since)
}
