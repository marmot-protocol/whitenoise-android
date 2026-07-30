package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.SystemFolderKind
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.account.AccountAvatarButton
import dev.ipf.whitenoise.android.ui.account.OtherAccountAvatarsRow
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

internal const val CHAT_LIST_FILTER_CHIP_ALL_TAG = "chat-list-filter-chip-all"

internal fun chatListFilterChipTag(folderId: String): String = "chat-list-filter-chip-$folderId"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatListTopBar(
    appState: WhiteNoiseAppState,
    searchOpen: Boolean,
    searchQuery: String,
    searchFocusRequester: FocusRequester,
    onSearchQueryChange: (String) -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    onMic: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchAccount: (String) -> Unit,
    connectivityState: ConnectivityBannerState = ConnectivityBannerState.Hidden,
) {
    TopAppBar(
        title = {
            when {
                searchOpen ->
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.chat_list_search_hint)) },
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                            ),
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Search,
                            ),
                    )
                // Connecting / JustConnected sit immediately right of the active
                // avatar; other signed-in accounts follow. Search mode hides the
                // inline chrome so it cannot overlap the field.
                else ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatListInlineConnectivityIndicator(state = connectivityState)
                        Box(modifier = Modifier.weight(1f, fill = false)) {
                            OtherAccountAvatarsRow(
                                appState = appState,
                                onSwitchAccount = onSwitchAccount,
                                onOpenSwitcher = onOpenSettings,
                            )
                        }
                    }
            }
        },
        navigationIcon = {
            when {
                searchOpen ->
                    IconButton(onClick = onSearchClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                else -> {
                    val active = appState.activeAccount
                    AccountAvatarButton(
                        title = active?.let { appState.displayName(it.accountIdHex) } ?: stringResource(R.string.app_name),
                        seed = active?.accountIdHex ?: "whitenoise",
                        pictureUrl = active?.let { appState.avatarUrl(it.accountIdHex) },
                        size = 44.dp,
                        onClick = onOpenSettings,
                        // Per-account dot: light only when the active account
                        // itself has unread, same shared decision the other
                        // avatars use — not "some other account has unread" (#805).
                        showUnreadDot = appState.accountShowsUnreadDot(active?.label),
                        unreadDotColor = accountActionColors(appState, active?.label).container,
                    )
                }
            }
        },
        actions = {
            if (searchOpen) {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.chat_list_search_clear),
                        )
                    }
                }
                IconButton(onClick = onMic) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = stringResource(R.string.chat_list_search_voice),
                    )
                }
            } else {
                IconButton(onClick = onSearchOpen) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(R.string.chat_list_search_open),
                    )
                }
            }
        },
    )
}

/**
 * Inline top-bar search for a single conversation (#292): a back arrow plus an
 * auto-focused field (✕ clears). The result count and previous/next match
 * navigation live on the bottom bar above the keyboard
 * ([ConversationSearchNavBar]); the IME "search" action steps to the next
 * match so the on-screen keyboard alone can walk the results.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    onSearchAction: () -> Unit,
    focusRequester: FocusRequester,
) {
    val hasQuery = query.isNotBlank()
    TopAppBar(
        title = {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.conversation_search_hint)) },
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                // Clear sits inline in the field; match navigation and the
                // result count live on the bottom bar above the keyboard.
                trailingIcon = {
                    if (hasQuery) {
                        IconButton(onClick = onClear) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.conversation_search_clear),
                            )
                        }
                    }
                },
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Search,
                    ),
                keyboardActions = KeyboardActions(onSearch = { onSearchAction() }),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.conversation_search_close),
                )
            }
        },
    )
}

/**
 * Search match navigation pinned above the keyboard while in-chat search is
 * open: a centered result count with previous/next steppers on the trailing
 * edge. Lives in the conversation `bottomBar` slot in place of the composer,
 * mirroring the composer's `navigationBarsPadding().imePadding()` so it rides
 * up with the soft keyboard rather than hiding behind it.
 */
@Composable
internal fun ConversationSearchNavBar(
    matchCount: Int,
    activeIndex: Int,
    hasQuery: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val navEnabled = matchCount > 0
    Surface(
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    when {
                        !hasQuery -> ""
                        matchCount > 0 ->
                            stringResource(
                                R.string.conversation_search_match_count,
                                activeIndex + 1,
                                matchCount,
                            )
                        else -> stringResource(R.string.conversation_search_no_matches)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onPrev, enabled = navEnabled) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.conversation_search_prev),
                )
            }
            IconButton(onClick = onNext, enabled = navEnabled) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.conversation_search_next),
                )
            }
        }
    }
}

@Composable
internal fun ChatListFilterChips(
    chips: List<ChatFolderChipModel>,
    selectedFolderId: String?,
    onSelect: (String?) -> Unit,
    onEditFolder: (String) -> Unit = {},
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // `All` is the permanent reset state, not a real folder: always
        // visible, never orderable, selected when nothing else is.
        ChatFolderChip(
            selected = selectedFolderId == null,
            label = stringResource(R.string.chat_list_filter_all),
            onClick = { onSelect(null) },
            modifier = Modifier.testTag(CHAT_LIST_FILTER_CHIP_ALL_TAG),
        )
        chips.forEach { chip ->
            ChatFolderChip(
                selected = selectedFolderId == chip.folderId,
                // A renamed default shows its stored name; the localized
                // default label is only the un-renamed fallback.
                label =
                    chip.customLabel.ifEmpty {
                        when (chip.systemKind) {
                            SystemFolderKind.UNREAD -> stringResource(R.string.chat_list_filter_unread)
                            SystemFolderKind.GROUPS -> stringResource(R.string.chat_list_filter_groups)
                            SystemFolderKind.ARCHIVED -> stringResource(R.string.archived)
                            null -> ""
                        }
                    },
                onClick = { onSelect(chip.folderId) },
                onLongClick = { onEditFolder(chip.folderId) },
                trailingCount = chip.trailingCount,
                modifier = Modifier.testTag(chatListFilterChipTag(chip.folderId)),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Suppress("FunctionNaming")
@Composable
private fun ChatFolderChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailingCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val trailingIcon: (@Composable () -> Unit)? =
        if (trailingCount > 0) {
            {
                Text(
                    text = if (trailingCount > 99) "99+" else trailingCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } else {
            null
        }
    val accessibleDescription = chatFolderChipAccessibleDescription(label, trailingCount)
    val interactionSource = remember { MutableInteractionSource() }
    val longClickLabel = if (onLongClick != null) stringResource(R.string.edit) else null
    val gestureModifier =
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = longClickLabel,
            role = Role.Checkbox,
        )
    Box {
        FilterChip(
            selected = selected,
            onClick = {},
            label = { Text(label) },
            trailingIcon = trailingIcon,
            interactionSource = interactionSource,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Box(
            modifier =
                modifier
                    .matchParentSize()
                    .then(gestureModifier)
                    .semantics(mergeDescendants = true) {
                        contentDescription = accessibleDescription
                        this.selected = selected
                    },
        )
    }
}

@Composable
private fun chatFolderChipAccessibleDescription(
    label: String,
    trailingCount: Int,
): String =
    if (trailingCount > 0) {
        val countLabel =
            pluralStringResource(
                R.plurals.chat_folder_chat_count,
                trailingCount,
                trailingCount,
            )
        "$label, $countLabel"
    } else {
        label
    }
