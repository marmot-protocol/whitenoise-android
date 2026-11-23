package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.LocalSearchCollapse
import dev.ipf.whitenoise.android.ui.common.SearchCollapse
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCompactSearchField
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.common.searchExpandReveal

@Suppress("FunctionNaming")
@Composable
internal fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    label: Int = R.string.settings_search,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    WhiteNoiseCompactSearchField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(label),
        clearDescription = stringResource(R.string.clear_search),
        outlined = true,
        modifier =
            Modifier
                .fillMaxWidth()
                .searchExpandReveal(query)
                .focusRequester(focus)
                .testTag("settings.search.field"),
    )
}

@Suppress("FunctionNaming")
@Composable
internal fun SettingsSearchResults(
    results: List<SettingsSearchDocument>,
    state: LazyListState,
    onSelect: (SettingsSearchEntry) -> Unit,
    animationsEnabled: Boolean = rememberSystemAnimationsEnabled(),
    query: String = "",
    onGuideSelect: (UserGuide) -> Unit = {},
) {
    val loadedGuides = rememberUserGuides()
    val guides = remember(loadedGuides, query) { loadedGuides.orEmpty().filter { it.matches(query) } }
    val ranks = results.mapIndexed { index, document -> document.entry.id to index }.toMap()
    SettingsList(
        state = state,
        listTag = "settings.search.results",
        itemModifier = { key ->
            val rank = (key as? String)?.let(ranks::get) ?: results.size
            // The strongest match stays flush below the toolbar. Lower rows retain
            // membership motion; disappearing rows retire without stale input nodes.
            Modifier
                .animateItem(
                    fadeInSpec = if (animationsEnabled && rank > 0) tween(SETTINGS_SEARCH_MEMBERSHIP_MILLIS) else null,
                    placementSpec = if (animationsEnabled && rank > 0) tween(SETTINGS_ROUTE_MOTION_MILLIS) else null,
                    fadeOutSpec = null,
                ).zIndex(-rank.toFloat())
        },
    ) {
        if (results.isEmpty() && guides.isEmpty()) {
            item(key = "settings.search.empty") {
                SettingsExplainer(stringResource(R.string.settings_search_no_results))
            }
        }
        results.forEach { document ->
            item(key = document.entry.id) {
                SettingsGroup {
                    row(document.entry.id) { context ->
                        SettingsLink(
                            context = context,
                            title = document.title,
                            subtitle =
                                listOf(
                                    document.category,
                                    document.summary,
                                ).filter(String::isNotBlank).joinToString("\n"),
                            onClick = { onSelect(document.entry) },
                            modifier = Modifier.testTag("settings.search.result.${document.entry.id}"),
                            leading = { SettingsLeadingIcon(document.entry.categoryIcon()) },
                        )
                    }
                }
            }
        }
        if (guides.isNotEmpty()) {
            item(key = "guide-results-heading") {
                if (results.isNotEmpty()) HorizontalDivider()
                SettingsSection(stringResource(R.string.user_documentation))
            }
            items(guides.size, key = { "guide.${guides[it].id}" }) { index ->
                val guide = guides[index]
                UserGuideButton(guide) { onGuideSelect(guide) }
            }
        }
    }
}

private const val SETTINGS_SEARCH_MEMBERSHIP_MILLIS = 120

/** A result borrows its hub category's glyph; categories outside the hub fall back to search. */
@DrawableRes
private fun SettingsSearchEntry.categoryIcon(): Int =
    SettingsHomeRow.entries.firstOrNull { it.titleRes == categoryRes || it.detail == destination }?.iconRes
        ?: R.drawable.ic_search

/** Build only the existing title slot; the Settings toolbar continues owning its layout. */
internal fun settingsSearchTitle(
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    searchCollapse: SearchCollapse,
    label: Int = R.string.settings_search,
): (@Composable () -> Unit)? =
    if (searchOpen) {
        {
            CompositionLocalProvider(LocalSearchCollapse provides searchCollapse) {
                SettingsSearchField(query, onQueryChange, label)
            }
        }
    } else {
        null
    }

@Composable
@Suppress("FunctionNaming")
internal fun SettingsSearchAction(
    searchOpen: Boolean,
    label: Int = R.string.settings_search,
    tag: String = "settings.search.open",
    onOpen: () -> Unit,
) {
    if (!searchOpen) {
        IconButton(onClick = onOpen, modifier = Modifier.testTag(tag)) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(label))
        }
    }
}
