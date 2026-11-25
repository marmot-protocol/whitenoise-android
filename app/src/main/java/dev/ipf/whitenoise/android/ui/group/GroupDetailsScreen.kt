package dev.ipf.whitenoise.android.ui.group

import android.content.ActivityNotFoundException
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AccountDevicesActivationFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.GroupPushDebugInfoFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.ChatLink
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.LeaveAction
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.GroupRosterLoadState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.acknowledgeDisbandFailure
import dev.ipf.whitenoise.android.state.cancelBlockedLeave
import dev.ipf.whitenoise.android.state.collapseLongMessagesInGroup
import dev.ipf.whitenoise.android.state.conversationNotifyMode
import dev.ipf.whitenoise.android.state.conversationVibrationPattern
import dev.ipf.whitenoise.android.state.disbandGroup
import dev.ipf.whitenoise.android.state.enableAccountDevices
import dev.ipf.whitenoise.android.state.enableGroupDisbanding
import dev.ipf.whitenoise.android.state.exportConversationTranscriptFile
import dev.ipf.whitenoise.android.state.folderMemberIds
import dev.ipf.whitenoise.android.state.inviteMembers
import dev.ipf.whitenoise.android.state.inviteProfileToGroups
import dev.ipf.whitenoise.android.state.offersLocalAbandonment
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.profileAddableGroupsState
import dev.ipf.whitenoise.android.state.removeMember
import dev.ipf.whitenoise.android.state.setConversationVibrationPattern
import dev.ipf.whitenoise.android.state.stepDownAsAdmin
import dev.ipf.whitenoise.android.state.transferAdmin
import dev.ipf.whitenoise.android.state.updateCollapseLongMessagesInGroup
import dev.ipf.whitenoise.android.state.updateMessageRetention
import dev.ipf.whitenoise.android.ui.chats.ChatFolderPickerSheet
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactPickerScreen
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.chats.newchat.DangerActionRow
import dev.ipf.whitenoise.android.ui.chats.newchat.FlowSearchField
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupActingAccountIdentity
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupMemberReviewPair
import dev.ipf.whitenoise.android.ui.chats.newchat.MemberReviewGroup
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.ErrorCallout
import dev.ipf.whitenoise.android.ui.common.LocalSearchCollapse
import dev.ipf.whitenoise.android.ui.common.LocalWhiteNoiseHeaderScroll
import dev.ipf.whitenoise.android.ui.common.PreparedGroupAvatarContent
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.abbreviatesPublicKey
import dev.ipf.whitenoise.android.ui.common.conversationGroupAvatarAsset
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.common.rememberConversationGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.common.rememberMeasuredControlClick
import dev.ipf.whitenoise.android.ui.common.rememberSearchCollapse
import dev.ipf.whitenoise.android.ui.common.searchCollapseHost
import dev.ipf.whitenoise.android.ui.common.searchExpandReveal
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationMotionEnabled
import dev.ipf.whitenoise.android.ui.medialibrary.MediaLibraryRoute
import dev.ipf.whitenoise.android.ui.medialibrary.SharedContentCategory
import dev.ipf.whitenoise.android.ui.medialibrary.SharedMediaSection
import dev.ipf.whitenoise.android.ui.medialibrary.rememberSharedMediaTiles
import dev.ipf.whitenoise.android.ui.profile.ProfileAddToGroupsScreen
import dev.ipf.whitenoise.android.ui.profile.rememberProfileAccountWideGroups
import dev.ipf.whitenoise.android.ui.profile.rememberProfileGroupPickerState
import dev.ipf.whitenoise.android.ui.settings.ChatBubbleColorsScreen
import dev.ipf.whitenoise.android.ui.settings.ChatFolderEditScreen
import dev.ipf.whitenoise.android.ui.settings.DiagnosticRow
import dev.ipf.whitenoise.android.ui.settings.LocalSettingsCardMorph
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsGroupScope
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsSection
import dev.ipf.whitenoise.android.ui.settings.SettingsSwitch
import dev.ipf.whitenoise.android.ui.settings.rememberMeasuredContourClick
import dev.ipf.whitenoise.android.ui.settings.settingsCardMorph
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun GroupDetailsLocalDeleteControl(
    isDm: Boolean,
    readOnlyInvite: Boolean,
    isSelfMember: Boolean,
    membersVerified: Boolean,
    enabled: Boolean,
    inProgress: Boolean,
    onDeleteConfirmed: () -> Unit,
    identity: (@Composable () -> Unit)? = null,
    ownerKey: Any? = Unit,
) {
    if (readOnlyInvite || isSelfMember || !membersVerified) return
    var confirmOpen by remember(ownerKey) { mutableStateOf(false) }
    val currentDeleteAllowed by rememberUpdatedState(enabled && !inProgress)
    val deleteLabel = if (isDm) R.string.chat_row_action_delete_chat else R.string.chat_row_action_delete_group
    val dialogTitle = if (isDm) R.string.delete_chat_dialog_title else R.string.delete_group_dialog_title
    val dialogMessage = if (isDm) R.string.delete_chat_dialog_message else R.string.delete_group_dialog_message
    DangerActionRow(
        icon = ImageVector.vectorResource(R.drawable.ic_delete),
        title = stringResource(deleteLabel),
        enabled = enabled,
        inProgress = inProgress,
        onClick = { confirmOpen = true },
    )
    if (confirmOpen) {
        val request = remember(ownerKey) { Any() }
        ConfirmDialog(
            ownerKey = ownerKey to request,
            title = stringResource(dialogTitle),
            message = stringResource(dialogMessage),
            confirmLabel = stringResource(R.string.delete_group_confirm),
            onConfirm = {
                if (confirmOpen) {
                    confirmOpen = false
                    if (currentDeleteAllowed) onDeleteConfirmed()
                }
            },
            onDismiss = { confirmOpen = false },
            destructive = true,
            identity = identity.takeUnless { isDm },
            confirmEnabled = enabled,
        )
    }
}

// Members shown in Group Details before the "See all" expander.
private const val GROUP_MEMBERS_PREVIEW_COUNT = 5

/** Save just the stable picker fields needed to restore an in-progress Add Members selection. */
internal val AddMemberSelectionSaver =
    listSaver<SnapshotStateList<RecipientSearch.Candidate>, String>(
        save = { selected ->
            selected.flatMap { candidate ->
                listOf(candidate.accountIdHex, candidate.displayName, candidate.npub)
            }
        },
        restore = { saved ->
            saved
                .chunked(3)
                .mapNotNull { fields ->
                    fields.takeIf { it.size == 3 }?.let {
                        RecipientSearch.Candidate(
                            accountIdHex = it[0],
                            displayName = it[1],
                            npub = it[2],
                        )
                    }
                }.toMutableStateList()
        },
    )

private val InitialAdminSelectionSaver =
    listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() })

/** Chat overview that keeps native membership, moderation and notification owners behind its presentation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupDetailsScreen(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    onBack: () -> Unit,
    onLeft: () -> Unit,
    // Jump back to a message in the conversation (Shared Media tile tap). The
    // caller closes details and reuses the existing focus-scroll mechanism.
    onJumpToMessage: (String) -> Unit = {},
    // When true (empty-group CTA), open the shared add-member sheet after the
    // details screen is mounted so the initial invite path reuses later-add UI.
    autoOpenAddMember: Boolean = false,
    onAutoOpenAddMemberConsumed: () -> Unit = {},
    // When true (header Members shortcut), open the members page directly; Back from it leaves details.
    autoOpenMembers: Boolean = false,
    onAutoOpenMembersConsumed: () -> Unit = {},
    // Close details and raise the conversation's message search.
    onOpenSearch: (() -> Unit)? = null,
    onStartGroupWithPeer: (RecipientSearch.Candidate) -> Unit = {},
    onStartGroupWithPeerMeasured: ((RecipientSearch.Candidate, androidx.compose.ui.geometry.Rect?) -> Unit)? = null,
) {
    PreparedGroupAvatarContent(
        appState,
        listOfNotNull(conversationGroupAvatarAsset(appState, controller)),
        controller.boundAccountRef,
        surfaceIdentity = controller,
    ) {
        val reviewRuntime = remember(controller) { appState.runtimeGeneration }
        val detailsScrollState = key(controller) { rememberScrollState() }
        // The overview header shares the retained viewport's lifetime, including child destinations.
        val detailsScrollBehavior =
            key(controller) {
                TopAppBarDefaults.pinnedScrollBehavior(
                    state = rememberTopAppBarState(initialContentOffset = -detailsScrollState.value.toFloat()),
                )
            }
        LaunchedEffect(detailsScrollState, detailsScrollBehavior) {
            snapshotFlow { detailsScrollState.value }.collect {
                detailsScrollBehavior.state.contentOffset = -it.toFloat()
            }
        }
        // Child destinations replace the scaffold; retain its geometry with the retained scroll owner.
        var nameBottom by remember(controller) { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
        var headerBottom by remember(controller) { mutableFloatStateOf(0f) }
        val showHeaderIdentity by remember(detailsScrollState, controller) {
            derivedStateOf { detailsScrollState.value > 0 && headerBottom > 0f && nameBottom <= headerBottom }
        }
        val membersScrollState = key(controller) { rememberScrollState() }
        // Saved together so a recreated shortcut-opened Members page reopens with its Back-to-chat origin.
        var membersExpanded by rememberSaveable(controller.group.groupIdHex) {
            mutableStateOf(autoOpenMembers && !controller.isDm)
        }
        var membersAutoOpened by rememberSaveable(controller.group.groupIdHex) { mutableStateOf(autoOpenMembers) }
        var memberSearchOpen by remember(controller) { mutableStateOf(false) }
        var memberQuery by remember(controller, appState.activeAccountRef) { mutableStateOf("") }
        var memberSearchRetry by remember(controller) { mutableIntStateOf(0) }
        val memberResolution =
            rememberGroupMemberSearchResolution(memberQuery, appState, controller, memberSearchRetry)
        // Reuse the established account/runtime admission owner for protected route drawing.
        if (appState.appLock.screenVisible || !controller.isCurrentGroupMutationOwner(appState, reviewRuntime)) {
            BackHandler { onBack() }
            return@PreparedGroupAvatarContent
        }
        var showChatRelays by remember(controller) { mutableStateOf(false) }
        var showEditGroup by remember(controller.group.groupIdHex) { mutableStateOf(false) }
        var showGroupInfo by remember(controller.group.groupIdHex) { mutableStateOf(false) }
        var showNotificationSettings by remember(controller.group.groupIdHex) { mutableStateOf(false) }
        var showVibrationPatternDialog by remember { mutableStateOf(false) }
        var showDmAddToGroups by remember(controller.group.groupIdHex) { mutableStateOf(false) }
        var mediaLibraryCategory by remember(controller) { mutableStateOf<SharedContentCategory?>(null) }
        var showBubbleColors by remember(controller.group.groupIdHex) { mutableStateOf(false) }
        var showFolderCreate by remember(controller.group.groupIdHex) { mutableStateOf(false) }
        var addingDmPeerToGroups by remember(controller.group.groupIdHex) { mutableStateOf(false) }
        // A requested picker stays closed until the roster is authoritative. This
        // avoids excluding candidates against a stale or empty member snapshot.
        var showAddMember by rememberSaveable(controller.group.groupIdHex, appState.activeAccountRef) {
            mutableStateOf(false)
        }
        // Saved with the staged selection so an open invite review survives recreation alongside it.
        val addReviewOpen =
            rememberSaveable(controller.group.groupIdHex, appState.activeAccountRef) { mutableStateOf(false) }
        val addSelection =
            rememberSaveable(
                controller.group.groupIdHex,
                appState.activeAccountRef,
                saver = AddMemberSelectionSaver,
            ) {
                mutableStateListOf<RecipientSearch.Candidate>()
            }
        val addAdminSelection =
            rememberSaveable(
                controller.group.groupIdHex,
                appState.activeAccountRef,
                saver = InitialAdminSelectionSaver,
            ) {
                mutableStateListOf<String>()
            }
        LaunchedEffect(addSelection.map { it.accountIdHex }) {
            addAdminSelection.removeAll { admin ->
                addSelection.none { it.accountIdHex.equals(admin, ignoreCase = true) }
            }
        }
        var addMemberAutoOpened by rememberSaveable(controller.group.groupIdHex) { mutableStateOf(autoOpenAddMember) }
        // Sole-admin "Transfer admin first" picker. Surfaced from the blocked
        // leave path and the Admins prompt so a trapped sole admin can hand the
        // role to another member (issue #417).
        var showTransferAdmin by remember(controller.group.groupIdHex) { mutableStateOf(false) }
        var disbandConfirmOpen by remember(controller, appState.activeAccountRef, appState.runtimeGeneration) {
            mutableStateOf(false)
        }
        var accountDevicesConfirmOpen by remember(controller, appState.activeAccountRef, appState.runtimeGeneration) {
            mutableStateOf(false)
        }
        val accountDevicesActivation =
            observeGroupAccountDevicesActivation(controller) { accountDevicesConfirmOpen = false }
        // #1131: when set, the transfer-admin picker is being used as the first step
        // of a sole-admin Leave (3+ members) — picking transfers admin then leaves,
        // rather than the standalone transfer-only action. Holds the group name for
        // the leave call/toast.
        var transferThenLeaveName by remember(controller.group.groupIdHex) { mutableStateOf<String?>(null) }
        // Follow remote admin changes while the details screen stays open.
        GroupManagementSnapshotRefreshEffect(controller)
        var mlsState by remember(controller.group.groupIdHex) { mutableStateOf<AppGroupMlsStateFfi?>(null) }
        var mlsLoading by remember(controller.group.groupIdHex) { mutableStateOf(false) }
        var pushDebugInfo by remember(controller.group.groupIdHex) { mutableStateOf<GroupPushDebugInfoFfi?>(null) }
        var pushDebugLoading by remember(controller.group.groupIdHex) { mutableStateOf(false) }
        // Scoped to the visible group; the controller mutation continues on appState
        // if the user switches conversations, but this sheet stops tracking it.
        var activeMutation by remember(controller, appState.activeAccountRef) {
            mutableStateOf<ActiveGroupMutation?>(null)
        }
        // Tap-time archive direction, recorded before the mutation coroutine is
        // dispatched so the menu's progress label can never read the pre-intent
        // presented state and show the opposite direction.
        val pendingReview =
            remember(controller, appState.activeAccountRef, appState.runtimeGeneration) {
                DetailsReviewSlot()
            }
        var pendingConfirm by pendingReview
        // #3310: the reviewed local-abandonment target and a Leave that did not finish, scoped to this account.
        val abandonment = remember(controller, appState.activeAccountRef) { GroupAbandonmentReview() }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val transcriptSave = rememberConversationTranscriptSave(appState, controller)
        var transcriptExportInFlight by remember(controller.group.groupIdHex) { mutableStateOf(false) }
        var pendingTranscriptShareFile by remember(controller.group.groupIdHex) { mutableStateOf<java.io.File?>(null) }
        val transcriptShareLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                pendingTranscriptShareFile?.delete()
                pendingTranscriptShareFile = null
            }
        val clipboard = LocalClipboardManager.current
        val readOnlyInvite = controller.group.pendingConfirmation
        val isDm = controller.isDm
        val groupTerminal = controller.group.disbanding || controller.group.disbanded
        val rosterReady = controller.memberRosterState == GroupRosterLoadState.READY
        val canEdit = controller.canEditGroupSettings
        // Presentation-only: a warm member seed plus the admin list on the group
        // record enables administration on the first frame; the invite commit
        // still requires the authoritative roster inside the controller.
        val canAdministerMembers =
            !isDm &&
                canEdit &&
                memberAdministrationPresentable(controller.memberRosterState, controller.seededSelfMember)
        val mutationOwnerCurrent = controller.isCurrentGroupMutationOwner(appState, reviewRuntime)
        val mutationsBlocked = activeMutation != null || controller.mutationInFlight || !mutationOwnerCurrent
        val openAddMembers = {
            addMemberAutoOpened = false
            showAddMember = true
        }
        val detailsOpenedAtMs = remember(controller.group.groupIdHex) { SystemClock.elapsedRealtime() }
        var administrationLatencyReported by remember(controller.group.groupIdHex) { mutableStateOf(false) }
        LaunchedEffect(canAdministerMembers) {
            if (canAdministerMembers && !administrationLatencyReported) {
                administrationLatencyReported = true
                reportMemberAdministrationEnabledLatency(
                    elapsedMs = SystemClock.elapsedRealtime() - detailsOpenedAtMs,
                    rosterState = controller.memberRosterState,
                )
            }
        }
        LaunchedEffect(autoOpenMembers) {
            if (autoOpenMembers && !isDm) {
                membersAutoOpened = true
                membersExpanded = true
                onAutoOpenMembersConsumed()
            }
        }
        LaunchedEffect(autoOpenAddMember, canAdministerMembers) {
            if (autoOpenAddMember && canAdministerMembers) {
                addMemberAutoOpened = true
                showAddMember = true
                onAutoOpenAddMemberConsumed()
            }
        }
        LaunchedEffect(canAdministerMembers) {
            if (!canAdministerMembers) {
                showAddMember = false
                addReviewOpen.value = false
                addSelection.clear()
                addAdminSelection.clear()
            }
        }
        val noShareTargetText = stringResource(R.string.no_share_target_available)
        val groupTitleCopy = rememberGroupTitleCopy()
        appState.profileRevisionForCompose
        val conversationTitle = controller.title(groupTitleCopy)
        val actingMemberHex = controller.boundAccountIdHex?.takeIf(String::isNotBlank)
        val reviewIdentity: @Composable (String) -> Unit = { memberHex ->
            val avatar = rememberConversationGroupAvatar(appState, controller)
            GroupActingAccountIdentity(appState, controller.boundAccountRef)
            GroupMemberReviewPair(
                appState = appState,
                group =
                    MemberReviewGroup(
                        controller.group.groupIdHex,
                        conversationTitle,
                        avatar.pictureUrl,
                        avatar.image,
                        groupAnimation = avatar.groupAnimation,
                    ),
                member = RecipientSearch.Candidate(memberHex, "", appState.npubForDisplay(memberHex)),
            )
        }
        val ownIdentity: (@Composable () -> Unit)? =
            if (actingMemberHex == null) null else ({ reviewIdentity(actingMemberHex) })
        val activeAccountIdHex = appState.activeAccount?.accountIdHex
        val dmPeerAccountIdHex =
            if (isDm) {
                controller.avatarAccount
                    ?: controller.members
                        .firstOrNull { !GroupProjector.isActiveAccountMember(it, activeAccountIdHex) }
                        ?.memberIdHex
            } else {
                null
            }
        val dmPeerNpub = dmPeerAccountIdHex?.let(appState::npub)
        // Chats beyond the retained chat-list window, read only while the picker is open (#2983).
        val dmAccountWideGroups =
            rememberProfileAccountWideGroups(appState, enabled = showDmAddToGroups && dmPeerAccountIdHex != null)
        val dmAccountWide = dmAccountWideGroups.current()
        val dmAddableGroupsState =
            rememberProfileGroupPickerState(
                appState,
                dmPeerAccountIdHex,
                dmAccountWide,
                WhiteNoiseAppState::profileAddableGroupsState,
            )
        LaunchedEffect(showDmAddToGroups, dmAddableGroupsState.pendingGroupIds) {
            if (showDmAddToGroups && dmAddableGroupsState.pendingGroupIds.isNotEmpty()) {
                dmAccountWideGroups.requestMembers(dmAddableGroupsState.pendingGroupIds)
            }
        }
        val detailsDestination =
            when {
                showDmAddToGroups && dmPeerAccountIdHex != null -> "add-to-groups"
                mediaLibraryCategory != null -> "media-$mediaLibraryCategory"
                showBubbleColors -> "bubble-colors"
                showNotificationSettings -> "notifications"
                showEditGroup -> "edit"
                showFolderCreate -> "create-folder"
                showAddMember && canAdministerMembers -> "add-members"
                showGroupInfo -> "group-info"
                membersExpanded && !isDm -> "members"
                showChatRelays -> "relays"
                else -> "details"
            }
        val detailsOwner = controller to appState.runtimeGeneration
        val parentMorph = LocalSettingsCardMorph.current
        val detailsMorph =
            remember(detailsOwner, parentMorph) {
                dev.ipf.whitenoise.android.ui.settings
                    .SettingsCardMorph(fullScreen = true, parent = parentMorph)
            }
        DisposableEffect(detailsMorph, detailsDestination, detailsScrollState, detailsScrollBehavior) {
            val release =
                if (detailsDestination == "details") {
                    detailsMorph.observeCaptureInputs {
                        Triple(
                            detailsScrollState.value,
                            detailsScrollBehavior.state.contentOffset to detailsScrollBehavior.state.heightOffset,
                            showHeaderIdentity,
                        )
                    }
                } else {
                    null
                }
            onDispose { release?.invoke() }
        }
        val detailsForward = detailsDestination != "details"
        val (_, detailsMotion) =
            dev.ipf.whitenoise.android.ui.settings.rememberSettingsCardMorphHost(
                morph = detailsMorph,
                target = detailsDestination,
                ownerKey = detailsOwner,
                motion =
                    rememberConversationMotionEnabled() &&
                        !(
                            (addMemberAutoOpened && detailsDestination == "add-members") ||
                                (membersAutoOpened && detailsDestination == "members")
                        ),
                forward = detailsForward,
            )

        val detailsViewport =
            dev.ipf.whitenoise.android.ui.settings
                .rememberViewport(detailsMorph.page)

        /** All child routes share one measured container transform and dispose their old controls immediately. */
        @Composable
        fun detailsRoute(content: @Composable () -> Unit) {
            CompositionLocalProvider(
                LocalSettingsCardMorph provides detailsMorph,
                dev.ipf.whitenoise.android.ui.settings.LocalSettingsNativeViewport provides detailsViewport.first,
            ) {
                dev.ipf.whitenoise.android.ui.common
                    .WindowSecureFlag(enabled = !appState.allowChatScreenshotsInChats)
                Box(Modifier.fillMaxSize().then(detailsMotion)) { content() }
            }
        }
        if (showDmAddToGroups && dmPeerAccountIdHex != null) {
            detailsRoute {
                ProfileAddToGroupsScreen(
                    appState = appState,
                    target =
                        RecipientSearch.Candidate(dmPeerAccountIdHex, conversationTitle, dmPeerNpub.orEmpty()),
                    state = dmAddableGroupsState,
                    busy = addingDmPeerToGroups,
                    onClose = { if (!addingDmPeerToGroups) showDmAddToGroups = false },
                    onRetry = {
                        dmAccountWideGroups.requestMembers(dmAddableGroupsState.pendingGroupIds, retry = true)
                    },
                    onAdd = { groups ->
                        if (!addingDmPeerToGroups) {
                            addingDmPeerToGroups = true
                            appState.launchMutation {
                                try {
                                    val added =
                                        appState.inviteProfileToGroups(
                                            targetRef = dmPeerAccountIdHex,
                                            targetGroupIds = groups.map { it.group.groupIdHex },
                                        )
                                    if (added) showDmAddToGroups = false
                                } finally {
                                    addingDmPeerToGroups = false
                                }
                            }
                        }
                    },
                )
            }
            return@PreparedGroupAvatarContent
        }
        val canShowEditAction = !isDm && canEdit
        LaunchedEffect(canShowEditAction) {
            if (!canShowEditAction) showEditGroup = false
        }
        val chatNotificationState by appState.chatMutePreferences.state.collectAsState()
        val vibrationSelections by appState.conversationVibrationPreferences.state.collectAsState()
        val notificationModes = chatNotificationState.notificationModes
        val conversationNotifyMode =
            remember(appState.activeAccountRef, controller.group.groupIdHex, notificationModes) {
                appState.conversationNotifyMode(controller.group.groupIdHex)
            }
        val conversationVibrationPattern =
            remember(appState.activeAccountRef, controller.group.groupIdHex, vibrationSelections) {
                appState.conversationVibrationPattern(controller.group.groupIdHex)
            }
        // Shared with the optional header shortcut so both run the same mute/unmute commands.
        val muteControl = rememberConversationMuteControl(appState, controller)

        suspend fun refreshMlsDetails() {
            if (!appState.developerMode) return
            mlsLoading = true
            try {
                mlsState = controller.groupMlsState()
            } finally {
                mlsLoading = false
            }
        }

        suspend fun refreshPushDebugInfo() {
            if (!appState.developerMode) return
            pushDebugLoading = true
            try {
                pushDebugInfo = controller.groupPushDebugInfo()
            } finally {
                pushDebugLoading = false
            }
        }

        /** Runs a group mutation under the shared lock, recording the action and target for the row status. */
        fun runGroupMutation(
            action: GroupMutationAction,
            mutation: suspend () -> Boolean,
            target: String? = null,
            onSuccess: () -> Unit = {},
            onAccepted: () -> Unit = {},
        ) {
            // Launched on a process-lifetime scope so the MLS commit + Nostr
            // publish complete even if the user dismisses this sheet mid-flight.
            // The refreshMembers() + appState.present(toast) inside each
            // ConversationController.* method then always run, regardless of
            // whether this composable is still on screen.
            val canRun =
                activeMutation == null &&
                    !controller.mutationInFlight &&
                    controller.isCurrentGroupMutationOwner(appState, reviewRuntime)
            if (!canRun) return
            activeMutation = ActiveGroupMutation(action, target)
            controller.clearLastMutationError()
            onAccepted()
            appState.launchMutation {
                try {
                    val succeeded = mutation()
                    abandonment.recordMutation(leave = action == GroupMutationAction.Leave, succeeded = succeeded)
                    if (succeeded) onSuccess()
                } finally {
                    // onSuccess() may have already dismissed this sheet; clearing
                    // detached Compose state is harmless in that case.
                    activeMutation = null
                }
            }
        }

        // Route the Leave action (#416) to the right confirm dialog based on the
        // user's role in the group. Shared by the overflow item and the bottom
        // destructive button so both surfaces stay in lockstep. The display name
        // is resolved here (not in the dialog) so the variants read the same title.
        fun requestLeave(displayName: String) {
            controller.clearLastMutationError()
            appState.launchMutation {
                when (controller.leaveAction()) {
                    LeaveAction.SoleMemberDeletesGroup -> pendingConfirm = DetailsConfirm.LeaveSoleMember(displayName)
                    // #1131: instead of the old Cancel-only dead-end, offer transfer
                    // then leave. One candidate → a single confirm; 3+ → the picker in
                    // leave mode. The (unexpected) no-candidate case keeps the old gate.
                    LeaveAction.SoleAdminMustTransfer -> {
                        val candidates = controller.transferAdminCandidates()
                        when {
                            candidates.size == 1 ->
                                pendingConfirm = DetailsConfirm.LeaveSoleAdminTransfer(displayName, candidates.first())
                            candidates.size >= 2 -> {
                                transferThenLeaveName = displayName
                                showTransferAdmin = true
                            }
                            else -> pendingConfirm = DetailsConfirm.LeaveSoleAdmin(displayName)
                        }
                    }
                    LeaveAction.Standard -> pendingConfirm = DetailsConfirm.Leave(displayName)
                }
            }
        }

        /**
         * Opens the abandonment review for this exact conversation under the current account (#3310, #3282), and
         * only once the conversation's account is the active one, so the review names the account that will forget.
         */
        fun requestAbandonment() {
            val account = abandonmentAccount(controller.boundAccountRef, appState.activeAccountRef) ?: return
            abandonment.open(
                GroupAbandonmentTarget(
                    accountRef = account,
                    groupIdHex = controller.group.groupIdHex,
                    conversationTitle = conversationTitle,
                    isDm = isDm,
                ),
            )
        }

        fun exportTranscript() {
            if (transcriptExportInFlight || transcriptSave.busy) return
            transcriptExportInFlight = true
            appState.launchMutation {
                var shareSheetLaunched = false
                try {
                    val file = controller.exportConversationTranscriptFile(context.cacheDir) ?: return@launchMutation
                    pendingTranscriptShareFile = file
                    try {
                        if (!controller.ownsRetainedForegroundPresentation) {
                            throw CancellationException("transcript owner changed")
                        }
                        transcriptShareLauncher.launch(conversationTranscriptShareIntent(context, file))
                        shareSheetLaunched = true
                    } catch (_: ActivityNotFoundException) {
                        pendingTranscriptShareFile = null
                        file.delete()
                        appState.present(
                            R.string.toast_couldnt_export_transcript,
                            AppText.Plain(noShareTargetText),
                            copyable = true,
                        )
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) {
                        if (!shareSheetLaunched) {
                            pendingTranscriptShareFile?.delete()
                            pendingTranscriptShareFile = null
                        }
                        throw error
                    }
                    pendingTranscriptShareFile?.delete()
                    pendingTranscriptShareFile = null
                    appState.presentFailure(
                        R.string.toast_couldnt_export_transcript,
                        "CONVERSATION_TRANSCRIPT_SHARE",
                        error,
                    )
                } finally {
                    transcriptExportInFlight = false
                }
            }
        }

        LaunchedEffect(
            appState.developerMode,
            controller.group.groupIdHex,
            controller.group.admins,
            controller.members.map { it.memberIdHex },
        ) {
            refreshMlsDetails()
            refreshPushDebugInfo()
        }

        // Observe the native result from the current composition, including after rotation.
        // Optimistic pending rows do not establish that the invitation succeeded.
        val inviteSelectionApplied =
            rosterReady &&
                addSelection.isNotEmpty() &&
                addSelection.all { selected ->
                    controller.members.any { it.memberIdHex.equals(selected.accountIdHex, ignoreCase = true) }
                } &&
                addAdminSelection.all { admin ->
                    controller.group.admins.any { it.equals(admin, ignoreCase = true) }
                }
        LaunchedEffect(showAddMember, inviteSelectionApplied, controller.mutationInFlight) {
            if (showAddMember && inviteSelectionApplied && !controller.mutationInFlight) {
                addReviewOpen.value = false
                addSelection.clear()
                addAdminSelection.clear()
                if (addMemberAutoOpened) onBack() else showAddMember = false
            }
        }

        val sharedMediaTiles = rememberSharedMediaTiles(controller, appState)
        val ttsAutoReadPrefs by appState.ttsAutoReadPreferences.state.collectAsState()
        val autoReadOverride =
            remember(ttsAutoReadPrefs, appState.activeAccountRef, controller.group.groupIdHex) {
                appState.activeAccountRef?.let { accountRef ->
                    appState.ttsAutoReadPreferences.overrideFor(accountRef, controller.group.groupIdHex)
                }
            }
        val autoReadSettingLabel =
            stringResource(
                ttsAutoReadSettingLabelRes(
                    override = autoReadOverride,
                    globalDefaultEnabled = ttsAutoReadPrefs.globalDefaultEnabled,
                ),
            )
        val autoReadPopupOwner = remember(controller, appState.activeAccountRef, appState.runtimeGeneration) { Any() }
        var autoReadPopupSource by remember(autoReadPopupOwner) {
            mutableStateOf<dev.ipf.whitenoise.android.ui.common.PopupSourceActivation?>(null)
        }
        var showAutoReadPicker by remember(autoReadPopupOwner) { mutableStateOf(false) }
        var showTranslationPicker by remember(controller) { mutableStateOf(false) }
        var showDisappearingPicker by remember(controller.group.groupIdHex) { mutableStateOf(false) }
        var showFolderPicker by remember(controller.group.groupIdHex) { mutableStateOf(false) }

        mediaLibraryCategory?.let { category ->
            BackHandler { mediaLibraryCategory = null }
            detailsRoute {
                MediaLibraryRoute(
                    tiles = sharedMediaTiles,
                    controller = controller,
                    appState = appState,
                    category = category,
                    onBack = { mediaLibraryCategory = null },
                    onJumpToMessage = onJumpToMessage,
                )
            }
            return@PreparedGroupAvatarContent
        }

        if (showBubbleColors) {
            BackHandler { showBubbleColors = false }
            detailsRoute {
                ChatBubbleColorsScreen(
                    appState = appState,
                    onBack = { showBubbleColors = false },
                    groupIdHex = controller.group.groupIdHex,
                    destination = "bubble-colors",
                )
            }
            return@PreparedGroupAvatarContent
        }

        MuteDurationHost(muteControl)

        if (showNotificationSettings) {
            BackHandler { showNotificationSettings = false }
            detailsRoute {
                ConversationNotificationSettingsScreen(
                    appState = appState,
                    groupIdHex = controller.group.groupIdHex,
                    conversationTitle = conversationTitle,
                    conversationAvatarUrl = controller.avatarUrl,
                    isDm = isDm,
                    isMuted = muteControl.muted,
                    muteCommandPending = muteControl.pending,
                    muteExpiryMillis = muteControl.mutedUntilMs,
                    notifyForMode = muteControl.restoreMode,
                    vibrationPattern = conversationVibrationPattern,
                    onBack = { showNotificationSettings = false },
                    onToggleMute = { turnOn ->
                        if (turnOn) muteControl.requestMute() else muteControl.unmute()
                    },
                    onChooseVibrationPattern = { showVibrationPatternDialog = true },
                )
            }
            if (showVibrationPatternDialog) {
                VibrationPatternDialog(
                    currentPattern = conversationVibrationPattern,
                    onDismiss = { showVibrationPatternDialog = false },
                    onSelect = { pattern ->
                        showVibrationPatternDialog = false
                        appState.setConversationVibrationPattern(
                            groupIdHex = controller.group.groupIdHex,
                            isDm = isDm,
                            conversationTitle = conversationTitle,
                            pattern = pattern,
                        )
                    },
                )
            }
            return@PreparedGroupAvatarContent
        }

        BackHandler { onBack() }

        if (showEditGroup) {
            detailsRoute {
                GroupEditScreen(appState = appState, controller = controller, onBack = { showEditGroup = false })
            }
            return@PreparedGroupAvatarContent
        }

        val folderAccountRef = appState.activeAccountRef
        if (showFolderCreate && folderAccountRef != null) {
            detailsRoute {
                ChatFolderEditScreen(
                    appState = appState,
                    accountRef = folderAccountRef,
                    folderId = null,
                    onClose = { showFolderCreate = false },
                    initialManualChatIds = setOf(controller.group.groupIdHex.lowercase(Locale.ROOT)),
                )
            }
            return@PreparedGroupAvatarContent
        }

        if (showAddMember && canAdministerMembers) {
            val adding = activeMutation?.action == GroupMutationAction.InviteMember
            val inviteProjection =
                largeGroupInviteProjection(
                    rosterReady = rosterReady,
                    authoritativeMemberIds = controller.members.map { it.memberIdHex },
                    activeAccountIdHex = activeAccountIdHex,
                    pendingInviteMemberIds = controller.pendingInviteMemberRefs,
                    stagedRecipientIds = addSelection.map { it.accountIdHex },
                )

            /** Submits the currently staged, normalized recipients and closes the picker only after native success. */
            fun submitSelectedInvites(
                selected: List<RecipientSearch.Candidate> = addSelection.toList(),
                selectedAdmins: Set<String> = addAdminSelection.toSet(),
            ) {
                if (!canAdministerMembers || !rosterReady) return
                val refs = selected.map { it.accountIdHex }
                val admins = refs.filter { id -> selectedAdmins.any { it.equals(id, ignoreCase = true) } }
                runGroupMutation(
                    action = GroupMutationAction.InviteMember,
                    mutation = { controller.inviteMembers(refs, initialAdminRefs = admins) },
                )
            }

            val reviewAvatar = rememberConversationGroupAvatar(appState, controller)
            detailsRoute {
                ContactPickerScreen(
                    reviewGroup =
                        MemberReviewGroup(
                            controller.group.groupIdHex,
                            conversationTitle,
                            reviewAvatar.pictureUrl,
                            reviewAvatar.image,
                            groupAnimation = reviewAvatar.groupAnimation,
                        ),
                    appState = appState,
                    title = stringResource(R.string.add_member),
                    selected = addSelection,
                    reviewOpen = addReviewOpen,
                    onBack = {
                        // When auto-opened from the empty-group CTA, Back returns to the
                        // conversation (via the details onBack) rather than exposing the
                        // details body the user never intended to see.
                        if (!adding) {
                            addReviewOpen.value = false
                            addSelection.clear()
                            addAdminSelection.clear()
                            if (addMemberAutoOpened) onBack() else showAddMember = false
                        }
                    },
                    onConfirm = { submitSelectedInvites() },
                    reviewDescription =
                        if (inviteProjection?.shouldWarn == true) {
                            stringResource(R.string.large_group_invite_confirm_message)
                        } else {
                            null
                        },
                    confirmIcon = ImageVector.vectorResource(R.drawable.ic_check),
                    initialAdminIds = addAdminSelection.toSet(),
                    onInitialAdminChange = { member, admin ->
                        if (!adding && canAdministerMembers) {
                            addAdminSelection.removeAll { it.equals(member.accountIdHex, ignoreCase = true) }
                            val selected =
                                addSelection.any {
                                    it.accountIdHex.equals(member.accountIdHex, ignoreCase = true)
                                }
                            if (admin && selected) {
                                addAdminSelection.add(member.accountIdHex)
                            }
                        }
                    },
                    confirmLabel = stringResource(R.string.add_member),
                    // The picker opens optimistically from the seed, but the commit
                    // needs the authoritative roster: hold the confirm busy until it
                    // lands instead of letting a tap hit the controller's gate silently.
                    busy = adding || controller.mutationInFlight || !rosterReady,
                    autoSelectResolvedIdentifier = true,
                    excludeAccountIdHexes =
                        (
                            controller.presentedMembers.map { it.memberIdHex } + controller.pendingInviteMemberRefs
                        ).toSet(),
                    footer = {
                        if (inviteProjection?.shouldWarn == true) {
                            LargeGroupInviteWarningBanner()
                        }
                    },
                )
            }
            return@PreparedGroupAvatarContent
        }

        if (showGroupInfo) {
            BackHandler { showGroupInfo = false }
            detailsRoute {
                GroupInfoScreen(
                    groupIdHex = controller.group.groupIdHex,
                    nostrGroupIdHex = controller.group.nostrGroupIdHex,
                    onBack = { showGroupInfo = false },
                )
            }
            return@PreparedGroupAvatarContent
        }

        if (showTransferAdmin) {
            TransferAdminSheet(
                controller = controller,
                appState = appState,
                busy = activeMutation != null || controller.mutationInFlight,
                onPick = { member ->
                    showTransferAdmin = false
                    val leaveName = transferThenLeaveName
                    transferThenLeaveName = null
                    pendingConfirm =
                        if (leaveName != null) {
                            DetailsConfirm.LeaveSoleAdminTransfer(leaveName, member)
                        } else {
                            DetailsConfirm.TransferAdmin(member)
                        }
                },
                onDismiss = {
                    showTransferAdmin = false
                    transferThenLeaveName = null
                },
            )
        }

        pendingConfirm?.let { confirm ->
            val memberIds = controller.members.map { it.memberIdHex.lowercase(Locale.ROOT) }.toSet()
            val adminIds =
                controller.group.admins
                    .map { it.lowercase(Locale.ROOT) }
                    .toSet()
            val reviewTicket = pendingReview.ticket
            val reviewedRoster = remember(reviewTicket) { memberIds to adminIds }
            val administration =
                confirm is DetailsConfirm.RemoveMember ||
                    confirm is DetailsConfirm.TransferAdmin ||
                    confirm is DetailsConfirm.StepDownAdmin ||
                    confirm == DetailsConfirm.StepDownSoleAdmin ||
                    confirm is DetailsConfirm.LeaveSoleAdminTransfer
            val departureHandover = confirm is DetailsConfirm.LeaveSoleAdminTransfer
            val currentReview =
                controller.boundAccountRef == appState.activeAccountRef &&
                    reviewRuntime == appState.runtimeGeneration &&
                    reviewedRoster == (memberIds to adminIds) &&
                    controller.isSelfMember &&
                    (!administration || controller.canClaimAdministrationReview(allowDmDeparture = departureHandover))
            LaunchedEffect(currentReview, reviewTicket) {
                if (!currentReview && pendingReview.ticket === reviewTicket) pendingConfirm = null
            }

            /** Claims the pending review only while its ticket, account, runtime and roster still match. */
            fun claimReview(): Boolean {
                val liveMembers = controller.members.map { it.memberIdHex.lowercase(Locale.ROOT) }.toSet()
                val liveAdmins =
                    controller.group.admins
                        .map { it.lowercase(Locale.ROOT) }
                        .toSet()
                val claimed =
                    when {
                        pendingReview.ticket !== reviewTicket || pendingConfirm == null -> false
                        controller.boundAccountRef != appState.activeAccountRef -> false
                        reviewRuntime != appState.runtimeGeneration -> false
                        reviewedRoster != (liveMembers to liveAdmins) || !controller.isSelfMember -> false
                        administration &&
                            !controller.canClaimAdministrationReview(allowDmDeparture = departureHandover) -> false
                        activeMutation != null || controller.mutationInFlight -> false
                        appState.signOutInProgress || appState.wipeInProgress -> false
                        else -> {
                            pendingConfirm = null
                            true
                        }
                    }
                if (!claimed && pendingReview.ticket === reviewTicket) pendingConfirm = null
                return claimed
            }

            val kind =
                when (confirm) {
                    is DetailsConfirm.RemoveMember -> GroupDetailsConfirmationKind.RemoveMember
                    is DetailsConfirm.TransferAdmin -> GroupDetailsConfirmationKind.TransferAdmin
                    is DetailsConfirm.StepDownAdmin -> GroupDetailsConfirmationKind.StepDown
                    DetailsConfirm.StepDownSoleAdmin -> GroupDetailsConfirmationKind.StepDownSoleAdmin
                    is DetailsConfirm.Leave -> GroupDetailsConfirmationKind.Leave
                    is DetailsConfirm.LeaveSoleMember -> GroupDetailsConfirmationKind.LeaveSoleMember
                    is DetailsConfirm.LeaveSoleAdmin -> GroupDetailsConfirmationKind.BlockedLeave
                    is DetailsConfirm.LeaveSoleAdminTransfer -> GroupDetailsConfirmationKind.TransferThenLeave
                }
            val recipientHex =
                when (confirm) {
                    is DetailsConfirm.RemoveMember -> confirm.member.memberIdHex
                    is DetailsConfirm.TransferAdmin -> confirm.member.memberIdHex
                    is DetailsConfirm.LeaveSoleAdminTransfer -> confirm.newAdmin.memberIdHex
                    else -> null
                }
            val memberHex = groupActionMemberHex(kind, actingMemberHex, recipientHex)
            val identity: (@Composable () -> Unit)? =
                if (memberHex == null) null else ({ reviewIdentity(memberHex) })
            when (confirm) {
                is DetailsConfirm.RemoveMember ->
                    GroupDetailsConfirmation(
                        ownerKey = reviewTicket,
                        identity = identity,
                        confirmEnabled = memberHex != null && currentReview && !mutationsBlocked,
                        model =
                            GroupDetailsConfirmationState.RemoveMember(
                                memberName = controller.memberDisplayName(confirm.member),
                                onConfirm = confirmAction@{
                                    if (!claimReview()) return@confirmAction
                                    runGroupMutation(
                                        action = GroupMutationAction.RemoveMember,
                                        mutation = { controller.removeMember(confirm.member) },
                                        target = confirm.member.memberIdHex,
                                    )
                                },
                            ),
                        onDismiss = { if (pendingReview.ticket === reviewTicket) pendingConfirm = null },
                    )
                is DetailsConfirm.TransferAdmin ->
                    GroupDetailsConfirmation(
                        ownerKey = reviewTicket,
                        identity = identity,
                        confirmEnabled = memberHex != null && currentReview && !mutationsBlocked,
                        model =
                            GroupDetailsConfirmationState.TransferAdmin(
                                memberName = controller.memberDisplayName(confirm.member),
                                onConfirm = confirmAction@{
                                    if (!claimReview()) return@confirmAction
                                    runGroupMutation(
                                        action = GroupMutationAction.TransferAdmin,
                                        mutation = { controller.transferAdmin(confirm.member) },
                                        target = confirm.member.memberIdHex,
                                    )
                                },
                            ),
                        onDismiss = { if (pendingReview.ticket === reviewTicket) pendingConfirm = null },
                    )
                is DetailsConfirm.StepDownAdmin ->
                    GroupDetailsConfirmation(
                        ownerKey = reviewTicket,
                        identity = identity,
                        confirmEnabled = memberHex != null && currentReview && !mutationsBlocked,
                        model =
                            GroupDetailsConfirmationState.StepDown(
                                onConfirm = confirmAction@{
                                    if (!claimReview()) return@confirmAction
                                    runGroupMutation(
                                        action = GroupMutationAction.SelfDemoteAdmin,
                                        mutation = { controller.stepDownAsAdmin() },
                                        target = confirm.member.memberIdHex,
                                    )
                                },
                            ),
                        onDismiss = { if (pendingReview.ticket === reviewTicket) pendingConfirm = null },
                    )
                DetailsConfirm.StepDownSoleAdmin ->
                    GroupDetailsConfirmation(
                        ownerKey = reviewTicket,
                        identity = identity,
                        confirmEnabled = memberHex != null && currentReview && !mutationsBlocked,
                        model =
                            GroupDetailsConfirmationState.StepDownSoleAdmin(
                                onConfirm = confirmAction@{
                                    if (!claimReview()) return@confirmAction
                                    showTransferAdmin = true
                                },
                            ),
                        onDismiss = { if (pendingReview.ticket === reviewTicket) pendingConfirm = null },
                    )
                is DetailsConfirm.Leave ->
                    GroupDetailsConfirmation(
                        ownerKey = reviewTicket,
                        identity = identity,
                        confirmEnabled = memberHex != null && currentReview && !mutationsBlocked,
                        model =
                            GroupDetailsConfirmationState.Leave(
                                groupName = confirm.groupName,
                                onConfirm = confirmAction@{
                                    if (!claimReview()) return@confirmAction
                                    // Process-lifetime scope so a swipe-dismiss mid-leave doesn't
                                    // kill the toast/refresh; onDismiss + onLeft are safe Compose
                                    // state writes from Main.immediate.
                                    runGroupMutation(
                                        action = GroupMutationAction.Leave,
                                        mutation = { controller.leaveGroup(displayName = confirm.groupName) },
                                        onSuccess = {
                                            onLeft()
                                        },
                                    )
                                },
                            ),
                        onDismiss = { if (pendingReview.ticket === reviewTicket) pendingConfirm = null },
                    )
                // A sole member dissolves the group instead of choosing an impossible successor.
                is DetailsConfirm.LeaveSoleMember ->
                    GroupDetailsConfirmation(
                        ownerKey = reviewTicket,
                        identity = identity,
                        confirmEnabled = memberHex != null && currentReview && !mutationsBlocked,
                        model =
                            GroupDetailsConfirmationState.LeaveSoleMember(
                                groupName = confirm.groupName,
                                onConfirm = confirmAction@{
                                    if (!claimReview()) return@confirmAction
                                    runGroupMutation(
                                        action = GroupMutationAction.Leave,
                                        mutation = { controller.leaveGroup(displayName = confirm.groupName) },
                                        onSuccess = {
                                            onLeft()
                                        },
                                    )
                                },
                            ),
                        onDismiss = { if (pendingReview.ticket === reviewTicket) pendingConfirm = null },
                    )
                is DetailsConfirm.LeaveSoleAdmin ->
                    // Fallback for the (unexpected) no-transfer-candidate case. The normal sole-admin Leave
                    // routes to LeaveSoleAdminTransfer or the picker in leave mode (#1131). With no eligible
                    // successor, local abandonment is the way out of this gate (#3310).
                    GroupSoleAdminLeaveDialog(
                        groupName = confirm.groupName,
                        isDm = isDm,
                        identity = identity,
                        onAbandon = abandonAction@{
                            if (!claimReview()) return@abandonAction
                            requestAbandonment()
                        },
                        onDismiss = { if (pendingReview.ticket === reviewTicket) pendingConfirm = null },
                    )
                is DetailsConfirm.LeaveSoleAdminTransfer ->
                    GroupDetailsConfirmation(
                        ownerKey = reviewTicket,
                        identity = identity,
                        confirmEnabled = memberHex != null && currentReview && !mutationsBlocked,
                        model =
                            GroupDetailsConfirmationState.TransferThenLeave(
                                memberName = controller.memberDisplayName(confirm.newAdmin),
                                onConfirm = confirmAction@{
                                    if (!claimReview()) return@confirmAction
                                    runGroupMutation(
                                        action = GroupMutationAction.Leave,
                                        mutation = {
                                            controller.transferAdmin(confirm.newAdmin) &&
                                                controller.leaveGroup(displayName = confirm.groupName)
                                        },
                                        onSuccess = { onLeft() },
                                    )
                                },
                            ),
                        onDismiss = { if (pendingReview.ticket === reviewTicket) pendingConfirm = null },
                    )
            }
        }

        abandonment.target?.let { target ->
            GroupAbandonmentReviewDialog(
                targets = listOf(target),
                status =
                    when {
                        activeMutation?.action == GroupMutationAction.Reset -> GroupAbandonmentStatus.Running
                        abandonment.attempted && controller.lastMutationError != null -> GroupAbandonmentStatus.Failed
                        else -> GroupAbandonmentStatus.Review
                    },
                onConfirm = {
                    val actingAccount = abandonmentAccount(controller.boundAccountRef, appState.activeAccountRef)
                    if (!abandonment.targets(actingAccount, controller.group.groupIdHex)) {
                        abandonment.dismiss()
                    } else if (activeMutation == null && !controller.mutationInFlight) {
                        runGroupMutation(
                            action = GroupMutationAction.Reset,
                            mutation = { controller.forgetGroupLocal() },
                            onAccepted = { abandonment.attempted = true },
                            onSuccess = {
                                abandonment.dismiss()
                                onLeft()
                            },
                        )
                    }
                },
                onDismiss = abandonment::dismiss,
                appState = appState,
                // The screen's error banner sits behind this modal, so the failed review carries its Copy action.
                error = controller.lastMutationError.takeIf { abandonment.attempted },
                confirmEnabled = mutationOwnerCurrent,
            )
        }
        // #3310: the escape path after a Leave that did not finish, under the error banner wherever it shows.
        val leaveBlockedNotice: @Composable (Modifier) -> Unit = { modifier ->
            if (controller.showsBlockedLeave) {
                Box(modifier) {
                    BlockedLeaveCancelNotice(
                        enabled = controller.canCancelBlockedLeave && !mutationsBlocked,
                        refreshingMembership = controller.blockedLeaveNeedsReadback,
                        onCancel = {
                            runGroupMutation(GroupMutationAction.CancelLeave, controller::cancelBlockedLeave)
                        },
                    )
                }
            } else if (abandonment.leaveBlocked && controller.group.offersLocalAbandonment()) {
                Box(modifier) {
                    GroupLeaveBlockedNotice(isDm = isDm, enabled = !mutationsBlocked, onAbandon = ::requestAbandonment)
                }
            }
        }
        val groupFeedback: @Composable () -> Unit = {
            controller.lastMutationError?.let { error ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    GroupMutationErrorBanner(error = error, onDismiss = { controller.clearLastMutationError() })
                }
            }
            leaveBlockedNotice(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        val memberContent: @Composable () -> Unit = {
            if (!isDm) {
                // Keep the member header, identities, pending invitations and the
                // full-roster action in one contiguous primary section. ContactRow
                // already provides Material touch heights and internal padding.
                Column(Modifier.testTag("chat_info.members")) {
                    if (!rosterReady) {
                        GroupRosterLoadStatus(
                            state = controller.memberRosterState,
                            onRetry = {
                                scope.launch { controller.retryMembers() }
                            },
                        )
                    } else {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f)) {
                                SettingsSection(stringResource(R.string.members))
                            }
                            IconButton(
                                onClick = {
                                    memberSearchOpen = !memberSearchOpen
                                    if (!memberSearchOpen) memberQuery = ""
                                },
                                modifier = Modifier.padding(end = Dimens.spaceSm),
                            ) {
                                Icon(
                                    painterResource(
                                        if (memberSearchOpen) R.drawable.ic_close else R.drawable.ic_search,
                                    ),
                                    contentDescription = stringResource(R.string.search_members),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val memberSearchCollapse = rememberSearchCollapse(memberSearchOpen)
                        if (memberSearchOpen || memberSearchCollapse.closing) {
                            // The slot keeps its height while the closed field shrinks into the icon.
                            Box(Modifier.fillMaxWidth().searchCollapseHost(memberSearchCollapse)) {
                                if (memberSearchOpen) {
                                    CompositionLocalProvider(LocalSearchCollapse provides memberSearchCollapse) {
                                        FlowSearchField(
                                            value = memberQuery,
                                            onValueChange = { memberQuery = it },
                                            placeholder = stringResource(R.string.search_members),
                                            clipboardInput = GroupMemberIdentitySearch::clipboardInput,
                                            modifier =
                                                Modifier
                                                    .testTag("chat_info.member_search")
                                                    .padding(horizontal = Dimens.spaceLg)
                                                    .padding(bottom = Dimens.spaceSm)
                                                    .searchExpandReveal(memberQuery),
                                        )
                                    }
                                } else {
                                    val heightDp =
                                        with(LocalDensity.current) { memberSearchCollapse.hostHeightPx.toDp() }
                                    Spacer(Modifier.fillMaxWidth().height(heightDp))
                                }
                            }
                        }
                        // #612: render members in a deterministic order — you first,
                        // then other admins alpha by display name, then non-admins
                        // alpha by display name, with memberIdHex as a stable
                        // tiebreaker. Display names are resolved once into a map so
                        // the comparator does pure reads. lowercase(Locale.ROOT) keeps
                        // ordering consistent across device locales (e.g. Turkish I).
                        // Prefetch member profiles here so the title map below can stay a
                        // pure read (contactDisplayNameCached); the profile/nickname
                        // revision key recomposes the sort once names or local aliases land.
                        val presentedMembers = controller.presentedMembers
                        LaunchedEffect(presentedMembers, controller.pendingInviteMemberRefs) {
                            appState.requestProfiles(
                                presentedMembers.map { it.memberIdHex } + controller.pendingInviteMemberRefs,
                            )
                        }
                        val memberTitlesByHex =
                            remember(presentedMembers, appState.profileRevisionForCompose) {
                                presentedMembers.associate {
                                    it.memberIdHex to appState.contactDisplayNameCached(it.memberIdHex)
                                }
                            }
                        val displayedMembers =
                            remember(
                                presentedMembers,
                                activeAccountIdHex,
                                memberTitlesByHex,
                            ) {
                                presentedMembers.sortedWith(
                                    compareBy(
                                        { !GroupProjector.isActiveAccountMember(it, activeAccountIdHex) },
                                        { !controller.isAdmin(it) },
                                        { memberTitlesByHex[it.memberIdHex]?.lowercase(Locale.ROOT).orEmpty() },
                                        { it.memberIdHex.lowercase(Locale.ROOT) },
                                    ),
                                )
                            }
                        val memberNeedle = memberQuery.trim()
                        val visibleMembers =
                            when {
                                memberNeedle.isNotEmpty() ->
                                    displayedMembers.filter {
                                        GroupMemberIdentitySearch.matches(
                                            memberNeedle,
                                            memberResolution.hex,
                                            it.memberIdHex,
                                            memberTitlesByHex[it.memberIdHex].orEmpty(),
                                        )
                                    }
                                membersExpanded || displayedMembers.size <= GROUP_MEMBERS_PREVIEW_COUNT ->
                                    displayedMembers
                                else -> displayedMembers.take(GROUP_MEMBERS_PREVIEW_COUNT)
                            }
                        if (memberResolution.resolving) {
                            val resolvingDescription = stringResource(R.string.recipient_preview_resolving)
                            CircularProgressIndicator(
                                Modifier
                                    .padding(horizontal = Dimens.spaceLg)
                                    .semantics {
                                        contentDescription = resolvingDescription
                                        liveRegion = LiveRegionMode.Polite
                                    },
                            )
                        } else if (memberNeedle.isNotEmpty() && visibleMembers.isEmpty()) {
                            Text(
                                stringResource(R.string.no_matches),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Dimens.spaceLg),
                            )
                            if (memberResolution.canRetry) {
                                TextButton(onClick = { memberSearchRetry++ }) { Text(stringResource(R.string.retry)) }
                            }
                        }
                        // Row taps route into the profile sheet, which carries the same
                        // admin actions (grant/revoke admin, remove) the old per-row menu
                        // exposed (#444/#635 scope rules).
                        val rosterToken = remember(detailsMorph, membersExpanded) { Any() }
                        val rosterBorder = amoledOutlineBorder()
                        val roster =
                            dev.ipf.whitenoise.android.ui.settings.rememberSettingsNativeSurface(
                                destination = if (membersExpanded) "members" else null,
                                surface = "members-roster",
                                horizontalInset = 16.dp,
                                registrationToken = rosterToken,
                                strokeColor = if (rosterBorder != null) Color.White else Color.Unspecified,
                            )
                        Column(Modifier.fillMaxWidth().then(roster.first)) {
                            GroupMemberIdentityRows(visibleMembers) { index, member ->
                                val isSelfRow = GroupProjector.isActiveAccountMember(member, activeAccountIdHex)
                                val rowMutationPending =
                                    controller.isMemberMutationPending(member.memberIdHex) ||
                                        activeMutation?.target == member.memberIdHex
                                // Only another member with a presentable identity has a profile to
                                // open, so self and unresolved rows stay inert and chevron-free.
                                val profileNpub =
                                    if (isSelfRow) "" else appState.npubForDisplay(member.memberIdHex)
                                val opensProfile = profileNpub.isNotBlank()
                                val memberTitle =
                                    if (isSelfRow) {
                                        stringResource(R.string.you)
                                    } else {
                                        controller.memberDisplayName(member)
                                    }
                                // The title actually shown decides: a member titled by their own key copies it (#3565).
                                val titleIsKey = opensProfile && abbreviatesPublicKey(memberTitle, profileNpub)
                                val profileLaunch =
                                    rememberMeasuredControlClick { origin ->
                                        appState.presentProfile(profileNpub, origin = origin)
                                    }
                                ChatInfoMemberCard(
                                    index = index,
                                    count = visibleMembers.size,
                                    suppressChrome = roster.second,
                                    nativeGroup = rosterToken,
                                    nativeKey = member.memberIdHex,
                                    modifier = Modifier.testTag("chat_info.member.${member.memberIdHex}"),
                                ) {
                                    ContactRow(
                                        title = memberTitle,
                                        subtitle = memberRoleLabel(controller.isAdmin(member), rowMutationPending),
                                        publicKey = profileNpub.takeIf { titleIsKey },
                                        publicKeyInTitle = true,
                                        avatarSeed = member.memberIdHex,
                                        avatarUrl = controller.memberAvatarUrl(member),
                                        followedAccount = member.memberIdHex,
                                        modifier =
                                            if (index == 0) {
                                                profileLaunch.first.performanceTestTag(PerformanceTestTags.MEMBER_LIST)
                                            } else {
                                                profileLaunch.first
                                            },
                                        onClick =
                                            if (opensProfile) {
                                                profileLaunch.second
                                            } else {
                                                null
                                            },
                                        trailing =
                                            if (opensProfile) {
                                                { ChatInfoRowChevron() }
                                            } else {
                                                null
                                            },
                                        avatarDomain = AvatarImageLoader.publicProfiles(),
                                    )
                                }
                            }
                        }
                        val canExpandMembers =
                            memberNeedle.isEmpty() &&
                                !membersExpanded &&
                                displayedMembers.size > GROUP_MEMBERS_PREVIEW_COUNT
                        if (canExpandMembers) {
                            SettingsGroup(
                                modifier =
                                    Modifier
                                        .padding(top = WhiteNoiseSpacing.Related)
                                        .testTag("chat_info.all_members"),
                            ) {
                                row("all_members") { rowContext ->
                                    SettingsLink(
                                        context = rowContext,
                                        title = stringResource(R.string.see_all_members),
                                        routeKey = "members",
                                        onClick = {
                                            membersAutoOpened = false
                                            membersExpanded = true
                                        },
                                        leading = {
                                            SettingsLeadingIcon(R.drawable.ic_group)
                                        },
                                    )
                                }
                            }
                        }
                        controller.pendingInviteMemberRefs.forEach { invite ->
                            val inviteNpub = appState.npubForDisplay(invite)
                            PendingGroupInviteRow(
                                title = appState.displayName(invite),
                                subtitle =
                                    stringResource(
                                        R.string.invite_pending,
                                        IdentityFormatter.short(inviteNpub),
                                    ),
                                npub = inviteNpub,
                                avatarSeed = invite,
                                avatarUrl = appState.contactAvatarSource(invite),
                                onClick =
                                    if (inviteNpub.isBlank()) {
                                        {}
                                    } else {
                                        { clipboard.setText(AnnotatedString(inviteNpub)) }
                                    },
                            )
                        }
                    }
                }
            }
        }
        if (membersExpanded && !isDm) {
            val closeMembers = { if (membersAutoOpened) onBack() else membersExpanded = false }
            BackHandler(onBack = closeMembers)
            detailsRoute {
                GroupMembersScreen(
                    onBack = closeMembers,
                    scrollState = membersScrollState,
                    feedback = groupFeedback,
                    content = memberContent,
                )
            }
            return@PreparedGroupAvatarContent
        }
        if (showChatRelays) {
            BackHandler { showChatRelays = false }
            detailsRoute {
                GroupRelaySettingsScreen(
                    relays = controller.group.relays,
                    isDm = isDm,
                    isAdmin = controller.isSelfAdmin,
                    onBack = { showChatRelays = false },
                    feedback = groupFeedback,
                )
            }
            return@PreparedGroupAvatarContent
        }

        val groupAvatar = rememberConversationGroupAvatar(appState, controller)
        val encryptedGroupAvatar = groupAvatar.image
        detailsRoute {
            WhiteNoiseScaffold(
                scrollBehavior = detailsScrollBehavior,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                topBar = {
                    TopAppBar(
                        modifier =
                            Modifier.onGloballyPositioned {
                                headerBottom = it.positionInWindow().y + it.size.height
                            },
                        title = {
                            if (showHeaderIdentity) {
                                Row(
                                    modifier = Modifier.testTag("chat_info.header_identity"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(Modifier.size(32.dp).testTag("chat_info.header_avatar")) {
                                        Avatar(
                                            title = conversationTitle,
                                            seed = controller.avatarAccount ?: controller.group.groupIdHex,
                                            size = 32.dp,
                                            pictureUrl = groupAvatar.pictureUrl.takeIf { encryptedGroupAvatar == null },
                                            picture = encryptedGroupAvatar,
                                            groupAnimation = groupAvatar.groupAnimation,
                                            pictureDomain = groupAvatar.pictureDomain,
                                            followedAccount = controller.avatarAccount?.takeIf { isDm },
                                        )
                                    }
                                    Text(
                                        conversationTitle,
                                        modifier = Modifier.weight(1f).testTag("chat_info.header_name"),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                }
                            }
                        },
                        scrollBehavior = LocalWhiteNoiseHeaderScroll.current,
                        colors =
                            androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    painterResource(R.drawable.ic_arrow_back),
                                    contentDescription = stringResource(R.string.back),
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                // The engine rejects all ordinary outbound group work while a disband
                // converges and forever after it lands; don't advertise actions that
                // can only fail. Local-only actions (archive, local delete) keep the
                // plain in-flight gate.
                val collapseLongMessages = appState.collapseLongMessagesInGroup(controller.group.groupIdHex)
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .then(detailsViewport.second)
                        .fadingVerticalScroll(detailsScrollState)
                        .testTag("chat_info.list")
                        .padding(bottom = 24.dp),
                ) {
                    // The chat header unfolds into this card, like the other details cards.
                    Box(Modifier.settingsCardMorph()) {
                        GroupDetailsHeader(
                            title = conversationTitle,
                            subtitle =
                                if (isDm) {
                                    ""
                                } else if (!rosterReady) {
                                    stringResource(R.string.members)
                                } else {
                                    stringResource(R.string.group_details_subtitle, controller.presentedMemberCount)
                                },
                            description =
                                if (isDm) {
                                    dmPeerNpub
                                        ?.let { IdentityFormatter.short(it, prefix = 12, suffix = 8) }
                                        .orEmpty()
                                } else {
                                    controller.group.description
                                },
                            // Show the DM peer's avatar + initials seed here — the same
                            // peer metadata the top bar and chat-list row resolve (#837).
                            // A group keeps its own avatar (controller.avatarUrl falls back
                            // to the group avatar; avatarAccount is null for groups).
                            seed = controller.avatarAccount ?: controller.group.groupIdHex,
                            pictureUrl = groupAvatar.pictureUrl,
                            picture = encryptedGroupAvatar,
                            groupAnimation = groupAvatar.groupAnimation,
                            archived = controller.presentedArchived,
                            readLocalBytes = groupAvatar.readOriginalBytes,
                            followedAccount = controller.avatarAccount?.takeIf { isDm },
                            // A group's URL picture decodes single-use, a direct chat's peer picture
                            // in the public profiles.
                            pictureDomain = groupAvatar.pictureDomain,
                            onEdit =
                                if (canShowEditAction) {
                                    { showEditGroup = true }
                                } else {
                                    null
                                },
                            editEnabled = activeMutation == null && !controller.mutationInFlight,
                            onAddDescription =
                                if (!isDm && canEdit && controller.group.description.isBlank()) {
                                    { showEditGroup = true }
                                } else {
                                    null
                                },
                            descriptionCopyValue = dmPeerNpub.takeIf { isDm },
                            onNameBottom = { nameBottom = it },
                        )
                    }

                    ChatInfoQuickActions(
                        onOpenProfile =
                            dmPeerNpub?.takeIf { isDm }?.let { npub ->
                                { origin -> appState.presentProfile(npub, origin = origin) }
                            },
                        onAddMembers = openAddMembers.takeIf { !isDm && canEdit },
                        addMembersEnabled = canAdministerMembers && !mutationsBlocked,
                        muteControl = muteControl,
                        disappearingState =
                            disappearingMessagesLabel(controller.group.disappearingMessageSecs.toLong()),
                        disappearingEnabled = disappearingTimerEditable(canEdit, mutationsBlocked),
                        onOpenDisappearing = { showDisappearingPicker = true },
                        onOpenSearch = onOpenSearch,
                    )
                    if (isDm && dmPeerAccountIdHex != null && dmPeerNpub != null) {
                        SettingsSection(stringResource(R.string.profile_actions))
                        SettingsGroup(modifier = Modifier.testTag("chat_info.group_actions")) {
                            row("start_group") { rowContext ->
                                val launch =
                                    rememberMeasuredControlClick { origin ->
                                        val candidate =
                                            RecipientSearch.Candidate(
                                                dmPeerAccountIdHex,
                                                conversationTitle,
                                                dmPeerNpub,
                                            )
                                        onStartGroupWithPeerMeasured?.invoke(candidate, origin)
                                            ?: onStartGroupWithPeer(candidate)
                                    }
                                SettingsAction(
                                    context = rowContext,
                                    title = stringResource(R.string.person_start_group),
                                    onClick = launch.second,
                                    leading = { SettingsLeadingIcon(R.drawable.ic_group_add) },
                                    modifier = launch.first.testTag("chat_info.start_group"),
                                )
                            }
                            row("groups_in_common") { rowContext ->
                                val profileLaunch =
                                    rememberMeasuredContourClick { origin, contour ->
                                        appState.presentProfileWithContour(
                                            dmPeerNpub,
                                            groupsInCommon = true,
                                            origin = origin,
                                            contour = contour,
                                        )
                                    }
                                SettingsAction(
                                    context = rowContext,
                                    title = stringResource(R.string.person_groups_in_common),
                                    onClick = profileLaunch.second,
                                    leading = { SettingsLeadingIcon(R.drawable.ic_group) },
                                    modifier = profileLaunch.first.testTag("chat_info.groups_in_common"),
                                )
                            }
                            row("add_to_group") { rowContext ->
                                SettingsAction(
                                    context = rowContext,
                                    title = stringResource(R.string.person_add_to_group),
                                    opensRoute = true,
                                    routeKey = "add-to-groups",
                                    onClick = { showDmAddToGroups = true },
                                    enabled = !addingDmPeerToGroups,
                                    leading = { SettingsLeadingIcon(R.drawable.ic_group_add) },
                                    modifier = Modifier.testTag("chat_info.add_to_group"),
                                )
                            }
                        }
                    }
                    controller.lastMutationError?.let { error ->
                        Box(Modifier.padding(horizontal = Dimens.spaceLg)) {
                            GroupMutationErrorBanner(error = error, onDismiss = { controller.clearLastMutationError() })
                        }
                    }
                    leaveBlockedNotice(Modifier.padding(horizontal = Dimens.spaceLg).padding(top = 8.dp))
                    ChatHistoryBackupHint(appState, controller)

                    memberContent()

                    if (!isDm && canEdit) {
                        SettingsGroup(
                            modifier =
                                Modifier
                                    .padding(
                                        top = WhiteNoiseSpacing.Section,
                                    ).testTag("chat_info.management"),
                        ) {
                            row("edit_group") { rowContext ->
                                SettingsLink(
                                    context = rowContext,
                                    title = stringResource(R.string.edit_group_info_title),
                                    routeKey = "edit",
                                    onClick = { showEditGroup = true },
                                    enabled = activeMutation == null && !controller.mutationInFlight,
                                    leading = { SettingsLeadingIcon(R.drawable.ic_edit) },
                                )
                            }
                            row("add_member") { rowContext ->
                                SettingsLink(
                                    context = rowContext,
                                    title = stringResource(R.string.add_member),
                                    routeKey = "add-members",
                                    onClick = openAddMembers,
                                    modifier = Modifier.testTag("chat_info.add_people"),
                                    enabled = canAdministerMembers && !mutationsBlocked,
                                    leading = { SettingsLeadingIcon(R.drawable.ic_group_add) },
                                )
                            }
                        }
                    }

                    SharedMediaSection(
                        tiles = sharedMediaTiles,
                        onOpenCategory = { mediaLibraryCategory = it },
                        modifier = Modifier.testTag("chat_info.shared_media"),
                    )

                    // Custom folders containing this chat — manual membership or a
                    // live rule match — so the value tracks membership changes made
                    // anywhere (chat list, Settings, or a rule flipping).
                    val folderStoreState by appState.chatFolderPreferences.state.collectAsState()
                    val chatIdLower = controller.group.groupIdHex.lowercase(Locale.ROOT)
                    val folderNames =
                        remember(
                            folderStoreState,
                            appState.chatListItems,
                            appState.archivedChatListItems,
                            appState.profileRevisionForCompose,
                            folderAccountRef,
                            chatIdLower,
                            groupTitleCopy,
                            appState.chatFolderMembership?.folders,
                        ) {
                            val accountRef = folderAccountRef ?: return@remember emptyList()
                            val thisChatRow =
                                (appState.chatListItems + appState.archivedChatListItems).filter {
                                    it.id.equals(
                                        chatIdLower,
                                        ignoreCase = true,
                                    )
                                }
                            appState.chatFolderPreferences
                                .foldersFor(accountRef)
                                .mapNotNull { folder ->
                                    val manual =
                                        chatIdLower in
                                            appState.chatFolderPreferences.membershipFor(accountRef, folder.id)
                                    val effective =
                                        chatIdLower in
                                            appState.folderMemberIds(accountRef, folder.id) {
                                                chatFolderChatIds(
                                                    items = thisChatRow,
                                                    manualChatIds =
                                                        appState.chatFolderPreferences.membershipFor(
                                                            accountRef,
                                                            folder.id,
                                                        ),
                                                    rule =
                                                        appState.chatFolderPreferences.folderRule(
                                                            accountRef,
                                                            folder.id,
                                                        ),
                                                    excludedChatIds =
                                                        appState.chatFolderPreferences.excludedChats(
                                                            accountRef,
                                                            folder.id,
                                                        ),
                                                    activeAccountIdHex = appState.activeAccount?.accountIdHex,
                                                    isMuted = { groupIdHex ->
                                                        thisChatRow.any {
                                                            it.group.groupIdHex == groupIdHex && it.engineMuted()
                                                        }
                                                    },
                                                    displayTitle = {
                                                        chatListItemDisplayTitle(it, appState, groupTitleCopy)
                                                    },
                                                )
                                            }
                                    if (effective) folder to manual else null
                                }
                        }

                    SettingsSection(stringResource(if (isDm) R.string.chat_actions else R.string.advanced))
                    SettingsGroup(modifier = Modifier.testTag("chat_info.actions")) {
                        row("pin_shortcut") { rowContext ->
                            PinConversationAction(
                                rowContext,
                                appState,
                                controller,
                                conversationTitle,
                            )
                        }
                        // A chat link only opens for people already in the chat;
                        // a DM shares its profile link instead (#3658).
                        if (!isDm && !readOnlyInvite) {
                            row("copy_chat_link") { rowContext ->
                                SettingsLink(
                                    context = rowContext,
                                    title = stringResource(R.string.copy_chat_link),
                                    subtitle = stringResource(R.string.copy_chat_link_subtitle),
                                    explanation = stringResource(R.string.explanation_chat_info_copy_chat_link),
                                    onClick = {
                                        clipboard.setText(AnnotatedString(ChatLink.uri(controller.group.groupIdHex)))
                                        appState.present(R.string.copied)
                                    },
                                    leading = { SettingsLeadingIcon(R.drawable.ic_link) },
                                )
                            }
                        }
                        row("collapse_long_messages") { rowContext ->
                            SettingsSwitch(
                                context = rowContext,
                                title = stringResource(R.string.collapse_long_messages),
                                checked = collapseLongMessages,
                                onCheckedChange = {
                                    appState.updateCollapseLongMessagesInGroup(controller.group.groupIdHex, it)
                                },
                                subtitle = stringResource(R.string.collapse_long_messages_subtitle),
                                leading = { SettingsLeadingIcon(R.drawable.ic_wrap_text) },
                            )
                        }
                        if (appState.ttsHasUsableEngine) {
                            row("auto_read") { rowContext ->
                                TtsAutoReadGroupActionRow(
                                    context = rowContext,
                                    title = stringResource(R.string.tts_auto_read_title),
                                    provenanceLabel = autoReadSettingLabel,
                                    sourceOwner = autoReadPopupOwner,
                                    onSourceActivated = { autoReadPopupSource = it },
                                    sourceActivation = autoReadPopupSource,
                                    onClick = { showAutoReadPicker = true },
                                )
                            }
                        }
                        if (controller.boundAccountRef != null) {
                            row("translation") { rowContext ->
                                SettingsLink(
                                    context = rowContext,
                                    title = stringResource(R.string.translation_automatic),
                                    leading = {
                                        SettingsLeadingIcon(R.drawable.ic_translate)
                                    },
                                    onClick = { showTranslationPicker = true },
                                )
                            }
                        }
                        row("notifications") { rowContext ->
                            SettingsLink(
                                context = rowContext,
                                title = stringResource(R.string.sounds_and_notifications),
                                routeKey = "notifications",
                                onClick = { showNotificationSettings = true },
                                modifier = Modifier.performanceTestTag(PerformanceTestTags.GROUP_NOTIFICATION_SETTINGS),
                                value =
                                    conversationNotificationSummary(
                                        appState,
                                        controller.group.groupIdHex,
                                        conversationNotifyMode,
                                    ),
                                leading = { SettingsLeadingIcon(R.drawable.ic_settings_notifications) },
                            )
                        }
                        row("bubble_colors") { rowContext ->
                            SettingsLink(
                                context = rowContext,
                                title = stringResource(R.string.chat_bubble_colors),
                                routeKey = "bubble-colors",
                                onClick = { showBubbleColors = true },
                                leading = { SettingsLeadingIcon(R.drawable.ic_palette) },
                            )
                        }
                    }

                    if (showTranslationPicker) {
                        controller.boundAccountRef?.let { account ->
                            dev.ipf.whitenoise.android.translation.TranslationChatSettingsDialog(
                                account,
                                controller.group.groupIdHex,
                                { showTranslationPicker = false },
                                ownerCurrent = {
                                    appState.activeAccountRef == account && !appState.appLock.screenVisible
                                },
                            )
                        }
                    }
                    if (showAutoReadPicker) {
                        TtsAutoReadPickerDialog(
                            source = autoReadPopupSource,
                            globalDefaultEnabled = ttsAutoReadPrefs.globalDefaultEnabled,
                            selectedOverride = autoReadOverride,
                            onDismiss = { showAutoReadPicker = false },
                            onSelect = { override ->
                                appState.setConversationAutoReadOverride(controller.group.groupIdHex, override)
                            },
                        )
                    }

                    DisappearingTimerEditor(
                        open = showDisappearingPicker,
                        currentSecs = controller.group.disappearingMessageSecs.toLong(),
                        editable = disappearingTimerEditable(canEdit, mutationsBlocked),
                        identity = ownIdentity,
                        resetKeys = arrayOf(controller, appState.activeAccountRef, appState.runtimeGeneration),
                        onDismiss = { showDisappearingPicker = false },
                        onApply = { secs ->
                            runGroupMutation(
                                action = GroupMutationAction.DisappearingMessages,
                                mutation = { controller.updateMessageRetention(secs.toULong()) },
                            )
                        },
                    )

                    // Rows shared by the DM technical group and the group lifecycle group.

                    /** Add to Folder row. */
                    fun SettingsGroupScope.folderRow() {
                        row("folders") { rowContext ->
                            SettingsLink(
                                context = rowContext,
                                title = stringResource(R.string.chat_add_folder),
                                onClick = { showFolderPicker = true },
                                modifier = Modifier.testTag("chat_info.folders"),
                                leading = { SettingsLeadingIcon(R.drawable.ic_add) },
                            )
                        }
                    }

                    /** Archive / Unarchive row. */
                    fun SettingsGroupScope.archiveRow() {
                        if (!readOnlyInvite) {
                            row("archive") { rowContext ->
                                SettingsAction(
                                    context = rowContext,
                                    title =
                                        stringResource(
                                            if (controller.presentedArchived) {
                                                R.string.unarchive_chat
                                            } else {
                                                R.string.archive_chat
                                            },
                                        ),
                                    onClick = {
                                        runGroupMutation(
                                            action = GroupMutationAction.Archive,
                                            mutation = { controller.setArchived(!controller.presentedArchived) },
                                        )
                                    },
                                    enabled = !mutationsBlocked,
                                    leading = {
                                        DangerLeading(
                                            icon =
                                                if (controller.presentedArchived) {
                                                    R.drawable.ic_unarchive
                                                } else {
                                                    R.drawable.ic_archive
                                                },
                                            inProgress = activeMutation?.action == GroupMutationAction.Archive,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    /** Leave row. */
                    fun SettingsGroupScope.leaveRow() {
                        if (controller.isSelfMember && !controller.showsBlockedLeave) {
                            row("leave") { rowContext ->
                                SettingsAction(
                                    context = rowContext,
                                    title = stringResource(if (isDm) R.string.leave_chat else R.string.leave_group),
                                    // Leave is refused for disbanding/terminal groups; local delete below is the
                                    // exit for a dead group.
                                    onClick = { requestLeave(controller.title(groupTitleCopy)) },
                                    enabled = !mutationsBlocked && controller.membersLoaded && !groupTerminal,
                                    destructive = true,
                                    leading = {
                                        DangerLeading(
                                            icon = R.drawable.ic_logout,
                                            inProgress = activeMutation?.action == GroupMutationAction.Leave,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    /** Developer-only transcript and transport diagnostics. */
                    @Composable
                    fun developerPanels() {
                        if (!appState.developerMode) return
                        Column(
                            Modifier.padding(top = WhiteNoiseSpacing.Related),
                            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                        ) {
                            ConversationTranscriptActions(
                                shareInFlight = transcriptExportInFlight,
                                saveInFlight = transcriptSave.busy,
                                sharePending = pendingTranscriptShareFile != null,
                                accountAvailable = appState.activeAccountRef != null,
                                onShare = { exportTranscript() },
                                onSave = {
                                    if (!transcriptExportInFlight && pendingTranscriptShareFile == null) {
                                        transcriptSave.save()
                                    }
                                },
                            )

                            DeveloperInfoPanel(title = stringResource(R.string.mls)) {
                                when {
                                    mlsLoading ->
                                        Text(
                                            stringResource(R.string.loading_mls_state),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    mlsState == null ->
                                        Text(
                                            stringResource(R.string.mls_state_unavailable),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    else -> {
                                        val state = requireNotNull(mlsState)
                                        DiagnosticRow(
                                            stringResource(R.string.group_id),
                                            IdentityFormatter.short(state.groupIdHex),
                                            copyValue = state.groupIdHex,
                                        )
                                        DiagnosticRow(stringResource(R.string.epoch), state.epoch.toString())
                                        DiagnosticRow(
                                            stringResource(R.string.mls_members),
                                            state.memberCount.toString(),
                                        )
                                        DiagnosticRow(
                                            stringResource(R.string.required_components),
                                            state.requiredAppComponents.joinToString(", "),
                                        )
                                    }
                                }
                            }

                            PushDeliveryDebugSection(
                                info = pushDebugInfo,
                                loading = pushDebugLoading,
                                appState = appState,
                            )
                        }
                    }

                    SettingsGroup(
                        modifier = Modifier.padding(top = WhiteNoiseSpacing.Section).testTag("chat_info.technical"),
                    ) {
                        row("relays") { rowContext ->
                            val relayCount = groupRelayDisplayList(controller.group.relays).size
                            SettingsLink(
                                context = rowContext,
                                title = stringResource(groupRelaysTitleRes(isDm)),
                                routeKey = "relays",
                                explanation = stringResource(R.string.explanation_chat_info_relays),
                                onClick = { showChatRelays = true },
                                modifier = Modifier.testTag("chat_info.relays"),
                                subtitle = pluralStringResource(R.plurals.chat_relay_count, relayCount, relayCount),
                                leading = { SettingsLeadingIcon(R.drawable.ic_tune) },
                            )
                        }
                        if (isDm) {
                            row("developer_tools") { rowContext ->
                                SettingsLink(
                                    context = rowContext,
                                    title = stringResource(R.string.developer_tools),
                                    routeKey = "group-info",
                                    onClick = { showGroupInfo = true },
                                    modifier = Modifier.testTag("chat_info.developer_tools"),
                                    leading = {
                                        SettingsLeadingIcon(R.drawable.ic_bug_report)
                                    },
                                )
                            }
                            folderRow()
                            archiveRow()
                            leaveRow()
                        }
                    }
                    if (isDm) developerPanels()

                    // Danger zone (#416): leave routes through requestLeave so the
                    // sole-admin and sole-member cases get their own confirm copy. On
                    // failure the controller's lastMutationError surfaces inline here
                    // (in addition to the snackbar) so the user can retry in place.
                    Column(Modifier.padding(top = WhiteNoiseSpacing.Section)) {
                        val selfMember =
                            controller.members.firstOrNull {
                                GroupProjector.isActiveAccountMember(
                                    it,
                                    activeAccountIdHex,
                                )
                            }
                        if (!isDm) {
                            SettingsGroup(modifier = Modifier.testTag("chat_info.lifecycle")) {
                                folderRow()
                                if (canEdit) {
                                    row("transfer_admin") { rowContext ->
                                        SettingsAction(
                                            context = rowContext,
                                            title = stringResource(R.string.transfer_admin),
                                            explanation = stringResource(R.string.explanation_chat_info_transfer_admin),
                                            onClick = {
                                                transferThenLeaveName = null
                                                showTransferAdmin = true
                                            },
                                            modifier = Modifier.testTag("chat_info.transfer_admin"),
                                            enabled =
                                                !mutationsBlocked && controller.transferAdminCandidates().isNotEmpty(),
                                            leading = {
                                                SettingsLeadingIcon(R.drawable.ic_swap_vert)
                                            },
                                        )
                                    }
                                }
                                if (canEdit && selfMember != null) {
                                    row("step_down") { rowContext ->
                                        SettingsAction(
                                            context = rowContext,
                                            title = stringResource(R.string.step_down_as_admin),
                                            explanation = stringResource(R.string.explanation_chat_info_step_down),
                                            onClick = {
                                                pendingConfirm =
                                                    if (controller.isSoleAdminWithOtherMembers) {
                                                        DetailsConfirm.StepDownSoleAdmin
                                                    } else {
                                                        DetailsConfirm.StepDownAdmin(selfMember)
                                                    }
                                            },
                                            enabled = !mutationsBlocked,
                                            leading = {
                                                DangerLeading(
                                                    icon = R.drawable.ic_admin_panel_settings,
                                                    inProgress =
                                                        activeMutation?.action == GroupMutationAction.SelfDemoteAdmin,
                                                )
                                            },
                                        )
                                    }
                                }
                                archiveRow()
                                if (controller.isSelfMember) {
                                    groupDisbandRows(
                                        management = controller.managementState,
                                        enabled = !mutationsBlocked,
                                        enableInProgress =
                                            activeMutation?.action == GroupMutationAction.EnableDisbanding,
                                        disbandInProgress = activeMutation?.action == GroupMutationAction.Disband,
                                        onEnable = {
                                            runGroupMutation(
                                                action = GroupMutationAction.EnableDisbanding,
                                                mutation = { controller.enableGroupDisbanding() },
                                            )
                                        },
                                        onDisbandRequested = { disbandConfirmOpen = true },
                                    )
                                    groupAccountDevicesRows(
                                        activation = accountDevicesActivation,
                                        enabled = !mutationsBlocked,
                                        onEnableRequested = { accountDevicesConfirmOpen = true },
                                    )
                                }
                                leaveRow()
                            }
                        }
                        if (showFolderPicker) {
                            ChatFolderPickerSheet(
                                appState = appState,
                                targetChatIds = listOf(chatIdLower),
                                // Rule-matched membership is visible in the row above but
                                // not toggleable here — the sheet edits manual membership
                                // only, so it must say why a checked-looking folder shows
                                // an unchecked box.
                                ruleMatchedFolderIds =
                                    folderNames
                                        .filterNot { (_, manual) -> manual }
                                        .mapTo(HashSet()) { (folder, _) -> folder.id },
                                onCreateFolder = {
                                    showFolderPicker = false
                                    showFolderCreate = true
                                },
                                onDismiss = { showFolderPicker = false },
                            )
                        }
                        if (!isDm && controller.isSelfMember) {
                            GroupDisbandStatus(
                                management = controller.managementState,
                                onAcknowledgeFailure = {
                                    runGroupMutation(
                                        action = GroupMutationAction.Disband,
                                        mutation = { controller.acknowledgeDisbandFailure() },
                                    )
                                },
                            )
                        }
                        if (accountDevicesConfirmOpen) {
                            GroupAccountDevicesConfirmDialog(
                                identity = ownIdentity,
                                enabled =
                                    !mutationsBlocked &&
                                        actingMemberHex != null &&
                                        accountDevicesActivation == AccountDevicesActivationFfi.ELIGIBLE,
                                onConfirm = {
                                    accountDevicesConfirmOpen = false
                                    runGroupMutation(
                                        action = GroupMutationAction.EnableAccountDevices,
                                        mutation = { controller.enableAccountDevices() },
                                    )
                                },
                                onDismiss = { accountDevicesConfirmOpen = false },
                            )
                        }
                        if (disbandConfirmOpen) {
                            val request = remember(controller) { Any() }
                            GroupDisbandConfirmDialog(
                                ownerKey =
                                    Triple(
                                        controller,
                                        appState.activeAccountRef,
                                        appState.runtimeGeneration,
                                    ) to request,
                                identity = ownIdentity,
                                enabled = !mutationsBlocked && actingMemberHex != null,
                                onConfirm = {
                                    disbandConfirmOpen = false
                                    runGroupMutation(
                                        action = GroupMutationAction.Disband,
                                        mutation = { controller.disbandGroup() },
                                    )
                                },
                                onDismiss = { disbandConfirmOpen = false },
                            )
                        }
                        // A disbanded group has ended even while its roster still lists this account, and the shared
                        // abandonment rule withdraws Reset there, so local deletion is its way out of Details.
                        val disbanded = controller.group.disbanded
                        GroupDetailsLocalDeleteControl(
                            ownerKey = Triple(controller, appState.activeAccountRef, appState.runtimeGeneration),
                            identity = ownIdentity,
                            isDm = isDm,
                            readOnlyInvite = readOnlyInvite,
                            isSelfMember = controller.isSelfMember && !disbanded,
                            membersVerified = controller.membersVerified || disbanded,
                            enabled = !mutationsBlocked && (isDm || actingMemberHex != null),
                            inProgress = activeMutation?.action == GroupMutationAction.Delete,
                            onDeleteConfirmed = {
                                runGroupMutation(
                                    action = GroupMutationAction.Delete,
                                    mutation = { controller.deleteGroupLocal() },
                                    onSuccess = onLeft,
                                )
                            },
                        )
                        GroupDetailsResetControl(
                            isDm = isDm,
                            offered = controller.group.offersLocalAbandonment(),
                            enabled = !mutationsBlocked,
                            inProgress = activeMutation?.action == GroupMutationAction.Reset,
                            onRequest = ::requestAbandonment,
                        )
                    }

                    if (!isDm) {
                        SettingsGroup(
                            modifier = Modifier.padding(top = WhiteNoiseSpacing.Section).testTag("chat_info.developer"),
                        ) {
                            row("developer_tools") { rowContext ->
                                SettingsLink(
                                    context = rowContext,
                                    title = stringResource(R.string.developer_tools),
                                    routeKey = "group-info",
                                    onClick = { showGroupInfo = true },
                                    modifier = Modifier.testTag("chat_info.developer_tools"),
                                    leading = {
                                        SettingsLeadingIcon(R.drawable.ic_bug_report)
                                    },
                                )
                            }
                        }
                    }

                    if (!isDm) developerPanels()
                }
            }
        }
    }
}

/** Dismissible banner for the latest group mutation failure. */
@Composable
private fun GroupMutationErrorBanner(
    error: ErrorPresentation,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ErrorCallout(
        message = stringResource(R.string.latest_group_error, error.message.resolve(context)),
        report = error.report,
        onDismiss = onDismiss,
    )
}

private sealed class DetailsConfirm {
    data class RemoveMember(
        val member: AppGroupMemberRecordFfi,
    ) : DetailsConfirm()

    data class TransferAdmin(
        val member: AppGroupMemberRecordFfi,
    ) : DetailsConfirm()

    data class StepDownAdmin(
        val member: AppGroupMemberRecordFfi,
    ) : DetailsConfirm()

    data object StepDownSoleAdmin : DetailsConfirm()

    /**
     * Standard leave: the active account is an ordinary member (or an admin
     * with at least one other admin left behind). Confirm → leave.
     */
    data class Leave(
        val groupName: String,
    ) : DetailsConfirm()

    /**
     * Sole-admin gate: the active account is the only admin of a group that
     * still has other members. Leaving would strand the group with no admin,
     * so this is an informational dialog (Cancel only, no Leave button) that
     * asks the user to promote another member to admin first.
     */
    data class LeaveSoleAdmin(
        val groupName: String,
    ) : DetailsConfirm()

    /**
     * Sole-admin Leave with exactly one transfer candidate (#1131): a single
     * confirm makes [newAdmin] admin and leaves the group in one step.
     */
    data class LeaveSoleAdminTransfer(
        val groupName: String,
        val newAdmin: AppGroupMemberRecordFfi,
    ) : DetailsConfirm()

    /**
     * Sole-member leave: the active account is the only member left, so
     * leaving dissolves the group entirely. Confirm → local cleanup; no MLS
     * commit is attempted because there is no other member to coordinate with.
     */
    data class LeaveSoleMember(
        val groupName: String,
    ) : DetailsConfirm()
}

/** Each opening, including equal singleton actions, gets a fresh one-use presentation identity. */
private class DetailsReviewSlot {
    private var value by mutableStateOf<DetailsConfirm?>(null)
    var ticket: Any by mutableStateOf(Any())
        private set

    operator fun getValue(
        thisRef: Any?,
        property: kotlin.reflect.KProperty<*>,
    ): DetailsConfirm? = value

    operator fun setValue(
        thisRef: Any?,
        property: kotlin.reflect.KProperty<*>,
        next: DetailsConfirm?,
    ) {
        ticket = Any()
        value = next
    }
}

/** Re-read each authority fence when claiming an administration confirmation. */
private fun ConversationController.canClaimAdministrationReview(allowDmDeparture: Boolean = false): Boolean =
    when {
        !isSelfAdmin || (isDm && !allowDmDeparture) -> false
        group.pendingConfirmation || group.disbanding || group.disbanded -> false
        else -> memberAdministrationPresentable(memberRosterState, seededSelfMember)
    }
