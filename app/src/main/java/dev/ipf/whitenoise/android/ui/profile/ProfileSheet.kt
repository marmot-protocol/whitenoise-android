@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.Nip05Resolver
import dev.ipf.whitenoise.android.core.ProfileFieldValidation
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.diagnostics.DmCreationDiagnostics
import dev.ipf.whitenoise.android.diagnostics.DmCreationInteraction
import dev.ipf.whitenoise.android.share.launchInviteShare
import dev.ipf.whitenoise.android.share.presentOutboundShareFailure
import dev.ipf.whitenoise.android.state.BlockOutcome
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MemberMutePreferences
import dev.ipf.whitenoise.android.state.ProfileGroupPickerLoadState
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.createProfileChatGroup
import dev.ipf.whitenoise.android.state.forwardTargetsRevision
import dev.ipf.whitenoise.android.state.inviteProfileToGroups
import dev.ipf.whitenoise.android.state.isUserBlocked
import dev.ipf.whitenoise.android.state.loadTeamReporting
import dev.ipf.whitenoise.android.state.memberMutePreferences
import dev.ipf.whitenoise.android.state.presentationNpubFromReference
import dev.ipf.whitenoise.android.state.profileAddableGroupsState
import dev.ipf.whitenoise.android.state.profileGroupPickerRevision
import dev.ipf.whitenoise.android.state.profileNotificationOverrides
import dev.ipf.whitenoise.android.state.profilePromotableGroupsState
import dev.ipf.whitenoise.android.state.promoteProfileInGroup
import dev.ipf.whitenoise.android.state.rethrowIfCancellation
import dev.ipf.whitenoise.android.state.setMemberMutedInGroup
import dev.ipf.whitenoise.android.state.setUserBlocked
import dev.ipf.whitenoise.android.state.teamReportingAvailable
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.account.startingAccount
import dev.ipf.whitenoise.android.ui.chats.newchat.StartChatAttemptResult
import dev.ipf.whitenoise.android.ui.chats.newchat.StartChatErrorCard
import dev.ipf.whitenoise.android.ui.chats.newchat.StartChatErrorUiState
import dev.ipf.whitenoise.android.ui.chats.newchat.attemptOpenOrStartProfileChat
import dev.ipf.whitenoise.android.ui.chats.newchat.recipientNip05Verified
import dev.ipf.whitenoise.android.ui.chats.newchat.withDmCreationOwner
import dev.ipf.whitenoise.android.ui.common.LocalWhiteNoiseTextFieldContainerColor
import dev.ipf.whitenoise.android.ui.common.PreparedGroupAvatarContent
import dev.ipf.whitenoise.android.ui.common.VISIBLE_GROUP_AVATAR_LIMIT
import dev.ipf.whitenoise.android.ui.common.abbreviatesPublicKey
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.common.rememberChatListGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.conversation.MeasuredPlaybackDialog
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationMotionEnabled
import dev.ipf.whitenoise.android.ui.group.GroupMemberMenuAction
import dev.ipf.whitenoise.android.ui.group.groupMemberMenuActions
import dev.ipf.whitenoise.android.ui.settings.SettingsContour
import dev.ipf.whitenoise.android.ui.settings.SettingsRouteMotion
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.theme.Dimens
import kotlinx.coroutines.delay
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
                sanitize = ProfileSanitizer::protocolImageUrl,
            ),
        bannerUrl =
            firstSanitizedProfileField(
                cached?.banner,
                discovered?.banner,
                sanitize = ProfileSanitizer::protocolImageUrl,
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

internal enum class ProfileSheetPage {
    PROFILE,
    GROUPS_IN_COMMON,
    NOTIFICATIONS,
    ADD_TO_GROUPS,
    MAKE_ADMIN,
}

/** The Add picker can be entered from either the profile root or its shared-groups page. */
internal fun profileSheetPageDepth(
    page: ProfileSheetPage,
    pickerParent: ProfileSheetPage,
): Int =
    when (page) {
        ProfileSheetPage.PROFILE -> 0
        ProfileSheetPage.ADD_TO_GROUPS -> if (pickerParent == ProfileSheetPage.GROUPS_IN_COMMON) 2 else 1
        else -> 1
    }

/** User Profile page for an npub: identity, about, shared groups and the message action. */
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
    openGroupsInCommon: Boolean = false,
    onStartGroupMeasured: (
        (
            RecipientSearch.Candidate,
            androidx.compose.ui.geometry.Rect?,
            SettingsContour?,
        ) -> Unit
    )? = null,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val currentDismiss by rememberUpdatedState(onDismiss)
    val accountAtOpen = remember(appState, npub) { appState.activeAccountRef }
    val runtimeAtOpen = remember(appState, npub) { appState.runtimeGeneration }
    val owner =
        remember(appState, npub) {
            PersonProfileOwner {
                appState.activeAccountRef == accountAtOpen &&
                    appState.runtimeGeneration == runtimeAtOpen &&
                    !appState.signOutInProgress &&
                    !appState.wipeInProgress &&
                    appState.retainedAccountReactivationRef == null
            }
        }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    LaunchedEffect(
        owner,
        appState.activeAccountRef,
        appState.runtimeGeneration,
        appState.signOutInProgress,
        appState.wipeInProgress,
        appState.retainedAccountReactivationRef,
    ) {
        if (!owner.canPresent()) currentDismiss()
    }
    if (!owner.canPresent() || appState.appLock.screenVisible) return
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
    val contentScrollState = rememberScrollState()
    var fullBannerOpen by remember(npub) { mutableStateOf(false) }
    var copied by remember(npub) { mutableStateOf(false) }
    var blocked by remember(npub) { mutableStateOf<Boolean?>(null) }
    var blockBusy by remember(npub) { mutableStateOf(false) }
    var feedback by remember(npub) { mutableStateOf<ToastMessage?>(null) }
    val teamReport = remember(npub) { ProfileTeamReportState() }
    LaunchedEffect(copied) {
        if (copied) {
            delay(PROFILE_COPY_FEEDBACK_MILLIS)
            copied = false
        }
    }

    LaunchedEffect(appState.runtimeGeneration) { appState.loadTeamReporting() }
    LaunchedEffect(hex, appState.runtimeMirrors.blocks.revision) {
        val target = hex
        val account = appState.activeAccountRef
        blocked = if (target != null && account != null) appState.isUserBlocked(account, target) else null
    }
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
    // The shortened key names a nameless person only as a fallback, so record where the title came from (#3565).
    // A name source that itself answers with this key's abbreviation is the same fallback, not a person's name.
    val personName =
        (profile.displayName ?: hex?.let { appState.networkDisplayNameOrNull(it) })
            ?.takeUnless { abbreviatesPublicKey(it, presentationNpub) }
    val title = personName ?: presentationNpubShort.orEmpty()
    val titleIsPublicKey = personName == null && presentationNpubShort != null
    val contactNickname = hex?.let { appState.contactNickname(it) }
    val contactNotes = hex?.let { appState.contactNotes(it) }
    // #1226: the header + identity surfaces show the nickname when one is set;
    // the "name from profile" section and the nickname dialog deliberately keep
    // the real profile name (`title`) so the user sees what they're renaming.
    val displayTitle = contactNickname ?: title
    val pictureUrl = hex?.let { appState.contactAvatarSource(it) } ?: profile.pictureUrl
    val bannerUrl = profile.bannerUrl
    val avatarImageAvailable = rememberAvatarImageAvailable(pictureUrl, AvatarImageLoader.publicProfiles())
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
    var page by remember(npub) {
        mutableStateOf(if (openGroupsInCommon) ProfileSheetPage.GROUPS_IN_COMMON else ProfileSheetPage.PROFILE)
    }
    var notificationEntry by remember(npub) { mutableStateOf(Any()) }
    var pickerParent by remember(npub) { mutableStateOf(ProfileSheetPage.PROFILE) }
    var showContactEditorDialog by remember(npub) { mutableStateOf(false) }
    var contactEditorEntry by remember(npub) { mutableStateOf(Any()) }
    var addingToGroups by remember(npub) { mutableStateOf(false) }
    var promotingAdmin by remember(npub) { mutableStateOf(false) }
    // UI guard covers both profile actions, including "Start new group". The
    // state-layer addable-groups helper still rejects self as a defensive check
    // for the add-to-existing-groups path.
    val targetIsSelf = hex?.let { activeAccountHex?.equals(it, ignoreCase = true) == true } == true
    // Per-member group mute (#2782). Only a profile opened from inside a group
    // conversation has a group to scope the preference to, so DMs and every
    // other entry point leave the key — and therefore the row — null. The
    // conversation's own account owns the entry, which is the active one except
    // during a notification-routed early open.
    val memberMuteGroupIdHex = adminController?.takeUnless { it.isDm }?.group?.groupIdHex
    val memberMuteAccountRef = adminController?.boundAccountRef ?: appState.activeAccountRef
    val mutedMemberKeys by appState.memberMutePreferences.state.collectAsState()
    val memberMuteKey =
        remember(memberMuteAccountRef, memberMuteGroupIdHex, hex, targetIsSelf) {
            MemberMutePreferences.memberKeyOrNull(
                accountRef = memberMuteAccountRef,
                groupIdHex = memberMuteGroupIdHex,
                senderIdHex = hex?.takeUnless { targetIsSelf },
            )
        }
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
    val liveGroupsRevision = appState.forwardTargetsRevision
    // Chats beyond the retained chat-list window, so groups in common and both pickers see every chat (#2983).
    val accountWideGroups = rememberProfileAccountWideGroups(appState, enabled = hex != null && !targetIsSelf)
    val accountWide = accountWideGroups.current()
    val shared =
        remember(hex, activeAccountHex, liveGroupsRevision, groupPickerRevision, accountWide) {
            // Existing on-demand projection includes archived groups and remains live behind a conversation.
            appState.personSharedGroupsForProfile(hex, accountWide)
        }
    val groupTitleCopy = rememberGroupTitleCopy()
    val sharedRows =
        remember(shared.groups, groupTitleCopy) {
            shared.groups.map {
                PersonSharedGroupRow(
                    it.group.groupIdHex,
                    chatListItemDisplayTitle(it, appState, groupTitleCopy),
                    it.memberCount,
                )
            }
        }
    val sharedItems = remember(shared.groups) { shared.groups.associateBy { it.group.groupIdHex } }
    LaunchedEffect(hex, shared.unresolvedGroupIds) {
        if (owner.canAct() && shared.unresolvedGroupIds.isNotEmpty()) {
            accountWideGroups.requestMembers(shared.unresolvedGroupIds)
        }
    }
    val addableGroupsState =
        rememberProfileGroupPickerState(appState, hex, accountWide, WhiteNoiseAppState::profileAddableGroupsState)
    val promotableGroupsState =
        rememberProfileGroupPickerState(appState, hex, accountWide, WhiteNoiseAppState::profilePromotableGroupsState)
    LaunchedEffect(page, addableGroupsState.pendingGroupIds, promotableGroupsState.pendingGroupIds) {
        val pendingGroupIds =
            when (page) {
                ProfileSheetPage.ADD_TO_GROUPS -> addableGroupsState.pendingGroupIds
                ProfileSheetPage.MAKE_ADMIN -> promotableGroupsState.pendingGroupIds
                ProfileSheetPage.PROFILE,
                ProfileSheetPage.GROUPS_IN_COMMON,
                ProfileSheetPage.NOTIFICATIONS,
                -> emptySet()
            }
        if (pendingGroupIds.isNotEmpty()) {
            accountWideGroups.requestMembers(pendingGroupIds)
        }
    }
    val editorAccount = accountAtOpen?.takeIf { showContactEditorDialog && hex != null && !targetIsSelf }
    if (editorAccount != null) {
        val entry = contactEditorEntry
        key(entry) {
            ContactPrivateDetailsRoot(
                appState = appState,
                account = editorAccount,
                contact = hex!!,
                profileName = title,
                nickname = contactNickname.orEmpty(),
                notes = contactNotes.orEmpty(),
                ownerIsCurrent = { owner.canAct() && showContactEditorDialog && contactEditorEntry === entry },
                onDismiss = { showContactEditorDialog = false },
                securePolicy = securePolicy,
            )
        }
    }

    val profileDmDiagnostic = remember(owner, npub) { DmCreationInteraction() }

    /** Opens the direct chat with this profile or creates it. */
    fun openOrCreateProfileChat(retryGroupIdHex: String? = null) {
        if (!owner.canAct() || creatingChat) return
        val progressHex = hex ?: return
        startChatError = null
        creatingChat = true
        val diagnosticAttempt = profileDmDiagnostic.nextAttempt()
        appState.beginChatCreateOpenTiming()
        appState.launchMutation {
            try {
                withDmCreationOwner(diagnosticAttempt, owner::canAct) { markOpened ->
                    when (
                        val result =
                            attemptOpenOrStartProfileChat(
                                npub = npub,
                                progressHex = progressHex,
                                recipientName = displayTitle,
                                retryGroupIdHex = retryGroupIdHex,
                                resolveDirectChat = {
                                    owner.requireCurrent()
                                    appState.resolveExistingDirectChat(npub, diagnosticAttempt = diagnosticAttempt)
                                },
                                createGroup = { reference ->
                                    owner.requireCurrent()
                                    appState.createProfileChatGroup(reference)
                                },
                                loadCreatedChatListItem = { id ->
                                    owner.requireCurrent()
                                    appState.loadCreatedChatListItem(id)
                                },
                                displayName = appState::displayName,
                                markCreateOpenStage = appState::markChatCreateOpenStage,
                                abandonCreateOpenTiming = appState::abandonChatCreateOpenTiming,
                                diagnosticAttempt = diagnosticAttempt,
                            )
                    ) {
                        is StartChatAttemptResult.Open ->
                            owner.leave {
                                accountAtOpen?.let {
                                    DmCreationDiagnostics.awaitFrame(
                                        it,
                                        result.item.group.groupIdHex,
                                        appState.runtimeGeneration,
                                        diagnosticAttempt,
                                    )
                                }
                                markOpened()
                                onOpenGroup(result.item, result.newlyCreated)
                            }
                        is StartChatAttemptResult.Failed -> if (owner.canAct()) startChatError = result.error
                    }
                }
            } finally {
                creatingChat = false
            }
        }
    }

    /** Adds the profile to the selected groups. */
    fun addProfileToGroups(selected: List<ChatListItem>) {
        val targetHex = hex ?: return
        if (!owner.canAct() || addingToGroups) return
        addingToGroups = true
        appState.launchMutation {
            try {
                owner.requireCurrent()
                val previousToast = appState.toast
                val allAdded =
                    appState.inviteProfileToGroups(
                        targetRef = targetHex,
                        targetGroupIds = selected.map { it.group.groupIdHex },
                    )
                if (owner.canAct()) {
                    feedback = personProfileFeedback(previousToast, appState.toast)
                    if (allAdded) page = pickerParent
                }
            } finally {
                addingToGroups = false
            }
        }
    }

    /** Grants the profile admin in the group. */
    fun makeProfileAdmin(group: ChatListItem) {
        val targetHex = hex ?: return
        if (!owner.canAct() || promotingAdmin) return
        promotingAdmin = true
        appState.launchMutation {
            try {
                owner.requireCurrent()
                val previousToast = appState.toast
                val promoted = appState.promoteProfileInGroup(targetHex, group.group.groupIdHex)
                if (owner.canAct()) {
                    feedback = personProfileFeedback(previousToast, appState.toast)
                    if (promoted) page = ProfileSheetPage.PROFILE
                }
            } finally {
                promotingAdmin = false
            }
        }
    }

    val notificationRevision by appState.profileNotificationOverrides.state.collectAsStateWithLifecycle()
    val profileNotificationSelection =
        remember(accountAtOpen, hex, notificationRevision) {
            appState.profileNotificationOverrides.get(accountAtOpen, hex)
        }
    val profileContent: @Composable (() -> Unit) -> Unit = { dismissProfile ->
        PersonProfileContent(
            startingAs =
                if (hex != null && !targetIsSelf) {
                    startingAccount(appState, accountAtOpen, runtimeAtOpen)
                } else {
                    null
                },
            person =
                PersonProfilePresentation(
                    displayTitle,
                    title,
                    hex ?: npub,
                    pictureUrl,
                    bannerUrl,
                    avatarImageAvailable,
                    about,
                    presentationNpub,
                    nip05,
                    nip05Verified,
                    lightningAddress,
                    hasTarget = hex != null,
                    self = targetIsSelf,
                    titleIsPublicKey = contactNickname == null && titleIsPublicKey,
                    publishedNameIsPublicKey = titleIsPublicKey,
                ),
            scroll = contentScrollState,
            follow = followRow,
            busy = creatingChat,
            fromGroup = adminController != null,
            canPromote =
                adminController == null &&
                    (
                        promotableGroupsState.groups.isNotEmpty() ||
                            promotableGroupsState.loadState != ProfileGroupPickerLoadState.READY
                    ),
            showSharedGroups = hex != null && !targetIsSelf,
            copied = copied,
            onBack = { if (!creatingChat) dismissProfile() },
            onMessage = { openOrCreateProfileChat() },
            notificationSummary =
                if (accountAtOpen != null && hex != null && !targetIsSelf) {
                    profileNotificationSummary(profileNotificationSelection)
                } else {
                    null
                },
            onNotifications = {
                if (owner.canAct()) {
                    notificationEntry = Any()
                    page = ProfileSheetPage.NOTIFICATIONS
                }
            },
            block =
                hex?.takeIf { !targetIsSelf }?.let {
                    ProfileBlockRowState(
                        blocked = blocked == true,
                        inProgress = blockBusy,
                        enabled = !blockBusy && blocked != null,
                    )
                },
            onBlock = {
                val request = blockRequest(hex, appState.activeAccountRef, blocked)
                if (request != null && owner.canAct() && !blockBusy) {
                    val (target, account, currentlyBlocked) = request
                    blockBusy = true
                    appState.launchMutation {
                        try {
                            owner.requireCurrent()
                            val outcome = appState.setUserBlocked(account, target, blocked = !currentlyBlocked)
                            if (owner.canAct()) {
                                when (outcome) {
                                    BlockOutcome.Confirmed -> blocked = !currentlyBlocked
                                    BlockOutcome.Uncertain -> appState.present(R.string.block_publication_uncertain)
                                    BlockOutcome.Unavailable -> appState.present(R.string.block_list_unavailable)
                                    BlockOutcome.TooLarge -> appState.present(R.string.error_update_too_large)
                                    BlockOutcome.Failed -> appState.present(R.string.block_change_failed)
                                }
                                if (outcome != BlockOutcome.Confirmed) feedback = appState.toast
                            }
                        } finally {
                            blockBusy = false
                        }
                    }
                }
            },
            onTeamReport =
                if (hex != null && !targetIsSelf && appState.teamReportingAvailable) {
                    { if (owner.canAct()) teamReport.open = true }
                } else {
                    null
                },
            memberMute =
                memberMuteKey?.let {
                    ProfileMemberMuteRowState(muted = it in mutedMemberKeys, enabled = owner.canAct())
                },
            onMemberMute = {
                val key = memberMuteKey
                if (owner.canAct() && key != null) {
                    appState.setMemberMutedInGroup(
                        accountRef = memberMuteAccountRef,
                        groupIdHex = memberMuteGroupIdHex,
                        memberIdHex = hex,
                        muted = key !in mutedMemberKeys,
                    )
                }
            },
            onFollow = {
                if (owner.canAct() && followRow.enabled && !followBusy) {
                    val target = checkNotNull(hex)
                    val desired = following != true
                    followBusy = true
                    appState.launchMutation {
                        try {
                            owner.requireCurrent()
                            runCatching { appState.setProfileFollowing(target, desired) }
                                .onSuccess { if (owner.canAct()) following = desired }
                                .onFailure { error ->
                                    rethrowIfCancellation(error)
                                    if (owner.canAct()) {
                                        appState.present(R.string.profile_follow_failed)
                                        feedback = appState.toast
                                    }
                                }
                        } finally {
                            followBusy = false
                        }
                    }
                }
            },
            onPrivateDetails = {
                if (owner.canAct()) {
                    contactEditorEntry = Any()
                    showContactEditorDialog = true
                }
            },
            onStartGroup = {
                if (owner.canAct()) {
                    hex?.let { target ->
                        owner.leave {
                            onStartGroup(RecipientSearch.Candidate(target, displayTitle, npub))
                        }
                    }
                }
            },
            onStartGroupMeasured = { origin, contour ->
                if (owner.canAct()) {
                    hex?.let { target ->
                        owner.leave {
                            val candidate = RecipientSearch.Candidate(target, displayTitle, npub)
                            onStartGroupMeasured?.invoke(candidate, origin, contour) ?: onStartGroup(candidate)
                        }
                    }
                }
            },
            onGroupEntry = {
                if (owner.canAct()) {
                    pickerParent = ProfileSheetPage.PROFILE
                    page = ProfileSheetPage.GROUPS_IN_COMMON
                }
            },
            onAddToGroup = {
                if (owner.canAct()) {
                    pickerParent = ProfileSheetPage.PROFILE
                    page = ProfileSheetPage.ADD_TO_GROUPS
                }
            },
            onPromote = { if (owner.canAct()) page = ProfileSheetPage.MAKE_ADMIN },
            onCopy = {
                if (owner.canAct()) {
                    clipboard.setText(AnnotatedString(presentationNpub))
                    copied = true
                }
            },
            onAvatar = { if (owner.canAct()) fullPictureOpen = true },
            onBanner = { if (owner.canAct()) fullBannerOpen = true },
            onCopyLightning = { if (owner.canAct()) lightningAddress?.let { clipboard.setText(AnnotatedString(it)) } },
            sharedAvatars = {
                PreparedGroupAvatarContent(
                    appState,
                    shared.groups.take(VISIBLE_GROUP_AVATAR_LIMIT).mapNotNull { it.selectedAvatarAsset },
                ) {
                    PersonSharedGroupAvatars(
                        sharedRows,
                        groupAnimation = { id ->
                            sharedItems[id]?.let { rememberChatListGroupAvatar(appState, it).groupAnimation }
                        },
                    ) { id ->
                        sharedItems[id]?.let { rememberChatListGroupAvatar(appState, it).image }
                    }
                }
            },
            error = {
                startChatError?.let { error ->
                    StartChatErrorCard(
                        error,
                        onRetry = { openOrCreateProfileChat(error.retryGroupIdHex) },
                        onInvite = {
                            if (owner.canAct()) {
                                launchInviteShare(context, inviteMessage, inviteTitle)
                                    .onFailure { appState.presentOutboundShareFailure("PROFILE_INVITE_SHARE", it) }
                            }
                        },
                        onCopy = { clipboard.setText(AnnotatedString(it)) },
                    )
                }
            },
            adminActions = {
                if (adminController != null && hex != null) {
                    ProfileSheetAdminActions(
                        adminController,
                        appState,
                        hex!!,
                        isCurrent = owner::canAct,
                        onFeedback = { if (owner.canAct()) feedback = it },
                    )
                }
            },
        )
    }

    MeasuredPlaybackDialog(
        origin = appState.pendingProfileOrigin,
        sourceContour = appState.pendingProfileContour,
        ownerKey = owner to appState.pendingProfilePresentationId,
        dismissEnabled = { !creatingChat && !addingToGroups && !promotingAdmin },
        onDismissStart = owner::dispose,
        onSourceDismiss =
            ({ currentDismiss() }).takeUnless {
                creatingChat || addingToGroups || promotingAdmin
            },
        onDismissRequest = { currentDismiss() },
        properties =
            DialogProperties(
                securePolicy = securePolicy,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        val returnFromGroups: () -> Unit = {
            if (openGroupsInCommon) dismiss() else page = ProfileSheetPage.PROFILE
        }
        // Register against the modal dialog's dispatcher, not the activity's;
        // otherwise the dialog consumes Back before this nested route sees it.
        BackHandler {
            if (!creatingChat && !addingToGroups && !promotingAdmin) {
                if (page == ProfileSheetPage.GROUPS_IN_COMMON) {
                    returnFromGroups()
                } else if (page == ProfileSheetPage.PROFILE) {
                    dismiss()
                } else {
                    page = if (page == ProfileSheetPage.ADD_TO_GROUPS) pickerParent else ProfileSheetPage.PROFILE
                }
            }
        }
        val motionPage = if (hex == null) ProfileSheetPage.PROFILE else page
        Box(Modifier.fillMaxSize().testTag(PROFILE_SHEET_HOST_TAG)) {
            SettingsRouteMotion(
                target = motionPage,
                depth = profileSheetPageDepth(motionPage, pickerParent),
                ownerKey = owner,
                contentVisible = owner.canPresent() && !appState.appLock.screenVisible,
                animationsEnabled = rememberConversationMotionEnabled(),
                cardMorph = true,
            ) { targetPage ->
                when (targetPage) {
                    ProfileSheetPage.ADD_TO_GROUPS -> {
                        val recipientHex = hex
                        if (recipientHex != null) {
                            ProfileAddToGroupsScreen(
                                target = RecipientSearch.Candidate(recipientHex, displayTitle, npub),
                                appState = appState,
                                state = addableGroupsState,
                                busy = addingToGroups,
                                onClose = { if (!addingToGroups) page = pickerParent },
                                onRetry = {
                                    accountWideGroups.requestMembers(addableGroupsState.pendingGroupIds, retry = true)
                                },
                                onAdd = ::addProfileToGroups,
                            )
                        }
                    }
                    ProfileSheetPage.MAKE_ADMIN ->
                        SettingsScaffold(
                            title = stringResource(R.string.person_promote_groups),
                            onBack = { if (!promotingAdmin) page = ProfileSheetPage.PROFILE },
                        ) {
                            Box(Modifier.fillMaxSize().fadingVerticalScroll(rememberScrollState())) {
                                ProfileMakeAdminContent(
                                    appState = appState,
                                    targetName = displayTitle,
                                    target = RecipientSearch.Candidate(hex.orEmpty(), displayTitle, npub),
                                    state = promotableGroupsState,
                                    busy = promotingAdmin,
                                    onClose = { if (!promotingAdmin) page = ProfileSheetPage.PROFILE },
                                    onRetry = {
                                        accountWideGroups.requestMembers(
                                            promotableGroupsState.pendingGroupIds,
                                            retry = true,
                                        )
                                    },
                                    onPromote = ::makeProfileAdmin,
                                )
                            }
                        }
                    ProfileSheetPage.GROUPS_IN_COMMON ->
                        PreparedGroupAvatarContent(
                            appState,
                            shared.groups.take(VISIBLE_GROUP_AVATAR_LIMIT).mapNotNull { it.selectedAvatarAsset },
                        ) {
                            PersonGroupsMembershipContent(
                                appState = appState,
                                account = accountAtOpen,
                                personHex = hex,
                                personName = displayTitle,
                                groups = shared.groups,
                                ownerIsCurrent = { owner.canAct() && page == ProfileSheetPage.GROUPS_IN_COMMON },
                                onFeedback = { if (owner.canAct()) feedback = it },
                                onMembershipChanged = {
                                    if (owner.canAct()) accountWideGroups.refreshAfterMembershipChange()
                                },
                                rows = sharedRows,
                                unresolved = shared.incomplete,
                                onBack = returnFromGroups,
                                onOpen = { id ->
                                    if (owner.canAct()) {
                                        appState
                                            .personSharedGroupsForProfile(hex, accountWideGroups.current())
                                            .groups
                                            .firstOrNull { it.group.groupIdHex == id }
                                            ?.let { item -> owner.leave { onOpenGroup(item, false) } }
                                    }
                                },
                                onAdd = {
                                    if (owner.canAct()) {
                                        pickerParent = ProfileSheetPage.GROUPS_IN_COMMON
                                        page = ProfileSheetPage.ADD_TO_GROUPS
                                    }
                                },
                                onRetry = {
                                    if (owner.canAct()) {
                                        accountWideGroups.requestMembers(shared.unresolvedGroupIds, retry = true)
                                    }
                                },
                                avatar =
                                    { id -> sharedItems[id]?.let { rememberChatListGroupAvatar(appState, it).image } },
                                groupAnimation = { id ->
                                    sharedItems[id]?.let { rememberChatListGroupAvatar(appState, it).groupAnimation }
                                },
                            )
                        }
                    ProfileSheetPage.NOTIFICATIONS -> {
                        val author = hex
                        if (accountAtOpen != null && author != null && !targetIsSelf) {
                            key(notificationEntry) {
                                val entry = notificationEntry
                                ProfileNotificationOverrideRoot(
                                    appState.profileNotificationOverrides,
                                    accountAtOpen,
                                    author,
                                    displayTitle,
                                    ownerIsCurrent = {
                                        val sameEntry = notificationEntry === entry
                                        owner.canAct() && page == ProfileSheetPage.NOTIFICATIONS && sameEntry
                                    },
                                    onBack = { page = ProfileSheetPage.PROFILE },
                                )
                            }
                        }
                    }
                    ProfileSheetPage.PROFILE -> profileContent(dismiss)
                }
            }
        }
    }

    val reportTarget = hex
    if (teamReport.open && reportTarget != null && accountAtOpen != null) {
        ProfileTeamReportSheet(
            appState = appState,
            accountRef = accountAtOpen,
            targetHex = reportTarget,
            canAct = owner::canAct,
            state = teamReport,
            onFeedback = { feedback = it },
        )
    }
    feedback?.let { result ->
        PersonProfileFeedbackDialog(result, onDismiss = { feedback = null }, securePolicy = securePolicy)
    }
    if (fullBannerOpen && bannerUrl != null) {
        AvatarFullScreenViewer(
            title = displayTitle,
            seed = hex ?: npub,
            pictureUrl = bannerUrl,
            onDismiss = { fullBannerOpen = false },
            securePolicy = securePolicy,
        )
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

/**
 * Banner load state for a surface [targetWidthPx] physical pixels wide.
 *
 * The width selects the loader's bounded banner decode, so the image is resolved for the box it
 * actually fills instead of being upscaled from the avatar cap (#2762).
 */
@Composable
internal fun rememberProfileBannerLoadState(
    bannerUrl: String,
    targetWidthPx: Int,
    peek: (String, Int) -> ImageBitmap? = AvatarImageLoader::peekBanner,
    load: suspend (String, Int) -> ImageBitmap? = { url, width ->
        // A kind-0 banner is published, so it decodes in the public profiles.
        AvatarImageLoader.loadBanner(url, width, AvatarImageLoader.publicProfiles())
    },
): ProfileBannerLoadState {
    var image by remember(bannerUrl, targetWidthPx) { mutableStateOf(peek(bannerUrl, targetWidthPx)) }
    var settled by remember(bannerUrl, targetWidthPx) { mutableStateOf(image != null) }
    LaunchedEffect(bannerUrl, targetWidthPx) {
        if (image == null) {
            image = load(bannerUrl, targetWidthPx)
            settled = true
        }
    }
    return ProfileBannerLoadState(image = image, settled = settled)
}

@Composable
@Suppress("FunctionNaming")
internal fun ProfileBannerImage(
    bannerUrl: String,
    peek: (String, Int) -> ImageBitmap? = AvatarImageLoader::peekBanner,
    load: suspend (String, Int) -> ImageBitmap? = { url, width ->
        // A kind-0 banner is published, so it decodes in the public profiles.
        AvatarImageLoader.loadBanner(url, width, AvatarImageLoader.publicProfiles())
    },
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val targetWidthPx = profileBannerTargetWidthPx(maxWidth)
        ProfileBannerImage(rememberProfileBannerLoadState(bannerUrl, targetWidthPx, peek, load))
    }
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

/** Edits viewer-local contact details with explicit Save/Cancel and the caller's secure-window policy. */
@Composable
@Suppress("LongMethod", "FunctionNaming")
internal fun ContactPrivateDetailsDialog(
    profileName: String,
    initialNickname: String,
    initialNotes: String,
    onDismiss: () -> Unit,
    onSave: (nickname: String, notes: String) -> Unit,
    securePolicy: SecureFlagPolicy = SecureFlagPolicy.Inherit,
    pictureState: ContactPictureEditorState? = null,
    pictureSource: String? = null,
    pictureSeed: String? = null,
    onPickPicture: () -> Unit = {},
    onRepositionPicture: () -> Unit = {},
    onClearPicture: () -> Unit = {},
    viewportInsets: WindowInsets? = null,
    popupMotion: dev.ipf.whitenoise.android.ui.design.PopupSurfaceMotion? = null,
) {
    val nickname =
        remember(initialNickname) {
            androidx.compose.foundation.text.input
                .TextFieldState(initialNickname)
        }
    val notes =
        remember(initialNotes) {
            androidx.compose.foundation.text.input
                .TextFieldState(initialNotes)
        }
    val scheme = MaterialTheme.colorScheme
    androidx.compose.runtime.CompositionLocalProvider(
        LocalWhiteNoiseTextFieldContainerColor provides scheme.surfaceContainerLowest,
    ) {
        ContactPrivateDetailsFrame(
            securePolicy = securePolicy,
            viewportInsets = viewportInsets,
            onDismiss = onDismiss,
            popupMotion = popupMotion,
            dismissEnabled = { pictureState?.busy != true },
            actions = {
                TextButton(
                    dismiss,
                    Modifier.testTag("person_profile.private_cancel"),
                    enabled = pictureState?.busy != true,
                ) { Text(stringResource(R.string.cancel)) }
                TextButton(
                    enabled = pictureState?.busy != true,
                    onClick = { onSave(nickname.text.toString(), notes.text.toString()) },
                    modifier = Modifier.testTag("person_profile.private_save"),
                ) { Text(stringResource(R.string.save)) }
            },
            form = {
                EmojiLabel(stringResource(R.string.profile_name_from_profile, profileName))
                pictureState?.let { state ->
                    ContactPictureControls(
                        state,
                        pictureSource,
                        profileName,
                        pictureSeed ?: profileName,
                        onPickPicture,
                        onRepositionPicture,
                        onClearPicture,
                    )
                }
                dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField(
                    state = nickname,
                    emojiAction = true,
                    enabled = pictureState?.busy != true,
                    label = { Text(stringResource(R.string.profile_contact_name_hint)) },
                    lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().testTag("person_profile.nickname"),
                )
                dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField(
                    state = notes,
                    emojiAction = true,
                    enabled = pictureState?.busy != true,
                    label = { Text(stringResource(R.string.profile_contact_notes_hint)) },
                    lineLimits =
                        androidx.compose.foundation.text.input.TextFieldLineLimits.MultiLine(
                            minHeightInLines = 3,
                            maxHeightInLines = 8,
                        ),
                    modifier = Modifier.fillMaxWidth().testTag("person_profile.notes"),
                )
                Text(
                    stringResource(R.string.profile_contact_editor_private_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
    }
}

private const val PROFILE_COPY_FEEDBACK_MILLIS = 2_000L

/** The three facts a block toggle needs; null until the target, account and current state are all known. */
private fun blockRequest(
    targetHex: String?,
    accountRef: String?,
    blocked: Boolean?,
): Triple<String, String, Boolean>? {
    if (targetHex == null || accountRef == null || blocked == null) return null
    return Triple(targetHex, accountRef, blocked)
}
