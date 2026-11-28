@file:Suppress("FunctionNaming") // Unit-returning Compose controls use the established PascalCase convention.

package dev.ipf.whitenoise.android.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseChoicePage
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseChoiceRow
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCompactSearchField
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePageChoice
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import java.text.Normalizer

private val CueMinWidth = 24.dp
private val LanguageFlagFontSize = 18.sp
private val LanguageFlagSlotSize = 26.sp
private const val SHEET_HEIGHT_SHARE = 0.88f
private const val ROWS_ABOVE_SELECTED = 2
private val CombiningMarks = Regex("\\p{Mn}+")

/** Case- and accent-insensitive form used to compare what was typed with names and codes. */
internal fun foldForLanguageSearch(value: String): String {
    val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
    return decomposed.replace(CombiningMarks, "").lowercase()
}

/** The [codes] whose localized name or code contains [query], ignoring case and accents; a blank query keeps all. */
internal fun filterLanguageCodes(
    codes: List<String>,
    label: (String) -> String,
    query: String,
): List<String> {
    val needle = foldForLanguageSearch(query.trim())
    if (needle.isEmpty()) return codes
    return codes.filter {
        foldForLanguageSearch(label(it)).contains(needle) ||
            (it.isNotEmpty() && foldForLanguageSearch(it).contains(needle))
    }
}

/**
 * The in-chat language picker: a bottom sheet over the conversation with a search field that filters on every
 * keystroke and radio rows with the [selected] language, which starts in view. Tapping a row selects it and closes.
 * Settings uses [TranslationLanguagePickerPage] instead, because there the list is a catalog to browse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslationLanguagePickerSheet(
    title: String,
    choices: TranslationLanguageChoices,
    selected: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(choices, query) { filterLanguageCodes(choices.codes, choices.label, query) }
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex =
                (visible.indexOf(selected) - ROWS_ABOVE_SELECTED).coerceAtLeast(0),
        )
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val maxHeight = with(LocalDensity.current) { (windowHeightPx * SHEET_HEIGHT_SHARE).toDp() }
    WhiteNoiseModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            WhiteNoiseSheetHeader(title, onClose = onDismiss)
            WhiteNoiseCompactSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.translation_search_languages),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = WhiteNoiseSpacing.CompactScreenMargin,
                            vertical = WhiteNoiseSpacing.Related,
                        ).testTag("translation.language.search"),
            )
            WhiteNoiseLazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .testTag("translation.language.list")
                        .semantics { collectionInfo = CollectionInfo(visible.size, 1) },
                contentPadding = PaddingValues(vertical = WhiteNoiseSpacing.Related),
                verticalArrangement = Arrangement.Top,
            ) {
                if (visible.isEmpty()) {
                    item(key = "empty") { TranslationLanguageNoMatches() }
                }
                itemsIndexed(visible, key = { _, code -> code }) { _, code ->
                    TranslationLanguageRow(
                        label = choices.label(code),
                        flag = translationLanguageFlag(code),
                        selected = code == selected,
                        onClick = { onSelect(code) },
                        modifier = Modifier.testTag("translation.language.$code"),
                    )
                }
            }
        }
    }
}

/**
 * One language as a Material radio row (#4094): the radio marks the current choice and the decorative flag or globe
 * cue sits beside the localized name, outside semantics.
 */
@Composable
private fun TranslationLanguageRow(
    label: String,
    flag: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WhiteNoiseChoiceRow(
        title = label,
        selected = selected,
        onClick = onClick,
        modifier = modifier.padding(horizontal = WhiteNoiseSpacing.Related),
        leading = { TranslationLanguageCue(flag) },
    )
}

/** The flag for a language with one clear country, else a globe, in a slot wide enough for any flag glyph. */
@Composable
internal fun TranslationLanguageCue(flag: String?) {
    val flagWidth = with(LocalDensity.current) { LanguageFlagSlotSize.toDp() }.coerceAtLeast(CueMinWidth)
    Box(Modifier.widthIn(min = flagWidth), contentAlignment = Alignment.Center) {
        if (flag == null) {
            Icon(
                painterResource(R.drawable.ic_language),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(flag, fontSize = LanguageFlagFontSize, maxLines = 1)
        }
    }
}

/**
 * The Settings form of the language picker: a catalog page with Back (#4094), searchable with the same accent- and
 * case-insensitive match as the sheet, radio rows with the flag cue, and the [selected] language in view. A tap
 * applies the language and returns to Settings.
 */
@Composable
internal fun TranslationLanguagePickerPage(
    title: String,
    choices: TranslationLanguageChoices,
    selected: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    WhiteNoiseChoicePage(
        title = title,
        choices =
            choices.codes.map { code ->
                WhiteNoisePageChoice(
                    value = code,
                    title = choices.label(code),
                    leading = { TranslationLanguageCue(translationLanguageFlag(code)) },
                )
            },
        selected = selected,
        onSelect = onSelect,
        onDismiss = onDismiss,
        searchPlaceholder = stringResource(R.string.translation_search_languages),
        matches = { choice, query -> filterLanguageCodes(listOf(choice.value), choices.label, query).isNotEmpty() },
        rowTag = { _, code -> "translation.language.$code" },
        searchTag = "translation.language.search",
        emptyText = stringResource(R.string.no_matches),
    )
}

@Composable
private fun TranslationLanguageNoMatches() {
    Text(
        stringResource(R.string.no_matches),
        modifier =
            Modifier
                .padding(horizontal = WhiteNoiseSpacing.Section, vertical = WhiteNoiseSpacing.Related)
                .testTag("translation.language.empty"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
