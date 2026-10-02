package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.core.FolderTruth
import dev.ipf.whitenoise.android.core.smartFolderMatches
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.SmartFolderCodec
import dev.ipf.whitenoise.android.state.SmartFolderFilter

internal data class SmartFolderPanelState(
    val advanced: Boolean,
    val root: SmartFolderFilter.Group?,
    val unresolved: Int = 0,
)

/** Only complete automatic rules can have unresolved source data; an empty root is manual-only. */
internal fun smartFolderUnresolvedCount(
    root: SmartFolderFilter.Group?,
    source: List<ChatListItem>,
    title: (ChatListItem) -> String,
): Int =
    if (root == null || root.children.isEmpty() || !SmartFolderCodec.valid(root)) {
        0
    } else {
        source.count { smartFolderMatches(root, it, title) == FolderTruth.UNKNOWN }
    }
