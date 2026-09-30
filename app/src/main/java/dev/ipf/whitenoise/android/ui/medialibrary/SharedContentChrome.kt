package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerPage
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.settings.SettingsSection

/** Four per-chat destinations, retaining the exact loaded native source rather than claiming a full account index. */
internal enum class SharedContentCategory(
    val titleRes: Int,
) {
    Media(R.string.shared_content_photos_videos),
    Links(R.string.global_search_content_links),
    Documents(R.string.media_type_documents),
    Voice(R.string.shared_media_tab_voice),
}

/** Filters preserve native newest-first source identity and never initiate network or history requests. */
internal enum class SharedVisualFilter(
    val titleRes: Int,
) {
    All(R.string.chat_list_filter_all),
    Images(R.string.shared_media_tab_images),
    Videos(R.string.shared_media_tab_videos),
}

/** Visual tiles matching the filter. */
internal fun SharedMediaTiles.visualsFor(filter: SharedVisualFilter): List<SharedMediaTile> =
    when (filter) {
        SharedVisualFilter.All -> visuals
        SharedVisualFilter.Images -> images
        SharedVisualFilter.Videos -> videos
    }

/** Counts and destinations share the same bounded, visibility-filtered native conversation projection. */
@Suppress("FunctionNaming")
@Composable
internal fun SharedContentCategories(
    tiles: SharedMediaTiles,
    onOpen: (SharedContentCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories =
        SharedContentCategory.entries.mapNotNull { category ->
            val count =
                when (category) {
                    SharedContentCategory.Media -> tiles.visuals.size
                    SharedContentCategory.Links -> tiles.urls.size
                    SharedContentCategory.Documents -> tiles.files.size
                    SharedContentCategory.Voice -> tiles.voice.size
                }
            if (count > 0) category to count else null
        }
    if (categories.isEmpty()) return

    SettingsSection(stringResource(R.string.shared_content_in_chat))
    SettingsGroup(modifier = modifier) {
        categories.forEach { (category, count) ->
            row(key = category.name) {
                val icon =
                    when (category) {
                        SharedContentCategory.Media -> R.drawable.ic_image
                        SharedContentCategory.Links -> R.drawable.ic_link
                        SharedContentCategory.Documents -> R.drawable.ic_description
                        SharedContentCategory.Voice -> R.drawable.ic_mic
                    }
                SettingsAction(
                    context = it,
                    title = stringResource(category.titleRes),
                    subtitle =
                        if (tiles.isLoading) {
                            stringResource(R.string.shared_content_loading)
                        } else {
                            pluralStringResource(R.plurals.shared_item_count, count, count)
                        },
                    leading = { Icon(painterResource(icon), contentDescription = null) },
                    modifier = Modifier.testTag("shared.category.${category.name}"),
                    onClick = { onOpen(category) },
                )
            }
        }
    }
}

/** Shared settings frame with the prototype media-only filter chips; native content retains its scroll owner. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun SharedContentScaffold(
    category: SharedContentCategory,
    filter: SharedVisualFilter,
    onFilter: (SharedVisualFilter) -> Unit,
    onBack: () -> Unit,
    loading: Boolean,
    content: @Composable () -> Unit,
) {
    SettingsScaffold(title = stringResource(category.titleRes), onBack = onBack) {
        Column(Modifier.fillMaxSize().testTag("shared.destination.${category.name}")) {
            if (category == SharedContentCategory.Media) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SharedVisualFilter.entries.forEach { value ->
                        FilterChip(
                            selected = filter == value,
                            onClick = { onFilter(value) },
                            label = { Text(stringResource(value.titleRes)) },
                            modifier = Modifier.testTag("shared.filter.${value.name}"),
                        )
                    }
                }
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.shared_content_loading), Modifier.padding(16.dp))
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}

/** Empty categories describe the loaded conversation window without suggesting all historical media was queried. */
@Suppress("FunctionNaming")
@Composable
internal fun SharedContentEmptyState(label: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        WhiteNoiseEmptyState(title = label, detail = stringResource(R.string.shared_content_loaded_scope))
    }
}

/** Keeps current-page identity through a projection refresh while withholding all unconfirmed media pages. */
internal class SharedMediaViewerSelection {
    var source by mutableStateOf<Pair<String, Int>?>(null)
        private set

    /** Selects the viewer source page. */
    fun select(
        messageId: String,
        attachmentIndex: Int,
    ) {
        source = messageId to attachmentIndex
    }

    /** Clears the viewer source. */
    fun clear() {
        source = null
    }

    /** Drops a selection whose page vanished once loading settled. */
    fun reconcile(
        loading: Boolean,
        pages: List<MediaViewerPage>,
    ) {
        if (loading) return
        val selected = source ?: return
        if (pages.none { it.messageIdHex == selected.first && it.attachmentIndex == selected.second }) clear()
    }
}
