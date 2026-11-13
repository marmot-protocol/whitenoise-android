@file:Suppress("FunctionNaming") // Unit-returning Compose controls use the established PascalCase convention.

package dev.ipf.whitenoise.android.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCompactSearchField
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import java.text.Normalizer

private val LanguageRowMinHeight = 48.dp
private val CheckSize = 24.dp
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
 * The one language picker: a black bottom sheet with a search field that filters on every keystroke and plain
 * rows with a check mark on the [selected] language, which starts in view. Tapping a row selects it and closes.
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
                (choices.codes.indexOf(selected) - ROWS_ABOVE_SELECTED).coerceAtLeast(0),
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
                        selected = code == selected,
                        onClick = { onSelect(code) },
                        modifier = Modifier.testTag("translation.language.$code"),
                    )
                }
            }
        }
    }
}

/** A full-width sheet row at least 48 dp tall: the language name and, on the current one, a check mark. */
@Composable
private fun TranslationLanguageRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = LanguageRowMinHeight)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = WhiteNoiseSpacing.Section, vertical = WhiteNoiseSpacing.Related),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier.size(CheckSize).clearAndSetSemantics {},
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Box(Modifier.size(CheckSize))
        }
    }
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
