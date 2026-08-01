package dev.ipf.whitenoise.android.ui.profile

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ProfileFieldValidation
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.chats.newchat.DangerActionRow
import dev.ipf.whitenoise.android.ui.chats.newchat.FlowSearchField
import dev.ipf.whitenoise.android.ui.chats.newchat.QuickActionButton
import dev.ipf.whitenoise.android.ui.chats.newchat.SelectionIndicator
import dev.ipf.whitenoise.android.ui.chats.newchat.SettingsActionRow
import dev.ipf.whitenoise.android.ui.chats.newchat.StartChatAttemptResult
import dev.ipf.whitenoise.android.ui.chats.newchat.StartChatErrorCard
import dev.ipf.whitenoise.android.ui.chats.newchat.StartChatErrorUiState
import dev.ipf.whitenoise.android.ui.chats.newchat.attemptStartProfileChat
import dev.ipf.whitenoise.android.ui.chats.newchat.inviteShareIntent
import dev.ipf.whitenoise.android.ui.common.AppDivider
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.CopyableValueRow
import dev.ipf.whitenoise.android.ui.common.GroupAvatar
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.common.rememberEncryptedGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.group.GroupMemberMenuAction
import dev.ipf.whitenoise.android.ui.group.groupMemberMenuActions
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * The group-admin actions to show in the in-conversation profile sheet
 * (issue #635) when an admin taps another member's avatar next to a message
 * bubble. Reuses [groupMemberMenuActions] verbatim so the scope rules stay in
 * lockstep with the Group Info members list (issue #444): actions appear only
 * when the viewer is an admin member of this group and the viewed user is a
 * member who is not the active account.
 *
 * The sheet surface never targets self (the avatar tap that opens it with admin
 * context is on someone else's bubble, and self is excluded by the issue), so
 * [GroupMemberMenuAction.StepDownAsAdmin] can never legitimately apply here; it
 * is filtered out defensively. [targetIsMember] is false when the viewed user
 * has no member record in this group, which fails scope and yields an empty
 * list regardless of the other inputs.
 */
internal fun profileSheetContactPrivateDetailsRowValue(
    contactNickname: String?,
    contactNotes: String?,
    addNicknameAndNotesLabel: String,
    notesLabel: String,
): String {
    contactNickname?.takeIf { it.isNotBlank() }?.let { return it }
    if (!contactNotes.isNullOrBlank()) return notesLabel
    return addNicknameAndNotesLabel
}

internal fun profileSheetAdminActions(
    viewerIsMember: Boolean,
    viewerIsAdmin: Boolean,
    targetIsMember: Boolean,
    targetIsSelf: Boolean,
    targetIsAdmin: Boolean,
): List<GroupMemberMenuAction> {
    if (!targetIsMember) return emptyList()
    return groupMemberMenuActions(
        viewerIsMember = viewerIsMember,
        viewerIsAdmin = viewerIsAdmin,
        targetIsSelf = targetIsSelf,
        targetIsAdmin = targetIsAdmin,
    ).filter { it != GroupMemberMenuAction.StepDownAsAdmin }
}

internal fun adminActionRowTag(action: GroupMemberMenuAction): String = "profile-admin-action-${action.name}"

internal fun runProfileSheetAdminMutation(
    action: GroupMemberMenuAction,
    isBusy: () -> Boolean,
    onPendingActionChange: (GroupMemberMenuAction?) -> Unit,
    clearLastMutationError: () -> Unit,
    launchMutation: (suspend () -> Unit) -> Unit,
    mutation: suspend () -> Unit,
): Boolean {
    if (isBusy()) return false
    onPendingActionChange(action)
    clearLastMutationError()
    launchMutation {
        try {
            mutation()
        } finally {
            onPendingActionChange(null)
        }
    }
    return true
}

@Composable
@Suppress("FunctionNaming")
internal fun ProfileSheetAdminActionRows(
    actions: List<GroupMemberMenuAction>,
    pendingAction: GroupMemberMenuAction?,
    busy: Boolean,
    onGrantAdmin: () -> Unit,
    onRevokeAdmin: () -> Unit,
    onRemoveMember: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        actions.forEach { action ->
            when (action) {
                GroupMemberMenuAction.GrantAdmin ->
                    SettingsActionRow(
                        icon = Icons.Default.Shield,
                        title = stringResource(R.string.make_admin),
                        modifier = Modifier.testTag(adminActionRowTag(action)),
                        enabled = !busy,
                        inProgress = pendingAction == action,
                        onClick = onGrantAdmin,
                    )
                GroupMemberMenuAction.RevokeAdmin ->
                    SettingsActionRow(
                        icon = Icons.Default.Shield,
                        title = stringResource(R.string.remove_admin),
                        modifier = Modifier.testTag(adminActionRowTag(action)),
                        enabled = !busy,
                        inProgress = pendingAction == action,
                        onClick = onRevokeAdmin,
                    )
                GroupMemberMenuAction.RemoveMember ->
                    DangerActionRow(
                        icon = Icons.Default.Delete,
                        title = stringResource(R.string.remove_member),
                        modifier = Modifier.testTag(adminActionRowTag(action)),
                        enabled = !busy,
                        inProgress = pendingAction == action,
                        onClick = onRemoveMember,
                    )
                // Self is excluded on this surface, so StepDownAsAdmin never
                // appears (it is filtered out by profileSheetAdminActions).
                GroupMemberMenuAction.StepDownAsAdmin -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileSheet(
    appState: WhiteNoiseAppState,
    npub: String,
    // (chat, justCreated): justCreated is true only on the path that just
    // created a brand-new DM with this person (issue #321), so the conversation
    // opens with the composer focused + keyboard up. Opening an existing DM or
    // a shared group passes false.
    onOpenGroup: (ChatListItem, Boolean) -> Unit,
    onStartGroup: (RecipientSearch.Candidate) -> Unit,
    onDismiss: () -> Unit,
    // Non-null only when the sheet is opened from inside a group conversation
    // by tapping a member's bubble avatar (issue #635). Supplies the live
    // ConversationController for that group so the sheet can show group-admin
    // moderation actions (grant/revoke admin, remove member) with the same
    // scope rules and engine calls the Group Info members list uses (#444).
    // Null for every other entry point (mentions, QR, reaction list, shell
    // members-list row), which keeps those sheets byte-identical to before.
    adminController: ConversationController? = null,
    securePolicy: SecureFlagPolicy = SecureFlagPolicy.Inherit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var hex by remember(npub) { mutableStateOf<String?>(null) }
    var fullPictureOpen by remember(npub) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val contentScrollState = rememberScrollState()
    val groupTitleCopy = rememberGroupTitleCopy()
    val compactMemberSheet = adminController != null

    LaunchedEffect(npub) {
        val resolved = appState.accountIdHex(npub)
        hex = resolved
        if (resolved != null) appState.refreshProfile(resolved)
    }

    val profile = hex?.let { appState.userProfile(it) }
    val title = hex?.let { appState.networkDisplayName(it) } ?: IdentityFormatter.short(npub)
    val contactNickname = hex?.let { appState.contactNickname(it) }
    val contactNotes = hex?.let { appState.contactNotes(it) }
    // #1226: the header + identity surfaces show the nickname when one is set;
    // the "name from profile" section and the nickname dialog deliberately keep
    // the real profile name (`title`) so the user sees what they're renaming.
    val displayTitle = contactNickname ?: title
    val pictureUrl = hex?.let { appState.avatarUrl(it) } ?: ProfileSanitizer.imageUrl(profile?.picture)
    val avatarImageAvailable = rememberAvatarImageAvailable(pictureUrl)
    val about = ProfileSanitizer.about(profile?.about)
    val nip05 =
        profile
            ?.nip05
            ?.trim()
            ?.takeIf { ProfileFieldValidation.isAcceptableNip05(it) }
    // The named, multi-member groups this account shares with the active user.
    // The whole derivation — the O(groups) `sharedGroupsWith` projection/scan
    // and the filter + list allocation — is memoized, keyed on `hex` and the
    // controller's observable chat-list projection (`appState.chatListItems`),
    // so it runs only when the underlying group set / membership / names
    // actually change and is skipped on unrelated recompositions (e.g. the
    // profile name/avatar resolving). Reading `chatListItems` as a key also
    // subscribes the sheet so the list still refreshes when the groups change
    // while it's open. This mirrors the keyed-remember memoization used by the
    // sibling sheets in this file (TransferAdminSheet, ReactionDetailsSheet,
    // ForwardSheet).
    val sharedGroups =
        remember(hex, appState.chatListItems) {
            // Only named, multi-member groups belong in this list: the 1:1 DM is
            // reached via the Message button, and an unnamed group would just
            // read as "Group of N people".
            hex
                ?.let { appState.sharedGroupsWith(it) }
                .orEmpty()
                .filter { it.memberCount > 2 && it.group.name.isNotBlank() }
        }
    // The existing 1:1 DM with this person, if any — the confirmed two-member
    // group with them. Drives the Message button: open it when present,
    // otherwise start a new DM.
    val directMessageGroup =
        remember(npub, appState.chatListItems) {
            appState.existingDirectChat(npub)
        }
    // True while a brand-new DM is being created+published, so the Message
    // button shows progress and we don't dismiss into a blank gap before the
    // conversation opens.
    var creatingChat by remember(npub) { mutableStateOf(false) }
    var startChatError by remember(npub) { mutableStateOf<StartChatErrorUiState?>(null) }
    var showAddToGroups by remember(npub) { mutableStateOf(false) }
    var showContactEditorDialog by remember(npub) { mutableStateOf(false) }
    var addingToGroups by remember(npub) { mutableStateOf(false) }
    val activeAccountHex = appState.activeAccount?.accountIdHex
    // UI guard covers both profile actions, including "Start new group". The
    // state-layer addable-groups helper still rejects self as a defensive check
    // for the add-to-existing-groups path.
    val targetIsSelf = hex?.let { activeAccountHex?.equals(it, ignoreCase = true) == true } == true
    val inviteTitle = stringResource(R.string.invite_to_white_noise)
    val inviteMessage = stringResource(R.string.invite_message)
    val addableGroups =
        remember(hex, appState.chatListItems) {
            hex?.let { appState.profileAddableGroups(it) }.orEmpty()
        }

    if (showAddToGroups && hex != null) {
        ProfileAddToGroupsSheet(
            appState = appState,
            targetName = displayTitle,
            groups = addableGroups,
            busy = addingToGroups,
            onDismiss = { if (!addingToGroups) showAddToGroups = false },
            onAdd = { selected ->
                if (addingToGroups) return@ProfileAddToGroupsSheet
                addingToGroups = true
                appState.launchMutation {
                    try {
                        val allAdded =
                            appState.inviteProfileToGroups(
                                targetRef = hex!!,
                                targetGroupIds = selected.map { it.group.groupIdHex },
                            )
                        if (allAdded) showAddToGroups = false
                    } finally {
                        addingToGroups = false
                    }
                }
            },
        )
        return
    }

    if (showContactEditorDialog && hex != null && !targetIsSelf) {
        ContactPrivateDetailsDialog(
            profileName = title,
            initialNickname = contactNickname.orEmpty(),
            initialNotes = contactNotes.orEmpty(),
            onDismiss = { showContactEditorDialog = false },
            onSave = { nickname, notes ->
                appState.setContactNickname(hex!!, nickname)
                appState.setContactNotes(hex!!, notes)
                showContactEditorDialog = false
            },
        )
    }

    fun openOrCreateProfileChat(retryGroupIdHex: String? = null) {
        if (creatingChat) return
        val progressHex = hex ?: return
        startChatError = null
        creatingChat = true
        appState.beginChatCreateOpenTiming()
        appState.launchMutation {
            try {
                when (
                    val result =
                        attemptStartProfileChat(
                            npub = npub,
                            progressHex = progressHex,
                            recipientName = displayTitle,
                            retryGroupIdHex = retryGroupIdHex,
                            createGroup = appState::createProfileChatGroup,
                            loadCreatedChatListItem = appState::loadCreatedChatListItem,
                            displayName = appState::displayName,
                            markCreateOpenStage = appState::markChatCreateOpenStage,
                            abandonCreateOpenTiming = appState::abandonChatCreateOpenTiming,
                        )
                ) {
                    is StartChatAttemptResult.Open -> onOpenGroup(result.item, true)
                    is StartChatAttemptResult.Failed -> startChatError = result.error
                }
            } finally {
                creatingChat = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!creatingChat) onDismiss() },
        sheetState = sheetState,
        containerColor = amoledSheetContainerColor(),
        properties = ModalBottomSheetProperties(securePolicy = securePolicy),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(contentScrollState)
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .clickable(
                            enabled = avatarImageAvailable,
                            onClickLabel = stringResource(R.string.profile_view_picture),
                            role = Role.Button,
                        ) { fullPictureOpen = true },
            ) {
                Avatar(
                    title = displayTitle,
                    seed = hex ?: npub,
                    size = 96.dp,
                    pictureUrl = pictureUrl,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(displayTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (nip05 != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(nip05, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (compactMemberSheet) {
                    val copyLabel = stringResource(R.string.copy)
                    Row(
                        modifier =
                            Modifier
                                .minimumInteractiveComponentSize()
                                .semantics { contentDescription = npub }
                                .clickable(
                                    onClickLabel = copyLabel,
                                    role = Role.Button,
                                ) {
                                    clipboard.setText(AnnotatedString(npub))
                                }.padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            IdentityFormatter.short(npub, prefix = 12, suffix = 8),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                        )
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXl)) {
                QuickActionButton(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    label = stringResource(R.string.message),
                    enabled = hex != null && !creatingChat,
                    inProgress = creatingChat,
                    // Opens the existing 1:1 DM, or starts a new one with this
                    // person when none exists yet. The create runs in the
                    // process-lifetime mutation scope (Main.immediate) so the MLS
                    // commit + Nostr publish finish regardless; we keep the sheet up
                    // with a spinner until the conversation is ready, then navigate
                    // straight in — no dismiss-into-a-blank-gap.
                    onClick = {
                        val existing = directMessageGroup
                        if (existing != null) {
                            onOpenGroup(existing, false)
                        } else {
                            openOrCreateProfileChat()
                        }
                    },
                )
                QuickActionButton(
                    icon = Icons.Default.Call,
                    label = stringResource(R.string.quick_action_audio),
                    onClick = {},
                    enabled = false,
                )
                QuickActionButton(
                    icon = Icons.Default.Videocam,
                    label = stringResource(R.string.quick_action_video),
                    onClick = {},
                    enabled = false,
                )
            }
            startChatError?.let { error ->
                StartChatErrorCard(
                    error = error,
                    onRetry = { openOrCreateProfileChat(error.retryGroupIdHex) },
                    onInvite = {
                        context.startActivity(
                            Intent.createChooser(
                                inviteShareIntent(inviteMessage),
                                inviteTitle,
                            ),
                        )
                    },
                    onCopy = { detail -> clipboard.setText(AnnotatedString(detail)) },
                )
            }
            if (!compactMemberSheet) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CopyableValueRow(
                        label = "npub",
                        value = npub,
                        clipboard = clipboard,
                    )
                    SectionCard(title = stringResource(R.string.about)) {
                        Text(
                            about ?: stringResource(R.string.profile_no_bio),
                            color =
                                if (about == null) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    Color.Unspecified
                                },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SectionCard(title = stringResource(R.string.profile_shared_groups)) {
                        if (sharedGroups.isEmpty()) {
                            Text(stringResource(R.string.profile_no_shared_groups), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            sharedGroups.forEachIndexed { index, group ->
                                ProfileSharedGroupRow(
                                    item = group,
                                    appState = appState,
                                    titleCopy = groupTitleCopy,
                                    onOpen = { onOpenGroup(group, false) },
                                )
                                if (index != sharedGroups.lastIndex) {
                                    AppDivider()
                                }
                            }
                        }
                    }
                }
            }
            if (hex == null) {
                Text(stringResource(R.string.couldnt_read_profile_code), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (hex != null && !targetIsSelf) {
                Column(Modifier.fillMaxWidth()) {
                    SettingsActionRow(
                        icon = Icons.Default.Edit,
                        title =
                            stringResource(
                                if (contactNickname == null) {
                                    R.string.profile_add_nickname_and_notes
                                } else {
                                    R.string.profile_nickname_and_notes
                                },
                            ),
                        value =
                            profileSheetContactPrivateDetailsRowValue(
                                contactNickname = contactNickname,
                                contactNotes = contactNotes,
                                addNicknameAndNotesLabel = stringResource(R.string.profile_add_nickname_and_notes),
                                notesLabel = stringResource(R.string.profile_contact_notes_hint),
                            ),
                        enabled = !creatingChat,
                        onClick = { showContactEditorDialog = true },
                    )
                    SettingsActionRow(
                        icon = Icons.Default.Group,
                        title = stringResource(R.string.profile_start_new_group_with, displayTitle),
                        enabled = !creatingChat,
                        onClick = {
                            hex?.let { accountIdHex ->
                                onStartGroup(
                                    RecipientSearch.Candidate(
                                        accountIdHex = accountIdHex,
                                        displayName = displayTitle,
                                        npub = npub,
                                    ),
                                )
                            }
                        },
                    )
                    SettingsActionRow(
                        icon = Icons.Default.Add,
                        title = stringResource(R.string.profile_add_to_another_group),
                        enabled = !creatingChat,
                        onClick = { showAddToGroups = true },
                    )
                }
            }
            // Group-admin moderation actions (issue #635). Only rendered when the
            // sheet was opened from inside a conversation (adminController != null)
            // AND the resolved user is a member of that group. The action set is
            // derived from profileSheetAdminActions, which reuses the members-list
            // scope rules (#444) verbatim, so an empty list (viewer not an admin
            // member, or the viewed user is self / not a member) renders nothing
            // and the sheet stays exactly as it is for every other entry point.
            if (adminController != null && hex != null) {
                ProfileSheetAdminActions(
                    controller = adminController,
                    appState = appState,
                    targetHex = hex!!,
                )
            }
        }
    }

    if (fullPictureOpen && pictureUrl != null && avatarImageAvailable) {
        AvatarFullScreenViewer(
            title = displayTitle,
            seed = hex ?: npub,
            pictureUrl = pictureUrl,
            onDismiss = { fullPictureOpen = false },
            securePolicy = securePolicy,
        )
    }
}

@Composable
private fun ContactPrivateDetailsDialog(
    profileName: String,
    initialNickname: String,
    initialNotes: String,
    onDismiss: () -> Unit,
    onSave: (nickname: String, notes: String) -> Unit,
) {
    var nickname by remember(initialNickname) { mutableStateOf(initialNickname) }
    var notes by remember(initialNotes) { mutableStateOf(initialNotes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_nickname_and_notes)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.profile_name_from_profile, profileName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text(stringResource(R.string.profile_contact_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.profile_contact_notes_hint)) },
                    singleLine = false,
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.profile_contact_editor_private_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(nickname, notes) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun ProfileAddToGroupsSheet(
    appState: WhiteNoiseAppState,
    targetName: String,
    groups: List<ChatListItem>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAdd: (List<ChatListItem>) -> Unit,
) {
    val groupTitleCopy = rememberGroupTitleCopy()
    val selected = remember { mutableStateListOf<String>() }
    var confirmSelection by remember { mutableStateOf<List<ChatListItem>?>(null) }
    var query by remember { mutableStateOf("") }
    val titledGroups =
        remember(groups, groupTitleCopy) {
            groups.map { it to chatListItemDisplayTitle(it, appState, groupTitleCopy) }
        }
    val filteredGroups =
        remember(titledGroups, query) {
            val needle = query.trim()
            if (needle.isEmpty()) {
                titledGroups
            } else {
                titledGroups.filter { (_, title) -> title.contains(needle, ignoreCase = true) }
            }
        }
    LaunchedEffect(groups) {
        val availableGroupIds = groups.mapTo(mutableSetOf()) { it.group.groupIdHex }
        selected.removeAll { it !in availableGroupIds }
    }
    val selectedGroups = groups.filter { selected.contains(it.group.groupIdHex) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = amoledSheetContainerColor(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.profile_add_to_groups_title, targetName),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(R.string.profile_add_to_groups_description, targetName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (groups.isEmpty()) {
                Text(
                    stringResource(R.string.profile_no_addable_groups),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                ) {
                    Text(stringResource(R.string.close))
                }
            } else {
                FlowSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.forward_search_chats),
                    modifier = Modifier.padding(horizontal = Dimens.spaceLg),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                ) {
                    if (filteredGroups.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.no_matches),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceLg),
                            )
                        }
                    }
                    items(
                        filteredGroups,
                        key = { (item, _) -> item.group.groupIdHex },
                    ) { (item, title) ->
                        val groupId = item.group.groupIdHex
                        val isSelected = selected.contains(groupId)
                        ContactRow(
                            title = title,
                            subtitle = stringResource(R.string.members_count, item.memberCount),
                            avatarSeed = item.group.groupIdHex,
                            avatarUrl = item.group.avatarUrl,
                            avatarImage = rememberEncryptedGroupAvatar(appState, item.group),
                            enabled = !busy,
                            onClick = {
                                if (isSelected) selected.remove(groupId) else selected.add(groupId)
                            },
                            trailing = { SelectionIndicator(selected = isSelected) },
                        )
                    }
                }
                Button(
                    onClick = { confirmSelection = selectedGroups },
                    enabled = selectedGroups.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.profile_add_to_groups_confirm_label))
                }
            }
        }
    }

    confirmSelection?.let { targets ->
        val groupNames =
            targets.joinToString { item ->
                chatListItemDisplayTitle(item, appState, groupTitleCopy)
            }
        ConfirmDialog(
            title = stringResource(R.string.profile_add_to_groups_confirm_title),
            message =
                stringResource(
                    R.string.profile_add_to_groups_confirm_message,
                    targetName,
                    groupNames,
                ),
            confirmLabel = stringResource(R.string.profile_add_to_groups_confirm_label),
            onConfirm = {
                confirmSelection = null
                onAdd(targets)
            },
            onDismiss = { confirmSelection = null },
        )
    }
}

/**
 * Group-admin moderation block shown inside the in-conversation profile sheet
 * (issue #635). Resolves the viewed user's member record in [controller] by a
 * case-insensitive hex match (consistent with the rest of the file), then asks
 * [profileSheetAdminActions] which actions are in scope — the same rules and
 * engine calls the Group Info members list uses (#444). Renders nothing when
 * scope fails (viewer not an admin member, viewed user is self or not a member
 * of this group), so the sheet is unchanged for those cases.
 *
 * Mutations run through [WhiteNoiseAppState.launchMutation] (process-lifetime
 * scope) — the same approach as GroupDetailsScreen's local runGroupMutation — so
 * the MLS commit + Nostr publish and the controller's own refreshMembers/toast
 * finish even if the sheet dismisses mid-flight. The locally pending action plus
 * the controller's [ConversationController.mutationInFlight] disable the buttons;
 * only the locally started action shows an in-row spinner.
 */
@Composable
private fun ProfileSheetAdminActions(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    targetHex: String,
) {
    val targetMember =
        remember(controller.members, targetHex) {
            controller.members.firstOrNull { it.memberIdHex.equals(targetHex, ignoreCase = true) }
        }
    val targetIsAdmin = targetMember?.let { controller.isAdmin(it) } == true
    val targetIsSelf =
        targetMember?.let {
            GroupProjector.isActiveAccountMember(it, appState.activeAccount?.accountIdHex)
        } == true
    val actions =
        profileSheetAdminActions(
            viewerIsMember = controller.isSelfMember,
            viewerIsAdmin = controller.isSelfAdmin,
            targetIsMember = targetMember != null,
            targetIsSelf = targetIsSelf,
            targetIsAdmin = targetIsAdmin,
        )
    if (targetMember == null || actions.isEmpty()) return

    // The action-scoped local state both disables immediately and identifies the
    // row that owns progress. mutationInFlight only disables for work started
    // elsewhere; it must not assign that work to a row in this sheet.
    var pendingAction by remember(targetHex) { mutableStateOf<GroupMemberMenuAction?>(null) }
    var confirmRemove by remember(targetHex) { mutableStateOf(false) }
    val busy = pendingAction != null || controller.mutationInFlight

    fun runMutation(
        action: GroupMemberMenuAction,
        mutation: suspend () -> Unit,
    ) {
        runProfileSheetAdminMutation(
            action = action,
            isBusy = { pendingAction != null || controller.mutationInFlight },
            onPendingActionChange = { pendingAction = it },
            clearLastMutationError = controller::clearLastMutationError,
            launchMutation = appState::launchMutation,
            mutation = mutation,
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        AppDivider()
        // Reuses the same "Admin" badge the members list shows so the action
        // labels (Grant/Revoke admin) read naturally. Uses an existing string
        // (R.string.admin) only — no new copy to translate.
        if (targetIsAdmin) {
            Text(
                stringResource(R.string.admin),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
            )
        }
        ProfileSheetAdminActionRows(
            actions = actions,
            pendingAction = pendingAction,
            busy = busy,
            onGrantAdmin = {
                runMutation(GroupMemberMenuAction.GrantAdmin) {
                    controller.setMemberAdmin(targetMember, admin = true)
                }
            },
            onRevokeAdmin = {
                runMutation(GroupMemberMenuAction.RevokeAdmin) {
                    controller.setMemberAdmin(targetMember, admin = false)
                }
            },
            onRemoveMember = { confirmRemove = true },
        )
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_remove_member_title),
            message =
                stringResource(
                    R.string.confirm_remove_member_message,
                    controller.memberDisplayName(targetMember),
                ),
            confirmLabel = stringResource(R.string.remove_member),
            onConfirm = {
                confirmRemove = false
                runMutation(GroupMemberMenuAction.RemoveMember) {
                    controller.removeMember(targetMember)
                }
            },
            onDismiss = { confirmRemove = false },
            destructive = true,
        )
    }
}

@Composable
private fun ProfileSharedGroupRow(
    item: ChatListItem,
    appState: WhiteNoiseAppState,
    titleCopy: GroupTitleCopy,
    onOpen: () -> Unit,
) {
    val title = chatListItemDisplayTitle(item, appState, titleCopy)
    val subtitle =
        when {
            item.group.archived -> stringResource(R.string.archived)
            item.memberCount == 1 -> stringResource(R.string.one_member)
            item.memberCount > 1 -> stringResource(R.string.members_count, item.memberCount)
            else -> stringResource(R.string.members)
        }
    ListItem(
        modifier =
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .amoledSurfaceBorder(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onOpen),
        leadingContent = {
            GroupAvatar(
                appState = appState,
                group = item.group,
                title = title,
                seed = item.group.groupIdHex,
                size = 40.dp,
            )
        },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
