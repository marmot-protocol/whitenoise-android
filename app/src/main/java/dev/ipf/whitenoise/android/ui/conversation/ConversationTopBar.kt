package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.state.ChatListAvatarSeed
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.ConversationSearchTopBar
import dev.ipf.whitenoise.android.ui.common.GroupAvatar
import dev.ipf.whitenoise.android.ui.design.KeyboardPreservingDropdownMenu
import dev.ipf.whitenoise.android.ui.design.conversationMenuItemPadding
import dev.ipf.whitenoise.android.ui.group.disappearingMessagesLabel
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag

internal const val CONVERSATION_TOP_BAR_TAG = "conversation-top-bar"

/** Renders frozen route-owned conversation identity and the active top-bar mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
internal fun ConversationTopBar(
    selectionMode: Boolean,
    selectedCount: Int,
    onCloseSelection: () -> Unit,
    searchOpen: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearchAction: () -> Unit,
    searchFocusRequester: FocusRequester,
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    groupTitleCopy: GroupTitleCopy,
    openedAsDmHint: Boolean,
    firstFrameAvatar: ChatListAvatarSeed? = null,
    freezeRoutePresentation: Boolean = false,
    openDetailsDescription: String,
    onOpenDetails: () -> Unit,
    onBack: () -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onOpenSearch: () -> Unit,
    onToggleArchived: () -> Unit,
    onRequestLeave: () -> Unit,
    onTtsTransportBodyClick: (() -> Unit)? = null,
    // Compact-height windows (landscape with the IME open) trade top-bar
    // height back to the transcript and composer while keeping Back, the
    // conversation identity, and the details/menu actions reachable.
    compactHeight: Boolean = false,
    performanceSelectorsEnabled: Boolean = BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS,
) {
    val liveTitle = controller.title(groupTitleCopy)
    val liveGroup = controller.group
    val liveAvatarAccount = controller.avatarAccount
    val liveMembersLoaded = controller.membersLoaded
    val liveMemberCount = controller.memberCount
    // Re-snapshot when a reused controller enters a new route transition. The
    // stable `true` key then keeps late hydration frozen for that transition.
    val routeTitle = remember(controller, freezeRoutePresentation) { liveTitle }
    val routeGroup = remember(controller, freezeRoutePresentation) { liveGroup }
    val routeAvatarAccount = remember(controller, freezeRoutePresentation) { liveAvatarAccount }
    val routeMembersLoaded = remember(controller, freezeRoutePresentation) { liveMembersLoaded }
    val routeMemberCount = remember(controller, freezeRoutePresentation) { liveMemberCount }
    val presentedTitle = if (freezeRoutePresentation) routeTitle else liveTitle
    val presentedGroup = if (freezeRoutePresentation) routeGroup else liveGroup
    val presentedAvatarAccount = if (freezeRoutePresentation) routeAvatarAccount else liveAvatarAccount
    val presentedMembersLoaded = if (freezeRoutePresentation) routeMembersLoaded else liveMembersLoaded
    val presentedMemberCount = if (freezeRoutePresentation) routeMemberCount else liveMemberCount
    Column {
        if (selectionMode) {
            MessageSelectionBar(
                count = selectedCount,
                onClose = onCloseSelection,
            )
        } else if (searchOpen) {
            ConversationSearchTopBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                onClear = onClearSearch,
                onClose = onCloseSearch,
                onSearchAction = onSearchAction,
                focusRequester = searchFocusRequester,
            )
        } else {
            TopAppBar(
                modifier = Modifier.testTag(CONVERSATION_TOP_BAR_TAG),
                expandedHeight =
                    if (compactHeight) {
                        compactTopBarHeightFor(LocalDensity.current.fontScale)
                    } else {
                        TopAppBarDefaults.TopAppBarExpandedHeight
                    },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onOpenDetails)
                                .performanceTestTag(
                                    PerformanceTestTags.OPEN_GROUP_DETAILS,
                                    enabled = performanceSelectorsEnabled,
                                ).semantics {
                                    contentDescription = openDetailsDescription
                                },
                    ) {
                        GroupAvatar(
                            appState = appState,
                            group = presentedGroup,
                            title = presentedTitle,
                            seed = presentedAvatarAccount ?: presentedGroup.groupIdHex,
                            size = if (compactHeight) 28.dp else 36.dp,
                            fallbackPictureUrl = presentedAvatarAccount?.let(appState::avatarUrl),
                            firstFrameAvatar = firstFrameAvatar,
                        )
                        Column {
                            Text(
                                presentedTitle,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val membersSubtitle =
                                if (
                                    shouldShowConversationMembersSubtitle(
                                        membersLoaded = presentedMembersLoaded,
                                        openedAsDmHint = openedAsDmHint,
                                        groupName = presentedGroup.name,
                                        memberCount = presentedMemberCount,
                                    )
                                ) {
                                    conversationMemberCountLabel(
                                        count = presentedMemberCount,
                                        justYou = stringResource(R.string.just_you),
                                        oneMember = stringResource(R.string.one_member),
                                        membersFormat = stringResource(R.string.members_count),
                                    )
                                } else {
                                    null
                                }
                            val disappearingSecs = presentedGroup.disappearingMessageSecs.toLong()
                            val showTimer = disappearingSecs > 0L
                            if (membersSubtitle != null || showTimer) {
                                val labelStyle = MaterialTheme.typography.labelSmall
                                val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                val subtitleRow: @Composable () -> Unit = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (membersSubtitle != null) {
                                            Text(membersSubtitle, style = labelStyle, color = labelColor)
                                        }
                                        if (showTimer) {
                                            if (membersSubtitle != null) {
                                                Text("·", style = labelStyle, color = labelColor)
                                            }
                                            Icon(
                                                Icons.Default.Schedule,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp),
                                                tint = labelColor,
                                            )
                                            Text(
                                                disappearingMessagesLabel(disappearingSecs),
                                                style = labelStyle,
                                                color = labelColor,
                                            )
                                        }
                                    }
                                }
                                if (showTimer) {
                                    val timerTooltipState = rememberTooltipState(isPersistent = true)
                                    val timerTooltipText = stringResource(R.string.disappearing_tooltip_text)
                                    val showTooltipOnce =
                                        remember(controller.group.groupIdHex) {
                                            !appState.disappearingTooltipShown
                                        }
                                    if (showTooltipOnce) {
                                        LaunchedEffect(controller.group.groupIdHex) {
                                            appState.markDisappearingTooltipShown()
                                            timerTooltipState.show()
                                        }
                                    }
                                    TooltipBox(
                                        positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
                                        tooltip = { RichTooltip { Text(timerTooltipText) } },
                                        state = timerTooltipState,
                                        content = subtitleRow,
                                    )
                                } else {
                                    subtitleRow()
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onMenuOpenChange(true) }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.chat_actions),
                        )
                    }
                    KeyboardPreservingDropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { onMenuOpenChange(false) },
                        shape = RoundedCornerShape(20.dp),
                        offset = DpOffset(x = (-8).dp, y = 0.dp),
                        modifier = Modifier.widthIn(min = 232.dp),
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.conversation_search_open),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            },
                            contentPadding = conversationMenuItemPadding,
                            onClick = onOpenSearch,
                        )
                        if (!controller.group.pendingConfirmation) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (controller.presentedArchived) R.string.unarchive else R.string.archive,
                                        ),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                },
                                contentPadding = conversationMenuItemPadding,
                                enabled = !controller.mutationInFlight,
                                onClick = onToggleArchived,
                            )
                            if (controller.isSelfMember) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.leave),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    },
                                    contentPadding = conversationMenuItemPadding,
                                    enabled = !controller.mutationInFlight && controller.membersLoaded,
                                    onClick = onRequestLeave,
                                )
                            }
                        }
                    }
                },
            )
        }
        TtsTransportBar(
            appState = appState,
            onBodyClick = onTtsTransportBodyClick,
        )
    }
}
