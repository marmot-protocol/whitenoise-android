package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.SystemFolderKind
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.LocalWhiteNoiseHeaderScroll
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseFilterChip
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.emojiPlainText
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

private const val FOLDER_HEADER_SCROLLED_THRESHOLD = 0.01f
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
    val headerScrollState = LocalWhiteNoiseHeaderScroll.current?.state
    val scrolled by remember(headerScrollState) {
        derivedStateOf { (headerScrollState?.overlappedFraction ?: 0f) > FOLDER_HEADER_SCROLLED_THRESHOLD }
    }
    val containerColor =
        animateColorAsState(
            if (scrolled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
            label = "Folder row background",
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
                onClick = { onSelect(chip.folderId) },
                onLongClick = { onEditFolder(chip.folderId) },
                trailingCount = chip.trailingCount,
                unreadMessageCount = if (chip.unfilteredScope) chatsUnreadCount else null,
            )
        }
        if (onFolders != null) {
            item(key = "manage") {
                // A full 48 dp IconButton: a size set outside it would shrink the touch target with the visual.
                IconButton(
                    onClick = onFolders,
                    modifier =
                        Modifier
                            .testTag("chats.manageFolders")
                            .explainOnLongPress(
                                chatsExplanation(
                                    R.string.manage_folders,
                                    R.string.explanation_chat_list_manage_folders,
                                ),
                            ),
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

/**
 * One folder destination filter; the shared chip owns the pill shape, selected fill and long-press semantics.
 * Folder rows stay borderless in every theme, AMOLED included, so a row of pills reads as one strip.
 */
@Suppress("LongParameterList", "FunctionNaming")
@Composable
internal fun ChatFolderPill(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailingCount: Int = 0,
    unreadMessageCount: ULong? = null,
    labelMaxWidth: Dp? = null,
) {
    val resources = LocalContext.current.resources
    val spokenLabel = emojiPlainText(label)
    val description =
        if (unreadMessageCount != null) {
            chatsPillAccessibleDescription(resources, spokenLabel, unreadMessageCount)
        } else {
            chatFolderChipAccessibleDescription(spokenLabel, trailingCount)
        }
    val visibleCount =
        unreadMessageCount?.let(::chatsPillVisibleCount)
            ?: trailingCount.takeIf { it > 0 }?.let { if (it > FOLDER_PILL_MAX_VISIBLE_COUNT) "99+" else it.toString() }
    WhiteNoiseFilterChip(
        selected = selected,
        onClick = onClick,
        label = { FolderPillLabel(label, labelMaxWidth) },
        modifier = Modifier.testTag(tag),
        trailingIcon = visibleCount?.let { count -> { Text(count, style = MaterialTheme.typography.labelSmall) } },
        contentDescription = description,
        onLongClick = onLongClick,
        longClickLabel = if (onLongClick == null) null else stringResource(R.string.edit),
        border = null,
    )
}

/**
 * Keeps destination filter labels in one bounded line while semantics expose their complete name. Custom emoji in the
 * name draw as artwork, like Unicode emoji do.
 */
@Composable
@Suppress("FunctionNaming")
private fun FolderPillLabel(
    label: String,
    maxWidth: Dp?,
) {
    EmojiLabel(
        label,
        modifier = if (maxWidth == null) Modifier else Modifier.widthIn(max = maxWidth),
        maxLines = if (maxWidth == null) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Announces the complete folder label and eligible count even when the visible label is ellipsized. */
@Composable
internal fun chatFolderChipAccessibleDescription(
    label: String,
    trailingCount: Int,
): String =
    if (trailingCount > 0) {
        val countLabel = pluralStringResource(R.plurals.chat_folder_chat_count, trailingCount, trailingCount)
        "$label, $countLabel"
    } else {
        label
    }
