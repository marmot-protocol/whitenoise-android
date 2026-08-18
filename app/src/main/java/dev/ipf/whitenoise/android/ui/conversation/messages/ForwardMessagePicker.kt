@file:Suppress("FunctionName")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.text.BidiFormatter
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.chatFolderTriState
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.chats.newchat.FlowSearchField
import dev.ipf.whitenoise.android.ui.chats.newchat.SectionHeader
import dev.ipf.whitenoise.android.ui.chats.newchat.SelectionIndicator
import dev.ipf.whitenoise.android.ui.common.ErrorContent
import dev.ipf.whitenoise.android.ui.common.InlineErrorBanner
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.StickyFormActionBar
import dev.ipf.whitenoise.android.ui.common.rememberEncryptedGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import java.util.Locale

internal const val FORWARD_CHAT_PICKER_SCREEN_TEST_TAG = "forward_chat_picker_full_screen"

/** Full-screen target selection keeps row taps out of a draggable sheet gesture arena. */
@Composable
internal fun ForwardMessagePickerFullScreen(
    appState: WhiteNoiseAppState,
    messageCount: Int,
    attachmentCount: Int,
    originGroupIdHex: String,
    onDismiss: () -> Unit,
    onForward: (List<String>) -> Boolean,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var dismissing by remember { mutableStateOf(false) }
    val dismissPicker = {
        if (!dismissing) {
            dismissing = true
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            onDismiss()
        }
    }
    Dialog(
        onDismissRequest = dismissPicker,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        ForwardMessagePickerContent(
            appState = appState,
            messageCount = messageCount,
            attachmentCount = attachmentCount,
            originGroupIdHex = originGroupIdHex,
            onDismiss = dismissPicker,
            onForward = onForward,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
internal fun ForwardMessagePickerContent(
    appState: WhiteNoiseAppState,
    messageCount: Int,
    attachmentCount: Int,
    originGroupIdHex: String,
    onDismiss: () -> Unit,
    onForward: (List<String>) -> Boolean,
) {
    val titleCopy = rememberGroupTitleCopy()
    var query by rememberSaveable(originGroupIdHex) { mutableStateOf("") }
    var selected by rememberSaveable(originGroupIdHex) { mutableStateOf(arrayListOf<String>()) }
    var startFailed by rememberSaveable(originGroupIdHex) { mutableStateOf(false) }
    val targetLoading = appState.forwardTargetsLoading
    val targetError = appState.forwardTargetsError
    val memberRevision = appState.forwardTargetMembersRevision
    val targetRevision = appState.forwardTargetsRevision
    val targets =
        remember(targetRevision, originGroupIdHex) {
            appState
                .forwardTargets()
                .filterNot { it.group.groupIdHex.equals(originGroupIdHex, ignoreCase = true) }
        }
    val targetIds = remember(targets) { targets.mapTo(hashSetOf()) { it.group.groupIdHex.lowercase(Locale.ROOT) } }
    LaunchedEffect(targetLoading, targetIds) {
        if (!targetLoading) {
            selected = ArrayList(selected.filter(targetIds::contains))
            appState.requestForwardTargetMembers(targetIds)
        }
    }
    val titledTargets =
        remember(targets, titleCopy, memberRevision, appState.profileRevisionForCompose) {
            targets.map { it to chatListItemDisplayTitle(it, appState, titleCopy) }
        }
    val filteredTargets =
        remember(titledTargets, query) {
            val needle = query.trim()
            if (needle.isEmpty()) {
                titledTargets
            } else {
                titledTargets.filter { (_, title) ->
                    title.contains(needle, ignoreCase = true)
                }
            }
        }
    val folderRows = remember(targets, titleCopy) { forwardFolderBulkRows(appState, targets, titleCopy) }
    val visibleFolderRows = remember(folderRows, query) { visibleForwardFolderRows(folderRows, query) }
    val forwardTitle = stringResource(R.string.forward_to)

    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(FORWARD_CHAT_PICKER_SCREEN_TEST_TAG)
                .semantics {
                    isTraversalGroup = true
                    paneTitle = forwardTitle
                },
        containerColor = amoledSheetContainerColor(),
        topBar = {
            TopAppBar(
                title = { Text(forwardTitle) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                },
            )
        },
        bottomBar = {
            StickyFormActionBar {
                Button(
                    onClick = {
                        val recipients = forwardRecipientGroupIds(selected, originGroupIdHex)
                        startFailed =
                            !confirmForwardTargets(
                                targets = recipients,
                                start = onForward,
                                dismiss = onDismiss,
                            )
                    },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Forward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (selected.isEmpty()) {
                            stringResource(R.string.forward)
                        } else {
                            pluralStringResource(R.plurals.forward_to_chats_count, selected.size, selected.size)
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ForwardSelectionSummary(
                messageCount = messageCount,
                attachmentCount = attachmentCount,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
            )
            FlowSearchField(
                value = query,
                onValueChange = {
                    query = it
                    startFailed = false
                },
                placeholder = stringResource(R.string.forward_search_chats),
                modifier = Modifier.padding(horizontal = Dimens.spaceLg),
            )
            if (startFailed) {
                Text(
                    text = stringResource(R.string.forward_start_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier.padding(horizontal = Dimens.spaceLg).semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                )
            }
            ForwardTargetList(
                appState = appState,
                targets = targets,
                filteredTargets = filteredTargets,
                visibleFolderRows = visibleFolderRows,
                targetLoading = targetLoading,
                targetError = targetError,
                selected = selected,
                onSelectionChange = { selected = ArrayList(it) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ForwardSelectionSummary(
    messageCount: Int,
    attachmentCount: Int,
    modifier: Modifier = Modifier,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val bidiFormatter = remember(rtl) { BidiFormatter.Builder(rtl).build() }
    val summary =
        buildList {
            add(pluralStringResource(R.plurals.forward_message_count, messageCount, messageCount))
            if (attachmentCount > 0) {
                add(pluralStringResource(R.plurals.forward_attachment_count, attachmentCount, attachmentCount))
            }
        }.joinToString(" · ") { bidiFormatter.unicodeWrap(it) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = modifier,
    ) {
        Text(summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun ForwardTargetList(
    appState: WhiteNoiseAppState,
    targets: List<ChatListItem>,
    filteredTargets: List<Pair<ChatListItem, String>>,
    visibleFolderRows: List<Pair<ChatFolder, List<String>>>,
    targetLoading: Boolean,
    targetError: ErrorPresentation?,
    selected: List<String>,
    onSelectionChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = Dimens.spaceLg)) {
        if (targetLoading && targets.isEmpty()) {
            item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingScreen()
                }
            }
        } else if (targetError != null && targets.isEmpty()) {
            item {
                Box(Modifier.fillParentMaxSize()) {
                    ErrorContent(
                        title = stringResource(R.string.couldnt_load_chats),
                        error = targetError,
                        onRetry = appState::retryForwardTargets,
                    )
                }
            }
        } else if (forwardPickerHasNoRows(targets.isEmpty(), filteredTargets.isEmpty(), visibleFolderRows.isEmpty())) {
            item {
                Text(
                    stringResource(if (targets.isEmpty()) R.string.forward_no_chats else R.string.forward_no_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceLg),
                )
            }
        } else {
            targetError?.let { failure ->
                item(key = "forward-picker-load-error") {
                    InlineErrorBanner(error = failure, onRetry = appState::retryForwardTargets)
                }
            }
            if (visibleFolderRows.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.chat_folders_title)) }
                items(visibleFolderRows, key = { (folder, _) -> "folder:${folder.id}" }) { (folder, memberIds) ->
                    val triState = chatFolderTriState(memberIds, selected.toSet())
                    ListItem(
                        modifier =
                            Modifier.triStateToggleable(
                                state = triState,
                                role = Role.Checkbox,
                                onClick = {
                                    onSelectionChange(forwardSelectionAfterFolderToggle(selected, memberIds))
                                },
                            ),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { TriStateCheckbox(state = triState, onClick = null) },
                        headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(pluralStringResource(R.plurals.chat_folder_chat_count, memberIds.size, memberIds.size))
                        },
                    )
                }
            }
            if (filteredTargets.isNotEmpty()) item { SectionHeader(stringResource(R.string.recent_chats)) }
            items(filteredTargets, key = { (item, _) -> item.group.groupIdHex }) { (item, title) ->
                ForwardTargetRow(
                    appState = appState,
                    item = item,
                    title = title,
                    selected = item.group.groupIdHex.lowercase(Locale.ROOT) in selected,
                    onToggle = { groupId ->
                        onSelectionChange(toggleForwardTargetSelection(selected, groupId))
                    },
                )
            }
        }
    }
}

@Composable
private fun ForwardTargetRow(
    appState: WhiteNoiseAppState,
    item: ChatListItem,
    title: String,
    selected: Boolean,
    onToggle: (String) -> Unit,
) {
    val activeAccountIdHex = appState.activeAccount?.accountIdHex
    val avatarAccount = forwardTargetAvatarAccount(item)
    val membersPreview =
        remember(item, activeAccountIdHex, appState.profileRevisionForCompose) {
            forwardTargetMembersPreview(item, activeAccountIdHex) { memberIdHex ->
                appState.contactDisplayNameCached(memberIdHex)
            }
        }
    ContactRow(
        title = title,
        subtitle = membersPreview,
        avatarSeed = avatarAccount ?: item.group.groupIdHex,
        avatarUrl = item.group.avatarUrl ?: avatarAccount?.let { appState.avatarUrl(it) },
        avatarImage = rememberEncryptedGroupAvatar(appState, item.group),
        modifier = Modifier.semantics { this.selected = selected },
        onClick = { onToggle(item.group.groupIdHex) },
        trailing = { SelectionIndicator(selected = selected) },
    )
}
