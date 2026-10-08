package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.SystemFolderKind
import dev.ipf.whitenoise.android.ui.common.LocalWhiteNoiseHeaderScroll
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

private const val FOLDER_HEADER_SCROLLED_THRESHOLD = 0.01f
private val FOLDER_MANAGER_BUTTON_SIZE = 32.dp
private val FOLDER_MANAGER_ICON_SIZE = 18.dp
private const val FOLDER_PILL_MAX_VISIBLE_COUNT = 99

/** Native folder order/rules/counts remain input facts; this row only presents and reveals the selected destination. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "LongParameterList", "FunctionNaming", "CyclomaticComplexMethod")
@Composable
internal fun ChatFolderPills(
    chips: List<ChatFolderChipModel>,
    selectedFolderId: String?,
    onSelect: (String?) -> Unit,
    onEditFolder: (String) -> Unit,
    onFolders: (() -> Unit)?,
    modifier: Modifier = Modifier,
    chatsUnreadCount: ULong? = null,
) {
    val scrolled =
        (LocalWhiteNoiseHeaderScroll.current?.state?.overlappedFraction ?: 0f) > FOLDER_HEADER_SCROLLED_THRESHOLD
    val containerColor =
        animateColorAsState(
            if (scrolled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
            label = "Folder row background",
        ).value
    val selectedColor =
        animateColorAsState(
            if (scrolled) {
                MaterialTheme.colorScheme.surfaceContainerLowest
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            label = "Selected folder background",
        ).value
    val home = chips.firstOrNull { it.unfilteredHome }
    val showRoot = home == null
    val selectedIndex =
        (
            if (selectedFolderId == null) {
                if (showRoot) 0 else chips.indexOf(home)
            } else {
                chips.indexOfFirst { it.folderId == selectedFolderId } + if (showRoot) 1 else 0
            }
        ).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    LaunchedEffect(selectedIndex, showRoot, chips.map { it.folderId }) {
        val layout = listState.layoutInfo
        val selected = layout.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        if (selected == null ||
            selected.offset < layout.viewportStartOffset ||
            selected.offset + selected.size > layout.viewportEndOffset
        ) {
            listState.scrollToItem(selectedIndex)
        }
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth().background(containerColor).testTag("chats.folders"),
        contentPadding = PaddingValues(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showRoot) {
            item(key = "scope:all") {
                ChatFolderPill(
                    stringResource(R.string.folder_all_chats),
                    selectedFolderId == null,
                    CHAT_LIST_FILTER_CHIP_ALL_TAG,
                    selectedColor,
                    { onSelect(null) },
                    unreadMessageCount = chatsUnreadCount,
                )
            }
        }
        items(chips, key = { it.folderId }) { chip ->
            ChatFolderPill(
                label =
                    chip.customLabel.ifEmpty {
                        when (chip.systemKind) {
                            SystemFolderKind.CHATS -> stringResource(R.string.chats)
                            SystemFolderKind.UNREAD -> stringResource(R.string.chat_list_filter_unread)
                            SystemFolderKind.GROUPS -> stringResource(R.string.chat_list_filter_groups)
                            SystemFolderKind.ARCHIVED -> stringResource(R.string.archived)
                            null -> ""
                        }
                    },
                selected = selectedFolderId == chip.folderId || (selectedFolderId == null && chip.unfilteredHome),
                tag = if (chip.unfilteredHome) CHAT_LIST_FILTER_CHIP_ALL_TAG else chatListFilterChipTag(chip.folderId),
                selectedColor = selectedColor,
                onClick = { onSelect(chip.folderId) },
                onLongClick = { onEditFolder(chip.folderId) },
                trailingCount = chip.trailingCount,
                unreadMessageCount = if (chip.unfilteredScope) chatsUnreadCount else null,
            )
        }
        if (onFolders != null) {
            item(key = "manage") {
                IconButton(
                    onClick = onFolders,
                    modifier =
                        Modifier
                            .minimumInteractiveComponentSize()
                            .size(FOLDER_MANAGER_BUTTON_SIZE)
                            .testTag("chats.manageFolders"),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_bookmark_manager),
                        stringResource(R.string.manage_folders),
                        Modifier.size(FOLDER_MANAGER_ICON_SIZE),
                    )
                }
            }
        }
    }
}

/** Native chip visuals share the existing single gesture/semantics owner, including long-press Edit and full counts. */
@Suppress("LongParameterList", "FunctionNaming")
@Composable
internal fun ChatFolderPill(
    label: String,
    selected: Boolean,
    tag: String,
    selectedColor: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailingCount: Int = 0,
    unreadMessageCount: ULong? = null,
    labelMaxWidth: Dp? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val resources = LocalContext.current.resources
    val description =
        if (unreadMessageCount != null) {
            chatsPillAccessibleDescription(resources, label, unreadMessageCount)
        } else {
            chatFolderChipAccessibleDescription(label, trailingCount)
        }
    val visibleCount =
        unreadMessageCount?.let(::chatsPillVisibleCount)
            ?: trailingCount.takeIf { it > 0 }?.let { if (it > FOLDER_PILL_MAX_VISIBLE_COUNT) "99+" else it.toString() }
    val gesture =
        interactions.chatFolderChipGestureModifier(
            state = if (selected) ToggleableState.On else ToggleableState.Off,
            showStateIndicator = false,
            onClick = onClick,
            onLongClick = onLongClick,
            longClickLabel = if (onLongClick == null) null else stringResource(R.string.edit),
        )
    Box {
        FilterChip(
            selected = selected,
            onClick = {},
            label = { FolderPillLabel(label, labelMaxWidth) },
            interactionSource = interactions,
            shape = CircleShape,
            border = null,
            colors =
                FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = selectedColor,
                    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                ),
            trailingIcon =
                if (visibleCount != null) {
                    (
                        {
                            Text(
                                visibleCount,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    )
                } else {
                    null
                },
            modifier = Modifier.clearAndSetSemantics {},
        )
        Box(
            Modifier.matchParentSize().testTag(tag).then(gesture).semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
            },
        )
    }
}

/** Keeps destination filter labels in one bounded line while semantics expose their complete name. */
@Composable
@Suppress("FunctionNaming")
private fun FolderPillLabel(
    label: String,
    maxWidth: Dp?,
) {
    Text(
        label,
        modifier = if (maxWidth == null) Modifier else Modifier.widthIn(max = maxWidth),
        maxLines = if (maxWidth == null) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Ellipsis,
    )
}
