package dev.ipf.whitenoise.android.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.FolderTruth
import dev.ipf.whitenoise.android.core.smartFolderMatches
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.SmartFolderCodec
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.ChatFolderPill
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.conversation.messages.destinationFolderMembershipRows
import dev.ipf.whitenoise.android.ui.conversation.messages.forwardTargetDisplayTitle
import dev.ipf.whitenoise.android.ui.settings.chatFolderDisplayName
import dev.ipf.whitenoise.android.ui.theme.Dimens

/** Reuses current folder rules and observed account projections; no history scan or wider target window is added. */
@Composable
internal fun rememberDestinationFolderRows(
    appState: WhiteNoiseAppState,
    targets: List<ChatListItem>,
    accountRef: String?,
    accountIdHex: String?,
    memberRevision: Long,
): List<Pair<ChatFolder, List<String>>> {
    val copy = rememberGroupTitleCopy()
    val states by appState.chatFolderPreferences.state.collectAsStateWithLifecycle()
    val folders = states[accountRef?.trim()]
    return remember(
        appState,
        targets,
        accountRef,
        accountIdHex,
        memberRevision,
        folders,
        copy,
        appState.profileRevisionForCompose,
    ) {
        destinationFolderMembershipRows(appState, targets, copy, accountRef, accountIdHex)
    }
}

/** Row-read success does not establish participant membership or other unavailable smart-rule facts. */
@Composable
@Suppress("ReturnCount") // Missing/manual/unsupported rules do not require automatic membership facts.
internal fun destinationFolderInputsComplete(
    appState: WhiteNoiseAppState,
    targets: List<ChatListItem>,
    accountRef: String?,
    folderId: String?,
    rows: List<Pair<ChatFolder, List<String>>>,
): Boolean {
    val copy = rememberGroupTitleCopy()
    if (accountRef == null || folderId == null) return true
    val rule = appState.chatFolderPreferences.folderRule(accountRef, folderId) ?: return true
    val matched =
        rows
            .firstOrNull { it.first.id == folderId }
            ?.second
            .orEmpty()
            .toHashSet()
    val unresolved = targets.filterNot { it.group.groupIdHex in matched }
    val payload = rule.smartFilter
    if (payload != null) {
        val filter = SmartFolderCodec.decode(payload)?.takeIf(SmartFolderCodec::valid) ?: return true
        return unresolved.none { item ->
            smartFolderMatches(filter, item) { forwardTargetDisplayTitle(it, appState, accountRef, copy) } ==
                FolderTruth.UNKNOWN
        }
    }
    val manualIds = appState.chatFolderPreferences.membershipFor(accountRef, folderId)
    return rule.includeMemberPubkeys.isEmpty() ||
        targets.none { it.memberSnapshot == null && it.group.groupIdHex !in manualIds }
}

/** A fixed All escape hatch plus bounded, scrollable filter pills; bulk-selection controls remain separate. */
@Composable
@Suppress("FunctionNaming")
internal fun DestinationFolderFilters(
    rows: List<Pair<ChatFolder, List<String>>>,
    state: DestinationFolderFilterState,
    selected: List<String>,
    onReviewSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(rows.map { it.first.id }, selected.isEmpty()) {
        val deleted = state.folderId != null && rows.none { it.first.id == state.folderId }
        val noSelection = state.reviewingSelected && selected.isEmpty()
        if (deleted || noSelection) state.selectFolder(null)
    }
    if (rows.isEmpty() && selected.isEmpty()) return
    val title = stringResource(R.string.destination_filter_label)
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        modifier
            .fillMaxWidth()
            .semantics {
                isTraversalGroup = true
                paneTitle = title
            }.padding(start = Dimens.spaceLg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatFolderPill(
            stringResource(R.string.destination_filter_all),
            state.folderId == null && !state.reviewingSelected,
            "destination.filter.all",
            color,
            { state.selectFolder(null) },
            labelMaxWidth = 140.dp,
        )
        LazyRow(
            Modifier.weight(1f).testTag("destination.filters"),
            contentPadding = PaddingValues(horizontal = Dimens.spaceSm),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
        ) {
            if (selected.isNotEmpty()) {
                item(key = "selected") {
                    ChatFolderPill(
                        stringResource(R.string.destination_filter_selected, selected.size),
                        state.reviewingSelected,
                        "destination.filter.selected",
                        color,
                        {
                            state.reviewSelected()
                            onReviewSelected()
                        },
                        labelMaxWidth = 180.dp,
                    )
                }
            }
            items(rows, key = { it.first.id }) { (folder, _) ->
                ChatFolderPill(
                    chatFolderDisplayName(folder),
                    state.folderId == folder.id,
                    "destination.filter.${folder.id}",
                    color,
                    { state.selectFolder(folder.id) },
                    labelMaxWidth = 180.dp,
                )
            }
        }
    }
}
