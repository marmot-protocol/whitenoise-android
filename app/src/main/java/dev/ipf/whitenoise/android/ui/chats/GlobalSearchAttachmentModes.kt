@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal const val CHAT_LIST_SEARCH_MODES_TAG = "global.library.modes"

/** Stable test tag for a mode chip. */
internal fun globalSearchModeTag(kind: GlobalSearchContentKind?): String {
    val name = kind?.name ?: "Messages"
    return "global.library.mode.$name"
}

/** Attachment kinds the prototype's browsing modes cover. */
internal val GLOBAL_SEARCH_ATTACHMENT_KINDS: Set<GlobalSearchContentKind> =
    setOf(
        GlobalSearchContentKind.IMAGES_VIDEO,
        GlobalSearchContentKind.FILES_DOCUMENTS,
        GlobalSearchContentKind.VOICE_AUDIO,
        GlobalSearchContentKind.ANY_ATTACHMENT,
    )

/** True when the content filter narrows the search to attachments only, the prototype's library mode. */
internal fun GlobalSearchState.isBrowsingAttachments(): Boolean =
    contentFilterSelection.isActive && GLOBAL_SEARCH_ATTACHMENT_KINDS.containsAll(contentFilterSelection.selectedKinds)

/**
 * The prototype's mode chips under the search bar: All (messages), Photos & videos, Files, Audio and
 * All attachments. A mode writes the content filter, so the chips and the filter menu stay one state.
 */
@Composable
internal fun GlobalSearchAttachmentModes(
    state: GlobalSearchState,
    onSelectionChange: (GlobalSearchContentFilterSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val choices =
        listOf(
            null to R.string.chat_list_search_mode_all,
            GlobalSearchContentKind.IMAGES_VIDEO to R.string.library_photos_videos,
            GlobalSearchContentKind.FILES_DOCUMENTS to R.string.library_files,
            GlobalSearchContentKind.VOICE_AUDIO to R.string.library_audio,
            GlobalSearchContentKind.ANY_ATTACHMENT to R.string.library_all,
        )
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag(CHAT_LIST_SEARCH_MODES_TAG),
        contentPadding = PaddingValues(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        items(choices, key = { it.first?.name ?: "Messages" }) { (kind, label) ->
            val selected =
                if (kind == null) {
                    !state.isBrowsingAttachments()
                } else {
                    state.contentFilterSelection.selectedKinds == setOf(kind)
                }
            FilterChip(
                selected = selected,
                onClick = {
                    onSelectionChange(GlobalSearchContentFilterSelection(kind?.let { setOf(it) }.orEmpty()))
                },
                label = { Text(stringResource(label)) },
                modifier = Modifier.testTag(globalSearchModeTag(kind)),
            )
        }
    }
}
