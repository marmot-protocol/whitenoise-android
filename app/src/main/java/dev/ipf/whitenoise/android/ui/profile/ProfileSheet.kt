@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.ui.profile

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
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
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.Nip05Resolver
import dev.ipf.whitenoise.android.core.ProfileFieldValidation
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ProfileGroupPickerLoadState
import dev.ipf.whitenoise.android.state.ProfileGroupPickerState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentationNpubFromReference
import dev.ipf.whitenoise.android.state.rethrowIfCancellation
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.chats.newchat.DangerActionRow
import dev.ipf.whitenoise.android.ui.chats.newchat.FlowSearchField
import dev.ipf.whitenoise.android.ui.chats.newchat.QuickActionButton
import dev.ipf.whitenoise.android.ui.chats.newchat.SelectionIndicator
import dev.ipf.whitenoise.android.ui.chats.newchat.SettingsActionRow
import dev.ipf.whitenoise.android.ui.chats.newchat.StartChatAttemptResult
import dev.ipf.whitenoise.android.ui.chats.newchat.StartChatErrorCard
import dev.ipf.whitenoise.android.ui.chats.newchat.StartChatErrorUiState
import dev.ipf.whitenoise.android.ui.chats.newchat.attemptOpenOrStartProfileChat
import dev.ipf.whitenoise.android.ui.chats.newchat.inviteShareIntent
import dev.ipf.whitenoise.android.ui.chats.newchat.recipientNip05Verified
import dev.ipf.whitenoise.android.ui.common.AppDivider
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.CopyableValueRow
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.common.rememberEncryptedGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.group.GroupMemberMenuAction
import dev.ipf.whitenoise.android.ui.group.groupMemberMenuActions
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
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

internal const val PROFILE_SHEET_ADMIN_ACTIONS_TAG = "profile-sheet-admin-actions"

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

/**
 * Null means the follow status is unknown — an initial read failure must not be
 * reported as "not following", or the sheet offers the opposite mutation.
 */
internal suspend fun loadProfileFollowing(
    previous: Boolean?,
    load: suspend () -> Boolean?,
): Boolean? =
    runCatching { load() }
        .fold(
            onSuccess = { it },
            onFailure = { error ->
                rethrowIfCancellation(error)
                previous
            },
        )

internal data class ProfileFollowRowState(
    val enabled: Boolean,
    val inProgress: Boolean,
    val showsUnfollow: Boolean,
)

internal fun profileFollowRowState(
    following: Boolean?,
    loading: Boolean,
    busy: Boolean,
    creatingChat: Boolean,
): ProfileFollowRowState =
    ProfileFollowRowState(
        enabled = following != null && !loading && !busy && !creatingChat,
        // Unknown-after-failure is a settled state, not a pending one — spinning
        // on it would be indistinguishable from a read that never returns.
        inProgress = loading || busy,
        showsUnfollow = following == true,
    )

internal data class ProfileSheetMetadata(
    val displayName: String?,
    val pictureUrl: String?,
    val bannerUrl: String?,
    val about: String?,
    val nip05: String?,
    val lightningAddress: String?,
)

private inline fun firstSanitizedProfileField(
    vararg candidates: String?,
    sanitize: (String?) -> String?,
): String? = candidates.firstNotNullOfOrNull(sanitize)

private fun sanitizedProfileNip05(raw: String?): String? =
    raw
        ?.trim()
        ?.takeIf { it.isNotEmpty() && ProfileFieldValidation.isAcceptableNip05(it) }

private fun sanitizedProfileLightningAddress(raw: String?): String? =
    raw
        ?.trim()
        ?.takeIf { it.isNotEmpty() && ProfileFieldValidation.isAcceptableLud16(it) }

/**
 * Keeps locally materialized fields authoritative without letting a sparse or
 * invalid cached record hide richer metadata carried by a discovery result.
 */
internal fun resolveProfileSheetMetadata(
    cached: UserProfileMetadataFfi?,
    discovered: UserProfileMetadataFfi?,
    cachedAvatarUrl: String?,
): ProfileSheetMetadata =
    ProfileSheetMetadata(
        displayName =
            firstSanitizedProfileField(
                cached?.displayName,
                discovered?.displayName,
                cached?.name,
                discovered?.name,
                sanitize = ProfileSanitizer::displayName,
            ),
        pictureUrl =
            firstSanitizedProfileField(
                cachedAvatarUrl,
                cached?.picture,
                discovered?.picture,
                sanitize = ProfileSanitizer::imageUrl,
            ),
        bannerUrl =
            firstSanitizedProfileField(
                cached?.banner,
                discovered?.banner,
                sanitize = ProfileSanitizer::imageUrl,
            ),
        about =
            firstSanitizedProfileField(
                cached?.about,
                discovered?.about,
                sanitize = ProfileSanitizer::about,
            ),
        nip05 =
            firstSanitizedProfileField(
                cached?.nip05,
                discovered?.nip05,
                sanitize = ::sanitizedProfileNip05,
            ),
        lightningAddress =
            firstSanitizedProfileField(
                cached?.lud16,
                discovered?.lud16,
                sanitize = ::sanitizedProfileLightningAddress,
            ),
    )

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
    Column(Modifier.fillMaxWidth().testTag(PROFILE_SHEET_ADMIN_ACTIONS_TAG)) {
        AppDivider()
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

private enum class ProfileSheetPage {
    PROFILE,
    ADD_TO_GROUPS,
    MAKE_ADMIN,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileSheet(
    appState: WhiteNoiseAppState,
    npub: String,
    // (chat, justCreated): justCreated is true only on the path that just
    // created a brand-new DM with this person (issue #321), so the conversation
    // opens with the composer focused + keyboard up. Opening an existing DM
    // passes false.
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
    // Seed the identity synchronously so the first composed frame already has
    // the content it will settle on. ModalBottomSheet animates toward its
    // measured height, so rows that resolve a frame later — about, NIP-05,
    // avatar — would move that target mid-animation and read as
    // a stutter on open (#1432). This is the same pure FFI decode (no storage
    // read) the message bubble already runs on its per-message render path, so
    // it is cheap enough for composition; the suspend resolver below still
    // covers references it can't normalize locally.
    var hex by remember(npub) { mutableStateOf(appState.profileReferenceAccountIdHex(npub)) }
    var fullPictureOpen by remember(npub) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val contentScrollState = rememberScrollState()
    val compactMemberSheet = adminController != null

    LaunchedEffect(npub) {
        // Only pay the IO hop when the local decode couldn't normalize the
        // reference; otherwise reassigning would rebuild identical state.
        val resolved = hex ?: appState.accountIdHex(npub)?.also { hex = it }
        if (resolved != null) appState.refreshProfile(resolved)
    }

    val cachedProfile = hex?.let { appState.userProfile(it) }
    val profile =
        resolveProfileSheetMetadata(
            cached = cachedProfile,
            discovered = appState.pendingProfileMetadata,
            cachedAvatarUrl = hex?.let { appState.avatarUrl(it) },
        )
    val presentationNpub =
        presentationNpubFromReference(
            reference = npub,
            resolvedAccountIdHex = hex,
            npubForDisplay = appState::npubForDisplay,
        )
    val presentationNpubShort =
        presentationNpub.takeIf { it.isNotBlank() }?.let {
            IdentityFormatter.short(it, prefix = 12, suffix = 8)
        }
    val title =
        profile.displayName
            ?: hex?.let { appState.networkDisplayName(it) }
            ?: presentationNpubShort.orEmpty()
    val contactNickname = hex?.let { appState.contactNickname(it) }
    val contactNotes = hex?.let { appState.contactNotes(it) }
    // #1226: the header + identity surfaces show the nickname when one is set;
    // the "name from profile" section and the nickname dialog deliberately keep
    // the real profile name (`title`) so the user sees what they're renaming.
    val displayTitle = contactNickname ?: title
    val pictureUrl = profile.pictureUrl
    val bannerUrl = profile.bannerUrl
    val avatarImageAvailable = rememberAvatarImageAvailable(pictureUrl)
    val about = profile.about
    val nip05 = profile.nip05
    var nip05ResolvedHex by remember(nip05) { mutableStateOf<String?>(null) }
    LaunchedEffect(nip05) {
        nip05ResolvedHex = nip05?.let { Nip05Resolver.resolve(it) }
    }
    val nip05Verified = recipientNip05Verified(nip05, nip05ResolvedHex, hex)
    val lightningAddress = profile.lightningAddress
    val activeAccountHex = appState.activeAccount?.accountIdHex
    // True while a brand-new DM is being created+published, so the Message
    // button shows progress and we don't dismiss into a blank gap before the
    // conversation opens.
    var creatingChat by remember(npub) { mutableStateOf(false) }
    var startChatError by remember(npub) { mutableStateOf<StartChatErrorUiState?>(null) }
    var page by remember(npub) { mutableStateOf(ProfileSheetPage.PROFILE) }
    var showContactEditorDialog by remember(npub) { mutableStateOf(false) }
    var addingToGroups by remember(npub) { mutableStateOf(false) }
    var promotingAdmin by remember(npub) { mutableStateOf(false) }
    // UI guard covers both profile actions, including "Start new group". The
    // state-layer addable-groups helper still rejects self as a defensive check
    // for the add-to-existing-groups path.
    val targetIsSelf = hex?.let { activeAccountHex?.equals(it, ignoreCase = true) == true } == true
    // Keyed by account as well as profile: the failure path deliberately keeps the
    // previous value, which would otherwise carry one account's relationship into
    // the next and offer the wrong follow/unfollow.
    var following by remember(npub, appState.activeAccountRef) { mutableStateOf<Boolean?>(null) }
    var followLoading by remember(npub, appState.activeAccountRef) { mutableStateOf(false) }
    var followBusy by remember(npub, appState.activeAccountRef) { mutableStateOf(false) }
    LaunchedEffect(hex, appState.activeAccountRef, appState.relationshipRevision) {
        val target = hex?.takeUnless { targetIsSelf }
        if (target == null) {
            following = null
            followLoading = false
            return@LaunchedEffect
        }
        followLoading = true
        try {
            following = loadProfileFollowing(following) { appState.isFollowingProfile(target) }
        } finally {
            followLoading = false
        }
    }
    val followRow =
        profileFollowRowState(
            following = following,
            loading = followLoading,
            busy = followBusy,
            creatingChat = creatingChat,
        )
    val inviteTitle = stringResource(R.string.invite_to_white_noise)
    val inviteMessage = stringResource(R.string.invite_message)
    val groupPickerRevision = appState.profileGroupPickerRevision
    val addableGroupsState =
        remember(hex, appState.chatListItems, groupPickerRevision) {
            hex?.let(appState::profileAddableGroupsState) ?: ProfileGroupPickerState.empty()
        }
    val promotableGroupsState =
        remember(hex, appState.chatListItems, groupPickerRevision) {
            hex?.let(appState::profilePromotableGroupsState) ?: ProfileGroupPickerState.empty()
        }
    LaunchedEffect(page, addableGroupsState.pendingGroupIds, promotableGroupsState.pendingGroupIds) {
        val pendingGroupIds =
            when (page) {
                ProfileSheetPage.ADD_TO_GROUPS -> addableGroupsState.pendingGroupIds
                ProfileSheetPage.MAKE_ADMIN -> promotableGroupsState.pendingGroupIds
                ProfileSheetPage.PROFILE -> emptySet()
            }
        if (pendingGroupIds.isNotEmpty()) {
            appState.requestProfileGroupMembers(pendingGroupIds)
        }
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
                        attemptOpenOrStartProfileChat(
                            npub = npub,
                            progressHex = progressHex,
                            recipientName = displayTitle,
                            retryGroupIdHex = retryGroupIdHex,
                            resolveDirectChat = { appState.resolveExistingDirectChat(npub) },
                            createGroup = appState::createProfileChatGroup,
                            loadCreatedChatListItem = appState::loadCreatedChatListItem,
                            displayName = appState::displayName,
                            markCreateOpenStage = appState::markChatCreateOpenStage,
                            abandonCreateOpenTiming = appState::abandonChatCreateOpenTiming,
                        )
                ) {
                    is StartChatAttemptResult.Open -> onOpenGroup(result.item, result.newlyCreated)
                    is StartChatAttemptResult.Failed -> startChatError = result.error
                }
            } finally {
                creatingChat = false
            }
        }
    }

    fun addProfileToGroups(selected: List<ChatListItem>) {
        val targetHex = hex ?: return
        if (addingToGroups) return
        addingToGroups = true
        appState.launchMutation {
            try {
                val allAdded =
                    appState.inviteProfileToGroups(
                        targetRef = targetHex,
                        targetGroupIds = selected.map { it.group.groupIdHex },
                    )
                if (allAdded) page = ProfileSheetPage.PROFILE
            } finally {
                addingToGroups = false
            }
        }
    }

    fun makeProfileAdmin(group: ChatListItem) {
        val targetHex = hex ?: return
        if (promotingAdmin) return
        promotingAdmin = true
        appState.launchMutation {
            try {
                val promoted = appState.promoteProfileInGroup(targetHex, group.group.groupIdHex)
                if (promoted) page = ProfileSheetPage.PROFILE
            } finally {
                promotingAdmin = false
            }
        }
    }

    val profileContent: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(contentScrollState)
                .padding(vertical = 24.dp)
                .testTag(PROFILE_SHEET_CONTENT_TAG),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProfileSheetHeaderImages(
                bannerUrl = bannerUrl,
                title = displayTitle,
                seed = hex ?: npub,
                pictureUrl = pictureUrl,
                avatarClickable = avatarImageAvailable,
                onAvatarClick = { fullPictureOpen = true },
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(displayTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (contactNickname != null && title != displayTitle) {
                    Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (nip05 != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (nip05Verified) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.profile_nip05_verified),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(nip05, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (compactMemberSheet && presentationNpub.isNotBlank()) {
                    val copyLabel = stringResource(R.string.copy)
                    Row(
                        modifier =
                            Modifier
                                .minimumInteractiveComponentSize()
                                .semantics { contentDescription = presentationNpub }
                                .clickable(
                                    onClickLabel = copyLabel,
                                    role = Role.Button,
                                ) {
                                    clipboard.setText(AnnotatedString(presentationNpub))
                                }.padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            presentationNpubShort.orEmpty(),
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
            Row(
                modifier = Modifier.testTag(PROFILE_QUICK_ACTIONS_TAG),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXl),
            ) {
                if (hex != null && !targetIsSelf) {
                    QuickActionButton(
                        icon = if (followRow.showsUnfollow) Icons.Default.PersonRemove else Icons.Default.PersonAdd,
                        label =
                            stringResource(
                                if (followRow.showsUnfollow) R.string.profile_unfollow else R.string.profile_follow,
                            ),
                        modifier = Modifier.testTag(PROFILE_FOLLOW_ACTION_TAG),
                        enabled = followRow.enabled,
                        inProgress = followRow.inProgress,
                        onClick = {
                            if (followBusy) return@QuickActionButton
                            val desired = following != true
                            followBusy = true
                            appState.launchMutation {
                                try {
                                    runCatching { appState.setProfileFollowing(hex!!, desired) }
                                        .onSuccess { following = desired }
                                        .onFailure { error ->
                                            rethrowIfCancellation(error)
                                            appState.present(R.string.profile_follow_failed)
                                        }
                                } finally {
                                    followBusy = false
                                }
                            }
                        },
                    )
                }
                QuickActionButton(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    label = stringResource(R.string.message),
                    modifier = Modifier.testTag(PROFILE_MESSAGE_ACTION_TAG),
                    enabled = hex != null && !creatingChat,
                    inProgress = creatingChat,
                    // Opens the existing 1:1 DM, or starts a new one with this
                    // person when none exists yet. The create runs in the
                    // process-lifetime mutation scope (Main.immediate) so the MLS
                    // commit + Nostr publish finish regardless; we keep the sheet up
                    // with a spinner until the conversation is ready, then navigate
                    // straight in — no dismiss-into-a-blank-gap.
                    onClick = { openOrCreateProfileChat() },
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
                    if (presentationNpub.isNotBlank()) {
                        CopyableValueRow(
                            label = "npub",
                            value = presentationNpub,
                            clipboard = clipboard,
                            displayValue = presentationNpubShort.orEmpty(),
                        )
                    }
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
                    if (lightningAddress != null) {
                        CopyableValueRow(
                            label = stringResource(R.string.lightning),
                            value = lightningAddress,
                            clipboard = clipboard,
                        )
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
                        onClick = { page = ProfileSheetPage.ADD_TO_GROUPS },
                    )
                    if (
                        adminController == null &&
                        (
                            promotableGroupsState.groups.isNotEmpty() ||
                                promotableGroupsState.loadState != ProfileGroupPickerLoadState.READY
                        )
                    ) {
                        SettingsActionRow(
                            icon = Icons.Default.Shield,
                            title = stringResource(R.string.make_admin),
                            enabled = !creatingChat,
                            onClick = { page = ProfileSheetPage.MAKE_ADMIN },
                        )
                    }
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

    ModalBottomSheet(
        // Back while the picker is visible is intercepted within this host.
        // Scrim/swipe dismiss the whole flow instead of recreating the profile
        // as a second modal window (#1868).
        onDismissRequest = { if (!creatingChat && !addingToGroups && !promotingAdmin) onDismiss() },
        sheetState = sheetState,
        containerColor = amoledSheetContainerColor(),
        properties = ModalBottomSheetProperties(securePolicy = securePolicy),
    ) {
        // Register against the modal dialog's dispatcher, not the activity's;
        // otherwise the dialog consumes Back before this nested route sees it.
        BackHandler(enabled = page != ProfileSheetPage.PROFILE) {
            if (!addingToGroups && !promotingAdmin) page = ProfileSheetPage.PROFILE
        }
        AnimatedContent(
            targetState = if (hex == null) ProfileSheetPage.PROFILE else page,
            transitionSpec = {
                val transition =
                    if (targetState != ProfileSheetPage.PROFILE) {
                        (slideInHorizontally { it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 4 } + fadeOut())
                    } else {
                        (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { it / 4 } + fadeOut())
                    }
                transition.using(SizeTransform(clip = false))
            },
            label = "profile-group-picker",
            modifier = Modifier.fillMaxWidth().testTag(PROFILE_SHEET_HOST_TAG),
        ) { targetPage ->
            when (targetPage) {
                ProfileSheetPage.ADD_TO_GROUPS ->
                    ProfileAddToGroupsContent(
                        appState = appState,
                        targetName = displayTitle,
                        state = addableGroupsState,
                        busy = addingToGroups,
                        onClose = { if (!addingToGroups) page = ProfileSheetPage.PROFILE },
                        onRetry = {
                            appState.requestProfileGroupMembers(
                                addableGroupsState.pendingGroupIds,
                                retry = true,
                            )
                        },
                        onAdd = ::addProfileToGroups,
                    )
                ProfileSheetPage.MAKE_ADMIN ->
                    ProfileMakeAdminContent(
                        appState = appState,
                        targetName = displayTitle,
                        state = promotableGroupsState,
                        busy = promotingAdmin,
                        onClose = { if (!promotingAdmin) page = ProfileSheetPage.PROFILE },
                        onRetry = {
                            appState.requestProfileGroupMembers(
                                promotableGroupsState.pendingGroupIds,
                                retry = true,
                            )
                        },
                        onPromote = ::makeProfileAdmin,
                    )
                ProfileSheetPage.PROFILE -> profileContent()
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

/**
 * The sheet header mirrors the own-profile header: the ringed avatar straddles
 * the banner's bottom edge instead of sitting in its own row below it.
 *
 * A profile with no banner — and one whose banner load fails, which
 * [ProfileBannerLoadState] reports the same way — keeps the plain, unoverlapped
 * avatar. Reserving the overlap for a banner that will never render would leave
 * a hole, and inventing a placeholder banner would make every bannerless profile
 * taller than it is today.
 */
@Composable
@Suppress("FunctionNaming")
private fun ProfileSheetHeaderImages(
    bannerUrl: String?,
    title: String,
    seed: String,
    pictureUrl: String?,
    avatarClickable: Boolean,
    onAvatarClick: () -> Unit,
) {
    val banner = bannerUrl?.let { rememberProfileBannerLoadState(it) }
    val viewPictureLabel = stringResource(R.string.profile_view_picture)
    val avatar: @Composable () -> Unit = {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp,
            modifier =
                Modifier
                    .clip(CircleShape)
                    .clickable(
                        enabled = avatarClickable,
                        onClickLabel = viewPictureLabel,
                        role = Role.Button,
                        onClick = onAvatarClick,
                    ),
        ) {
            Box(Modifier.padding(PROFILE_AVATAR_RING)) {
                Avatar(title = title, seed = seed, size = PROFILE_AVATAR_SIZE, pictureUrl = pictureUrl)
            }
        }
    }
    if (banner?.visible != true) {
        avatar()
        return
    }
    Box(
        modifier = Modifier.fillMaxWidth().padding(bottom = PROFILE_AVATAR_OVERLAP),
        contentAlignment = Alignment.BottomCenter,
    ) {
        ProfileBannerImage(banner)
        // The offset overflows the banner; the bottom padding above reserves it.
        Box(Modifier.offset(y = PROFILE_AVATAR_OVERLAP)) { avatar() }
    }
}

private val PROFILE_AVATAR_SIZE = 96.dp
private val PROFILE_AVATAR_RING = 4.dp

/** Half the ringed avatar, so it sits centred on the banner's bottom edge. */
private val PROFILE_AVATAR_OVERLAP = 52.dp

/**
 * [AvatarImageLoader.load] answers null both while it is working and when the
 * fetch failed, so completion has to be tracked separately: a banner that will
 * never arrive is dropped instead of spinning forever.
 */
internal data class ProfileBannerLoadState(
    val image: ImageBitmap?,
    val settled: Boolean,
) {
    /** False once a load has failed, so callers collapse as if there were no banner. */
    val visible: Boolean get() = image != null || !settled
}

@Composable
internal fun rememberProfileBannerLoadState(
    bannerUrl: String,
    peek: (String) -> ImageBitmap? = AvatarImageLoader::peek,
    load: suspend (String) -> ImageBitmap? = AvatarImageLoader::load,
): ProfileBannerLoadState {
    var image by remember(bannerUrl) { mutableStateOf(peek(bannerUrl)) }
    var settled by remember(bannerUrl) { mutableStateOf(image != null) }
    LaunchedEffect(bannerUrl) {
        if (image == null) {
            image = load(bannerUrl)
            settled = true
        }
    }
    return ProfileBannerLoadState(image = image, settled = settled)
}

@Composable
@Suppress("FunctionNaming")
internal fun ProfileBannerImage(
    bannerUrl: String,
    peek: (String) -> ImageBitmap? = AvatarImageLoader::peek,
    load: suspend (String) -> ImageBitmap? = AvatarImageLoader::load,
) {
    ProfileBannerImage(rememberProfileBannerLoadState(bannerUrl, peek, load))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@Suppress("FunctionNaming")
internal fun ProfileBannerImage(state: ProfileBannerLoadState) {
    val bitmap = state.image
    if (!state.visible) return
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.spaceLg)
                .aspectRatio(2f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag(PROFILE_BANNER_TAG),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LoadingIndicator(modifier = Modifier.size(20.dp).testTag(PROFILE_BANNER_LOADING_TAG))
        }
    }
}

internal const val PROFILE_BANNER_TAG = "profile-banner"
internal const val PROFILE_BANNER_LOADING_TAG = "profile-banner-loading"
internal const val PROFILE_FOLLOW_ACTION_TAG = "profile-follow-action"
internal const val PROFILE_MESSAGE_ACTION_TAG = "profile-message-action"
internal const val PROFILE_QUICK_ACTIONS_TAG = "profile-quick-actions"
internal const val PROFILE_SHEET_HOST_TAG = "profile-sheet-host"
internal const val PROFILE_SHEET_CONTENT_TAG = "profile-sheet-content"
internal const val PROFILE_ADD_TO_GROUPS_CONTENT_TAG = "profile-add-to-groups-content"
internal const val PROFILE_MAKE_ADMIN_CONTENT_TAG = "profile-make-admin-content"

@Composable
internal fun ContactPrivateDetailsDialog(
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

internal fun stableAdminActionTargetIsAdmin(
    authoritativeAdmin: Boolean,
    pendingAction: GroupMemberMenuAction?,
): Boolean =
    when (pendingAction) {
        GroupMemberMenuAction.GrantAdmin -> false
        GroupMemberMenuAction.RevokeAdmin -> true
        else -> authoritativeAdmin
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun ProfileAddToGroupsSheet(
    appState: WhiteNoiseAppState,
    targetName: String,
    state: ProfileGroupPickerState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onAdd: (List<ChatListItem>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = amoledSheetContainerColor(),
    ) {
        ProfileAddToGroupsContent(
            appState = appState,
            targetName = targetName,
            state = state,
            busy = busy,
            onClose = onDismiss,
            onRetry = onRetry,
            onAdd = onAdd,
        )
    }
}

@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun ProfileAddToGroupsContent(
    appState: WhiteNoiseAppState,
    targetName: String,
    state: ProfileGroupPickerState,
    busy: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onAdd: (List<ChatListItem>) -> Unit,
) {
    val groups = state.groups
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
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .testTag(PROFILE_ADD_TO_GROUPS_CONTENT_TAG),
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
        if (groups.isEmpty() && state.loadState == ProfileGroupPickerLoadState.READY) {
            Text(
                stringResource(R.string.profile_no_addable_groups),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Text(stringResource(R.string.close))
            }
        } else if (groups.isEmpty()) {
            ProfileGroupPickerPendingState(state.loadState, onRetry)
        } else {
            if (state.loadState != ProfileGroupPickerLoadState.READY) {
                ProfileGroupPickerPendingState(state.loadState, onRetry)
            }
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

@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun ProfileMakeAdminContent(
    appState: WhiteNoiseAppState,
    targetName: String,
    state: ProfileGroupPickerState,
    busy: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onPromote: (ChatListItem) -> Unit,
) {
    val groups = state.groups
    val groupTitleCopy = rememberGroupTitleCopy()
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
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
        if (groups.none { it.group.groupIdHex == selectedGroupId }) selectedGroupId = null
    }
    val selectedGroup = groups.firstOrNull { it.group.groupIdHex == selectedGroupId }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .testTag(PROFILE_MAKE_ADMIN_CONTENT_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.profile_make_admin_title, targetName),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = Dimens.spaceLg),
        )
        Text(
            stringResource(R.string.profile_make_admin_description, targetName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.spaceLg),
        )
        if (groups.isEmpty() && state.loadState == ProfileGroupPickerLoadState.READY) {
            Text(
                stringResource(R.string.profile_no_promotable_groups),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
            ) {
                Text(stringResource(R.string.close))
            }
        } else if (groups.isEmpty()) {
            ProfileGroupPickerPendingState(state.loadState, onRetry)
        } else {
            if (state.loadState != ProfileGroupPickerLoadState.READY) {
                ProfileGroupPickerPendingState(state.loadState, onRetry)
            }
            FlowSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.forward_search_chats),
                modifier = Modifier.padding(horizontal = Dimens.spaceLg),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
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
                    val selected = item.group.groupIdHex == selectedGroupId
                    ContactRow(
                        title = title,
                        subtitle = stringResource(R.string.members_count, item.memberCount),
                        avatarSeed = item.group.groupIdHex,
                        avatarUrl = item.group.avatarUrl,
                        avatarImage = rememberEncryptedGroupAvatar(appState, item.group),
                        enabled = !busy,
                        onClick = { selectedGroupId = item.group.groupIdHex },
                        trailing = { SelectionIndicator(selected = selected) },
                    )
                }
            }
            Button(
                onClick = { selectedGroup?.let(onPromote) },
                enabled = selectedGroup != null && !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Shield, contentDescription = null)
                }
                Spacer(Modifier.width(Dimens.spaceSm))
                Text(stringResource(R.string.make_admin))
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ProfileGroupPickerPendingState(
    loadState: ProfileGroupPickerLoadState,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loadState == ProfileGroupPickerLoadState.LOADING) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        Text(
            stringResource(
                if (loadState == ProfileGroupPickerLoadState.FAILED) {
                    R.string.profile_addable_groups_failed
                } else {
                    R.string.profile_addable_groups_loading
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (loadState == ProfileGroupPickerLoadState.FAILED) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
        }
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
    // Keep the initiated action stable until its coroutine completes. Detailed
    // MDK state can land one frame before the local pending flag clears.
    var pendingAction by remember(targetHex) { mutableStateOf<GroupMemberMenuAction?>(null) }
    var confirmRemove by remember(targetHex) { mutableStateOf(false) }
    val targetMember =
        remember(controller.presentedMembers, targetHex) {
            controller.presentedMembers.firstOrNull { it.memberIdHex.equals(targetHex, ignoreCase = true) }
        }
    // Keep the action label stable while the optimistic badge changes. The row
    // remains disabled until MDK reconciles, then switches to the inverse action.
    val targetWasAuthoritativelyAdmin = targetMember?.let { controller.isAuthoritativeAdmin(it) } == true
    val actionTargetIsAdmin = stableAdminActionTargetIsAdmin(targetWasAuthoritativelyAdmin, pendingAction)
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
            targetIsAdmin = actionTargetIsAdmin,
        )
    if (targetMember == null || actions.isEmpty()) return

    // The action-scoped local state both disables immediately and identifies the
    // row that owns progress. mutationInFlight only disables for work started
    // elsewhere; it must not assign that work to a row in this sheet.
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
