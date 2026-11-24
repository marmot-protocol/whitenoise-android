package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupInviteResultFfi
import dev.ipf.marmotkit.GroupManagementStateFfi
import dev.ipf.marmotkit.GroupPushDebugInfoFfi
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupRejoinInvitationFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.HistoryNoticeFfi
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ConversationSearchMatch
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.IndexedAttachment
import dev.ipf.whitenoise.android.core.LeaveAction
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.core.ReplyNavigation
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.core.TimelineReplyDisplay
import dev.ipf.whitenoise.android.core.replyBodyWithTypedMediaFallback
import dev.ipf.whitenoise.android.core.replyMediaKindFromMime
import dev.ipf.whitenoise.android.core.typedReplyMediaFallback
import dev.ipf.whitenoise.android.diagnostics.GroupMembershipTimings
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceSendStage
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.media.OutgoingSend
import dev.ipf.whitenoise.android.media.REMOVE_GROUP_IMAGE_MUTATION_KEY
import dev.ipf.whitenoise.android.media.mutationKey
import dev.ipf.whitenoise.android.ui.conversation.media.nativeFileBackedSendLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal fun <T> newestMatchingController(
    controllers: Iterable<T>,
    matches: (T) -> Boolean,
): T? = controllers.lastOrNull(matches)

data class ConversationControllerCopy(
    val couldntAddMemberDuplicateFormat: String =
        "Couldn't add %1\$s. They're already a member, or their signing key conflicts with an existing member.",
    val groupRosterChanged: String = "Group membership changed. Review the group and try again.",
) {
    fun couldntAddMemberDuplicate(name: String): String = String.format(couldntAddMemberDuplicateFormat, name)
}

/**
 * Whether [detail] is the MLS "duplicate signature key" commit rejection
 * (issue #899). The engine surfaces this as a raw enum path, e.g.
 * `add_members: CreateCommitError(ProposalValidationError(DuplicateSignatureKey))`,
 * which must never reach the user. In practice it means the proposed member
 * already holds a seat (the common case), or their signing key collides with an
 * existing member's; either way the add can't proceed. Callers map it to a
 * plain-language, name-aware message instead of the raw backend string. Matched
 * case-insensitively against the leaf enum name so it survives wrapper/format
 * churn around it.
 */
internal fun isDuplicateSignatureKeyError(detail: String?): Boolean = detail?.contains("DuplicateSignatureKey", ignoreCase = true) == true

internal fun duplicateSignatureKeyDisplayName(
    refs: List<String>,
    displayName: (String) -> String,
): String = refs.firstOrNull()?.let(displayName).orEmpty()

// Cap on how many subscription windows one coalesced batch can absorb. A
// runaway producer shouldn't be able to wedge the UI behind an unbounded
// drain loop; this keeps latency-to-first-paint bounded.
private const val TIMELINE_BATCH_CAP = 32

// Window to wait for additional subscription windows to coalesce into the
// current batch. 6ms is roughly the slack on a 120Hz frame budget (8.33ms)
// minus the apply+publish work, so we soak up updates that arrive within
// one frame without delaying the next paint.
private const val TIMELINE_BATCH_DRAIN_MS = 6L

internal fun groupWithPublicAvatar(
    group: AppGroupRecordFfi,
    avatarUrl: String?,
    encryptedImageCleared: Boolean = true,
): AppGroupRecordFfi =
    group.copy(
        avatarUrl = avatarUrl,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = if (avatarUrl != null && encryptedImageCleared) null else group.imageHashHex,
    )

internal data class ConversationIdentityProjection(
    val otherMemberAccount: String?,
    val memberCount: Int,
)

internal fun conversationIdentityProjection(
    members: List<AppGroupMemberRecordFfi>,
    activeAccountIdHex: String?,
    acceptedInvitePeerAccount: String?,
): ConversationIdentityProjection {
    val memberCount = GroupProjector.uniqueMemberCount(members)
    val rosterPeer =
        members
            .firstOrNull { member ->
                member.memberIdHex.isNotBlank() &&
                    !GroupProjector.isActiveAccountMember(member, activeAccountIdHex)
            }?.memberIdHex
    val continuityPeer = acceptedInvitePeerAccount?.takeIf(String::isNotBlank)
    return ConversationIdentityProjection(
        otherMemberAccount = rosterPeer ?: continuityPeer,
        memberCount = if (rosterPeer == null && continuityPeer != null) maxOf(memberCount, 2) else memberCount,
    )
}

internal enum class GroupAdministrationCommitOutcome {
    COMMITTED,
    ROSTER_CHANGED,
    KEEP_ONE_ADMIN,
    NO_CHANGE,
}

internal fun conversationStartsLoading(
    startOnConstruction: Boolean,
    accountRefOverride: String?,
    activeAccountRef: String?,
): Boolean = startOnConstruction && (accountRefOverride ?: activeAccountRef) != null

/** Whether a conversation open failed in a way Retry cannot fix, so no Retry action is offered. */
internal fun isTerminalOpenFailure(throwable: Throwable): Boolean = throwable is ConversationInitialLoadException

/**
 * The sentence shown for a failed conversation load. A bounded-read timeout keeps its restart guidance;
 * MarmotKit's not-ready and window-timeout states get copy that says the app is still working and will
 * retry, instead of the generic "something went wrong". A full disk asks the user to free space: since
 * MDK #2826 it no longer latches the account, so Retry can succeed once space is free.
 */
internal fun conversationLoadFailureMessage(
    throwable: Throwable,
    initialOpenTimedOut: Boolean,
): Int =
    when {
        initialOpenTimedOut -> R.string.error_restart_app_before_retry
        DiagnosticFormatter.errorCode(throwable) == "STORAGE_FULL" -> R.string.error_storage_full
        DiagnosticFormatter.isNotReady(throwable) -> R.string.error_not_ready
        DiagnosticFormatter.errorCode(throwable) == "TIMEOUT" -> R.string.error_window_timed_out
        else -> R.string.error_try_again
    }

/** Whether a failed conversation open may offer Try again, which a terminal failure never does. */
internal fun shouldOfferConversationLoadRetry(throwable: Throwable): Boolean = !isTerminalOpenFailure(throwable)

internal typealias MediaUploader =
    suspend (String, String, MediaUploadRequestFfi) -> MediaUploadResultFfi

internal typealias MediaImetaTagsBuilder =
    suspend (String, String, List<MediaAttachmentReferenceFfi>) -> List<MessageTagFfi>

internal typealias MediaPublisher =
    suspend (String, String, List<MediaAttachmentReferenceFfi>, String?) -> SendSummaryFfi

internal typealias InviteAcceptor = suspend (String, String) -> AppGroupRecordFfi

/** Decodes a just-sent attachment's poster in the given worker domain, that of the draft it was sent from. */
internal typealias MediaThumbnailDecoder =
    suspend (PendingAttachment, dev.ipf.whitenoise.android.media.MediaTrustDomain) -> android.graphics.Bitmap?

internal const val MESSAGE_EDIT_EVENT_KIND = 1009uL

class ConversationController(
    internal val appState: WhiteNoiseAppState,
    initialGroup: AppGroupRecordFfi,
    internal val initialMemberSnapshot: GroupMemberSnapshot? = null,
    initialChatListRow: ChatListRowFfi? = null,
    initialInviteConfirmationUnresolved: Boolean = false,
    internal val initialIsDm: Boolean = false,
    initialTimelinePreview: ChatListMessagePreviewFfi? = null,
    // Pins the conversation to a specific account instead of the account active
    // at construction. Set only by notification routing when the target opens
    // before its account switch lands (#586); every MDK read/write below is
    // already account-explicit, so a pinned controller stays correct while the
    // active account catches up.
    accountRefOverride: String? = null,
    private val startOnConstruction: Boolean = false,
    internal val copy: ConversationControllerCopy = ConversationControllerCopy(),
    internal val groupRosterReader: suspend (String, String) -> GroupRosterFfi = { account, groupIdHex ->
        appState.marmotIo(MarmotTraceSection.REFRESH_GROUP_ROSTER) {
            groupRoster(account, groupIdHex)
        }
    },
    internal val groupRecoveryStatusReader: suspend (String, String) -> GroupRecoveryStatusFfi =
        { account, groupIdHex ->
            appState.marmotIo {
                groupRecoveryStatus(account, groupIdHex)
            }
        },
    // Test seams: whether MDK parked this chat's convergence, the explicit retry, and the raw event stream.
    internal val groupConvergenceParkedReader: suspend (String, String) -> Boolean = { account, groupIdHex ->
        appState.marmotIo { groupMlsState(account, groupIdHex).convergenceParked }
    },
    private val groupConvergenceRetrier: suspend (String, String) -> Unit = { account, groupIdHex ->
        appState.marmotIo { retryGroupConvergence(account, groupIdHex) }
    },
    // MDK's runtime event firehose, the only stream that carries a chat's park and resume edges.
    internal val runtimeEventStreamOpener: suspend () -> RuntimeEventStream = { appState.openRuntimeEventStream() },
    private val historyNoticeDismisser: suspend (String, String) -> Boolean = { account, noticeId ->
        appState.marmotIo { dismissHistoryNotice(account, noticeId) }
    },
    internal val historyNoticesReader: suspend (String) -> List<HistoryNoticeFfi> = { account ->
        appState.marmotIo { historyNotices(account) }
    },
    internal val textPublisher: (suspend (String?, String, String, String) -> SendSummaryFfi)? = null,
    // Test seams: the user's own emoji a text uses, and the send that carries them.
    internal val customEmojiReader: (suspend (String) -> List<LocalEmojiArtwork>)? = null,
    internal val customEmojiSender: CustomEmojiSender? = null,
    internal val messageEditPublisher: suspend (String, String, String, String) -> Unit = { account, groupId, target, text ->
        appState.marmotIo(MarmotTraceSection.MESSAGE_EDIT) { editMessage(account, groupId, target, text) }
    },
    internal val pendingMessageEditPublisher: PendingMessageEditPublisher? = null,
    internal val pendingEditStatusReader: suspend (String, String, String) -> dev.ipf.marmotkit.LocalSendStatusFfi? =
        { account, groupId, token -> appState.marmotIo { localSendStatus(account, groupId, token) } },
    internal val mediaUploader: MediaUploader? = null,
    internal val mediaFileUploader: MediaFileUploader? = null,
    // The native per-send ciphertext bound for file-backed uploads; null when MarmotKit cannot report it.
    internal val fileBatchCiphertextBytes: () -> Long? = { nativeFileBackedSendLimits()?.batchCiphertextBytes },
    internal val mediaImetaTagsBuilder: MediaImetaTagsBuilder = { account, groupIdHex, references ->
        appState.marmotIo {
            references.map { reference -> buildMediaImetaTag(account, groupIdHex, reference) }
        }
    },
    internal val mediaPublisher: MediaPublisher? = null,
    // A just-sent pick decodes in its sent draft's domain, whose worker retires once the send has ended.
    internal val mediaThumbnailDecoder: MediaThumbnailDecoder = { attachment, domain ->
        decodeMediaThumbnailOffMain(attachment, domain)
    },
    internal val markdownParser: suspend (String) -> MarkdownDocumentFfi = { appState.parseMarkdownOrEmpty(it) },
    private val groupArchivedUpdater: suspend (String, String, Boolean) -> AppGroupRecordFfi =
        { account, groupIdHex, archived ->
            appState.marmotIo { setGroupArchived(account, groupIdHex, archived) }
        },
    private val groupProfileUpdater: suspend (String, String, String?, String?) -> Unit =
        { account, groupIdHex, name, description ->
            appState.runGroupChange(account, groupIdHex, actionLabel = R.string.edit_group_info_title) {
                appState.marmotIo { updateGroupProfile(account, groupIdHex, name, description) }
            }
        },
    internal val clockMillis: () -> Long = System::currentTimeMillis,
    internal val inviteAcceptor: InviteAcceptor = { account, groupIdHex ->
        appState.marmotIo(MarmotTraceSection.ACCEPT_GROUP_INVITE) {
            acceptGroupInvite(account, groupIdHex)
        }
    },
    private val windowPreparationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val windowApplyNanoTime: () -> Long = System::nanoTime,
    private val onWindowApplyMeasured: (WindowApplyPerformanceSample) -> Unit = {},
    internal val groupSnapshotReader: suspend (String, String) -> dev.ipf.marmotkit.GroupConversationSnapshotFfi =
        { account, groupId -> appState.marmotIo { groupConversationSnapshot(account, groupId) } },
    internal val initialAdminInviteWriter:
        suspend (String, String, List<String>, List<String>) -> GroupInviteResultFfi =
        { account, groupId, members, admins ->
            appState.runGroupChange(
                account,
                groupId,
                invite = true,
                actionLabel = R.string.group_action_add_members,
                affectedMemberIds = members,
                initialAdminIds = admins.toSet(),
            ) {
                appState.marmotIo(MarmotTraceSection.INVITE_MEMBERS) {
                    inviteMembersDetailedWithInitialAdmins(account, groupId, members, admins)
                }
            }
        },
) {
    internal val liveSubscriptions = appState.conversationLiveSubscriptions()

    internal var inviteConfirmationUnresolved by mutableStateOf(initialInviteConfirmationUnresolved)
    internal var inviteAuthorityReadInFlight = false
    internal var inviteAcceptanceResolutionState by mutableStateOf(GroupRosterLoadState.LOADING)
        internal set
    internal val inviteConfirmationAuthority = InviteConfirmationAuthority(initialGroup)
    var group by mutableStateOf(
        inviteConfirmationAuthority
            .reconcile(
                reconcileTerminalSelfMembership(initialGroup, initialGroup),
                establishesAcceptance = !initialInviteConfirmationUnresolved,
            ).let { if (initialInviteConfirmationUnresolved) it.copy(pendingConfirmation = false) else it },
    )
        internal set

    internal val mutationPresentation =
        ConversationMutationPresentation(
            ownsPresentation = {
                !controllerCleared &&
                    !isAccountTeardownRequested() &&
                    appState.runtimeGeneration == conversationRuntimeGeneration
            },
        )

    // A typed native refusal proves only that this Welcome is no longer
    // pending. Keep its action retired until an authoritative roster resolves
    // whether the account is already a member or has left/been removed.
    internal var inviteAcceptanceAwaitingAuthority by mutableStateOf<InviteAcceptanceGeneration?>(null)

    internal val inviteAcceptanceResolutionPending: Boolean
        get() = inviteConfirmationUnresolved || inviteAcceptanceAwaitingAuthority != null

    internal val ownsInviteAcceptanceResult: Boolean
        get() = !controllerCleared && !isAccountTeardownRequested()

    /** Metadata refreshes may cross Join; a different Welcome or terminal observation may not. */
    internal fun ownsInviteAttempt(attempt: InviteAcceptanceAttempt): Boolean =
        ownsInviteAcceptanceResult &&
            !inviteConfirmationUnresolved &&
            inviteConfirmationAuthority.revision == attempt.inviteRevision &&
            attempt.generation.matches(group) &&
            group.acceptsInviteResults()

    /**
     * Archived state the conversation surfaces should present: the accepted
     * in-flight archive/restore intent when one exists, else the authoritative
     * group. Failure or cancellation only clears the intent, so the newest
     * authoritative state shows through instead of a captured snapshot.
     */
    val presentedArchived: Boolean
        get() = mutationPresentation.presentedArchived(group.archived)

    internal var acceptedInvitePeerAccount by mutableStateOf<String?>(null)

    // Retains a locally committed rename against a stale group-state snapshot (#2696).
    internal val localGroupNames = LocalGroupFieldAuthority()

    // The same retention for a just-committed description, so a pre-edit snapshot cannot restore it (#3704).
    internal val localGroupDescriptions = LocalGroupFieldAuthority()

    /**
     * Latest chat-list projection for this conversation, kept live even while
     * the chat list itself is hidden (its controller freezes item recomputes
     * off-screen). Read for row-scoped state a conversation surface needs
     * fresh, like the engine's durable mute.
     */
    var latestChatListRow by mutableStateOf(initialChatListRow)
        private set

    /**
     * The engine's conversation kind alone. The row above changes with every last message, unread count and read
     * marker, so the DM classification and title read this instead: their readers, every visible bubble among
     * them, recompose only when the kind itself changes.
     */
    private val latestConversationKind: ChatConversationKindFfi? by derivedStateOf {
        latestChatListRow?.conversationKind
    }

    /**
     * An encrypted image upload may succeed before clearing a legacy URL
     * avatar fails. MDK gives URL avatars precedence, so retain this transient
     * retry latch and avoid uploading the same bytes again on the next tap.
     */
    internal var pendingLegacyAvatarClearAfterImageMutationKey: String? = null

    // Hex of the conversation's own account. Captured like
    // conversationAccountRef so display/permission/"is me" helpers stay tied to
    // the conversation's account instead of reading the live active account
    // (which can differ from the controller's account before teardown, and
    // during a notification-routed open while the switch is still landing).
    // A pinned controller never falls back to the active account: an override
    // whose label is missing resolves to null, which fails closed ("is me",
    // admin, and composer gates all deny) instead of mixing two accounts.
    internal val conversationAccountIdHex =
        if (accountRefOverride != null) {
            appState.accounts.firstOrNull { it.label == accountRefOverride }?.accountIdHex
        } else {
            appState.activeAccount?.accountIdHex
        }

    private val membershipSeed = conversationMembershipSeed(initialGroup, initialMemberSnapshot, conversationAccountIdHex)

    var members by mutableStateOf<List<AppGroupMemberRecordFfi>>(membershipSeed.members)
        internal set

    internal val membershipTimings = GroupMembershipTimings()
    internal val optimisticGroupRosterMutation =
        OptimisticGroupRosterMutationTracker(
            onMembershipStarted = membershipTimings::pendingAccepted,
            onMembershipSettled = membershipTimings::pendingSettled,
        )

    /** Ephemeral action feedback belonging only to this account-bound conversation controller. */
    internal val pendingMembershipActivity: PendingGroupMembershipActivity?
        get() = optimisticGroupRosterMutation.pendingMembershipActivity

    /** Roster shown by group-management UI while MDK reconciles a local action. */
    val presentedMembers: List<AppGroupMemberRecordFfi>
        get() = projectedGroupMembers(members, optimisticGroupRosterMutation.current)

    /** Invite targets shown as pending rows until detailed MDK state arrives. */
    val pendingInviteMemberRefs: List<String>
        get() = pendingGroupInviteRefs(members, optimisticGroupRosterMutation.current)

    val presentedMemberCount: Int
        get() = GroupProjector.uniqueMemberCount(presentedMembers)

    fun isMemberMutationPending(memberIdHex: String): Boolean =
        when (val mutation = optimisticGroupRosterMutation.current) {
            is OptimisticGroupRosterMutation.Remove ->
                mutation.memberIdHex.equals(memberIdHex, ignoreCase = true)
            is OptimisticGroupRosterMutation.SetAdmin ->
                mutation.memberIdHex.equals(memberIdHex, ignoreCase = true)
            else -> false
        }

    // Use cached members immediately to avoid a blank composer gap while the
    // first refresh verifies the roster.
    var membersLoaded by mutableStateOf(membershipSeed.membersLoaded)
        internal set

    // True when the projected self-membership or cached roster positively places
    // the active account in the group. Lets the bottom bar show the composer
    // immediately while refreshMembers() verifies, without flashing it for a
    // group the user has already left. A projected non-member row
    // (`selfMembership = REMOVED/LEFT`) remains authoritative and overrides any
    // stale member snapshot that still contains self.
    val seededSelfMember: Boolean = membershipSeed.seededSelfMember

    // True when construction received a synchronous membership signal: either a
    // member snapshot (warm from the chat-list cache or shared AppState snapshot)
    // or the chat-list projection's own self-membership. When true,
    // `seededSelfMember` is authoritative for the initial composer state.
    //
    // When false there is NO local membership signal yet (genuinely cold open:
    // first-ever open, fresh process, or a row tapped before its background
    // member fetch landed). In that case neither the active composer nor the
    // "no longer a member" notice is known to be correct, so the bottom bar must
    // not paint either — doing so flashes a wrong state for ~0.5–1s until
    // refreshMembers() confirms. Before this, a cold open of a group the user IS
    // a member of (especially an admin re-entering their own group) flashed the
    // disabled notice (issue #623, the inverse of #545). Non-empty vs empty is
    // not the test: a non-null snapshot is membership-known even if leaving a
    // solo group emptied it.
    val seededMembershipKnown: Boolean = membershipSeed.seededMembershipKnown

    var membersVerified by mutableStateOf(membershipSeed.membersVerified)
        internal set

    internal val memberRosterLoadTracker =
        GroupRosterLoadTracker(
            if (membershipSeed.membersVerified) {
                GroupRosterLoadState.READY
            } else {
                GroupRosterLoadState.LOADING
            },
        )

    internal val memberRosterState: GroupRosterLoadState
        get() = memberRosterLoadTracker.state

    /** The settled branch that left the roster unverified, cleared by the next verified roster. */
    internal var rosterBlockReason by mutableStateOf<GroupRosterBlockReason?>(null)
        internal set

    internal val memberRosterRefreshGeneration = StalenessGuard()

    // Invalidated when a timeline page or live subscription batch lands so an
    // in-flight full-page refresh cannot clobber newer state (#1849).
    internal val timelineWindowGeneration = StalenessGuard()
    internal val windowPresentationTiming = appState.conversationWindowPresentationTiming()

    // Authoritative local self-leave marker (issue #787). Short-lived lifecycle
    // state (lives only as long as this controller, never persisted —
    // AGENTS.md); it mirrors ChatsController.removedGroupIds for the chat-list
    // row.
    //
    // The engine eviction (GroupStateError::UseAfterEviction) that
    // refreshMembers() relies on may not have landed locally yet right after a
    // self-leave, so a transient authoritative roster refresh
    // round-trip would otherwise re-read the full roster (self still present),
    // restore the member count and re-enable the composer. While set,
    // isSelfMember reads false and roster application refuses to re-add self,
    // keeping the left state durable.
    //
    // Seeded from an authoritative synchronous not-member signal
    // (seededMembershipKnown && !seededSelfMember): either a snapshot that
    // already excludes self, or the chat-list projection's own self-membership
    // says REMOVED/LEFT. Re-opening a just-left/removed group builds a NEW
    // controller whose own success path never ran, so without this its first
    // refreshMembers() could re-add self and revert the left state (the exact
    // #787 repro). These seed paths are the same local evidence the composer
    // gate uses for its initial NOTICE.
    internal val selfMembership =
        ConversationSelfLeftState(seededMembershipKnown, seededSelfMember, membershipSeed.accountStaysOnRoster)

    // A snapshot map (not mutableStateOf<Map>) so a bubble reading one key isn't
    // recomposed when a different message's reactions change.
    internal val reactionsState = mutableStateMapOf<String, List<ReactionTally>>()
    val reactions: Map<String, List<ReactionTally>> get() = reactionsState
    var deletedMessageIds by mutableStateOf<Set<String>>(emptySet())
        internal set

    // One-shot invalidation mailbox for UI snapshots held outside the bounded
    // timeline. ConversationScreen acknowledges each batch after reconciliation,
    // so removed IDs do not become a second long-lived message index.
    var pendingTimelineRemovedMessageIds by mutableStateOf<Set<String>>(emptySet())
        internal set
    private var replySelection by mutableStateOf<AppMessageRecordFfi?>(null)

    /** Distinguishes a new selection of the same message from the reply an in-flight send captured. */
    internal var replySelectionVersion = 0L
        private set

    var replyingTo: AppMessageRecordFfi?
        get() = replySelection
        set(value) {
            replySelectionVersion += 1
            replySelection = value
        }

    /** Set by the mounted conversation screen so non-composer senders never drop its staged attachments. */
    @Volatile
    internal var stagedAttachmentSender: StagedAttachmentSender? = null

    /** Per-target edit history for kind-1009 events, recomputed on every
     * timeline publish. The bubble reads `.latestText` and the "(edited · N)"
     * affordance reads `.count`. Null entry == message never edited. */
    var editsByTarget by mutableStateOf<Map<String, EditState>>(emptyMap())
        internal set

    /**
     * Local optimistic edits keyed by target message id, applied immediately on
     * confirm so the bubble flips to the edited text without waiting for the
     * kind-1009 to round-trip through the engine (the echo can lag ~1s). Merged
     * over MDK's accepted-edit summaries on every publish, then dropped once the real
     * edit lands in the timeline. A [MessageStatus.Pending] entry drives a
     * brief sending indicator on the target bubble; [MessageStatus.Failed]
     * reverts the displayed text to the pre-edit body and lights the same
     * retry/discard affordance a failed send shows. Mirrors the
     * [optimisticMessages] map: local-first display, reconciled on engine echo.
     */
    internal val optimisticEdits = mutableStateMapOf<String, OptimisticEdit>()
    internal val pendingMessageEditHandoff: PendingMessageEditHandoff get() = appState.pendingMessageEditHandoff

    internal fun pendingEditKey(token: String): String = "${conversationAccountRef.orEmpty()}|${group.groupIdHex}|$token"

    /** Set when the user has tapped Edit on a kind-9 they sent — the composer
     * banner reflects this and the next [send] routes through [editMessage]
     * instead of producing a new chat. Cleared on submit, cancel, or
     * navigation away. */
    internal var editSessionRevision = 0L
    var editingMessageId by mutableStateOf<String?>(null)

    fun beginMessageEdit(messageId: String) {
        if (editingMessageId != messageId) cancelMessageEdit()
        if (
            "msg:$messageId" in optimisticMessages &&
            (
                optimisticMessages["msg:$messageId"]?.status != MessageStatus.Sent ||
                    timelineRecords[messageId]?.sourceMessageIdHex == null
            )
        ) {
            val originalToken = timelineRecords[messageId]?.clientToken ?: messageId
            pendingMessageEditHandoff.begin(pendingEditKey(messageId), originalToken)
        }
        editSessionRevision += 1
        editingMessageId = messageId
    }

    fun cancelMessageEdit() {
        editSessionRevision += 1
        val readyEdit = editingMessageId?.let { pendingMessageEditHandoff.cancel(pendingEditKey(it)) }
        editingMessageId = null
        readyEdit?.let { edit -> appState.launchMutation { editMessage(edit.targetId, edit.text) } }
    }

    // Production controllers start their local subscription during
    // construction. Reflect that synchronously so the first composition cannot
    // mistake the not-yet-started coroutine for an authoritative empty chat.
    var isLoading by
        mutableStateOf(
            conversationStartsLoading(startOnConstruction, accountRefOverride, appState.activeAccountRef),
        )
        internal set
    internal var pageLoadInFlight by mutableStateOf<ConversationSearchPageDirection?>(null)
        internal set
    var hasMoreBefore by mutableStateOf(false)
        private set
    internal var hasMoreAfter by mutableStateOf(false)

    /** Shared action slot, independent of the subscription attempt's lifetime. */
    val mutationInFlight: Boolean get() = mutationPresentation.inFlight
    var lastMutationError: ErrorPresentation?
        get() = mutationPresentation.error
        internal set(value) {
            if (value == null) mutationPresentation.clearError() else mutationPresentation.recordFailure(value)
        }
    internal var subscriptionError by mutableStateOf<ErrorPresentation?>(null)
    internal var pageError by mutableStateOf<ErrorPresentation?>(null)
    val error: ErrorPresentation?
        get() = pageError ?: subscriptionError
    internal val errorEdge: ConversationLoadFailureEdge
        get() = conversationLoadFailureEdge(pageError != null, failedPageDirection)

    // staleness-exempt: observable retry trigger; nullable snapshots below retain its value.
    var retryGeneration by mutableLongStateOf(0L)
        private set
    internal var terminalLoadFailure = false
    internal var failedPageDirection: ConversationSearchPageDirection? = null

    fun clearLastMutationError() {
        lastMutationError = null
    }

    /** Retains the original conversation error without showing it on a replacement account or runtime. */
    internal fun recordMutationFailure(
        @StringRes title: Int,
        operationCode: String,
        throwable: Throwable,
        detail: AppText = AppText.Resource(R.string.error_try_again),
    ) {
        lastMutationError = privacySafeErrorPresentation(operationCode, throwable, detail)
        if (appState.activeAccountRef == conversationAccountRef &&
            appState.runtimeGeneration == conversationRuntimeGeneration
        ) {
            appState.presentFailure(title, operationCode, throwable, detail)
        }
    }

    internal fun Throwable.rethrowIfCancellation() {
        if (this is CancellationException) throw this
    }

    /** Admits one UI mutation while keeping its execution in the caller/app-owned scope. */
    internal suspend fun <T> withMutationLockResult(
        defaultValue: T,
        block: suspend () -> T,
    ): T = mutationPresentation.runIfIdle(defaultValue, block)

    internal val conversationAccountRef = accountRefOverride ?: appState.activeAccountRef
    internal val conversationRuntimeGeneration = appState.runtimeGeneration
    internal val boundAccountRef: String?
        get() = conversationAccountRef

    /** This conversation's media trust domain: a worker that decodes its images serves no other conversation. */
    internal val mediaTrustDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain
        get() =
            dev.ipf.whitenoise.android.media.MediaTrustDomain
                .conversation(conversationAccountRef, group.groupIdHex)

    /**
     * The domain of one file this conversation's user picked to send here: [pick] is its composer slot id, which a
     * restored draft attachment keeps as its own, or a document's URI. It decodes in its draft's one shared worker,
     * which no later draft reaches, while a read that names no pick decodes single-use. The drafts belong to the
     * app, not this controller, so a replaced controller or screen keeps every pick's draft.
     */
    internal fun outgoingMediaTrustDomain(pick: String?): dev.ipf.whitenoise.android.media.MediaTrustDomain =
        appState.outgoingDrafts.domain(conversationAccountRef, group.groupIdHex, pick)

    /**
     * Starts a send of [picks]: they keep their draft's worker and the next pick starts a new draft. With [ownDraft],
     * picks that joined no draft yet, as after a restart, join one of their own. The returned send stays open until
     * [endOutgoingSend], once its just-sent thumbnails are done.
     */
    internal fun beginOutgoingSend(
        picks: Collection<String>,
        ownDraft: Boolean = false,
    ): OutgoingSend = appState.outgoingDrafts.beginSend(conversationAccountRef, group.groupIdHex, picks, ownDraft)

    /** Ends a send [beginOutgoingSend] started, retiring each of its drafts that nothing decodes in any more. */
    internal fun endOutgoingSend(send: OutgoingSend) = appState.outgoingDrafts.endSend(send)

    /**
     * Records the [picks] now in the composer, so a sent pick that left it releases its draft and an empty composer
     * ends the current draft.
     */
    internal fun retainOutgoingPicks(picks: Set<String>) {
        appState.outgoingDrafts.retain(conversationAccountRef, group.groupIdHex, picks)
    }

    /** Current retained owner eligibility; the shell's privacy and route admission remain independent. */
    internal val ownsRetainedForegroundPresentation: Boolean
        get() =
            !controllerCleared &&
                !isAccountTeardownRequested() &&
                conversationAccountRef != null &&
                appState.activeAccountRef == conversationAccountRef &&
                appState.runtimeGeneration == conversationRuntimeGeneration

    // Use the conversation's identity for mentions and "is me" checks.
    // The active account can lag during an early open from a notification (#586).
    internal val boundAccountIdHex: String?
        get() = conversationAccountIdHex

    internal fun presentConversationTransient(
        @StringRes titleRes: Int,
        kind: TransientNoticeKind = TransientNoticeKind.Confirmation,
    ) {
        val accountRef = conversationAccountRef ?: return
        appState.presentConversationTransient(accountRef, group.groupIdHex, titleRes, kind = kind)
    }

    internal fun presentConversationTransient(title: AppText) {
        val accountRef = conversationAccountRef ?: return
        appState.presentConversationTransient(accountRef, group.groupIdHex, title)
    }

    internal val mediaUploadSessionEpoch = appState.mediaUploadSessionEpoch()
    internal val messageById = linkedMapOf<String, AppMessageRecordFfi>()
    internal val timelineRecords = linkedMapOf<String, TimelineMessageRecordFfi>()

    /** Whether [messageIdHex] is an authoritative row of the current window rather than a local optimistic id. */
    internal fun retainsTimelineRecord(messageIdHex: String): Boolean = timelineRecords.containsKey(messageIdHex)

    internal val timelineItemsById = linkedMapOf<String, TimelineMessage>()
    internal val timelineOrder = mutableListOf<String>()
    internal val authoritativeTimelineOrderByMessageId = linkedMapOf<String, ULong>()
    internal val optimisticMessages = appState.optimisticMessages(conversationAccountRef, initialGroup.groupIdHex)
    internal val optimisticSendPhases =
        appState.optimisticSendPhases(conversationAccountRef, initialGroup.groupIdHex)
    internal val durableAcceptanceCallbacks =
        appState.durableAcceptanceCallbacks(conversationAccountRef, initialGroup.groupIdHex)
    private val initialTimeline =
        initialConversationTimeline(
            preview = initialTimelinePreview,
            accountIdHex = conversationAccountIdHex,
            groupIdHex = initialGroup.groupIdHex,
            pendingConfirmation = group.pendingConfirmation || initialInviteConfirmationUnresolved,
            optimisticMessages = optimisticMessages.values,
        )

    /** Whether construction had enough complete local UI state to paint a real bubble. */
    val hadInitialTimelineSeed: Boolean = initialTimeline.isNotEmpty()

    /** True only until MDK's first authoritative page replaces the construction seed. */
    var initialTimelineSeedActive by mutableStateOf(hadInitialTimelineSeed)
        internal set

    var timeline by mutableStateOf(initialTimeline)
        internal set

    /** UI identity aliases only, bounded to this controller's retained timeline window. */
    internal val timelinePresentationIds = mutableMapOf<String, String>()

    /** Recovery generation represented by the latest authoritative timeline. */
    var recoveryProjectionGeneration by mutableLongStateOf(0L)
        internal set

    var hasPublishedAuthoritativeTimeline by mutableStateOf(false)
        internal set

    /** Screen tests promote a tapped chat once the first native page would have published. */
    @VisibleForTesting
    internal fun markAuthoritativeTimelinePublishedForTest() {
        hasPublishedAuthoritativeTimeline = true
    }

    /** Local sender metadata needed for a settled first presentation is ready. */
    var hasPreparedInitialPresentation by mutableStateOf(false)
        internal set

    /**
     * True while the first authoritative page names event cards and is held unpublished until their cached state has
     * been read, or the card budget ran out (#2238). Every path that shows the conversation then composes those rows
     * only with their cards complete, and the chat-list open may wait the card budget instead of its usual bound.
     * Changes made meanwhile, a send included, publish with the page, at most the card budget after it arrived.
     */
    var isHoldingInitialPageForEventCards by mutableStateOf(false)
        internal set

    /** The engine proved this conversation is no longer available to the account. */
    var terminalConversationUnavailable by mutableStateOf(false)
        internal set

    /** Current engine-owned recovery evidence for this conversation. */
    var groupRecoveryStatus by mutableStateOf<GroupRecoveryStatusFfi?>(null)
        internal set
    internal val groupRecoveryLifetime = StalenessGuard()

    // staleness-exempt: raw group-state edges invalidate the details screen’s advisory capability read.
    internal var accountDevicesGroupRevision by mutableLongStateOf(0L)

    /** Whether the latest recovery-status read failed while prior evidence stays inspectable. */
    var groupRecoveryReadFailed by mutableStateOf(false)
        internal set

    /** Whether this group's first shown history notice says the account rejoined, so older messages may be missing. */
    var groupHistoryNoticeRejoined by mutableStateOf(false)
        internal set

    /** Prevents duplicate accept/decline actions while MDK verifies the selected invitation. */
    var groupRecoveryMutationInFlight by mutableStateOf(false)
        private set

    /** The last answer MDK gave to whether it parked this chat's convergence, kept when a later read fails. */
    var groupConvergenceParked by mutableStateOf(false)
        internal set

    /** Whether the user's explicit convergence retry is running. */
    var groupConvergenceRetrying by mutableStateOf(false)
        private set

    /** Why the latest convergence retry left the chat parked, as a localized message, or null. */
    @get:StringRes
    var groupConvergenceRetryFailure by mutableStateOf<Int?>(null)
        internal set

    // Only the newest parked-flag read may publish, so a slow earlier read cannot overwrite a later answer.
    internal val groupConvergenceReads = StalenessGuard()

    // One parked-flag read runs at a time, and requests made meanwhile fold into a single read after it.
    internal val groupConvergenceReadGate = Mutex()
    internal val groupConvergenceReadRequested = AtomicBoolean(false)

    /** Rereads in a row that were scheduled because a group-state edge made the previous answer stale. */
    internal var groupConvergenceStaleRereads = 0

    // Completes once the conversation's first open settles, so the parked read never waits ahead of it.
    internal val groupConvergenceOpenSettled = CompletableDeferred<Unit>()
    internal val projectedMessageIds = appState.projectedMessageIds(conversationAccountRef, initialGroup.groupIdHex)
    internal val localTimelineOrderOverrides =
        appState.timelineOrderOverrides(conversationAccountRef, initialGroup.groupIdHex)
    internal val localTimelineTimestampOverrides =
        appState.timelineTimestampOverrides(conversationAccountRef, initialGroup.groupIdHex)

    // The optimistic *send* handoffs that wrote the overrides above.
    // These are transient and get released once their optimistic bubble is gone,
    // so a stale one can't pin a confirmed row above a newer neighbour (#1578).
    // Ownership lives in AppState alongside the overrides so a replacement
    // controller can finish the cleanup.
    internal val optimisticSendPositionPreserves =
        appState.optimisticSendPositionPreserves(conversationAccountRef, initialGroup.groupIdHex)
    internal val retentionAtSendByMessageId =
        appState.retentionAtSend(conversationAccountRef, initialGroup.groupIdHex)

    // Unlike immediately published text, accepted-pending text keeps its
    // temporary optimistic id. Retain MDK's canonical id across controller
    // replacement so the eventual projection settles the exact bubble.
    internal val acceptedPendingTextOptimisticIds =
        appState.acceptedPendingTextOptimisticIds(conversationAccountRef, initialGroup.groupIdHex)

    // Holding pen for media projection echoes that arrive while their
    // matching bridge is still mid-`sendMediaAttachments`. Shared via
    // AppState so that if the user navigates out of the chat between echo
    // and bridge insert, the OLD controller's `performMediaUpload` still
    // sees the stash that the NEW controller's subscription contributed
    // to (or vice-versa).
    internal val pendingProjectionsAwaitingBridge =
        appState.pendingProjectionsAwaitingBridge(conversationAccountRef, initialGroup.groupIdHex)
    internal val optimisticReactionChanges = linkedMapOf<String, OptimisticReactionChange>()
    internal val unprojectedOwnReactions = mutableSetOf<Pair<String, String>>()
    internal val reactionIntentConflator = ReactionIntentConflator()
    internal val reactionTapAdmissions = KeyedMutexPool()
    internal val groupCommitRefusal = GroupCommitRefusalState()
    internal val window = ConversationWindowState()
    internal val timelineSession =
        ConversationTimelineSession(onTimelineRetired = { window.readySubscription = null })
    internal val controllerScope = timelineSession.scope

    /** First-frame snapshots of this conversation's public event cards, read from MDK before rows show (#2238). */
    internal val eventCardPresentation =
        ConversationEventCardPresentation(
            appState = appState,
            conversationAccountRef = conversationAccountRef,
            currentScope = { conversationScope },
            latestInstalledPage = { timelineSubscription?.latestInstalledWindow()?.page },
            shownRecords = { timelineRecords },
            initialPresentationReady = { hasPreparedInitialPresentation },
        )
    internal val eventCardSnapshots get() = eventCardPresentation.eventCardSnapshots
    internal val initialPresentationWarmCoordinator =
        ConversationInitialPresentationWarmCoordinator(
            scope = controllerScope,
            warmProfiles = { senders -> appState.warmProfilePresentationsBlocking(senders) },
            warmEventCards = { work -> eventCardPresentation.warmInitialEventCards(work) },
            onReady = {
                // Rows and the readiness flag change together, so the opened route never sees a held page.
                releaseInitialPageEventCardHold()
                hasPreparedInitialPresentation = true
            },
        )
    internal val inviteStreamScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val attachmentPresentation =
        ConversationAttachmentPresentation(
            appState = appState,
            accountRef = conversationAccountRef,
            groupIdHex = initialGroup.groupIdHex,
            sourceMessageId = { nativeAttachmentSourceId(it) },
        )
    internal val attachmentTransfers: AttachmentTransferCoordinator get() = attachmentPresentation.transfers

    /**
     * Native transfer states idle tiles read once, by `messageId#index`, so a tile scrolled back into view never reads
     * again. Touched only on the main thread; a failed read is not kept, so the next composition tries again.
     */
    internal val nativeAttachmentStateReads = HashMap<String, dev.ipf.marmotkit.AttachmentTransferStateFfi>()
    private val stickers = appState.stickersFor(conversationAccountRef)

    /** The bound account's sticker packs, shared with its other conversations. */
    internal val stickerLibrary: StickerLibrary get() = stickers.library

    /** The bound account's decoded sticker images, shared with its other conversations. */
    internal val stickerAssets get() = stickers.assets

    /** The installed native handle; paging and commits share the session's retirement authorities. */
    internal val timelineSubscription: ConversationTimelineSubscriptionHandle?
        get() = timelineSession.timelineSubscription
    internal val liveSubscriptionLock: Any get() = timelineSession.lock
    internal val timelineSubscriptionActiveCallMutex: Mutex get() = timelineSession.activeCallMutex
    internal val conversationScope: CoroutineScope? get() = timelineSession.currentScope
    internal val accountTeardownRequested: Boolean get() = timelineSession.accountTeardownRequested
    internal val controllerCleared: Boolean get() = timelineSession.cleared

    internal val inboundVisibleHostAttempt = HostPerformanceAttemptSlot()
    internal var inboundVisibleHostGeneration by mutableLongStateOf(0L)
        private set
    private val outboundVisibleHostAttempts = HostPerformanceAttemptRegistry()
    internal val foregroundSweepScheduleSignals = Channel<Unit>(Channel.CONFLATED)
    internal var lastForegroundSweepStartedAtMillis = 0L
    internal var hasLoadedOlderPages = false

    // Snapshot of message ids in the deliberately-loaded history window after the
    // last successful loadOlderPage(). Live Upserts after that are capped separately
    // so indexes and messageById cannot grow without bound (#1163).
    internal val protectedTimelineMessageIds = mutableSetOf<String>()

    // Read watermark seeded from MDK, then updated by visible read requests.
    // Compose-observable so the jump-to-mention chip follows the engine
    // watermark rather than the scroll position.
    var lastReadMessageId: String? by
        mutableStateOf(initialChatListRow?.lastReadMessageIdHex?.takeIf { it.isNotBlank() })
        internal set

    // A persisted watermark is not a read performed during this visit. The
    // first settled visible message must reach MDK even when the watermark
    // stays unchanged: MDK also clears manual unread attention on that read.
    internal var lastSubmittedReadMessageId: String? = null
    internal var manualUnreadReminderConsumed = false
    internal var nextReadRequestId = 0L
    internal var lastReadRequestId = 0L
    internal val pendingReadRequestIds = mutableSetOf<Long>()
    internal var lastSuccessfulReadRequestId = 0L
    internal var successfulReadWithoutRow: Pair<ChatListRowFfi?, String>? = null

    // Persisted read watermark from the chat-list projection / mark-read FFI.
    // Drives read-anchored disappearing-message deferral (#797).
    internal var persistedLastReadTimelineAt: ULong? = initialChatListRow?.lastReadTimelineAt

    // Session display/read anchors for received rows and local optimistic
    // sends. They override send-time expiry only when the row also carries
    // its pinned retention duration; projected sends otherwise use MDK's
    // authoritative deadline.
    internal val readAnchoredAtSeconds = mutableMapOf<String, ULong>()

    val title: String
        get() = title()

    /**
     * The conversation's display title from its group, roster and engine conversation kind, with [copy] supplying
     * the generated wording. Reads the derived kind, so its readers recompose on a chat-list row update only when
     * that update changes the kind.
     */
    fun title(copy: dev.ipf.whitenoise.android.core.GroupTitleCopy = dev.ipf.whitenoise.android.core.GroupTitleCopy.Default): String {
        val identity =
            conversationIdentityProjection(
                members = members,
                activeAccountIdHex = conversationAccountIdHex,
                acceptedInvitePeerAccount = acceptedInvitePeerAccount,
            )
        return GroupProjector.displayTitle(
            group = group,
            otherMemberAccount = identity.otherMemberAccount,
            memberCount = identity.memberCount,
            memberTitle = { appState.chatMemberTitle(it) },
            copy = copy,
            conversationKind = latestConversationKind,
            soleSelfMember = GroupProjector.isSelfSoleMember(members, conversationAccountIdHex),
            selfAccountIdHex = conversationAccountIdHex,
        )
    }

    val inviteAccount: String?
        get() {
            val me = conversationAccountIdHex
            return GroupProjector.inviteAccount(group, GroupProjector.otherMemberAccount(members, me), me)
        }

    /**
     * The peer account whose profile picture stands in for a 1:1 conversation's
     * avatar, mirroring the chat-list row (#837): the inviter for a pending
     * invite, otherwise the lone counterparty of an unnamed two-member chat.
     * Null for multi-member or named groups, which use their own group avatar.
     */
    val avatarAccount: String?
        get() {
            val peer =
                conversationIdentityProjection(
                    members = members,
                    activeAccountIdHex = conversationAccountIdHex,
                    acceptedInvitePeerAccount = acceptedInvitePeerAccount,
                )
            return GroupProjector.avatarAccount(group, peer.otherMemberAccount, peer.memberCount, boundAccountIdHex)
        }

    /**
     * The DM peer whose private nickname may title this conversation, else null (#2766).
     *
     * A pending invite is excluded because its title is the "Invite from …" line, which already
     * resolves the inviter through the nickname-aware member title.
     */
    val dmNicknamePeerAccount: String?
        get() = avatarAccount?.takeIf { isDm && !group.pendingConfirmation }

    /**
     * Avatar URL for the conversation top bar. A group's own avatar wins; a 1:1
     * DM falls back to the peer's profile picture so the top bar matches the
     * chat-list row instead of showing a blank/initials placeholder (#837).
     */
    val avatarUrl: String?
        get() = group.avatarUrl ?: avatarAccount?.let { appState.contactAvatarSource(it, boundAccountRef) }

    // Deduped case-insensitively, mirroring the projector's classification —
    // a hex-casing-drifted duplicate must not inflate the visible headcount.
    val memberCount: Int get() = GroupProjector.uniqueMemberCount(members)
    val isDm: Boolean
        get() =
            GroupProjector.isDm(latestConversationKind, memberCount, group.name) ||
                (
                    initialIsDm &&
                        !membersVerified &&
                        latestConversationKind != ChatConversationKindFfi.GROUP
                )

    val subtitle: String
        get() = subtitle(justYou = "Just you", oneMember = "1 member", membersFormat = "%1\$d members")

    fun subtitle(
        justYou: String,
        oneMember: String,
        membersFormat: String,
    ): String {
        val count = memberCount
        return when (count) {
            0 -> justYou
            1 -> oneMember
            else -> String.format(membersFormat, count)
        }
    }

    val isSelfAdmin: Boolean
        get() = GroupProjector.isAdminRef(group, conversationAccountIdHex)

    /**
     * DM classification for the deletion-capability matrix. Prefers the
     * engine's projected conversation kind, falling back to the same
     * headcount/name signals as the chat list for UNKNOWN and unprojected
     * rows. The fallback can transiently misclassify while the roster is
     * still unverified, but every path that grants delete-for-everyone also
     * requires [canSendMessages] (which includes membersVerified), so no
     * moderation capability is granted from an unverified roster.
     */
    val isDirectConversation: Boolean
        get() = GroupProjector.isDm(latestConversationKind, memberCount, group.name)

    /**
     * The authoritative deletion capability for [message], shared by the
     * delete surface (what to offer) and [deleteMessage] (what to accept).
     * [alreadyDeleted] defaults to the optimistic tombstone set and retained
     * timeline projection. The visible row can supply its current projection.
     */
    fun deleteCapabilityFor(
        message: AppMessageRecordFfi,
        alreadyDeleted: Boolean = isMessageDeleted(message.messageIdHex),
        optimisticKeyOverride: String? = null,
    ): MessageDeleteCapability {
        val optimisticKey = optimisticKeyOverride ?: optimisticSendKey(message)
        if (isOptimisticMessageId(message.messageIdHex)) {
            val optimistic = optimisticKey?.let(optimisticMessages::get)
            val cancellable =
                optimistic?.status == MessageStatus.Failed ||
                    optimisticKey?.let(optimisticSendPhases::get) == OptimisticSendPhase.PRE_ACCEPTANCE
            return MessageDeleteCapability(
                canDeleteForMe = false,
                canDeleteForEveryone = cancellable,
            )
        }
        return messageDeleteCapability(
            isDirectConversation = isDirectConversation,
            mine = isMessageMine(message),
            selfIsAdmin = isSelfAdmin,
            localDeleteSupported = message.messageIdHex.isNotBlank(),
            remoteDeleteSupported = canSendMessages && message.messageIdHex.isNotBlank(),
            alreadyDeleted = alreadyDeleted,
        )
    }

    fun isMessageMine(message: AppMessageRecordFfi): Boolean = MessageProjector.isMine(message, conversationAccountIdHex)

    val isSelfMember: Boolean
        get() =
            !inviteAcceptanceResolutionPending &&
                selfMembership.isSelfMember(members, conversationAccountIdHex)

    val canSendMessages: Boolean
        // The engine gates all ordinary outbound work while a disband
        // converges and forever after it lands; mirror that on the composer.
        get() =
            membersVerified &&
                isSelfMember &&
                !showsBlockedLeave &&
                !group.unrecoverable &&
                !group.disbanding &&
                !group.disbanded &&
                window.allowsSend

    val canLeaveGroup: Boolean
        get() = GroupProjector.canLeaveGroup(group, conversationAccountIdHex, memberCount)

    /**
     * Leave-dialog routing from a live roster read. The cached [members] list can
     * lag behind roster changes, and a stale multi-member count would send a sole
     * remaining member into the transfer-admin dead end (#811). Fall back to the
     * current in-memory roster only if the live read is unavailable.
     */
    suspend fun leaveAction(): LeaveAction {
        val account = conversationAccountRef
        val liveMemberCount =
            if (account != null) {
                runCatchingCancellable { appState.marmotIo { groupMembers(account, group.groupIdHex) } }
                    .getOrNull()
                    ?.let(GroupProjector::uniqueMemberCount)
            } else {
                null
            } ?: memberCount
        return GroupProjector.leaveAction(group, conversationAccountIdHex, liveMemberCount)
    }

    /**
     * True when the active account is the only admin of a group that still has
     * other members, i.e. trapped: they can't revoke their own admin or leave
     * until they hand admin to someone else. The group-detail UI uses this to
     * surface the "Transfer admin" entry point from the blocked revoke / leave
     * paths (issue #417).
     */
    val isSoleAdminWithOtherMembers: Boolean
        get() = GroupProjector.isSoleAdminWithOtherMembers(group, conversationAccountIdHex, memberCount)

    /** Members eligible to receive a transferred admin role from the active account. */
    fun transferAdminCandidates(): List<AppGroupMemberRecordFfi> = members.filter { GroupProjector.canTransferAdminTo(group, it, conversationAccountIdHex) }

    fun revokeWouldDepleteAdmins(member: AppGroupMemberRecordFfi): Boolean = GroupProjector.revokeWouldDepleteAdmins(group, member, memberCount)

    fun canTransferAdminTo(member: AppGroupMemberRecordFfi): Boolean = GroupProjector.canTransferAdminTo(group, member, conversationAccountIdHex)

    /**
     * Starts the local snapshot and live subscriptions on this controller's
     * lifecycle. Construction calls this before the first conversation frame;
     * later calls (including retry wiring in MainShell) are single-flight.
     */
    fun start() {
        val account = conversationAccountRef ?: return
        timelineSession.start(retryGeneration) { runStart(account) }
    }

    /**
     * The retained timeline row for the raw message id [messageId], whose item id is `msg:<hex>`. Null when the
     * bounded window no longer holds the row.
     */
    internal fun retainedTimelineItem(messageId: String): TimelineMessage? = timelineItemsById["msg:$messageId"]

    /** Resolves a current visible action target, including local sends, without trusting a stale Compose row. */
    internal fun currentActionTimelineItem(messageId: String): TimelineMessage? {
        val item =
            optimisticMessages.values.firstOrNull { it.record.messageIdHex == messageId }
                ?: retainedTimelineItem(messageId) ?: return null
        val order = firstMessageOrder(sweepExpiryMessages().map { it.record.messageIdHex })
        val gone =
            item.projected?.deleted == true ||
                MessageProjector.isDeleted(messageId, deletedMessageIds) ||
                isTimelineRecordLocallyExpired(clockMillis(), item.record, localExpiryRow(item, order))
        return item.takeUnless { gone }
    }

    /**
     * Whether the retained row [messageId] is deleted or past its local disappearing-message deadline. The
     * timeline filters expired rows out without an engine event, so UI hosted outside the row asks here. A row
     * that is merely absent from the bounded window is unknown, not gone, and reads as false.
     */
    internal fun isRetainedRowGone(messageId: String): Boolean {
        val (item, row) = retainedExpiryRow(messageId) ?: return false
        val deleted = item.projected?.deleted == true || MessageProjector.isDeleted(messageId, deletedMessageIds)
        return deleted || isTimelineRecordLocallyExpired(clockMillis(), item.record, row)
    }

    /**
     * What a host watching the row [messageId] should do next, given the last deadline it saw for the row
     * ([rememberedDeadlineMillis]). Combines the retained row's state with the wall clock.
     */
    internal fun watchRetainedRow(
        messageId: String,
        rememberedDeadlineMillis: Long?,
    ): RetainedRowWatch =
        decideRetainedRowWatch(
            nowMillis = clockMillis(),
            retained = retainedTimelineItem(messageId) != null,
            gone = isRetainedRowGone(messageId),
            deadlineMillis = retainedRowExpiryDeadlineMillis(messageId),
            rememberedDeadlineMillis = rememberedDeadlineMillis,
        )

    /** Distinguishes timeout wakes from publish signals that expose due rows. */
    internal fun shouldRunForegroundSweepAfterWake(wakeSignalReceived: Boolean): Boolean =
        DisappearingMessageSweep.shouldRunForegroundSweepAfterWake(
            wakeSignalReceived = wakeSignalReceived,
            nowMillis = clockMillis(),
            lastSweepStartedAtMillis = lastForegroundSweepStartedAtMillis,
            rows = foregroundSweepExpiryRows(),
        )

    /** Retires this account's reads before native teardown; accepted mutations keep their app owner. */
    suspend fun closeLiveSubscriptionsForAccountTeardown(accountRef: String) {
        if (!shouldTeardownLiveSubscriptionsForAccount(accountRef, conversationAccountRef, conversationAccountRef)) return
        timelineSession.closeForAccountTeardown { memberRosterRefreshGeneration.advance() }
    }

    // Apply a fresh group-state snapshot/update, republishing the timeline when
    // the current policy changes so optimistic-send state is immediately
    // reconciled. Each row's pinned deadline remains independent of the current
    // policy, so historical rows are never reinterpreted by this republish.

    /** Test entry that installs [item] under its item id and in the membership index, as a projection apply does. */
    @VisibleForTesting
    internal fun retainTimelineItemForTest(item: TimelineMessage) {
        if (item.id !in timelineItemsById) insertTimelineItemId(item.id)
        timelineItemsById[item.id] = item
    }

    /** Test entry to the subscription-update application path. */
    @VisibleForTesting
    internal fun applyGroupStateForTest(update: AppGroupRecordFfi) = applyGroupState(update)

    /** Explicit retry for an advisory recovery-status read failure, which also re-reads the parked flag. */
    suspend fun retryGroupRecoveryStatus() {
        refreshGroupRecoveryStatus()
        requestGroupConvergenceRead()
    }

    /**
     * Re-reads the parked flag when the conversation returns to the foreground. MDK's event firehose skips events
     * when it lags, so a park or resume edge can be lost while the stream stays open; this keeps a dropped resume
     * from leaving a stale notice. It waits for the first open, which already reads the flag once.
     */
    suspend fun refreshGroupConvergenceOnStart() {
        if (groupConvergenceOpenSettled.isCompleted) requestGroupConvergenceRead()
    }

    /**
     * Asks MDK to resume this chat's parked convergence, then re-reads the parked flag to learn whether it worked.
     * While it runs the card shows progress and the other recovery actions wait. A chat that stays parked gets a
     * localized reason, never the engine's own text.
     */
    @Suppress("TooGenericExceptionCaught") // Every non-cancellation native failure is mapped to a localized reason.
    suspend fun retryGroupConvergence() {
        if (groupRecoveryMutationInFlight || !groupConvergenceParked) return
        val accountRef = conversationAccountRef ?: return
        val groupIdHex = group.groupIdHex
        val runtimeGeneration = appState.runtimeGeneration
        groupRecoveryMutationInFlight = true
        groupConvergenceRetrying = true
        groupConvergenceRetryFailure = null
        try {
            val failure =
                try {
                    groupConvergenceRetrier(accountRef, groupIdHex)
                    null
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (throwable: Throwable) {
                    Log.w("DMConversation", releaseFailureMarker("GROUP_CONVERGENCE_RETRY", throwable))
                    throwable
                }
            val stillParked = readGroupConvergenceParkedAfterRetry()
            if (ownsGroupRecoveryRead(accountRef, groupIdHex, runtimeGeneration)) {
                groupConvergenceRetryFailure = groupConvergenceRetryFailureMessage(failure, stillParked)
            }
        } finally {
            groupConvergenceRetrying = false
            groupRecoveryMutationInFlight = false
        }
    }

    /** Dismisses this group's shown notice ids, keeping the action busy until refresh completes. */
    suspend fun dismissGroupHistoryNotices() {
        val accountRef = conversationAccountRef
        val noticeIds = groupRecoveryStatus?.historyNoticeIds.orEmpty()
        if (groupRecoveryMutationInFlight || accountRef == null || noticeIds.isEmpty()) return
        val groupIdHex = group.groupIdHex
        val recoveryEpoch = groupRecoveryLifetime.capture()
        groupRecoveryMutationInFlight = true
        try {
            val firstFailure =
                dismissHistoryNoticeIds(noticeIds) { id ->
                    historyNoticeDismisser(accountRef, id)
                }
            var shouldRefresh = false
            groupRecoveryLifetime.runIfCurrent(recoveryEpoch) {
                shouldRefresh = ownsGroupRecoveryGroup(groupIdHex) && conversationAccountRef == accountRef
            }
            if (shouldRefresh) {
                refreshGroupRecoveryStatus()
                firstFailure?.let { failure ->
                    groupRecoveryLifetime.runIfCurrent(recoveryEpoch) {
                        if (ownsGroupRecoveryGroup(groupIdHex) && conversationAccountRef == accountRef) {
                            recordMutationFailure(
                                R.string.history_notice_dismiss_failed,
                                "GROUP_HISTORY_NOTICE_DISMISS",
                                failure,
                            )
                        }
                    }
                }
            }
        } finally {
            // Single-flight excludes another recovery mutation until this operation releases its slot.
            groupRecoveryMutationInFlight = false
        }
    }

    /** Accepts exactly the still-present invitation that the user confirmed. */
    suspend fun confirmGroupRejoin(invitation: GroupRejoinInvitationFfi) {
        if (groupRecoveryMutationInFlight) return
        val accountRef = conversationAccountRef
        val current = groupRecoveryStatus?.matchingRejoinInvitation(invitation)
        if (accountRef == null || current == null) return
        val groupIdHex = group.groupIdHex
        val recoveryEpoch = groupRecoveryLifetime.capture()
        groupRecoveryMutationInFlight = true
        try {
            val status =
                appState.marmotIo {
                    confirmGroupRejoin(accountRef, current.welcomeIdHex, current.localStateToken)
                }
            val rejoined = firstGroupNoticeRejoined(accountRef, status)
            var published = false
            groupRecoveryLifetime.runIfCurrent(recoveryEpoch) {
                if (ownsGroupRecoveryGroup(groupIdHex) && status.groupIdHex == groupIdHex) {
                    groupRecoveryStatus = status
                    groupHistoryNoticeRejoined = rejoined
                    groupRecoveryReadFailed = false
                    presentConversationTransient(R.string.group_rejoin_success)
                    published = true
                }
            }
            if (published) {
                runCatchingCancellable { refreshMembers() }
                runCatchingCancellable { refreshCurrentTimeline(accountRef) }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            refreshGroupRecoveryStatus()
            recordMutationFailure(R.string.group_rejoin_failed, "GROUP_REJOIN_CONFIRM", throwable)
        } finally {
            groupRecoveryMutationInFlight = false
        }
    }

    /** Declines exactly the still-present invitation selected by the user. */
    suspend fun declineGroupRejoin(invitation: GroupRejoinInvitationFfi) {
        if (groupRecoveryMutationInFlight) return
        val accountRef = conversationAccountRef
        val current = groupRecoveryStatus?.matchingRejoinInvitation(invitation)
        if (accountRef == null || current == null) return
        val groupIdHex = group.groupIdHex
        val recoveryEpoch = groupRecoveryLifetime.capture()
        groupRecoveryMutationInFlight = true
        try {
            appState.marmotIo {
                declineGroupRejoin(accountRef, current.welcomeIdHex)
            }
            groupRecoveryLifetime.runIfCurrent(recoveryEpoch) {
                if (ownsGroupRecoveryGroup(groupIdHex)) {
                    // Only the declined invitation leaves. The notice ids, and with them the rejoin wording, stay
                    // as they are until the refresh below rereads both.
                    groupRecoveryStatus =
                        groupRecoveryStatus?.copy(
                            rejoinInvitations =
                                groupRecoveryStatus?.rejoinInvitations.orEmpty().filterNot {
                                    it.welcomeIdHex == current.welcomeIdHex
                                },
                        )
                }
            }
            refreshGroupRecoveryStatus()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            refreshGroupRecoveryStatus()
            recordMutationFailure(R.string.group_rejoin_failed, "GROUP_REJOIN_DECLINE", throwable)
        } finally {
            groupRecoveryMutationInFlight = false
        }
    }

    /**
     * Cancel controller-owned scopes that outlive a single [start] call. The
     * conversation screen calls this once when it disposes the controller.
     *
     * [inviteStreamScope] launches post-accept warm-up from [acceptInvite],
     * invoked from a separate mutation scope that can fire after [start]'s loops
     * have ended while the invite screen is still composed, so it must outlive
     * [start]'s teardown (#279).
     */
    fun onCleared() {
        cancelMessageEdit()
        timelineSession.dispose {
            memberRosterRefreshGeneration.advance()
            timelineWindowGeneration.advance()
        }
        windowPresentationTiming.cancel()
        inboundVisibleHostAttempt.cancel()
        outboundVisibleHostAttempts.cancelAll()
        inviteStreamScope.cancel()
        attachmentPresentation.dispose()
    }

    /** Transfers every just-published optimistic row to the next conversation reveal frame. */
    internal fun claimVisibleHostAttempts(): HostPerformanceAttemptBatch = outboundVisibleHostAttempts.claimAll()

    internal fun matchesConversation(
        accountRef: String?,
        groupIdHex: String,
    ): Boolean = conversationAccountRef == accountRef && group.groupIdHex == groupIdHex

    /** Rejects UI callbacks after their conversation or account lifecycle has ended. */
    internal fun acceptsConversationActionOwner(
        accountRef: String?,
        groupIdHex: String,
    ): Boolean = !controllerCleared && !isAccountTeardownRequested() && matchesConversation(accountRef, groupIdHex)

    /** Captures only pages already received by the current local subscription attempt. */
    internal fun pendingTimelineAtForeground(): CompletableDeferred<Boolean>? = timelineSession.pendingAtForeground()

    /** Applies a matching native row and invalidates any null-result acknowledgement superseded by that row. */
    internal fun applyAuthoritativeChatListRow(
        accountRef: String?,
        row: ChatListRowFfi,
    ) {
        if (!matchesConversation(accountRef, row.groupIdHex)) return
        // A new native row supersedes a null-result acknowledgement, including
        // a new reminder whose preceding manual=false echo was not observed.
        if (successfulReadWithoutRow?.let { it.first !== row } == true) {
            successfulReadWithoutRow = null
            manualUnreadReminderConsumed = false
        }
        if (!row.manuallyMarkedUnread || latestChatListRow?.manuallyMarkedUnread != true) {
            manualUnreadReminderConsumed = false
        }
        latestChatListRow = row
    }

    internal fun acceptConfirmedMediaHandoff(
        confirmedId: String,
        deferredProjection: TimelineMessageRecordFfi?,
    ): Boolean {
        assertMainThread { "acceptConfirmedMediaHandoff" }
        val projected =
            if (controllerCleared) {
                null
            } else {
                deferredProjection ?: timelineRecords[confirmedId]
            }
        return if (projected == null) {
            false
        } else {
            pendingProjectionsAwaitingBridge.remove(confirmedId)
            timelineRecords[confirmedId] = projected
            projectedMessageIds.add(confirmedId)
            val projectedAction = TimelineProjector.toAppMessageRecord(projected)
            messageById[confirmedId] = projectedAction
            val projectedItem =
                timelineMessageFromProjection(
                    record = projected,
                    actionRecord = projectedAction,
                    retentionAtSendSeconds =
                        retentionAtSendForProjection(
                            messageId = confirmedId,
                            projectedRetentionSeconds = projectedAction.retentionSeconds,
                            optimisticMessageId = confirmedId,
                        ),
                )
            if (projectedItem.id !in timelineItemsById) {
                insertTimelineItemId(projectedItem.id)
            }
            timelineItemsById[projectedItem.id] = projectedItem
            optimisticMessages.remove("msg:$confirmedId")
            publishTimelineFromIndexes()
            true
        }
    }

    /** Consumes authoritative MDK windows from [feed] and publishes only the newest coalesced window. */
    internal suspend fun CoroutineScope.runTimelineSubscriptionPipeline(feed: ConversationTimelineSubscriptionHandle) {
        val timelineWindows = Channel<RecoveryStampedTimelineWindow>(capacity = Channel.BUFFERED)
        val handoff = ConversationTimelineHandoff()
        timelineSession.installHandoff(handoff)
        var observedProducerEnd = false
        val pump =
            async {
                // The seam absorbs MDK's window errors, so reaching this catch means an unexpected failure.
                // It still must not cancel the conversation scope: ending the pump lets the subscription
                // loop reconnect, where a crash would take the whole app down (#2616 device reports).
                try {
                    while (isActive) {
                        val received =
                            runCatchingCancellable {
                                withContext(Dispatchers.IO) {
                                    feed.nextWindow()?.let { page -> page to handoff.received() }
                                }
                            }.getOrNull() ?: break
                        timelineWindows.send(
                            RecoveryStampedTimelineWindow(
                                page = received.first,
                                recoveryGeneration = appState.recoveryDiagnostics.recordTimelineSubscriptionReceived(),
                                receivedAtElapsedMs = SystemClock.elapsedRealtime(),
                                productObservationTicket = appState.productObservationTicket(),
                                foregroundHandoff = received.second,
                            ),
                        )
                    }
                } finally {
                    timelineWindows.close()
                }
            }
        try {
            while (isActive) {
                val first = timelineWindows.receiveCatching().getOrNull()
                if (first == null) {
                    observedProducerEnd = true
                    break
                }
                // Drain any windows that arrived within roughly one
                // 120Hz frame budget into a single batch. The runtime
                // can emit several complete windows back-to-back during a
                // sync burst; only the newest can be authoritative. Batching
                // collapses them into one replacement publish. The
                // timeout only wraps the local channel receive; the
                // UniFFI next() call above is always awaited to
                // completion so a timed-out drain can't consume and
                // drop a Rust subscription window.
                val batch = mutableListOf(first)
                while (batch.size < TIMELINE_BATCH_CAP) {
                    val available = timelineWindows.tryReceive()
                    val received =
                        if (available.isSuccess || available.isClosed) {
                            available
                        } else {
                            withTimeoutOrNull(TIMELINE_BATCH_DRAIN_MS) {
                                timelineWindows.receiveCatching()
                            }
                        }
                    if (received?.isClosed == true) observedProducerEnd = true
                    val more = received?.getOrNull() ?: break
                    batch += more
                }
                val newest = batch.last()
                if (
                    newest.page.messages.any { message ->
                        message.direction != "sent" &&
                            message.messageIdHex !in timelineRecords &&
                            !MessageProjector.isControlMutationKind(message.kind)
                    }
                ) {
                    inboundVisibleHostAttempt.replace(
                        appState.beginHostPerformance(
                            HostPerformanceOperationFfi.INBOUND_MESSAGE_VISIBLE,
                            newest.receivedAtElapsedMs,
                        ),
                    )
                    // This observable ticket restarts the frame owner even when an older inbound
                    // row leaves the chronological tail id unchanged.
                    inboundVisibleHostGeneration += 1
                }
                appState.recordHostPerformanceSince(
                    HostPerformanceOperationFfi.TIMELINE_HANDOFF,
                    newest.receivedAtElapsedMs,
                )
                windowPresentationTiming.begin(
                    receivedAtElapsedMs = batch.first().receivedAtElapsedMs,
                    ticket = batch.first().productObservationTicket,
                )
                var batchCommitted = false
                // Cards are read before the apply timing starts, so TIMELINE_APPLY stays the apply alone.
                val newestPage = warmLaterPageEventCards(newest.page)
                appState.measureHostPerformance(HostPerformanceOperationFfi.TIMELINE_APPLY) {
                    applyTimelinePage(
                        newestPage,
                        replaceWindow = false,
                        updatePagination = true,
                        reconcileNewExtendedRecords = true,
                        onCommitted = {
                            batchCommitted = true
                            handoff.settled(batch.map { it.foregroundHandoff }, applied = true)
                        },
                    )
                }
                publishReadyTimelineSubscription(feed)
                // Older recovery is revision-gated inside the commit: a queued command echo must
                // not release its own failed attempt. Preserve forward prefetch's arrival budget.
                automaticPaging.newer.reset()
                publishRecoveryTimelineProjection(batch.mapNotNull { it.recoveryGeneration }.maxOrNull())
                if (!batchCommitted) handoff.settled(batch.map { it.foregroundHandoff }, applied = false)
                // Scroll-driven mark-read in the UI layer handles
                // the user-visible read pointer.
            }
        } finally {
            timelineSession.retireHandoff(handoff)
            // Channel close precedes coroutine completion. Let an ended producer finish normally;
            // cancelling in that gap makes await() throw and bypasses initial metadata settlement.
            if (!observedProducerEnd && pump.isActive) {
                pump.cancel()
            }
        }
        pump.await()
    }

    /**
     * Send a text message. [onAccepted] runs once the optimistic bubble has
     * been committed to the projection and published — i.e. the send has
     * visibly started. [onDurablyAccepted] runs only after MDK returns a typed
     * accepted disposition, so the caller can delete the persisted composer
     * draft without losing a pre-acceptance send across process death.
     * [onTerminalFailure] distinguishes a definite failure from an ambiguous
     * delivery that remains Pending. None of the callbacks run when a guard
     * rejects the send (no account yet, blank text, unknown/non-member state,
     * a terminal group, or a reply to a message that has no event id yet).
     * The edit path also leaves [onAccepted] uncalled: the composer restores its
     * pre-edit draft via the `editingMessageId` LaunchedEffect, not by clearing.
     * Set [consumeReply] to false for a separate picker send that must retain the composer's pending reply.
     */
    suspend fun send(
        text: String,
        onAccepted: () -> Unit = {},
        onTerminalFailure: () -> Unit = {},
        onPresentationAccepted: (String) -> Unit = {},
        consumeReply: Boolean = true,
        onDurablyAccepted: () -> Unit = {},
    ) {
        val trimmed = text.trim()
        val account = textSendAccount(text, trimmed) ?: return
        val editTarget = editingMessageId
        if (editTarget != null) {
            submitComposerEdit(editTarget, trimmed)
            return
        }
        val replyTarget = replyTargetForSend(replyingTo?.messageIdHex?.takeIf { consumeReply }).getOrElse { return }
        val sendHostAttempt = appState.beginHostPerformance(HostPerformanceOperationFfi.MESSAGE_SEND)
        val outboundVisibleAttempt = appState.beginHostPerformance(HostPerformanceOperationFfi.OUTBOUND_MESSAGE_VISIBLE)

        // WNPerf assigns an opaque process-local operation id only while the
        // user has explicitly enabled the bounded diagnostic session.
        val trace = PerformanceDiagnostics.begin(PerformanceOperation.TEXT_SEND)
        sendTrace(trace, PerformancePhase.ACCEPTED, elapsedMs = 0L, result = PerformanceResult.PENDING)
        // Each first send mints its client token here; a manual retry reuses the failed row's token.
        val tempId = UUID.randomUUID().toString()
        outboundVisibleHostAttempts.register(tempId, outboundVisibleAttempt)
        appState.pendingSendDiagnostics.track(tempId, trace)
        val row = insertOptimisticTextSend(tempId, trimmed, replyTarget, onDurablyAccepted)
        if (consumeReply) replyingTo = null
        // The optimistic bubble is now in the projection and published — the
        // send has visibly started. Only now is it safe to clear the input and
        // draft (issue #264): clearing earlier, synchronously in the UI on the
        // mere act of dispatching this coroutine, lost the text whenever a
        // guard above bailed before this point.
        onPresentationAccepted(row.key)
        onAccepted()
        // The optimistic bubble is now published: the pending clock is on
        // screen. The chat-list order is reserved, but its preview waits for
        // the parser so raw Markdown never flashes there.
        sendTrace(trace, PerformancePhase.OPTIMISTIC_SHOWN, result = PerformanceResult.PENDING)
        appState.pendingSendDiagnostics.update(tempId, PerformanceSendStage.OPTIMISTIC)
        // Reconciliation and failure retention use the completed presentation,
        // but neither presentation step gates native admission.
        var publishedRecord = row.record
        try {
            val summary =
                coroutineScope {
                    // Enter the existing send-order/commit locks before presentation
                    // can suspend. Keep rejection as data until the styled failure
                    // row is ready; cancellation still cancels both branches.
                    val admission =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            runCatchingCancellable {
                                publishTextWithRetry(replyTarget, account, trimmed, trace, tempId, row.key)
                                    .also { completeDurableAcceptance(row.key) }
                            }
                        }
                    publishedRecord = hydrateOptimisticSendMarkdown(row.key, row.record)
                    applyOptimisticSendPreview(row, publishedRecord, trace)
                    admission.await().getOrThrow()
                }
            val reconciliation = settleAcceptedTextSend(summary, row.key, tempId, publishedRecord, row.order)
            // The publish returned and the pending clock flips to sent here only
            // when the projected engine echo has not already landed. If echo
            // reconciliation consumed the temp bubble first, `echo-reconcile` is
            // the actual clock → tick latency and this later event is just send
            // completion; don't label it as another `sent-flip`.
            sendTrace(
                trace,
                if (reconciliation.insertedSent) PerformancePhase.SENT_FLIP else PerformancePhase.SEND_COMPLETE,
            )
            // When we keep the temp bubble for echo reconciliation, leave the
            // trace entry so `echo-reconcile` can still be logged.
            if (!reconciliation.awaitingProjection) {
                appState.pendingSendDiagnostics.forget(tempId)
            }
            if (!reconciliation.acceptedPending) {
                optimisticSendPhases.remove(row.key)
            }
            sendHostAttempt.success()
        } catch (throwable: Throwable) {
            handleTextSendFailure(throwable, row, publishedRecord, account, trace, sendHostAttempt, onTerminalFailure)
        } finally {
            sendHostAttempt.unavailable()
        }
    }

    /**
     * Send one or more attachments as a single kind:9 album. MIME-agnostic:
     * images, documents (PDF/zip/etc.), audio, video — anything the picker
     * surfaces — funnel through this same path. Callers are responsible for
     * any MIME-specific pre-processing (image downscale via
     * [MediaPipeline.readDownscaledJpeg]; documents pass through as-is)
     * because the FFI encrypts the bytes exactly as supplied.
     *
     * All attachments upload via one `uploadMedia(list)` FFI call and publish
     * via one `sendMediaAttachments(list, caption)`; the receiving group sees
     * a single message carrying N imeta tags. A single-attachment call is
     * the degenerate case (list of one) and routes through the same path.
     */
    suspend fun sendAttachments(
        attachments: List<PendingAttachment>,
        caption: String?,
    ) {
        val seeded =
            queueAttachments(
                attachments = attachments,
                caption = caption,
                outboundVisibleStartedAtElapsedMs = SystemClock.elapsedRealtime(),
            ) ?: return
        uploadQueued(seeded)
    }

    /**
     * Slot-allocated for a queued attachment send: holds the temp message id,
     * the optimistic record, and the timeline-order key so a caller that
     * batched several queues can drive the uploads in pick-order without
     * losing the synchronously-seeded bubbles.
     */
    data class QueuedAttachmentSend(
        val account: String,
        val key: String,
        val tempId: String,
        val optimisticOrder: ULong,
        val optimistic: AppMessageRecordFfi,
    )

    /**
     * Synchronous half of a media send: validates the album, allocates a
     * temp id, retains the bytes for retry, inserts the optimistic bubble,
     * and republishes the timeline so the bubble appears immediately.
     * Returns null when the send can't proceed (no account, can't send,
     * empty, oversize, or a reply with no event id yet). Caller pairs each non-null result with a
     * matching [uploadQueued] call to drive the FFI work. [canQueue] rechecks a caller's
     * presentation owner after Markdown preparation, before publishing any optimistic state.
     */
    @Suppress(
        "LongMethod",
        "ReturnCount",
        "TooGenericExceptionCaught",
    ) // Admission guards precede the single optimistic publication and cancel timing on any preparation failure.
    suspend fun queueAttachments(
        attachments: List<PendingAttachment>,
        caption: String?,
        canQueue: () -> Boolean = { true },
        outboundVisibleStartedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
        replyTarget: String? = replyingTo?.messageIdHex,
        replyVersion: Long = replySelectionVersion,
    ): QueuedAttachmentSend? {
        if (!canQueue()) return null
        val account =
            conversationAccountRef
                ?.takeIf {
                    shouldAcceptMediaUploadForAccount(
                        it,
                        mediaUploadSessionEpoch,
                        appState.activeAccountRef,
                        appState.mediaUploadSessionEpoch(),
                    )
                }
                ?: return null
        val target = replyTargetForSend(replyTarget).getOrElse { return null }
        if (!canQueueAttachmentBytes(attachments, target)) return null
        val tempId = UUID.randomUUID().toString()
        val key = "msg:$tempId"
        val outboundVisibleAttempt =
            appState.beginHostPerformance(
                HostPerformanceOperationFfi.OUTBOUND_MESSAGE_VISIBLE,
                outboundVisibleStartedAtElapsedMs,
            )
        val now = nowSeconds()
        val retentionSnapshot = group.disappearingMessageSecs
        val trimmedCaption = caption?.trim()?.takeIf { it.isNotBlank() }
        val placeholderName =
            if (attachments.size == 1) {
                attachments.first().fileName
            } else {
                "${attachments.size} attachments"
            }
        val body = trimmedCaption ?: "📎 $placeholderName"
        val optimistic =
            try {
                appState.measureHostPerformance(HostPerformanceOperationFfi.MEDIA_PREPARE) {
                    pendingAttachmentRecord(
                        tempId = tempId,
                        body = body,
                        attachments = attachments,
                        now = now,
                        replyTarget = target,
                    )
                }
            } catch (throwable: Throwable) {
                outboundVisibleAttempt.cancel()
                throw throwable
            }
        // Markdown preparation can suspend: a reviewed take must still belong to its visible owner.
        if (!canQueueMediaForAccount(account, canQueue)) {
            outboundVisibleAttempt.cancel()
            return null
        }
        val replyRevision = target?.let { prepareQueuedMediaReply(account, it, trimmedCaption, attachments) }
        val replyStagingFailed = target != null && replyRevision == null
        if (replyStagingFailed || !canQueueMediaForAccount(account, canQueue)) {
            outboundVisibleAttempt.cancel()
            if (replyStagingFailed) appState.present(R.string.media_reply_draft_conflict)
            return null
        }
        val retentionAtSendSeconds = rememberRetentionAtSend(tempId, retentionSnapshot)
        val optimisticOrder = nextOptimisticTimelineOrder()
        retainedMediaUploads.put(
            key,
            RetainedMediaUpload(attachments, trimmedCaption, target, replyRevision, replyVersion),
        )
        // Mark this slot as "still needed by a pending send" so the screen
        // dispose hook's `clearRetainedUploads` won't wipe bytes for slots
        // queued behind the one currently uploading.
        activeUploadKeys.add(key)
        appState.trackInFlightMediaUpload(conversationAccountRef, group.groupIdHex, key)
        optimisticMessages[key] =
            TimelineMessage(
                key,
                optimistic,
                MessageStatus.Pending,
                timelineOrder = optimisticOrder,
                retentionAtSendSeconds = retentionAtSendSeconds,
            )
        optimisticSendPhases[key] = OptimisticSendPhase.PRE_ACCEPTANCE
        messageById[tempId] = optimistic
        // Do not expose this attempt to an unrelated reveal while preparation is suspended. Once
        // registered, the optimistic row is published synchronously in the same main-thread turn.
        outboundVisibleHostAttempts.register(tempId, outboundVisibleAttempt)
        publishTimelineFromIndexes()
        // Media sends bump the chat-list row like text sends do: the
        // optimistic body is the caption or the attachment placeholder, so the
        // row and the bubble read the same. The engine echo folds by the
        // confirmed id recorded at commit time, so the placeholder never
        // outlives reconciliation.
        appState.applyOptimisticSentPreview(
            conversationAccountRef,
            group.groupIdHex,
            sentPreview(tempId, body, optimistic.contentTokens, now),
        )
        appState.pendingSendDiagnostics.startMediaSend(tempId)
        return QueuedAttachmentSend(account, key, tempId, optimisticOrder, optimistic)
    }

    /** Attaches Android source cleanup to a queued item's existing retained-byte owner. */
    internal fun retainQueuedAttachmentSource(
        seeded: QueuedAttachmentSend,
        release: () -> Unit,
    ): Boolean = retainedMediaUploads.get(seeded.key)?.retainSource(release) == true

    /**
     * Drive the upload + publish for a previously [queueAttachments]-seeded slot.
     * [onDurablyAccepted] survives controller replacement and runs once MDK
     * accepts the logical send, including accepted-pending ownership.
     */
    suspend fun uploadQueued(
        seeded: QueuedAttachmentSend,
        onDurablyAccepted: (() -> Unit)? = null,
    ) {
        // Every seeded slot, including one waiting for an earlier upload, survives
        // dispose-time cleanup. Terminal performMediaUpload paths remove the key.
        onDurablyAccepted?.let { durableAcceptanceCallbacks.putIfAbsent(seeded.key, it) }
        performMediaUpload(seeded.account, seeded.key, seeded.tempId, seeded.optimisticOrder, seeded.optimistic)
    }

    /** Optimistically adds or removes [emoji], then reconciles the authoritative Marmot mutation. */
    suspend fun toggleReaction(
        emoji: String,
        message: AppMessageRecordFfi,
        onLocalIntent: (Boolean) -> Unit = {},
        stillAvailable: () -> Boolean = { true },
    ) {
        val account = reactionAccountIfAccepted() ?: return
        val target =
            message.messageIdHex.takeIf { it.isNotBlank() }
                ?: run {
                    appState.present(R.string.toast_reaction_failed)
                    return
                }
        val key = target.lowercase() to emoji
        val owner = ReactionMutationTarget(account, target, stillAvailable)
        val initialMine = admitReactionTap(owner, emoji, onLocalIntent) ?: return
        val optimisticId = reactionIntentOverlayId(target, emoji)

        val outcome =
            try {
                convergeReactionIntent(owner, emoji, key, initialMine)
            } catch (cancel: CancellationException) {
                recomputeReactions(optimisticReactionChanges.clearReactionIntent(optimisticId, target))
                throw cancel
            }

        when (outcome) {
            is ReactionIntentDrainOutcome.Failed -> {
                recomputeReactions(optimisticReactionChanges.clearReactionIntent(optimisticId, target))
                appState.presentReactionMutationFailure(outcome.throwable)
            }
            is ReactionIntentDrainOutcome.Settled -> {
                if (!outcome.mutated) {
                    recomputeReactions(optimisticReactionChanges.clearReactionIntent(optimisticId, target))
                }
                if (outcome.addedReaction) markReactionTargetRead(target)
            }
        }
    }

    suspend fun deleteMessage(
        message: AppMessageRecordFfi,
        presentFailure: Boolean = true,
        optimisticKeyOverride: String? = null,
    ): Boolean = deleteMessageResult(message, presentFailure, optimisticKeyOverride).isSuccess

    /** Structured variant used by batch delete so retry diagnostics retain a safe failure category. */
    internal suspend fun deleteMessageResult(
        message: AppMessageRecordFfi,
        presentFailure: Boolean = true,
        optimisticKeyOverride: String? = null,
    ): Result<Unit> {
        val account = conversationAccountRef
        return when {
            account == null -> Result.failure(IllegalStateException("Conversation account unavailable"))
            isOptimisticMessageId(message.messageIdHex) -> {
                val result = cancelOptimisticSendResult(message, optimisticKeyOverride)
                if (result.isFailure && presentFailure) {
                    appState.presentFailure(
                        R.string.toast_couldnt_delete_message,
                        "MESSAGE_CANCEL",
                        requireNotNull(result.exceptionOrNull()),
                    )
                }
                result
            }
            // Same capability model the delete surface renders from; re-checked
            // here so the mutation path stays authoritative even if a stale or
            // forged UI state requests an unauthorized scope. Also makes repeat
            // requests idempotent: once the optimistic tombstone is set, the
            // capability reads alreadyDeleted and the second call is a no-op.
            !deleteCapabilityFor(message).canDeleteForEveryone ->
                Result.failure(SecurityException("Delete capability unavailable"))
            else -> {
                val target = message.messageIdHex
                deletedMessageIds = deletedMessageIds + target
                try {
                    deleteMessageForEveryone(account, target) {
                        appState.marmotIo { deleteMessage(account, group.groupIdHex, target) }
                    }
                    Result.success(Unit)
                } catch (throwable: Throwable) {
                    deletedMessageIds = deletedMessageIds - target
                    throwable.rethrowIfCancellation()
                    if (presentFailure) {
                        appState.presentFailure(R.string.toast_couldnt_delete_message, "MESSAGE_DELETE", throwable)
                    }
                    Result.failure(throwable)
                }
            }
        }
    }

    suspend fun hideMessageForMe(
        messageIdHex: String,
        presentFailure: Boolean = true,
    ): Boolean {
        val result = hideMessageForMeResult(messageIdHex)
        if (result.isFailure && presentFailure) {
            appState.presentFailure(
                R.string.toast_couldnt_delete_message,
                "MESSAGE_HIDE_LOCAL",
                result.exceptionOrNull() ?: IllegalStateException("Local message hide failed"),
            )
        }
        return result.isSuccess
    }

    /** Account-bound local-hide variant used by batch retry accounting. */
    internal suspend fun hideMessageForMeResult(messageIdHex: String): Result<Unit> {
        val account = conversationAccountRef
        val target = messageIdHex.takeIf { it.isNotBlank() }
        return when {
            account == null -> Result.failure(IllegalStateException("Conversation account unavailable"))
            target == null -> Result.failure(IllegalArgumentException("Message id unavailable"))
            else ->
                try {
                    if (appState.hideMessageForMe(account, group.groupIdHex, target)) {
                        publishTimelineFromIndexes()
                        Result.success(Unit)
                    } else {
                        Result.failure(java.io.IOException("Local message hide was not persisted"))
                    }
                } catch (throwable: Throwable) {
                    throwable.rethrowIfCancellation()
                    Result.failure(throwable)
                }
        }
    }

    fun acknowledgeTimelineRemovals(messageIds: Set<String>) {
        pendingTimelineRemovedMessageIds = pendingTimelineRemovedMessageIds - messageIds
    }

    internal val pendingEditAdmissions = mutableSetOf<String>()
    internal val pendingEditAdmissionMutex = Mutex()

    // Only in-flight callbacks retain this proof; finally removes it even on cancellation.
    internal val pendingEditPublicationObserved = mutableSetOf<String>()
    internal val pendingEditStatusReads = mutableSetOf<String>()
    internal val pendingEditStatusRechecks = mutableSetOf<String>()

    /**
     * Latest text to display for a kind-9 chat row: the most-recent
     * kind-1009 edit's content when one exists, otherwise the original
     * plaintext. Bubble + reply preview both read through this so an edit
     * shows everywhere the original would have.
     */
    fun displayedText(record: AppMessageRecordFfi): String =
        optimisticEdits[record.messageIdHex]?.text
            ?: editsByTarget[record.messageIdHex]?.latestText
            ?: record.plaintext

    // Tracks optimistic ids the user discarded while a retry was in flight.
    // The retry coroutine consults this set before re-inserting a failed
    // record, so a discard during retry doesn't get clobbered by the catch
    // path putting the message back as Failed.
    internal val discardedDuringRetry = mutableSetOf<String>()

    /** Shared wakeup lets a replacement controller release an old controller's retry. */
    internal val optimisticCancellationGeneration =
        appState.optimisticCancellationGeneration(conversationAccountRef, initialGroup.groupIdHex)

    internal fun rollbackOptimisticChatListPreview(optimisticMessageIdHex: String) {
        appState.rollbackOptimisticSentPreview(conversationAccountRef, group.groupIdHex, optimisticMessageIdHex)
    }

    /**
     * Compressed bytes for in-flight / failed outgoing attachments, keyed by
     * the optimistic timeline id (`"msg:<tempId>"`). Retained so a failed
     * upload can be retried in place (no re-attach), so the sender's own
     * bubble can be seeded into the app-level decrypted cache on confirm, and
     * so the optimistic bubble can preview the local image while uploading.
     * Bounded by bytes so undiscarded failures can't accrete unbounded heap.
     *
     * Hoisted to app-level state keyed on `(account, group)` so a NEW
     * ConversationController created after a navigation-out/return cycle
     * picks up the same instance. A controller-local map would leave the
     * returning user looking at an empty pending bubble — the bytes the
     * bubble preview needs would be on the GC-pending old controller.
     */
    internal val retainedMediaUploads =
        appState.retainedMediaUploads(conversationAccountRef, initialGroup.groupIdHex)
    internal val activeUploadKeys =
        appState.activeUploadKeys(conversationAccountRef, initialGroup.groupIdHex)

    /**
     * App-level cache key for a decrypted attachment. Scoped to
     * account+group+message (not bare messageIdHex) so a cache entry can only
     * ever satisfy a lookup from the same account and group that decrypted it —
     * defense-in-depth against an evicted/rejoined member replaying an old
     * event id to read plaintext it shouldn't.
     */
    internal fun mediaCacheKey(
        account: String,
        messageIdHex: String,
        attachmentIndex: Int,
    ): String = attachmentPresentation.mediaCacheKey(account, messageIdHex, attachmentIndex)

    /** Peeks at the existing cache without starting a native transfer. */
    fun hasCachedAttachment(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean = attachmentPresentation.hasCachedAttachment(messageIdHex, attachmentIndex)

    /** Hydrates the disk index off main before reporting availability. */
    suspend fun hasCachedAttachmentAfterHydration(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean = attachmentPresentation.hasCachedAttachmentAfterHydration(messageIdHex, attachmentIndex)

    /** Acquires one attachment observer under this conversation presentation owner. */
    internal fun attachmentTransferState(
        messageIdHex: String,
        attachmentIndex: Int,
        initiallyAvailable: Boolean,
    ): StateFlow<AttachmentTransferState> =
        attachmentPresentation.attachmentTransferState(
            messageIdHex,
            attachmentIndex,
            initiallyAvailable,
        )

    /** Releases the row observer independently of app/native transfer work. */
    internal fun releaseAttachmentTransferState(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Unit = attachmentPresentation.releaseAttachmentTransferState(messageIdHex, attachmentIndex)

    /** Waits for a fresh availability event for this exact attachment. */
    internal suspend fun awaitNextAttachmentAvailability(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Unit = attachmentPresentation.awaitNextAttachmentAvailability(messageIdHex, attachmentIndex)

    /** Joins one presentation transfer while native admission retains its app owner. */
    internal fun requestAttachmentTransfer(
        messageIdHex: String,
        attachmentIndex: Int,
        reference: MediaAttachmentReferenceFfi,
        retainedPlaintext: ByteArray? = null,
        priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Interactive,
    ): Deferred<ByteArray> =
        attachmentPresentation.requestAttachmentTransfer(
            messageIdHex,
            attachmentIndex,
            reference,
            retainedPlaintext,
            priority,
        )

    /** Starts a durable in-place download of a visible attachment that never opens it once it arrives. */
    internal fun requestAttachmentDownload(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean = attachmentPresentation.requestAttachmentDownload(messageIdHex, attachmentIndex)

    /** Captures an open intent for the visible attachment destination. */
    internal fun requestAttachmentOpen(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean = attachmentPresentation.requestAttachmentOpen(messageIdHex, attachmentIndex)

    /** Finds the current visible destination for this attachment. */
    internal fun attachmentOpenRequest(
        messageIdHex: String,
        attachmentIndex: Int,
    ): AttachmentOpenRequest? = attachmentPresentation.attachmentOpenRequest(messageIdHex, attachmentIndex)

    /** Whether this attachment still has an armed in-place download. */
    internal fun hasAttachmentDownloadIntent(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean = attachmentPresentation.hasAttachmentDownloadIntent(messageIdHex, attachmentIndex)

    /** Retires an in-place download once its bytes are ready, without opening anything. */
    internal suspend fun consumeAttachmentDownloadIntent(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean = attachmentPresentation.consumeAttachmentDownloadIntent(messageIdHex, attachmentIndex)

    /** Resolves an optimistic reference before native download/save admission. */
    internal suspend fun authoritativeAttachmentReference(
        messageIdHex: String,
        attachmentIndex: Int,
        fallback: MediaAttachmentReferenceFfi,
    ): MediaAttachmentReferenceFfi =
        attachmentPresentation.authoritativeAttachmentReference(
            messageIdHex,
            attachmentIndex,
            fallback,
        )

    /** Materializes plaintext within the requested presentation budget. */
    internal suspend fun downloadAttachment(
        messageIdHex: String,
        attachmentIndex: Int,
        reference: MediaAttachmentReferenceFfi,
        priority: AttachmentDownloadPriority,
        maxBytes: Long = ATTACHMENT_PRESENTATION_MAX_BYTES,
    ): ByteArray =
        attachmentPresentation.downloadAttachment(
            messageIdHex,
            attachmentIndex,
            reference,
            priority,
            maxBytes,
        )

    /** Returns a decoded thumbnail from the existing app cache. */
    fun thumbnailFor(
        messageIdHex: String,
        attachmentIndex: Int,
    ): android.graphics.Bitmap? = attachmentPresentation.thumbnailFor(messageIdHex, attachmentIndex)

    /** Keeps a displayed kept-card thumbnail eligible for rereads until its surface releases the hold. */
    internal fun retainThumbnail(
        messageIdHex: String,
        attachmentIndex: Int,
    ): AutoCloseable? =
        attachmentPresentation.retainThumbnail(
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
        )

    /** Returns cached pixels together with the largest request they can satisfy. */
    internal fun thumbnailEntryFor(
        messageIdHex: String,
        attachmentIndex: Int,
        requestedMaxEdgePx: Int = 0,
    ): dev.ipf.whitenoise.android.media.MediaThumbnail? =
        attachmentPresentation.thumbnailEntryFor(
            messageIdHex,
            attachmentIndex,
            requestedMaxEdgePx,
        )

    /** Stores a decoded thumbnail under the pinned cache identity. */
    fun cacheThumbnail(
        messageIdHex: String,
        attachmentIndex: Int,
        bitmap: android.graphics.Bitmap,
        maxEdgePx: Int = dev.ipf.whitenoise.android.media.MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
    ): Unit = attachmentPresentation.cacheThumbnail(messageIdHex, attachmentIndex, bitmap, maxEdgePx)

    /** Resolves interactive attachment bytes through the bounded presentation owner. */
    suspend fun downloadAttachment(
        messageIdHex: String,
        attachmentIndex: Int,
        reference: MediaAttachmentReferenceFfi,
    ): ByteArray = downloadAttachment(messageIdHex, attachmentIndex, reference, AttachmentDownloadPriority.Interactive)

    /**
     * Every pending attachment in the optimistic album, ordered by attachment
     * index. Empty when no upload is queued under the temp id. Used by the
     * upload placeholder so the sender sees the same grid/file-pill shape
     * during upload as the post-upload bubble — the placeholder needs the
     * filename + MIME alongside the bytes so non-image attachments render
     * with their original name instead of a generic preview.
     */
    fun pendingAttachmentsList(messageIdHex: String): List<PendingAttachment> =
        retainedMediaUploads
            .get("msg:$messageIdHex")
            ?.attachments
            .orEmpty()

    /**
     * Byte progress of the single-file send still pending as [messageIdHex], or null when it has none to
     * show: an album, a send held in memory, or a message that is no longer pending here.
     */
    internal fun pendingUploadProgress(messageIdHex: String): StateFlow<FileUploadProgress?>? {
        val retained = retainedMediaUploads.get("msg:$messageIdHex") ?: return null
        return retained.uploadProgress
    }

    /**
     * Drop all retained outgoing JPEG bytes. Called when leaving the
     * conversation so account A's decrypted outgoing media doesn't linger in
     * memory (e.g. before a sign-out), matching the app-cache hygiene.
     */
    fun clearRetainedUploads() {
        // Skip entries whose upload is mid-flight. `sendStagedAttachments`
        // hands off to `mutationsScope` (app-scoped), so the upload loop
        // survives the conversation screen's `DisposableEffect.onDispose`
        // that calls this. Wiping the bytes for an in-flight slot turns
        // `performMediaUpload`'s `retainedMediaUploads.get(key)` into a
        // null and flips the bubble to Failed (re-attach to retry). The
        // privacy guarantee still holds: successful sends clean their own
        // bytes (line ~1614), and a future dispose drains any
        // failed-and-never-retried entries that are no longer in flight.
        retainedMediaUploads.keysSnapshot().forEach { key ->
            if (key !in activeUploadKeys) retainedMediaUploads.remove(key)
        }
    }

    /**
     * Re-issues a previously-failed outgoing send for [item]. The optimistic
     * record is updated in-place (preserving its [TimelineMessage.timelineOrder]
     * so the bubble doesn't visually jump) and transitions Failed -> Pending
     * while the FFI call is in flight. On success it follows the same
     * confirmed-id swap path as [send]; on failure it returns to Failed.
     * Keep the established original-send retry transaction behind the failed-edit guard.
     */
    @Suppress("LongMethod", "ReturnCount") // Each guard exits before the retry changes the row.
    suspend fun retryFailedSend(item: TimelineMessage) {
        // A send that failed before the leave was blocked, or before a disband, stays failed while that state holds.
        outboundRefusal()?.let {
            appState.presentTransient(it, kind = TransientNoticeKind.Information)
            return
        }
        val key = item.id
        // A failed edit's bubble is the target's projected row (no
        // optimisticMessages entry); its retry re-runs the edit publish rather
        // than re-sending a new message. editMessage flips the overlay back to
        // Pending, so a double-tap finds it non-Failed and the guard below exits.
        val failedEdit = failedEditForAction(item)
        if (failedEdit != null) {
            retryFailedEdit(item.record.messageIdHex, failedEdit)
            return
        }
        // Re-check live state. The captured item.status may be stale if the
        // user double-taps before recomposition: both taps would see Failed
        // on the captured argument and both would queue FFI sends. By reading
        // from optimisticMessages and bailing unless still Failed, the second
        // tap finds Pending (set by the first tap below) and exits.
        val current = optimisticMessages[key] ?: return
        if (current.status != MessageStatus.Failed) return
        val account =
            conversationAccountRef
                ?.takeIf {
                    shouldAcceptMediaUploadForAccount(
                        it,
                        mediaUploadSessionEpoch,
                        appState.activeAccountRef,
                        appState.mediaUploadSessionEpoch(),
                    )
                }
                ?: return
        if (current.record.tags.any { it.values.firstOrNull() == "_media_pending" }) {
            retryFailedMediaSend(account, key, current)
            return
        }
        val tempId = current.record.messageIdHex
        val text = current.record.plaintext.takeIf { it.isNotBlank() } ?: return
        val replyTarget = MessageProjector.replyTargetMessageId(current.record)
        val refreshedRecord = current.record.copy()
        val order = retriedTimelineOrder(current.timelineOrder) { nextOptimisticTimelineOrder() }
        markFailedTextSendPending(key, tempId, current, refreshedRecord, order)
        var retryTrace: PerformanceTrace? = null
        try {
            val committedProjection =
                committedButUnpublishedProjectionForOptimistic(
                    timelineRecords,
                    refreshedRecord,
                    conversationAccountIdHex,
                )
            if (committedProjection != null) {
                settleRetryFromCommittedProjection(key, tempId, committedProjection)
                return
            }
            retryTrace = PerformanceDiagnostics.begin(PerformanceOperation.TEXT_SEND)
            appState.pendingSendDiagnostics.track(tempId, retryTrace)
            sendTrace(
                retryTrace,
                PerformancePhase.MANUAL_RETRY,
                elapsedMs = 0L,
                result = PerformanceResult.PENDING,
            )
            val summary = publishTextWithRetry(replyTarget, account, text, retryTrace, tempId, key)
            completeDurableAcceptance(key)
            if (discardedDuringRetry.remove(key)) {
                // User discarded mid-flight; drop the result entirely.
                discardOptimisticSend(key, tempId, OptimisticSendDiscard.DISCARDED_IN_FLIGHT)
                trimCancelledSendTombstones()
                return
            }
            val reconciliation = settleAcceptedTextSend(summary, key, tempId, refreshedRecord, order)
            if (!reconciliation.acceptedPending) {
                optimisticSendPhases.remove(key)
            }
        } catch (throwable: Throwable) {
            handleFailedSendRetryFailure(
                throwable = throwable,
                key = key,
                tempId = tempId,
                current = current,
                refreshedRecord = refreshedRecord,
                order = order,
                account = account,
                retryTrace = retryTrace,
            )
        }
    }

    /**
     * Drops a failed outgoing send. An optimistic send MarmotKit never accepted is local cleanup only, and its
     * cancellation still enters the group commit lock so a stale failure action cannot race an admitted retry. A
     * durable send whose publish failed goes to [discardDurableFailedSend], which publishes nothing to the group.
     */
    suspend fun discardFailedSend(item: TimelineMessage) {
        val key = item.id
        // A failed edit only abandons its unsent kind-1009 edit, reverting the bubble to the untouched original.
        val failedEdit = failedEditForAction(item)
        if (failedEdit != null) {
            val intent = failedEdit.durableIntent
            if (intent == null) {
                optimisticEdits.remove(item.record.messageIdHex)
                publishTimelineFromIndexes()
            } else {
                discardPendingEdit(item.record.messageIdHex, intent)
            }
            return
        }
        val current =
            optimisticMessages[key]
                ?: run {
                    if (isDurableFailedSend(item)) {
                        discardDurableFailedSend(item.record.messageIdHex)
                    } else {
                        removeCancelledOptimisticSend(key, item.record.messageIdHex)
                    }
                    return
                }
        cancelOptimisticSendResult(current.record, optimisticKeyOverride = key)
            .onFailure { failure ->
                appState.presentFailure(R.string.toast_couldnt_delete_message, "MESSAGE_CANCEL", failure)
            }
    }

    /**
     * Leave the open conversation. [displayName], when non-blank, selects the
     * named success snackbar ("Left <name>") the group-settings Leave action
     * wants (#416); callers without a display name (e.g. the conversation
     * overflow) fall back to the generic "Left chat" copy.
     */
    suspend fun leaveGroup(displayName: String? = null): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            val activeAccountIdHex = conversationAccountIdHex
            // Decide the leave from a live roster read rather than the in-memory
            // `members` count, which can be stale (#811). If the roster is just
            // you there is no one to coordinate an MLS commit with, so bypass the
            // sole-admin transfer gate and dissolve the group with local cleanup.
            val liveMembers =
                runCatchingCancellable { appState.marmotIo { groupMembers(account, group.groupIdHex) } }
                    .getOrNull()
            val liveMemberCount = liveMembers?.let(GroupProjector::uniqueMemberCount) ?: memberCount
            val soleMember =
                GroupProjector.shouldDissolveAsSoleMember(
                    liveMembers,
                    activeAccountIdHex,
                )
            if (!soleMember && !GroupProjector.canLeaveGroup(group, activeAccountIdHex, liveMemberCount)) {
                appState.present(R.string.toast_make_another_admin_before_leaving, R.string.toast_group_needs_admin)
                return@withMutationLockResult false
            }
            var demotedBeforeLeave = false
            runCatching {
                val left =
                    appState.withGroupCommitLock(account, group.groupIdHex) {
                        leaveWithRoster(account, soleMember, activeAccountIdHex) { demotedBeforeLeave = true }
                    }
                if (!left) return@runCatching false
                appState.removeComposerExpansionForGroup(account, group.groupIdHex)
                // Authoritative local self-leave: record it before the
                // synchronous snapshot drop so any subsequent
                // authoritative roster round-trip that still
                // sees the engine pre-eviction cannot re-add self and re-enable
                // the composer / restore the full member count (issue #787).
                recordSelfLeft()
                // Drop self from the cached member snapshot synchronously so
                // re-opening the just-left group seeds a roster without self
                // and renders the disabled notice immediately, instead of
                // flashing the active composer (issue #545). The subscription's
                // markActiveAccountRemovedFromMembers() may not fire before the
                // UI navigates back and disposes this controller, so this is the
                // authoritative invalidation.
                appState.removeActiveAccountFromGroupMemberSnapshot(account, group.groupIdHex)
                // Flip the chat-list row to its left state too: the engine
                // pushes no chat-list update for a self-leave, and the
                // chat-list controller's removedGroupIds/memberCache (which
                // drive the row's left state) are only updated by its own
                // leaveGroup, not this Details path — so without this the row
                // stays active until the next bind (issue #767).
                appState.markGroupLeftOnChatList(account, group.groupIdHex)
                val name = displayName?.takeIf { it.isNotBlank() }
                if (name != null) {
                    presentConversationTransient(AppText.Resource(R.string.toast_left_named, listOf(name)))
                } else {
                    presentConversationTransient(R.string.toast_left_chat)
                }
                true
            }.getOrElse {
                if (it is CancellationException) throw it
                recordMutationFailure(
                    if (demotedBeforeLeave) {
                        R.string.toast_demoted_but_couldnt_leave
                    } else {
                        R.string.toast_couldnt_leave_chat
                    },
                    if (demotedBeforeLeave) "GROUP_LEAVE_AFTER_DEMOTE" else "GROUP_LEAVE",
                    it,
                )
                false
            }
        }

    suspend fun dismissConversationNotifications() {
        val account = conversationAccountRef ?: return
        appState.dismissConversationNotifications(account, group.groupIdHex)
    }

    /**
     * Dispatches Join only while the rendered invitation remains current to
     * this controller, retaining optimistic state across safe bounded retries.
     */
    @Suppress("ComplexCondition", "ReturnCount") // Explicit fences keep stale outcomes side-effect free.
    suspend fun acceptInvite(
        notify: Boolean = true,
        renderedGroupIdHex: String = group.groupIdHex,
        renderedWelcomeMessageIdHex: String? = group.viaWelcomeMessageIdHex,
    ): Boolean =
        withMutationLockResult(false) {
            val account = conversationAccountRef ?: return@withMutationLockResult false
            val invitePeerAccount = inviteAccount
            val previousGroup = group
            val generation = InviteAcceptanceGeneration(renderedGroupIdHex, renderedWelcomeMessageIdHex)
            if (
                !ownsInviteAcceptanceResult ||
                inviteAcceptanceResolutionPending ||
                !canAcceptRenderedInvite(previousGroup, generation)
            ) {
                return@withMutationLockResult false
            }
            val authorityEpochBefore = mutationPresentation.groupAuthorityEpoch
            inviteConfirmationAuthority.begin(previousGroup)
            val optimisticGroup = optimisticAcceptedInvite(previousGroup)
            group = optimisticGroup
            appState.applyLocalGroupUpdate(optimisticGroup, account)
            val attempt =
                InviteAcceptanceAttempt(account, generation, inviteConfirmationAuthority.revision)
            val acceptedGroup = resolveInviteAcceptance(attempt)
            if (acceptedGroup == null) return@withMutationLockResult false
            if (
                !ownsInviteAttempt(attempt) ||
                !acceptedInviteMatchesGeneration(acceptedGroup, generation)
            ) {
                if (ownsInviteAttempt(attempt)) {
                    inviteConfirmationAuthority.finish()
                    group = group.copy(pendingConfirmation = false)
                    inviteAcceptanceAwaitingAuthority = generation
                    refreshMembers()
                }
                return@withMutationLockResult false
            }
            acceptedInvitePeerAccount = invitePeerAccount
            applyAcceptedInviteGroup(acceptedGroup, authorityEpochBefore)
            appState.applyLocalGroupUpdate(group, account)
            appState.dismissConversationNotifications(account, generation.groupIdHex)
            // Accepting an invite (re-)joins the group, so clear any stale
            // local self-left latch before refreshMembers() so roster application
            // is allowed to add self back to the roster (issue #787).
            selfMembership.clearSelfLeft()
            if (notify) presentConversationTransient(R.string.toast_invite_accepted)
            inviteStreamScope.launch {
                runBestEffortPostCommitSteps(
                    steps =
                        listOf(
                            "members" to { refreshMembers() },
                            "timeline" to { refreshCurrentTimeline(account) },
                            "read-state" to { initializeReadState(account) },
                        ),
                    onFailure = { step, throwable ->
                        if (BuildConfig.DEBUG) Log.w("DMConversation", "post-accept $step refresh failed", throwable)
                    },
                )
            }
            true
        }

    // The invitation whose decline waits for the user to agree to step down as admin first.
    internal var inviteDeclineStepDownRequest by mutableStateOf<InviteAcceptanceGeneration?>(null)

    /** Whether Decline is waiting for the user to agree to step down as admin, for the invitation still shown. */
    val inviteDeclineStepDownRequested: Boolean
        get() {
            val request = inviteDeclineStepDownRequest ?: return false
            return group.pendingConfirmation && request == inviteGeneration(group)
        }

    /** Withdraws the step-down question, so nothing is declined and no role changes. */
    fun cancelInviteDeclineStepDown() {
        inviteDeclineStepDownRequest = null
    }

    /**
     * Declines the rendered Welcome in one native operation. If MDK requires a separate admin step-down,
     * the controller asks for consent before changing roles.
     */
    internal suspend fun declineInvite(rendered: InviteAcceptanceGeneration) = declineInviteWithConsent(null, rendered)

    /**
     * Steps down and declines the invitation the user agreed to, but only while it is still the one shown. The
     * question stays open until the decline takes the mutation lock, so a busy conversation never drops the answer.
     */
    suspend fun confirmInviteDeclineStepDown(): Boolean {
        val consent = inviteDeclineStepDownRequest ?: return false
        return declineInviteWithConsent(stepDownConsent = consent)
    }

    /**
     * Archive or restore this conversation with a presentation-only optimistic
     * intent: the conversation surfaces and the bound chat-list row acknowledge
     * the accepted action before the engine commit starts or waits behind the
     * group commit lock. MDK stays authoritative — success settles into the
     * returned group in the same frame the intent retires, while failure or
     * cancellation clears only the matching intent so the newest authoritative
     * projection shows through instead of a captured snapshot. The mutation
     * lock drops repeated taps while one logical mutation is in flight, so no
     * duplicate commits or conflicting overlays can start.
     */
    suspend fun setArchived(archived: Boolean): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            val groupIdHex = group.groupIdHex
            val intent = mutationPresentation.beginArchive(archived)
            val chatListIntent = appState.beginChatListArchiveIntent(account, groupIdHex, archived)
            val authorityEpochBefore = mutationPresentation.groupAuthorityEpoch
            try {
                runCatchingCancellable {
                    appState.withGroupCommitLock(account, groupIdHex) {
                        val updated = groupArchivedUpdater(account, groupIdHex, archived)
                        // A newer authoritative application (subscription update
                        // or details round-trip) that landed while this commit
                        // was in flight wins over the late success; the engine's
                        // own archive-change event reconverges the projection.
                        if (mutationPresentation.groupAuthorityEpoch == authorityEpochBefore) {
                            if (mutationPresentation.ownsCurrentPresentation()) group = updated
                            appState.applyLocalGroupUpdate(updated, account)
                        }
                    }
                    presentConversationTransient(
                        if (archived) R.string.toast_chat_archived else R.string.toast_chat_restored,
                    )
                    true
                }.onFailure {
                    recordMutationFailure(R.string.toast_couldnt_update_chat, "GROUP_ARCHIVE_UPDATE", it)
                }.getOrDefault(false)
            } finally {
                // Identity-compared so a newer action's intent is never cleared
                // by an older completion, and account/group switches (which
                // rebind the chat list) cannot receive this stale settle.
                mutationPresentation.finishArchive(intent)
                appState.finishChatListArchiveIntent(account, groupIdHex, chatListIntent)
            }
        }

    suspend fun deleteGroupLocal(): Boolean =
        runLocalGroupRemoval(R.string.toast_couldnt_delete_chat, "GROUP_LOCAL_DELETE") { account ->
            appState.deleteGroupLocalWithClientCleanup(account, group.groupIdHex)
            R.string.toast_chat_deleted_local
        }

    /**
     * Confirmed local abandonment (#3310) of this DM or group: MDK 0.10.0's forget behind the durable cleanup
     * journal. It sends no leave, admin change or disband, so a refused handover or an absent peer cannot block it.
     */
    suspend fun forgetGroupLocal(): Boolean {
        val dm = isDm
        val failure = if (dm) R.string.toast_couldnt_delete_chat else R.string.toast_couldnt_reset_group
        return runLocalGroupRemoval(failure, "GROUP_LOCAL_RESET") { account ->
            val runtime = appState.runtimeGeneration
            val reset =
                appState.forgetGroupLocalWithClientCleanup(account, group.groupIdHex) {
                    conversationAccountRef == account &&
                        appState.runtimeGeneration == runtime &&
                        !appState.signOutInProgress &&
                        !appState.wipeInProgress
                }
            when {
                !reset && dm -> R.string.toast_chat_already_abandoned
                !reset -> R.string.toast_group_reset_already_waiting
                dm -> R.string.toast_chat_deleted_local
                else -> R.string.toast_group_reset_done
            }
        }
    }

    /**
     * Commits the fields the user changed in the group editor, compared with the values it opened
     * with ([openedName] and [openedDescription]). An unchanged field is sent as null, so MDK carries
     * it forward from canonical group state, even if another admin changed it meanwhile. A changed
     * field is sent as its trimmed text, so an erased one arrives as the explicit empty string MDK
     * applies as a clear (#3704). Callers other than the editor may omit the opened values; they then
     * default to the live group, so a field equal to its current value is treated as unchanged.
     */
    suspend fun updateGroupProfile(
        name: String,
        description: String,
        openedName: String = group.name,
        openedDescription: String = group.description,
    ): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            val updatedName = name.trim().takeIf { it != openedName.trim() }
            val updatedDescription = description.trim().takeIf { it != openedDescription.trim() }
            if (updatedName == null && updatedDescription == null) return@withMutationLockResult true
            runCatchingCancellable {
                val before = group
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    groupProfileUpdater(account, group.groupIdHex, updatedName, updatedDescription)
                }
                // Reflect the committed profile locally so the header and every chat-list
                // projection agree immediately, rather than after the group-state
                // subscription converges (#2696).
                applyLocalGroupProfile(
                    before = before,
                    updatedName = updatedName,
                    updatedDescription = updatedDescription,
                    account = account,
                )
                presentConversationTransient(R.string.toast_group_updated)
                true
            }.onFailure {
                recordMutationFailure(R.string.toast_couldnt_update_group, "GROUP_PROFILE_UPDATE", it)
            }.getOrDefault(false)
        }

    /**
     * Updates the public avatar and reports failure only while [change] owns the attempt.
     * [commitIfCurrent] must support reads on IO as well as the editor's main thread.
     */
    internal suspend fun updateGroupAvatarUrl(
        change: ScopedGroupImageMutation<String?>,
        commitIfCurrent: () -> Boolean = change.isActive,
    ): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val report = change.isActive
            val account = conversationAccountRef ?: return@withMutationLockResult false
            // The Rust side validates + normalizes the URL (https-only, no
            // private hosts). We only set the URL here; dim/thumbhash are
            // optimization hints we don't compute on Android, so clear them.
            val normalized = change.value?.trim()?.takeIf { it.isNotEmpty() }
            var encryptedImageCleared = group.imageHashHex == null || normalized == null
            runCatchingCancellable {
                val committed =
                    appState.withGroupCommitLock(account, group.groupIdHex) {
                        // Queuing behind another commit can outlive this editor or account.
                        if (!commitIfCurrent()) return@withGroupCommitLock false
                        val wroteAvatar =
                            appState.runGroupChange(
                                account,
                                group.groupIdHex,
                                stillWanted = commitIfCurrent,
                                actionLabel = if (normalized == null) R.string.remove_photo else R.string.change_photo,
                            ) {
                                // A retry the user continued past must reach MDK, or its acknowledgement outlives it.
                                val admitted = isAdmittedGroupChange()
                                appState.marmotIo {
                                    // Dispatch to IO can also wait after the group lock was acquired.
                                    if (!admitted && !commitIfCurrent()) {
                                        false
                                    } else {
                                        updateGroupAvatarUrl(account, group.groupIdHex, normalized, null, null)
                                        true
                                    }
                                }
                            }
                        if (!wroteAvatar) return@withGroupCommitLock false
                        // A public avatar supersedes the encrypted component. Clear it
                        // after the URL is durable so another client cannot resurrect it.
                        if (normalized != null && group.imageHashHex != null) {
                            encryptedImageCleared = clearSupersededGroupImage(account)
                        }
                        true
                    }

                if (!committed) return@runCatchingCancellable false
                // Reflect the change locally so the avatar updates immediately,
                // without waiting for the group-state subscription to converge.
                group = groupWithPublicAvatar(group, normalized, encryptedImageCleared)
                presentConversationTransient(R.string.toast_group_updated)
                true
            }.onFailure {
                if (report()) recordMutationFailure(R.string.toast_couldnt_update_group, "GROUP_AVATAR_URL_UPDATE", it)
            }.getOrDefault(false)
        }

    /** Commits the encrypted image and reports failure only while [change] owns the attempt. */
    internal suspend fun updateGroupImage(change: ScopedGroupImageMutation<ImageUploadDraft?>): Boolean {
        if (change.viewerPermissionCheck && !change.isActive()) return false
        val draft = change.value
        val capturedGroupId = group.groupIdHex
        val requestedMutationKey =
            draft?.let { withContext(Dispatchers.Default) { it.mutationKey() } }
                ?: REMOVE_GROUP_IMAGE_MUTATION_KEY
        return withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            var attemptedLegacyClear = false
            val committed =
                runCatchingCancellable {
                    val accepted =
                        commitGroupImageMutation(account, capturedGroupId, change, requestedMutationKey) {
                            attemptedLegacyClear = true
                        }
                    if (!accepted) return@runCatchingCancellable false
                    if (change.viewerPermissionCheck) {
                        if (!refreshViewerGroupImageDetails(account, capturedGroupId, change)) {
                            return@runCatchingCancellable false
                        }
                    } else {
                        refreshMembers()
                    }
                    if (!change.viewerPermissionCheck || change.isActive()) {
                        presentConversationTransient(R.string.toast_group_updated)
                    }
                    true
                }.onFailure {
                    if (change.isActive()) {
                        presentGroupImageMutationFailure(it, requestedMutationKey, attemptedLegacyClear)
                    }
                }.getOrDefault(false)
            committed && (!change.viewerPermissionCheck || change.isActive())
        }
    }

    /**
     * Engine-authoritative admin/lifecycle capabilities for the details
     * screen: disband eligibility, blockers, and the in-flight request.
     * Refreshed on screen entry and after each disband-family mutation.
     */
    var managementState by mutableStateOf<GroupManagementStateFfi?>(null)
        internal set
    internal val managementStateLifetime = StalenessGuard()
    internal var cancelledLeaveAwaitingRoster by mutableStateOf(false)
    internal var observedBlockedLeave by mutableStateOf(initialGroup.leaveBlocked)

    /** A cleared block still needs verification before Android restores membership. */
    internal val blockedLeaveNeedsReadback: Boolean
        get() = cancelledLeaveAwaitingRoster || (observedBlockedLeave && !group.leaveRequestPending)

    /** Keep the departure notice until native membership has been verified. */
    internal val showsBlockedLeave: Boolean
        get() = group.leaveBlocked || blockedLeaveNeedsReadback

    /** MDK owns cancellation eligibility. An unresolved readback can retry without another mutation. */
    internal val canCancelBlockedLeave: Boolean
        get() = blockedLeaveNeedsReadback || (group.leaveBlocked && managementState?.canCancelLeave == true)

    fun isAdmin(member: AppGroupMemberRecordFfi): Boolean =
        projectedGroupAdmin(
            authoritativeAdmin = GroupProjector.isAdmin(group, member),
            memberIdHex = member.memberIdHex,
            mutation = optimisticGroupRosterMutation.current,
        )

    internal fun isAuthoritativeAdmin(member: AppGroupMemberRecordFfi): Boolean = GroupProjector.isAdmin(group, member)

    fun memberDisplayName(member: AppGroupMemberRecordFfi): String = appState.displayName(member.memberIdHex)

    fun memberSubtitle(member: AppGroupMemberRecordFfi): String = appState.shortNpub(member.memberIdHex)

    /** Applies account-private member pictures while preserving the native profile URL as fallback. */
    fun memberAvatarUrl(member: AppGroupMemberRecordFfi): String? =
        appState.contactAvatarSource(
            member.memberIdHex,
            boundAccountRef,
        )

    suspend fun groupMlsState(): AppGroupMlsStateFfi? {
        val account = conversationAccountRef ?: return null
        return runCatchingCancellable {
            appState.marmotIo { groupMlsState(account, group.groupIdHex) }
        }.onFailure {
            appState.presentFailure(R.string.toast_couldnt_load_mls_state, "GROUP_MLS_STATE_LOAD", it)
        }.getOrNull()
    }

    suspend fun groupPushDebugInfo(): GroupPushDebugInfoFfi? {
        val account = conversationAccountRef ?: return null
        return runCatchingCancellable {
            appState.marmotIo { groupPushDebugInfo(account, group.groupIdHex) }
        }.onFailure {
            appState.presentFailure(R.string.toast_couldnt_load_push_debug_info, "GROUP_PUSH_DEBUG_LOAD", it)
        }.getOrNull()
    }

    /** Pages older from [anchorMessageIdHex]; an automatic [origin] stands down once the engine has nothing older. */
    suspend fun loadOlder(
        anchorMessageIdHex: String? = null,
        origin: PagingOrigin = PagingOrigin.EXPLICIT,
    ) {
        loadOlderPage(anchorMessageIdHex, origin)
    }

    /** True when the canonical timeline holds more history after the loaded window. */
    val hasMoreAfterTimeline: Boolean
        get() = hasMoreAfter

    /** Pages the subscription window older; true when the window actually advanced. */
    suspend fun loadOlderTimelinePage(): Boolean = loadOlderPage()

    /** Pages the subscription window newer; true when the window actually advanced. */
    suspend fun loadNewerTimelinePage(origin: PagingOrigin = PagingOrigin.EXPLICIT): Boolean = loadNewerPage(origin)

    /**
     * Page the exact chat-list first-unread boundary into the initial window.
     * Unlike interactive reply navigation, entry positioning must not silently
     * substitute a newer row after an arbitrary page cap. The subscription
     * loader's no-progress and exhaustion guards still bound this traversal.
     */
    suspend fun loadConversationEntryUnreadMessageAvailable(messageIdHex: String): Boolean {
        while (!timelineRecords.containsKey(messageIdHex) && hasMoreBefore) {
            if (!loadOlderPage()) break
        }
        return timelineRecords.containsKey(messageIdHex)
    }

    /**
     * Move the subscription-owned bounded window toward [match] until that
     * exhaustive local-search result is present or the relevant side of local
     * history is exhausted. Timestamp + id let traversal page in either
     * direction after an earlier jump shifted the capped window.
     */
    suspend fun loadSearchResultMessageAvailable(match: ConversationSearchMatch): Boolean {
        while (!timelineRecords.containsKey(match.messageIdHex)) {
            if (timelineRecords.isEmpty()) break
            val oldest =
                timelineRecords.values.minWithOrNull(
                    compareBy({ it.timelineAt }, { it.messageIdHex }),
                ) ?: break
            val newest =
                timelineRecords.values.maxWithOrNull(
                    compareBy({ it.timelineAt }, { it.messageIdHex }),
                ) ?: break
            val direction =
                conversationSearchPageDirection(
                    match = match,
                    oldestTimelineAt = oldest.timelineAt,
                    oldestMessageId = oldest.messageIdHex,
                    newestTimelineAt = newest.timelineAt,
                    newestMessageId = newest.messageIdHex,
                    hasMoreBefore = hasMoreBefore,
                    hasMoreAfter = hasMoreAfter,
                )
            val madeProgress =
                when (direction) {
                    // Anchor at the oldest held row: an unanchored page at the window cap stays
                    // head-anchored and trims what it just fetched, so it never reaches the match (#2873).
                    ConversationSearchPageDirection.OLDER -> loadOlderPage(anchorMessageIdHex = oldest.messageIdHex)
                    ConversationSearchPageDirection.NEWER -> loadNewerPage()
                    null -> false
                }
            if (!madeProgress) break
        }
        return timelineRecords.containsKey(match.messageIdHex)
    }

    /**
     * Move the bounded window toward a message at a known timeline position,
     * paging in whichever direction it lies — [loadUntilMessageAvailable]
     * only walks older, so it can never reach a target newer than the loaded
     * window and drags the window further away with each retry.
     */
    suspend fun loadTimelineMessageAvailable(
        messageIdHex: String,
        timelineAt: ULong,
    ): Boolean = loadSearchResultMessageAvailable(ConversationSearchMatch(messageIdHex, timelineAt))

    fun replyTargetMessageId(item: TimelineMessage): String? = ReplyNavigation.targetMessageId(item.record, item.projected)

    /**
     * Accepted attachments keyed by protocol index, without duplicating MarmotKit's
     * projection state. A projected row is authoritative even when empty; positional
     * tag parsing is reserved for optimistic/compatibility records without one.
     */
    fun attachmentsFor(record: AppMessageRecordFfi): List<IndexedAttachment> =
        timelineRecords[record.messageIdHex]?.media?.let(MessageAttachments::accepted)
            ?: MessageAttachments.indexed(
                MediaReferenceSupport.parseAllImetaTags(tags = record.tags, sourceEpoch = record.sourceEpoch ?: 0uL),
            )

    /**
     * Load a focus/navigation source id and return the rendered message the
     * reader should scroll to. Reaction notifications carry the kind-7 event id;
     * if that source is only found after pagination, re-resolve it to the
     * reacted-to parent via the `e` tag before the final scroll (#1113).
     */
    suspend fun loadScrollNavigationTarget(sourceMessageIdHex: String): String? =
        ReplyNavigation.loadScrollNavigationTarget(
            sourceMessageIdHex = sourceMessageIdHex,
            lookupSourceRecord = { lookupMessageRecord(sourceMessageIdHex) },
            loadUntilMessageAvailable = ::loadUntilMessageAvailable,
        )

    // Bounds the opportunistic prefetch in each direction once it stops making progress (#2764, #2727).
    internal val automaticPaging = AutomaticPagingGuards()

    /** Whether viewport-driven forward prefetch should stand down until a page advances. */
    val automaticNewerPagingBlocked: Boolean
        get() = automaticPaging.newer.blocked

    /** Whether older prefetch is waiting quietly for recovery or fresh touch intent. */
    val automaticOlderPagingBlocked: Boolean
        get() = automaticPaging.older.blocked

    /** Recovery ticket read by the paging collector even when visible geometry did not change. */
    val olderPagingRecoveryGeneration: Long
        get() = automaticPaging.older.recoveryGeneration

    /** Releases quiet older prefetch on a new drag; never changes the viewport or unread state. */
    fun onOlderPagingGestureStarted() {
        automaticPaging.older.onUserGestureStarted()
    }

    /**
     * Whether an older page failed in a way the reader must retry.
     *
     * Scroll-driven prefetch reads this so a window that timed out is not asked again on every
     * frame; the retry affordance, or any live replacement that clears [pageError], releases it.
     */
    val olderPageBlocked: Boolean
        get() = pageError != null && failedPageDirection == ConversationSearchPageDirection.OLDER

    suspend fun retryLoadFailure() {
        if (subscriptionError?.retryable == false) return
        when (failedPageDirection?.takeIf { pageError != null }) {
            // Retrying deliberately pages from the revision MDK still holds rather than re-reporting
            // an anchor: the reader has not scrolled since the attempt they are retrying.
            ConversationSearchPageDirection.OLDER -> loadOlderPage()
            ConversationSearchPageDirection.NEWER -> loadNewerPage()
            null ->
                if (terminalLoadFailure) {
                    terminalLoadFailure = false
                    retryGeneration += 1L
                } else {
                    timelineSession.retry()
                }
        }
    }

    /** Test seam: refreshes the whole window through the production path with [pageLoader]'s page. */
    internal suspend fun testRefreshCurrentTimeline(
        account: String,
        pageLoader: suspend () -> TimelinePageFfi,
    ) = refreshCurrentTimeline(account, pageLoader)

    /** Test seam: applies live subscription [changes] through the production path. */
    internal fun testApplyLiveTimelineChanges(changes: List<TimelineMessageChangeFfi>) = applyTimelineChanges(changes)

    /**
     * Resolved reply target as (sender pubkey, display body). Returns the raw
     * sender — not a display name — so the caller can cache this projection in
     * `remember` while name resolution stays live for late profile loads.
     */
    fun replyPreview(
        item: TimelineMessage,
        copy: MessageTextCopy = MessageTextCopy.Default,
    ): TimelineReplyDisplay? {
        val projectedPreview = item.projected?.let { record -> TimelineProjector.replyPreview(record, copy) }
        if (projectedPreview != null && !projectedPreview.originalUnavailable) return projectedPreview
        val targetMessageId = MessageProjector.replyTargetMessageId(item.record) ?: return projectedPreview
        val target = messageById[targetMessageId] ?: return projectedPreview
        return replyTargetPreview(target, copy)
    }

    /** Resolves a live target into the same typed preview model used by projected replies. */
    fun replyTargetPreview(
        target: AppMessageRecordFfi,
        copy: MessageTextCopy = MessageTextCopy.Default,
    ): TimelineReplyDisplay {
        val refs = mediaReferencesFor(target)
        val mediaFallback = typedReplyMediaFallback(refs)
        timelineRecords[target.messageIdHex]?.let { projectedTarget ->
            return TimelineProjector.replyTargetPreview(projectedTarget, mediaFallback, copy)
        }
        val mediaKind =
            mediaFallback?.kind
                ?: replyMediaKindFromMime(refs.firstOrNull()?.mediaType)
        val projectedBody = MessageProjector.displayBody(target, copy)
        return TimelineReplyDisplay(
            sender = target.sender,
            body =
                replyBodyWithTypedMediaFallback(
                    plaintext = target.plaintext,
                    projectedBody = projectedBody,
                    mediaFallback = mediaFallback,
                    copy = copy,
                ),
            mediaKind = mediaKind,
            mediaFileName = mediaFallback?.filename,
            mediaType = mediaFallback?.mediaType,
        )
    }

    /**
     * Applies one timeline page and invalidates suspended whole-window refreshes. A window command result
     * and its stream echo can land in either order, so the newest installed replacement is applied together
     * with its own sidecar instead of pairing [page] with a frame from a later revision.
     */
    @Suppress("LongMethod") // The generation check and one main-thread diff commit must remain atomic.
    internal suspend fun applyTimelinePage(
        page: TimelinePageFfi,
        replaceWindow: Boolean,
        updatePagination: Boolean,
        reconcileNewExtendedRecords: Boolean = false,
        onCommitted: (() -> Unit)? = null,
    ) {
        val preparationGeneration = timelineWindowGeneration.advance()
        val installed = timelineSubscription?.latestInstalledWindow()
        // A newer installed window keeps the Markdown parsed for [page]'s cards before this call (#2238).
        val applied = installed?.page?.withParsedRecords(page.messages) ?: page
        val priorOldestId = timeline.firstOrNull()?.id
        val snapshot = currentWindowApplySnapshot()
        val preparation =
            prepareWindowApplyOn(
                dispatcher = windowPreparationDispatcher,
                nanoTime = windowApplyNanoTime,
                page = applied,
                snapshot = snapshot,
                replaceWindow = replaceWindow,
                reconcileNewExtendedRecords = reconcileNewExtendedRecords,
            )
        val prepared = preparation.value
        if (!timelineWindowGeneration.isCurrent(preparationGeneration)) return
        assertMainThread { "applyTimelinePage commit" }
        val commitStartedAt = windowApplyNanoTime()
        val commitPlan =
            prepared.planCommit(
                snapshot,
                timelineRecords,
                timelineItemsById.keys,
                pendingProjectionsAwaitingBridge.keys,
            )
        commitPlan.departedIds.forEach(::removeProjectedRecord)
        if (prepared.mode == WindowApplyMode.REPLACE) trimStateForWindowReplacement()
        installAuthoritativeOrder(prepared)
        val appliedRecords = ArrayList<TimelineMessageRecordFfi>(prepared.rows.size)
        prepared.rows.forEach { row ->
            val record = row.record
            pendingTimelineRemovedMessageIds = pendingTimelineRemovedMessageIds - record.messageIdHex
            appliedRecords.add(record)
            val reconcilesOptimistic =
                row.reconcilesOptimistic || (reconcileNewExtendedRecords && record.messageIdHex !in timelineRecords)
            if (record.messageIdHex in commitPlan.projectIds) {
                upsertProjectedRecord(
                    record,
                    reconcileOptimistic = reconcilesOptimistic,
                    allowDelayedProjection = reconcilesOptimistic,
                    preparedAction = row.actionRecord,
                )
            }
            if (record.deleted) {
                deletedMessageIds = deletedMessageIds - record.messageIdHex
            }
        }
        appState.requestProfiles(prepared.profileIds)
        if (updatePagination) {
            hasMoreBefore = applied.hasMoreBefore
            hasMoreAfter = applied.hasMoreAfter
        }
        // A rebuilt window is a new place in history, so a prefetch that stood down at the old
        // edge gets to ask again from here (#2727).
        if (replaceWindow) {
            automaticPaging.reset()
        } else {
            automaticPaging.older.onWindowApplied(installed?.frame?.revision)
            if (prepared.mode == WindowApplyMode.REPLACE) automaticPaging.newer.reset()
        }
        // Rows this page kept skip re-projection, so their projected items still carry the ordinal
        // from where the window used to sit. Display sorts on that ordinal, so re-stamp it before
        // publishing or a slid window would reorder history the reader is looking at.
        if (prepared.mode == WindowApplyMode.EXTEND) refreshAuthoritativeOrder(applied)
        readAnchoredAtSeconds.retainTimelineWindow()
        pruneConfirmedOptimisticMessages()
        retentionAtSendByMessageId.retainTimelineWindow()
        pruneMessageOverlaysToWindow()
        pruneRetainedTimelineRows(prepared)
        val reactionReferenceChanges = installWindowFrame(installed?.frame)
        pruneConfirmedOptimisticReactions()
        // A replacement invalidates every tally. An extension updates changed rows and reaction references (#2990).
        if (prepared.mode == WindowApplyMode.REPLACE) {
            recomputeReactions()
        } else {
            recomputeReactions(commitPlan.touchedIds + reactionReferenceChanges)
        }
        // A non-replaceWindow page (older-history load once hasLoadedOlderPages
        // is set) skips the replaceWindow trim above, so prune messageById to the
        // current window + optimistic records here too (#373).
        messageById.retainTimelineWindow()
        // Edits whose target left the window: the status-based prune in
        // publishTimelineFromIndexesInternal can't fire once aggregated[target]
        // goes null, so a never-echoed Pending edit would otherwise leak (#691).
        optimisticEdits.retainTimelineWindow()
        // The pass above already projected every record into the by-id and
        // order indexes (and reconciled optimistics), exactly like the live
        // update paths do — so publish directly. A second full rebuild here
        // re-projected every held record on each page load. See #74.
        val preparingInitialPresentation = !hasPreparedInitialPresentation
        markPagePublishedUnlessHeldForEventCards(preparingInitialPresentation, appliedRecords)
        // While the first page is prepared, its card warm owns the cache read and reuses this hydration's parse.
        val hydratedRecords =
            publishTimelinePageBeforeMarkdownHydration(
                appliedRecords,
                warmEventCards = !preparingInitialPresentation,
            )
        clearRecoveredOlderPageFailure(priorOldestId)
        scheduleProfilePresentationWarm(
            records = appliedRecords,
            hydratedRecords = hydratedRecords,
            markInitialPresentationReady = preparingInitialPresentation,
        )
        recordPagePublicationUnlessHeld()
        onWindowApplyMeasured(
            preparation.performanceSample(prepared, commitPlan.projectIds.size, commitStartedAt, windowApplyNanoTime()),
        )
        onCommitted?.invoke()
    }

    /** Reads cards through the conversation-owned presentation pipeline before committing later pages. */
    internal suspend fun warmLaterPageEventCards(
        page: TimelinePageFfi,
        anchorMessageId: String? = null,
    ): TimelinePageFfi = eventCardPresentation.warmLaterPageEventCards(page, anchorMessageId)

    /**
     * Advance the per-chat read pointer to [messageId]. Called from the UI
     * layer when the user has actually scrolled past a message; lets the
     * chat-list unread count decrement incrementally during the session
     * instead of being zeroed out on chat open.
     *
     * Dedupes requests submitted by this controller so a quiet scroll
     * (settled on the same row) doesn't issue redundant FFI hops.
     * Invalid/optimistic IDs and hidden, locked or differently owned conversations are ignored.
     * A stored watermark alone does not dedupe the first visible read of a new visit.
     */
    suspend fun markReadUpTo(messageId: String) {
        // Optimistic UUIDs and missing anchors cannot reach native read commands.
        val trimmed = messageId.takeIf { it.isNotBlank() && HEX_MESSAGE_ID.matches(it) } ?: return
        val account = conversationAccountRef ?: return
        if (appState.isConversationReadVisible(account, group.groupIdHex)) {
            submitVisibleReadUpTo(account, trimmed)
        }
    }

    /**
     * Returns the timeline index of the FIRST received message that hasn't
     * been read yet, given the chat-list-projection's [unreadCount].
     * Returns -1 when there's nothing unread, the timeline is empty, or
     * the unread count exceeds the loaded window (caller falls back to the
     * bottom in that case).
     */
    fun firstUnreadTimelineIndex(unreadCount: Int): Int = firstUnreadReceivedIndex(timeline, unreadCount)

    internal fun acceptedPendingTextOptimisticIdForProjection(projectedMessageIdHex: String): String? {
        val optimisticMessageId =
            acceptedPendingTextOptimisticIdForProjection(
                projectedMessageIdHex = projectedMessageIdHex,
                acceptedPendingOptimisticIdsByMessageId = acceptedPendingTextOptimisticIds,
            ) ?: return null
        return optimisticMessageId.takeIf { "msg:$it" in optimisticMessages }
            ?: run {
                // The projection is settling now, but its local bubble was
                // discarded or otherwise removed in the meantime.
                acceptedPendingTextOptimisticIds.remove(projectedMessageIdHex)
                null
            }
    }

    internal fun acceptedPendingMediaOptimisticIdForProjection(projectedMessageIdHex: String): String? {
        val acceptedPendingMessageIdsByOptimisticId =
            retainedMediaUploads
                .keysSnapshot()
                .mapNotNull { optimisticKey ->
                    val timelineMessage = optimisticMessages[optimisticKey] ?: return@mapNotNull null
                    val isPendingMedia =
                        timelineMessage.record.tags.any { it.values.firstOrNull() == "_media_pending" }
                    val acceptedMessageIdHex = retainedMediaUploads.get(optimisticKey)?.acceptedPendingMessageIdHex
                    acceptedMessageIdHex
                        ?.takeIf { isPendingMedia }
                        ?.let { optimisticKey.removePrefix("msg:") to it }
                }.toMap()
        return acceptedPendingMediaOptimisticIdForProjection(
            projectedMessageIdHex = projectedMessageIdHex,
            acceptedPendingMessageIdsByOptimisticId = acceptedPendingMessageIdsByOptimisticId,
        )
    }

    /** Removes one projection and every controller-owned index keyed to it. */
    internal fun removeProjectedRecord(messageIdHex: String) {
        val itemId = "msg:$messageIdHex"
        timelineRecords.remove(messageIdHex)
        authoritativeTimelineOrderByMessageId.remove(messageIdHex)
        projectedMessageIds.remove(messageIdHex)
        localTimelineOrderOverrides.remove(messageIdHex)
        localTimelineTimestampOverrides.remove(messageIdHex)
        optimisticSendPositionPreserves.remove(messageIdHex)
        retentionAtSendByMessageId.remove(messageIdHex)
        readAnchoredAtSeconds.remove(messageIdHex)
        deletedMessageIds = deletedMessageIds - messageIdHex
        optimisticReactionChanges.entries.removeAll { (_, change) -> change.targetMessageId == messageIdHex }
        reactionsState.remove(messageIdHex)
        timelineItemsById.remove(itemId)
        timelineOrder.remove(itemId)
    }

    // Main-thread only, like the rest of ConversationController's Compose
    // state. Re-entrant counter — when non-zero,
    // `publishTimelineFromIndexes` defers its work and the outermost
    // `coalesceTimelinePublishes` flushes once. Batching a burst of
    // subscription emits into one publish is the largest single saving for the
    // 7.21ms-on-janky-frames "Input+Anim+Layout" cost in `dumpsys gfxinfo`:
    // each publish re-sorts + de-dupes the full timeline and re-aggregates the
    // edits index.
    internal var publishSuppressionDepth = 0
    internal var publishPending = false
    internal var timelinePositionSettlementJob: Job? = null

    // #1578 detector de-dup: adjacent inversion pairs already logged this session.
    internal var loggedTimelineOrderingInversionPairs = emptySet<Pair<String, String>>()

    /** Reads and maps a complete canonical reaction snapshot off the UI thread for this conversation. */
    internal suspend fun loadReactionParticipants(targetMessageId: String): List<ReactionParticipant> =
        readReactionRecords(targetMessageId) { rows ->
            rows.map { ReactionParticipant(sender = it.sender, emoji = it.emoji, reactedAt = it.reactedAt) }
        }

    /** Overlays pending own mutations onto a complete snapshot; never expands the chip preview. */
    internal fun reactionParticipantsFor(
        targetMessageId: String,
        confirmed: List<ReactionParticipant>,
    ): List<ReactionParticipant> =
        reactionDetailsParticipants(
            confirmed = confirmed,
            mine = conversationAccountIdHex,
            changes = optimisticReactionChanges.values.filter { it.targetMessageId == targetMessageId },
            now = nowSeconds(),
        )

    /** Re-reads authoritative membership without replaying a pending group mutation. */
    suspend fun retryMembers() = refreshMembers()

    /** Retries only the authority read for a retired invite; it never replays Join. */
    suspend fun retryInviteAcceptanceAuthority() {
        if (ownsInviteAcceptanceResult && inviteAcceptanceResolutionPending) {
            withMutationLockResult(Unit) { if (inviteAcceptanceResolutionPending) refreshMembers() }
        }
    }

    /**
     * Latch the authoritative local self-leave marker (issue #787). Set on a
     * confirmed self-leave (leaveGroup success) or engine eviction
     * ([markActiveAccountRemovedFromMembers]); honoured by [isSelfMember] and
     * roster application so a transient round-trip can't restore self
     * before the engine eviction is observed locally. A removed device
     * ([SelfMembershipFfi.DEVICE_REMOVED]) keeps its account on the roster.
     */
    internal fun recordSelfLeft() {
        selfMembership.recordSelfLeft(accountStaysOnRoster = group.isDeviceRemovedCopy())
    }

    /** Drops entries for messages that left the loaded window and have no in-flight optimistic record. */
    internal fun MutableMap<String, *>.retainTimelineWindow() = retainWindowMessageKeys(this, timelineRecords.keys, optimisticMessages.values)

    init {
        if (startOnConstruction) start()
    }

    companion object {
        /**
         * 32 MiB cap on retained compressed bytes for in-flight/failed
         * uploads. A few failed images stay retryable without letting an
         * undiscarded backlog accrete unbounded heap. Exposed so the UI
         * picker can bound the album payload against the SAME ceiling
         * (otherwise an oversize album would self-evict its retained
         * bytes on insertion and turn into a "reattach to retry" loop).
         */
        const val MEDIA_RETAINED_MAX_BYTES: Long = 32L * 1024L * 1024L

        /** True iff the cumulative plaintext bytes across [attachments]
         *  would exceed the retained-bytes cap. Pure for unit-testing. */
        fun albumExceedsRetainedCap(attachments: List<PendingAttachment>): Boolean {
            var total = 0L
            for (a in attachments) {
                total += a.plaintextBytes.size.toLong()
                if (total > MEDIA_RETAINED_MAX_BYTES) return true
            }
            return false
        }

        // 32-byte (64 hex char) message id as Rust expects on the FFI
        // boundary. Used to filter optimistic UUID-format ids out of FFI
        // calls that would otherwise throw InvalidHex.
        internal val HEX_MESSAGE_ID: Regex = Regex("^[0-9a-fA-F]{64}$")
    }
}

/** What a membership change means for a full-list read taken before it (#3586). */
internal enum class MembershipRemoval {
    /** Nothing left the window, or only rows evicted past its tail, so an earlier read still covers every chat. */
    None,

    /** A row the window should still hold left it, so an earlier read can no longer prove completeness. */
    Ambiguous,

    /** A chat this controller deleted locally, which an earlier read can drop by id. */
    LocalDeletion,
}
