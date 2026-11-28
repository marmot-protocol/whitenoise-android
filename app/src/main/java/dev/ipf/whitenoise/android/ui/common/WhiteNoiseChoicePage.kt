// The page composable and its row model belong together, so the file is named after the page.
@file:Suppress("MatchingDeclarationName")

package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.ui.conversation.MeasuredPlaybackDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/**
 * One row of a [WhiteNoiseChoicePage]: the value it applies, its label, optional detail and decorative cue, and
 * whether it can be chosen at all.
 */
internal data class WhiteNoisePageChoice<T>(
    val value: T,
    val title: String,
    val subtitle: String? = null,
    val leading: (@Composable () -> Unit)? = null,
    val accessibilityLabel: String? = null,
    val enabled: Boolean = true,
)

/**
 * The catalog picker from docs/ui-patterns.md: a page with Back for a list someone browses or searches, such as
 * the read-aloud voices or the translation languages. Every value is a Material radio row and the current one is
 * selected, so the selection is clear before anything changes. Tapping a row applies it through [onSelect] and the
 * page closes back to its opener; Back or the toolbar arrow before a tap keeps the current value. With
 * [searchPlaceholder] set, a search field filters the rows with [matches] (by default a case-insensitive title match).
 * The page scrolls to the current choice when it opens.
 */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
@Composable
internal fun <T> WhiteNoiseChoicePage(
    title: String,
    choices: List<WhiteNoisePageChoice<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    searchPlaceholder: String? = null,
    matches: (WhiteNoisePageChoice<T>, String) -> Boolean = { choice, query ->
        choice.title.contains(query, ignoreCase = true)
    },
    rowTag: (Int, T) -> String = { index, _ -> "choice_page.row.$index" },
    searchTag: String = WHITE_NOISE_CHOICE_PAGE_SEARCH_TAG,
    emptyText: String? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible =
        remember(choices, query) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) choices else choices.filter { matches(it, trimmed) }
        }
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex =
                (choices.indexOfFirst { it.value == selected } - ROWS_ABOVE_SELECTED).coerceAtLeast(0),
        )
    MeasuredPlaybackDialog(
        origin = null,
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        WhiteNoiseScaffold(
            modifier = modifier.fillMaxSize().testTag(WHITE_NOISE_CHOICE_PAGE_TAG),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = { WhiteNoiseTopBar(title = title, onBack = dismiss) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (searchPlaceholder != null) {
                    WhiteNoiseCompactSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = searchPlaceholder,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = WhiteNoiseSpacing.CompactScreenMargin,
                                    vertical = WhiteNoiseSpacing.Related,
                                ).testTag(searchTag),
                    )
                }
                WhiteNoiseLazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .selectableGroup()
                            .semantics { collectionInfo = CollectionInfo(visible.size, 1) },
                    contentPadding =
                        PaddingValues(
                            horizontal = WhiteNoiseSpacing.Related,
                            vertical = WhiteNoiseSpacing.Related,
                        ),
                ) {
                    if (visible.isEmpty() && emptyText != null) {
                        item(key = "empty") {
                            Text(
                                emptyText,
                                modifier = Modifier.padding(WhiteNoiseSpacing.CompactScreenMargin),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(visible, key = { choices.indexOf(it) }) { choice ->
                        WhiteNoiseChoiceRow(
                            title = choice.title,
                            selected = choice.value == selected,
                            onClick = { if (acceptsInput) close { onSelect(choice.value) } },
                            enabled = choice.enabled && acceptsInput,
                            modifier = Modifier.testTag(rowTag(choices.indexOf(choice), choice.value)),
                            subtitle = choice.subtitle,
                            accessibilityLabel = choice.accessibilityLabel,
                            leading = choice.leading,
                        )
                    }
                }
            }
        }
    }
}

/** Test tag on the page's scaffold. */
internal const val WHITE_NOISE_CHOICE_PAGE_TAG = "choice_page"

/** Test tag on the page's search field. */
internal const val WHITE_NOISE_CHOICE_PAGE_SEARCH_TAG = "choice_page.search"

/** Rows kept above the current choice when the page opens scrolled to it. */
private const val ROWS_ABOVE_SELECTED = 2
