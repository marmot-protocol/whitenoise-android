package dev.ipf.whitenoise.android.ui.group

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.GroupPushDebugInfoFfi
import dev.ipf.marmotkit.GroupPushTokenDebugEntryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.LeaveAction
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.GroupRosterLoadState
import dev.ipf.whitenoise.android.state.ProfileGroupPickerState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.ui.chats.ChatFolderPickerSheet
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactPickerScreen
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.chats.newchat.DangerActionRow
import dev.ipf.whitenoise.android.ui.chats.newchat.FlowQuickActionRow
import dev.ipf.whitenoise.android.ui.chats.newchat.FlowSearchField
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupFlow
import dev.ipf.whitenoise.android.ui.chats.newchat.QuickActionButton
import dev.ipf.whitenoise.android.ui.chats.newchat.SectionHeader
import dev.ipf.whitenoise.android.ui.chats.newchat.SettingsActionRow
import dev.ipf.whitenoise.android.ui.common.AppDivider
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.common.rememberEncryptedGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.conversation.ConversationTransientNotice
import dev.ipf.whitenoise.android.ui.conversation.media.fileProviderUri
import dev.ipf.whitenoise.android.ui.design.KeyboardPreservingDropdownMenu
import dev.ipf.whitenoise.android.ui.design.conversationMenuItemPadding
import dev.ipf.whitenoise.android.ui.medialibrary.MediaLibraryRoute
import dev.ipf.whitenoise.android.ui.medialibrary.SharedMediaSection
import dev.ipf.whitenoise.android.ui.medialibrary.rememberSharedMediaTiles
import dev.ipf.whitenoise.android.ui.profile.AvatarFullScreenViewer
import dev.ipf.whitenoise.android.ui.profile.ProfileAddToGroupsSheet
import dev.ipf.whitenoise.android.ui.profile.rememberAvatarImageAvailable
import dev.ipf.whitenoise.android.ui.settings.ChatBubbleColorsScreen
import dev.ipf.whitenoise.android.ui.settings.ChatFolderEditScreen
import dev.ipf.whitenoise.android.ui.settings.DiagnosticRow
import dev.ipf.whitenoise.android.ui.settings.chatFolderDisplayName
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun conversationTranscriptShareIntent(
    context: Context,
    file: java.io.File,
): Intent {
    val uri = fileProviderUri(context, file)
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_TITLE, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return Intent.createChooser(intent, null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Composable
internal fun GroupDetailsLocalDeleteControl(
    isDm: Boolean,
    readOnlyInvite: Boolean,
    isSelfMember: Boolean,
    membersVerified: Boolean,
    enabled: Boolean,
    inProgress: Boolean,
    onDeleteConfirmed: () -> Unit,
) {
    if (readOnlyInvite || isSelfMember || !membersVerified) return
    var confirmOpen by remember { mutableStateOf(false) }
    val deleteLabel = if (isDm) R.string.chat_row_action_delete_chat else R.string.chat_row_action_delete_group
    val dialogTitle = if (isDm) R.string.delete_chat_dialog_title else R.string.delete_group_dialog_title
    val dialogMessage = if (isDm) R.string.delete_chat_dialog_message else R.string.delete_group_dialog_message
    DangerActionRow(
        icon = Icons.Default.Delete,
        title = stringResource(deleteLabel),
        enabled = enabled,
        inProgress = inProgress,
        onClick = { confirmOpen = true },
    )
    if (confirmOpen) {
        ConfirmDialog(
            title = stringResource(dialogTitle),
            message = stringResource(dialogMessage),
            confirmLabel = stringResource(R.string.delete_group_confirm),
            onConfirm = {
                confirmOpen = false
                onDeleteConfirmed()
            },
            onDismiss = { confirmOpen = false },
            destructive = true,
        )
    }
}

// Members shown in Group Details before the "See all" expander.
private const val GROUP_MEMBERS_PREVIEW_COUNT = 6
private const val SHARED_GROUPS_PREVIEW_COUNT = 3

internal fun directDetailsSharedGroups(
    groups: List<ChatListItem>,
    currentGroupIdHex: String,
): List<ChatListItem> =
    groups.filter { item ->
        !item.group.groupIdHex.equals(currentGroupIdHex, ignoreCase = true) &&
            item.memberCount >= 2 &&
            (item.memberCount != 2 || item.group.name.isNotBlank())
    }

internal fun visibleDirectDetailsSharedGroups(
    groups: List<ChatListItem>,
    expanded: Boolean,
): List<ChatListItem> = if (expanded) groups else groups.take(SHARED_GROUPS_PREVIEW_COUNT)

/** Renders group identity, membership, behavior, and notification configuration. */
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
    // When true (sole admin routed in from the blocked top-level Leave gate),
    // open the transfer-admin picker immediately so the trapped admin lands on
    // the action instead of having to hunt for it in the Admins section (#417).
    autoOpenTransferAdmin: Boolean = false,
    // When true (empty-group CTA), open the shared add-member sheet after the
    // details screen is mounted so the initial invite path reuses later-add UI.
    autoOpenAddMember: Boolean = false,
    onAutoOpenAddMemberConsumed: () -> Unit = {},
    // Close details and raise the conversation's message search.
    onOpenSearch: (() -> Unit)? = null,
    // Shared-group rows and a newly-created group replace the currently open
    // conversation in the shell without bouncing through the chat list.
    onOpenConversation: (ChatListItem, Boolean) -> Unit = { _, _ -> },
    onGroupCreateSubmitted: () -> Long = { 0L },
    onGroupCreateCompletedOpen: (ChatListItem, Long) -> Unit = { item, _ -> onOpenConversation(item, false) },
    onGroupCreateFlowSuperseded: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showEditGroup by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    var showGroupInfo by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    var showNotificationSettings by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    var showMuteDurationDialog by remember { mutableStateOf(false) }
    var showNotifyForDialog by remember { mutableStateOf(false) }
    var showVibrationPatternDialog by remember { mutableStateOf(false) }
    // A requested picker stays closed until the roster is authoritative. This
    // avoids excluding candidates against a stale or empty member snapshot.
    var showAddMember by remember { mutableStateOf(false) }
    val addMemberAutoOpened = remember { autoOpenAddMember }
    var membersExpanded by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    var memberSearchOpen by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    var memberQuery by remember(controller.group.groupIdHex) { mutableStateOf("") }
    var sharedGroupsExpanded by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    var showStartGroupWithContact by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    var showAddContactToGroups by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    var addingContactToGroups by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    // Sole-admin "Transfer admin first" picker. Surfaced from the blocked
    // leave path and the Admins prompt so a trapped sole admin can hand the
    // role to another member (issue #417).
    var showTransferAdmin by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    // #1131: when set, the transfer-admin picker is being used as the first step
    // of a sole-admin Leave (3+ members) — picking transfers admin then leaves,
    // rather than the standalone transfer-only action. Holds the group name for
    // the leave call/toast.
    var transferThenLeaveName by remember(controller.group.groupIdHex) { mutableStateOf<String?>(null) }
    // Honor the caller's request to jump straight into the transfer picker
    // (sole admin routed here from the blocked top-level Leave gate). Gated on
    // the sole-admin predicate so a stale flag can't pop the sheet once the
    // user is no longer trapped (e.g. transfer already completed).
    LaunchedEffect(autoOpenTransferAdmin, controller.isSoleAdminWithOtherMembers) {
        if (autoOpenTransferAdmin && controller.isSoleAdminWithOtherMembers) {
            showTransferAdmin = true
        }
    }
    // Disband eligibility, blockers, and any in-flight request are
    // engine-authoritative; fetch on entry and again whenever the group
    // record moves, so a remote admin's enable/disband lands here without
    // reopening the screen.
    LaunchedEffect(controller, controller.group) {
        controller.refreshManagementState()
    }
    var mlsState by remember(controller.group.groupIdHex) { mutableStateOf<AppGroupMlsStateFfi?>(null) }
    var mlsLoading by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    var pushDebugInfo by remember(controller.group.groupIdHex) { mutableStateOf<GroupPushDebugInfoFfi?>(null) }
    var pushDebugLoading by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    // Scoped to the visible group; the controller mutation continues on appState
    // if the user switches conversations, but this sheet stops tracking it.
    var activeMutation by remember(controller.group.groupIdHex) { mutableStateOf<ActiveGroupMutation?>(null) }
    // Tap-time archive direction, recorded before the mutation coroutine is
    // dispatched so the menu's progress label can never read the pre-intent
    // presented state and show the opposite direction.
    var pendingArchiveTarget by remember(controller.group.groupIdHex) { mutableStateOf<Boolean?>(null) }
    var pendingConfirm by remember { mutableStateOf<DetailsConfirm?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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
    val canEdit = !readOnlyInvite && controller.isSelfMember && controller.isSelfAdmin && !groupTerminal
    // Presentation-only: a warm member seed plus the admin list on the group
    // record enables administration on the first frame; the invite commit
    // still requires the authoritative roster inside the controller.
    val canAdministerMembers =
        !isDm && canEdit && memberAdministrationPresentable(controller.memberRosterState, controller.seededSelfMember)
    val mutationsBlocked = activeMutation != null || controller.mutationInFlight
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
    LaunchedEffect(autoOpenAddMember, canAdministerMembers) {
        if (autoOpenAddMember && canAdministerMembers) {
            showAddMember = true
            onAutoOpenAddMemberConsumed()
        }
    }
    LaunchedEffect(canAdministerMembers) {
        if (!canAdministerMembers) showAddMember = false
    }
    val noShareTargetText = stringResource(R.string.no_share_target_available)
    val groupTitleCopy = rememberGroupTitleCopy()
    appState.profileRevisionForCompose
    val conversationTitle = controller.title(groupTitleCopy)
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
    val canShowEditAction = !isDm && canEdit
    LaunchedEffect(canShowEditAction) {
        if (!canShowEditAction) showEditGroup = false
    }
    val dmSharedGroups =
        remember(dmPeerAccountIdHex, appState.chatListItems, controller.group.groupIdHex) {
            dmPeerAccountIdHex
                ?.let(appState::sharedGroupsWith)
                ?.let { directDetailsSharedGroups(it, controller.group.groupIdHex) }
                .orEmpty()
        }
    val groupPickerRevision = appState.profileGroupPickerRevision
    val dmAddableGroupsState =
        remember(dmPeerAccountIdHex, appState.chatListItems, groupPickerRevision) {
            dmPeerAccountIdHex?.let(appState::profileAddableGroupsState) ?: ProfileGroupPickerState.empty()
        }
    LaunchedEffect(showAddContactToGroups, dmAddableGroupsState.pendingGroupIds) {
        if (showAddContactToGroups && dmAddableGroupsState.pendingGroupIds.isNotEmpty()) {
            appState.requestProfileGroupMembers(dmAddableGroupsState.pendingGroupIds)
        }
    }
    val dmPeerCandidate =
        remember(dmPeerAccountIdHex, dmPeerNpub, conversationTitle) {
            if (dmPeerAccountIdHex != null && dmPeerNpub != null) {
                RecipientSearch.Candidate(
                    accountIdHex = dmPeerAccountIdHex,
                    displayName = conversationTitle,
                    npub = dmPeerNpub,
                )
            } else {
                null
            }
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
    val engineMuted =
        controller.latestChatListRow?.muted
            ?: appState.engineConversationMuted(controller.group.groupIdHex)
    var targetedMuteSettings by remember(appState.activeAccountRef, controller.group.groupIdHex) {
        mutableStateOf<dev.ipf.marmotkit.ChatNotificationSettingsFfi?>(null)
    }
    LaunchedEffect(appState.activeAccountRef, controller.group.groupIdHex, controller.latestChatListRow) {
        targetedMuteSettings = null
        if (controller.latestChatListRow == null) {
            targetedMuteSettings = appState.authoritativeConversationMuteSettings(controller.group.groupIdHex)
        }
    }
    val muteOverride = appState.conversationMuteOverride(controller.group.groupIdHex)
    val conversationMuted =
        muteOverride?.muted
            ?: controller.latestChatListRow?.muted
            ?: targetedMuteSettings?.muted
            ?: engineMuted
    val muteCommandPending = appState.isConversationMutePending(controller.group.groupIdHex)
    // Android retains only the All/Only-mentions preference behind MDK's mute.
    val conversationRestoreMode =
        remember(
            appState.activeAccountRef,
            controller.group.groupIdHex,
            notificationModes,
        ) {
            appState.conversationRestoreNotifyMode(controller.group.groupIdHex)
        }
    val conversationMuteExpiry =
        if (muteOverride != null) {
            muteOverride.mutedUntilMs
        } else {
            controller.latestChatListRow?.mutedUntilMs
                ?: targetedMuteSettings?.mutedUntilMs
                ?: appState.conversationMuteExpiryMillis(controller.group.groupIdHex)
        }

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

    fun runGroupMutation(
        action: GroupMutationAction,
        mutation: suspend () -> Boolean,
        target: String? = null,
        onSuccess: () -> Unit = {},
    ) {
        // Launched on a process-lifetime scope so the MLS commit + Nostr
        // publish complete even if the user dismisses this sheet mid-flight.
        // The refreshMembers() + appState.present(toast) inside each
        // ConversationController.* method then always run, regardless of
        // whether this composable is still on screen.
        activeMutation = ActiveGroupMutation(action, target)
        controller.clearLastMutationError()
        appState.launchMutation {
            try {
                if (mutation()) onSuccess()
            } finally {
                // onSuccess() may have already dismissed this sheet; clearing
                // detached Compose state is harmless in that case.
                activeMutation = null
                pendingArchiveTarget = null
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

    fun exportTranscript() {
        if (transcriptExportInFlight) return
        transcriptExportInFlight = true
        appState.launchMutation {
            var shareSheetLaunched = false
            try {
                val file = controller.exportConversationTranscriptFile(context.cacheDir) ?: return@launchMutation
                pendingTranscriptShareFile = file
                try {
                    withContext(Dispatchers.Main) {
                        transcriptShareLauncher.launch(conversationTranscriptShareIntent(context, file))
                        shareSheetLaunched = true
                    }
                } catch (_: ActivityNotFoundException) {
                    pendingTranscriptShareFile = null
                    file.delete()
                    appState.present(R.string.toast_couldnt_export_transcript, AppText.Plain(noShareTargetText), copyable = true)
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

    // Close the picker only after the controller has accepted the invite and
    // installed its optimistic row. This avoids dismissing a selection when a
    // competing mutation won the controller's single-flight guard.
    LaunchedEffect(showAddMember, controller.pendingInviteMemberRefs) {
        if (showAddMember && controller.pendingInviteMemberRefs.isNotEmpty()) {
            if (addMemberAutoOpened) onBack() else showAddMember = false
        }
    }

    val sharedMediaTiles = rememberSharedMediaTiles(controller, appState)
    var showMediaLibrary by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    var showBubbleColors by remember(controller.group.groupIdHex) { mutableStateOf(false) }
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
    var showAutoReadPicker by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    var showDisappearingPicker by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    var pendingDisappearingSecs by remember(controller.group.groupIdHex) { mutableStateOf<Long?>(null) }
    var showFolderPicker by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    var showFolderCreate by remember(controller.group.groupIdHex) { mutableStateOf(false) }

    if (showMediaLibrary) {
        BackHandler { showMediaLibrary = false }
        MediaLibraryRoute(
            tiles = sharedMediaTiles,
            controller = controller,
            appState = appState,
            onBack = { showMediaLibrary = false },
            onJumpToMessage = onJumpToMessage,
        )
        return
    }

    if (showBubbleColors) {
        BackHandler { showBubbleColors = false }
        ChatBubbleColorsScreen(
            appState = appState,
            onBack = { showBubbleColors = false },
            groupIdHex = controller.group.groupIdHex,
        )
        return
    }

    if (showNotificationSettings) {
        BackHandler { showNotificationSettings = false }
        ConversationNotificationSettingsScreen(
            appState = appState,
            groupIdHex = controller.group.groupIdHex,
            conversationTitle = conversationTitle,
            conversationAvatarUrl = controller.avatarUrl,
            isDm = isDm,
            isMuted = conversationMuted,
            muteCommandPending = muteCommandPending,
            muteExpiryMillis = conversationMuteExpiry,
            notifyForMode = conversationRestoreMode,
            vibrationPattern = conversationVibrationPattern,
            onBack = { showNotificationSettings = false },
            onToggleMute = { turnOn ->
                if (turnOn) {
                    showMuteDurationDialog = true
                } else {
                    // Unmute back to the All/Only-mentions preference the mute hid.
                    appState.setConversationNotifyMode(controller.group.groupIdHex, conversationRestoreMode)
                }
            },
            onChooseNotifyFor = { showNotifyForDialog = true },
            onChooseVibrationPattern = { showVibrationPatternDialog = true },
        )
        if (showMuteDurationDialog) {
            MuteDurationDialog(
                onDismiss = { showMuteDurationDialog = false },
                onConfirm = { target ->
                    showMuteDurationDialog = false
                    when (target) {
                        is MuteTarget.After ->
                            appState.muteConversationFor(
                                controller.group.groupIdHex,
                                target.durationMillis,
                            )
                        is MuteTarget.At ->
                            appState.muteConversationUntil(
                                controller.group.groupIdHex,
                                target.expiryMillis,
                            )
                        MuteTarget.Always ->
                            appState.muteConversationFor(
                                controller.group.groupIdHex,
                                durationMillis = 0L,
                            )
                    }
                },
            )
        }
        if (showNotifyForDialog) {
            NotifyForDialog(
                currentMode = conversationRestoreMode,
                onDismiss = { showNotifyForDialog = false },
                onSelect = { mode ->
                    showNotifyForDialog = false
                    appState.setConversationNotifyForMode(controller.group.groupIdHex, mode)
                },
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
        return
    }

    BackHandler { onBack() }

    if (showEditGroup) {
        GroupEditScreen(appState = appState, controller = controller, onBack = { showEditGroup = false })
        return
    }

    val folderAccountRef = appState.activeAccountRef
    if (showFolderCreate && folderAccountRef != null) {
        ChatFolderEditScreen(
            appState = appState,
            accountRef = folderAccountRef,
            folderId = null,
            onClose = { showFolderCreate = false },
            initialManualChatIds = setOf(controller.group.groupIdHex.lowercase(Locale.ROOT)),
        )
        return
    }

    if (showAddMember && canAdministerMembers) {
        // remember scoped to this branch: the selection resets every time the
        // picker is reopened.
        val addSelection = remember { mutableStateListOf<RecipientSearch.Candidate>() }
        val adding = activeMutation?.action == GroupMutationAction.InviteMember
        ContactPickerScreen(
            appState = appState,
            title = stringResource(R.string.add_member),
            selected = addSelection,
            onBack = {
                // When auto-opened from the empty-group CTA, Back returns to the
                // conversation (via the details onBack) rather than exposing the
                // details body the user never intended to see.
                if (!adding) {
                    if (addMemberAutoOpened) onBack() else showAddMember = false
                }
            },
            onConfirm = confirm@{
                if (!canAdministerMembers) return@confirm
                val refs = addSelection.map { it.accountIdHex }
                // Members are added as regular members; admin is granted
                // per-member afterward from the profile sheet. The old bulk
                // "add as admin" toggle couldn't express per-member intent for
                // a multi-select add, so it's gone.
                runGroupMutation(
                    action = GroupMutationAction.InviteMember,
                    mutation = { controller.inviteMembers(refs, addAsAdmin = false) },
                    onSuccess = {
                        if (addMemberAutoOpened) onBack() else showAddMember = false
                    },
                )
            },
            confirmIcon = Icons.Default.Check,
            confirmLabel = stringResource(R.string.add_member),
            // The picker opens optimistically from the seed, but the commit
            // needs the authoritative roster: hold the confirm busy until it
            // lands instead of letting a tap hit the controller's gate silently.
            busy = adding || controller.mutationInFlight || !rosterReady,
            autoSelectResolvedIdentifier = true,
            excludeAccountIdHexes =
                (controller.presentedMembers.map { it.memberIdHex } + controller.pendingInviteMemberRefs).toSet(),
        )
        return
    }

    if (showStartGroupWithContact && dmPeerCandidate != null) {
        NewGroupFlow(
            appState = appState,
            initialMembers = listOf(dmPeerCandidate),
            onCreateCompletedOpen = onGroupCreateCompletedOpen,
            onCreateSubmitted = onGroupCreateSubmitted,
            onCreateFlowSuperseded = onGroupCreateFlowSuperseded,
            onClose = {
                showStartGroupWithContact = false
                onGroupCreateFlowSuperseded()
            },
        )
        return
    }

    if (showGroupInfo) {
        BackHandler { showGroupInfo = false }
        GroupInfoScreen(
            groupIdHex = controller.group.groupIdHex,
            nostrGroupIdHex = controller.group.nostrGroupIdHex,
            relays = controller.group.relays,
            onBack = { showGroupInfo = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (!readOnlyInvite) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.actions))
                        }
                    }
                    KeyboardPreservingDropdownMenu(
                        expanded = menuOpen && !readOnlyInvite,
                        onDismissRequest = { menuOpen = false },
                        shape = RoundedCornerShape(20.dp),
                        // Match the conversation top-bar menu exactly: inset from
                        // the right edge, roomy iconless body-large rows.
                        offset = DpOffset(x = (-8).dp, y = 0.dp),
                        modifier = Modifier.widthIn(min = 232.dp),
                    ) {
                        if (canShowEditAction) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.edit),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                },
                                contentPadding = conversationMenuItemPadding,
                                enabled = activeMutation == null && !controller.mutationInFlight,
                                onClick = {
                                    menuOpen = false
                                    showEditGroup = true
                                },
                            )
                        }
                        if (!readOnlyInvite) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            archiveMenuLabelForTarget(
                                                pendingArchiveTarget = pendingArchiveTarget,
                                                presentedArchived = controller.presentedArchived,
                                            ),
                                        ),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                },
                                contentPadding = conversationMenuItemPadding,
                                enabled = activeMutation == null && !controller.mutationInFlight,
                                onClick = {
                                    menuOpen = false
                                    val target = !controller.presentedArchived
                                    pendingArchiveTarget = target
                                    runGroupMutation(
                                        action = GroupMutationAction.Archive,
                                        mutation = { controller.setArchived(target) },
                                    )
                                },
                            )
                        }
                        if (!readOnlyInvite && controller.isSelfMember) {
                            val leaveLabel =
                                when {
                                    activeMutation?.action == GroupMutationAction.Leave -> R.string.leaving_chat
                                    isDm -> R.string.leave_chat
                                    else -> R.string.leave_group
                                }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(leaveLabel),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                },
                                contentPadding = conversationMenuItemPadding,
                                // Tappable for members (greyed while a mutation is
                                // in flight). The sole-admin gate is surfaced as an
                                // explanatory dialog by requestLeave rather than a
                                // silently-disabled item — but only once the roster
                                // is loaded, since requestLeave classifies the leave
                                // from member count and an empty roster reads as
                                // "sole member" (delete group).
                                enabled = activeMutation == null && !controller.mutationInFlight && controller.membersLoaded,
                                onClick = {
                                    menuOpen = false
                                    requestLeave(controller.title(groupTitleCopy))
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.group_info),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            },
                            contentPadding = conversationMenuItemPadding,
                            onClick = {
                                menuOpen = false
                                showGroupInfo = true
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            ConversationTransientNotice(
                notice = appState.transientNotice,
                accountRef = appState.activeAccountRef,
                groupIdHex = controller.group.groupIdHex,
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
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val encryptedGroupAvatar = rememberEncryptedGroupAvatar(appState, controller.group)
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
                pictureUrl = controller.avatarUrl,
                picture = encryptedGroupAvatar,
                archived = controller.presentedArchived,
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
            )

            if ((!isDm && canEdit) || onOpenSearch != null) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.spaceLg),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXl, Alignment.CenterHorizontally),
                ) {
                    GroupDetailsAddMemberAction(
                        visible = !isDm && canEdit,
                        rosterState = controller.memberRosterState,
                        mutationsBlocked = mutationsBlocked,
                        onClick = { showAddMember = true },
                        seededSelfMember = controller.seededSelfMember,
                    )
                    if (onOpenSearch != null) {
                        QuickActionButton(
                            icon = Icons.Default.Search,
                            label = stringResource(R.string.quick_action_search),
                            onClick = onOpenSearch,
                        )
                    }
                }
            }

            controller.lastMutationError?.let { error ->
                Box(Modifier.padding(horizontal = Dimens.spaceLg)) {
                    GroupMutationErrorBanner(
                        error = error,
                        onDismiss = { controller.clearLastMutationError() },
                    )
                }
            }

            Column(Modifier.padding(horizontal = Dimens.spaceLg)) {
                SharedMediaSection(
                    tiles = sharedMediaTiles,
                    controller = controller,
                    appState = appState,
                    onSeeAll = { showMediaLibrary = true },
                    onJumpToMessage = onJumpToMessage,
                )
            }

            Column {
                AppDivider()
                Spacer(Modifier.height(Dimens.spaceMd))
                SettingsActionRow(
                    icon = Icons.Default.Schedule,
                    title = stringResource(R.string.disappearing_messages),
                    value = disappearingMessagesLabel(controller.group.disappearingMessageSecs.toLong()),
                    supportingText =
                        stringResource(
                            if (canEdit) {
                                R.string.disappearing_row_hint_admin
                            } else {
                                R.string.disappearing_row_hint_readonly
                            },
                        ),
                    enabled = canEdit && !mutationsBlocked,
                    inProgress = activeMutation?.action == GroupMutationAction.DisappearingMessages,
                    disabledReason =
                        stringResource(R.string.disappearing_row_hint_readonly).takeUnless { canEdit },
                    // Keep a disabled button semantic for role-gated rows. A
                    // missing click action looked enabled but silently ignored
                    // touch and accessibility activation (#2201).
                    onClick = { showDisappearingPicker = true },
                )
                SettingsActionRow(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.chat_bubble_colors),
                    onClick = { showBubbleColors = true },
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
                        appState.profileRevisionForCompose,
                        folderAccountRef,
                        chatIdLower,
                        groupTitleCopy,
                    ) {
                        val accountRef = folderAccountRef ?: return@remember emptyList()
                        val thisChatRow = appState.chatListItems.filter { it.id.equals(chatIdLower, ignoreCase = true) }
                        appState.chatFolderPreferences
                            .foldersFor(accountRef)
                            .mapNotNull { folder ->
                                val manual =
                                    chatIdLower in appState.chatFolderPreferences.membershipFor(accountRef, folder.id)
                                val effective =
                                    chatIdLower in
                                        chatFolderChatIds(
                                            items = thisChatRow,
                                            manualChatIds =
                                                appState.chatFolderPreferences.membershipFor(accountRef, folder.id),
                                            rule = appState.chatFolderPreferences.folderRule(accountRef, folder.id),
                                            activeAccountIdHex = appState.activeAccount?.accountIdHex,
                                            isMuted = { groupIdHex ->
                                                thisChatRow.any {
                                                    it.group.groupIdHex == groupIdHex && it.engineMuted()
                                                }
                                            },
                                            displayTitle = { chatListItemDisplayTitle(it, appState, groupTitleCopy) },
                                        )
                                if (effective) folder to manual else null
                            }
                    }
                SettingsActionRow(
                    icon = Icons.Default.Folder,
                    title = stringResource(R.string.chat_folders_title),
                    value =
                        folderNames
                            .takeIf { it.isNotEmpty() }
                            ?.map { (folder, _) -> chatFolderDisplayName(folder) }
                            ?.joinToString(", ")
                            ?: stringResource(R.string.chat_folders_none),
                    onClick = { showFolderPicker = true },
                )
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
                GroupSwitchActionRow(
                    icon = Icons.AutoMirrored.Filled.WrapText,
                    title = stringResource(R.string.collapse_long_messages),
                    subtitle = stringResource(R.string.collapse_long_messages_subtitle),
                    checked = collapseLongMessages,
                    onCheckedChange = {
                        appState.updateCollapseLongMessagesInGroup(controller.group.groupIdHex, it)
                    },
                )
                if (appState.ttsHasUsableEngine) {
                    TtsAutoReadGroupActionRow(
                        title = stringResource(R.string.tts_auto_read_title),
                        provenanceLabel = autoReadSettingLabel,
                        onClick = { showAutoReadPicker = true },
                    )
                }
                SettingsActionRow(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.sounds_and_notifications),
                    value = notificationModeLabel(conversationNotifyMode),
                    modifier = Modifier.performanceTestTag(PerformanceTestTags.GROUP_NOTIFICATION_SETTINGS),
                    onClick = { showNotificationSettings = true },
                )
                if (isDm && dmPeerCandidate != null) {
                    DirectDetailsContactEditorRow(
                        appState = appState,
                        groupIdHex = controller.group.groupIdHex,
                        peerAccountIdHex = dmPeerAccountIdHex,
                        isDm = isDm,
                        readOnlyInvite = readOnlyInvite,
                        dmPeerNpub = dmPeerNpub,
                        activeAccountRef = appState.activeAccountRef,
                        accounts = appState.accounts,
                    )
                }
            }

            if (showAutoReadPicker) {
                TtsAutoReadPickerSheet(
                    globalDefaultEnabled = ttsAutoReadPrefs.globalDefaultEnabled,
                    selectedOverride = autoReadOverride,
                    onDismiss = { showAutoReadPicker = false },
                    onSelect = { override ->
                        appState.setConversationAutoReadOverride(controller.group.groupIdHex, override)
                    },
                )
            }

            if (showDisappearingPicker) {
                DisappearingMessagesPickerDialog(
                    currentSecs = controller.group.disappearingMessageSecs.toLong(),
                    onDismiss = { showDisappearingPicker = false },
                    onPick = { secs ->
                        showDisappearingPicker = false
                        val currentSecs = controller.group.disappearingMessageSecs.toLong()
                        // Only turning the timer ON (from off) or SHORTENING it prunes
                        // existing history, so confirm just those. An unchanged pick is
                        // a no-op; turning off or relaxing (lengthening) the window
                        // prunes nothing, so apply it directly without the destructive
                        // warning (#674 review).
                        val needsConfirm = secs > 0L && (currentSecs == 0L || secs < currentSecs)
                        when {
                            secs == currentSecs -> Unit
                            needsConfirm -> pendingDisappearingSecs = secs
                            else ->
                                runGroupMutation(
                                    action = GroupMutationAction.DisappearingMessages,
                                    mutation = { controller.updateMessageRetention(secs.toULong()) },
                                )
                        }
                    },
                )
            }

            pendingDisappearingSecs?.let { secs ->
                ConfirmDialog(
                    title = stringResource(R.string.disappearing_confirm_title),
                    message = stringResource(R.string.disappearing_confirm_message, disappearingMessagesLabel(secs)),
                    confirmLabel = stringResource(R.string.disappearing_confirm_button),
                    onConfirm = {
                        pendingDisappearingSecs = null
                        runGroupMutation(
                            action = GroupMutationAction.DisappearingMessages,
                            mutation = { controller.updateMessageRetention(secs.toULong()) },
                        )
                    },
                    onDismiss = { pendingDisappearingSecs = null },
                    destructive = true,
                )
            }

            if (isDm && dmPeerCandidate != null) {
                // Give the contact actions a small section inset after the
                // settings divider, then keep the actions and shared groups
                // contiguous through their own row padding.
                Column {
                    AppDivider()
                    Spacer(Modifier.height(Dimens.spaceSm))
                    DmDetailsContactActionRows(
                        createGroupTitle = stringResource(R.string.contact_create_group_with, conversationTitle),
                        addToGroupTitle = stringResource(R.string.contact_add_to_group),
                        addingContactToGroups = addingContactToGroups,
                        onCreateGroup = { showStartGroupWithContact = true },
                        onAddToGroup = { showAddContactToGroups = true },
                    )
                    if (dmSharedGroups.isNotEmpty()) {
                        SectionHeader(
                            pluralStringResource(
                                R.plurals.contact_groups_in_common,
                                dmSharedGroups.size,
                                dmSharedGroups.size,
                            ),
                        )
                    }
                    visibleDirectDetailsSharedGroups(dmSharedGroups, sharedGroupsExpanded).forEach { sharedGroup ->
                        ContactRow(
                            title = chatListItemDisplayTitle(sharedGroup, appState, groupTitleCopy),
                            subtitle = stringResource(R.string.members_count, sharedGroup.memberCount),
                            avatarSeed = sharedGroup.group.groupIdHex,
                            avatarUrl = sharedGroup.group.avatarUrl,
                            avatarImage = rememberEncryptedGroupAvatar(appState, sharedGroup.group),
                            onClick = { onOpenConversation(sharedGroup, false) },
                        )
                    }
                    if (!sharedGroupsExpanded && dmSharedGroups.size > SHARED_GROUPS_PREVIEW_COUNT) {
                        FlowQuickActionRow(
                            icon = Icons.Default.ExpandMore,
                            title = stringResource(R.string.contact_groups_see_all),
                            onClick = { sharedGroupsExpanded = true },
                        )
                    }
                }
            }

            if (!isDm) {
                // Keep the member header, add action, identities and technical
                // info in one contiguous list section. ContactRow/FlowQuickActionRow
                // already provide Material touch heights and internal padding.
                Column {
                    AppDivider()
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
                            Text(
                                stringResource(R.string.members_count, controller.presentedMemberCount),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f).padding(start = Dimens.spaceLg, end = Dimens.spaceSm),
                            )
                            IconButton(
                                onClick = {
                                    memberSearchOpen = !memberSearchOpen
                                    if (!memberSearchOpen) memberQuery = ""
                                },
                                modifier = Modifier.padding(end = Dimens.spaceSm),
                            ) {
                                Icon(
                                    if (memberSearchOpen) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = stringResource(R.string.search_members),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (memberSearchOpen) {
                            FlowSearchField(
                                value = memberQuery,
                                onValueChange = { memberQuery = it },
                                placeholder = stringResource(R.string.search_members),
                                modifier =
                                    Modifier
                                        .padding(horizontal = Dimens.spaceLg)
                                        .padding(bottom = Dimens.spaceSm),
                            )
                        }
                        if (canEdit) {
                            FlowQuickActionRow(
                                icon = Icons.Default.PersonAdd,
                                title = stringResource(R.string.add_member),
                                enabled = !mutationsBlocked,
                                onClick = {
                                    showAddMember = true
                                },
                            )
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
                                        memberTitlesByHex[it.memberIdHex]
                                            .orEmpty()
                                            .contains(memberNeedle, ignoreCase = true)
                                    }
                                membersExpanded || displayedMembers.size <= GROUP_MEMBERS_PREVIEW_COUNT ->
                                    displayedMembers
                                else -> displayedMembers.take(GROUP_MEMBERS_PREVIEW_COUNT)
                            }
                        if (memberNeedle.isNotEmpty() && visibleMembers.isEmpty()) {
                            Text(
                                stringResource(R.string.no_matches),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Dimens.spaceLg),
                            )
                        }
                        // Row taps route into the profile sheet, which carries the same
                        // admin actions (grant/revoke admin, remove) the old per-row menu
                        // exposed (#444/#635 scope rules).
                        GroupMemberIdentityRows(visibleMembers) { index, member ->
                            val isSelfRow = GroupProjector.isActiveAccountMember(member, activeAccountIdHex)
                            val rowMutationPending =
                                controller.isMemberMutationPending(member.memberIdHex) ||
                                    activeMutation?.target == member.memberIdHex
                            val memberNpub = appState.npubForDisplay(member.memberIdHex)
                            ContactRow(
                                title = controller.memberDisplayName(member),
                                subtitle =
                                    if (isSelfRow) {
                                        stringResource(R.string.you)
                                    } else {
                                        IdentityFormatter.short(memberNpub)
                                    },
                                avatarSeed = member.memberIdHex,
                                avatarUrl = controller.memberAvatarUrl(member),
                                modifier =
                                    if (index == 0) {
                                        Modifier.performanceTestTag(PerformanceTestTags.MEMBER_LIST)
                                    } else {
                                        Modifier
                                    },
                                onSubtitleClick =
                                    if (isSelfRow || memberNpub.isBlank()) {
                                        null
                                    } else {
                                        { clipboard.setText(AnnotatedString(memberNpub)) }
                                    },
                                onClick = { appState.presentProfile(appState.npub(member.memberIdHex)) },
                                trailing = {
                                    GroupMemberMutationStatus(
                                        isAdmin = controller.isAdmin(member),
                                        inProgress = rowMutationPending,
                                    )
                                },
                            )
                        }
                        val canExpandMembers =
                            memberNeedle.isEmpty() &&
                                !membersExpanded &&
                                displayedMembers.size > GROUP_MEMBERS_PREVIEW_COUNT
                        if (canExpandMembers) {
                            FlowQuickActionRow(
                                icon = Icons.Default.ExpandMore,
                                title = stringResource(R.string.see_all_members, displayedMembers.size),
                                onClick = { membersExpanded = true },
                            )
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
                                avatarSeed = invite,
                                avatarUrl = appState.avatarUrl(invite),
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

            if (appState.developerMode) {
                Column(
                    Modifier.padding(horizontal = Dimens.spaceLg),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsActionRow(
                        icon = Icons.Default.Description,
                        title = stringResource(R.string.export_conversation_transcript),
                        enabled = !transcriptExportInFlight && appState.activeAccountRef != null,
                        inProgress = transcriptExportInFlight,
                        onClick = { exportTranscript() },
                    )

                    SectionCard(title = stringResource(R.string.mls)) {
                        when {
                            mlsLoading -> Text(stringResource(R.string.loading_mls_state), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            mlsState == null -> Text(stringResource(R.string.mls_state_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> {
                                val state = requireNotNull(mlsState)
                                DiagnosticRow(
                                    stringResource(R.string.group_id),
                                    IdentityFormatter.short(state.groupIdHex),
                                    copyValue = state.groupIdHex,
                                )
                                DiagnosticRow(stringResource(R.string.epoch), state.epoch.toString())
                                DiagnosticRow(stringResource(R.string.mls_members), state.memberCount.toString())
                                DiagnosticRow(stringResource(R.string.required_components), state.requiredAppComponents.joinToString(", "))
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

            // Danger zone (#416): leave routes through requestLeave so the
            // sole-admin and sole-member cases get their own confirm copy. On
            // failure the controller's lastMutationError surfaces inline here
            // (in addition to the snackbar) so the user can retry in place.
            Column {
                AppDivider()
                val selfMember =
                    controller.members.firstOrNull { GroupProjector.isActiveAccountMember(it, activeAccountIdHex) }
                if (!isDm && canEdit && selfMember != null) {
                    DangerActionRow(
                        icon = Icons.Default.Shield,
                        title = stringResource(R.string.step_down_as_admin),
                        enabled = !mutationsBlocked,
                        inProgress = activeMutation?.action == GroupMutationAction.SelfDemoteAdmin,
                        onClick = {
                            pendingConfirm =
                                if (controller.isSoleAdminWithOtherMembers) {
                                    DetailsConfirm.StepDownSoleAdmin
                                } else {
                                    DetailsConfirm.StepDownAdmin(selfMember)
                                }
                        },
                    )
                }
                if (!readOnlyInvite) {
                    DangerActionRow(
                        icon = Icons.Default.Archive,
                        title =
                            stringResource(
                                if (controller.presentedArchived) R.string.unarchive_chat else R.string.archive_chat,
                            ),
                        enabled = !mutationsBlocked,
                        inProgress = activeMutation?.action == GroupMutationAction.Archive,
                        onClick = {
                            runGroupMutation(
                                action = GroupMutationAction.Archive,
                                mutation = { controller.setArchived(!controller.presentedArchived) },
                            )
                        },
                    )
                }
                if (controller.isSelfMember) {
                    DangerActionRow(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        title = stringResource(if (isDm) R.string.leave_chat else R.string.leave_group),
                        // The engine refuses Leave for disbanding/terminal groups;
                        // local delete below is the exit for a dead group.
                        enabled = !mutationsBlocked && controller.membersLoaded && !groupTerminal,
                        inProgress = activeMutation?.action == GroupMutationAction.Leave,
                        onClick = { requestLeave(controller.title(groupTitleCopy)) },
                    )
                    if (!isDm) {
                        GroupDetailsDisbandControls(
                            management = controller.managementState,
                            enabled = !mutationsBlocked,
                            enableInProgress = activeMutation?.action == GroupMutationAction.EnableDisbanding,
                            disbandInProgress = activeMutation?.action == GroupMutationAction.Disband,
                            onEnable = {
                                runGroupMutation(
                                    action = GroupMutationAction.EnableDisbanding,
                                    mutation = { controller.enableGroupDisbanding() },
                                )
                            },
                            onDisbandConfirmed = {
                                runGroupMutation(
                                    action = GroupMutationAction.Disband,
                                    mutation = { controller.disbandGroup() },
                                )
                            },
                            onAcknowledgeFailure = {
                                runGroupMutation(
                                    action = GroupMutationAction.Disband,
                                    mutation = { controller.acknowledgeDisbandFailure() },
                                )
                            },
                        )
                    }
                }
                GroupDetailsLocalDeleteControl(
                    isDm = isDm,
                    readOnlyInvite = readOnlyInvite,
                    isSelfMember = controller.isSelfMember,
                    membersVerified = controller.membersVerified,
                    enabled = !mutationsBlocked,
                    inProgress = activeMutation?.action == GroupMutationAction.Delete,
                    onDeleteConfirmed = {
                        runGroupMutation(
                            action = GroupMutationAction.Delete,
                            mutation = { controller.deleteGroupLocal() },
                            onSuccess = onLeft,
                        )
                    },
                )
            }
        }
    }
    if (showAddContactToGroups && dmPeerAccountIdHex != null) {
        ProfileAddToGroupsSheet(
            appState = appState,
            targetName = conversationTitle,
            state = dmAddableGroupsState,
            busy = addingContactToGroups,
            onDismiss = {
                if (!addingContactToGroups) showAddContactToGroups = false
            },
            onRetry = {
                appState.requestProfileGroupMembers(
                    dmAddableGroupsState.pendingGroupIds,
                    retry = true,
                )
            },
            onAdd = { selected ->
                if (addingContactToGroups) return@ProfileAddToGroupsSheet
                addingContactToGroups = true
                appState.launchMutation {
                    try {
                        val allAdded =
                            appState.inviteProfileToGroups(
                                targetRef = dmPeerAccountIdHex,
                                targetGroupIds = selected.map { it.group.groupIdHex },
                            )
                        if (allAdded) showAddContactToGroups = false
                    } finally {
                        addingContactToGroups = false
                    }
                }
            },
        )
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
                if (leaveName != null) {
                    // Sole-admin Leave, 3+ members (#1131): transfer then leave as
                    // one action. controller.group updates in-place after the
                    // transfer, so the subsequent leave's gate sees the new admin.
                    runGroupMutation(
                        action = GroupMutationAction.Leave,
                        mutation = { controller.transferAdmin(member) && controller.leaveGroup(displayName = leaveName) },
                        onSuccess = { onLeft() },
                    )
                } else {
                    pendingConfirm = DetailsConfirm.TransferAdmin(member)
                }
            },
            onDismiss = {
                showTransferAdmin = false
                transferThenLeaveName = null
            },
        )
    }

    pendingConfirm?.let { confirm ->
        when (confirm) {
            is DetailsConfirm.RemoveMember ->
                ConfirmDialog(
                    title = stringResource(R.string.confirm_remove_member_title),
                    message =
                        stringResource(
                            R.string.confirm_remove_member_message,
                            controller.memberDisplayName(confirm.member),
                        ),
                    confirmLabel = stringResource(R.string.remove_member),
                    onConfirm = {
                        pendingConfirm = null
                        runGroupMutation(
                            action = GroupMutationAction.RemoveMember,
                            mutation = { controller.removeMember(confirm.member) },
                            target = confirm.member.memberIdHex,
                        )
                    },
                    onDismiss = { pendingConfirm = null },
                    destructive = true,
                )
            is DetailsConfirm.TransferAdmin ->
                ConfirmDialog(
                    title = stringResource(R.string.confirm_transfer_admin_title),
                    message =
                        stringResource(
                            R.string.confirm_transfer_admin_message,
                            controller.memberDisplayName(confirm.member),
                        ),
                    confirmLabel = stringResource(R.string.transfer_admin),
                    onConfirm = {
                        pendingConfirm = null
                        runGroupMutation(
                            action = GroupMutationAction.TransferAdmin,
                            mutation = { controller.transferAdmin(confirm.member) },
                            target = confirm.member.memberIdHex,
                        )
                    },
                    onDismiss = { pendingConfirm = null },
                )
            is DetailsConfirm.StepDownAdmin ->
                ConfirmDialog(
                    title = stringResource(R.string.step_down_as_admin),
                    message = stringResource(R.string.confirm_step_down_admin_message),
                    confirmLabel = stringResource(R.string.step_down_as_admin),
                    onConfirm = {
                        pendingConfirm = null
                        runGroupMutation(
                            action = GroupMutationAction.SelfDemoteAdmin,
                            mutation = { controller.stepDownAsAdmin() },
                            target = confirm.member.memberIdHex,
                        )
                    },
                    onDismiss = { pendingConfirm = null },
                    destructive = true,
                )
            DetailsConfirm.StepDownSoleAdmin ->
                AlertDialog(
                    onDismissRequest = { pendingConfirm = null },
                    title = { Text(stringResource(R.string.sole_admin_leave_blocked_title)) },
                    text = { Text(stringResource(R.string.sole_admin_transfer_hint)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingConfirm = null
                                showTransferAdmin = true
                            },
                        ) {
                            Text(stringResource(R.string.transfer_admin))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingConfirm = null }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            is DetailsConfirm.Leave ->
                ConfirmDialog(
                    title = stringResource(R.string.confirm_leave_title_named, confirm.groupName),
                    message = stringResource(R.string.confirm_leave_message_named),
                    confirmLabel = stringResource(R.string.leave),
                    onConfirm = {
                        pendingConfirm = null
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
                    onDismiss = { pendingConfirm = null },
                    destructive = true,
                )
            is DetailsConfirm.LeaveSoleMember ->
                // Sole member: leaving dissolves the group entirely through local
                // cleanup (no other member to coordinate an MLS commit with). The
                // copy makes the destructive "this deletes the group" consequence clear.
                ConfirmDialog(
                    title = stringResource(R.string.confirm_leave_sole_member_title, confirm.groupName),
                    message = stringResource(R.string.confirm_leave_sole_member_message),
                    confirmLabel = stringResource(R.string.leave),
                    onConfirm = {
                        pendingConfirm = null
                        runGroupMutation(
                            action = GroupMutationAction.Leave,
                            mutation = { controller.leaveGroup(displayName = confirm.groupName) },
                            onSuccess = {
                                onLeft()
                            },
                        )
                    },
                    onDismiss = { pendingConfirm = null },
                    destructive = true,
                )
            is DetailsConfirm.LeaveSoleAdmin ->
                // Fallback for the (unexpected) no-transfer-candidate case — still
                // an informational gate. The normal sole-admin Leave now routes to
                // LeaveSoleAdminTransfer or the picker in leave mode (#1131).
                AlertDialog(
                    onDismissRequest = { pendingConfirm = null },
                    title = { Text(stringResource(R.string.confirm_leave_sole_admin_title)) },
                    text = { Text(stringResource(R.string.confirm_leave_sole_admin_message, confirm.groupName)) },
                    confirmButton = {
                        TextButton(onClick = { pendingConfirm = null }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            is DetailsConfirm.LeaveSoleAdminTransfer ->
                ConfirmDialog(
                    title = stringResource(R.string.confirm_leave_sole_admin_title),
                    message =
                        stringResource(
                            R.string.confirm_sole_admin_transfer_then_leave_message,
                            controller.memberDisplayName(confirm.newAdmin),
                        ),
                    confirmLabel = stringResource(R.string.leave),
                    onConfirm = {
                        pendingConfirm = null
                        runGroupMutation(
                            action = GroupMutationAction.Leave,
                            mutation = { controller.transferAdmin(confirm.newAdmin) && controller.leaveGroup(displayName = confirm.groupName) },
                            onSuccess = { onLeft() },
                        )
                    },
                    onDismiss = { pendingConfirm = null },
                    destructive = true,
                )
        }
    }
}

@Composable
internal fun GroupDetailsHeader(
    title: String,
    subtitle: String,
    description: String,
    seed: String,
    pictureUrl: String?,
    picture: ImageBitmap? = null,
    archived: Boolean,
    onEdit: (() -> Unit)? = null,
    editEnabled: Boolean = true,
    onAddDescription: (() -> Unit)? = null,
    descriptionCopyValue: String? = null,
) {
    val clipboard = LocalClipboardManager.current
    val safePictureUrl = ProfileSanitizer.protocolImageUrl(pictureUrl)
    val remoteImageAvailable = rememberAvatarImageAvailable(safePictureUrl)
    val avatarImageAvailable = picture != null || remoteImageAvailable
    var viewerOpen by remember(safePictureUrl, picture) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp).padding(horizontal = Dimens.spaceLg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .clickable(
                            enabled = avatarImageAvailable,
                            onClickLabel = stringResource(R.string.profile_view_picture),
                            role = Role.Button,
                        ) { viewerOpen = true },
            ) {
                Avatar(
                    title = title,
                    seed = seed,
                    size = 96.dp,
                    pictureUrl = safePictureUrl?.takeIf { picture == null },
                    picture = picture,
                )
            }
            if (onEdit == null) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Row(
                    modifier =
                        Modifier
                            .widthIn(max = 320.dp)
                            .heightIn(min = 48.dp)
                            .clickable(
                                enabled = editEnabled,
                                onClickLabel = stringResource(R.string.edit_group_name_and_description),
                                role = Role.Button,
                                onClick = onEdit,
                            ),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (description.isNotBlank()) {
                val copyValue = descriptionCopyValue
                if (copyValue == null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    val copyLabel = stringResource(R.string.copy)
                    Row(
                        modifier =
                            Modifier
                                .widthIn(max = 320.dp)
                                .minimumInteractiveComponentSize()
                                .semantics { contentDescription = copyValue }
                                .clickable(
                                    onClickLabel = copyLabel,
                                    role = Role.Button,
                                ) {
                                    clipboard.setText(AnnotatedString(copyValue))
                                },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            } else if (onAddDescription != null) {
                TextButton(onClick = onAddDescription) {
                    Text(
                        stringResource(R.string.add_group_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (archived) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.archive_chat)) },
                        leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                    )
                }
            }
        }
    }
    if (viewerOpen && avatarImageAvailable) {
        AvatarFullScreenViewer(
            title = title,
            seed = seed,
            pictureUrl = safePictureUrl,
            picture = picture,
            onDismiss = { viewerOpen = false },
        )
    }
}

@Composable
private fun GroupMutationErrorBanner(
    error: ErrorPresentation,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val message = error.message.resolve(context)
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null)
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.latest_group_error, message),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(error.report)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy))
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dismiss))
            }
        }
    }
}

@Composable
private fun GroupSwitchActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Switch) { onCheckedChange(!checked) }
                .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun GroupActionRow(
    icon: @Composable () -> Unit,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.5f else 0.28f),
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                icon()
            }
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
    }
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

@Composable
private fun PushDeliveryDebugSection(
    info: GroupPushDebugInfoFfi?,
    loading: Boolean,
    appState: WhiteNoiseAppState,
) {
    SectionCard(title = stringResource(R.string.push_delivery)) {
        when {
            loading ->
                Text(
                    stringResource(R.string.loading_push_debug_info),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            info == null ->
                Text(
                    stringResource(R.string.push_debug_info_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else -> {
                val yesText = stringResource(R.string.yes)
                val noText = stringResource(R.string.no)
                val yesNo: (Boolean) -> String = { value -> if (value) yesText else noText }

                DiagnosticRow(stringResource(R.string.push_debug_total_tokens), info.totalTokenCount.toString())
                DiagnosticRow(stringResource(R.string.push_debug_active_tokens), info.activeTokenCount.toString())
                DiagnosticRow(stringResource(R.string.push_debug_stale_tokens), info.staleTokenCount.toString())
                DiagnosticRow(stringResource(R.string.push_debug_missing_relay_hints), info.missingRelayHintCount.toString())
                info.lastTokenListUpdatedAtMs?.let { updatedAtMs ->
                    val updatedAtText = updatedAtMs.toString()
                    DiagnosticRow(
                        stringResource(R.string.push_debug_last_token_list_update),
                        updatedAtText,
                        copyValue = updatedAtText,
                    )
                }

                Spacer(Modifier.heightIn(min = 4.dp))
                Text(
                    stringResource(R.string.push_debug_local_registration),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val local = info.localRegistration
                DiagnosticRow(stringResource(R.string.push_debug_registered), yesNo(local.registered))
                DiagnosticRow(stringResource(R.string.push_debug_shareable), yesNo(local.shareable))
                DiagnosticRow(stringResource(R.string.push_debug_local_notifications_enabled), yesNo(local.localNotificationsEnabled))
                DiagnosticRow(stringResource(R.string.native_push), yesNo(local.nativePushEnabled))
                local.localLeafIndex?.let { leafIndex ->
                    DiagnosticRow(stringResource(R.string.push_debug_local_leaf_index), leafIndex.toString())
                }
                DiagnosticRow(stringResource(R.string.push_debug_local_token_cached), yesNo(local.localTokenCached))

                if (info.tokens.isNotEmpty()) {
                    Spacer(Modifier.heightIn(min = 4.dp))
                    Text(
                        stringResource(R.string.push_debug_member_tokens),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    info.tokens.forEachIndexed { index, token ->
                        if (index > 0) {
                            AppDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }
                        PushTokenDebugRows(token, appState, yesNo)
                    }
                }
            }
        }
    }
}

@Composable
private fun PushTokenDebugRows(
    token: GroupPushTokenDebugEntryFfi,
    appState: WhiteNoiseAppState,
    yesNo: (Boolean) -> String,
) {
    val memberName = appState.chatMemberTitle(token.memberIdHex)
    Text(
        memberName,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth(),
    )
    val identityCopy =
        pushDebugPublicIdentityCopy(
            memberIdHex = token.memberIdHex,
            serverPubkeyHex = token.serverPubkeyHex,
            npubForDisplay = appState::npubForDisplay,
        )
    identityCopy.memberNpub?.let { memberNpub ->
        DiagnosticRow(
            stringResource(R.string.push_debug_member),
            IdentityFormatter.short(memberNpub),
            copyValue = memberNpub,
        )
    }
    DiagnosticRow(stringResource(R.string.push_debug_leaf_index), token.leafIndex.toString())
    DiagnosticRow(stringResource(R.string.push_debug_platform), token.platform.name)
    DiagnosticRow(
        stringResource(R.string.push_debug_token_fingerprint),
        IdentityFormatter.short(token.tokenFingerprint),
        copyValue = token.tokenFingerprint,
    )
    identityCopy.serverNpub?.let { serverNpub ->
        DiagnosticRow(
            stringResource(R.string.push_debug_push_server_pubkey),
            IdentityFormatter.short(serverNpub),
            copyValue = serverNpub,
        )
    }
    DiagnosticRow(stringResource(R.string.push_debug_relay_hint), yesNo(token.hasRelayHint))
    DiagnosticRow(stringResource(R.string.push_debug_active_leaf), yesNo(token.activeLeaf))
    DiagnosticRow(stringResource(R.string.push_debug_member_matches_active_leaf), yesNo(token.memberMatchesActiveLeaf))
    DiagnosticRow(stringResource(R.string.push_debug_is_local_member), yesNo(token.isLocalMember))
    val updatedAtText = token.updatedAtMs.toString()
    DiagnosticRow(
        stringResource(R.string.push_debug_updated_at),
        updatedAtText,
        copyValue = updatedAtText,
    )
}

private enum class GroupMutationAction {
    SaveProfile,
    InviteMember,
    RemoveMember,
    PromoteAdmin,
    DemoteAdmin,
    SelfDemoteAdmin,
    TransferAdmin,
    DisappearingMessages,
    Archive,
    Delete,
    Leave,
    EnableDisbanding,
    Disband,
}

private data class ActiveGroupMutation(
    val action: GroupMutationAction,
    val target: String? = null,
)
