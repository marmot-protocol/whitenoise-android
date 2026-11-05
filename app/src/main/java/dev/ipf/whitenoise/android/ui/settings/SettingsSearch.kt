package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import java.text.Normalizer
import java.util.Locale

/** Static UI metadata only. Never supply account values or other user content. */
internal data class SettingsSearchEntry(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val categoryRes: Int,
    val destination: SettingsDetail?,
    val itemKey: String? = null,
    val rowKey: String? = null,
    @param:StringRes val summaryRes: Int? = null,
    @param:StringRes val aliasesRes: Int? = null,
    val accountRequired: Boolean = false,
    val developerRequired: Boolean = false,
    val nonAmoledRequired: Boolean = false,
    val pushRequired: Boolean = false,
)

internal data class SettingsSearchEligibility(
    val hasAccount: Boolean,
    val developer: Boolean,
    val amoled: Boolean,
    val pushAvailable: Boolean,
    val hiddenIds: Set<String> = emptySet(),
) {
    @Suppress("ComplexCondition") // Independent declarative visibility prerequisites must all hold.
    fun allows(entry: SettingsSearchEntry): Boolean =
        entry.id !in hiddenIds &&
            (!entry.accountRequired || hasAccount) &&
            (!entry.developerRequired || developer) &&
            (!entry.nonAmoledRequired || !amoled) &&
            (!entry.pushRequired || pushAvailable)
}

internal fun normalizeSettingsQuery(value: String): String =
    Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .trim()
        .replace("\\s+".toRegex(), " ")

internal const val SETTINGS_SEARCH_MAX_QUERY = 160

/** Resolved once per locale/eligibility revision; each edit touches only these bounded strings. */
internal class SettingsSearchDocument(
    val entry: SettingsSearchEntry,
    val title: String,
    val category: String,
    val summary: String,
    aliases: String,
) {
    internal val normalizedTitle = normalizeSettingsQuery(title)
    internal val normalizedAliases = aliases.split('|').map(::normalizeSettingsQuery).filter(String::isNotEmpty)
    internal val fields = listOf(normalizedTitle, normalizeSettingsQuery(summary), normalizeSettingsQuery(category)) + normalizedAliases
}

internal fun prepareSettingsSearch(
    entries: List<SettingsSearchEntry>,
    eligibility: SettingsSearchEligibility,
    resolve: (Int) -> String,
): List<SettingsSearchDocument> =
    entries.filter(eligibility::allows).map { entry ->
        SettingsSearchDocument(
            entry,
            resolve(entry.titleRes),
            resolve(entry.categoryRes),
            entry.summaryRes?.let(resolve).orEmpty(),
            entry.aliasesRes?.let(resolve).orEmpty(),
        )
    }

/** Every token is required, including infixes; deterministic title strength precedes metadata. */
@Suppress("CyclomaticComplexMethod", "MagicNumber") // Explicit deterministic numeric ranking tiers are intentionally ordered together.
internal fun searchSettings(
    documents: List<SettingsSearchDocument>,
    query: String,
): List<SettingsSearchDocument> {
    val phrase = normalizeSettingsQuery(query.take(SETTINGS_SEARCH_MAX_QUERY))
    if (phrase.isEmpty()) return emptyList()
    val tokens = phrase.split(' ').distinct()
    return documents
        .mapIndexedNotNull { index, document ->
            if (!tokens.all { token -> document.fields.any { token in it } }) return@mapIndexedNotNull null
            val title = document.normalizedTitle
            val tier =
                when {
                    title == phrase || phrase in document.normalizedAliases -> 0
                    title.startsWith(phrase) -> 1
                    title.split(' ').any { it.startsWith(phrase) } -> 2
                    tokens.all { token -> title.split(' ').any { it.startsWith(token) } } -> 3
                    phrase in title -> 4
                    tokens.all { it in title } -> 5
                    else -> 6
                }
            val titleCoverage = tokens.count { it in title }
            val fieldStrength = tokens.sumOf { token -> document.fields.indexOfFirst { token in it }.coerceAtLeast(0) }
            Triple(document, listOf(tier, -titleCoverage, fieldStrength, index), document.entry.id)
        }.sortedWith(compareBy({ it.second[0] }, { it.second[1] }, { it.second[2] }, { it.second[3] }, { it.third }))
        .map { it.first }
}
