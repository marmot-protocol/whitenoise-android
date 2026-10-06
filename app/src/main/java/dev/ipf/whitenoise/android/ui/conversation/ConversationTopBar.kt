package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.conversationHeaderTitle
import dev.ipf.whitenoise.android.core.selectedChatPresentationTitle
import dev.ipf.whitenoise.android.state.ChatListAvatarSeed
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.adoptableSelectedAvatarAsset
import dev.ipf.whitenoise.android.state.currentGroupAvatarItem
import dev.ipf.whitenoise.android.state.isPeerSourced
import dev.ipf.whitenoise.android.state.selectedAvatarIsPersonPicture
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.chats.ConversationSearchTopBar
import dev.ipf.whitenoise.android.ui.common.GroupAvatar
import dev.ipf.whitenoise.android.ui.common.LocalWhiteNoiseHeaderScroll
import dev.ipf.whitenoise.android.ui.common.PreparedGroupAvatarContent
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
    onTtsTransportBodyClick: (() -> Unit)? = null,
    // Compact-height windows (landscape with the IME open) trade top-bar
    // height back to the transcript and composer while keeping Back, the
    // conversation identity and the details action reachable.
    compactHeight: Boolean = false,
    performanceSelectorsEnabled: Boolean = BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS,
) {
    // MDK 0.10.0 prepares the conversation title inside the live window; fall back to the app's own
    // projection until the first replacement arrives or when the seam has no window. A DM peer's
    // private nickname is Android-owned and outranks both, per [conversationHeaderTitle] (#2766).
    val liveTitle =
        conversationHeaderTitle(
            dmPeerAccountIdHex = controller.dmNicknamePeerAccount,
            contactNickname = appState::contactNickname,
            preparedTitle = selectedChatPresentationTitle(controller.window.header?.selected, groupTitleCopy),
            projectedTitle = { controller.title(groupTitleCopy) },
        )
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
    val liveRow =
        appState
            .currentGroupAvatarItem(controller.boundAccountRef, presentedGroup.groupIdHex)
    val currentRow =
        liveRow
            ?.takeIf {
                it.group.avatarUrl == presentedGroup.avatarUrl &&
                    it.group.imageHashHex == presentedGroup.imageHashHex
            }
    val selectedAsset =
        if (freezeRoutePresentation || controller.window.header == null) {
            currentRow?.selectedAvatarAsset
        } else {
            controller.window.header?.let { header ->
                adoptableSelectedAvatarAsset(
                    header.avatarAsset,
                    header.selected.avatarSource,
                    presentedGroup,
                    presentedMemberCount,
                    header.selected.avatar,
                )
            }
        }
    // Only a member's profile picture may animate; a group-owned image always stays still.
    val selectedAssetIsPersonPicture =
        selectedAsset != null &&
            if (freezeRoutePresentation || controller.window.header == null) {
                currentRow?.selectedAvatarIsPersonPicture == true
            } else {
                controller.window.header
                    ?.selected
                    ?.avatarSource
                    ?.isPeerSourced() == true
            }
    val avatarSource =
        if (freezeRoutePresentation || controller.window.header == null) {
            currentRow?.selectedPresentation
        } else {
            controller.window.header?.selected
        }
    val peerEligible =
        dev.ipf.whitenoise.android.core.GroupProjector
            .lendsPeerAvatar(presentedGroup, presentedMemberCount)
    val privatePeer =
        (presentedAvatarAccount ?: avatarSource?.peerId?.takeIf { avatarSource.avatarSource.isPeerSourced() })
            ?.takeIf { peerEligible }
            ?.let { appState.contactAvatarSource(it, controller.boundAccountRef) }
            ?.takeIf(dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader::isPrivate)
    val hasExplicitSelection = controller.window.header != null || liveRow?.selectedAvatarAsset != null
    val explicitSelectionMissing = hasExplicitSelection && selectedAsset == null
    val avatarGroup =
        if (explicitSelectionMissing) {
            presentedGroup.copy(avatarUrl = null, imageHashHex = null)
        } else {
            presentedGroup
        }
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
            PreparedGroupAvatarContent(
                appState,
                listOfNotNull(selectedAsset),
                controller.boundAccountRef,
                surfaceIdentity = controller,
            ) {
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
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(role = Role.Button, onClick = onOpenDetails)
                                    .performanceTestTag(
                                        PerformanceTestTags.OPEN_GROUP_DETAILS,
                                        enabled = performanceSelectorsEnabled,
                                    ).semantics(mergeDescendants = true) {
                                        contentDescription = openDetailsDescription
                                    },
                        ) {
                            Box(Modifier.testTag("conversation.header.avatar")) {
                                GroupAvatar(
                                    appState = appState,
                                    group = avatarGroup,
                                    title = presentedTitle,
                                    seed = presentedAvatarAccount ?: presentedGroup.groupIdHex,
                                    size = if (compactHeight) 28.dp else 40.dp,
                                    fallbackPictureUrl =
                                        privatePeer ?: presentedAvatarAccount
                                            ?.takeUnless { explicitSelectionMissing }
                                            ?.let(appState::avatarUrl),
                                    firstFrameAvatar = firstFrameAvatar,
                                    accountRef = controller.boundAccountRef,
                                    durableAvatar = selectedAsset,
                                    durableAvatarIsPersonPicture =
                                        selectedAssetIsPersonPicture ||
                                            avatarSource?.avatarSource?.isPeerSourced() == true,
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(CONVERSATION_TITLE_LINE_SPACING_DP.dp)) {
                                EmojiLabel(
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
                                                    painterResource(R.drawable.ic_timer),
                                                    contentDescription = null,
                                                    modifier =
                                                        Modifier.size(12.dp).testTag("conversation.header.timer"),
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
                                painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    scrollBehavior = LocalWhiteNoiseHeaderScroll.current,
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                )
            }
        }
        TtsTransportBar(
            appState = appState,
            onBodyClick = onTtsTransportBodyClick,
        )
    }
}

private const val CONVERSATION_TITLE_LINE_SPACING_DP = -2
