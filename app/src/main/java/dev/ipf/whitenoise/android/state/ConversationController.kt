package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AgentStreamSubscription
import dev.ipf.marmotkit.AgentStreamUpdateFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupManagementStateFfi
import dev.ipf.marmotkit.GroupMutationResultFfi
import dev.ipf.marmotkit.GroupPushDebugInfoFfi
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupRejoinInvitationFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.HistoryNoticeCauseFfi
import dev.ipf.marmotkit.HistoryNoticeFfi
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ConversationSearchMatch
import dev.ipf.whitenoise.android.core.ConversationTranscriptExport
import dev.ipf.whitenoise.android.core.ConversationTranscriptTimelineReader
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupSystemEvents
import dev.ipf.whitenoise.android.core.IndexedAttachment
import dev.ipf.whitenoise.android.core.LeaveAction
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.core.ReplyNavigation
import dev.ipf.whitenoise.android.core.StreamDebugEventFormatter
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.core.TimelineReplyDisplay
import dev.ipf.whitenoise.android.core.exceedsOutgoingMessageLimit
import dev.ipf.whitenoise.android.core.replyBodyWithTypedMediaFallback
import dev.ipf.whitenoise.android.core.replyMediaKindFromMime
import dev.ipf.whitenoise.android.core.typedReplyMediaFallback
import dev.ipf.whitenoise.android.core.withAuthoritativeEdits
import dev.ipf.whitenoise.android.diagnostics.CreationOperation
import dev.ipf.whitenoise.android.diagnostics.DmCreationAttempt
import dev.ipf.whitenoise.android.diagnostics.DmCreationFailure
import dev.ipf.whitenoise.android.diagnostics.DmCreationInteraction
import dev.ipf.whitenoise.android.diagnostics.DmCreationOutcome
import dev.ipf.whitenoise.android.diagnostics.DmCreationPhase
import dev.ipf.whitenoise.android.diagnostics.GroupMembershipTimings
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceSendStage
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import dev.ipf.whitenoise.android.diagnostics.invitationFailed
import dev.ipf.whitenoise.android.diagnostics.projectionFinished
import dev.ipf.whitenoise.android.diagnostics.traceInvitation
import dev.ipf.whitenoise.android.media.GroupImageMutationFailure
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.media.OutgoingSend
import dev.ipf.whitenoise.android.media.REMOVE_GROUP_IMAGE_MUTATION_KEY
import dev.ipf.whitenoise.android.media.classifyGroupImageMutationFailure
import dev.ipf.whitenoise.android.media.mutationKey
import dev.ipf.whitenoise.android.media.shouldCommitPrimaryGroupImageMutation
import dev.ipf.whitenoise.android.ui.conversation.media.isPendingVideo
import dev.ipf.whitenoise.android.ui.conversation.media.nativeFileBackedSendLimits
import dev.ipf.whitenoise.android.ui.conversation.media.pendingVideoPosterFrame
import dev.ipf.whitenoise.android.ui.conversation.media.uploadSourcesDirectory
import dev.ipf.whitenoise.android.ui.conversation.nostr.mayRenderNostrEventCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * The two deletion scopes a user may apply to one message. This is the single
 * authoritative deletion-capability model: both the action UI (which choices
 * the delete surface offers) and the controller mutation path (which requests
 * it accepts) derive from [messageDeleteCapability], so button visibility is
 * never the security boundary.
 */
@Immutable
data class MessageDeleteCapability(
    val canDeleteForMe: Boolean,
    val canDeleteForEveryone: Boolean,
) {
    val canDeleteAtAll: Boolean get() = canDeleteForMe || canDeleteForEveryone
}

/**
 * Whether the runtime can actually deliver a moderator's delete of another
 * member's group message. The Marmot timeline ingest now honours a group
 * admin's delete of another member's message: the moderation verdict is
 * evaluated against signed group state when the delete is recorded and
 * persisted with the event, and direct conversations never grant moderation.
 * Kept as a constant so the moderator branch can be cut in one place if the
 * engine behaviour ever has to be rolled back.
 */
internal const val GROUP_MODERATION_DELETE_SUPPORTED = true

/**
 * Deletion-capability matrix:
 *
 *  - Own message (direct or group): delete for me, delete for everyone.
 *  - Someone else's direct message: delete for me only. A direct conversation
 *    has no moderation, whatever admin flags its underlying two-member group
 *    carries — DM creators are often marked admin at the MLS layer, and an
 *    admin role held in any other group never reaches here at all (each
 *    conversation's controller only passes its own membership role).
 *  - Another member's group message: delete for everyone only when the
 *    current user moderates this group (admin/owner) and the runtime supports
 *    delivering it ([moderationDeleteSupported]); regular members get
 *    delete for me only.
 *  - Already-deleted projection: delete for me only. The underlying protocol
 *    tombstone remains authoritative; local cleanup never offers or retries a
 *    delete for everyone.
 *
 * [localDeleteSupported] and [remoteDeleteSupported] carry the plumbing facts
 * (usable message id, live membership / publish path); this function owns only
 * the ownership/role policy layered on top of them.
 */
internal fun messageDeleteCapability(
    isDirectConversation: Boolean,
    mine: Boolean,
    selfIsAdmin: Boolean,
    localDeleteSupported: Boolean,
    remoteDeleteSupported: Boolean,
    alreadyDeleted: Boolean,
    moderationDeleteSupported: Boolean = GROUP_MODERATION_DELETE_SUPPORTED,
): MessageDeleteCapability {
    if (alreadyDeleted) {
        return MessageDeleteCapability(
            canDeleteForMe = localDeleteSupported,
            canDeleteForEveryone = false,
        )
    }
    val moderatesOthersMessages = !isDirectConversation && selfIsAdmin && moderationDeleteSupported
    return MessageDeleteCapability(
        canDeleteForMe = localDeleteSupported,
        canDeleteForEveryone = remoteDeleteSupported && (mine || moderatesOthersMessages),
    )
}

/**
 * Ownership boundary for an optimistic outgoing row. Only [PRE_ACCEPTANCE]
 * may be cancelled locally; every later phase is already owned by MDK or has
 * an acknowledgement whose ownership cannot be disproved safely.
 */
internal enum class OptimisticSendPhase {
    PRE_ACCEPTANCE,
    ACCEPTED_PENDING,
    ACCEPTANCE_UNKNOWN,
    SETTLED,
    FAILED,
    CANCELLED,
}

private class OptimisticSendCancelledException : Exception("Optimistic send cancelled before admission")

private const val MAX_CANCELLED_SEND_TOMBSTONES = 64

/** Shared membership gate for optimistic work that the engine later validates. */
private fun canAcceptSeededMemberMutation(
    accountRef: String?,
    membersVerified: Boolean,
    isSelfMember: Boolean,
    seededSelfMember: Boolean,
    selfLeft: Boolean,
    unrecoverable: Boolean,
    disbanding: Boolean,
    disbanded: Boolean,
): Boolean =
    accountRef != null &&
        !unrecoverable &&
        !disbanding &&
        !disbanded &&
        (
            (membersVerified && isSelfMember) ||
                (!membersVerified && seededSelfMember && !selfLeft)
        )

/**
 * Whether a text [ConversationController.send] can commit an optimistic record.
 * This must stay aligned with the UI's input-clearing decision (issue #264).
 */
internal fun canAcceptTextSend(
    accountRef: String?,
    trimmed: String,
    membersVerified: Boolean,
    isSelfMember: Boolean,
    seededSelfMember: Boolean,
    selfLeft: Boolean,
    unrecoverable: Boolean,
    disbanding: Boolean,
    disbanded: Boolean,
): Boolean =
    trimmed.isNotEmpty() &&
        canAcceptSeededMemberMutation(
            accountRef = accountRef,
            membersVerified = membersVerified,
            isSelfMember = isSelfMember,
            seededSelfMember = seededSelfMember,
            selfLeft = selfLeft,
            unrecoverable = unrecoverable,
            disbanding = disbanding,
            disbanded = disbanded,
        )

/**
 * Reactions use the same seed-safe membership window as text sends. The
 * authoritative engine still validates the mutation and rolls back the
 * optimistic reaction if the cached membership has become stale.
 */
internal fun canAcceptReaction(
    accountRef: String?,
    membersVerified: Boolean,
    isSelfMember: Boolean,
    seededSelfMember: Boolean,
    selfLeft: Boolean,
    unrecoverable: Boolean,
    disbanding: Boolean,
    disbanded: Boolean,
): Boolean =
    canAcceptSeededMemberMutation(
        accountRef = accountRef,
        membersVerified = membersVerified,
        isSelfMember = isSelfMember,
        seededSelfMember = seededSelfMember,
        selfLeft = selfLeft,
        unrecoverable = unrecoverable,
        disbanding = disbanding,
        disbanded = disbanded,
    )

internal fun mediaCacheKey(
    account: String,
    groupIdHex: String,
    messageIdHex: String,
    attachmentIndex: Int,
): String = "$account|$groupIdHex|$messageIdHex|$attachmentIndex"

/**
 * True when [cacheKey] was minted by [mediaCacheKey] for this account+group.
 * The retention sweep uses this to drop a pruned group's decrypted in-memory
 * entries: the engine prunes atomically, so no pre-prune media rows survive
 * to map ciphertext tags to exact keys, and the whole group slice goes.
 * Case-insensitive: hex ids drift in casing across sources, matching the
 * projector's comparisons and the chat list's lowercased row keys.
 */
internal fun mediaCacheKeyInGroup(
    cacheKey: String,
    account: String,
    groupIdHex: String,
): Boolean = cacheKey.startsWith("$account|$groupIdHex|", ignoreCase = true)

/**
 * The keys in this account+group's L1 slice whose message row is no longer
 * loaded. The bounded timeline window trims old rows, so an expired-tag
 * mapping for them is gone; the open conversation's sweep drops these
 * fail-closed rather than let a pruned attachment's decrypted bytes outlive
 * the prune, while loaded rows keep their entries (no on-screen refetch).
 */
internal fun staleGroupMediaCacheKeys(
    cachedKeys: Collection<String>,
    account: String,
    groupIdHex: String,
    loadedMessageIds: Set<String>,
): List<String> {
    val loadedLowercase = loadedMessageIds.mapTo(HashSet()) { it.lowercase() }
    return cachedKeys.filter { key ->
        mediaCacheKeyInGroup(key, account, groupIdHex) &&
            key.split('|').getOrNull(2)?.lowercase() !in loadedLowercase
    }
}

internal suspend fun removeMediaMemoryCacheKeys(
    cacheKeys: Iterable<String>,
    dispatcher: CoroutineDispatcher,
    removeEntry: (String) -> Unit,
) {
    withContext(dispatcher) {
        cacheKeys.forEach(removeEntry)
    }
}

/**
 * The poster seeded under a just-sent attachment's confirmed key.
 *
 * A photo decodes its own bytes. A video has a frame pulled from the same bytes instead, so the
 * confirmed bubble opens on the poster the optimistic one already showed rather than waiting for
 * the file to be materialized and read again (#2732). A photo decodes in [domain]'s worker.
 */
private suspend fun decodeMediaThumbnailOffMain(
    attachment: PendingAttachment,
    domain: dev.ipf.whitenoise.android.media.MediaTrustDomain =
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
): android.graphics.Bitmap? {
    // A file-backed item has no bytes here; its confirmed bubble paints the thumbhash, then MDK's copy.
    val bytes = attachment.inMemoryBytes ?: return null
    return withContext(Dispatchers.Default) {
        if (attachment.isPendingVideo) {
            pendingVideoPosterFrame(bytes, extractPoster = true).bitmap
        } else {
            MediaPipeline.decodeSampledBitmap(
                bytes,
                MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
                domain = domain,
            )
        }
    }
}

internal fun <T> newestMatchingController(
    controllers: Iterable<T>,
    matches: (T) -> Boolean,
): T? = controllers.lastOrNull(matches)

data class ConversationControllerCopy(
    val waitingForStream: String = "Waiting for stream...",
    val streamFailedFormat: String = "Stream failed: %1\$s",
    val tryAgain: String = "Try again.",
    val couldntAddMemberDuplicateFormat: String =
        "Couldn't add %1\$s. They're already a member, or their signing key conflicts with an existing member.",
    val groupRosterChanged: String = "Group membership changed. Review the group and try again.",
) {
    fun streamFailed(): String = String.format(streamFailedFormat, tryAgain)

    fun couldntAddMemberDuplicate(name: String): String = String.format(couldntAddMemberDuplicateFormat, name)
}

/**
 * Whether [update] ends this device's membership. A [SelfMembershipFfi.DEVICE_REMOVED] copy counts: this device takes
 * no more sends, while its account stays on the roster through another device. A durable
 * pending leave is provisional even when the record already says LEFT.
 */
internal fun groupStateUpdateRemovesSelf(
    previous: AppGroupRecordFfi,
    update: AppGroupRecordFfi,
): Boolean = !previous.hasSettledDeparture() && update.hasSettledDeparture()

/** Pending LEFT can still resolve to a joined account after cancellation. */
private fun AppGroupRecordFfi.hasSettledDeparture(): Boolean =
    selfMembership.isNonMember() && !(selfMembership == SelfMembershipFfi.LEFT && leaveRequestPending)

/** Distinguishes an actual eviction from a provisional voluntary departure. */
private fun SelfMembershipFfi.isGroupEviction(): Boolean =
    when (this) {
        SelfMembershipFfi.REMOVED, SelfMembershipFfi.DEVICE_REMOVED -> true
        SelfMembershipFfi.MEMBER, SelfMembershipFfi.LEFT -> false
    }

/** A readback can rejoin only an active, confirmed group with no outstanding leave request. */
private fun AppGroupRecordFfi.isJoinedAfterLeave(): Boolean =
    selfMembership == SelfMembershipFfi.MEMBER &&
        !leaveRequestPending &&
        !leaveBlocked &&
        !pendingConfirmation &&
        !disbanded &&
        !disbanding

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

internal fun agentStreamFailureText(
    throwable: Throwable,
    copy: ConversationControllerCopy,
): String {
    if (throwable is CancellationException) throw throwable
    return copy.streamFailed()
}

private const val MARMOT_DELETE_EVENT_KIND = 5uL
private const val MARMOT_REACTION_EVENT_KIND = 7uL

/** Delivery changes can carry the peer reaction that confirms a previously failed send. */
private fun TimelineUpdateTriggerFfi.recomputesReactions(): Boolean =
    when (this) {
        TimelineUpdateTriggerFfi.REACTION_ADDED,
        TimelineUpdateTriggerFfi.REACTION_REMOVED,
        TimelineUpdateTriggerFfi.MESSAGE_DELETED,
        TimelineUpdateTriggerFfi.MESSAGE_EDITED_OR_REPROJECTED,
        TimelineUpdateTriggerFfi.SNAPSHOT_REFRESH,
        TimelineUpdateTriggerFfi.DELIVERY_OR_SEND_STATE_CHANGED,
        -> true
        TimelineUpdateTriggerFfi.NEW_MESSAGE,
        TimelineUpdateTriggerFfi.REPLY_PREVIEW_CHANGED,
        TimelineUpdateTriggerFfi.AGENT_STREAM_STARTED,
        TimelineUpdateTriggerFfi.AGENT_STREAM_FINISHED,
        TimelineUpdateTriggerFfi.RECEIPT_CHANGED,
        // Typed agent, group-system, and custom-event updates do not mutate
        // reaction tallies, so they fall in the false bucket — kept explicit
        // so a future trigger that *does* change
        // reactions fails the exhaustiveness check rather than silently
        // missing a recompute.
        TimelineUpdateTriggerFfi.AGENT_ACTIVITY,
        TimelineUpdateTriggerFfi.AGENT_OPERATION,
        TimelineUpdateTriggerFfi.GROUP_SYSTEM,
        TimelineUpdateTriggerFfi.CUSTOM_EVENT,
        -> false
    }

@Suppress("MayBeConstant")
internal val ConversationTimelinePageLimit = 50u

internal fun compareConversationTimelinePosition(
    firstAt: ULong,
    firstId: String,
    secondAt: ULong,
    secondId: String,
): Int = firstAt.compareTo(secondAt).takeIf { it != 0 } ?: firstId.compareTo(secondId)

internal enum class ConversationSearchPageDirection { OLDER, NEWER }

internal enum class ConversationLoadFailureEdge { TOP, BOTTOM }

internal fun conversationLoadFailureEdge(
    hasPageFailure: Boolean,
    failedPageDirection: ConversationSearchPageDirection?,
): ConversationLoadFailureEdge =
    if (hasPageFailure && failedPageDirection == ConversationSearchPageDirection.NEWER) {
        ConversationLoadFailureEdge.BOTTOM
    } else {
        ConversationLoadFailureEdge.TOP
    }

internal fun conversationSearchPageDirection(
    match: ConversationSearchMatch,
    oldestTimelineAt: ULong,
    oldestMessageId: String,
    newestTimelineAt: ULong,
    newestMessageId: String,
    hasMoreBefore: Boolean,
    hasMoreAfter: Boolean,
): ConversationSearchPageDirection? =
    when {
        hasMoreBefore &&
            compareConversationTimelinePosition(
                match.timelineAt,
                match.messageIdHex,
                oldestTimelineAt,
                oldestMessageId,
            ) < 0 -> ConversationSearchPageDirection.OLDER
        hasMoreAfter &&
            compareConversationTimelinePosition(
                match.timelineAt,
                match.messageIdHex,
                newestTimelineAt,
                newestMessageId,
            ) > 0 -> ConversationSearchPageDirection.NEWER
        else -> null
    }

// Cap on the live-projected timeline window (≈4 pages). Bounds memory for a
// long-open, busy conversation while leaving ample scroll headroom before
// loadOlder() must re-fetch.
private const val LIVE_TIMELINE_WINDOW_CAP = 200

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

private enum class GroupAdministrationCommitOutcome {
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

private const val MESSAGE_EDIT_EVENT_KIND = 1009uL

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
    private val copy: ConversationControllerCopy = ConversationControllerCopy(),
    private val groupRosterReader: suspend (String, String) -> GroupRosterFfi = { account, groupIdHex ->
        appState.marmotIo(MarmotTraceSection.REFRESH_GROUP_ROSTER) {
            groupRoster(account, groupIdHex)
        }
    },
    private val groupRecoveryStatusReader: suspend (String, String) -> GroupRecoveryStatusFfi = { account, groupIdHex ->
        appState.marmotIo {
            groupRecoveryStatus(account, groupIdHex)
        }
    },
    // Test seams: whether MDK parked this chat's convergence, the explicit retry, and the raw event stream.
    private val groupConvergenceParkedReader: suspend (String, String) -> Boolean = { account, groupIdHex ->
        appState.marmotIo { groupMlsState(account, groupIdHex).convergenceParked }
    },
    private val groupConvergenceRetrier: suspend (String, String) -> Unit = { account, groupIdHex ->
        appState.marmotIo { retryGroupConvergence(account, groupIdHex) }
    },
    // MDK's runtime event firehose, the only stream that carries a chat's park and resume edges.
    private val runtimeEventStreamOpener: suspend () -> RuntimeEventStream = { appState.openRuntimeEventStream() },
    private val historyNoticeDismisser: suspend (String, String) -> Boolean = { account, noticeId ->
        appState.marmotIo { dismissHistoryNotice(account, noticeId) }
    },
    private val historyNoticesReader: suspend (String) -> List<HistoryNoticeFfi> = { account ->
        appState.marmotIo { historyNotices(account) }
    },
    private val textPublisher: (suspend (String?, String, String, String) -> SendSummaryFfi)? = null,
    // Test seams: the user's own emoji a text uses, and the send that carries them.
    private val customEmojiReader: (suspend (String) -> List<LocalEmojiArtwork>)? = null,
    private val customEmojiSender: CustomEmojiSender? = null,
    private val messageEditPublisher: suspend (String, String, String, String) -> Unit = { account, groupId, target, text ->
        appState.marmotIo(MarmotTraceSection.MESSAGE_EDIT) { editMessage(account, groupId, target, text) }
    },
    private val pendingMessageEditPublisher: PendingMessageEditPublisher? = null,
    private val pendingEditStatusReader: suspend (String, String, String) -> dev.ipf.marmotkit.LocalSendStatusFfi? =
        { account, groupId, token -> appState.marmotIo { localSendStatus(account, groupId, token) } },
    private val mediaUploader: MediaUploader? = null,
    private val mediaFileUploader: MediaFileUploader? = null,
    // The native per-send ciphertext bound for file-backed uploads; null when MarmotKit cannot report it.
    private val fileBatchCiphertextBytes: () -> Long? = { nativeFileBackedSendLimits()?.batchCiphertextBytes },
    private val mediaImetaTagsBuilder: MediaImetaTagsBuilder = { account, groupIdHex, references ->
        appState.marmotIo {
            references.map { reference -> buildMediaImetaTag(account, groupIdHex, reference) }
        }
    },
    private val mediaPublisher: MediaPublisher? = null,
    // A just-sent pick decodes in its sent draft's domain, whose worker retires once the send has ended.
    private val mediaThumbnailDecoder: MediaThumbnailDecoder = { attachment, domain ->
        decodeMediaThumbnailOffMain(attachment, domain)
    },
    private val markdownParser: suspend (String) -> MarkdownDocumentFfi = { appState.parseMarkdownOrEmpty(it) },
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
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val inviteAcceptor: InviteAcceptor = { account, groupIdHex ->
        appState.marmotIo(MarmotTraceSection.ACCEPT_GROUP_INVITE) {
            acceptGroupInvite(account, groupIdHex)
        }
    },
    private val windowPreparationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val windowApplyNanoTime: () -> Long = System::nanoTime,
    private val onWindowApplyMeasured: (WindowApplyPerformanceSample) -> Unit = {},
    private val groupSnapshotReader: suspend (String, String) -> dev.ipf.marmotkit.GroupConversationSnapshotFfi =
        { account, groupId -> appState.marmotIo { groupConversationSnapshot(account, groupId) } },
    private val initialAdminInviteWriter:
        suspend (String, String, List<String>, List<String>) -> dev.ipf.marmotkit.GroupMutationResultFfi =
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
    private val liveSubscriptions = appState.conversationLiveSubscriptions()

    private var inviteConfirmationUnresolved by mutableStateOf(initialInviteConfirmationUnresolved)
    private var inviteAuthorityReadInFlight = false
    internal var inviteAcceptanceResolutionState by mutableStateOf(GroupRosterLoadState.LOADING)
        private set
    private val inviteConfirmationAuthority = InviteConfirmationAuthority(initialGroup)
    var group by mutableStateOf(
        inviteConfirmationAuthority
            .reconcile(
                reconcileTerminalSelfMembership(initialGroup, initialGroup),
                establishesAcceptance = !initialInviteConfirmationUnresolved,
            ).let { if (initialInviteConfirmationUnresolved) it.copy(pendingConfirmation = false) else it },
    )
        private set

    private val mutationPresentation =
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
    private var inviteAcceptanceAwaitingAuthority by mutableStateOf<InviteAcceptanceGeneration?>(null)

    internal val inviteAcceptanceResolutionPending: Boolean
        get() = inviteConfirmationUnresolved || inviteAcceptanceAwaitingAuthority != null

    private val ownsInviteAcceptanceResult: Boolean
        get() = !controllerCleared && !isAccountTeardownRequested()

    /** Metadata refreshes may cross Join; a different Welcome or terminal observation may not. */
    private fun ownsInviteAttempt(attempt: InviteAcceptanceAttempt): Boolean =
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

    private var acceptedInvitePeerAccount by mutableStateOf<String?>(null)

    // Retains a locally committed rename against a stale group-state snapshot (#2696).
    private val localGroupNames = LocalGroupFieldAuthority()

    // The same retention for a just-committed description, so a pre-edit snapshot cannot restore it (#3704).
    private val localGroupDescriptions = LocalGroupFieldAuthority()

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
    private var pendingLegacyAvatarClearAfterImageMutationKey: String? = null

    // Hex of the conversation's own account. Captured like
    // conversationAccountRef so display/permission/"is me" helpers stay tied to
    // the conversation's account instead of reading the live active account
    // (which can differ from the controller's account before teardown, and
    // during a notification-routed open while the switch is still landing).
    // A pinned controller never falls back to the active account: an override
    // whose label is missing resolves to null, which fails closed ("is me",
    // admin, and composer gates all deny) instead of mixing two accounts.
    private val conversationAccountIdHex =
        if (accountRefOverride != null) {
            appState.accounts.firstOrNull { it.label == accountRefOverride }?.accountIdHex
        } else {
            appState.activeAccount?.accountIdHex
        }

    private val membershipSeed = conversationMembershipSeed(initialGroup, initialMemberSnapshot, conversationAccountIdHex)

    var members by mutableStateOf<List<AppGroupMemberRecordFfi>>(membershipSeed.members)
        private set

    internal val membershipTimings = GroupMembershipTimings()
    private val optimisticGroupRosterMutation =
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
        private set

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
        private set

    private val memberRosterLoadTracker =
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
        private set

    private val memberRosterRefreshGeneration = StalenessGuard()

    // Invalidated when a timeline page or live subscription batch lands so an
    // in-flight full-page refresh cannot clobber newer state (#1849).
    private val timelineWindowGeneration = StalenessGuard()
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
    private val selfMembership =
        ConversationSelfLeftState(seededMembershipKnown, seededSelfMember, membershipSeed.accountStaysOnRoster)

    // A snapshot map (not mutableStateOf<Map>) so a bubble reading one key isn't
    // recomposed when a different message's reactions change.
    private val reactionsState = mutableStateMapOf<String, List<ReactionTally>>()
    val reactions: Map<String, List<ReactionTally>> get() = reactionsState
    var deletedMessageIds by mutableStateOf<Set<String>>(emptySet())
        private set

    // One-shot invalidation mailbox for UI snapshots held outside the bounded
    // timeline. ConversationScreen acknowledges each batch after reconciliation,
    // so removed IDs do not become a second long-lived message index.
    var pendingTimelineRemovedMessageIds by mutableStateOf<Set<String>>(emptySet())
        private set
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
    private val optimisticEdits = mutableStateMapOf<String, OptimisticEdit>()
    private val pendingMessageEditHandoff: PendingMessageEditHandoff get() = appState.pendingMessageEditHandoff

    private fun pendingEditKey(token: String): String = "${conversationAccountRef.orEmpty()}|${group.groupIdHex}|$token"

    /** Set when the user has tapped Edit on a kind-9 they sent — the composer
     * banner reflects this and the next [send] routes through [editMessage]
     * instead of producing a new chat. Cleared on submit, cancel, or
     * navigation away. */
    private var editSessionRevision = 0L
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
        private set
    internal var pageLoadInFlight by mutableStateOf<ConversationSearchPageDirection?>(null)
        internal set
    var hasMoreBefore by mutableStateOf(false)
        private set
    internal var hasMoreAfter by mutableStateOf(false)

    /** Shared action slot, independent of the subscription attempt's lifetime. */
    val mutationInFlight: Boolean get() = mutationPresentation.inFlight
    var lastMutationError: ErrorPresentation?
        get() = mutationPresentation.error
        private set(value) {
            if (value == null) mutationPresentation.clearError() else mutationPresentation.recordFailure(value)
        }
    private var subscriptionError by mutableStateOf<ErrorPresentation?>(null)
    internal var pageError by mutableStateOf<ErrorPresentation?>(null)
    val error: ErrorPresentation?
        get() = pageError ?: subscriptionError
    internal val errorEdge: ConversationLoadFailureEdge
        get() = conversationLoadFailureEdge(pageError != null, failedPageDirection)

    // staleness-exempt: observable retry trigger; nullable snapshots below retain its value.
    var retryGeneration by mutableLongStateOf(0L)
        private set
    private var terminalLoadFailure = false
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

    private fun Throwable.rethrowIfCancellation() {
        if (this is CancellationException) throw this
    }

    /** Admits one UI mutation while keeping its execution in the caller/app-owned scope. */
    private suspend fun <T> withMutationLockResult(
        defaultValue: T,
        block: suspend () -> T,
    ): T = mutationPresentation.runIfIdle(defaultValue, block)

    private val conversationAccountRef = accountRefOverride ?: appState.activeAccountRef
    private val conversationRuntimeGeneration = appState.runtimeGeneration
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

    // Self-identity for conversation-scoped UI ("is me", mention detection).
    // Follows the conversation's account, which the active account only lags
    // during a notification-routed early open (#586).
    internal val boundAccountIdHex: String?
        get() = conversationAccountIdHex

    private fun presentConversationTransient(
        @StringRes titleRes: Int,
    ) {
        val accountRef = conversationAccountRef ?: return
        appState.presentConversationTransient(accountRef, group.groupIdHex, titleRes)
    }

    private fun presentConversationTransient(title: AppText) {
        val accountRef = conversationAccountRef ?: return
        appState.presentConversationTransient(accountRef, group.groupIdHex, title)
    }

    private val mediaUploadSessionEpoch = appState.mediaUploadSessionEpoch()
    private val messageById = linkedMapOf<String, AppMessageRecordFfi>()
    internal val timelineRecords = linkedMapOf<String, TimelineMessageRecordFfi>()

    /** Whether [messageIdHex] is an authoritative row of the current window rather than a local optimistic id. */
    internal fun retainsTimelineRecord(messageIdHex: String): Boolean = timelineRecords.containsKey(messageIdHex)

    internal val timelineItemsById = linkedMapOf<String, TimelineMessage>()
    private val timelineOrder = mutableListOf<String>()
    internal val authoritativeTimelineOrderByMessageId = linkedMapOf<String, ULong>()
    private val durableStreamDisplayParentByMessageId = mutableMapOf<String, String>()
    private val optimisticMessages = appState.optimisticMessages(conversationAccountRef, initialGroup.groupIdHex)
    private val optimisticSendPhases =
        appState.optimisticSendPhases(conversationAccountRef, initialGroup.groupIdHex)
    private val durableAcceptanceCallbacks =
        appState.durableAcceptanceCallbacks(conversationAccountRef, initialGroup.groupIdHex)
    private val initialTimeline =
        initialConversationTimeline(
            preview = initialTimelinePreview,
            groupIdHex = initialGroup.groupIdHex,
            pendingConfirmation = group.pendingConfirmation || initialInviteConfirmationUnresolved,
            optimisticMessages = optimisticMessages.values,
        )

    /** Whether construction had enough complete local UI state to paint a real bubble. */
    val hadInitialTimelineSeed: Boolean = initialTimeline.isNotEmpty()

    /** True only until MDK's first authoritative page replaces the construction seed. */
    var initialTimelineSeedActive by mutableStateOf(hadInitialTimelineSeed)
        private set

    var timeline by mutableStateOf(initialTimeline)
        private set

    /** UI identity aliases only, bounded to this controller's retained timeline window. */
    private val timelinePresentationIds = mutableMapOf<String, String>()

    /** Recovery generation represented by the latest authoritative timeline. */
    var recoveryProjectionGeneration by mutableLongStateOf(0L)
        private set

    var hasPublishedAuthoritativeTimeline by mutableStateOf(false)
        private set

    /**
     * Test-only seam: navigation promotes a chat-list tap only after the authoritative page publishes, and
     * screen-level tests can't run the FFI subscription that flips this in production.
     */
    @VisibleForTesting
    internal fun markAuthoritativeTimelinePublishedForTest() {
        hasPublishedAuthoritativeTimeline = true
    }

    /** Local sender metadata needed for a settled first presentation is ready. */
    var hasPreparedInitialPresentation by mutableStateOf(false)
        private set

    /**
     * True while the first authoritative page names event cards and is held unpublished until their cached state has
     * been read, or the card budget ran out (#2238). Every path that shows the conversation then composes those rows
     * only with their cards complete, and the chat-list open may wait the card budget instead of its usual bound.
     * Changes made meanwhile, a send included, publish with the page, at most the card budget after it arrived.
     */
    var isHoldingInitialPageForEventCards by mutableStateOf(false)
        private set

    /** The engine proved this conversation is no longer available to the account. */
    var terminalConversationUnavailable by mutableStateOf(false)
        private set

    /** Current engine-owned recovery evidence for this conversation. */
    var groupRecoveryStatus by mutableStateOf<GroupRecoveryStatusFfi?>(null)
        private set
    private val groupRecoveryLifetime = StalenessGuard()

    /** Whether the latest recovery-status read failed while prior evidence stays inspectable. */
    var groupRecoveryReadFailed by mutableStateOf(false)
        private set

    /** Whether this group's first shown history notice says the account rejoined, so older messages may be missing. */
    var groupHistoryNoticeRejoined by mutableStateOf(false)
        private set

    /** Prevents duplicate accept/decline actions while MDK verifies the selected invitation. */
    var groupRecoveryMutationInFlight by mutableStateOf(false)
        private set

    /** The last answer MDK gave to whether it parked this chat's convergence, kept when a later read fails. */
    var groupConvergenceParked by mutableStateOf(false)
        private set

    /** Whether the user's explicit convergence retry is running. */
    var groupConvergenceRetrying by mutableStateOf(false)
        private set

    /** Why the latest convergence retry left the chat parked, as a localized message, or null. */
    @get:StringRes
    var groupConvergenceRetryFailure by mutableStateOf<Int?>(null)
        private set

    // Only the newest parked-flag read may publish, so a slow earlier read cannot overwrite a later answer.
    private val groupConvergenceReads = StalenessGuard()

    // One parked-flag read runs at a time, and requests made meanwhile fold into a single read after it.
    private val groupConvergenceReadGate = Mutex()
    private val groupConvergenceReadRequested = AtomicBoolean(false)

    /** Rereads in a row that were scheduled because a group-state edge made the previous answer stale. */
    private var groupConvergenceStaleRereads = 0

    // Completes once the conversation's first open settles, so the parked read never waits ahead of it.
    private val groupConvergenceOpenSettled = CompletableDeferred<Unit>()
    private val projectedMessageIds = appState.projectedMessageIds(conversationAccountRef, initialGroup.groupIdHex)
    internal val localTimelineOrderOverrides =
        appState.timelineOrderOverrides(conversationAccountRef, initialGroup.groupIdHex)
    internal val localTimelineTimestampOverrides =
        appState.timelineTimestampOverrides(conversationAccountRef, initialGroup.groupIdHex)

    // Subset of the preserves above that came from an optimistic *send* handoff
    // (not a stream-final live preview). These are transient and get released
    // once their optimistic bubble is gone, so a stale one can't pin a confirmed
    // row above a newer neighbour (#1578). Ownership lives in AppState alongside
    // the overrides so a replacement controller can finish the cleanup.
    private val optimisticSendPositionPreserves =
        appState.optimisticSendPositionPreserves(conversationAccountRef, initialGroup.groupIdHex)
    private val retentionAtSendByMessageId =
        appState.retentionAtSend(conversationAccountRef, initialGroup.groupIdHex)

    // Unlike immediately published text, accepted-pending text keeps its
    // temporary optimistic id. Retain MDK's canonical id across controller
    // replacement so the eventual projection settles the exact bubble.
    private val acceptedPendingTextOptimisticIds =
        appState.acceptedPendingTextOptimisticIds(conversationAccountRef, initialGroup.groupIdHex)
    private val preservedTimelinePositionOverrideIds = mutableSetOf<String>()
    private val durableStreamPositionOverrideIds = mutableSetOf<String>()

    // Holding pen for media projection echoes that arrive while their
    // matching bridge is still mid-`sendMediaAttachments`. Shared via
    // AppState so that if the user navigates out of the chat between echo
    // and bridge insert, the OLD controller's `performMediaUpload` still
    // sees the stash that the NEW controller's subscription contributed
    // to (or vice-versa).
    internal val pendingProjectionsAwaitingBridge =
        appState.pendingProjectionsAwaitingBridge(conversationAccountRef, initialGroup.groupIdHex)
    private val optimisticReactionChanges = linkedMapOf<String, OptimisticReactionChange>()
    private val unprojectedOwnReactionEventIds = linkedMapOf<Pair<String, String>, String>()
    private val reactionIntentConflator = ReactionIntentConflator()

    internal val window = ConversationWindowState()
    private val timelineSession =
        ConversationTimelineSession(onTimelineRetired = { window.readySubscription = null })
    private val controllerScope = timelineSession.scope

    /** First-frame snapshots of this conversation's public event cards, read from MDK before rows show (#2238). */
    private val eventCardPresentation =
        ConversationEventCardPresentation(
            appState = appState,
            conversationAccountRef = conversationAccountRef,
            currentScope = { conversationScope },
            latestInstalledPage = { timelineSubscription?.latestInstalledWindow()?.page },
            shownRecords = { timelineRecords },
            initialPresentationReady = { hasPreparedInitialPresentation },
        )
    internal val eventCardSnapshots get() = eventCardPresentation.eventCardSnapshots
    private val initialPresentationWarmCoordinator =
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
    private val inviteStreamScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
    private val conversationScope: CoroutineScope? get() = timelineSession.currentScope
    internal val accountTeardownRequested: Boolean get() = timelineSession.accountTeardownRequested
    private val controllerCleared: Boolean get() = timelineSession.cleared

    internal val inboundVisibleHostAttempt = HostPerformanceAttemptSlot()
    internal var inboundVisibleHostGeneration by mutableLongStateOf(0L)
        private set
    private val outboundVisibleHostAttempts = HostPerformanceAttemptRegistry()
    private val activeStreamIds = mutableSetOf<String>()
    private val foregroundSweepScheduleSignals = Channel<Unit>(Channel.CONFLATED)
    private var lastForegroundSweepStartedAtMillis = 0L

    // Transient streaming-debug rows keyed by their synthetic timeline id.
    // Lifecycle-scoped UI state for the live agent-stream watch — NOT a
    // persistent cache of protocol data (AGENTS.md): they exist only while the
    // developer toggle is on, are never written to the White Noise store, and
    // are dropped wholesale when the toggle turns off. Bounded so a long-lived
    // agent-heavy conversation can't grow them without limit.
    private val streamDebugTimelineItems = linkedMapOf<String, TimelineMessage>()

    // staleness-exempt: ordered synthetic debug-row identifier, not a latest-wins guard.
    private var streamDebugEventSequence: ULong = 0uL

    // Bounded LRU set: tombstones are capped so an agent-heavy conversation
    // kept open for a long time can't grow memory or per-batch filter cost
    // without bound. See #200.
    private val removedStreamIds = BoundedStreamTombstones()
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
        private set

    // A persisted watermark is not a read performed during this visit. The
    // first settled visible message must reach MDK even when the watermark
    // stays unchanged: MDK also clears manual unread attention on that read.
    private var lastSubmittedReadMessageId: String? = null
    private var manualUnreadReminderConsumed = false
    private var nextReadRequestId = 0L
    private var lastReadRequestId = 0L
    private val pendingReadRequestIds = mutableSetOf<Long>()
    private var lastSuccessfulReadRequestId = 0L
    private var successfulReadWithoutRow: Pair<ChatListRowFfi?, String>? = null

    // Persisted read watermark from the chat-list projection / mark-read FFI.
    // Drives read-anchored disappearing-message deferral (#797).
    private var persistedLastReadTimelineAt: ULong? = initialChatListRow?.lastReadTimelineAt

    // Session display/read anchors for received rows and local optimistic
    // sends. They override send-time expiry only when the row also carries
    // its pinned retention duration; projected sends otherwise use MDK's
    // authoritative deadline.
    private val readAnchoredAtSeconds = mutableMapOf<String, ULong>()

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
        )
    }

    val inviteAccount: String?
        get() {
            val me = conversationAccountIdHex
            val other = GroupProjector.otherMemberAccount(members, me)
            return GroupProjector.inviteAccount(group, other)
        }

    /**
     * The peer account whose profile picture stands in for a 1:1 conversation's
     * avatar, mirroring the chat-list row (#837): the inviter for a pending
     * invite, otherwise the lone counterparty of an unnamed two-member chat.
     * Null for multi-member or named groups, which use their own group avatar.
     */
    val avatarAccount: String?
        get() {
            val identity =
                conversationIdentityProjection(
                    members = members,
                    activeAccountIdHex = conversationAccountIdHex,
                    acceptedInvitePeerAccount = acceptedInvitePeerAccount,
                )
            return GroupProjector.avatarAccount(group, identity.otherMemberAccount, identity.memberCount)
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
     * [alreadyDeleted] defaults to the controller's optimistic/reconciled
     * tombstone set; the UI passes its projection-aware `deleted` instead so
     * a remotely deleted message reads as undeletable there too.
     */
    fun deleteCapabilityFor(
        message: AppMessageRecordFfi,
        alreadyDeleted: Boolean = message.messageIdHex in deletedMessageIds,
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

    /** Presents startup outcomes while the timeline session owns admission and finalization. */
    private suspend fun runStart(account: String) {
        isLoading = true
        terminalConversationUnavailable = false
        subscriptionError = null
        pageError = null
        terminalLoadFailure = false
        try {
            timelineSession.runReadScope {
                // Global relay catch-up is owned by the chat-list/foreground
                // lifecycle. The timeline and group handles below subscribe to
                // local projections, so opening them does not need another
                // account-worker sync; reactions render their optimistic overlay
                // before any authoritative commit waits for that worker.
                // Local NIP-40 enforcement (#333) + secure delete (#334): on open
                // and then at the next loaded row's expiry boundary, securely wipe
                // plaintext past the retention window via the engine and re-publish
                // so rows leave the open timeline while the user is still watching.
                // The timer is lifecycle-bound to this conversation and is
                // rescheduled by timeline/group-state publishes; when there is no
                // loaded row near expiry it falls back to the old slow cadence.
                val foregroundSweepJob = launch { runForegroundDisappearingMessageSweep(account) }
                val convergenceEventsJob = launch { runGroupConvergenceEventLoop(account) }
                try {
                    runConversationSubscriptionLoop(account)
                } finally {
                    // A terminal initial-open failure returns normally from
                    // the subscription loop. Stop its infinite sibling so
                    // runStart can finish and release its lifecycle state.
                    foregroundSweepJob.cancel()
                    convergenceEventsJob.cancel()
                }
            }
        } catch (cancel: CancellationException) {
            // Expected when the conversation screen leaves the composition.
            // Re-throw so cancellation propagates and we don't log it as an
            // error.
            throw cancel
        } catch (throwable: Throwable) {
            windowPresentationTiming.fail()
            if (throwable.isUseAfterEviction()) {
                discardInitialTimelineSeedForFailure(preserveOptimisticMessages = false)
                markActiveAccountRemovedFromMembers(account)
                isLoading = false
                terminalConversationUnavailable = true
                subscriptionError = null
                pageError = null
                return
            }
            discardInitialTimelineSeedForFailure(preserveOptimisticMessages = true)
            isLoading = false
            subscriptionError = privacySafeErrorPresentation("CONVERSATION_LOAD", throwable)
            terminalLoadFailure = true
        }
    }

    private fun discardInitialTimelineSeedForFailure(preserveOptimisticMessages: Boolean) {
        if (!shouldDiscardInitialTimelineSeedForFailure(hasPublishedAuthoritativeTimeline)) return
        // A failure resolves the first presentation, so a page held for its cards must not stay hidden.
        isHoldingInitialPageForEventCards = false
        initialTimelineSeedActive = false
        if (preserveOptimisticMessages) {
            publishTimelineFromIndexes()
        } else {
            // Use-after-eviction means this account no longer owns the group.
            // Do not keep any plaintext row visible under an unavailable owner.
            timeline = emptyList()
        }
        // Failure is an authoritative resolution of the initial presentation:
        // there is no page left to await. Let the route reconcile/reveal the
        // retained optimistic rows or switch to its terminal-unavailable surface.
        hasPublishedAuthoritativeTimeline = true
        hasPreparedInitialPresentation = true
    }

    /** Publishes an empty first page, which names no card, so any earlier card hold ends with it. */
    private fun publishAuthoritativeEmptyInitialTimeline() {
        isHoldingInitialPageForEventCards = false
        hasPublishedAuthoritativeTimeline = true
        hasPreparedInitialPresentation = true
        initialTimelineSeedActive = false
        publishTimelineFromIndexes()
        windowPresentationTiming.timelinePublished()
    }

    /** Runs MDK's authoritative sweep while any loaded row owns a deadline. */
    private suspend fun runForegroundDisappearingMessageSweep(account: String) {
        while (coroutineContext.isActive) {
            val expiryRows = foregroundSweepExpiryRows()
            if (expiryRows.any(DisappearingMessageSweep::hasLocalExpiry)) {
                val nowMillis = clockMillis()
                lastForegroundSweepStartedAtMillis = nowMillis
                // Engine-owned gate + prune: the account worker runs the
                // skew/unread deferrals atomically with the prune, serialized
                // against this conversation's own sends. Android applies the
                // pruned outcome — tray-card dismissal (#333) and media-cache
                // eviction (#334) — for this group here, with precise keys
                // from the loaded timeline; any other pruned group of the
                // account goes through the shared closed-conversation path.
                val outcome =
                    appState.runRetentionSweep(account, nowMillis, handledGroupIdHex = group.groupIdHex)
                if (outcome != null) {
                    if (outcome.prunedMessages > 0uL) {
                        appState.dismissConversationNotifications(account, group.groupIdHex)
                    }
                    evictExpiredMediaCaches(account, outcome.mediaCiphertextSha256.toSet())
                }
                publishTimelineFromIndexes()
            }
            awaitForegroundDisappearingSweepSchedule()
        }
    }

    private suspend fun awaitForegroundDisappearingSweepSchedule() {
        while (coroutineContext.isActive) {
            while (foregroundSweepScheduleSignals.tryReceive().isSuccess) {
                // Drop stale self-signals before sleeping; the next timeout is
                // based on the timeline/group state that is current right now.
            }
            val wakeSignalReceived =
                withTimeoutOrNull(foregroundDisappearingSweepDelayMillis()) {
                    foregroundSweepScheduleSignals.receive()
                    true
                } == true
            if (shouldRunForegroundSweepAfterWake(wakeSignalReceived)) return
        }
    }

    /** Returns the next loaded row deadline, capped to the foreground cadence. */
    private fun foregroundDisappearingSweepDelayMillis(): Long =
        DisappearingMessageSweep.nextForegroundSweepDelayMillis(
            nowMillis = clockMillis(),
            rows = foregroundSweepExpiryRows(),
        )

    /** Projects loaded timeline messages into their row-owned expiry inputs. */
    private fun foregroundSweepExpiryRows(): List<DisappearingMessageSweep.LocalExpiryRow> {
        val messages = sweepExpiryMessages()
        val messageOrder = firstMessageOrder(messages.map { it.record.messageIdHex })
        return messages
            .filter { shouldApplyLocalDisappearingExpiry(it.record) }
            .map { localExpiryRow(it, messageOrder) }
    }

    /** Returns every loaded row so a group-system read anchor keeps its ordering position. */
    private fun sweepExpiryMessages(): List<TimelineMessage> =
        buildList {
            addAll(optimisticMessages.values)
            timelineOrder.mapNotNullTo(this) { timelineItemsById[it] }
        }

    /** Builds expiry input without consulting the group's mutable policy. */
    private fun localExpiryRow(
        message: TimelineMessage,
        messageOrder: Map<String, Int>,
    ): DisappearingMessageSweep.LocalExpiryRow {
        val record = message.record
        val authoritativeExpiry = record.retentionExpiresAt?.takeIf { it > 0uL }
        return DisappearingMessageSweep.LocalExpiryRow(
            timelineAtSeconds = record.recordedAt,
            // The engine's authoritative per-message expiry wins over the
            // send-time + retention arithmetic below when it is projected.
            // Zero is not a real expiry (the engine emits null when retention
            // is off); guard like message info does so a zero could never
            // read as epoch-expired and hide the row instantly.
            expiresAtLocalSeconds = authoritativeExpiry,
            retentionAtSendSeconds =
                record.retentionSeconds?.takeIf { authoritativeExpiry != null && it > 0uL }
                    ?: message.retentionAtSendSeconds?.takeIf { it > 0uL },
            readAnchoredAtSeconds = readAnchoredAtSeconds[record.messageIdHex],
            deferSendTimeExpiry =
                isDisappearingSendTimeExpiryDeferred(
                    record = record,
                    lastReadMessageId = lastReadMessageId,
                    lastReadTimelineAt = persistedLastReadTimelineAt,
                    messageOrder = messageOrder,
                ),
        )
    }

    /**
     * The retained timeline row for the raw message id [messageId]. The row map is keyed by item id
     * (`msg:<hex>`, or `stream:<id>` for stream starts), so the id is resolved the way [removeProjectedRecord]
     * resolves it. Null when the bounded window no longer holds the row.
     */
    internal fun retainedTimelineItem(messageId: String): TimelineMessage? {
        val itemId = timelineRecords[messageId]?.let(::projectedItemId) ?: "msg:$messageId"
        return timelineItemsById[itemId]
    }

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

    /** The retained row's expiry input, ordered like the timeline filter and the sweep (optimistic ids first). */
    private fun retainedExpiryRow(messageId: String): Pair<TimelineMessage, DisappearingMessageSweep.LocalExpiryRow>? {
        val item = retainedTimelineItem(messageId) ?: return null
        val order = firstMessageOrder(sweepExpiryMessages().map { it.record.messageIdHex })
        return item to localExpiryRow(item, order)
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

    /** The wall-clock deadline in milliseconds of the retained row [messageId], or null without a resolved deadline. */
    private fun retainedRowExpiryDeadlineMillis(messageId: String): Long? {
        val seconds =
            retainedExpiryRow(messageId)?.let { (item, row) ->
                DisappearingMessageSweep
                    .resolveLocalExpirySeconds(row)
                    ?.takeIf { shouldApplyLocalDisappearingExpiry(item.record) }
            } ?: return null
        return java.util.concurrent.TimeUnit.SECONDS
            .toMillis(seconds.coerceAtMost(Long.MAX_VALUE.toULong()).toLong())
    }

    /** Distinguishes timeout wakes from publish signals that expose due rows. */
    private fun shouldRunForegroundSweepAfterWake(wakeSignalReceived: Boolean): Boolean =
        DisappearingMessageSweep.shouldRunForegroundSweepAfterWake(
            wakeSignalReceived = wakeSignalReceived,
            nowMillis = clockMillis(),
            lastSweepStartedAtMillis = lastForegroundSweepStartedAtMillis,
            rows = foregroundSweepExpiryRows(),
        )

    private fun signalForegroundSweepScheduleChanged() {
        foregroundSweepScheduleSignals.trySend(Unit)
    }

    /** Retires this account's reads before native teardown; accepted mutations keep their app owner. */
    suspend fun closeLiveSubscriptionsForAccountTeardown(accountRef: String) {
        if (!shouldTeardownLiveSubscriptionsForAccount(accountRef, conversationAccountRef, conversationAccountRef)) return
        timelineSession.closeForAccountTeardown { memberRosterRefreshGeneration.advance() }
    }

    private fun isAccountTeardownRequested(): Boolean = timelineSession.accountTeardownRequested

    // The initial page is a local read, but it crosses the FFI boundary — a
    // hung call would leave the chat-list tap permanently inert because
    // navigation cannot promote until the first page publishes. Bound it, and
    // convert exhaustion into the ordinary load failure whose surface already
    // promotes navigation and offers retry. Every subscription open, including
    // reconnects with a retained window, consumes its one-shot snapshot before
    // waiting for live updates.
    private suspend fun publishInitialTimelineSnapshot(
        account: String,
        timelineStream: ConversationTimelineSubscriptionHandle,
    ): List<String> {
        val snapshot = timelineSession.snapshot(timelineStream)
        windowPresentationTiming.begin(
            receivedAtElapsedMs = SystemClock.elapsedRealtime(),
            ticket = appState.productObservationTicket(),
        )
        val recoveryGeneration =
            appState.recoveryDiagnostics.recordTimelineSubscriptionReceived(
                count = snapshot?.messages?.size ?: 0,
            )
        return if (snapshot == null) {
            if (timelineRecords.isEmpty()) publishAuthoritativeEmptyInitialTimeline()
            publishRecoveryTimelineProjection(recoveryGeneration)
            emptyList()
        } else {
            hasLoadedOlderPages = false
            protectedTimelineMessageIds.clear()
            // A reconnect's snapshot reads its cards first; a first page has its own hold.
            val warmedSnapshot = warmLaterPageEventCards(snapshot)
            val streamIds =
                appState.measureHostPerformance(HostPerformanceOperationFfi.TIMELINE_APPLY) {
                    applyTimelinePage(
                        warmedSnapshot,
                        replaceWindow = true,
                        updatePagination = true,
                    )
                }
            initializeReadState(account)
            publishRecoveryTimelineProjection(recoveryGeneration)
            publishReadyTimelineSubscription(timelineStream)
            streamIds
        }
    }

    /** Publishes a captured recovery generation only after timeline state changed. */
    private fun publishRecoveryTimelineProjection(generation: Long?) {
        if (
            generation != null &&
            appState.recoveryDiagnostics.recordTimelineProjectionPublished(generation, timeline.size)
        ) {
            recoveryProjectionGeneration = generation
        }
    }

    /**
     * Retry loop for the timeline + group-state live subscriptions. Extracted
     * from [start] so R8 can compile the smaller suspend entrypoint (the
     * monolithic method hit an invalid stack-map-table bug in release builds).
     */
    private suspend fun runConversationSubscriptionLoop(account: String) {
        timelineSession.runReconnectLoop { runConversationSubscriptionIteration(account) }
    }

    /**
     * One connect/reconnect attempt. Returns whether the caller should exit
     * until explicit retry (account evicted or a native open timed out) and
     * whether the subscriptions connected successfully (so the retry backoff
     * can reset).
     */
    private suspend fun runConversationSubscriptionIteration(account: String): Pair<Boolean, Boolean> {
        val timelineOpen = appState.beginHostPerformance(HostPerformanceOperationFfi.TIMELINE_OPEN)
        var groupSubscription: ConversationGroupStateSubscriptionHandle? = null
        var timelineStream: ConversationTimelineSubscriptionHandle? = null
        // A notification-routed group hides its transcript until the roster lands (#586).
        val rosterPrefetch = conversationScope?.prefetchGroupRoster(group.groupIdHex, account, groupRosterReader)
        try {
            timelineStream =
                timelineSession.openTimeline {
                    liveSubscriptions.openTimeline(account, group.groupIdHex, ConversationTimelinePageLimit)
                } ?: return true to false
            val snapshotStreamIds = publishInitialTimelineSnapshot(account, timelineStream)
            timelineOpen.success()
            // Don't blanket-mark the absolute newest as read here — the UI
            // layer now drives mark-read as the user scrolls so partial-read
            // sessions retain accurate unread counts on the chat list.

            var connected = false
            val initialMetadataReady = CompletableDeferred<Unit>()

            coroutineScope {
                runUntilFirstLiveSubscriptionEndsWithAttemptJobs(
                    startAttemptJobs = {
                        // Snapshot-time agent streams are attempt-scoped: cancel them when
                        // either live subscription ends, but do not join them before
                        // reconnecting and closing the dropped live subscription handles.
                        // Batch-time streams below remain timeline-pipeline children.
                        snapshotStreamIds.forEach { streamId ->
                            if (activeStreamIds.add(streamId)) {
                                launch { watchAgentTextStream(account, streamId) }
                            }
                        }
                    },
                    first = {
                        runTimelineSubscriptionPipeline(account, timelineStream)
                        // Normal EOF must not cancel the initial roster before it can settle
                        // loading/disclosure. Failures still unwind the paired attempt immediately.
                        initialMetadataReady.await()
                    },
                    second = {
                        // The authoritative timeline is already installed. Keep consuming its
                        // bounded replacements while independent group/roster reads settle;
                        // transcript consent and membership presentation retain their own gates.
                        // Otherwise a slow roster can leave background messages queued until
                        // well after the retained conversation's first foreground frame (#3178).
                        val groupStream =
                            timelineSession.openGroup { liveSubscriptions.openGroupState(account, group.groupIdHex) }
                                ?: return@runUntilFirstLiveSubscriptionEndsWithAttemptJobs
                        groupSubscription = groupStream
                        val groupSnapshot =
                            withContext(Dispatchers.IO) {
                                groupStream.snapshot()
                            }
                        groupSnapshot?.let(::applyGroupState)
                        refreshGroupRecoveryStatus()
                        refreshMembersAfterGroupState(prefetchedRoster = rosterPrefetch)
                        isLoading = false
                        groupConvergenceOpenSettled.complete(Unit)
                        subscriptionError = null
                        connected = true
                        initialMetadataReady.complete(Unit)
                        runGroupStateSubscriptionLoop(groupStream)
                    },
                )
            }
            return false to connected
        } catch (cancel: CancellationException) {
            timelineOpen.cancel()
            throw cancel
        } catch (throwable: Throwable) {
            timelineOpen.failure()
            windowPresentationTiming.fail()
            if (throwable.isUseAfterEviction()) {
                discardInitialTimelineSeedForFailure(preserveOptimisticMessages = false)
                markActiveAccountRemovedFromMembers(account)
                isLoading = false
                terminalConversationUnavailable = true
                subscriptionError = null
                return true to false
            }
            val initialOpenTimedOut = isTerminalOpenFailure(throwable)
            discardInitialTimelineSeedForFailure(preserveOptimisticMessages = true)
            isLoading = false
            val operationCode = if (timelineRecords.isEmpty()) "CONVERSATION_LOAD" else "CONVERSATION_REFRESH"
            if (!BuildConfig.DEBUG) Log.e("DMConversation", releaseFailureMarker(operationCode, throwable))
            subscriptionError =
                privacySafeErrorPresentation(
                    operationCode = operationCode,
                    throwable = throwable,
                    message = AppText.Resource(conversationLoadFailureMessage(throwable, initialOpenTimedOut)),
                    retryable = shouldOfferConversationLoadRetry(throwable),
                )
            if (initialOpenTimedOut) {
                // A timed-out FFI call may still occupy its native blocking
                // worker. Automatic retries would accumulate more stuck calls;
                // stop here. The user must restart rather than launch another
                // native open while the single-flight owner is unresolved.
                timelineSession.cancelInitialOpen()
                terminalLoadFailure = true
                return true to false
            }
        } finally {
            timelineOpen.unavailable()
            rosterPrefetch?.cancel() // A no-op once awaited; an early return must not leave it running.
            timelineSession.closeHandles(groupSubscription, timelineStream)
        }
        return false to false
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

    /** Applies a canonical group update while retaining only a still-unresolved stale action fence. */
    private fun applyGroupState(incoming: AppGroupRecordFfi) {
        val named = localGroupNames.reconciled(incoming)
        val description = localGroupDescriptions.reconcile(named.groupIdHex, named.description)
        val update = if (description == named.description) named else named.copy(description = description)
        val previousGroup = group
        if (update.leaveBlocked) observedBlockedLeave = true
        if (update.selfMembership.isGroupEviction()) {
            observedBlockedLeave = false
            cancelledLeaveAwaitingRoster = false
        }
        groupRecoveryLifetime.advance()
        val previousRetention = group.disappearingMessageSecs
        mutationPresentation.authoritativeGroupApplied()
        val reconciled =
            reconcileTerminalSelfMembership(
                update = update,
                previous = previousGroup,
            )
        val confirmed = inviteConfirmationAuthority.reconcile(reconciled)
        inviteConfirmationUnresolved = false
        val awaitingAuthority = inviteAcceptanceAwaitingAuthority
        val freshTerminalReinvite = isDistinctWelcomeReinvite(previousGroup, update)
        group =
            if (awaitingAuthority?.matches(confirmed) == true && confirmed.pendingConfirmation) {
                confirmed.copy(pendingConfirmation = false)
            } else {
                inviteAcceptanceAwaitingAuthority = null
                confirmed
            }
        if (!inviteAcceptanceResolutionPending) inviteAcceptanceResolutionState = GroupRosterLoadState.READY
        if (freshTerminalReinvite) {
            memberRosterRefreshGeneration.advance()
            selfMembership.clearSelfLeft()
            membersVerified = false
            memberRosterLoadTracker.transition(GroupRosterRefreshEvent.STARTED)
        }
        if (group.selfMembership.isNonMember()) recordSelfLeft()
        if (previousRetention != update.disappearingMessageSecs) {
            publishTimelineFromIndexes()
        }
    }

    /**
     * Re-reads group recovery after each authoritative group-state edge. The parked flag is read separately, by the
     * raw-event loop, so this open-path read stays a single account-worker command.
     */
    @Suppress("TooGenericExceptionCaught") // Every non-cancellation native failure is classified for presentation.
    private suspend fun refreshGroupRecoveryStatus() {
        val accountRef = conversationAccountRef ?: return
        val groupIdHex = group.groupIdHex
        val runtimeGeneration = appState.runtimeGeneration
        val recoveryEpoch = groupRecoveryLifetime.capture()
        val status =
            try {
                retryTransientGroupRecoveryRead {
                    groupRecoveryStatusReader(accountRef, groupIdHex)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                // An advisory read that has never succeeded for a group this account just created
                // is not evidence of anything; see groupRecoveryReadFailureIsPresentable.
                val presentable =
                    groupRecoveryReadFailureIsPresentable(
                        lastConfirmedStatus = groupRecoveryStatus,
                        freshlyCreated = isFreshlyCreatedGroup(groupIdHex),
                        failure = failure,
                    )
                groupRecoveryLifetime.runIfCurrent(recoveryEpoch) {
                    if (ownsGroupRecoveryRead(accountRef, groupIdHex, runtimeGeneration)) {
                        groupRecoveryReadFailed = presentable
                    }
                }
                return
            }
        val rejoined = firstGroupNoticeRejoined(accountRef, status)
        groupRecoveryLifetime.runIfCurrent(recoveryEpoch) {
            if (
                ownsGroupRecoveryRead(accountRef, groupIdHex, runtimeGeneration) &&
                status.groupIdHex == groupIdHex
            ) {
                groupRecoveryStatus = status
                groupHistoryNoticeRejoined = rejoined
                groupRecoveryReadFailed = false
                appState.freshGroupCreations.settle(accountRef, groupIdHex, runtimeGeneration)
            }
        }
    }

    /**
     * Whether the first of [status]'s notice ids is a rejoin notice, the one the card words. The cause lives only in
     * the account's notice list, and a failed advisory read falls back to the general wording.
     */
    @Suppress("TooGenericExceptionCaught") // Any non-cancellation native failure keeps the general wording.
    private suspend fun firstGroupNoticeRejoined(
        accountRef: String,
        status: GroupRecoveryStatusFfi,
    ): Boolean {
        val first = status.historyNoticeIds.firstOrNull() ?: return false
        val notices =
            try {
                historyNoticesReader(accountRef)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                emptyList()
            }
        return notices.firstOrNull { it.noticeId == first }?.cause == HistoryNoticeCauseFfi.BEFORE_REJOIN
    }

    /** Whether this conversation is the group whose creation was opened most recently. */
    private fun isFreshlyCreatedGroup(groupIdHex: String): Boolean =
        appState.freshGroupCreations.isFresh(conversationAccountRef, groupIdHex, appState.runtimeGeneration)

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
     * Reads the parked flag now, or, when a read is already running, makes that reader read once more after it
     * finishes. A burst of events therefore costs at most one read in flight and one after it.
     */
    @VisibleForTesting
    internal suspend fun requestGroupConvergenceRead() {
        groupConvergenceReadRequested.set(true)
        while (groupConvergenceReadRequested.get() && groupConvergenceReadGate.tryLock()) {
            try {
                while (groupConvergenceReadRequested.getAndSet(false)) refreshGroupConvergenceParked()
            } finally {
                groupConvergenceReadGate.unlock()
            }
        }
    }

    /**
     * Reads the parked flag for Retry through the same gate as event reads, so a reread that a stale answer asks for
     * still runs. Returns the newest published answer, or null when none was published.
     */
    private suspend fun readGroupConvergenceParkedAfterRetry(): Boolean? {
        val answer =
            groupConvergenceReadGate.withLock {
                var latest = refreshGroupConvergenceParked()
                while (groupConvergenceReadRequested.getAndSet(false)) {
                    refreshGroupConvergenceParked()?.let { latest = it }
                }
                latest
            }
        // A request made just as the gate was released still gets its read.
        if (groupConvergenceReadRequested.get()) requestGroupConvergenceRead()
        return answer
    }

    /**
     * Reads whether MDK parked this chat's convergence and publishes the answer under the recovery guards. Returns
     * the published answer, or null when the read failed or lost ownership. A failed read keeps the last known
     * answer, since it says nothing about whether the chat is parked.
     */
    private suspend fun refreshGroupConvergenceParked(): Boolean? {
        val accountRef = conversationAccountRef
        val groupIdHex = group.groupIdHex
        val runtimeGeneration = appState.runtimeGeneration
        val recoveryEpoch = groupRecoveryLifetime.capture()
        val readEpoch = groupConvergenceReads.advance()
        // Any non-cancellation native failure keeps the last known answer.
        val parked =
            accountRef?.let { account ->
                runCatchingCancellable { groupConvergenceParkedReader(account, groupIdHex) }.getOrNull()
            } ?: return null
        var published = false
        groupRecoveryLifetime.runIfCurrent(recoveryEpoch) {
            if (
                groupConvergenceReads.isCurrent(readEpoch) &&
                accountRef != null &&
                ownsGroupRecoveryRead(accountRef, groupIdHex, runtimeGeneration)
            ) {
                groupConvergenceParked = parked
                if (!parked) groupConvergenceRetryFailure = null
                published = true
            }
        }
        // A group-state edge during the read only made this answer stale, so read again while this chat is still owned.
        // The rereads are bounded, so a steady stream of edges can't spin; the next event or foreground return reads.
        if (published) {
            groupConvergenceStaleRereads = 0
        } else if (
            groupConvergenceStaleRereads < MAX_GROUP_CONVERGENCE_STALE_REREADS &&
            groupConvergenceReads.isCurrent(readEpoch) &&
            ownsGroupRecoveryRead(accountRef, groupIdHex, runtimeGeneration)
        ) {
            groupConvergenceStaleRereads++
            groupConvergenceReadRequested.set(true)
        }
        return if (published) parked else null
    }

    /**
     * Reads the parked flag on entry and on each raw group-state event for this chat, which MDK fires whenever the
     * group's state changes, parks and resumes included. It starts only after the first open settles, so the open
     * path never waits on this account-worker read. Every successful open, the first included, reads once, since
     * an edge may pass before the stream exists. A stream that ends or fails is destroyed and reopened after a pause.
     */
    @VisibleForTesting
    internal suspend fun runGroupConvergenceEventLoop(
        account: String,
        awaitOpen: suspend () -> Unit = { groupConvergenceOpenSettled.await() },
        retryMillis: Long = GROUP_CONVERGENCE_EVENT_RETRY_MILLIS,
    ) {
        awaitOpen()
        while (currentCoroutineContext().isActive) {
            val stream = runCatchingCancellable { runtimeEventStreamOpener() }.getOrNull()
            if (stream != null) {
                try {
                    requestGroupConvergenceRead()
                    runCatchingCancellable {
                        while (true) {
                            val event = stream.next() ?: break
                            if (event.isGroupStateUpdateFor(account, group.groupIdHex)) requestGroupConvergenceRead()
                        }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { runCatching { stream.destroy() } }
                }
            }
            delay(retryMillis)
        }
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

    /** Whether this controller still owns UI publication for the captured group. */
    private fun ownsGroupRecoveryGroup(groupIdHex: String): Boolean = !controllerCleared && !isAccountTeardownRequested() && group.groupIdHex == groupIdHex

    /** Whether the captured account, group, and runtime still own recovery-read publication. */
    private fun ownsGroupRecoveryRead(
        accountRef: String,
        groupIdHex: String,
        runtimeGeneration: Int,
    ): Boolean =
        conversationAccountRef == accountRef &&
            appState.runtimeGeneration == runtimeGeneration &&
            ownsGroupRecoveryGroup(groupIdHex)

    private suspend fun runGroupStateSubscriptionLoop(groupStream: ConversationGroupStateSubscriptionHandle) {
        while (coroutineContext.isActive) {
            val update =
                withContext(Dispatchers.IO) {
                    groupStream.next()
                } ?: break
            val previousGroup = group
            applyGroupState(update)
            refreshGroupRecoveryStatus()
            if (groupStateUpdateRemovesSelf(previousGroup, update)) {
                conversationAccountRef?.let(::markActiveAccountRemovedFromMembers)
            }
            refreshMembersAfterGroupState()
        }
    }

    /** The first snapshot and live updates both require full readback after a blocked leave clears. */
    @VisibleForTesting
    internal suspend fun refreshMembersAfterGroupState(prefetchedRoster: Deferred<Result<GroupRosterFfi>>? = null) {
        if (!observedBlockedLeave || group.leaveRequestPending || !refreshManagementState()) {
            refreshMembers(prefetchedRoster = prefetchedRoster)
        }
    }

    /**
     * Cancel controller-owned scopes that outlive a single [start] call. The
     * conversation screen calls this once when it disposes the controller.
     *
     * [inviteStreamScope] launches post-accept warm-up and agent-stream watchers
     * from [acceptInvite], invoked from a separate mutation scope that can fire
     * after [start]'s loops have ended while the invite screen is still composed.
     * Cancelling it in [start]'s teardown left a dead scope, so the accepted
     * invite's agent-stream watchers never launched yet were marked active in
     * [activeStreamIds] and never retried — the streaming previews stayed stuck
     * (#279).
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

    /** Consumes authoritative MDK windows and publishes only the newest coalesced window. */
    private suspend fun CoroutineScope.runTimelineSubscriptionPipeline(
        account: String,
        timelineStream: ConversationTimelineSubscriptionHandle,
    ) {
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
                                    timelineStream.nextWindow()?.let { page -> page to handoff.received() }
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
                val streamIdsLaunched =
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
                publishReadyTimelineSubscription(timelineStream)
                // Older recovery is revision-gated inside the commit: a queued command echo must
                // not release its own failed attempt. Preserve forward prefetch's arrival budget.
                automaticPaging.newer.reset()
                publishRecoveryTimelineProjection(batch.mapNotNull { it.recoveryGeneration }.maxOrNull())
                if (!batchCommitted) handoff.settled(batch.map { it.foregroundHandoff }, applied = false)
                // Scroll-driven mark-read in the UI layer handles
                // the user-visible read pointer.
                streamIdsLaunched.forEach { streamId ->
                    if (activeStreamIds.add(streamId)) {
                        launch { watchAgentTextStream(account, streamId) }
                    }
                }
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
     * Parse the sent text into the same Markdown AST projected records carry
     * and rebind it onto the already-published optimistic bubble. The chat-list
     * preview is not published until this attempt finishes, so it never exposes
     * a raw-Markdown intermediate frame. Native admission runs independently;
     * only presentation reconciliation waits for this document, so it cannot
     * overwrite the styled bubble with the pre-parse snapshot.
     * A parse failure keeps the plain-text presentation. The chat list publishes
     * that fallback only after the parse attempt completes, so it cannot flash a
     * raw frame before replacing it with styled content. In either case,
     * a bubble already reconciled or rolled back is left alone. The parsed
     * record still backs preview reconciliation when an early echo removed
     * the optimistic bubble, so the chat list cannot lose its styled tokens.
     */
    private suspend fun hydrateOptimisticSendMarkdown(
        optimisticKey: String,
        optimistic: AppMessageRecordFfi,
    ): AppMessageRecordFfi {
        val tokens =
            runCatchingCancellable { markdownParser(optimistic.plaintext) }
                .getOrNull()
                ?.takeIf { it.blocks.isNotEmpty() }
                ?: return optimistic
        val pending = optimisticMessages[optimisticKey]
        return if (pending == null || pending.record.messageIdHex != optimistic.messageIdHex) {
            optimistic.copy(contentTokens = tokens)
        } else {
            val hydrated = pending.record.copy(contentTokens = tokens)
            optimisticMessages[optimisticKey] = pending.copy(record = hydrated)
            messageById[optimistic.messageIdHex] = hydrated
            publishTimelineFromIndexes()
            hydrated
        }
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
     * or a terminal group).
     * The edit path also leaves [onAccepted] uncalled: the composer restores its
     * pre-edit draft via the `editingMessageId` LaunchedEffect, not by clearing.
     */
    suspend fun send(
        text: String,
        onAccepted: () -> Unit = {},
        onTerminalFailure: () -> Unit = {},
        onPresentationAccepted: (String) -> Unit = {},
        onDurablyAccepted: () -> Unit = {},
    ) {
        if (showsBlockedLeave) {
            // Dictation's send action and the in-conversation wave and location shares reach here without the
            // composer gate, so say why.
            if (text.isNotBlank()) appState.presentTransient(blockedLeaveHint())
            return
        }
        val trimmed = text.trim()
        if (exceedsOutgoingMessageLimit(trimmed)) {
            // Sends and edits both stop here, before any optimistic bubble, so the composer keeps its text.
            appState.present(R.string.toast_message_too_long)
            return
        }
        val accountRef = conversationAccountRef
        if (
            !canAcceptTextSend(
                accountRef = accountRef,
                trimmed = trimmed,
                membersVerified = membersVerified,
                isSelfMember = isSelfMember,
                seededSelfMember = seededSelfMember,
                selfLeft = selfMembership.selfLeft,
                unrecoverable = group.unrecoverable,
                disbanding = group.disbanding,
                disbanded = group.disbanded,
            )
        ) {
            // A positive seeded member is accepted during refresh above. A
            // visible composer can still reach this guard from a genuinely
            // unknown notification-open state; preserve the draft and surface
            // that the handoff has not started instead of dropping it silently.
            val sendHasContent = accountRef != null && trimmed.isNotEmpty()
            val membershipUnknown = !membersVerified && !seededSelfMember && !selfMembership.selfLeft
            val groupCanEventuallySend = !group.unrecoverable && !group.disbanding && !group.disbanded
            if (sendHasContent && membershipUnknown && groupCanEventuallySend) {
                appState.present(R.string.toast_send_membership_verifying)
            }
            return
        }
        // Non-null guaranteed by canAcceptTextSend above.
        val account = requireNotNull(accountRef)

        // Edit mode short-circuits the normal send path: publish a kind-1009
        // edit instead, then clear edit state. The bubble's text rebinds
        // automatically once the kind-1009 echoes back into the timeline and
        // [editsByTarget] picks it up.
        val editTarget = editingMessageId
        if (editTarget != null) {
            // Native projection tokens come from MDK's retained submission ledger.
            // Keep native edit ordering after publication and controller replacement too.
            val originalToken =
                pendingMessageEditHandoff.originalClientToken(pendingEditKey(editTarget))
                    ?: optimisticEdits[editTarget]
                        ?.takeIf { it.status != MessageStatus.Sent }
                        ?.durableIntent
                        ?.originalClientToken
                    ?: timelineRecords[editTarget]?.clientToken
            if (originalToken != null && (textPublisher == null || pendingMessageEditPublisher != null)) {
                var session = editSessionRevision
                // Local admission is independent of relay publication. Until it succeeds the editor
                // retains its revision; an unadmitted original cannot silently consume this edit.
                val admitted =
                    admitPendingEdit(editTarget, originalToken, trimmed, onAttemptStarted = {
                        editSessionRevision += 1
                        session = editSessionRevision
                    })
                if (admitted && editSessionRevision == session) {
                    pendingMessageEditHandoff.abandon(pendingEditKey(editTarget))
                    editingMessageId = null
                }
                return
            }
            val handoffKey = pendingEditKey(editTarget)
            if ("msg:$editTarget" in optimisticMessages && !pendingMessageEditHandoff.hasSession(handoffKey)) {
                pendingMessageEditHandoff.begin(handoffKey, editTarget)
            }
            val editSubmission = pendingMessageEditHandoff.submit(handoffKey, editTarget, trimmed)
            editingMessageId = null
            when (editSubmission) {
                is PendingMessageEditHandoff.Submission.Publish -> editMessage(editSubmission.targetId, trimmed)
                PendingMessageEditHandoff.Submission.Deferred -> {
                    // The UUID is a client token, not an event id. Keep the
                    // revision visible, but publish no kind-1009 until the
                    // original has a confirmed target.
                    val previous = optimisticEdits[editTarget]?.preEditText ?: currentDisplayedText(editTarget)
                    optimisticEdits[editTarget] = OptimisticEdit(trimmed, previous, MessageStatus.Pending)
                    publishTimelineFromIndexes()
                }
            }
            return
        }
        val sendHostAttempt = appState.beginHostPerformance(HostPerformanceOperationFfi.MESSAGE_SEND)
        val outboundVisibleAttempt =
            appState.beginHostPerformance(HostPerformanceOperationFfi.OUTBOUND_MESSAGE_VISIBLE)

        val replyTarget = replyingTo?.messageIdHex?.takeIf { it.isNotBlank() }
        // WNPerf assigns an opaque process-local operation id only while the
        // user has explicitly enabled the bounded diagnostic session.
        val trace = PerformanceDiagnostics.begin(PerformanceOperation.TEXT_SEND)
        sendTrace(trace, PerformancePhase.ACCEPTED, elapsedMs = 0L, result = PerformanceResult.PENDING)
        val tempId = UUID.randomUUID().toString()
        outboundVisibleHostAttempts.register(tempId, outboundVisibleAttempt)
        appState.pendingSendDiagnostics.track(tempId, trace)
        val now = nowSeconds()
        val retentionAtSendSeconds = rememberRetentionAtSend(tempId, group.disappearingMessageSecs)
        val optimistic =
            AppMessageRecordFfi(
                messageIdHex = tempId,
                direction = "sent",
                groupIdHex = group.groupIdHex,
                sender = conversationAccountIdHex ?: "",
                plaintext = trimmed,
                // Publish with an empty AST so the bubble reaches the very next
                // frame without first suspending on the parse FFI's IO hop — a
                // congested IO lane used to hold the whole bubble hostage. An
                // empty document renders the plaintext unstyled;
                // hydrateOptimisticSendMarkdown below rebinds the styled
                // document the projected record will carry.
                contentTokens = EMPTY_MARKDOWN_DOCUMENT,
                kind = 9uL,
                tags =
                    replyTarget
                        ?.let {
                            listOf(MessageProjector.eventTag(it), MessageProjector.quoteTag(it))
                        }.orEmpty(),
                sourceEpoch = null,
                retentionSeconds = null,
                retentionExpiresAt = null,
                recordedAt = now,
                receivedAt = now,
                sticker = null,
            )
        val optimisticOrder = nextOptimisticTimelineOrder()
        val optimisticKey = "msg:$tempId"
        optimisticMessages[optimisticKey] =
            TimelineMessage(
                optimisticKey,
                optimistic,
                MessageStatus.Pending,
                timelineOrder = optimisticOrder,
                retentionAtSendSeconds = retentionAtSendSeconds,
            )
        optimisticSendPhases[optimisticKey] = OptimisticSendPhase.PRE_ACCEPTANCE
        durableAcceptanceCallbacks[optimisticKey] = onDurablyAccepted
        messageById[tempId] = optimistic
        val chatListPreviewReserved =
            appState.reserveOptimisticSentPreview(
                conversationAccountRef,
                group.groupIdHex,
                tempId,
            )
        publishTimelineFromIndexes()
        replyingTo = null
        // The optimistic bubble is now in the projection and published — the
        // send has visibly started. Only now is it safe to clear the input and
        // draft (issue #264): clearing earlier, synchronously in the UI on the
        // mere act of dispatching this coroutine, lost the text whenever a
        // guard above bailed before this point.
        onPresentationAccepted(optimisticKey)
        onAccepted()
        // The optimistic bubble is now published: the pending clock is on
        // screen. The chat-list order is reserved, but its preview waits for
        // the parser so raw Markdown never flashes there.
        sendTrace(trace, PerformancePhase.OPTIMISTIC_SHOWN, result = PerformanceResult.PENDING)
        appState.pendingSendDiagnostics.update(tempId, PerformanceSendStage.OPTIMISTIC)
        // Reconciliation and failure retention use the completed presentation,
        // but neither presentation step gates native admission.
        var publishedRecord = optimistic
        try {
            val summary =
                coroutineScope {
                    // Enter the existing send-order/commit locks before presentation
                    // can suspend. Keep rejection as data until the styled failure
                    // row is ready; cancellation still cancels both branches.
                    val admission =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            runCatchingCancellable {
                                publishTextWithRetry(replyTarget, account, trimmed, trace, tempId, optimisticKey)
                                    .also { completeDurableAcceptance(optimisticKey) }
                            }
                        }
                    publishedRecord = hydrateOptimisticSendMarkdown(optimisticKey, optimistic)
                    // Keep the prior chat-list row while the parser is in flight. The
                    // optimistic preview is published only with a completed document,
                    // never as a raw frame followed by a styled replacement (#2411).
                    // An empty/failed parse publishes one stable plaintext fallback only
                    // after the attempt completes. Check cancellation and apply without
                    // suspending on this controller's context: a cancel cannot interleave,
                    // and a native publish holding the commit lock cannot delay styling.
                    requireOptimisticSendNotCancelled(optimisticKey)
                    val previewApplied =
                        chatListPreviewReserved &&
                            appState.applyReservedOptimisticSentPreview(
                                conversationAccountRef,
                                group.groupIdHex,
                                ChatListMessagePreviewFfi(
                                    retentionSeconds = null,
                                    retentionExpiresAt = null,
                                    messageIdHex = tempId,
                                    sender = conversationAccountIdHex ?: "",
                                    senderDisplayName = null,
                                    plaintext = trimmed,
                                    contentTokens = publishedRecord.contentTokens,
                                    kind = 9uL,
                                    timelineAt = now,
                                    deleted = false,
                                    // A row we are optimistically publishing carries no deletion evidence.
                                    deletionSource = DeletionSourceFfi.UNKNOWN,
                                    attachmentKind = null,
                                    attachmentCount = 0u,
                                    groupSystem = null,
                                    deliveryState = ChatListMessageDeliveryStateFfi.PENDING,
                                    sticker = null,
                                ),
                            )
                    if (previewApplied == false) {
                        // No bound row to bump (pre-first-frame open, brand-new group,
                        // account-pinned window) — the engine echo will still update the
                        // list, but keep the drop visible in the send trace.
                        sendTrace(trace, PerformancePhase.CHAT_LIST_PREVIEW_DROPPED, result = PerformanceResult.FAILURE)
                    }
                    admission.await().getOrThrow()
                }
            val reconciliation =
                reconcileSuccessfulTextSend(
                    summaryMessageIds = summary.messageIds,
                    acceptDisposition = summary.acceptDisposition,
                    optimisticKey = optimisticKey,
                    tempId = tempId,
                    optimisticRecord = publishedRecord,
                    optimisticMessages = optimisticMessages,
                    messageById = messageById,
                    projectedMessageIds = projectedMessageIds,
                    timelineOrder = optimisticOrder,
                    acceptedPendingTextOptimisticIdsByMessageId = acceptedPendingTextOptimisticIds,
                )
            handoffPendingMessageEdit(
                tempId,
                reconciliation.confirmedId,
                ready = !reconciliation.acceptedPending && !reconciliation.awaitingEcho,
            )
            appState.pendingSendDiagnostics.alias(tempId, reconciliation.confirmedId)
            appState.pendingSendDiagnostics.recordAcceptedPending(tempId, reconciliation.acceptedPending)
            // MDK drains durable submissions and schedules convergence. A host recovery
            // call here would hold the group commit lock across network maintenance,
            // preventing the next message from even reaching durable admission.
            if (reconciliation.awaitingProjection) {
                appState.pendingSendDiagnostics.update(tempId, PerformanceSendStage.WAITING_PROJECTION)
            }
            if (!reconciliation.acceptedPending) {
                transferRetentionAtSend(tempId, reconciliation.confirmedId)
                appState.commitOptimisticSentPreview(
                    accountRef = conversationAccountRef,
                    groupIdHex = group.groupIdHex,
                    optimisticMessageIdHex = tempId,
                    confirmedMessageIdHex = reconciliation.confirmedId,
                )
                invalidatedProjectionIdsMatchingMessage(timelineRecords, reconciliation.confirmed)
                    .forEach(::removeProjectedRecord)
            }
            val insertedSent = reconciliation.insertedSent
            publishTimelineFromIndexes()
            // The publish returned and the pending clock flips to sent here only
            // when the projected engine echo has not already landed. If echo
            // reconciliation consumed the temp bubble first, `echo-reconcile` is
            // the actual clock → tick latency and this later event is just send
            // completion; don't label it as another `sent-flip`.
            sendTrace(
                trace,
                if (insertedSent) PerformancePhase.SENT_FLIP else PerformancePhase.SEND_COMPLETE,
            )
            // When we keep the temp bubble for echo reconciliation, leave the
            // trace entry so `echo-reconcile` can still be logged.
            if (!reconciliation.awaitingProjection) {
                appState.pendingSendDiagnostics.forget(tempId)
            }
            if (!reconciliation.acceptedPending) {
                optimisticSendPhases.remove(optimisticKey)
            }
            sendHostAttempt.success()
        } catch (throwable: Throwable) {
            if (throwable is OptimisticSendCancelledException) {
                sendHostAttempt.cancel()
                discardedDuringRetry.remove(optimisticKey)
                removeCancelledOptimisticSend(optimisticKey, tempId)
                trimCancelledSendTombstones()
                onTerminalFailure()
                return
            }
            if (
                throwable is CancellationException &&
                optimisticSendPhases[optimisticKey] == OptimisticSendPhase.CANCELLED
            ) {
                discardedDuringRetry.remove(optimisticKey)
                trimCancelledSendTombstones()
            }
            if (throwable is CancellationException) sendHostAttempt.cancel()
            throwable.rethrowIfCancellation()
            if (throwable.isUseAfterEviction()) {
                sendHostAttempt.failure()
                // The engine realized our own eviction while replaying to send:
                // we are no longer a member, so this message can never publish.
                // Drop the optimistic bubble (a retry would only re-fail) and
                // flip to the read-only removed state — the same realization the
                // conversation-open and refresh paths perform — instead of
                // surfacing the raw backend error.
                discardOptimisticSend(optimisticKey, tempId, OptimisticSendDiscard.ABANDONED)
                markActiveAccountRemovedFromMembers(account)
                onTerminalFailure()
                return
            }
            if (isAmbiguousRelayDeliveryError(throwable)) {
                sendHostAttempt.unavailable()
                if (optimisticSendPhases[optimisticKey] == OptimisticSendPhase.PRE_ACCEPTANCE) {
                    optimisticSendPhases[optimisticKey] = OptimisticSendPhase.ACCEPTANCE_UNKNOWN
                }
                // The event may already be on a relay. Preserve both the
                // optimistic bubble and chat-list preview as Pending, then let
                // an authoritative projection or MDK convergence settle it.
                // Calling textPublisher again here could mint a duplicate.
                sendTrace(
                    trace,
                    PerformancePhase.DELIVERY_UNCERTAIN,
                    result = PerformanceResult.PENDING,
                    layer = PerformanceLayer.TRANSPORT,
                )
                appState.pendingSendDiagnostics.update(tempId, PerformanceSendStage.DELIVERY_UNCERTAIN)
                Log.w(
                    "ConversationController",
                    "message delivery uncertain; keeping pending type=${throwable.javaClass.simpleName}",
                )
                publishTimelineFromIndexes()
                return
            }
            if (optimisticSendIsMdkOwnedOrUnknown(optimisticKey)) {
                sendHostAttempt.unavailable()
                publishTimelineFromIndexes()
                return
            }
            // The bubble stays visible as Failed — the row must agree instead
            // of silently reverting to the prior message.
            failOptimisticChatListPreview(tempId)
            retainFailedOptimisticTextSend(
                optimisticMessages = optimisticMessages,
                messageById = messageById,
                key = optimisticKey,
                optimistic = publishedRecord,
                timelineOrder = optimisticOrder,
            )
            optimisticSendPhases[optimisticKey] = OptimisticSendPhase.FAILED
            suppressProjectedTimelineItems(
                unpublishedProjectionIdsMatchingMessage(
                    timelineRecords = timelineRecords,
                    message = publishedRecord,
                    activeAccountIdHex = conversationAccountIdHex,
                ),
            )
            publishTimelineFromIndexes()
            sendTrace(
                trace,
                PerformancePhase.SEND_FAILED,
                result = PerformanceResult.FAILURE,
            )
            appState.pendingSendDiagnostics.forget(tempId)
            sendHostAttempt.failure()
            presentSendFailure(appState, throwable, sendFailureAttempt(optimisticKey))
            onTerminalFailure()
        } finally {
            sendHostAttempt.unavailable()
        }
    }

    /**
     * Token-aware admission may safely repeat a closed-runtime call because MDK
     * deduplicates the original client token. The injected legacy publisher
     * still retries only failures proven to occur before relay publication.
     */
    private fun isRetryableTextAdmissionError(throwable: Throwable): Boolean =
        isTransientRelaySendError(throwable) ||
            (textPublisher == null && isTransientRuntimeWorkerError(throwable))

    /**
     * The lock an emoji send takes around its publish only, so uploads never hold up cancel or other
     * mutations. The acceptance bookkeeping happens inside the same lock, so a cancel waiting for it
     * sees the send as owned by MDK (or uncertain) and cannot report success for a published send. A
     * rejection that published nothing, such as a stale epoch, leaves the send pre-acceptance.
     */
    private fun commitPublishLock(
        account: String,
        optimisticKey: String,
        controllerContext: CoroutineContext,
    ): EmojiPublishLock =
        { publish ->
            appState.withGroupCommitLock(account, group.groupIdHex) {
                try {
                    publish().also { summary ->
                        withContext(controllerContext) { recordOptimisticSendAcceptance(optimisticKey, summary) }
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") failure: Throwable,
                ) {
                    withContext(controllerContext) { recordSendAdmissionFailure(optimisticKey, failure) }
                    throw failure
                }
            }
        }

    /**
     * Uploads a send with file-backed items through MDK's file path, with the same commit lock,
     * cancellation check, retry capture and draft/admission rules as the in-memory path. While the
     * transfer holds the lock, the user's Cancel reaches it through [RetainedMediaUpload.requestTransferCancel].
     */
    private suspend fun uploadFileBackedRetained(
        account: String,
        key: String,
        tempId: String,
        retained: RetainedMediaUpload,
        startedAtMs: Long,
    ): MediaUploadResultFfi {
        val batchCiphertextBytes =
            checkNotNull(withContext(Dispatchers.IO) { fileBatchCiphertextBytes() }) {
                "file-backed upload limits unavailable"
            }
        val stagingDirectory = uploadSourcesDirectory(appState.appContext.cacheDir)
        return withFileUploadRequest(
            retained.attachments,
            retained.caption,
            stagingDirectory,
            batchCiphertextBytes,
        ) { request ->
            mediaFileUploader?.invoke(account, group.groupIdHex, request, retained::attachTransferCancel)
                ?: appState
                    .withGroupCommitLock(account, group.groupIdHex) {
                        requireOptimisticSendNotCancelled(key)
                        val outcome =
                            withNativeTransferControl(
                                retained::attachTransferCancel,
                                onProgress = retained::reportTransferProgress,
                            ) { control ->
                                appState.marmotIo(MarmotTraceSection.MEDIA_UPLOAD) {
                                    uploadOrAdmitComposerMediaFilesWithToken(
                                        account,
                                        group.groupIdHex,
                                        request,
                                        control,
                                        tempId,
                                    )
                                }
                            }
                        outcome.acceptance?.let { recordOptimisticSendAcceptance(key, it) }
                        outcome
                    }.also { outcome ->
                        outcome.captureForRetry(retained, appState.pendingSendDiagnostics, tempId, startedAtMs)
                    }.upload
        }
    }

    /** Retires host cancellation when a failed admission may already have published. */
    private fun recordSendAdmissionFailure(
        optimisticKey: String,
        failure: Throwable,
    ) {
        if (failure is OptimisticSendCancelledException || !isAmbiguousRelayDeliveryError(failure)) {
            return
        }
        if (optimisticSendPhases[optimisticKey] == OptimisticSendPhase.PRE_ACCEPTANCE) {
            optimisticSendPhases[optimisticKey] = OptimisticSendPhase.ACCEPTANCE_UNKNOWN
        }
    }

    /** Runs [block] under the group commit lock, or directly when [skip] says the block locks its own publish. */
    private suspend fun <T> commitLockUnless(
        skip: Boolean,
        account: String,
        block: suspend () -> T,
    ): T = if (skip) block() else appState.withGroupCommitLock(account, group.groupIdHex, block)

    /**
     * The user's own emoji a plain, non-reply text send must carry, or none when this send carries none.
     * A lookup failure is raised as an [EmojiUploadFailure], a definite pre-publication failure.
     */
    private suspend fun emojiArtworkForSend(
        account: String,
        replyTarget: String?,
        text: String,
    ): List<LocalEmojiArtwork> {
        val applies = emojiSendApplies(replyTarget, textPublisher != null)
        if (!applies) return emptyList()
        // The lookup runs before anything is published, so its failure is never an uncertain delivery.
        return runCatchingCancellable { customEmojiReader?.invoke(text) ?: appState.emojiArtworkIn(account, text) }
            .getOrElse { failure -> throw failure as? EmojiSendLimitException ?: EmojiUploadFailure(failure) }
    }

    /**
     * Admission failures retry for plain text. An emoji send retries only an upload-stage failure,
     * because nothing was published then, and never a failure of the publish itself. Null artwork
     * means its lookup failed, which also published nothing.
     */
    private fun isRetryableComposerSend(
        emojiArtwork: List<LocalEmojiArtwork>?,
        throwable: Throwable,
    ): Boolean =
        if (emojiArtwork?.isEmpty() == true) {
            isRetryableTextAdmissionError(throwable)
        } else {
            isRetryableEmojiUploadFailure(throwable)
        }

    /**
     * One native publication of the composer text: the durable token-bound send, or, when the text
     * uses the user's own emoji, upload-only images followed by one tagged media send. The optimistic
     * send is re-checked on this controller's context after the upload and again inside the commit
     * lock right before publishing, so a cancelled send is never published.
     */
    @Suppress("LongParameterList")
    private suspend fun publishComposerTextOnce(
        account: String,
        replyTarget: String?,
        text: String,
        clientToken: String,
        probeExistingAdmission: Boolean,
        emojiArtwork: List<LocalEmojiArtwork>,
        optimisticKey: String,
    ): dev.ipf.marmotkit.SendSummaryFfi {
        // The native calls run on the IO dispatcher, but the send phases belong to this controller's context.
        val controllerContext = currentCoroutineContext()
        val ensureCurrent: suspend () -> Unit = {
            withContext(controllerContext) { requireOptimisticSendNotCancelled(optimisticKey) }
        }
        val injected = customEmojiSender
        return when {
            emojiArtwork.isEmpty() ->
                publishDurableComposerText(account, replyTarget, text, clientToken, probeExistingAdmission)
            injected != null -> {
                val lock = commitPublishLock(account, optimisticKey, controllerContext)
                injected(account, text, emojiArtwork, ensureCurrent, lock)
            }
            else ->
                appState.marmotIo(MarmotTraceSection.TEXT_SEND) {
                    sendTextWithCustomEmoji(
                        account,
                        group.groupIdHex,
                        text,
                        emojiArtwork,
                        appState.emojiUploads,
                        ensureCurrent = ensureCurrent,
                        publishLock = commitPublishLock(account, optimisticKey, controllerContext),
                    )
                }
        }
    }

    /**
     * Admits one logical text/reply token to native durable ownership. Proven
     * pre-admission connectivity failures reuse the token; interrupted admission
     * queries native status before another call. Acceptance stays pending until
     * the authoritative projection reports publication. Native convergence owns
     * accepted delivery; the host never invents another semantic send to retry it.
     */
    @Suppress("LongMethod") // Retry admission, tracing, and lock ownership form one atomic policy boundary.
    private suspend fun publishTextWithRetry(
        replyTarget: String?,
        account: String,
        trimmed: String,
        trace: PerformanceTrace?,
        clientToken: String,
        optimisticKey: String,
    ): dev.ipf.marmotkit.SendSummaryFfi =
        appState.withConversationTextSendOrder(account, group.groupIdHex) {
            // Replies and injected publishers keep their plain-text path. A message that uses the user's
            // own emoji uploads outside the commit lock, and only an upload-stage failure is retried.
            // Resolving its artwork inside the text order keeps a later message from publishing first,
            // and inside the attempt lets a transient lookup failure retry before anything is published.
            val artwork = SendEmojiArtwork { emojiArtworkForSend(account, replyTarget, trimmed) }
            retryPendingConversationSend(
                connectivityRecoveryGeneration = appState.validatedConnectivityRecoveryGeneration,
                cancellationGeneration = optimisticCancellationGeneration,
                retryableFailure = { isRetryableComposerSend(artwork.value, it) },
                onTransientFailure = { attempt, _ -> logSendRetry(trace, clientToken, attempt) },
            ) { attempt ->
                // A cancel wakes the retry wait, so check it before a lookup that may fail again and retry forever.
                requireOptimisticSendNotCancelled(optimisticKey)
                val emojiArtwork = untilSendCancelled(optimisticKey) { artwork.resolve() }
                // Serialize only this commit-producing FFI attempt. Releasing the
                // commit lock before retry backoff keeps reactions and other
                // mutations usable; the outer text-order lock keeps later text
                // sends behind this one until its outcome is known.
                val lockWaitStartMs = trace?.let { traceNowMs() }
                updatePendingSendStage(clientToken, PerformanceSendStage.WAITING_COMMIT_LOCK, attempt)
                // An emoji send uploads outside the commit lock and locks only its publish, so a cancel
                // taking the same lock never waits for a slow upload.
                commitLockUnless(emojiArtwork.isNotEmpty() && textPublisher == null, account) {
                    requireOptimisticSendNotCancelled(optimisticKey)
                    val lockHeldAtMs = trace?.let { traceNowMs() }
                    sendTrace(
                        trace,
                        PerformancePhase.COMMIT_LOCK_ACQUIRED,
                        durationMs = lockHeldAtMs?.minus(lockWaitStartMs ?: lockHeldAtMs) ?: 0L,
                        attempt = attempt,
                    )
                    updatePendingSendStage(clientToken, PerformanceSendStage.FFI_ADMISSION, attempt)
                    // Measure admission separately from transport publication.
                    val ffiStartMs = trace?.let { traceNowMs() }
                    sendTrace(
                        trace,
                        PerformancePhase.FFI_START,
                        result = PerformanceResult.PENDING,
                        layer = PerformanceLayer.FFI,
                        attempt = attempt,
                    )
                    try {
                        val summary =
                            textPublisher?.invoke(replyTarget, account, group.groupIdHex, trimmed)
                                ?: publishComposerTextOnce(
                                    account,
                                    replyTarget,
                                    trimmed,
                                    clientToken,
                                    attempt > 1,
                                    emojiArtwork,
                                    optimisticKey,
                                )
                        recordOptimisticSendAcceptance(optimisticKey, summary)
                        sendTrace(
                            trace,
                            PerformancePhase.FFI_RETURN,
                            durationMs = ffiStartMs?.let { traceNowMs() - it } ?: 0L,
                            layer = PerformanceLayer.FFI,
                            attempt = attempt,
                            count = summary.messageIds.size,
                        )
                        if (summary.acceptDisposition == SendAcceptDispositionFfi.PUBLISHED) {
                            sendTrace(
                                trace,
                                PerformancePhase.TRANSPORT_COMPLETE,
                                layer = PerformanceLayer.TRANSPORT,
                                count = summary.messageIds.size,
                            )
                        }
                        summary
                    } catch (throwable: Throwable) {
                        // Presentation may still be parsing when this child
                        // returns. Retire host cancellation before releasing
                        // the commit lock if publication may have happened.
                        recordSendAdmissionFailure(optimisticKey, throwable)
                        sendTrace(
                            trace,
                            PerformancePhase.FFI_ERROR,
                            durationMs = ffiStartMs?.let { traceNowMs() - it } ?: 0L,
                            result = PerformanceResult.FAILURE,
                            layer = PerformanceLayer.FFI,
                            attempt = attempt,
                        )
                        throw throwable
                    }
                }
            }
        }

    /**
     * Trace a transient send retry with the current relay-health snapshot.
     * Aggregate connection-state counts only (no relay URLs, account/group/
     * message ids, or payload) so the #294 failure window is observable without
     * violating the repo's privacy posture. Best-effort: a failure to read
     * health must never escalate a send retry into an error.
     */
    private suspend fun logSendRetry(
        trace: PerformanceTrace?,
        optimisticId: String,
        attempt: Int,
    ) {
        if (trace == null) return
        val health = runCatchingCancellable { appState.marmotIo { relayHealth() } }.getOrNull()
        sendTrace(
            trace,
            PerformancePhase.TRANSIENT_RETRY,
            result = PerformanceResult.FAILURE,
            layer = PerformanceLayer.TRANSPORT,
            attempt = attempt,
            connectedRelays = health?.connected?.toInt(),
            totalRelays = health?.totalRelays?.toInt(),
        )
        updatePendingSendStage(optimisticId, PerformanceSendStage.WAITING_COMMIT_LOCK, attempt)
    }

    /** Updates the closed stage sampled if this send crosses a pending checkpoint. */
    private fun updatePendingSendStage(
        optimisticId: String,
        stage: PerformanceSendStage,
        attempt: Int,
    ) {
        appState.pendingSendDiagnostics.update(optimisticId, stage, attempt)
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
     * empty, or oversize). Caller pairs each non-null result with a
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
        if (!canQueueAttachmentBytes(attachments, replyTarget)) return null
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
                        replyTarget = replyTarget,
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
        val replyRevision =
            replyTarget?.let { prepareQueuedMediaReply(account, it, trimmedCaption, attachments) }
        val replyStagingFailed = replyTarget != null && replyRevision == null
        if (replyStagingFailed || !canQueueMediaForAccount(account, canQueue)) {
            outboundVisibleAttempt.cancel()
            if (replyStagingFailed) appState.present(R.string.media_reply_draft_conflict)
            return null
        }
        val retentionAtSendSeconds = rememberRetentionAtSend(tempId, retentionSnapshot)
        val optimisticOrder = nextOptimisticTimelineOrder()
        retainedMediaUploads.put(
            key,
            RetainedMediaUpload(attachments, trimmedCaption, replyTarget, replyRevision, replyVersion),
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

    /**
     * Rejects empty payloads before reserving a pending slot and reports the retained-byte limit for
     * in-memory items. A file-backed item cannot join a reply: native reply drafts carry attachment bytes.
     */
    private fun canQueueAttachmentBytes(
        attachments: List<PendingAttachment>,
        replyTarget: String?,
    ): Boolean {
        if (!canSendMessages || attachments.isEmpty() || attachments.any { !it.hasContent }) return false
        return if (albumExceedsRetainedCap(attachments)) {
            appState.present(R.string.media_album_too_large)
            false
        } else if (replyTarget != null && attachments.any { it.sourceFile != null }) {
            appState.present(R.string.media_file_too_large)
            false
        } else {
            true
        }
    }

    /** Rechecks presentation eligibility and account generation after asynchronous preparation. */
    private fun canQueueMediaForAccount(
        account: String,
        canQueue: () -> Boolean,
    ): Boolean =
        canQueue() &&
            canSendMessages &&
            shouldAcceptMediaUploadForAccount(
                account,
                mediaUploadSessionEpoch,
                appState.activeAccountRef,
                appState.mediaUploadSessionEpoch(),
            )

    /** A refused native draft write leaves the caller's unsent media and reply selection available. */
    private suspend fun prepareQueuedMediaReply(
        account: String,
        target: String,
        caption: String?,
        attachments: List<PendingAttachment>,
    ): dev.ipf.marmotkit.MessageDraftRevisionFfi? =
        runCatchingCancellable {
            appState.marmotIo { stageMediaReply(account, group.groupIdHex, target, caption, attachments) }
        }.getOrNull()

    /** Builds the optimistic pending record that carries staged attachments until the send confirms. */
    private suspend fun pendingAttachmentRecord(
        tempId: String,
        body: String,
        attachments: List<PendingAttachment>,
        now: ULong,
        replyTarget: String?,
    ): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = tempId,
            direction = "sent",
            groupIdHex = group.groupIdHex,
            sender = conversationAccountIdHex ?: "",
            plaintext = body,
            contentTokens = markdownParser(body),
            kind = 9uL,
            tags =
                attachments.map {
                    MessageTagFfi(listOf("_media_pending", it.fileName, it.mediaType))
                } + replyTarget?.let { listOf(MessageProjector.eventTag(it), MessageProjector.quoteTag(it)) }.orEmpty(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = now,
            receivedAt = now,
            sticker = null,
        )

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

    /**
     * Shared upload→publish path for both first send and retry. Reads the
     * compressed bytes from [retainedMediaUploads] (keyed by [key]) so it
     * survives a Failed→retry round-trip. On success: drops the optimistic +
     * retained entry, then seeds [WhiteNoiseAppState.cacheMediaPlaintext] with the just-uploaded
     * plaintext so the sender renders its own image without a download. On
     * failure: flips back to Failed (bytes stay retained for another retry).
     */
    private suspend fun performMediaUpload(
        account: String,
        key: String,
        tempId: String,
        order: ULong,
        optimistic: AppMessageRecordFfi,
    ) {
        val sendHostAttempt = appState.beginHostPerformance(HostPerformanceOperationFfi.MESSAGE_SEND)
        val retentionAtSendSeconds = optimisticMessages[key]?.retentionAtSendSeconds
        val uploadJob = appState.trackInFlightMediaUpload(conversationAccountRef, group.groupIdHex, key)
        val diagnostics = appState.pendingSendDiagnostics
        try {
            if (
                !shouldAcceptMediaUploadForAccount(
                    account,
                    mediaUploadSessionEpoch,
                    appState.activeAccountRef,
                    appState.mediaUploadSessionEpoch(),
                )
            ) {
                discardOptimisticSend(key, tempId, OptimisticSendDiscard.ABANDONED)
                return
            }
            // queueAttachments and uploadQueued are intentionally separate so
            // a user can cancel the seeded bubble before this continuation is
            // scheduled. Honour that tombstone before consulting retained
            // bytes; otherwise their cancellation cleanup looks like an
            // eviction and resurrects the row as Failed.
            if (
                optimisticSendPhases[key] == OptimisticSendPhase.CANCELLED ||
                discardedDuringRetry.remove(key)
            ) {
                removeCancelledOptimisticSend(key, tempId)
                trimCancelledSendTombstones()
                return
            }
            val retained =
                retainedMediaUploads.get(key) ?: run {
                    // Bytes are gone (evicted under cap, or process death) — can't
                    // retry without a re-attach. Leave the bubble Failed and drop
                    // the in-flight marker so a future dispose can clean up.
                    optimisticMessages[key] =
                        TimelineMessage(
                            key,
                            optimistic,
                            MessageStatus.Failed,
                            timelineOrder = order,
                            retentionAtSendSeconds = retentionAtSendSeconds,
                        )
                    optimisticSendPhases[key] = OptimisticSendPhase.FAILED
                    activeUploadKeys.remove(key)
                    failOptimisticChatListPreview(tempId)
                    diagnostics.complete(
                        tempId,
                        PerformancePhase.SEND_FAILED,
                        PerformanceResult.FAILURE,
                    )
                    publishTimelineFromIndexes()
                    appState.present(R.string.toast_reattach_to_retry_media)
                    return
                }
            discardedDuringRetry.remove(key)
            // Every attempt reports its own transfer from the start, so a Retry never shows the last one's bytes.
            retained.clearTransferProgress()
            try {
                // Reuse the references if a prior attempt already uploaded the
                // blobs (publish-only failure) — re-uploading would orphan
                // duplicates on the Blossom server.
                val request =
                    MediaUploadRequestFfi(
                        attachments =
                            retained.attachments.map { attachment ->
                                MediaUploadAttachmentRequestFfi(
                                    fileName = attachment.fileName,
                                    mediaType = attachment.mediaType,
                                    plaintext = attachment.plaintextBytes,
                                    dim = attachment.dim,
                                    thumbhash = attachment.thumbhash,
                                )
                            },
                        caption = retained.caption,
                        send = false,
                        blossomServer = null,
                    )
                val references =
                    retained.uploadedReferences?.also { diagnostics.mediaUploadReused(tempId) }
                        ?: run {
                            val startedAtMs = diagnostics.beginMediaUpload(tempId)
                            val uploaded =
                                if (retained.isFileBacked) {
                                    uploadFileBackedRetained(account, key, tempId, retained, startedAtMs)
                                } else {
                                    mediaUploader?.invoke(account, group.groupIdHex, request)
                                        ?: appState
                                            .withGroupCommitLock(account, group.groupIdHex) {
                                                requireOptimisticSendNotCancelled(key)
                                                val outcome =
                                                    appState.marmotIo(MarmotTraceSection.MEDIA_UPLOAD) {
                                                        uploadOrAdmitComposerMediaWithToken(
                                                            account,
                                                            group.groupIdHex,
                                                            request,
                                                            tempId,
                                                            capturedReply = retained.replyRevision != null,
                                                        )
                                                    }
                                                outcome.acceptance?.let { recordOptimisticSendAcceptance(key, it) }
                                                outcome
                                            }.also { outcome ->
                                                outcome.captureForRetry(
                                                    retained,
                                                    diagnostics,
                                                    tempId,
                                                    startedAtMs,
                                                )
                                            }.upload
                                }.attachments.map { it.reference }
                            diagnostics.finishMediaUpload(tempId, startedAtMs)
                            uploaded
                                .also {
                                    if (!retained.recoveredWithoutUpload && it.size != retained.attachments.size) {
                                        error(
                                            "media upload returned ${it.size} references for " +
                                                "${retained.attachments.size} attachments",
                                        )
                                    }
                                }.also { retained.uploadedReferences = it }
                        }
                // Stop before publication after a discard; an unreferenced Blossom blob is inert.
                if (discardedDuringRetry.remove(key)) {
                    discardOptimisticSend(key, tempId, OptimisticSendDiscard.DISCARDED_IN_FLIGHT)
                    trimCancelledSendTombstones()
                    return
                }
                val summary =
                    retained.localAcceptance
                        ?: run {
                            val startedAtMs = diagnostics.beginMediaPublish(tempId)
                            appState
                                .withGroupCommitLock(account, group.groupIdHex) {
                                    requireOptimisticSendNotCancelled(key)
                                    val accepted =
                                        mediaPublisher?.invoke(account, group.groupIdHex, references, retained.caption)
                                            ?: appState.marmotIo(MarmotTraceSection.MEDIA_SEND) {
                                                sendComposerMedia(
                                                    account,
                                                    group.groupIdHex,
                                                    references,
                                                    retained.caption,
                                                    tempId,
                                                    replyRevision = retained.replyRevision,
                                                )
                                            }
                                    recordOptimisticSendAcceptance(key, accepted)
                                    accepted
                                }.also { diagnostics.finishMediaPublish(tempId, startedAtMs) }
                        }
                // Once MDK has accepted the send, no retry uploads again, so large snapshots can go now instead of
                // waiting, possibly offline for hours, for it to settle. Until then a stale-epoch refusal needs them.
                retained.releaseStagedSources(appState::closeStagedUploadSourceOffMain)
                completeDurableAcceptance(key)
                if (retained.replyTarget != null && replySelectionVersion == retained.replyVersion) replyingTo = null
                sendHostAttempt.success()
                val canonicalId = summary.messageIds.firstOrNull()
                diagnostics.alias(tempId, canonicalId)
                if (summary.acceptDisposition == SendAcceptDispositionFfi.ACCEPTED_PENDING) {
                    // MDK now owns a durable, unpublished media intent. It still
                    // returns the canonical app-event id, which lets a later
                    // projection settle this exact bubble even when other media
                    // sends are queued at the same time.
                    val acceptedPendingMessageIdHex =
                        canonicalId?.takeIf(HEX_MESSAGE_ID::matches)
                    diagnostics.recordAcceptedPending(tempId, acceptedPending = true)
                    // A retry can be discarded while the FFI call is suspended.
                    // MDK cannot retract an accepted intent, but the user chose
                    // to discard this local bubble, so don't retain its bytes or
                    // use the canonical id to restore a deferred projection.
                    if (discardedDuringRetry.remove(key)) {
                        acceptedPendingMessageIdHex?.let(pendingProjectionsAwaitingBridge::remove)
                        discardOptimisticSend(key, tempId, OptimisticSendDiscard.DISCARDED_IN_FLIGHT)
                        trimCancelledSendTombstones()
                        return
                    }
                    // A single-media projection can beat the FFI return and
                    // reconcile heuristically before its exact MDK id is known.
                    // Once the return supplies that id, finish the same exact
                    // chat-list handoff and release the retained upload state.
                    val acceptedPendingProjectionAlreadyLanded =
                        acceptedPendingMessageIdHex != null &&
                            acceptedPendingMessageIdHex in projectedMessageIds
                    if (acceptedPendingProjectionAlreadyLanded) {
                        val confirmedMessageIdHex = requireNotNull(acceptedPendingMessageIdHex)
                        // The early echo may have seeded only L1 before upload supplied the ciphertext tag.
                        // Register the guarded durable host copy before releasing its retained plaintext.
                        handoffOwnMediaCacheOnReconcile(key, confirmedMessageIdHex)
                        appState.commitOptimisticSentPreview(
                            accountRef = conversationAccountRef,
                            groupIdHex = group.groupIdHex,
                            optimisticMessageIdHex = tempId,
                            confirmedMessageIdHex = confirmedMessageIdHex,
                        )
                        optimisticMessages.remove(key)
                        messageById.remove(tempId)
                        retainedMediaUploads.remove(key)
                        activeUploadKeys.remove(key)
                        diagnostics.recordEchoReconcile(tempId)
                        publishTimelineFromIndexes()
                        optimisticSendPhases.remove(key)
                        return
                    }
                    retained.acceptedPending = true
                    retained.acceptedPendingMessageIdHex = acceptedPendingMessageIdHex
                    // The local projection can beat the FFI return. It was held
                    // only because several media optimistics were indistinguishable
                    // at that instant; now the MDK id gives it an exact owner.
                    acceptedPendingMessageIdHex
                        ?.let(pendingProjectionsAwaitingBridge::get)
                        ?.let { deferredProjection ->
                            upsertProjectedRecord(
                                deferredProjection,
                                reconcileOptimistic = true,
                                allowDelayedProjection = true,
                            )
                        }
                    publishTimelineFromIndexes()
                    return
                }
                // MarmotKit owns the encrypted-media wire format. Build the
                // optimistic bridge tags through the same native API that
                // validates and publishes the projected attachments.
                val imetaTags = mediaImetaTagsBuilder(account, group.groupIdHex, references)
                val confirmedId = canonicalId ?: tempId
                diagnostics.mediaTransportComplete(tempId)
                transferRetentionAtSend(tempId, confirmedId)
                appState.commitOptimisticSentPreview(
                    accountRef = conversationAccountRef,
                    groupIdHex = group.groupIdHex,
                    optimisticMessageIdHex = tempId,
                    confirmedMessageIdHex = confirmedId,
                )
                // INVARIANT: the discard re-check must run BEFORE any cache mutation
                // below, so a mid-flight discard never seeds the just-sent bytes.
                if (discardedDuringRetry.remove(key)) {
                    // User discarded after publish committed; drop the local
                    // optimistic + bytes. The published event may still echo back
                    // via projection (publish already succeeded — not retractable).
                    discardOptimisticSend(key, tempId, OptimisticSendDiscard.DISCARDED_AFTER_PUBLISH)
                    trimCancelledSendTombstones()
                    return
                }
                // Seed the decrypted-bytes AND decoded-thumbnail caches under the
                // confirmed id so the sender's own bubble renders instantly — no
                // Blossom round-trip and no decode spinner. One cache entry per
                // attachment under `(account, messageId, attachmentIndex)`.
                //
                // Re-check the session/account immediately before seeding: the
                // upload + sendMediaAttachments above are long suspend points, and
                // a sign-out / account switch in that window runs
                // `clearInMemoryMediaCaches()` and bumps the upload-session epoch.
                // Without this guard, the in-memory L1 caches (which are NOT
                // generation-gated) would be repopulated with the just-signed-out
                // account's decrypted plaintext, surviving the sign-out clear until
                // the next `clearInMemoryMediaCaches()`. The L2 disk write below is
                // already generation-gated, but skipping it too on a stale session
                // keeps the behaviour consistent (it would no-op anyway). The bridge
                // insert below is intentionally left running: the publish already
                // committed, so the timeline state still needs reconciling.
                val sessionStillValid = mediaUploadSessionStillCurrent(account)
                if (confirmedId.isNotEmpty() && sessionStillValid) {
                    retained.attachments.forEachIndexed { index, attachment ->
                        if (!mediaUploadSessionStillCurrent(account)) return@forEachIndexed
                        // MDK retains a file-backed item's own copy; only in-memory bytes seed the host cache.
                        val bytes = attachment.inMemoryBytes ?: return@forEachIndexed
                        val confirmedKey = mediaCacheKey(account, confirmedId, index)
                        appState.cacheUploadedAttachment(
                            confirmedKey,
                            bytes,
                            references.getOrNull(index)?.ciphertextSha256,
                        )
                        // Offload the multi-MB ARGB decode to Default; the
                        // main-confined thumbnail-cache put resumes on Main.
                        // Mirrors the receive/render path in WhiteNoiseApp.
                        // The attachment names its sent draft's domain, the one its previews used.
                        val decoded = mediaThumbnailDecoder(attachment, attachment.outgoingDomain)
                        if (!mediaUploadSessionStillCurrent(account)) return@forEachIndexed
                        if (decoded != null) {
                            appState.cacheMediaThumbnail(confirmedKey, decoded)
                        }
                    }
                }
                // Keep the pending row available to exact native reconciliation across thumbnail suspension.
                // Removing it earlier lets a concurrent echo publish a new presentation identity.
                optimisticMessages.remove(key)
                messageById.remove(tempId)
                retainedMediaUploads.remove(key)
                activeUploadKeys.remove(key)
                // Bridge the gap until the published event echoes back via the
                // projection: insert a confirmed *image* optimistic carrying the
                // imeta tags (one per uploaded reference), keyed on confirmedId.
                // Same key as the eventual projected item, so the bubble never
                // disappears/reappears, and it renders from the seeded thumbnail.
                // pruneConfirmedOptimisticMessages reconciles it on arrival.
                val handoffHandled =
                    if (confirmedId.isNotEmpty()) {
                        // Always insert the bridge. When the projection has already
                        // arrived (race-loser), `optimisticMessageIdForProjection`
                        // refuses to reconcile (no exact-id match + multiple
                        // `_media_pending` siblings → null), leaving the new
                        // projection alongside the still-pending optimistic until
                        // this bridge insert resolves the pairing via id collision
                        // in `publishTimelineFromIndexes`. The bridge carries the
                        // real imeta tags so it renders identically to the
                        // projection it eventually consumes.
                        val confirmedRecord =
                            optimistic.copy(
                                messageIdHex = confirmedId,
                                // Match what the published event carries (the caption we
                                // sent), not the "📎 filename" optimistic placeholder, so
                                // the bridge bubble is identical to the projected one.
                                plaintext = retained.caption.orEmpty(),
                                tags =
                                    imetaTags +
                                        optimistic.tags.filter {
                                            it.values.firstOrNull() != "_media_pending"
                                        },
                                sourceEpoch = references.firstOrNull()?.sourceEpoch,
                            )
                        messageById[confirmedId] = confirmedRecord
                        optimisticMessages["msg:$confirmedId"] =
                            TimelineMessage(
                                "msg:$confirmedId",
                                confirmedRecord,
                                MessageStatus.Sent,
                                timelineOrder = order,
                                retentionAtSendSeconds = retentionAtSendSeconds,
                                presentationId = key,
                            )
                        // Register the bridge through the same tracked preserve path
                        // as text sends so orphan cleanup can release its overrides
                        // after the confirmed handoff. Drain any
                        // projection echo that arrived while this send was still
                        // mid-`sendMediaAttachments` — at that point the heuristic
                        // refused the match and the projection was stashed in
                        // `pendingProjectionsAwaitingBridge` to avoid a position-0
                        // render flip. Now that the overrides are stamped, the
                        // build below will produce a TimelineMessage at the right
                        // position.
                        preserveOptimisticDisplayPosition(confirmedId, confirmedId)
                        val deferredProjection = pendingProjectionsAwaitingBridge[confirmedId]
                        val deliveredToAttachedController =
                            appState.deliverConfirmedMediaHandoff(
                                accountRef = conversationAccountRef,
                                groupIdHex = group.groupIdHex,
                                confirmedId = confirmedId,
                                deferredProjection = deferredProjection,
                            )
                        // A controller used outside the composed screen is not in
                        // AppState's attached set. It may still consume its own
                        // projection, but a disposed upload owner must leave the
                        // shared bridge + stash for the next live controller.
                        deliveredToAttachedController ||
                            (
                                !controllerCleared &&
                                    acceptConfirmedMediaHandoff(confirmedId, deferredProjection)
                            )
                    } else {
                        false
                    }
                // The accepting controller owns publication and settlement. A
                // disposed upload owner must not consume its shared preserve.
                if (!handoffHandled && !controllerCleared) {
                    publishTimelineFromIndexes()
                }
                optimisticSendPhases.remove(key)
            } catch (throwable: Throwable) {
                // Coroutine cancellation (e.g. leaving the screen) is not a send
                // failure — rethrow so it isn't surfaced as a Failed bubble/toast.
                if (throwable is CancellationException) {
                    sendHostAttempt.cancel()
                    if (optimisticSendPhases[key] == OptimisticSendPhase.CANCELLED) {
                        discardedDuringRetry.remove(key)
                        trimCancelledSendTombstones()
                    }
                    throw throwable
                }
                if (throwable is OptimisticSendCancelledException) {
                    sendHostAttempt.cancel()
                    discardedDuringRetry.remove(key)
                    removeCancelledOptimisticSend(key, tempId)
                    trimCancelledSendTombstones()
                    return
                }
                // Cancel stopped a file-backed transfer before admission: settle it as cancelled, not failed.
                if (retained.consumeTransferCancelRequest() && !optimisticSendIsMdkOwnedOrUnknown(key)) {
                    sendHostAttempt.cancel()
                    optimisticSendPhases[key] = OptimisticSendPhase.CANCELLED
                    optimisticCancellationGeneration.value += 1
                    discardedDuringRetry.remove(key)
                    removeCancelledOptimisticSend(key, tempId)
                    trimCancelledSendTombstones()
                    return
                }
                if (discardedDuringRetry.remove(key)) {
                    sendHostAttempt.cancel()
                    discardOptimisticSend(key, tempId, OptimisticSendDiscard.DISCARDED_AFTER_MEDIA_FAILURE)
                    trimCancelledSendTombstones()
                    return
                }
                if (optimisticSendIsMdkOwnedOrUnknown(key)) {
                    sendHostAttempt.unavailable()
                    publishTimelineFromIndexes()
                    if (BuildConfig.DEBUG) Log.w("DMConversation", "post-acceptance media settlement failed", throwable)
                    return
                }
                // MDK refuses references whose upload epoch or branch is no longer current (mdk#2891), and
                // reusing them would fail every Retry the same way, so the next attempt uploads again.
                if (isStaleMediaReferenceEpoch(throwable)) retained.uploadedReferences = null
                // A failed bubble offers Retry, not the bytes of the attempt that failed.
                retained.clearTransferProgress()
                optimisticMessages[key] =
                    TimelineMessage(
                        key,
                        optimistic,
                        MessageStatus.Failed,
                        timelineOrder = order,
                        retentionAtSendSeconds = retentionAtSendSeconds,
                    )
                optimisticSendPhases[key] = OptimisticSendPhase.FAILED
                failOptimisticChatListPreview(tempId)
                // Failed bubble shown but bytes are retained for a possible
                // retry — KEEP the key in `activeUploadKeys` so a screen
                // dispose can't wipe the bytes out from under a retry tap.
                // The key drains when the user retries (terminal performMediaUpload
                // path runs) or explicitly discards.
                publishTimelineFromIndexes()
                if (BuildConfig.DEBUG) Log.w("DMConversation", "media upload failed", throwable)
                diagnostics.complete(
                    tempId,
                    PerformancePhase.SEND_FAILED,
                    PerformanceResult.FAILURE,
                )
                sendHostAttempt.failure()
                presentSendFailure(appState, throwable, sendFailureAttempt(key))
            }
        } finally {
            sendHostAttempt.unavailable()
            appState.untrackInFlightMediaUpload(conversationAccountRef, group.groupIdHex, key, uploadJob)
        }
    }

    /** Reactions must wait for a blocked leave to be resolved, even with seeded membership. */
    private fun reactionAccountIfAccepted(): String? {
        val accountRef = conversationAccountRef
        if (
            showsBlockedLeave ||
            !canAcceptReaction(
                accountRef = accountRef,
                membersVerified = membersVerified,
                isSelfMember = isSelfMember,
                seededSelfMember = seededSelfMember,
                selfLeft = selfMembership.selfLeft,
                unrecoverable = group.unrecoverable,
                disbanding = group.disbanding,
                disbanded = group.disbanded,
            )
        ) {
            appState.present(R.string.toast_reaction_failed)
            return null
        }
        return accountRef
    }

    /** Commits an add and retains its exact event id for an immediate queued removal. */
    private suspend fun commitReactionAdd(
        owner: ReactionMutationTarget,
        emoji: String,
    ) {
        val account = owner.account
        val target = owner.messageId
        val key = target.lowercase() to emoji
        val messageIdHex =
            appState.withGroupCommitLock(account, group.groupIdHex) {
                owner.requireAvailable()
                val summary =
                    appState.marmotIo(MarmotTraceSection.MESSAGE_REACT) {
                        reactToMessage(account, group.groupIdHex, target, emoji)
                    }
                summary.messageIds.firstOrNull()?.takeIf(String::isNotBlank)
            }
        // Presence records that the add committed even when older bindings return no event id.
        // A rapid later removal can then wait for local history instead of mistaking it for a no-op.
        unprojectedOwnReactionEventIds[key] = messageIdHex.orEmpty()
    }

    /** Commits a removal, using history when a just-added reaction did not return an event id. */
    private suspend fun commitReactionRemoval(
        owner: ReactionMutationTarget,
        emoji: String,
        ownEmojisBeforeMutation: Set<String>,
        removeBeforeProjection: Boolean,
    ) {
        val account = owner.account
        val target = owner.messageId
        val key = target.lowercase() to emoji
        val preferredEventId =
            if (removeBeforeProjection) {
                unprojectedOwnReactionEventIds[key]
            } else {
                null
            }
        if (removeBeforeProjection && preferredEventId.isNullOrBlank()) {
            // This is only a visibility barrier. retractOwnReaction deliberately rereads history
            // under the group commit lock before choosing event-scoped delete versus unreact.
            awaitReactionEventHistory(emoji) {
                resolveActiveOwnReactionEventIds(account, target, requireNotNull(conversationAccountIdHex))
            }
        }
        appState.withGroupCommitLock(account, group.groupIdHex) {
            retractOwnReaction(owner, emoji, ownEmojisBeforeMutation, preferredEventId)
        }
    }

    /** Resolves active own reaction ids from Marmot's authoritative local event history. */
    private suspend fun resolveActiveOwnReactionEventIds(
        account: String,
        target: String,
        activeAccountIdHex: String,
    ): Map<String, String>? =
        runCatchingCancellable {
            appState.marmotIo {
                messages(
                    account,
                    group.groupIdHex,
                    null,
                    listOf(MARMOT_DELETE_EVENT_KIND, MARMOT_REACTION_EVENT_KIND),
                )
            }
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w("DMConversation", "reaction event lookup failed", it)
        }.getOrNull()
            ?.let { records -> activeOwnReactionEventIdsByEmoji(records, activeAccountIdHex, target) }

    /** Removes the tapped own reaction without clearing a different emoji when several are active. */
    private suspend fun retractOwnReaction(
        owner: ReactionMutationTarget,
        emoji: String,
        ownEmojisBeforeMutation: Set<String>,
        preferredEventId: String? = null,
    ) {
        val account = owner.account
        val target = owner.messageId
        // Retract just the tapped emoji by deleting its own reaction event; the
        // FFI target-only unreact would drop the wrong emoji when the user holds
        // more than one reaction on the same message.
        val me = conversationAccountIdHex ?: error("no active account to retract reaction")
        val projectedEventIdByEmoji = projectedOwnReactionEventIds(timelineRecords[target], me)
        val preferredReactionEventId = preferredEventId?.takeIf(String::isNotBlank)
        val requiresAuthoritativeHistory =
            preferredReactionEventId == null &&
                (ownEmojisBeforeMutation == setOf(emoji) || projectedEventIdByEmoji[emoji].isNullOrBlank())
        val authoritativeEventIds =
            if (requiresAuthoritativeHistory) {
                resolveActiveOwnReactionEventIds(account, target, me)
            } else {
                null
            }
        val knownEventIdByEmoji =
            knownReactionEventIds(
                projectedEventIds = projectedEventIdByEmoji,
                emoji = emoji,
                unprojectedEventId = unprojectedOwnReactionEventIds[target.lowercase() to emoji],
                authoritativeEventIds = authoritativeEventIds,
            )
        val authoritativeOwnEmojis =
            when {
                authoritativeEventIds != null -> authoritativeEventIds.keys
                ownEmojisBeforeMutation.size > 1 -> ownEmojisBeforeMutation
                else -> null
            }
        val plan =
            planOwnReactionRetraction(
                emoji = emoji,
                knownEventIdByEmoji = knownEventIdByEmoji,
                authoritativeOwnEmojis = authoritativeOwnEmojis,
                preferredEventId = preferredReactionEventId,
            )
        // History reads and lock admission can suspend after the reaction's quiet period.
        owner.requireAvailable()
        when (plan) {
            is OwnReactionRetractionPlan.DeleteReactionMessage -> {
                appState.marmotIo { deleteMessage(account, group.groupIdHex, plan.messageIdHex) }
                unprojectedOwnReactionEventIds.remove(target.lowercase() to emoji)
            }
            OwnReactionRetractionPlan.UnreactTarget -> {
                appState.marmotIo { unreactFromMessage(account, group.groupIdHex, target) }
                unprojectedOwnReactionEventIds.keys.removeAll { (messageId, _) ->
                    messageId.equals(target, ignoreCase = true)
                }
            }
            OwnReactionRetractionPlan.Unavailable -> error("no reaction event to retract for $emoji")
        }
    }

    /** Own reactions known by projection plus locally committed additions awaiting projection. */
    private fun authoritativeOwnReactionEmojis(target: String): Set<String> =
        conversationAccountIdHex?.let { me ->
            projectedOwnReactionEmojis(window.references(target), timelineRecords[target], me)
                .includeUnprojectedReactionEmojis(target, unprojectedOwnReactionEventIds.keys)
        } ?: emptySet()

    /** Drives native state toward the newest intent without blocking later optimistic taps. */
    private suspend fun convergeReactionIntent(
        owner: ReactionMutationTarget,
        emoji: String,
        key: Pair<String, String>,
    ): ReactionIntentDrainOutcome {
        val target = owner.messageId
        val ownEmojis = authoritativeOwnReactionEmojis(target).toMutableSet()
        var addedBeforeProjection = key in unprojectedOwnReactionEventIds
        return drainReactionIntent(
            key = key,
            conflator = reactionIntentConflator,
            initialMine = emoji in ownEmojis,
        ) { commitMine ->
            retryBusyReactionMutation(
                stillDesired = { reactionIntentConflator.latest(key)?.desiredMine == commitMine },
            ) {
                if (commitMine) {
                    commitReactionAdd(owner, emoji)
                    ownEmojis += emoji
                    addedBeforeProjection = true
                } else {
                    commitReactionRemoval(
                        owner = owner,
                        emoji = emoji,
                        ownEmojisBeforeMutation = ownEmojis.toSet(),
                        removeBeforeProjection = addedBeforeProjection,
                    )
                    ownEmojis -= emoji
                    addedBeforeProjection = false
                }
            }
        }
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
        val desiredMine = reactions[target].orEmpty().none { it.emoji == emoji && it.mine }
        val optimisticId = reactionIntentOverlayId(target, emoji)
        optimisticReactionChanges[optimisticId] = OptimisticReactionChange(target, emoji, add = desiredMine)
        recomputeReactions(setOf(target))
        val submission = reactionIntentConflator.submit(key, desiredMine)
        // Local presentation acceptance is distinct from native/remote completion.
        onLocalIntent(desiredMine)
        if (!submission.shouldDrain) return

        val outcome =
            try {
                convergeReactionIntent(ReactionMutationTarget(account, target, stillAvailable), emoji, key)
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

    /**
     * Removes one optimistic send and all Android-owned presentation state.
     * The phase entry is deliberately retained while cancellation is in
     * flight so a late parser/upload/retry continuation cannot enter MDK.
     */
    private fun removeCancelledOptimisticSend(
        optimisticKey: String,
        tempId: String,
    ) = discardOptimisticSend(optimisticKey, tempId, OptimisticSendDiscard.CANCELLED)

    /**
     * Drops one optimistic send from Android presentation state. [discard]
     * names the intended differences between cancellation, abandonment and
     * the in-flight discard paths; traces, host attempts, membership and
     * tombstones stay with each caller.
     */
    private fun discardOptimisticSend(
        optimisticKey: String,
        tempId: String,
        discard: OptimisticSendDiscard,
    ) {
        if (discard.releasesEditsAndFailureNotice) {
            pendingMessageEditHandoff.abandon(pendingEditKey(tempId))
            optimisticEdits.remove(tempId)
        }
        optimisticMessages.remove(optimisticKey)
        durableAcceptanceCallbacks.remove(optimisticKey)
        messageById.remove(tempId)
        retainedMediaUploads.remove(optimisticKey)
        activeUploadKeys.remove(optimisticKey)
        if (discard.rollsBackPreview) {
            retentionAtSendByMessageId.remove(tempId)
            rollbackOptimisticChatListPreview(tempId)
        }
        if (discard.removesPhase) optimisticSendPhases.remove(optimisticKey)
        when (discard.diagnostic) {
            DiscardedSendDiagnostic.FORGET -> appState.pendingSendDiagnostics.forget(tempId)
            DiscardedSendDiagnostic.COMPLETE_DROPPED ->
                appState.pendingSendDiagnostics.complete(
                    tempId,
                    PerformancePhase.SEND_COMPLETE,
                    PerformanceResult.DROPPED,
                )
            DiscardedSendDiagnostic.LEAVE_TRACKED -> Unit
        }
        if (discard.releasesEditsAndFailureNotice) {
            appState.dismissSendFailureNotice(sendFailureAttempt(optimisticKey))
        }
        publishTimelineFromIndexes()
    }

    /** Resolves a projected row to its local optimistic send token, if still retained. */
    private fun optimisticSendKey(message: AppMessageRecordFfi): String? {
        val directKey = "msg:${message.messageIdHex}"
        if (directKey in optimisticMessages || directKey in optimisticSendPhases) return directKey
        return optimisticMessages.entries.firstOrNull { (_, item) -> item.record == message }?.key
    }

    /**
     * Competes with native admission under the group commit lock. Cancelling
     * before admission removes the optimistic row and wakes its sleeping retry;
     * once native owns the send, the canonical pending row remains intact.
     */
    @Suppress("ReturnCount") // Distinct unavailable-account/key exits precede the single lock-arbitrated result.
    private suspend fun cancelOptimisticSendResult(
        message: AppMessageRecordFfi,
        optimisticKeyOverride: String? = null,
    ): Result<Unit> {
        val account =
            conversationAccountRef
                ?: return Result.failure(IllegalStateException("Conversation account unavailable"))
        val optimisticKey =
            optimisticKeyOverride
                ?: optimisticSendKey(message)
                ?: return Result.failure(IllegalStateException("Message is no longer locally cancellable"))
        // A running file-backed transfer holds the commit lock for its whole upload; stop it first.
        retainedMediaUploads.get(optimisticKey)?.requestTransferCancel()
        return appState.withGroupCommitLock(account, group.groupIdHex) {
            val current = optimisticMessages[optimisticKey]
            val tempId = current?.record?.messageIdHex ?: message.messageIdHex
            val phase = optimisticSendPhases[optimisticKey]
            when {
                phase == OptimisticSendPhase.CANCELLED -> Result.success(Unit)
                current?.status == MessageStatus.Failed || phase == OptimisticSendPhase.PRE_ACCEPTANCE -> {
                    val retryInFlight = current?.status == MessageStatus.Pending
                    optimisticSendPhases[optimisticKey] = OptimisticSendPhase.CANCELLED
                    optimisticCancellationGeneration.value += 1
                    if (retryInFlight) discardedDuringRetry.add(optimisticKey)
                    removeCancelledOptimisticSend(optimisticKey, tempId)
                    if (!retryInFlight) trimCancelledSendTombstones()
                    Result.success(Unit)
                }
                else -> Result.failure(IllegalStateException("Message is already owned by MarmotKit"))
            }
        }
    }

    /**
     * Runs [block] until it finishes or the send [optimisticKey] is cancelled. A cancel stops [block] at
     * once, so a slow artwork lookup never holds the text order for later sends, and ends the send with
     * [OptimisticSendCancelledException], so it can never publish.
     */
    private suspend fun <T> untilSendCancelled(
        optimisticKey: String,
        block: suspend () -> T,
    ): T =
        coroutineScope {
            // A lookup that never suspends finishes here without yielding the send to anything else.
            val work = async(start = CoroutineStart.UNDISPATCHED) { block() }
            if (work.isCompleted) return@coroutineScope work.await()
            val watcher =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    optimisticCancellationGeneration.first {
                        optimisticSendPhases[optimisticKey] == OptimisticSendPhase.CANCELLED
                    }
                    work.cancel()
                }
            try {
                work.await()
            } catch (cancel: CancellationException) {
                requireOptimisticSendNotCancelled(optimisticKey)
                throw cancel
            } finally {
                watcher.cancel()
            }
        }

    private fun requireOptimisticSendNotCancelled(optimisticKey: String) {
        if (optimisticSendPhases[optimisticKey] == OptimisticSendPhase.CANCELLED) {
            throw OptimisticSendCancelledException()
        }
    }

    private fun recordOptimisticSendAcceptance(
        optimisticKey: String,
        summary: dev.ipf.marmotkit.SendSummaryFfi,
    ) {
        optimisticSendPhases[optimisticKey] =
            if (summary.acceptDisposition == SendAcceptDispositionFfi.ACCEPTED_PENDING) {
                OptimisticSendPhase.ACCEPTED_PENDING
            } else {
                OptimisticSendPhase.SETTLED
            }
    }

    private fun optimisticSendIsMdkOwnedOrUnknown(optimisticKey: String): Boolean =
        when (optimisticSendPhases[optimisticKey]) {
            OptimisticSendPhase.ACCEPTED_PENDING,
            OptimisticSendPhase.ACCEPTANCE_UNKNOWN,
            OptimisticSendPhase.SETTLED,
            -> true
            else -> false
        }

    private fun trimCancelledSendTombstones() {
        val cancelledKeys =
            optimisticSendPhases
                .asSequence()
                .filter { (key, phase) ->
                    phase == OptimisticSendPhase.CANCELLED && key !in discardedDuringRetry
                }.map { (key, _) -> key }
                .toList()
        cancelledKeys
            .take((cancelledKeys.size - MAX_CANCELLED_SEND_TOMBSTONES).coerceAtLeast(0))
            .forEach(optimisticSendPhases::remove)
    }

    /** Advances the read marker after any accepted add without coupling it to reaction rollback. */
    private suspend fun markReactionTargetRead(target: String) {
        runCatchingCancellable { markReadUpTo(target) }
            .onFailure {
                if (BuildConfig.DEBUG) Log.w("DMConversation", "mark-read after reaction failed", it)
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
                    appState.withGroupCommitLock(account, group.groupIdHex) {
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

    /**
     * Publish a kind-1009 edit replacing the body of [targetMessageId] with
     * [content]. The runtime enforces the wire-level constraint that the
     * edit's signer matches the original; recipients re-enforce
     * through MDK’s accepted-edit projection. Trim is applied before send so a
     * trailing newline from the composer doesn't change the visible body.
     */
    suspend fun editMessage(
        targetMessageId: String,
        content: String,
    ) {
        val account = conversationAccountRef ?: return
        if (!canSendMessages) return
        val target = targetMessageId.takeIf { it.isNotBlank() } ?: return
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        // Apply the new body locally before the publish round-trips so the
        // bubble flips to the edited text at once instead of showing the old
        // text until the kind-1009 echoes back (~1s). Capture the body shown
        // before this edit (a prior optimistic edit's pre-edit text takes
        // priority over the now-stale displayed text) so a failure reverts
        // verbatim. Pending drives a brief sending indicator on the bubble.
        val preEditText = optimisticEdits[target]?.preEditText ?: currentDisplayedText(target)
        optimisticEdits[target] = OptimisticEdit(trimmed, preEditText, MessageStatus.Pending)
        publishTimelineFromIndexes()
        try {
            appState.withGroupCommitLock(account, group.groupIdHex) {
                messageEditPublisher(account, group.groupIdHex, target, trimmed)
            }
            // Publish accepted: drop the Pending indicator but keep the text
            // overlay so the bubble doesn't flicker back to the old body in the
            // gap before the kind-1009 lands in the timeline. The overlay is
            // pruned once MDK reflects the same latest text.
            // Only act if this attempt still owns the overlay: if the user
            // re-edited the same target while this publish was in flight, a
            // newer Pending overlay (different text) has superseded us, and
            // flipping it to Sent would wrongly confirm the newer attempt.
            optimisticEdits[target]
                ?.takeIf { it.status == MessageStatus.Pending && it.text == trimmed }
                ?.let { optimisticEdits[target] = it.copy(status = MessageStatus.Sent) }
            publishTimelineFromIndexes()
        } catch (throwable: Throwable) {
            throwable.rethrowIfCancellation()
            // Revert the displayed body to the pre-edit text and flip the
            // bubble to Failed, lighting the same retry/discard affordance a
            // failed send shows. Retry re-runs this edit; discard clears the
            // overlay and restores the original body. Guarded the same way as
            // the success path: a newer in-flight attempt's overlay must not be
            // clobbered back to this stale attempt's Failed/pre-edit text.
            // MDK refuses an edit of a message this account cannot edit; retrying could never succeed, so the
            // original body returns instead of a Failed overlay with Retry.
            val notEditable = throwable is MarmotKitException.InvalidEditTarget
            optimisticEdits[target]
                ?.takeIf { it.status == MessageStatus.Pending && it.text == trimmed }
                ?.let {
                    if (notEditable) {
                        optimisticEdits.remove(target)
                    } else {
                        optimisticEdits[target] = OptimisticEdit(trimmed, preEditText, MessageStatus.Failed)
                    }
                }
            publishTimelineFromIndexes()
            val detail = if (notEditable) R.string.error_message_not_editable else R.string.error_try_again
            val title = R.string.toast_couldnt_edit_message
            appState.presentFailure(title, "MESSAGE_EDIT", throwable, AppText.Resource(detail))
        }
    }

    /** Transfers the revision to MDK before dismissing the editor, with immutable tokens for safe retries. */
    private suspend fun admitPendingEdit(
        target: String,
        originalToken: String,
        text: String,
        retryIntent: DurablePendingEditIntent? = null,
        onAttemptStarted: () -> Unit = {},
    ): Boolean {
        val account = conversationAccountRef ?: return false
        val confirmedTarget = pendingMessageEditHandoff.confirmedTarget(pendingEditKey(target))
        val resolvedTarget =
            confirmedTarget?.takeIf {
                it in timelineRecords || "msg:$it" in optimisticMessages
            } ?: target
        val retained =
            optimisticEdits[resolvedTarget]
                ?.takeIf {
                    !it.nativeRevisionRejected &&
                        it.text == text &&
                        it.durableIntent?.originalClientToken == originalToken
                }?.durableIntent
        val intent = retryIntent ?: retained ?: DurablePendingEditIntent(originalToken, UUID.randomUUID().toString())
        return if (!pendingEditAdmissions.add(intent.editClientToken)) {
            false
        } else {
            onAttemptStarted()
            performPendingEdit(account, target, resolvedTarget, intent, text)
        }
    }

    private suspend fun performPendingEdit(
        account: String,
        target: String,
        resolvedTarget: String,
        intent: DurablePendingEditIntent,
        text: String,
    ): Boolean {
        val previous = optimisticEdits[resolvedTarget]?.preEditText ?: currentDisplayedText(resolvedTarget)
        optimisticEdits[resolvedTarget] = OptimisticEdit(text, previous, MessageStatus.Pending, intent)
        publishTimelineFromIndexes()
        return try {
            runCatchingCancellable { publishPendingEdit(account, intent, text) }.fold(
                onSuccess = { summary -> completePendingEditAdmission(intent, summary, text) },
                onFailure = { cause ->
                    if (intent.editClientToken in pendingEditPublicationObserved) {
                        true
                    } else {
                        failPendingEditAdmission(intent, target, account, cause)
                        // A message MDK will never let this account edit ends the edit too, so the editor closes.
                        cause is MarmotKitException.InvalidEditTarget
                    }
                },
            )
        } finally {
            pendingEditAdmissions.remove(intent.editClientToken)
            pendingEditPublicationObserved.remove(intent.editClientToken)
            optimisticEdits.entries
                .firstOrNull { it.value.durableIntent == intent }
                ?.key
                ?.let(::recoverPendingEditStatus)
        }
    }

    private suspend fun publishPendingEdit(
        account: String,
        intent: DurablePendingEditIntent,
        text: String,
    ) = pendingEditAdmissionMutex.withLock {
        // Preserve this controller's submission order at the local native boundary.
        // Newer UI revisions already own their overlay while this admission waits.
        pendingMessageEditPublisher?.invoke(
            account,
            group.groupIdHex,
            intent.originalClientToken,
            text,
            intent.editClientToken,
        ) ?: appState.marmotIo(MarmotTraceSection.MESSAGE_EDIT) {
            admitPendingMessageEdit(account, group.groupIdHex, intent, text)
        }
    }

    private fun completePendingEditAdmission(
        intent: DurablePendingEditIntent,
        summary: SendSummaryFfi,
        text: String,
    ): Boolean {
        val current = optimisticEdits.entries.firstOrNull { it.value.durableIntent == intent }
        if (current != null) {
            optimisticEdits[current.key] = current.value.copy(nativeEditMessageId = summary.messageIds.firstOrNull())
        }
        publishTimelineFromIndexes()
        return current != null ||
            intent.editClientToken in pendingEditPublicationObserved ||
            timelineRecords.values.any { record ->
                record.edit?.latestEditMessageIdHex in summary.messageIds && record.plaintext == text
            }
    }

    /** Shows a refused pending edit as Failed with Retry, or restores the original text when MDK says it is final. */
    private fun failPendingEditAdmission(
        intent: DurablePendingEditIntent,
        target: String,
        account: String,
        cause: Throwable,
    ) {
        val current = optimisticEdits.entries.firstOrNull { it.value.durableIntent == intent }
        // A message that can no longer be edited gets its original text back; a Retry could never succeed.
        val notEditable = cause is MarmotKitException.InvalidEditTarget
        if (current != null) {
            if (notEditable || optimisticMessages["msg:$target"]?.status == MessageStatus.Failed) {
                optimisticEdits.remove(current.key)
            } else {
                optimisticEdits[current.key] = current.value.copy(status = MessageStatus.Failed)
            }
        }
        publishTimelineFromIndexes()
        if (current != null && appState.activeAccountRef == account) {
            val detail = if (notEditable) R.string.error_message_not_editable else R.string.error_try_again
            val title = R.string.toast_couldnt_edit_message
            appState.presentFailure(title, "MESSAGE_EDIT", cause, AppText.Resource(detail))
        }
    }

    private val pendingEditAdmissions = mutableSetOf<String>()
    private val pendingEditAdmissionMutex = Mutex()

    // Only in-flight callbacks retain this proof; finally removes it even on cancellation.
    private val pendingEditPublicationObserved = mutableSetOf<String>()
    private val pendingEditStatusReads = mutableSetOf<String>()
    private val pendingEditStatusRechecks = mutableSetOf<String>()

    /** Edit-event delivery can advance without changing the original row's rendered body. */
    private fun recoverPendingEditStatusForProjection(record: TimelineMessageRecordFfi) {
        val targets =
            optimisticEdits
                .filterValues { edit ->
                    edit.nativeEditMessageId == record.messageIdHex ||
                        (record.clientToken != null && edit.durableIntent?.editClientToken == record.clientToken)
                }.keys
                .toList()
        recoverPendingEditStatus(record)
        if (record.kind == MESSAGE_EDIT_EVENT_KIND && record.sourceMessageIdHex != null) {
            targets.forEach { target ->
                optimisticEdits[target]?.let { edit ->
                    edit.durableIntent
                        ?.editClientToken
                        ?.takeIf { it in pendingEditAdmissions }
                        ?.let(pendingEditPublicationObserved::add)
                    optimisticEdits[target] =
                        edit.copy(
                            status = MessageStatus.Sent,
                            nativeEditMessageId = record.messageIdHex,
                        )
                }
            }
            if (targets.isNotEmpty()) publishTimelineFromIndexes()
        } else {
            targets.mapNotNull(timelineRecords::get).forEach(::recoverPendingEditStatus)
        }
    }

    /** Recover native engine ownership or terminal rejection when the target reprojects. */
    private fun recoverPendingEditStatus(record: TimelineMessageRecordFfi) {
        recoverPendingEditStatus(record.messageIdHex)
    }

    private fun recoverPendingEditStatus(target: String) {
        val edit = optimisticEdits[target]
        val intent = edit?.durableIntent
        val account = conversationAccountRef
        if (edit == null || intent == null || account == null) return
        val unresolvedFailure = edit.status == MessageStatus.Failed && !edit.nativeRevisionRejected
        val shouldRecover = !controllerCleared && (edit.status == MessageStatus.Pending || unresolvedFailure)
        if (!shouldRecover || intent.editClientToken in pendingEditAdmissions) {
            return
        }
        if (!pendingEditStatusReads.add(intent.editClientToken)) {
            pendingEditStatusRechecks.add(intent.editClientToken)
        } else {
            controllerScope.launch { readPendingEditStatus(account, intent) }
        }
    }

    private suspend fun readPendingEditStatus(
        account: String,
        intent: DurablePendingEditIntent,
    ) {
        try {
            val status =
                runCatchingCancellable {
                    pendingEditStatusReader(account, group.groupIdHex, intent.editClientToken)
                }.getOrNull()
            if (controllerCleared) return
            val current =
                optimisticEdits.entries.firstOrNull {
                    it.value.durableIntent == intent && it.value.status != MessageStatus.Sent
                }
            if (current != null) {
                optimisticEdits[current.key] = current.value.withNativeEditStatus(status)
                publishTimelineFromIndexes()
            }
        } finally {
            pendingEditStatusReads.remove(intent.editClientToken)
            // Coalesce native events received during the read, including identical rows.
            if (pendingEditStatusRechecks.remove(intent.editClientToken) && !controllerCleared) {
                optimisticEdits.entries
                    .firstOrNull { it.value.durableIntent == intent }
                    ?.key
                    ?.let(::recoverPendingEditStatus)
            }
        }
    }

    private fun handoffPendingMessageEdit(
        clientToken: String,
        confirmedId: String,
        ready: Boolean,
    ) {
        val queuedText = pendingMessageEditHandoff.confirm(pendingEditKey(clientToken), confirmedId, ready)
        if (!ready || confirmedId == clientToken) return
        optimisticEdits.remove(clientToken)?.let { edit ->
            // An edit already submitted against the confirmed row is newer than the temporary overlay.
            if (confirmedId !in optimisticEdits) optimisticEdits[confirmedId] = edit
        }
        if (queuedText != null) {
            // Projection installation can still be mid-batch here. Resume on
            // the next main turn before publishing and rebuilding the timeline.
            appState.launchMutation {
                yield()
                editMessage(confirmedId, queuedText)
            }
        }
    }

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

    /**
     * Body shown for [targetId] before any in-flight optimistic edit: the
     * latest confirmed kind-1009 text if one exists, otherwise the message's
     * original plaintext. Captured as an optimistic edit's revert target.
     */
    private fun currentDisplayedText(targetId: String): String =
        editsByTarget[targetId]?.latestText
            ?: messageById[targetId]?.plaintext
            ?: optimisticMessages["msg:$targetId"]?.record?.plaintext
            ?: ""

    // Tracks optimistic ids the user discarded while a retry was in flight.
    // The retry coroutine consults this set before re-inserting a failed
    // record, so a discard during retry doesn't get clobbered by the catch
    // path putting the message back as Failed.
    private val discardedDuringRetry = mutableSetOf<String>()

    /** Shared wakeup lets a replacement controller release an old controller's retry. */
    private val optimisticCancellationGeneration =
        appState.optimisticCancellationGeneration(conversationAccountRef, initialGroup.groupIdHex)

    /**
     * Settles the durable-acceptance callback for [optimisticKey], and retires the send-failure
     * notice that attempt raised: once the engine owns the send, the notice is no longer true (#2666).
     */
    private fun completeDurableAcceptance(optimisticKey: String) {
        durableAcceptanceCallbacks.remove(optimisticKey)?.invoke()
        appState.dismissSendFailureNotice(sendFailureAttempt(optimisticKey))
    }

    /** Correlation key for a notice raised by the send attempt on optimistic [key]. */
    private fun sendFailureAttempt(key: String) = SendFailureAttempt(conversationAccountRef, group.groupIdHex, key)

    private fun rollbackOptimisticChatListPreview(optimisticMessageIdHex: String) {
        appState.rollbackOptimisticSentPreview(conversationAccountRef, group.groupIdHex, optimisticMessageIdHex)
    }

    private fun failOptimisticChatListPreview(optimisticMessageIdHex: String) {
        appState.failOptimisticSentPreview(conversationAccountRef, group.groupIdHex, optimisticMessageIdHex)
    }

    /** The pending chat-list preview an outgoing send bumps its row with. */
    private fun sentPreview(
        tempId: String,
        plaintext: String,
        contentTokens: MarkdownDocumentFfi,
        timelineAt: ULong,
    ): ChatListMessagePreviewFfi =
        ChatListMessagePreviewFfi(
            retentionSeconds = null,
            retentionExpiresAt = null,
            messageIdHex = tempId,
            sender = conversationAccountIdHex ?: "",
            senderDisplayName = null,
            plaintext = plaintext,
            contentTokens = contentTokens,
            kind = 9uL,
            timelineAt = timelineAt,
            deleted = false,
            deletionSource = DeletionSourceFfi.UNKNOWN,
            attachmentKind = null,
            attachmentCount = 0u,
            groupSystem = null,
            deliveryState = ChatListMessageDeliveryStateFfi.PENDING,
            sticker = null,
        )

    // The chat-list preview a manual retry re-applies: same optimistic id so
    // the row entry flips FAILED → PENDING in place, fresh timestamp so the
    // row re-bumps to the top like any new send.
    private fun retryPreview(
        tempId: String,
        record: AppMessageRecordFfi,
    ): ChatListMessagePreviewFfi = sentPreview(tempId, record.plaintext, record.contentTokens, nowSeconds())

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
    private val retainedMediaUploads =
        appState.retainedMediaUploads(conversationAccountRef, initialGroup.groupIdHex)
    private val activeUploadKeys =
        appState.activeUploadKeys(conversationAccountRef, initialGroup.groupIdHex)

    /**
     * App-level cache key for a decrypted attachment. Scoped to
     * account+group+message (not bare messageIdHex) so a cache entry can only
     * ever satisfy a lookup from the same account and group that decrypted it —
     * defense-in-depth against an evicted/rejoined member replaying an old
     * event id to read plaintext it shouldn't.
     */
    private fun mediaCacheKey(
        account: String,
        messageIdHex: String,
        attachmentIndex: Int,
    ): String = attachmentPresentation.mediaCacheKey(account, messageIdHex, attachmentIndex)

    private fun mediaUploadSessionStillCurrent(account: String): Boolean =
        shouldAcceptMediaUploadForAccount(
            account,
            mediaUploadSessionEpoch,
            appState.activeAccountRef,
            appState.mediaUploadSessionEpoch(),
        )

    // Evict decrypted media (L1 plaintext, decoded thumbnails, L2 disk) for the
    // attachments the engine just secure-deleted on expiry, matched by ciphertext
    // hash through the cached references. Without this the decrypted bytes stay
    // recoverable from the local caches after the row is gone (#674 review).
    private suspend fun evictExpiredMediaCaches(
        account: String,
        expiredCiphertextSha256: Set<String>,
    ) {
        if (expiredCiphertextSha256.isEmpty()) return
        // Map expired hashes to cache keys via the loaded projected rows.
        // TimelineMessageRecordFfi.media is authoritative and already carries
        // each attachment's real source epoch, so this needs no group-wide
        // listMedia scan or parallel controller cache.
        val loadedKeys =
            timelineRecords.values.flatMap { record ->
                MessageAttachments.accepted(record.media).mapNotNull { (index, reference) ->
                    if (reference.ciphertextSha256 in expiredCiphertextSha256) {
                        mediaCacheKey(account, record.messageIdHex, index)
                    } else {
                        null
                    }
                }
            }
        // The bounded window can't map rows it has already trimmed: any L1
        // key in this group's slice without a loaded or in-flight row is
        // dropped fail-closed alongside the precise hits, so a pruned old
        // attachment's decrypted bytes can't outlive the prune.
        val loadedMessageIds =
            buildSet {
                addAll(timelineRecords.keys)
                optimisticMessages.values.forEach { add(it.record.messageIdHex) }
                addAll(optimisticMessages.keys)
            }
        val staleKeys =
            withContext(Dispatchers.Main.immediate) {
                staleGroupMediaCacheKeys(
                    cachedKeys = appState.mediaMemoryCacheKeysSnapshot(),
                    account = account,
                    groupIdHex = group.groupIdHex,
                    loadedMessageIds = loadedMessageIds,
                )
            }
        val evictedKeys = loadedKeys + staleKeys
        removeMediaMemoryCacheKeys(
            cacheKeys = evictedKeys,
            dispatcher = Dispatchers.Main.immediate,
            removeEntry = appState::removeMediaMemoryCacheEntry,
        )
        withContext(Dispatchers.IO) {
            loadedKeys.forEach { appState.diskMediaCache.remove(it) }
            // Plus any disk entry stamped with an expired ciphertext tag — the
            // only path that reaches media whose message isn't currently loaded,
            // including across process restarts. #674 review.
            appState.diskMediaCache.removeByCiphertextTags(expiredCiphertextSha256)
        }
    }

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
     * Race-fix for own-sent media bubbles: when an outgoing media projection
     * arrives and we reconcile it against the pending `_media_pending`
     * optimistic, we already hold the JPEG bytes in [retainedMediaUploads].
     * Seed L1 plaintext + decoded thumbnail under the projection's cache
     * key so the bubble's `LaunchedEffect` finds bytes immediately —
     * otherwise it would call `downloadAttachment` → empty L1 → empty L2
     * (background disk write hasn't flushed yet) → re-download from
     * Blossom, even though we just uploaded the same bytes.
     *
     * L2 (disk) write is scheduled in the background, same pattern as
     * `downloadAttachment`'s post-fetch path.
     */
    private fun handoffOwnMediaCacheOnReconcile(
        optimisticKey: String,
        projectedMessageIdHex: String,
    ) {
        val retained = retainedMediaUploads.get(optimisticKey) ?: return
        val account = conversationAccountRef?.takeIf(::mediaUploadSessionStillCurrent) ?: return
        // Seed every attachment under its own (messageId, attachmentIndex)
        // key so the projected album bubble's per-tile cache lookups all
        // hit immediately on reconcile.
        retained.attachments.forEachIndexed { index, attachment ->
            if (!mediaUploadSessionStillCurrent(account)) return@forEachIndexed
            // MDK retains a file-backed item's own copy; only in-memory bytes seed the host cache.
            val bytes = attachment.inMemoryBytes ?: return@forEachIndexed
            val cacheKey = mediaCacheKey(account, projectedMessageIdHex, index)
            appState.cacheUploadedAttachment(
                cacheKey,
                bytes,
                retained.uploadedReferences?.getOrNull(index)?.ciphertextSha256,
            )
            // Register local publication before yielding, including files beyond L1 admission,
            // so a confirmed card waits for its disk copy instead of re-downloading from Blossom.
            // Offload the multi-MB ARGB thumbnail decode off this main-thread
            // reconcile. The main-confined thumbnail-cache put resumes on Main
            // via launchMutation's Main.immediate scope. Mirrors the
            // receive/render path in WhiteNoiseApp.
            val posterSource = attachment
            appState.launchMutation {
                val decoded = decodeMediaThumbnailOffMain(posterSource)
                if (decoded != null && mediaUploadSessionStillCurrent(account)) {
                    appState.cacheMediaThumbnail(cacheKey, decoded)
                }
            }
        }
        if (retained.acceptedPending) {
            retainedMediaUploads.remove(optimisticKey)
            activeUploadKeys.remove(optimisticKey)
        }
    }

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
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    suspend fun retryFailedSend(item: TimelineMessage) {
        // A send that failed before the leave was blocked, or before a disband, stays failed while that state holds.
        outboundRefusal()?.let {
            appState.presentTransient(it)
            return
        }
        val key = item.id
        // A failed edit's bubble is the target's projected row (no
        // optimisticMessages entry); its retry re-runs the edit publish rather
        // than re-sending a new message. editMessage flips the overlay back to
        // Pending, so a double-tap finds it non-Failed and the guard below exits.
        val failedEdit = failedEditForAction(item)
        if (failedEdit != null) {
            val intent = failedEdit.durableIntent
            if (intent == null) {
                editMessage(item.record.messageIdHex, failedEdit.text)
            } else {
                // A definite native rejection owns no publication. Only this deliberate Retry
                // creates a new token; ambiguous admissions keep their original identity.
                val retryIntent = intent.takeUnless { failedEdit.nativeRevisionRejected }
                admitPendingEdit(item.record.messageIdHex, intent.originalClientToken, failedEdit.text, retryIntent)
            }
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
        // Media attachments re-upload from the retained compressed bytes via
        // the shared path. If the bytes were evicted/lost, performMediaUpload
        // flips back to Failed and prompts a re-attach.
        if (current.record.tags.any { it.values.firstOrNull() == "_media_pending" }) {
            val mediaOrder = retriedTimelineOrder(current.timelineOrder) { nextOptimisticTimelineOrder() }
            val mediaTempId = current.record.messageIdHex
            optimisticMessages[key] =
                current.copy(
                    status = MessageStatus.Pending,
                    timelineOrder = mediaOrder,
                )
            optimisticSendPhases[key] = OptimisticSendPhase.PRE_ACCEPTANCE
            // Re-mark this slot as "in flight" — if the previous attempt's
            // Failed branch had drained the bytes via a dispose-time clear,
            // this would still no-op (performMediaUpload bails on missing
            // bytes), but normally the bytes are retained for retry.
            activeUploadKeys.add(key)
            appState.trackInFlightMediaUpload(conversationAccountRef, group.groupIdHex, key)
            publishTimelineFromIndexes()
            // The retry is a fresh pending send from the list's point of view:
            // flip the row's failed preview back to pending and re-bump its
            // position. performMediaUpload owns the terminal state.
            appState.applyOptimisticSentPreview(
                conversationAccountRef,
                group.groupIdHex,
                retryPreview(mediaTempId, current.record),
            )
            appState.pendingSendDiagnostics.startMediaSend(mediaTempId, manualRetry = true)
            performMediaUpload(account, key, mediaTempId, mediaOrder, current.record)
            return
        }
        val tempId = current.record.messageIdHex
        val text = current.record.plaintext.takeIf { it.isNotBlank() } ?: return
        val replyTarget = MessageProjector.replyTargetMessageId(current.record)
        val refreshedRecord = current.record.copy()
        val order = retriedTimelineOrder(current.timelineOrder) { nextOptimisticTimelineOrder() }
        discardedDuringRetry.remove(key)
        optimisticSendPhases[key] = OptimisticSendPhase.PRE_ACCEPTANCE
        optimisticMessages[key] =
            current.copy(
                record = refreshedRecord,
                status = MessageStatus.Pending,
                timelineOrder = order,
            )
        messageById[tempId] = refreshedRecord
        publishTimelineFromIndexes()
        appState.applyOptimisticSentPreview(
            conversationAccountRef,
            group.groupIdHex,
            retryPreview(tempId, refreshedRecord),
        )
        var retryTrace: PerformanceTrace? = null
        try {
            val activeAccountIdHex = conversationAccountIdHex
            val committedProjection =
                committedButUnpublishedProjectionForOptimistic(
                    timelineRecords,
                    refreshedRecord,
                    activeAccountIdHex,
                )
            if (committedProjection != null) {
                optimisticSendPhases[key] = OptimisticSendPhase.ACCEPTED_PENDING
                preserveOptimisticDisplayPosition(committedProjection.messageIdHex, tempId)
                completeDurableAcceptance(key)
                transferRetentionAtSend(tempId, committedProjection.messageIdHex)
                appState.commitOptimisticSentPreview(
                    accountRef = conversationAccountRef,
                    groupIdHex = group.groupIdHex,
                    optimisticMessageIdHex = tempId,
                    confirmedMessageIdHex = committedProjection.messageIdHex,
                )
                optimisticMessages.remove(key)
                messageById.remove(tempId)
                val projectedRecord = upsertProjectedRecord(committedProjection)
                handoffPendingMessageEdit(tempId, committedProjection.messageIdHex, ready = true)
                invalidatedProjectionIdsMatchingMessage(timelineRecords, projectedRecord)
                    .forEach(::removeProjectedRecord)
                if (discardedDuringRetry.remove(key)) {
                    publishTimelineFromIndexes()
                    optimisticSendPhases.remove(key)
                    trimCancelledSendTombstones()
                    return
                }
                publishTimelineFromIndexes()
                optimisticSendPhases.remove(key)
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
            val reconciliation =
                reconcileSuccessfulTextSend(
                    summaryMessageIds = summary.messageIds,
                    acceptDisposition = summary.acceptDisposition,
                    optimisticKey = key,
                    tempId = tempId,
                    optimisticRecord = refreshedRecord,
                    optimisticMessages = optimisticMessages,
                    messageById = messageById,
                    projectedMessageIds = projectedMessageIds,
                    timelineOrder = order,
                    acceptedPendingTextOptimisticIdsByMessageId = acceptedPendingTextOptimisticIds,
                )
            handoffPendingMessageEdit(
                tempId,
                reconciliation.confirmedId,
                ready = !reconciliation.acceptedPending && !reconciliation.awaitingEcho,
            )
            appState.pendingSendDiagnostics.alias(tempId, reconciliation.confirmedId)
            appState.pendingSendDiagnostics.recordAcceptedPending(tempId, reconciliation.acceptedPending)
            if (reconciliation.awaitingProjection) {
                appState.pendingSendDiagnostics.update(tempId, PerformanceSendStage.WAITING_PROJECTION)
            }
            if (!reconciliation.acceptedPending) {
                transferRetentionAtSend(tempId, reconciliation.confirmedId)
                appState.commitOptimisticSentPreview(
                    accountRef = conversationAccountRef,
                    groupIdHex = group.groupIdHex,
                    optimisticMessageIdHex = tempId,
                    confirmedMessageIdHex = reconciliation.confirmedId,
                )
                invalidatedProjectionIdsMatchingMessage(timelineRecords, reconciliation.confirmed)
                    .forEach(::removeProjectedRecord)
            }
            publishTimelineFromIndexes()
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

    /** Settle a manual text retry without republishing an uncertain delivery. */
    @Suppress("LongMethod") // Each failure classification owns different cleanup and user-visible recovery state.
    private fun handleFailedSendRetryFailure(
        throwable: Throwable,
        key: String,
        tempId: String,
        current: TimelineMessage,
        refreshedRecord: AppMessageRecordFfi,
        order: ULong,
        account: String,
        retryTrace: PerformanceTrace?,
    ) {
        if (throwable is OptimisticSendCancelledException) {
            discardedDuringRetry.remove(key)
            removeCancelledOptimisticSend(key, tempId)
            trimCancelledSendTombstones()
            return
        }
        if (throwable is CancellationException && optimisticSendPhases[key] == OptimisticSendPhase.CANCELLED) {
            discardedDuringRetry.remove(key)
            trimCancelledSendTombstones()
        }
        throwable.rethrowIfCancellation()
        if (BuildConfig.DEBUG) Log.w("DMConversation", "retryFailedSend failed", throwable)
        when {
            discardedDuringRetry.remove(key) -> {
                // User discarded mid-flight; don't restore the Failed bubble.
                discardOptimisticSend(key, tempId, OptimisticSendDiscard.DISCARDED_IN_FLIGHT)
                trimCancelledSendTombstones()
            }
            throwable.isUseAfterEviction() -> {
                discardOptimisticSend(key, tempId, OptimisticSendDiscard.ABANDONED)
                markActiveAccountRemovedFromMembers(account)
            }
            isAmbiguousRelayDeliveryError(throwable) -> {
                if (optimisticSendPhases[key] == OptimisticSendPhase.PRE_ACCEPTANCE) {
                    optimisticSendPhases[key] = OptimisticSendPhase.ACCEPTANCE_UNKNOWN
                }
                // Publication may already have reached a relay. Keep the
                // existing row Pending; another high-level send could mint a
                // duplicate event.
                sendTrace(
                    retryTrace,
                    PerformancePhase.DELIVERY_UNCERTAIN,
                    result = PerformanceResult.PENDING,
                    layer = PerformanceLayer.TRANSPORT,
                )
                appState.pendingSendDiagnostics.update(tempId, PerformanceSendStage.DELIVERY_UNCERTAIN)
                publishTimelineFromIndexes()
            }
            optimisticSendIsMdkOwnedOrUnknown(key) -> {
                publishTimelineFromIndexes()
            }
            else -> {
                optimisticSendPhases[key] = OptimisticSendPhase.FAILED
                optimisticMessages[key] =
                    current.copy(
                        record = refreshedRecord,
                        status = MessageStatus.Failed,
                        timelineOrder = order,
                    )
                failOptimisticChatListPreview(tempId)
                suppressProjectedTimelineItems(
                    unpublishedProjectionIdsMatchingMessage(
                        timelineRecords = timelineRecords,
                        message = refreshedRecord,
                        activeAccountIdHex = conversationAccountIdHex,
                    ),
                )
                publishTimelineFromIndexes()
                appState.pendingSendDiagnostics.forget(tempId)
                presentSendFailure(appState, throwable, sendFailureAttempt(key))
            }
        }
    }

    /**
     * Drops a failed outgoing send from the local timeline. Purely client-side
     * cleanup; the message was never accepted by MarmotKit, so there is
     * nothing to retract. Cancellation still enters the group commit lock so
     * a stale failure action cannot race a retry that has already been admitted.
     */
    suspend fun discardFailedSend(item: TimelineMessage) {
        val key = item.id
        // Discarding a failed edit drops the local overlay, reverting the
        // bubble to its pre-edit body. The original message is untouched —
        // only the unsent kind-1009 edit is abandoned.
        val failedEdit = failedEditForAction(item)
        if (failedEdit != null) {
            val edit = failedEdit
            val intent = edit.durableIntent
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
                    removeCancelledOptimisticSend(key, item.record.messageIdHex)
                    return
                }
        cancelOptimisticSendResult(current.record, optimisticKeyOverride = key)
            .onFailure { failure ->
                appState.presentFailure(R.string.toast_couldnt_delete_message, "MESSAGE_CANCEL", failure)
            }
    }

    /** Retry and Discard act on a failed original before its dependent durable edit. */
    private fun failedEditForAction(item: TimelineMessage): OptimisticEdit? =
        optimisticEdits[item.record.messageIdHex]?.takeIf {
            it.status == MessageStatus.Failed &&
                !(it.durableIntent != null && optimisticMessages[item.id]?.status == MessageStatus.Failed)
        }

    /** An unavailable acknowledgement cannot authorize discarding a durably queued revision. */
    private suspend fun discardPendingEdit(
        target: String,
        intent: DurablePendingEditIntent,
    ) {
        val account = conversationAccountRef ?: return
        val result =
            runCatchingCancellable {
                pendingEditStatusReader(account, group.groupIdHex, intent.editClientToken)
            }
        val current =
            optimisticEdits[target]?.takeIf {
                it.durableIntent == intent && it.status == MessageStatus.Failed
            } ?: return
        val status = result.getOrNull()
        when {
            result.isFailure -> {
                if (appState.activeAccountRef == account) {
                    appState.presentFailure(
                        R.string.toast_couldnt_edit_message,
                        "MESSAGE_EDIT",
                        requireNotNull(result.exceptionOrNull()),
                    )
                }
            }
            status == null || status == LocalSendStatusFfi.Rejected -> optimisticEdits.remove(target)
            else -> optimisticEdits[target] = current.withNativeEditStatus(status)
        }
        publishTimelineFromIndexes()
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
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    if (soleMember) {
                        appState.deleteGroupLocalWithClientCleanup(account, group.groupIdHex)
                    } else {
                        if (GroupProjector.requiresSelfDemoteBeforeLeave(group, activeAccountIdHex, liveMemberCount)) {
                            demoteThenLeave(account) { demotedBeforeLeave = true }
                        } else {
                            appState.marmotIo { leaveGroup(account, group.groupIdHex) }
                        }
                    }
                }
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

    /**
     * Steps [account] down as admin, then leaves, asking first if MDK holds the step-down back. The question stays
     * cancellable; once the step-down starts, it, [onDemoted] and the leave run to completion together.
     */
    private suspend fun demoteThenLeave(
        account: String,
        onDemoted: () -> Unit,
    ) {
        appState.runGroupChange(account, group.groupIdHex, actionLabel = R.string.group_action_step_down_and_leave) {
            withContext(NonCancellable) {
                val demoteResult =
                    appState.marmotIo(MarmotTraceSection.SELF_DEMOTE_ADMIN) {
                        selfDemoteAdminDetailed(account, group.groupIdHex)
                    }
                onDemoted()
                applyMutationDetails(account, demoteResult.details)
                appState.marmotIo { leaveGroup(account, group.groupIdHex) }
            }
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
                            "timeline" to {
                                refreshCurrentTimeline(account).forEach { streamId ->
                                    if (activeStreamIds.add(streamId)) {
                                        inviteStreamScope.launch { watchAgentTextStream(account, streamId) }
                                    }
                                }
                            },
                            "read-state" to { initializeReadState(account) },
                        ),
                    onFailure = { step, throwable ->
                        if (BuildConfig.DEBUG) Log.w("DMConversation", "post-accept $step refresh failed", throwable)
                    },
                )
            }
            true
        }

    /**
     * Resolves the native Join call while every retry remains fenced to the
     * same rendered Welcome and controller owner.
     */
    private suspend fun resolveInviteAcceptance(attempt: InviteAcceptanceAttempt): AppGroupRecordFfi? =
        runCatching {
            retryIdempotentRuntimeMutation(
                onTransientFailure = { attemptNumber ->
                    Log.w(
                        "DMConversation",
                        "invite acceptance temporarily unavailable; retrying " +
                            "failedAttempt=$attemptNumber",
                    )
                },
            ) {
                if (ownsInviteAttempt(attempt)) {
                    inviteAcceptor(attempt.account, attempt.generation.groupIdHex)
                } else {
                    null
                }
            }
        }.getOrElse { failure ->
            if (failure is MarmotKitException.GroupInviteNotPending) {
                if (ownsInviteAttempt(attempt)) {
                    inviteConfirmationAuthority.finish()
                    group = group.copy(pendingConfirmation = false)
                    inviteAcceptanceAwaitingAuthority = attempt.generation
                    appState.applyLocalGroupUpdate(group, attempt.account)
                    refreshMembers()
                }
                return@getOrElse null
            }
            val currentGroup = group
            group = inviteConfirmationAuthority.rollback(currentGroup)
            val ownsPresentation = ownsInviteAcceptanceResult
            if (ownsPresentation && group != currentGroup) appState.applyLocalGroupUpdate(group, attempt.account)
            failure.rethrowIfCancellation()
            if (ownsPresentation) {
                appState.presentFailure(R.string.toast_couldnt_accept_invite, "GROUP_INVITE_ACCEPT", failure)
            }
            null
        }

    /** Keeps metadata refreshed during Join while committing confirmation for its current Welcome. */
    private fun applyAcceptedInviteGroup(
        acceptedGroup: AppGroupRecordFfi,
        authorityEpochBefore: Long,
    ) {
        val confirmed =
            if (mutationPresentation.groupAuthorityEpoch == authorityEpochBefore) {
                acceptedGroup
            } else {
                group.copy(pendingConfirmation = false)
            }
        group = inviteConfirmationAuthority.reconcile(confirmed)
        inviteConfirmationAuthority.finish()
    }

    // The invitation whose decline waits for the user to agree to step down as admin first.
    private var inviteDeclineStepDownRequest by mutableStateOf<InviteAcceptanceGeneration?>(null)

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
     * Declines the current pending Welcome. Declining counts as leaving, and MDK will not let an admin leave until
     * they step down, which the group's members see, so an admin invitee is asked first instead of being stepped
     * down silently. A non-admin invitee declines at once.
     */
    suspend fun declineInvite(): Boolean = declineInviteWithConsent(stepDownConsent = null)

    /**
     * Steps down and declines the invitation the user agreed to, but only while it is still the one shown. The
     * question stays open until the decline takes the mutation lock, so a busy conversation never drops the answer.
     */
    suspend fun confirmInviteDeclineStepDown(): Boolean {
        val consent = inviteDeclineStepDownRequest ?: return false
        return declineInviteWithConsent(stepDownConsent = consent)
    }

    /**
     * Declines the current pending Welcome, stepping down first only when [stepDownConsent] names this invitation.
     * Without that consent an admin invitee, or a decline MDK refuses for an admin, raises the step-down question.
     */
    private suspend fun declineInviteWithConsent(stepDownConsent: InviteAcceptanceGeneration?): Boolean =
        withMutationLockResult(false) {
            if (inviteAcceptanceResolutionPending) return@withMutationLockResult false
            val account = conversationAccountRef ?: return@withMutationLockResult false
            val invitation = inviteGeneration(group)
            val stepDownAgreed = stepDownConsent != null
            if (requestDeclineConsentIfNeeded(invitation, stepDownConsent)) return@withMutationLockResult false
            val stillPending = {
                mutationPresentation.ownsCurrentPresentation() &&
                    !inviteAcceptanceResolutionPending &&
                    group.pendingConfirmation &&
                    inviteGeneration(group) == invitation
            }
            runCatching {
                when (declinePendingInvite(account, invitation.groupIdHex, stepDownAgreed, stillPending)) {
                    InviteDeclineOutcome.DECLINED -> Unit
                    InviteDeclineOutcome.STALE -> return@runCatching false
                    InviteDeclineOutcome.NEEDS_STEP_DOWN_CONSENT -> {
                        inviteDeclineStepDownRequest = invitation
                        return@runCatching false
                    }
                }
                appState.dismissConversationNotifications(account, invitation.groupIdHex)
                if (mutationPresentation.ownsCurrentPresentation() && inviteGeneration(group) == invitation) {
                    group = group.copy(pendingConfirmation = false, archived = true)
                    appState.applyLocalGroupUpdate(group, account)
                    presentConversationTransient(R.string.toast_invite_declined)
                }
                true
            }.getOrElse { failure ->
                failure.rethrowIfCancellation()
                presentInviteDeclineFailure(failure, invitation)
                false
            }
        }

    /**
     * Stops Decline for [invitation] when it must not go ahead yet, and returns whether it stopped. Consent counts
     * only for the invitation it named and is used up here, under the mutation lock. A sole admin cannot step down,
     * so asking would promise what MDK refuses: they get the existing guidance at once. Any other admin who has not
     * agreed yet gets the step-down question. MDK's own refusals stay the fallback when this copy of the roles is
     * stale.
     */
    private fun requestDeclineConsentIfNeeded(
        invitation: InviteAcceptanceGeneration,
        stepDownConsent: InviteAcceptanceGeneration?,
    ): Boolean {
        inviteDeclineStepDownRequest = null
        val stop =
            when {
                stepDownConsent != null -> stepDownConsent != invitation
                isSoleAdminInvitee() -> {
                    appState.present(R.string.toast_make_another_admin_before_leaving, R.string.toast_group_needs_admin)
                    true
                }
                isSelfAdmin -> {
                    inviteDeclineStepDownRequest = invitation
                    true
                }
                else -> false
            }
        return stop
    }

    /**
     * Whether this account is the invitation's only admin, from MDK's admin list, which a pending invitation has
     * before its roster loads.
     */
    private fun isSoleAdminInvitee(): Boolean = isSelfAdmin && GroupProjector.uniqueAdminCount(group) <= 1

    /** Explains a failed decline, giving a sole admin the same guidance as Leave instead of a generic failure. */
    private fun presentInviteDeclineFailure(
        failure: Throwable,
        invitation: InviteAcceptanceGeneration,
    ) {
        if (
            failure is MarmotKitException.WouldRemoveLastAdmin &&
            failure.groupIdHex.equals(invitation.groupIdHex, ignoreCase = true)
        ) {
            appState.present(R.string.toast_make_another_admin_before_leaving, R.string.toast_group_needs_admin)
        } else {
            appState.presentFailure(R.string.toast_couldnt_decline_invite, "GROUP_INVITE_DECLINE", failure)
        }
    }

    /**
     * Serializes decline with commits and keeps an admitted step-down and its decline together. MDK refusing an
     * admin's decline steps down only when [stepDownAgreed], otherwise the caller asks the user first.
     */
    private suspend fun declinePendingInvite(
        account: String,
        groupIdHex: String,
        stepDownAgreed: Boolean,
        stillPending: () -> Boolean,
    ): InviteDeclineOutcome =
        appState.withGroupCommitLock(account, groupIdHex) {
            if (!stillPending()) return@withGroupCommitLock InviteDeclineOutcome.STALE
            try {
                appState.marmotIo { declineGroupInvite(account, groupIdHex) }
            } catch (refusal: MarmotKitException.AdminCannotSelfRemove) {
                if (!refusal.groupIdHex.equals(groupIdHex, ignoreCase = true)) throw refusal
                if (!stepDownAgreed) return@withGroupCommitLock InviteDeclineOutcome.NEEDS_STEP_DOWN_CONSENT
                if (!stillPending()) return@withGroupCommitLock InviteDeclineOutcome.STALE
                val declineResult =
                    appState.runGroupChange(
                        account,
                        groupIdHex,
                        stillWanted = stillPending,
                        actionLabel = R.string.decline,
                    ) {
                        withContext(NonCancellable) {
                            val demotion =
                                appState.marmotIo(MarmotTraceSection.SELF_DEMOTE_ADMIN) {
                                    selfDemoteAdminDetailed(account, groupIdHex)
                                }
                            applyMutationDetails(account, demotion.details)
                            // Keep a failed decline from retrying a step-down that already succeeded.
                            runCatchingCancellable { appState.marmotIo { declineGroupInvite(account, groupIdHex) } }
                        }
                    }
                declineResult.getOrThrow()
            }
            InviteDeclineOutcome.DECLINED
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
     * Shared shape of the two local removals: one mutation slot, a success notice chosen by the removal itself. Both
     * close this conversation, so the notice is app-wide instead of scoped to the closed route (#3310), and like a
     * failure it is not shown on a replacement account or runtime.
     */
    private suspend fun runLocalGroupRemoval(
        failureToast: Int,
        operationCode: String,
        removal: suspend (account: String) -> Int,
    ): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            runCatchingCancellable {
                val success = removal(account)
                val sameRuntime = appState.runtimeGeneration == conversationRuntimeGeneration
                if (sameRuntime && appState.activeAccountRef == account) appState.presentTransient(success)
                true
            }.onFailure { recordMutationFailure(failureToast, operationCode, it) }.getOrDefault(false)
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
     * Installs a just-committed group profile locally and hands it to the chat list (#2696).
     *
     * [LocalGroupFieldAuthority] then retains the new name and description against a pre-edit
     * snapshot that was already in flight, while a genuinely newer authoritative commit still wins.
     */
    private fun applyLocalGroupProfile(
        before: AppGroupRecordFfi,
        updatedName: String?,
        updatedDescription: String?,
        account: String,
    ) {
        // [before] is the record the edit was made against: the subscription may already have applied
        // the committed profile, and the fences still need the values it replaced. Only the fields
        // this save changed are fenced; the other one keeps whatever the subscription last applied.
        val newName = updatedName?.takeIf { it != before.name }
        val newDescription = updatedDescription?.takeIf { it != before.description }
        if (newName == null && newDescription == null) return
        newName?.let { localGroupNames.record(group.groupIdHex, before.name, it) }
        newDescription?.let { localGroupDescriptions.record(group.groupIdHex, before.description, it) }
        val clearedName = before.name.takeIf { newName?.isBlank() == true && it.isNotBlank() }
        group =
            group.copy(
                name = updatedName ?: group.name,
                description = updatedDescription ?: group.description,
            )
        appState.applyLocalGroupUpdate(group, account, clearedName = clearedName)
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

    /**
     * Best-effort clean-up of the encrypted image a public avatar supersedes. The user never asked for it, so when MDK
     * holds it back nobody is asked: the refusal, like any failure, leaves the encrypted image in place, and the
     * public avatar still wins for display.
     */
    private suspend fun clearSupersededGroupImage(account: String): Boolean =
        runCatchingCancellable {
            appState.runGroupChange(account, group.groupIdHex, askUser = false, actionLabel = R.string.remove_photo) {
                appState.marmotIo { clearGroupImage(account, group.groupIdHex) }
            }
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w("DMConversation", "encrypted avatar cleanup failed", it)
        }.isSuccess

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

    /** Serializes permission, primary image publication and legacy cleanup, retaining ambiguous cleanup recovery. */
    private suspend fun commitGroupImageMutation(
        account: String,
        capturedGroupId: String,
        change: ScopedGroupImageMutation<ImageUploadDraft?>,
        requestedMutationKey: String,
        onLegacyClearAttempt: () -> Unit,
    ): Boolean =
        appState.withGroupCommitLock(account, capturedGroupId) {
            val admission = admitViewerGroupImageMutation(appState, change, account, capturedGroupId)
            if (!admission.allowed) return@withGroupCommitLock false
            val needsPrimary =
                if (change.viewerPermissionCheck) {
                    !admission.alreadyCommitted
                } else {
                    shouldCommitPrimaryGroupImageMutation(
                        requestedMutationKey = requestedMutationKey,
                        pendingLegacyClearMutationKey = pendingLegacyAvatarClearAfterImageMutationKey,
                        hasProjectedEncryptedImage = group.imageHashHex != null,
                    )
                }
            if (needsPrimary) {
                commitGroupImageBytes(account, capturedGroupId, change.value, change)
            }

            // URL avatars win; clear the legacy URL only after the encrypted
            // mutation succeeds so a partial failure never removes both images.
            if (admission.legacyAvatarPresent ||
                !group.avatarUrl.isNullOrBlank() ||
                pendingLegacyAvatarClearAfterImageMutationKey != null
            ) {
                pendingLegacyAvatarClearAfterImageMutationKey = requestedMutationKey
                onLegacyClearAttempt()
                try {
                    appState.runGroupChange(
                        account,
                        capturedGroupId,
                        stillWanted = { change.stillOwned() },
                        actionLabel = R.string.change_photo,
                    ) {
                        val admitted = isAdmittedGroupChange()
                        appState.marmotIo {
                            if (!admitted && !change.stillOwned()) throw CancellationException("Viewer owner replaced")
                            updateGroupAvatarUrl(account, capturedGroupId, null, null, null)
                        }
                    }
                } catch (declined: GroupChangeDeclinedException) {
                    // Waiting after the new image landed must say so, not end quietly behind the old URL avatar.
                    if (!change.stillOwned()) throw declined
                    throw declined.refusal
                }
                pendingLegacyAvatarClearAfterImageMutationKey = null
            }
            true
        }

    /** Publish the current native group image/roster through the normal conversation and chat-list state path. */
    private suspend fun refreshViewerGroupImageDetails(
        account: String,
        groupId: String,
        change: ScopedGroupImageMutation<ImageUploadDraft?>,
    ): Boolean {
        if (!change.isActive()) return false
        val details = appState.marmotIo { groupDetails(account, groupId) }
        val applicable = change.isActive() && details.group.groupIdHex.equals(groupId, ignoreCase = true)
        if (applicable) {
            check(applyMutationDetails(account, details)) { "Authoritative group image projection unavailable" }
        }
        return applicable
    }

    /**
     * Native command entry rechecks viewer ownership after dispatcher/lock suspension, except on the retry of a change
     * the user continued past, which must reach MDK to spend its acknowledgement.
     */
    private suspend fun commitGroupImageBytes(
        account: String,
        groupId: String,
        draft: ImageUploadDraft?,
        change: ScopedGroupImageMutation<ImageUploadDraft?>,
    ) {
        appState.runGroupChange(
            account,
            groupId,
            stillWanted = { change.stillOwned() },
            actionLabel =
                if (draft == null) {
                    R.string.remove_photo
                } else {
                    R.string.change_photo
                },
        ) {
            // A retry the user continued past must reach MDK, or its acknowledgement outlives it.
            val admitted = isAdmittedGroupChange()
            appState.marmotIo {
                if (!admitted && !change.stillOwned()) throw CancellationException("Viewer owner replaced")
                if (draft != null) {
                    updateGroupImage(account, groupId, draft.plaintext, draft.mediaType)
                } else {
                    clearGroupImage(account, groupId)
                }
            }
        }
    }

    private fun presentGroupImageMutationFailure(
        throwable: Throwable,
        requestedMutationKey: String,
        attemptedLegacyClear: Boolean,
    ) {
        val failure =
            classifyGroupImageMutationFailure(
                requestedMutationKey = requestedMutationKey,
                pendingLegacyClearMutationKey = pendingLegacyAvatarClearAfterImageMutationKey,
                attemptedLegacyClear = attemptedLegacyClear,
            )
        val title =
            when (failure) {
                GroupImageMutationFailure.UploadCleanup -> R.string.toast_group_image_uploaded_cleanup_failed
                else -> R.string.toast_couldnt_update_group
            }
        recordMutationFailure(title, "GROUP_IMAGE_UPDATE", throwable)
    }

    private fun canAdministerMembersFromAuthoritativeRoster(): Boolean =
        memberRosterState == GroupRosterLoadState.READY &&
            !isDm &&
            isSelfMember &&
            isSelfAdmin &&
            !group.pendingConfirmation &&
            !group.disbanding &&
            !group.disbanded

    private fun authoritativeAdministrationTarget(targetMemberIdHex: String): AppGroupMemberRecordFfi? {
        if (!canAdministerMembersFromAuthoritativeRoster() || targetMemberIdHex.isBlank()) return null
        return members.singleOrNull { member ->
            member.memberIdHex.equals(targetMemberIdHex, ignoreCase = true)
        }
    }

    private fun presentRosterChanged(
        @StringRes title: Int,
    ) {
        lastMutationError =
            ErrorPresentation(
                message = AppText.Plain(copy.groupRosterChanged),
                report = "White Noise error report\noperation=GROUP_ROSTER_UPDATE\nerror=STALE_STATE",
            )
        appState.present(title, R.string.toast_group_roster_changed)
    }

    private suspend fun resolveCanonicalInviteRefs(
        memberRefs: List<String>,
        diagnostic: DmCreationAttempt? = null,
    ): List<String>? =
        try {
            canonicalGroupInviteRefs(memberRefs, appState::resolveAccountIdHex)
        } catch (throwable: Throwable) {
            diagnostic?.failed(DmCreationPhase.RECIPIENTS, throwable)
            throwable.rethrowIfCancellation()
            recordMutationFailure(R.string.toast_couldnt_add_members, "GROUP_INVITE_RESOLVE", throwable)
            null
        }

    private fun presentAdminCommitOutcome(
        outcome: GroupAdministrationCommitOutcome,
        adminAdded: Boolean,
    ): Boolean =
        when (outcome) {
            GroupAdministrationCommitOutcome.COMMITTED -> {
                presentConversationTransient(
                    if (adminAdded) R.string.toast_admin_added else R.string.toast_admin_removed,
                )
                true
            }
            GroupAdministrationCommitOutcome.ROSTER_CHANGED -> {
                presentRosterChanged(R.string.toast_couldnt_update_admin)
                false
            }
            GroupAdministrationCommitOutcome.KEEP_ONE_ADMIN -> {
                appState.present(
                    R.string.toast_keep_one_admin,
                    R.string.toast_promote_before_removing_admin,
                )
                false
            }
            GroupAdministrationCommitOutcome.NO_CHANGE -> false
        }

    /**
     * Invites the captured recipients and their initial admin subset in one native membership commit.
     * [initialAdminRefs] must be a subset of [memberRefs]; its default preserves the older all-admin
     * [addAsAdmin] option while ordinary invitations default to members. Authority is rechecked inside
     * the group commit lock. An uncertain reply may be confirmed by a fresh authoritative read, but
     * never triggers another invitation or a separate promotion commit.
     */
    @Suppress("LongMethod") // One captured role selection, native commit and authoritative reconciliation.
    suspend fun inviteMembers(
        memberRefs: List<String>,
        addAsAdmin: Boolean = false,
        initialAdminRefs: List<String> = if (addAsAdmin) memberRefs else emptyList(),
    ): Boolean {
        val diagnostic = DmCreationInteraction(operation = CreationOperation.GROUP_INVITE).nextAttempt()
        return diagnostic.traceInvitation {
            diagnostic.record(DmCreationPhase.ROSTER, DmCreationOutcome.START)
            withMutationLockResult(false) {
                lastMutationError = null
                if (!canAdministerMembersFromAuthoritativeRoster()) {
                    diagnostic.record(DmCreationPhase.ROSTER, DmCreationOutcome.FAILURE, DmCreationFailure.UNKNOWN)
                    return@withMutationLockResult false
                }
                diagnostic.record(DmCreationPhase.ROSTER, DmCreationOutcome.SUCCESS)
                val account = conversationAccountRef ?: return@withMutationLockResult false
                diagnostic.record(DmCreationPhase.RECIPIENTS, DmCreationOutcome.START)
                val refs =
                    resolveCanonicalInviteRefs(memberRefs, diagnostic) ?: return@withMutationLockResult false
                if (refs.isEmpty()) {
                    diagnostic.record(DmCreationPhase.RECIPIENTS, DmCreationOutcome.SUCCESS)
                    return@withMutationLockResult false
                }
                optimisticGroupRosterMutation.track(OptimisticGroupRosterMutation.Invite(refs)) {
                    val adminTargets =
                        resolveCanonicalInviteRefs(initialAdminRefs, diagnostic) ?: return@track false
                    diagnostic.record(DmCreationPhase.RECIPIENTS, DmCreationOutcome.SUCCESS)
                    val activityId = pendingMembershipActivity?.id
                    var nativeInvoked = false
                    var nativeAccepted = false
                    var canonicalAdmins = emptyList<String>()
                    val runtimeGeneration = appState.runtimeGeneration
                    val absentBefore =
                        refs.none { ref -> members.any { it.memberIdHex.equals(ref, ignoreCase = true) } }
                    try {
                        canonicalAdmins = adminTargets
                        check(adminTargets.all { admin -> refs.any { it.equals(admin, ignoreCase = true) } }) {
                            "Initial admin selection contains an unselected recipient"
                        }
                        val outcome =
                            appState.withGroupCommitLock(account, group.groupIdHex) {
                                if (!canAdministerMembersFromAuthoritativeRoster()) {
                                    return@withGroupCommitLock GroupAdministrationCommitOutcome.ROSTER_CHANGED
                                }
                                val authorityEpoch = mutationPresentation.groupAuthorityEpoch
                                diagnostic.record(DmCreationPhase.CREATE, DmCreationOutcome.START)
                                nativeInvoked = true
                                val inviteResult =
                                    initialAdminInviteWriter(account, group.groupIdHex, refs, adminTargets)
                                nativeAccepted = true
                                diagnostic.record(DmCreationPhase.CREATE, DmCreationOutcome.SUCCESS)
                                diagnostic.record(DmCreationPhase.PROJECTION, DmCreationOutcome.START)
                                publishInitialAdminInviteResult(
                                    account,
                                    inviteResult,
                                    authorityEpoch,
                                    runtimeGeneration,
                                )
                                diagnostic.projectionFinished(
                                    appState.runtimeGeneration == runtimeGeneration &&
                                        !controllerCleared &&
                                        !isAccountTeardownRequested(),
                                )
                                optimisticGroupRosterMutation.settleMembershipActivity(activityId)
                                GroupAdministrationCommitOutcome.COMMITTED
                            }
                        if (outcome == GroupAdministrationCommitOutcome.ROSTER_CHANGED) {
                            diagnostic.record(
                                DmCreationPhase.ROSTER,
                                DmCreationOutcome.FAILURE,
                                DmCreationFailure.UNKNOWN,
                            )
                            presentRosterChanged(R.string.toast_couldnt_add_members)
                            return@track false
                        }
                        presentConversationTransient(R.string.toast_invite_sent)
                        true
                    } catch (throwable: Throwable) {
                        diagnostic.invitationFailed(throwable, nativeInvoked, nativeAccepted)
                        throwable.rethrowIfCancellation()
                        val reconcilable = canReconcileInviteReply(nativeInvoked, absentBefore, throwable)
                        if (reconcilable) {
                            diagnostic.record(DmCreationPhase.RECONCILIATION, DmCreationOutcome.START)
                            val reconciled =
                                reconcileInitialAdminInvite(
                                    refs,
                                    canonicalAdmins,
                                    absentBefore,
                                    nativeInvoked,
                                    throwable,
                                )
                            if (reconciled) {
                                diagnostic.record(DmCreationPhase.RECONCILIATION, DmCreationOutcome.SUCCESS)
                                presentConversationTransient(R.string.toast_invite_sent)
                                return@track true
                            }
                            diagnostic.record(
                                DmCreationPhase.RECONCILIATION,
                                DmCreationOutcome.FAILURE,
                                DmCreationFailure.UNKNOWN,
                            )
                        }
                        presentInviteFailure(throwable, refs)
                    }
                }
            }
        }
    }

    /** Keep error copy independent of native invitation and reconciliation ownership. */
    private fun presentInviteFailure(
        throwable: Throwable,
        refs: List<String>,
    ): Boolean {
        val rawMessage = throwable.message.orEmpty()
        return if (isDuplicateSignatureKeyError(rawMessage)) {
            // MLS rejected the add commit because the proposed member
            // already holds a seat (or their signing key collides with
            // an existing member's). The UI pre-checks membership, but a
            // race or key collision can still land here — surface plain
            // language with the resolved name instead of the raw
            // CreateCommitError(ProposalValidationError(...)) enum (#899).
            val name = duplicateSignatureKeyDisplayName(refs, appState::displayName)
            val friendly = copy.couldntAddMemberDuplicate(name)
            recordMutationFailure(
                R.string.toast_couldnt_add_members,
                "GROUP_INVITE_DUPLICATE_MEMBER",
                throwable,
                AppText.Plain(friendly),
            )
            false
        } else {
            recordMutationFailure(
                R.string.toast_couldnt_add_members,
                "GROUP_INVITE_MEMBER",
                throwable,
                inviteFailureDetail(
                    throwable,
                    appState::displayName,
                    if (throwable is MarmotKitException.InvalidKeyPackageEvent) {
                        refs.singleOrNull()?.let(appState::displayName)
                    } else {
                        null
                    },
                ),
            )
            false
        }
    }

    /** A newer details read requires a fresh native snapshot after this commit, not its older result. */
    private suspend fun publishInitialAdminInviteResult(
        account: String,
        result: dev.ipf.marmotkit.GroupMutationResultFfi,
        authorityEpoch: Long,
        runtimeGeneration: Int,
    ) {
        if (appState.runtimeGeneration != runtimeGeneration || controllerCleared || isAccountTeardownRequested()) return
        if (mutationPresentation.groupAuthorityEpoch == authorityEpoch) {
            applyManagementSnapshot(account, result.details, result.managementState)
        } else {
            refreshManagementState()
        }
    }

    /** These typed key, routing, lookup and admission rejections happen before the native membership commit. */
    private fun inviteReplyNeedsReconciliation(throwable: Throwable): Boolean =
        when (throwable) {
            is MarmotKitException.MissingKeyPackage,
            is MarmotKitException.MissingMemberInboxRoute,
            is MarmotKitException.InvalidKeyPackageEvent,
            is MarmotKitException.DirectoryLookupIncomplete,
            is MarmotKitException.GroupCatchingUp,
            is MarmotKitException.GroupCommitUndelivered,
            -> false
            else -> true
        }

    /** Only an uncertain first invitation for absent recipients may reconcile by reading membership. */
    private fun canReconcileInviteReply(
        nativeInvoked: Boolean,
        absentBefore: Boolean,
        failure: Throwable,
    ): Boolean = nativeInvoked && absentBefore && inviteReplyNeedsReconciliation(failure)

    /** Read actual membership after an uncertain reply, without issuing a second invitation. */
    private suspend fun reconcileInitialAdminInvite(
        refs: List<String>,
        admins: List<String>,
        absentBefore: Boolean,
        nativeInvoked: Boolean,
        failure: Throwable,
    ): Boolean {
        val eligible = canReconcileInviteReply(nativeInvoked, absentBefore, failure)
        if (!eligible) return false
        val beforeRead = mutationPresentation.groupAuthorityEpoch
        val applied = refreshManagementState()
        val observedMembers =
            membersVerified && refs.all { ref -> members.any { it.memberIdHex.equals(ref, ignoreCase = true) } }
        val observedAdmins = admins.all { ref -> group.admins.any { it.equals(ref, ignoreCase = true) } }
        return applied && mutationPresentation.groupAuthorityEpoch != beforeRead && observedMembers && observedAdmins
    }

    /** Native capabilities stay unknown when local terminal reconciliation changes their returned group. */
    private fun applyManagementSnapshot(
        account: String,
        details: GroupDetailsFfi,
        management: GroupManagementStateFfi,
    ): Boolean =
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            val applied = applyMutationDetails(account, details, freshLeaveReadback = true)
            if (applied) {
                managementState = management.takeIf { group == details.group }
            }
            applied && managementState != null
        }

    /** Removes [member] from the group, asking first if MDK holds the change back. True once they are gone. */
    suspend fun removeMember(
        member: AppGroupMemberRecordFfi,
        refreshAuthorityBeforeCommit: Boolean = false,
    ): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            val runtimeAtAdmission = appState.runtimeGeneration

            fun ownsRemovalPresentation(): Boolean =
                !refreshAuthorityBeforeCommit ||
                    (appState.activeAccountRef == account && appState.runtimeGeneration == runtimeAtAdmission)
            // remove_members signs a roster update for a Nostr pubkey, so use the
            // stable member id. memberRef may be a local account label.
            val target = member.memberIdHex
            optimisticGroupRosterMutation.track(OptimisticGroupRosterMutation.Remove(target)) {
                try {
                    val outcome =
                        appState.withGroupCommitLock(account, group.groupIdHex) {
                            if (!refreshRemovalAuthority(refreshAuthorityBeforeCommit, ::ownsRemovalPresentation)) {
                                return@withGroupCommitLock GroupAdministrationCommitOutcome.ROSTER_CHANGED
                            }
                            authoritativeAdministrationTarget(target)
                                ?: return@withGroupCommitLock GroupAdministrationCommitOutcome.ROSTER_CHANGED
                            val result =
                                appState.runGroupChange(
                                    account,
                                    group.groupIdHex,
                                    actionLabel = R.string.remove_member,
                                    affectedMemberIds = listOf(target),
                                ) {
                                    appState.marmotIo(MarmotTraceSection.REMOVE_MEMBERS) {
                                        removeMembersDetailed(account, group.groupIdHex, listOf(target))
                                    }
                                }
                            applyMutationDetails(account, result.details)
                            GroupAdministrationCommitOutcome.COMMITTED
                        }
                    if (outcome == GroupAdministrationCommitOutcome.ROSTER_CHANGED) {
                        if (ownsRemovalPresentation()) presentRosterChanged(R.string.toast_couldnt_remove_member)
                        return@track false
                    }
                    if (ownsRemovalPresentation()) presentConversationTransient(R.string.toast_member_removed)
                    true
                } catch (throwable: Throwable) {
                    throwable.rethrowIfCancellation()
                    // The commit may have persisted before a follow-on publish
                    // failed. One authoritative details read is enough here; an MLS
                    // replay probe would duplicate bridge work and does not affect
                    // whether this specific target remains in the local roster.
                    refreshMembers()
                    if (members.none { it.memberIdHex.equals(target, ignoreCase = true) }) {
                        if (ownsRemovalPresentation()) presentConversationTransient(R.string.toast_member_removed)
                        true
                    } else {
                        if (ownsRemovalPresentation()) {
                            recordMutationFailure(
                                R.string.toast_couldnt_remove_member,
                                "GROUP_REMOVE_MEMBER",
                                throwable,
                            )
                        }
                        false
                    }
                }
            }
        }

    /** Called inside the existing commit lock; a failed read never reuses the previous permission frontier. */
    private suspend fun refreshRemovalAuthority(
        refresh: Boolean,
        ownsPresentation: () -> Boolean,
    ): Boolean {
        if (!refresh) return true
        val epoch = mutationPresentation.groupAuthorityEpoch
        val applied = refreshManagementState()
        return applied && mutationPresentation.groupAuthorityEpoch != epoch && ownsPresentation()
    }

    /** Promotes or demotes [member], asking first if MDK holds the change back. True once committed. */
    suspend fun setMemberAdmin(
        member: AppGroupMemberRecordFfi,
        admin: Boolean,
    ): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            // promote_admin / demote_admin sign the new admin list onto the MLS
            // group, so they require a Nostr pubkey hex — not a local-account
            // label. memberRef can return either; memberIdHex is always the hex.
            val target = member.memberIdHex
            if (!admin && isAdmin(member) && GroupProjector.revokeWouldDepleteAdmins(group, member, memberCount)) {
                appState.present(R.string.toast_keep_one_admin, R.string.toast_promote_before_removing_admin)
                return@withMutationLockResult false
            }
            optimisticGroupRosterMutation.track(OptimisticGroupRosterMutation.SetAdmin(target, admin)) {
                runCatchingCancellable {
                    val outcome =
                        appState.withGroupCommitLock(account, group.groupIdHex) {
                            val currentMember =
                                authoritativeAdministrationTarget(target)
                                    ?: return@withGroupCommitLock GroupAdministrationCommitOutcome.ROSTER_CHANGED
                            if (GroupProjector.isAdmin(group, currentMember) == admin) {
                                return@withGroupCommitLock GroupAdministrationCommitOutcome.NO_CHANGE
                            }
                            if (!admin && GroupProjector.revokeWouldDepleteAdmins(group, currentMember, memberCount)) {
                                return@withGroupCommitLock GroupAdministrationCommitOutcome.KEEP_ONE_ADMIN
                            }
                            if (admin) {
                                val result =
                                    appState.runGroupChange(
                                        account,
                                        group.groupIdHex,
                                        actionLabel = R.string.make_admin,
                                        affectedMemberIds = listOf(target),
                                    ) {
                                        appState.marmotIo(MarmotTraceSection.PROMOTE_ADMIN) {
                                            promoteAdminDetailed(account, group.groupIdHex, target)
                                        }
                                    }
                                applyMutationDetails(account, result.details)
                            } else {
                                val result =
                                    appState.runGroupChange(
                                        account,
                                        group.groupIdHex,
                                        actionLabel = R.string.remove_admin,
                                        affectedMemberIds = listOf(target),
                                    ) {
                                        appState.marmotIo(MarmotTraceSection.DEMOTE_ADMIN) {
                                            demoteAdminDetailed(account, group.groupIdHex, target)
                                        }
                                    }
                                applyMutationDetails(account, result.details)
                            }
                            GroupAdministrationCommitOutcome.COMMITTED
                        }
                    presentAdminCommitOutcome(outcome, adminAdded = admin)
                }.onFailure {
                    recordMutationFailure(R.string.toast_couldnt_update_admin, "GROUP_MEMBER_ADMIN_UPDATE", it)
                }.getOrDefault(false)
            }
        }

    /**
     * Set the per-group disappearing-message retention. `0` disables it; any
     * positive value is the NIP-40 expiration the engine applies to outgoing
     * kind:445 events. Optimistically updates the local group so the row
     * reflects the new value without waiting for the group-state subscription.
     */
    suspend fun updateMessageRetention(disappearingMessageSecs: ULong): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            // Stay bound to the conversation's account (like every other mutation
            // here); activeAccountRef could shift if the user switches accounts
            // before this completes, sending the retention change to the wrong store.
            val account = conversationAccountRef ?: return@withMutationLockResult false
            runCatchingCancellable {
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    appState.runGroupChange(
                        account,
                        group.groupIdHex,
                        actionLabel = R.string.disappearing_messages,
                        retentionSeconds = disappearingMessageSecs,
                    ) {
                        appState.marmotIo { updateMessageRetention(account, group.groupIdHex, disappearingMessageSecs) }
                    }
                }
                val previousRetention = group.disappearingMessageSecs
                group = group.copy(disappearingMessageSecs = disappearingMessageSecs)
                // The engine prunes plaintext older than the new window during the
                // call above. Reload the open timeline so the admin who just set
                // the timer sees the pruned state immediately, instead of only
                // after leaving and re-entering the chat. The retention change has
                // already succeeded, so a refresh failure must NOT flip this to a
                // failure toast — log it and fall back to an in-memory re-filter.
                runCatchingCancellable { refreshCurrentTimeline(account) }
                    .onFailure { refreshError ->
                        if (BuildConfig.DEBUG) Log.w("DMConversation", "retention refresh failed", refreshError)
                        publishTimelineFromIndexes()
                    }
                presentConversationTransient(R.string.toast_disappearing_messages_updated)
                true
            }.onFailure {
                recordMutationFailure(R.string.toast_couldnt_update_disappearing, "GROUP_RETENTION_UPDATE", it)
            }.getOrDefault(false)
        }

    /**
     * Engine-authoritative admin/lifecycle capabilities for the details
     * screen: disband eligibility, blockers, and the in-flight request.
     * Refreshed on screen entry and after each disband-family mutation.
     */
    var managementState by mutableStateOf<GroupManagementStateFfi?>(null)
        private set
    private val managementStateLifetime = StalenessGuard()
    private var cancelledLeaveAwaitingRoster by mutableStateOf(false)
    private var observedBlockedLeave by mutableStateOf(initialGroup.leaveBlocked)

    /** A cleared block still needs verification before Android restores membership. */
    internal val blockedLeaveNeedsReadback: Boolean
        get() = cancelledLeaveAwaitingRoster || (observedBlockedLeave && !group.leaveRequestPending)

    /** Keep the departure notice until native membership has been verified. */
    internal val showsBlockedLeave: Boolean
        get() = group.leaveBlocked || blockedLeaveNeedsReadback

    /** MDK owns cancellation eligibility. An unresolved readback can retry without another mutation. */
    internal val canCancelBlockedLeave: Boolean
        get() = blockedLeaveNeedsReadback || (group.leaveBlocked && managementState?.canCancelLeave == true)

    /** Cancel the durable leave for the bound account, then read membership from MDK. */
    internal suspend fun cancelBlockedLeave(): Boolean =
        withMutationLockResult(false) {
            val account = conversationAccountRef ?: return@withMutationLockResult false
            val groupId = group.groupIdHex
            if (!ownsBlockedLeaveAction(account, groupId) || !canCancelBlockedLeave) return@withMutationLockResult false
            lastMutationError = null
            val result = runCatchingCancellable { cancelNativeBlockedLeave(account, groupId) }
            if (!ownsBlockedLeaveAction(account, groupId) || result.getOrNull() == false) {
                return@withMutationLockResult false
            }
            // MDK can durably cancel before its membership projection is updated.
            // Read back even after a native error or an already-cancelled request.
            refreshManagementState()
            if (!ownsBlockedLeaveAction(account, groupId)) return@withMutationLockResult false
            val stayed = isSelfMember && !showsBlockedLeave && !group.leaveRequestPending
            if (!stayed) {
                result.exceptionOrNull()?.let {
                    recordMutationFailure(R.string.toast_couldnt_cancel_leave, "GROUP_CANCEL_LEAVE", it)
                }
            }
            stayed
        }

    /**
     * Why ordinary outbound work is refused right now, or null when it may proceed: a blocked leave, or a group that is
     * disbanding or disbanded, the terminal states [canSendMessages] also refuses. Shared by every send path that does
     * not pass through the composer gate.
     */
    @StringRes
    private fun outboundRefusal(): Int? =
        when {
            showsBlockedLeave -> blockedLeaveHint()
            group.disbanded -> R.string.conversation_disbanded_notice
            group.disbanding -> R.string.conversation_disbanding_notice
            else -> null
        }

    /** Matches the blocked-leave notice: a cancelled or cleared block that only awaits readback asks for Refresh. */
    @StringRes
    private fun blockedLeaveHint(): Int =
        when {
            blockedLeaveNeedsReadback -> R.string.group_leave_refresh_hint
            else -> R.string.group_leave_blocked_hint
        }

    /** Check the action owner again under the native group lock before canceling or retrying readback. */
    private suspend fun cancelNativeBlockedLeave(
        account: String,
        groupId: String,
    ): Boolean =
        appState.withGroupCommitLock(account, groupId) {
            if (!ownsBlockedLeaveAction(account, groupId) || !canCancelBlockedLeave) return@withGroupCommitLock false
            if (!blockedLeaveNeedsReadback) {
                appState.marmotIo { cancelLeave(account, groupId) }
                memberRosterRefreshGeneration.advance()
                managementStateLifetime.advance()
                cancelledLeaveAwaitingRoster = observedBlockedLeave
            }
            true
        }

    /** Reject work from a retired conversation, account or runtime. */
    private fun ownsBlockedLeaveAction(
        account: String,
        groupId: String,
    ): Boolean =
        acceptsConversationActionOwner(account, groupId) &&
            appState.activeAccountRef == account &&
            appState.runtimeGeneration == conversationRuntimeGeneration

    /** Details and capabilities share one native frontier; later group updates supersede this read. */
    suspend fun refreshManagementState(): Boolean {
        val account = conversationAccountRef ?: return false
        var applied = false
        val requestToken = managementStateLifetime.advance()
        val authorityEpoch = mutationPresentation.groupAuthorityEpoch
        val rosterGeneration = memberRosterRefreshGeneration.capture()
        val runtimeGeneration = appState.runtimeGeneration
        val groupId = group.groupIdHex
        val activeAccount = appState.activeAccountRef
        val startingGroup = group
        runCatchingCancellable {
            groupSnapshotReader(account, groupId)
        }.onSuccess { refreshed ->
            managementStateLifetime.runIfCurrent(requestToken) {
                val staleRead =
                    !ownsCurrentMemberRead(rosterGeneration, runtimeGeneration) ||
                        mutationPresentation.groupAuthorityEpoch != authorityEpoch
                val changedOwner = group != startingGroup || appState.activeAccountRef != activeAccount
                val wrongTarget =
                    !refreshed.details.group.groupIdHex
                        .equals(groupId, ignoreCase = true) ||
                        !refreshed.managementState.myAccountIdHex.equals(conversationAccountIdHex, ignoreCase = true) ||
                        !matchesConversation(account, groupId)
                if (staleRead || changedOwner || wrongTarget) return@runIfCurrent
                applied = applyManagementSnapshot(account, refreshed.details, refreshed.managementState)
            }
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w("DMConversation", "management state refresh failed", it)
        }
        return applied
    }

    /** Install and require the lifecycle component in one admin commit. */
    suspend fun enableGroupDisbanding(): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            runCatchingCancellable {
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    val result =
                        appState.runGroupChange(
                            account,
                            group.groupIdHex,
                            actionLabel = R.string.group_disband_enable_action,
                        ) {
                            appState.marmotIo { enableGroupDisbanding(account, group.groupIdHex) }
                        }
                    applyMutationDetails(account, result.details)
                }
                refreshManagementState()
                true
            }.onFailure {
                recordMutationFailure(R.string.toast_couldnt_enable_disbanding, "GROUP_DISBAND_ENABLE", it)
            }.getOrDefault(false)
        }

    /**
     * Durably accept the irreversible disband. Completion converges through
     * normal group-state updates; the request itself survives restarts.
     */
    suspend fun disbandGroup(): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            runCatchingCancellable {
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    appState.runGroupChange(account, group.groupIdHex, actionLabel = R.string.group_disband_action) {
                        appState.marmotIo { disbandGroup(account, group.groupIdHex) }
                    }
                }
                refreshManagementState()
                true
            }.onFailure {
                recordMutationFailure(R.string.toast_couldnt_disband, "GROUP_DISBAND", it)
            }.getOrDefault(false)
        }

    /** Clear a failed disband request so the action can be retried. */
    suspend fun acknowledgeDisbandFailure(): Boolean =
        withMutationLockResult(false) {
            val account = conversationAccountRef ?: return@withMutationLockResult false
            runCatchingCancellable {
                appState.marmotIo { acknowledgeDisbandFailure(account, group.groupIdHex) }
                refreshManagementState()
                true
            }.onFailure {
                if (BuildConfig.DEBUG) Log.w("DMConversation", "acknowledge disband failure failed", it)
            }.getOrDefault(false)
        }

    /** Steps this account down as admin while another admin remains, asking first if MDK holds it back. */
    suspend fun stepDownAsAdmin(): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            val activeAccountIdHex = conversationAccountIdHex ?: return@withMutationLockResult false
            if (!GroupProjector.isAdminRef(group, activeAccountIdHex)) return@withMutationLockResult false
            if (group.admins.distinctBy { it.lowercase() }.size <= 1) {
                appState.present(R.string.toast_keep_one_admin, R.string.toast_promote_before_removing_admin)
                return@withMutationLockResult false
            }
            runCatchingCancellable {
                val outcome =
                    appState.withGroupCommitLock(account, group.groupIdHex) {
                        if (!canAdministerMembersFromAuthoritativeRoster()) {
                            return@withGroupCommitLock GroupAdministrationCommitOutcome.ROSTER_CHANGED
                        }
                        if (group.admins.distinctBy { it.lowercase() }.size <= 1) {
                            return@withGroupCommitLock GroupAdministrationCommitOutcome.KEEP_ONE_ADMIN
                        }
                        val result =
                            appState.runGroupChange(
                                account,
                                group.groupIdHex,
                                actionLabel = R.string.step_down_as_admin,
                            ) {
                                appState.marmotIo(MarmotTraceSection.SELF_DEMOTE_ADMIN) {
                                    selfDemoteAdminDetailed(account, group.groupIdHex)
                                }
                            }
                        applyMutationDetails(account, result.details)
                        GroupAdministrationCommitOutcome.COMMITTED
                    }
                presentAdminCommitOutcome(outcome, adminAdded = false)
            }.onFailure {
                recordMutationFailure(R.string.toast_couldnt_update_admin, "GROUP_SELF_DEMOTE", it)
            }.getOrDefault(false)
        }

    /**
     * Transfer admin to [member]: grant them admin, then step down ourselves.
     *
     * Ordering is enforced — grant first, self-demote second — so the group is
     * never momentarily left with no admin (which the engine would reject and
     * which would strand the sole-admin transfer the Leave-group flow depends
     * on, issue #417). If the grant succeeds but the self-demote fails, the
     * target keeps the admin rights they were just given and we surface a
     * partial-failure toast naming the mid-state rather than the generic
     * "couldn't update admin" copy. The caller (active account) must be an
     * admin; the target must not already be one.
     */
    suspend fun transferAdmin(member: AppGroupMemberRecordFfi): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            val activeAccountIdHex = conversationAccountIdHex
            // promote_admin / self_demote_admin sign the new admin list onto
            // the MLS group, so the grant target needs a Nostr pubkey hex, not
            // a local-account label. memberIdHex is always the hex.
            val target = member.memberIdHex
            if (!GroupProjector.canTransferAdminTo(group, member, activeAccountIdHex)) {
                // Already an admin, the active account isn't an admin, or the
                // target is the active account itself. Nothing to transfer.
                appState.present(R.string.toast_couldnt_update_admin, R.string.toast_cant_transfer_admin, copyable = true)
                return@withMutationLockResult false
            }
            // Tracks whether the grant landed before the self-demote attempt so
            // a self-demote failure reports the partial state honestly.
            var grantedBeforeDemote = false
            runCatchingCancellable {
                val transferCommitted =
                    appState.withGroupCommitLock(account, group.groupIdHex) {
                        val currentMember =
                            authoritativeAdministrationTarget(target)
                                ?: return@withGroupCommitLock false
                        if (!GroupProjector.canTransferAdminTo(group, currentMember, activeAccountIdHex)) {
                            return@withGroupCommitLock false
                        }
                        val promoteResult =
                            appState.runGroupChange(
                                account,
                                group.groupIdHex,
                                actionLabel = R.string.transfer_admin,
                                affectedMemberIds = listOf(target),
                            ) {
                                appState.marmotIo(MarmotTraceSection.PROMOTE_ADMIN) {
                                    promoteAdminDetailed(account, group.groupIdHex, target)
                                }
                            }
                        grantedBeforeDemote = true
                        applyMutationDetails(account, promoteResult.details)
                        // The grant has already landed on the MLS group. If the scope is
                        // cancelled now, skipping the demote would strand both accounts as
                        // admin without telling the caller (runCatchingCancellable propagates
                        // cancellation past the partial-state branch). Run it to completion.
                        completeAdminTransfer(account, target)
                        true
                    }
                if (!transferCommitted) {
                    appState.present(R.string.toast_couldnt_update_admin, R.string.toast_cant_transfer_admin, copyable = true)
                    return@runCatchingCancellable false
                }
                presentConversationTransient(R.string.toast_admin_transferred)
                true
            }.onFailure {
                if (grantedBeforeDemote) {
                    // Target is now an admin but we couldn't step down. Tell the user
                    // so they can retry the step-down (or revoke the grant).
                    recordMutationFailure(
                        R.string.toast_granted_but_couldnt_step_down,
                        "GROUP_ADMIN_TRANSFER_STEP_DOWN",
                        it,
                    )
                } else {
                    recordMutationFailure(R.string.toast_couldnt_update_admin, "GROUP_ADMIN_TRANSFER", it)
                }
            }.getOrDefault(false)
        }

    /** Finish the already-granted transfer even if its caller is cancelled. */
    private suspend fun completeAdminTransfer(
        account: String,
        target: String,
    ) {
        withContext(NonCancellable) {
            val demoteResult =
                try {
                    appState.runGroupChange(
                        account,
                        group.groupIdHex,
                        actionLabel = R.string.transfer_admin,
                        affectedMemberIds = listOf(target),
                    ) {
                        appState.marmotIo(MarmotTraceSection.SELF_DEMOTE_ADMIN) {
                            selfDemoteAdminDetailed(account, group.groupIdHex)
                        }
                    }
                } catch (declined: GroupChangeDeclinedException) {
                    // Waiting leaves both admins: report the partial transfer, not a quiet cancel.
                    throw declined.refusal
                }
            applyMutationDetails(account, demoteResult.details)
        }
    }

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

    suspend fun exportConversationTranscriptFile(cacheDir: java.io.File): java.io.File? {
        val account = conversationAccountRef ?: return null
        // One timestamp for the whole export so the JSON `exported_at` and the
        // file name stamp match instead of drifting across two now() reads.
        val exportedAt = java.time.Instant.now()
        return runCatchingCancellable {
            val messages =
                withContext(Dispatchers.Default) {
                    ConversationTranscriptExport.fetchAllMessages(
                        timelineReader =
                            object : ConversationTranscriptTimelineReader {
                                override suspend fun timelineMessages(
                                    accountRef: String,
                                    query: TimelineMessageQueryFfi,
                                ): TimelinePageFfi =
                                    appState.marmotIo(MarmotTraceSection.TIMELINE_READ) {
                                        timelineMessages(accountRef, query)
                                    }
                            },
                        accountRef = account,
                        groupIdHex = group.groupIdHex,
                    )
                }
            val data =
                withContext(Dispatchers.Default) {
                    val document = ConversationTranscriptExport.makeDocument(group, messages, exportedAt)
                    ConversationTranscriptExport.encodeJson(document)
                }
            withContext(Dispatchers.IO) {
                ConversationTranscriptExport.writeTemporaryFile(
                    cacheDir = cacheDir,
                    data = data,
                    groupIdHex = group.groupIdHex,
                    exportedAt = exportedAt,
                )
            }
        }.onFailure {
            appState.presentFailure(R.string.toast_couldnt_export_transcript, "CONVERSATION_TRANSCRIPT_EXPORT", it)
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

    /** Read a raw message record from the in-memory window or recent store tail. */
    private suspend fun lookupMessageRecord(messageIdHex: String): AppMessageRecordFfi? {
        messageById[messageIdHex]?.let { return it }
        val account = conversationAccountRef ?: return null
        return runCatchingCancellable {
            withContext(Dispatchers.IO) {
                appState.marmotIo { messages(account, group.groupIdHex, 120u, null) }
            }
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w("DMConversation", "lookup message failed", it)
        }.getOrNull()
            ?.firstOrNull { it.messageIdHex.equals(messageIdHex, ignoreCase = true) }
    }

    /**
     * Pages the window older, keeping the reader's oldest visible row as the window anchor.
     *
     * True only when new rows arrived, so callers that page in a loop still stop on no progress.
     */
    private suspend fun loadOlderPage(
        anchorMessageIdHex: String? = null,
        origin: PagingOrigin = PagingOrigin.EXPLICIT,
    ): Boolean = loadOlderPageInternal(anchorMessageIdHex, origin) == ConversationPageLoad.ADVANCED

    /** Pages the window newer for [origin]; true only when new rows arrived. */
    private suspend fun loadNewerPage(origin: PagingOrigin = PagingOrigin.EXPLICIT): Boolean {
        val load = loadNewerPageInternal(origin)
        return load == ConversationPageLoad.ADVANCED
    }

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

    /** Replaces the timeline only when no newer page or live update superseded the read. */
    private suspend fun refreshCurrentTimeline(
        account: String,
        pageLoader: (suspend () -> TimelinePageFfi)? = null,
    ): List<String> {
        val refreshGeneration = timelineWindowGeneration.advance()
        val page =
            pageLoader?.invoke()
                ?: appState.marmotIo(MarmotTraceSection.TIMELINE_READ) {
                    timelineMessages(
                        account,
                        TimelineMessageQueryFfi.page(
                            groupIdHex = group.groupIdHex,
                            search = null,
                            before = null,
                            beforeMessageId = null,
                            after = null,
                            afterMessageId = null,
                            limit = ConversationTimelinePageLimit,
                        ),
                    )
                }
        if (!timelineWindowGeneration.isCurrent(refreshGeneration)) {
            return emptyList()
        }
        hasLoadedOlderPages = false
        protectedTimelineMessageIds.clear()
        return applyTimelinePage(warmLaterPageEventCards(page), replaceWindow = true, updatePagination = true)
    }

    internal suspend fun testRefreshCurrentTimeline(
        account: String,
        pageLoader: suspend () -> TimelinePageFfi,
    ): List<String> = refreshCurrentTimeline(account, pageLoader)

    internal fun testApplyLiveTimelineChangesAndRegisterStreams(changes: List<TimelineMessageChangeFfi>) {
        applyTimelineChanges(changes).forEach(activeStreamIds::add)
    }

    internal fun testActiveStreamIds(): Set<String> = activeStreamIds.toSet()

    /** Returns loaded ordinary-message ids eligible for the foreground expiry sweep. */
    internal fun testSweepExpiryIds(): List<String> =
        sweepExpiryMessages()
            .filter { shouldApplyLocalDisappearingExpiry(it.record) }
            .map { it.record.messageIdHex }

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
     * Retires a stream whose final record arrived in this window.
     *
     * Marking it removed stops a late `AgentStreamUpdateFfi.Finished` event recreating the
     * optimistic preview as a duplicate. See #25.
     */
    private fun retireFinishedStream(actionRecord: AppMessageRecordFfi) {
        if (!MessageProjector.isStreamFinal(actionRecord)) return
        MessageProjector.streamId(actionRecord)?.let { streamId ->
            activeStreamIds.remove(streamId)
            removedStreamIds.add(streamId)
            optimisticMessages.remove("stream:$streamId")
        }
    }

    /** Drops indexes owned by the previous authoritative bounded window. */
    private fun trimStateForWindowReplacement() {
        timelineRecords.clear()
        timelineItemsById.clear()
        timelineOrder.clear()
        authoritativeTimelineOrderByMessageId.clear()
        durableStreamDisplayParentByMessageId.clear()
        projectedMessageIds.clear()
        // Drop stale projected records so messageById doesn't grow unbounded
        // as older pages are loaded; keep in-flight optimistic records so a
        // pending send still reconciles across a window replacement. See #68.
        val optimisticIds = optimisticMessages.values.mapTo(mutableSetOf()) { it.record.messageIdHex }
        messageById.keys.retainAll(optimisticIds)
        // Same unbounded-growth trim for the optimistic position/timestamp
        // overrides: the reconcile path below re-adds them for any optimistic
        // message that reconciles into this page, so scrolled-out entries
        // don't accumulate for the controller's lifetime.
        localTimelineOrderOverrides.keys.retainAll(optimisticIds)
        localTimelineTimestampOverrides.keys.retainAll(optimisticIds)
        preservedTimelinePositionOverrideIds.retainAll(localTimelineTimestampOverrides.keys)
        optimisticSendPositionPreserves.retainAll(localTimelineTimestampOverrides.keys)
        durableStreamPositionOverrideIds.retainAll(localTimelineTimestampOverrides.keys)
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
    ): List<String> {
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
        if (!timelineWindowGeneration.isCurrent(preparationGeneration)) return emptyList()
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
        val streamIds = mutableListOf<String>()
        val appliedRecords = ArrayList<TimelineMessageRecordFfi>(prepared.rows.size)
        prepared.rows.forEach { row ->
            val record = row.record
            pendingTimelineRemovedMessageIds = pendingTimelineRemovedMessageIds - record.messageIdHex
            appliedRecords.add(record)
            val reconcilesOptimistic =
                row.reconcilesOptimistic || (reconcileNewExtendedRecords && record.messageIdHex !in timelineRecords)
            val actionRecord =
                if (record.messageIdHex in commitPlan.projectIds) {
                    upsertProjectedRecord(
                        record,
                        reconcileOptimistic = reconcilesOptimistic,
                        allowDelayedProjection = reconcilesOptimistic,
                        preparedAction = row.actionRecord,
                    )
                } else {
                    row.actionRecord
                }
            actionRecord.takeIf(MessageProjector::isStreamStart)?.let(MessageProjector::streamId)?.let(streamIds::add)
            if (record.deleted) {
                deletedMessageIds = deletedMessageIds - record.messageIdHex
            }
            retireFinishedStream(actionRecord)
        }
        appState.requestProfiles(prepared.profileIds)
        applyDurableStreamPositions(durableStreamDisplayPositions(timelineRecords.values.toList()))
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
        pruneConfirmedOptimisticReactions()
        pruneMessageOverlaysToWindow()
        pruneRetainedTimelineRows(prepared)
        val reactionReferenceChanges = installWindowFrame(installed?.frame)
        // A replacement rebuilt every row, so every tally is stale. An extended window only changed the
        // rows it added, altered or dropped, plus rows whose sidecar reactions moved under them (#2990).
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
        val streamIdsToLaunch = windowStreamIdsToLaunch(streamIds, removedStreamIds::contains)
        onWindowApplyMeasured(
            preparation.performanceSample(prepared, commitPlan.projectIds.size, commitStartedAt, windowApplyNanoTime()),
        )
        onCommitted?.invoke()
        return streamIdsToLaunch
    }

    /**
     * Warms existing local profile presentation state and, for a page that names
     * them, cached event cards. MainShell keeps a prepared chat-list tap on its
     * source page until this initial warm completes, preventing sender metadata
     * from remeasuring bubbles during the route transition. A page with cards is
     * also held unpublished until then, so no open path shows a card unwarmed.
     */
    private fun scheduleProfilePresentationWarm(
        records: List<TimelineMessageRecordFfi>,
        hydratedRecords: Deferred<List<TimelineMessageRecordFfi>>?,
        markInitialPresentationReady: Boolean,
    ) {
        if (!markInitialPresentationReady) return
        initialPresentationWarmCoordinator.prepare(
            InitialPresentationWarmWork(
                senders = initialPresentationProfileSenders(records),
                eventCardRecords = records.filter(::mayRenderNostrEventCard),
                hydratedRecords = hydratedRecords,
            ),
        )
    }

    /**
     * Marks an applied page published, unless it is a first page that names event cards (#2238). That page, and any
     * page replacing it before readiness, stays held until its cached cards are read, so no row composes unwarmed.
     */
    private fun markPagePublishedUnlessHeldForEventCards(
        preparingInitialPresentation: Boolean,
        records: List<TimelineMessageRecordFfi>,
    ) {
        isHoldingInitialPageForEventCards =
            preparingInitialPresentation &&
            (isHoldingInitialPageForEventCards || records.any(::mayRenderNostrEventCard))
        if (isHoldingInitialPageForEventCards) return
        hasPublishedAuthoritativeTimeline = true
        initialTimelineSeedActive = false
    }

    /**
     * Suspends until no page is held unpublished for its event cards (#2238). Only a page committed before the first
     * presentation is ready is held, until readiness, which the card budget bounds, so this returns at once otherwise.
     */
    internal suspend fun awaitEventCardHoldReleased() {
        if (isHoldingInitialPageForEventCards) initialPresentationWarmCoordinator.awaitReady()
    }

    /** Times the page's publication now, or, for a page held for its event cards, when the hold releases it. */
    private fun recordPagePublicationUnlessHeld() {
        if (!isHoldingInitialPageForEventCards) windowPresentationTiming.timelinePublished()
    }

    /**
     * Publishes the first page held for its event cards, together with the flags that say it is published. Called
     * once the card warm settled or its budget ran out, so a held row composes with its snapshot in place, or, past
     * the budget, as an ordinary unwarmed card.
     */
    private fun releaseInitialPageEventCardHold() {
        if (!isHoldingInitialPageForEventCards) return
        isHoldingInitialPageForEventCards = false
        hasPublishedAuthoritativeTimeline = true
        initialTimelineSeedActive = false
        publishTimelineFromIndexes()
        windowPresentationTiming.timelinePublished()
    }

    /** Reads cards through the conversation-owned presentation pipeline before committing later pages. */
    internal suspend fun warmLaterPageEventCards(
        page: TimelinePageFfi,
        anchorMessageId: String? = null,
    ): TimelinePageFfi = eventCardPresentation.warmLaterPageEventCards(page, anchorMessageId)

    /**
     * Publishes [records], then hydrates their Markdown, and returns that hydration's parsed records when one runs.
     * With [warmEventCards], cards are read from MDK's cache before the hydrated rows apply. Otherwise the caller's
     * initial warm reads them from the returned records, so the same messages are never parsed twice. The returned
     * records complete only after they applied, so a first page held for its cards is released with them in place.
     */
    private fun publishTimelinePageBeforeMarkdownHydration(
        records: List<TimelineMessageRecordFfi>,
        warmEventCards: Boolean,
    ): Deferred<List<TimelineMessageRecordFfi>>? {
        val scope = conversationScope ?: return null.also { publishTimelineFromIndexes() }
        val hydratedRecords = CompletableDeferred<List<TimelineMessageRecordFfi>>()
        val hydration =
            publishTimelineBeforeMarkdownHydration(
                scope = scope,
                records = records,
                publish = ::publishTimelineFromIndexes,
                // Cards appear once their message's Markdown does, so their cached state is read before it applies.
                hydrate = { pending ->
                    hydrateTimelineMarkdown(pending).also { hydrated ->
                        if (warmEventCards) {
                            eventCardPresentation.warmEventCardSnapshots(hydrated.filter(::mayRenderNostrEventCard))
                        }
                    }
                },
                applyHydrated = { hydrated ->
                    applyHydratedTimelineMarkdown(hydrated)
                    hydratedRecords.complete(hydrated)
                },
            )
        // A cancelled or failed hydration still releases the warm waiting on it, with nothing parsed.
        hydration?.invokeOnCompletion { hydratedRecords.complete(emptyList()) }
        return hydratedRecords.takeIf { hydration != null }
    }

    /** Applies live timeline changes and invalidates suspended whole-window refreshes. */
    private fun applyTimelineChanges(changes: List<TimelineMessageChangeFfi>): List<String> {
        timelineWindowGeneration.advance()
        val displayedProjectedStreamItemIds =
            timelineItemsById.keys.filterTo(mutableSetOf()) { it.startsWith("stream:") }
        val removedIds =
            changes
                .filterIsInstance<TimelineMessageChangeFfi.Remove>()
                .mapTo(mutableSetOf(), TimelineMessageChangeFfi.Remove::messageIdHex)
        val recordsForStreamPositions =
            buildList {
                timelineRecords.values.filterTo(this) { it.messageIdHex !in removedIds }
                changes.forEach { change ->
                    if (change is TimelineMessageChangeFfi.Upsert) add(change.message)
                }
            }.associateBy(TimelineMessageRecordFfi::messageIdHex)
                .values
                .toList()
        val streamIds = mutableListOf<String>()
        val reactionTargets = linkedSetOf<String>()
        changes.forEach { change ->
            when (change) {
                is TimelineMessageChangeFfi.Upsert -> {
                    val record = change.message
                    recordMembershipProjectionArrival(change)
                    val actionRecord =
                        upsertProjectedRecord(
                            record,
                            reconcileOptimistic = change.trigger == TimelineUpdateTriggerFfi.NEW_MESSAGE,
                            allowDelayedProjection = change.trigger == TimelineUpdateTriggerFfi.NEW_MESSAGE,
                            displayedProjectedStreamItemIds = displayedProjectedStreamItemIds,
                        )
                    if (change.trigger.recomputesReactions()) {
                        reactionTargets.add(record.messageIdHex)
                    }
                    appState.requestProfile(record.sender)
                    record.replyPreview?.let { appState.requestProfile(it.sender) }
                    appState.requestProfiles(record.reactions.userReactions.map { it.sender })
                    if (record.deleted) {
                        deletedMessageIds = deletedMessageIds - record.messageIdHex
                    }
                    if (MessageProjector.isStreamStart(actionRecord)) {
                        MessageProjector.streamId(actionRecord)?.let { streamId ->
                            removedStreamIds.remove(streamId)
                            streamIds.add(streamId)
                        }
                    }
                    if (MessageProjector.isStreamFinal(actionRecord)) {
                        MessageProjector.streamId(actionRecord)?.let { streamId ->
                            activeStreamIds.remove(streamId)
                            // Mark removed so a late Finished event can't recreate
                            // the optimistic preview as a duplicate. See #25.
                            removedStreamIds.add(streamId)
                            optimisticMessages.remove("stream:$streamId")
                        }
                    }
                }
                is TimelineMessageChangeFfi.Remove -> {
                    pendingTimelineRemovedMessageIds = pendingTimelineRemovedMessageIds + change.messageIdHex
                    val removed = timelineRecords[change.messageIdHex]
                    removed
                        ?.let(TimelineProjector::toAppMessageRecord)
                        ?.takeIf(MessageProjector::isStreamStart)
                        ?.let(MessageProjector::streamId)
                        ?.let { streamId ->
                            removedStreamIds.add(streamId)
                            activeStreamIds.remove(streamId)
                            optimisticMessages.remove("stream:$streamId")
                        }
                    removeProjectedRecord(change.messageIdHex)
                    messageById.remove(change.messageIdHex)
                    reactionTargets.add(change.messageIdHex)
                    optimisticMessages.remove("msg:${change.messageIdHex}")
                }
            }
        }
        applyDurableStreamPositions(durableStreamDisplayPositions(recordsForStreamPositions))
        pruneConfirmedOptimisticMessages()
        pruneConfirmedOptimisticReactions()
        recomputeReactions(reactionTargets)
        // The engine streams Upserts for new messages but never Removes ones that
        // scroll out, so the live indexes grow without bound for a conversation
        // kept open while messages arrive. Cap live rows to the newest entries;
        // after loadOlder(), deliberately-loaded history is preserved and only
        // post-pagination live Upserts are trimmed (#1163 / #537).
        trimLiveTimelineWindow(LIVE_TIMELINE_WINDOW_CAP)
        retentionAtSendByMessageId.retainTimelineWindow()
        // Live Upsert/Projection batches add to messageById but never trim it;
        // prune to the (now-bounded) window + optimistic records so it doesn't
        // grow unbounded for an actively-watched conversation (#373).
        messageById.retainTimelineWindow()
        optimisticEdits.retainTimelineWindow()
        pruneMessageOverlaysToWindow()
        publishTimelineFromIndexes()
        // Don't relaunch a watcher for a stream finalized in this same batch
        // (start + final records together) — it was just marked removed. See #25.
        return streamIds.filterNot { it in removedStreamIds }
    }

    private suspend fun hydrateTimelineMarkdown(records: List<TimelineMessageRecordFfi>): List<TimelineMessageRecordFfi> {
        if (records.none(::needsTimelineMarkdownHydration)) return records
        val parseGate = Semaphore(4)
        return coroutineScope {
            records
                .map { record ->
                    async {
                        if (!needsTimelineMarkdownHydration(record)) return@async record
                        val existingDocument =
                            timelineRecords[record.messageIdHex]
                                ?.takeIf { existing -> existing.plaintext == record.plaintext }
                                ?.contentTokens
                                ?.takeIf { document -> document.blocks.isNotEmpty() }
                        val document =
                            existingDocument
                                ?: parseGate.withPermit {
                                    appState.parseMarkdownOrEmpty(record.plaintext)
                                }
                        if (document.blocks.isEmpty()) record else record.withMarkdownTokens(document)
                    }
                }.awaitAll()
        }
    }

    private fun applyHydratedTimelineMarkdown(records: List<TimelineMessageRecordFfi>) {
        assertMainThread { "applyHydratedTimelineMarkdown" }
        var changed = false
        records.forEach { hydrated ->
            val document = hydrated.contentTokens.takeIf { it.blocks.isNotEmpty() } ?: return@forEach
            val current = timelineRecords[hydrated.messageIdHex] ?: return@forEach
            if (!needsTimelineMarkdownHydration(current) || current.plaintext != hydrated.plaintext) {
                return@forEach
            }
            upsertProjectedRecord(current.withMarkdownTokens(document))
            changed = true
        }
        if (changed) publishTimelineFromIndexes()
    }

    private suspend fun initializeReadState(account: String) {
        runCatchingCancellable {
            appState.marmotIo { initializeChatReadState(account, group.groupIdHex) }
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w("DMConversation", "initialize read state failed", it)
        }
    }

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
     * Submits the visit's visible cursor to MDK, including a saved cursor with pending manual attention.
     * Successful rows fold monotonically; after failures, only the latest remaining request restores display state.
     * A latest failure clears request dedupe for retry, and cancellation is rethrown. A null success acknowledges
     * this row instance until a new native row arrives. Already-confirmed reads do not restart retention deadlines.
     */
    private suspend fun submitVisibleReadUpTo(
        account: String,
        trimmed: String,
    ) {
        val consumeManualReminder = shouldConsumeManualUnreadReminder(account)
        if (trimmed == lastSubmittedReadMessageId && !consumeManualReminder) return
        val readWasAlreadyConfirmed = trimmed == latestChatListRow?.lastReadMessageIdHex
        val requestId = ++nextReadRequestId
        lastReadRequestId = requestId
        pendingReadRequestIds += requestId
        lastReadMessageId = trimmed
        lastSubmittedReadMessageId = trimmed
        val markReadResult =
            runCatching {
                appState.marmotIo { markTimelineMessageRead(account, group.groupIdHex, trimmed) }
            }
        pendingReadRequestIds -= requestId
        val markReadFailure = markReadResult.exceptionOrNull()
        if (markReadFailure != null) {
            restoreFailedReadRequest(requestId)
            if (markReadFailure is CancellationException) throw markReadFailure
            if (BuildConfig.DEBUG) Log.w("DMConversation", "mark read failed", markReadFailure)
            return
        }
        markReadResult.getOrNull()?.let { row -> foldVisibleReadRow(account, row) }
        if (requestId > lastSuccessfulReadRequestId) {
            lastSuccessfulReadRequestId = requestId
            successfulReadWithoutRow = if (markReadResult.getOrNull() == null) latestChatListRow to trimmed else null
        }
        if (lastReadRequestId == requestId) {
            lastReadMessageId = confirmedReadMessageId()
            lastSubmittedReadMessageId = trimmed
            if (consumeManualReminder) manualUnreadReminderConsumed = true
        }
        // A visit-level attention acknowledgement must not restart an
        // already-confirmed message's disappearing deadline after reopening.
        if (!readWasAlreadyConfirmed) {
            val anchoredAtSeconds = (clockMillis() / 1_000L).toULong()
            anchorReadExpiryUpTo(trimmed, anchoredAtSeconds)
        }
        runCatchingCancellable {
            appState.dismissConversationNotifications(account, group.groupIdHex)
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w("DMConversation", "dismiss read notifications failed", it)
        }
    }

    /** Restores only the latest failed request; finished requests never regain ownership or suppress retries. */
    private fun restoreFailedReadRequest(requestId: Long) {
        if (lastReadRequestId != requestId) return
        lastReadMessageId = confirmedReadMessageId()
        manualUnreadReminderConsumed = false
        lastSubmittedReadMessageId = null
        lastReadRequestId = pendingReadRequestIds.maxOrNull() ?: 0L
    }

    /** Uses the native cursor, or a successful null-result cursor only while its original row remains current. */
    private fun confirmedReadMessageId(): String? =
        successfulReadWithoutRow?.takeIf { it.first === latestChatListRow }?.second
            ?: latestChatListRow?.lastReadMessageIdHex?.takeIf { it.isNotBlank() }

    /** Folds MDK's read result into the matching list, or monotonically into this conversation if no list is bound. */
    private fun foldVisibleReadRow(
        accountRef: String,
        row: ChatListRowFfi,
    ) {
        persistedLastReadTimelineAt =
            foldMarkReadReturnedRow(row, persistedLastReadTimelineAt) { incoming ->
                if (!appState.applyChatListRowFromMarkRead(accountRef, incoming)) {
                    val folded =
                        latestChatListRow?.let { current ->
                            reduceSubscriptionChatListRow(current, incoming, ChatListUpdateTriggerFfi.UNREAD_CHANGED)
                        } ?: incoming
                    applyAuthoritativeChatListRow(accountRef, folded)
                }
            }
    }

    /** Retries an unconsumed native manual reminder only while this account's conversation is visibly owned. */
    private fun shouldConsumeManualUnreadReminder(accountRef: String): Boolean =
        !manualUnreadReminderConsumed &&
            appState.hasManualUnreadReminder(accountRef, group.groupIdHex, latestChatListRow)

    private fun anchorReadExpiryUpTo(
        messageId: String,
        anchoredAtSeconds: ULong,
    ) {
        val ordered =
            buildList {
                optimisticMessages.values.forEach { add(it.record.messageIdHex) }
                timelineOrder.mapNotNull { timelineItemsById[it]?.record?.messageIdHex }.forEach(::add)
            }
        val upToIdx = ordered.indexOf(messageId)
        if (upToIdx < 0) {
            readAnchoredAtSeconds.putIfAbsent(messageId, anchoredAtSeconds)
            return
        }
        for (index in 0..upToIdx) {
            ordered.getOrNull(index)?.let { readAnchoredAtSeconds.putIfAbsent(it, anchoredAtSeconds) }
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

    private fun pruneConfirmedOptimisticMessages() {
        confirmedOptimisticMessageKeys(
            optimisticKeys = optimisticMessages.keys,
            projectedMessageIds = timelineRecords.keys,
        ).forEach(optimisticMessages::remove)
    }

    private fun acceptedPendingTextOptimisticIdForProjection(projectedMessageIdHex: String): String? {
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

    private fun acceptedPendingMediaOptimisticIdForProjection(projectedMessageIdHex: String): String? {
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

    /** Settles reaction overlays once the authoritative projection matches their intended state. */
    private fun pruneConfirmedOptimisticReactions() {
        confirmedOptimisticReactionKeys(
            activeAccountIdHex = conversationAccountIdHex,
            optimisticChanges = optimisticReactionChanges,
            confirmedSendersByTarget = baseReactionSenders(),
        ).forEach(optimisticReactionChanges::remove)
    }

    /** Only newly delivered live events are timed; initial, paging and refresh rows are excluded. */
    private fun recordMembershipProjectionArrival(change: TimelineMessageChangeFfi.Upsert) {
        if (change.trigger != TimelineUpdateTriggerFfi.NEW_MESSAGE &&
            change.trigger != TimelineUpdateTriggerFfi.GROUP_SYSTEM
        ) {
            return
        }
        val record = change.message
        val type = record.groupSystem?.systemType
        val isMembershipEvent = type == "member_added" || type == "member_removed"
        if (record.messageIdHex !in timelineRecords &&
            isMembershipEvent &&
            GroupSystemEvents.resolve(record)?.fromAuthenticatedStateProjection == true
        ) {
            membershipTimings.projectionArrived(record.messageIdHex)
        }
    }

    /** Installs a native row; peer receipts reconcile only exact send ownership, never same-content legacy guesses. */
    private fun upsertProjectedRecord(
        record: TimelineMessageRecordFfi,
        reconcileOptimistic: Boolean = false,
        allowDelayedProjection: Boolean = false,
        displayedProjectedStreamItemIds: Set<String> = emptySet(),
        preparedAction: AppMessageRecordFfi? = null,
    ): AppMessageRecordFfi {
        val reconcilesSend = reconcileOptimistic || record.recipientConfirmed
        pendingTimelineRemovedMessageIds = pendingTimelineRemovedMessageIds - record.messageIdHex
        // Defensive guard against the Rust core re-emitting an identical
        // record (own-publish + own-relay-echo both fire
        // `timeline_changes_for_event` for the same kind-9). Without this,
        // the remove-then-reinsert pair below briefly empties the bubble
        // from the timeline and Compose renders the "vanished + reappeared"
        // frame as a visible duplicate flash on large media bubbles.
        // Upstream fix tracked separately. See docs/design/white-noise-double-upsert.md.
        //
        // Compare on RENDER-RELEVANT fields only. `receivedAt` and other
        // ephemeral fields differ between the two emits (the Rust core
        // records them with distinct local timestamps), so full equality
        // would never fire — but the bubble's content is identical.
        //
        // Also require the by-id index to already hold the item: only skip the
        // re-projection when the bubble is genuinely still on screen. If the
        // record is held but unprojected, fall through and (re)build it.
        val existing = timelineRecords[record.messageIdHex]
        val previousItemId = existing?.let(::projectedItemId)
        val stillProjected = previousItemId != null && timelineItemsById.containsKey(previousItemId)
        if (existing != null && stillProjected && timelineRecordsRenderEqual(existing, record)) {
            recoverPendingEditStatusForProjection(record)
            return preparedAction ?: TimelineProjector.toAppMessageRecord(record)
        }
        var retentionAtSendSeconds =
            previousItemId?.let { itemId -> timelineItemsById[itemId]?.retentionAtSendSeconds }
        if (previousItemId != null) {
            timelineItemsById.remove(previousItemId)
            timelineOrder.remove(previousItemId)
        }
        // If multiple `_media_pending` siblings are in flight AND no exact
        // bridge has been inserted yet, the projection's owning send is
        // still mid-`sendMediaAttachments` — writing the bubble visibly now
        // would put it at timelineOrder=0uL until the bridge insert stamps
        // the override (visible flip). Stash the record and bail; the
        // bridge insert path will drain `pendingProjectionsAwaitingBridge`
        // and write the projection with the override already in place. The
        // bridge in `optimisticMessages` covers the bubble in the meantime
        // — same content, same position.
        val draftAction = preparedAction ?: TimelineProjector.toAppMessageRecord(record)
        val projectedIsMediaUpsert = draftAction.tags.any { it.values.firstOrNull() == "imeta" }
        val hasAcceptedPendingTextBridge =
            reconcilesSend && record.messageIdHex in acceptedPendingTextOptimisticIds
        val acceptedPendingTextOptimisticId =
            if (hasAcceptedPendingTextBridge) {
                acceptedPendingTextOptimisticIdForProjection(record.messageIdHex)
            } else {
                null
            }
        val acceptedPendingMediaOptimisticId =
            acceptedPendingMediaOptimisticIdForProjection(record.messageIdHex).takeIf { reconcilesSend }
        val acceptedPendingOptimisticId =
            record.clientToken?.takeIf { reconcilesSend && "msg:$it" in optimisticMessages }
                ?: acceptedPendingTextOptimisticId ?: acceptedPendingMediaOptimisticId
        val hasExactBridge =
            optimisticMessages.values.any { it.record.messageIdHex == record.messageIdHex } ||
                hasAcceptedPendingTextBridge ||
                acceptedPendingOptimisticId != null
        val reconcileLegacyMedia = projectedIsMediaUpsert && record.clientToken == null && reconcileOptimistic
        if (reconcileLegacyMedia && !hasExactBridge) {
            val pendingMediaCount =
                optimisticMessages.values.count {
                    isSendableOptimisticStatus(it.status, allowDelayedProjection) &&
                        it.record.tags.any { tag -> tag.values.firstOrNull() == "_media_pending" }
                }
            if (pendingMediaCount > 1) {
                pendingProjectionsAwaitingBridge[record.messageIdHex] = record
                return draftAction
            }
        }
        pendingProjectionsAwaitingBridge.remove(record.messageIdHex)
        val actionRecord = draftAction
        if (
            record.clientToken == null &&
            record.invalidationStatus != null &&
            failedOptimisticMessageIdForInvalidatedProjection(optimisticMessages.values, actionRecord) != null
        ) {
            timelineRecords.remove(record.messageIdHex)
            authoritativeTimelineOrderByMessageId.remove(record.messageIdHex)
            projectedMessageIds.remove(record.messageIdHex)
            messageById.remove(record.messageIdHex)
            if (previousItemId != null) {
                timelineItemsById.remove(previousItemId)
                timelineOrder.remove(previousItemId)
            }
            return actionRecord
        }
        val unownedLegacyRecord =
            record.clientToken == null && record.sourceMessageIdHex == null && !record.recipientConfirmed
        if (
            unownedLegacyRecord &&
            record.invalidationStatus == null &&
            failedOptimisticMessageIdForInvalidatedProjection(optimisticMessages.values, actionRecord) != null
        ) {
            timelineRecords[record.messageIdHex] = record
            projectedMessageIds.add(record.messageIdHex)
            messageById[record.messageIdHex] = actionRecord
            return actionRecord
        }
        if (record.invalidationStatus == null && !record.recipientConfirmed) {
            invalidatedProjectionIdsMatchingMessage(timelineRecords, actionRecord)
                .filterNot { it == record.messageIdHex }
                .forEach(::removeProjectedRecord)
        }
        timelineRecords[record.messageIdHex] = record
        projectedMessageIds.add(record.messageIdHex)
        preserveOptimisticDisplayPosition(record.messageIdHex, record.messageIdHex)
        preserveStreamFinalDisplayPosition(
            record.messageIdHex,
            actionRecord,
            displayedProjectedStreamItemIds,
        )
        // A peer receipt proves delivery of this native row, not ownership of a same-content optimistic send.
        val reconciledOptimisticId =
            acceptedPendingOptimisticId
                ?: if (hasAcceptedPendingTextBridge || record.clientToken != null || record.recipientConfirmed) {
                    null
                } else {
                    optimisticMessageIdForProjection(
                        optimisticMessages.values,
                        actionRecord,
                        allowDelayedProjection = allowDelayedProjection,
                    ).takeIf { reconcileOptimistic }
                }
        acceptedPendingOptimisticId?.let { optimisticId ->
            appState.commitOptimisticSentPreview(
                accountRef = conversationAccountRef,
                groupIdHex = record.groupIdHex,
                optimisticMessageIdHex = optimisticId,
                confirmedMessageIdHex = record.messageIdHex,
            )
        }
        retentionAtSendSeconds =
            retentionAtSendForProjection(
                messageId = record.messageIdHex,
                projectedRetentionSeconds = actionRecord.retentionSeconds,
                optimisticMessageId = reconciledOptimisticId,
            )
        reconciledOptimisticId?.let { optimisticId ->
            handoffPendingMessageEdit(optimisticId, record.messageIdHex, ready = true)
            preserveOptimisticDisplayPosition(record.messageIdHex, optimisticId)
            val optimisticKey = "msg:$optimisticId"
            // An authoritative own-message projection proves the engine has
            // durably accepted this optimistic send even if the synchronous
            // FFI response was lost. Clear the captured draft at this point;
            // a later send exception may be only an ambiguous acknowledgement
            // failure and must not undo durable acceptance.
            completeDurableAcceptance(optimisticKey)
            // Hand off own-sent media bytes from the pending optimistic to
            // the projection's cache key BEFORE the bubble's LaunchedEffect
            // can fire and ask Blossom for them. Without this, the projected
            // bubble starts rendering, finds the thumbnail/plaintext caches
            // empty for the confirmed messageIdHex, and triggers an FFI
            // downloadMedia round-trip — re-downloading bytes we literally
            // just uploaded.
            handoffOwnMediaCacheOnReconcile(optimisticKey, record.messageIdHex)
            optimisticMessages.remove(optimisticKey)
            optimisticSendPhases.remove(optimisticKey)
            messageById.remove(optimisticId)
            if (optimisticId == acceptedPendingTextOptimisticId) {
                acceptedPendingTextOptimisticIds.remove(record.messageIdHex)
            }
            // The engine echo just flipped this pending bubble to a
            // projected record. If we're tracing this send, this is the
            // "self-echo drives the sent flip" path (issue #913).
            appState.pendingSendDiagnostics.recordEchoReconcile(optimisticId)
        }
        messageById[record.messageIdHex] = actionRecord
        recoverPendingEditStatusForProjection(record)
        record.clientToken?.let { clientToken ->
            // A newly mounted controller may see the native projection after
            // the editor's original controller was disposed. The process-owned
            // handoff still knows this token and can publish its queued edit.
            if (pendingMessageEditHandoff.hasSession(pendingEditKey(clientToken))) {
                handoffPendingMessageEdit(clientToken, record.messageIdHex, ready = true)
            }
        }
        val item =
            timelineMessageFromProjection(
                record = record,
                actionRecord = actionRecord,
                retentionAtSendSeconds = retentionAtSendSeconds,
            )
        timelineItemsById[item.id] = item
        insertTimelineItemId(item.id)
        return actionRecord
    }

    /** Bridges a newly projected row through the position of its optimistic bubble. */
    private fun preserveOptimisticDisplayPosition(
        projectedId: String,
        optimisticId: String,
    ) {
        val optimistic = optimisticMessages["msg:$optimisticId"] ?: return
        timelinePresentationIds[projectedId] = optimistic.presentationId
        durableStreamPositionOverrideIds.remove(projectedId)
        durableStreamDisplayParentByMessageId.remove(projectedId)
        preservedTimelinePositionOverrideIds.add(projectedId)
        optimisticSendPositionPreserves.add(projectedId)
        localTimelineOrderOverrides[projectedId] = optimistic.timelineOrder
        localTimelineTimestampOverrides[projectedId] = optimistic.record.recordedAt
    }

    private fun rememberRetentionAtSend(
        messageId: String,
        retentionSeconds: ULong?,
    ): ULong? =
        retentionSeconds?.takeIf { it > 0uL }?.also { snapshot ->
            retentionAtSendByMessageId[messageId] = snapshot
        }

    private fun transferRetentionAtSend(
        fromMessageId: String,
        toMessageId: String,
    ): ULong? {
        if (fromMessageId == toMessageId) return retentionAtSendByMessageId[toMessageId]
        val snapshot = retentionAtSendByMessageId.remove(fromMessageId) ?: retentionAtSendByMessageId[toMessageId]
        if (snapshot != null) retentionAtSendByMessageId[toMessageId] = snapshot
        return snapshot
    }

    private fun retentionAtSendForProjection(
        messageId: String,
        projectedRetentionSeconds: ULong?,
        optimisticMessageId: String?,
    ): ULong? {
        val optimisticSnapshot =
            optimisticMessageId?.let { optimisticId ->
                retentionAtSendByMessageId[optimisticId]
                    ?: optimisticMessages["msg:$optimisticId"]?.retentionAtSendSeconds
            }
        if (optimisticMessageId != null && optimisticMessageId != messageId) {
            retentionAtSendByMessageId.remove(optimisticMessageId)
        }
        val snapshot =
            retentionHintForProjection(
                projectedRetentionSeconds = projectedRetentionSeconds,
                currentGroupRetentionSeconds = group.disappearingMessageSecs,
                optimisticSnapshot = optimisticSnapshot,
                rememberedSnapshot = retentionAtSendByMessageId[messageId],
            )
        if (snapshot == null) {
            retentionAtSendByMessageId.remove(messageId)
        } else {
            retentionAtSendByMessageId[messageId] = snapshot
        }
        return snapshot
    }

    /** Rebuilds durable stream overrides and their immediate rendered-parent links. */
    private fun applyDurableStreamPositions(positions: Map<String, StreamFinalDisplayPosition>) {
        val staleIds = durableStreamPositionOverrideIds + durableStreamDisplayParentByMessageId.keys - positions.keys
        staleIds.forEach(::clearDurableStreamPosition)
        positions.forEach { (messageId, position) ->
            // A live preview is the row the user is already reading. Its
            // position wins for this controller session; the durable link is
            // the reload/cold-projection fallback.
            val parentId = position.afterMessageId ?: return@forEach
            val parent = timelineRecords[parentId] ?: return@forEach
            if (
                messageId in preservedTimelinePositionOverrideIds ||
                messageId in optimisticSendPositionPreserves
            ) {
                durableStreamDisplayParentByMessageId[messageId] = parentId
                refreshProjectedTimelinePosition(messageId)
                return@forEach
            }
            if (messageId !in timelineRecords) return@forEach
            val parentRecordedAt = localTimelineTimestampOverrides[parentId] ?: parent.timelineAt
            val effectivePosition =
                resolvedDurableStreamDisplayPosition(
                    candidate = position,
                    parentRecordedAt = parentRecordedAt,
                    parentTimelineOrder = localTimelineOrderOverrides[parentId] ?: 0uL,
                ) ?: run {
                    clearDurableStreamPosition(messageId)
                    return@forEach
                }

            durableStreamDisplayParentByMessageId[messageId] = parentId
            localTimelineOrderOverrides[messageId] = effectivePosition.timelineOrder
            localTimelineTimestampOverrides[messageId] = effectivePosition.recordedAt
            durableStreamPositionOverrideIds.add(messageId)
            refreshProjectedTimelinePosition(messageId)
        }
    }

    /** Releases a durable override and any parent link used only for display ordering. */
    private fun clearDurableStreamPosition(messageId: String) {
        val removedOverride = durableStreamPositionOverrideIds.remove(messageId)
        val removedParent = durableStreamDisplayParentByMessageId.remove(messageId) != null
        if (!removedOverride && !removedParent) return
        if (removedOverride) {
            localTimelineOrderOverrides.remove(messageId)
            localTimelineTimestampOverrides.remove(messageId)
        }
        refreshProjectedTimelinePosition(messageId)
    }

    /** Reprojects one indexed row after a transient display position changes. */
    private fun refreshProjectedTimelinePosition(messageId: String) {
        val projected = timelineRecords[messageId] ?: return
        val itemId = projectedItemId(projected)
        val currentItem = timelineItemsById[itemId] ?: return
        val actionRecord = TimelineProjector.toAppMessageRecord(projected)
        timelineItemsById[itemId] =
            timelineMessageFromProjection(
                record = projected,
                actionRecord = actionRecord,
                retentionAtSendSeconds = currentItem.retentionAtSendSeconds,
            )
        timelineOrder.remove(itemId)
        insertTimelineItemId(itemId)
    }

    /**
     * Keeps send bridges while media siblings remain pending, then lets projected
     * rows settle back to their authoritative positions in MDK's bounded window.
     */
    private fun releaseOrphanedOptimisticSendPreserves(): Boolean {
        // Keep completed rows beside their still-pending send siblings. MDK order wins
        // after the last media handoff, failure or discard removes this transient bridge.
        val pendingMedia =
            optimisticMessages.values.any { message ->
                message.status == MessageStatus.Pending &&
                    message.record.tags.any { tag -> tag.values.firstOrNull() == "_media_pending" }
            }
        if (pendingMedia) return false
        val orphaned =
            optimisticSendPositionPreserves.releaseOrphaned(
                optimisticKeys = optimisticMessages.keys,
                projectedMessageIds = projectedMessageIds,
            )
        orphaned.forEach { id ->
            localTimelineOrderOverrides.remove(id)
            localTimelineTimestampOverrides.remove(id)
            preservedTimelinePositionOverrideIds.remove(id)
            refreshProjectedTimelinePosition(id)
        }
        return orphaned.isNotEmpty()
    }

    /**
     * Reports unexpected adjacent fallback-order inversions once per pair in debug
     * builds; authoritative pairs may intentionally invert wall time for epoch safety.
     */
    private fun logTimelineInversionsForDebug(
        rows: List<TimelineMessage>,
        expectedHandoffPreserves: Set<String>,
    ) {
        if (!BuildConfig.DEBUG) return
        val inversionsByPair =
            adjacentTimelineInversions(rows)
                .filterNot { inversion ->
                    (inversion.above.authoritativeOrder != null && inversion.below.authoritativeOrder != null) ||
                        checkNotNull(inversion.above.projected).messageIdHex in expectedHandoffPreserves ||
                        checkNotNull(inversion.below.projected).messageIdHex in expectedHandoffPreserves
                }.associateBy { inversion ->
                    checkNotNull(inversion.above.projected).messageIdHex to
                        checkNotNull(inversion.below.projected).messageIdHex
                }
        // De-dup so a persistent inversion logs once, not every publish.
        (inversionsByPair.keys - loggedTimelineOrderingInversionPairs).forEach { pair ->
            Log.w("TimelineOrder", describeTimelineInversion(inversionsByPair.getValue(pair)))
        }
        loggedTimelineOrderingInversionPairs = inversionsByPair.keys
    }

    /** Formats both rows and controller override state for an inversion diagnostic. */
    private fun describeTimelineInversion(inversion: TimelineAdjacentInversion): String {
        val aboveSource = checkNotNull(inversion.above.projected)
        val belowSource = checkNotNull(inversion.below.projected)
        return "inversion(#1578) group=${group.groupIdHex.take(8)} " +
            "source=${inversion.sourceTimelineInverted} arrival=${inversion.arrivalInverted} " +
            "above[${describeTimelineInversionRow(inversion.above, aboveSource)}] " +
            "below[${describeTimelineInversionRow(inversion.below, belowSource)}]"
    }

    /** Formats the source and effective positions of one diagnostic row. */
    private fun describeTimelineInversionRow(
        row: TimelineMessage,
        source: TimelineMessageRecordFfi,
    ): String =
        "id=${source.messageIdHex.take(8)} direction=${row.record.direction} " +
            "displayAt=${row.record.recordedAt} sourceAt=${source.timelineAt} " +
            "receivedAt=${source.receivedAt} order=${row.timelineOrder} authoritative=${row.authoritativeOrder} " +
            "timestampOverride=${source.messageIdHex in localTimelineTimestampOverrides} " +
            "orderOverride=${source.messageIdHex in localTimelineOrderOverrides} " +
            "durable=${source.messageIdHex in durableStreamPositionOverrideIds} " +
            "optimisticPreserve=${source.messageIdHex in optimisticSendPositionPreserves}"

    /** Keeps a durable stream final at the position of the preview it replaces. */
    private fun preserveStreamFinalDisplayPosition(
        projectedId: String,
        actionRecord: AppMessageRecordFfi,
        displayedProjectedStreamItemIds: Set<String>,
    ) {
        val streamId = MessageProjector.streamId(actionRecord) ?: return
        val itemId = "stream:$streamId"
        val displayedStream =
            optimisticMessages[itemId]
                ?: timelineItemsById[itemId]?.takeIf { itemId in displayedProjectedStreamItemIds }
        val position = streamFinalDisplayPosition(actionRecord, displayedStream) ?: return
        durableStreamPositionOverrideIds.remove(projectedId)
        preservedTimelinePositionOverrideIds.add(projectedId)
        // A stream-final live preview holds its position for the whole session, so
        // it is not an orphan-releasable optimistic-send preserve (#1578).
        optimisticSendPositionPreserves.remove(projectedId)
        localTimelineOrderOverrides[projectedId] = position.timelineOrder
        localTimelineTimestampOverrides[projectedId] = position.recordedAt
    }

    /** Removes one projection and every controller-owned index keyed to it. */
    internal fun removeProjectedRecord(messageIdHex: String) {
        val itemId = timelineRecords[messageIdHex]?.let(::projectedItemId) ?: "msg:$messageIdHex"
        timelineRecords.remove(messageIdHex)
        authoritativeTimelineOrderByMessageId.remove(messageIdHex)
        durableStreamDisplayParentByMessageId.remove(messageIdHex)
        projectedMessageIds.remove(messageIdHex)
        localTimelineOrderOverrides.remove(messageIdHex)
        localTimelineTimestampOverrides.remove(messageIdHex)
        preservedTimelinePositionOverrideIds.remove(messageIdHex)
        optimisticSendPositionPreserves.remove(messageIdHex)
        durableStreamPositionOverrideIds.remove(messageIdHex)
        retentionAtSendByMessageId.remove(messageIdHex)
        readAnchoredAtSeconds.remove(messageIdHex)
        deletedMessageIds = deletedMessageIds - messageIdHex
        optimisticReactionChanges.entries.removeAll { (_, change) -> change.targetMessageId == messageIdHex }
        reactionsState.remove(messageIdHex)
        timelineItemsById.remove(itemId)
        timelineOrder.remove(itemId)
    }

    private fun suppressProjectedTimelineItems(messageIds: List<String>) {
        messageIds.forEach { messageIdHex ->
            val itemId = timelineRecords[messageIdHex]?.let(::projectedItemId) ?: "msg:$messageIdHex"
            timelineItemsById.remove(itemId)
            timelineOrder.remove(itemId)
        }
    }

    // Drop the oldest live-projected records beyond [maxItems], using the same
    // total order the publish sorts by. When older history was deliberately
    // loaded, [protectedTimelineMessageIds] is excluded from the trim.
    private fun trimLiveTimelineWindow(maxItems: Int) {
        val protectedIds = if (hasLoadedOlderPages) protectedTimelineMessageIds else emptySet()
        val evictedIds =
            timelineMessageIdsExceedingLiveCap(
                items = timelineItemsById.values,
                protectedIds = protectedIds,
                maxLiveItems = maxItems,
            )
        if (evictedIds.isNotEmpty()) {
            evictedIds.forEach(::removeProjectedRecord)
        }
        readAnchoredAtSeconds.retainTimelineWindow()
    }

    /** Drops entries for messages that left the loaded window and have no in-flight optimistic record. */
    private fun MutableMap<String, *>.retainTimelineWindow() = retainWindowMessageKeys(this, timelineRecords.keys, optimisticMessages.values)

    private fun pruneMessageOverlaysToWindow() {
        if (deletedMessageIds.isEmpty() && optimisticReactionChanges.isEmpty()) return
        val optimisticIds = optimisticMessages.values.map { it.record.messageIdHex }
        val overlayTargets =
            buildList {
                addAll(deletedMessageIds)
                optimisticReactionChanges.values.forEach { add(it.targetMessageId) }
            }
        val retained =
            retainedMessageOverlayTargets(
                timelineMessageIds = timelineRecords.keys,
                optimisticMessageIds = optimisticIds,
                overlayTargetIds = overlayTargets,
            )
        if (deletedMessageIds.any { it !in retained }) {
            deletedMessageIds = deletedMessageIds.filterTo(linkedSetOf()) { it in retained }
        }
        optimisticReactionChanges.entries.removeAll { (_, change) -> change.targetMessageId !in retained }
    }

    /** Projects one MDK row with its authoritative rank and any transient display bridge. */
    private fun timelineMessageFromProjection(
        record: TimelineMessageRecordFfi,
        actionRecord: AppMessageRecordFfi = TimelineProjector.toAppMessageRecord(record),
        retentionAtSendSeconds: ULong? = null,
    ): TimelineMessage {
        val streamId = MessageProjector.streamId(actionRecord).takeIf { MessageProjector.isStreamStart(actionRecord) }
        val displayRecord =
            if (streamId != null) {
                actionRecord.copy(plaintext = actionRecord.plaintext.ifBlank { copy.waitingForStream })
            } else {
                actionRecord
            }.withRecordedAtOverride(localTimelineTimestampOverrides[record.messageIdHex])
        return TimelineMessage(
            id = streamId?.let { "stream:$it" } ?: "msg:${record.messageIdHex}",
            record = displayRecord,
            status =
                when {
                    streamId != null -> MessageStatus.Streaming
                    MessageProjector.isMine(actionRecord, conversationAccountIdHex) ->
                        if (record.sourceMessageIdHex == null && !record.recipientConfirmed) {
                            MessageStatus.Pending
                        } else {
                            MessageStatus.Sent
                        }
                    else -> MessageStatus.Received
                },
            projected = record,
            timelineOrder = localTimelineOrderOverrides[record.messageIdHex] ?: 0uL,
            authoritativeOrder =
                authoritativeTimelineOrderByMessageId[record.messageIdHex]
                    ?.takeUnless {
                        record.messageIdHex in localTimelineOrderOverrides ||
                            record.messageIdHex in localTimelineTimestampOverrides
                    },
            displayAfterMessageIdHex = durableStreamDisplayParentByMessageId[record.messageIdHex],
            retentionAtSendSeconds = retentionAtSendSeconds.takeIf { actionRecord.retentionSeconds == null },
            presentationId = timelinePresentationIds[record.messageIdHex] ?: projectedItemId(record),
        )
    }

    private fun projectedItemId(record: TimelineMessageRecordFfi): String {
        val actionRecord = TimelineProjector.toAppMessageRecord(record)
        val streamId = MessageProjector.streamId(actionRecord).takeIf { MessageProjector.isStreamStart(actionRecord) }
        return streamId?.let { "stream:$it" } ?: "msg:${record.messageIdHex}"
    }

    /** Adds an item to the membership index; publication performs display ordering. */
    private fun insertTimelineItemId(itemId: String) {
        // Append in O(1). Position is irrelevant: publishTimelineFromIndexes
        // orders authoritative rows by MDK ordinal and merges local overlays,
        // so timelineOrder is only a membership set. The previous sorted insert
        // did an O(n) scan per item, making each page load O(n²). See #74.
        timelineOrder.add(itemId)
    }

    // Main-thread only, like the rest of ConversationController's Compose
    // state. Re-entrant counter — when non-zero,
    // `publishTimelineFromIndexes` defers its work and the outermost
    // `coalesceTimelinePublishes` flushes once. Batching a burst of
    // subscription emits into one publish is the largest single saving for the
    // 7.21ms-on-janky-frames "Input+Anim+Layout" cost in `dumpsys gfxinfo`:
    // each publish re-sorts + de-dupes the full timeline and re-aggregates the
    // edits index.
    private var publishSuppressionDepth = 0
    private var publishPending = false
    private var timelinePositionSettlementJob: Job? = null

    // #1578 detector de-dup: adjacent inversion pairs already logged this session.
    private var loggedTimelineOrderingInversionPairs = emptySet<Pair<String, String>>()

    private inline fun coalesceTimelinePublishes(block: () -> Unit) {
        assertMainThread { "coalesceTimelinePublishes" }
        publishSuppressionDepth += 1
        try {
            block()
        } finally {
            publishSuppressionDepth -= 1
            if (publishSuppressionDepth == 0 && publishPending) {
                publishPending = false
                publishTimelineFromIndexesInternal()
            }
        }
    }

    private fun publishTimelineFromIndexes() {
        assertMainThread { "publishTimelineFromIndexes" }
        if (publishSuppressionDepth > 0) {
            publishPending = true
            return
        }
        publishTimelineFromIndexesInternal()
    }

    private fun scheduleTimelinePositionSettlement() {
        timelinePositionSettlementJob =
            deferTimelinePositionSettlement(
                scope = inviteStreamScope,
                currentJob = timelinePositionSettlementJob,
                awaitBoundary = ::awaitRenderedTimelineFrame,
            ) {
                timelinePositionSettlementJob = null
                publishPending = false
                publishTimelineFromIndexes()
            }
    }

    private suspend fun awaitRenderedTimelineFrame() {
        // Choreographer callbacks run before traversal. Waiting for two callbacks
        // guarantees the preserved state gets one complete draw before settlement
        // updates the state for the following frame.
        repeat(2) {
            suspendCancellableCoroutine { continuation ->
                val choreographer = Choreographer.getInstance()
                val callback =
                    Choreographer.FrameCallback {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                continuation.invokeOnCancellation {
                    choreographer.removeFrameCallback(callback)
                }
                choreographer.postFrameCallback(callback)
            }
        }
    }

    /** Publishes the indexed window after row-owned expiry and visibility filters. */
    private fun publishTimelineFromIndexesInternal() {
        // A first page held for its event cards is published whole by its release, with every change made meanwhile.
        if (isHoldingInitialPageForEventCards) return
        // Preserve the handoff snapshot for one complete frame. Any projection
        // or optimistic mutation arriving during that frame is already reflected
        // in the indexes and is folded into the scheduled settlement publish.
        if (timelinePositionSettlementJob?.isActive == true) {
            publishPending = true
            return
        }
        val projected = timelineOrder.mapNotNull { timelineItemsById[it] }
        // Read-anchored local expiry (#797) uses only the policy pinned to each
        // row. The mutable group policy cannot reinterpret projected history.
        // When no row owns a deadline, keep the no-filter-allocation fast path.
        val candidates = optimisticMessages.values + projected
        val hasExpiryCandidate =
            candidates.any { message ->
                message.record.retentionExpiresAt?.let { it > 0uL } == true ||
                    message.retentionAtSendSeconds?.let { it > 0uL } == true
            }
        val live =
            if (!hasExpiryCandidate) {
                candidates
            } else {
                val nowMillis = clockMillis()
                val nowSeconds = (nowMillis.coerceAtLeast(0L) / 1_000L).toULong()
                val messageOrder =
                    firstMessageOrder(
                        buildList {
                            optimisticMessages.values.forEach { add(it.record.messageIdHex) }
                            projected.forEach { add(it.record.messageIdHex) }
                        },
                    )
                candidates.filter { message ->
                    val record = message.record
                    if (record.direction == "sent" && message.projected == null) {
                        readAnchoredAtSeconds.putIfAbsent(record.messageIdHex, nowSeconds)
                    }
                    !isTimelineRecordLocallyExpired(
                        nowMillis = nowMillis,
                        record = record,
                        row = localExpiryRow(message, messageOrder),
                    )
                }
            }
        val hiddenIds = appState.hiddenMessageIdsInGroup(conversationAccountRef, group.groupIdHex)
        val visible =
            if (hiddenIds.isEmpty()) {
                live
            } else {
                live.filter { message ->
                    isTimelineMessageVisible(message.record.messageIdHex, hiddenIds)
                }
            }
        val aggregated = withAuthoritativeEdits(emptyMap(), authoritativeEditsOf(timelineRecords.values))
        // Admission projects the revised body before publication. The original row's
        // delivery state cannot confirm its edit; retain the overlay until the edit's
        // own native publication and exact revision agree.
        optimisticEdits.entries
            .filter { (target, edit) ->
                isConfirmedOptimisticEdit(target, edit, aggregated)
            }.map { it.key }
            .forEach(optimisticEdits::remove)
        timeline =
            orderTimelineMessagesForDisplay(
                (visible + streamDebugTimelineItems.values).map { it.withOptimisticEditStatus() },
            )
        timelinePresentationIds.keys.retainAll(timeline.mapTo(HashSet()) { it.record.messageIdHex })
        // The optimistic→confirmed handoff snapshot is intentionally preserved;
        // do not report it as the stale-override symptom this detector targets.
        logTimelineInversionsForDebug(timeline, optimisticSendPositionPreserves.snapshot())
        // Publish the confirmed row once at its optimistic position before
        // cleaning the transient bridge. Cleanup rebuilds only the internal
        // indexes; the immutable snapshot above remains stable for observers,
        // and the next publish settles the row to its projected position.
        if (releaseOrphanedOptimisticSendPreserves()) {
            scheduleTimelinePositionSettlement()
        }
        editsByTarget = applyOptimisticEdits(aggregated)
        signalForegroundSweepScheduleChanged()
    }

    private fun isConfirmedOptimisticEdit(
        target: String,
        edit: OptimisticEdit,
        aggregated: Map<String, EditState>,
    ): Boolean {
        val confirmedWireEdit = aggregated[target]?.latestText == edit.text
        val record = timelineRecords[target]
        val confirmedNativeEdit =
            edit.nativeEditMessageId != null &&
                record?.edit?.latestEditMessageIdHex == edit.nativeEditMessageId &&
                record.plaintext == edit.text
        return edit.status == MessageStatus.Sent && (confirmedWireEdit || confirmedNativeEdit)
    }

    /**
     * Overlay the optimistic edit text onto [aggregated] so the bubble renders
     * the edited body immediately. A Pending/Sent overlay shows its text; a
     * Failed overlay shows [OptimisticEdit.preEditText] (the revert target).
     */
    private fun applyOptimisticEdits(aggregated: Map<String, EditState>): Map<String, EditState> {
        if (optimisticEdits.isEmpty()) return aggregated
        val merged = LinkedHashMap(aggregated)
        for ((target, edit) in optimisticEdits) {
            val failed = edit.status == MessageStatus.Failed
            val displayText = if (failed) edit.preEditText else edit.text
            val base = merged[target]
            when {
                base != null -> merged[target] = base.copy(latestText = displayText)
                // No real kind-1009 was accepted yet (null base). Synthesize an edit
                // aggregate only for an applied overlay (Pending/Sent) so the bubble
                // shows the optimistic body with an edited indicator. A Failed edit
                // reverts to the original text and never accepted a kind-1009, so
                // leave the target absent — no spurious "edited" badge.
                !failed -> merged[target] = EditState(latestText = displayText, count = 1, versions = emptyList())
            }
        }
        return merged
    }

    /**
     * Surface an in-flight optimistic edit as the target bubble's status so the
     * existing Sending indicator / Failed retry+discard row light up without a
     * new affordance. Durable revisions can also override a pending original;
     * a failed original keeps its own retry status until that send resolves.
     */
    private fun TimelineMessage.withOptimisticEditStatus(): TimelineMessage {
        val edit = optimisticEdits[record.messageIdHex] ?: return this
        val durablePending = edit.durableIntent != null && status == MessageStatus.Pending
        if (status != MessageStatus.Sent && !durablePending) return this
        val fromEdit = status == MessageStatus.Sent
        return when (edit.status) {
            MessageStatus.Pending -> copy(status = MessageStatus.Pending, statusFromEdit = fromEdit)
            MessageStatus.Failed -> copy(status = MessageStatus.Failed, statusFromEdit = fromEdit)
            else -> this
        }
    }

    private fun nextOptimisticTimelineOrder(): ULong =
        nextTimelineOrder(
            published = timeline.asSequence().map { it.timelineOrder },
            pending = optimisticMessages.values.asSequence().map { it.timelineOrder },
        )

    private fun recomputeReactions() {
        // Lowercased to match baseReactionSenders(): hex account-id casing can
        // drift between the active account and reaction senders, and a mismatch
        // would render your own reaction as not-mine. See #143.
        val mine = conversationAccountIdHex?.lowercase()
        val sendersByTarget = baseReactionSenders()
        if (mine != null) {
            optimisticReactionChanges.values.forEach { change ->
                val sendersByEmoji = sendersByTarget.getOrPut(change.targetMessageId) { linkedMapOf() }
                val senders = sendersByEmoji.getOrPut(change.emoji) { linkedSetOf() }
                if (change.add) {
                    senders.add(mine)
                } else {
                    senders.remove(mine)
                }
            }
        }
        val computed =
            reactionTalliesForWindow(sendersByTarget, window.frame?.references, optimisticReactionChanges.values, mine)
        reactionsState.keys.retainAll(computed.keys)
        reactionsState.putAll(computed)
    }

    private fun recomputeReactions(targetMessageIds: Set<String>) {
        if (targetMessageIds.isEmpty()) return
        targetMessageIds.forEach { target ->
            val tallies = reactionTalliesFor(target)
            if (tallies.isEmpty()) {
                reactionsState.remove(target)
            } else {
                reactionsState[target] = tallies
            }
        }
    }

    /** Chips for one message: window references when retained, otherwise confirmed senders plus optimistic changes. */
    private fun reactionTalliesFor(targetMessageId: String): List<ReactionTally> {
        val changes = optimisticReactionChanges.values.filter { it.targetMessageId == targetMessageId }
        window.references(targetMessageId)?.let { return windowReactionTallies(it.reactions, changes) }
        val confirmed = linkedMapOf<String, MutableSet<String>>()
        timelineRecords[targetMessageId]?.reactions?.byEmoji.orEmpty().forEach { summary ->
            confirmed.getOrPut(summary.emoji) { linkedSetOf() }.addAll(summary.senders)
        }
        return reactionTalliesForSenders(
            activeAccountIdHex = conversationAccountIdHex,
            confirmedSendersByEmoji = confirmed,
            optimisticChanges = optimisticReactionChanges.values.filter { it.targetMessageId == targetMessageId },
        )
    }

    /** Reads and maps a complete canonical reaction snapshot off the UI thread for this conversation. */
    internal suspend fun loadReactionParticipants(targetMessageId: String): List<ReactionParticipant> {
        val account = requireNotNull(conversationAccountRef)
        check(acceptsConversationActionOwner(account, group.groupIdHex))
        val participants =
            appState.marmotIo {
                messageReactions(account, group.groupIdHex, targetMessageId)
                    .map { ReactionParticipant(sender = it.sender, emoji = it.emoji, reactedAt = it.reactedAt) }
            }
        check(acceptsConversationActionOwner(account, group.groupIdHex))
        return participants
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

    private fun baseReactionSenders(): LinkedHashMap<String, LinkedHashMap<String, MutableSet<String>>> {
        val result = linkedMapOf<String, LinkedHashMap<String, MutableSet<String>>>()
        timelineRecords.values.forEach { record ->
            val byEmoji = result.getOrPut(record.messageIdHex) { linkedMapOf() }
            record.reactions.byEmoji.forEach { summary ->
                // Lowercased so the optimistic add/remove and the `mine` check in
                // recomputeReactions() match regardless of casing drift. See #143.
                byEmoji.getOrPut(summary.emoji) { linkedSetOf() }.addAll(summary.senders.map { it.lowercase() })
            }
        }
        return result
    }

    /** Re-reads authoritative membership without replaying a pending group mutation. */
    suspend fun retryMembers() = refreshMembers()

    /** Retries only the authority read for a retired invite; it never replays Join. */
    suspend fun retryInviteAcceptanceAuthority() {
        if (ownsInviteAcceptanceResult && inviteAcceptanceResolutionPending) {
            withMutationLockResult(Unit) { if (inviteAcceptanceResolutionPending) refreshMembers() }
        }
    }

    /** Starts a roster read only while this controller still owns its account presentation. */
    private fun beginMemberRosterRefresh(): Long? =
        synchronized(liveSubscriptionLock) {
            val resolvingElsewhere = inviteAcceptanceResolutionPending && inviteAuthorityReadInFlight
            val cannotRefresh = accountTeardownRequested || controllerCleared || resolvingElsewhere
            if (cannotRefresh) null else memberRosterRefreshGeneration.advance()
        }

    /** Resolves conflicting list snapshots only while the opening controller still owns the read. */
    private suspend fun resolveInitialInviteConfirmation(
        account: String,
        refreshGeneration: Long,
    ): Boolean {
        if (!inviteConfirmationUnresolved) return true
        val epoch = mutationPresentation.groupAuthorityEpoch
        val runtimeGeneration = appState.runtimeGeneration
        val canonical =
            try {
                inviteAuthorityReadInFlight = true
                liveSubscriptions.readInviteConfirmation(account, group.groupIdHex)
            } finally {
                inviteAuthorityReadInFlight = false
            }
        val currentRead = memberRosterRefreshGeneration.isCurrent(refreshGeneration)
        val ownsRead =
            ownsInviteAcceptanceResult &&
                epoch == mutationPresentation.groupAuthorityEpoch &&
                currentRead &&
                appState.runtimeGeneration == runtimeGeneration
        if (ownsRead) applyGroupState(canonical)
        // A newer canonical nonterminal update already resolved consent. Discard this
        // older snapshot, but let the same owner load a fresh roster for that group.
        val resolvedByUpdate = !inviteConfirmationUnresolved && group.acceptsInviteResults()
        return ownsCurrentMemberRead(refreshGeneration, runtimeGeneration) && (ownsRead || resolvedByUpdate)
    }

    // Publication holds the generation lock; never acquire liveSubscriptionLock here.
    private fun ownsCurrentMemberRead(
        generation: Long,
        runtimeGeneration: Int,
    ): Boolean =
        !controllerCleared &&
            !accountTeardownRequested &&
            memberRosterRefreshGeneration.isCurrent(generation) &&
            appState.runtimeGeneration == runtimeGeneration

    /**
     * Publishes the latest authoritative roster while rejecting older refresh completions.
     * [automaticRetryAttempt] counts the bounded re-reads already spent by this chain (#2861).
     */
    private suspend fun refreshMembers(
        automaticRetryAttempt: Int = 0,
        prefetchedRoster: Deferred<Result<GroupRosterFfi>>? = null,
    ) {
        val account = conversationAccountRef ?: return
        val generation = beginMemberRosterRefresh() ?: return
        val runtimeGeneration = appState.runtimeGeneration
        val recoveringFromBlock = automaticRetryAttempt > 0 || rosterBlockReason != null
        if (inviteAcceptanceResolutionPending) inviteAcceptanceResolutionState = GroupRosterLoadState.LOADING
        memberRosterLoadTracker.transition(GroupRosterRefreshEvent.STARTED)
        try {
            runCatchingCancellable {
                readAndApplyMembers(account, generation, runtimeGeneration, prefetchedRoster)
            }.onSuccess {
                if (recoveringFromBlock && memberRosterState == GroupRosterLoadState.READY) {
                    logRosterRead("recovered", kind = null, attempt = automaticRetryAttempt)
                }
            }.onFailure { failure ->
                if (ownsCurrentMemberRead(generation, runtimeGeneration)) {
                    handleMemberReadFailure(account, generation, runtimeGeneration, failure, automaticRetryAttempt)
                }
            }
        } catch (cancel: CancellationException) {
            memberRosterRefreshGeneration.runIfCurrent(generation) {
                if (ownsCurrentMemberRead(generation, runtimeGeneration)) {
                    memberRosterLoadTracker.restoreAfterCancellation()
                    if (inviteAcceptanceResolutionPending) inviteAcceptanceResolutionState = GroupRosterLoadState.FAILED
                }
            }
            throw cancel
        }
    }

    /** Reads consent before members and never publishes a roster across a canonical group edge. */
    private suspend fun readAndApplyMembers(
        account: String,
        generation: Long,
        runtimeGeneration: Int,
        prefetchedRoster: Deferred<Result<GroupRosterFfi>>?,
    ) {
        val resolvingConflict = inviteConfirmationUnresolved
        if (!resolveInitialInviteConfirmation(account, generation)) return
        val authorityEpoch = mutationPresentation.groupAuthorityEpoch
        // A prefetched roster can predate the consent check and must not settle that check.
        val roster =
            if (resolvingConflict) {
                groupRosterReader(account, group.groupIdHex)
            } else {
                prefetchedRoster?.await()?.getOrThrow() ?: groupRosterReader(account, group.groupIdHex)
            }
        memberRosterRefreshGeneration.runIfCurrent(generation) {
            if (ownsCurrentMemberRead(generation, runtimeGeneration)) {
                if (authorityEpoch != mutationPresentation.groupAuthorityEpoch) throw SupersededGroupRosterRead()
                val applied = applyGroupRoster(account, roster)
                if (applied != null) appState.applyLocalGroupDetails(account, applied.group, applied.members)
            }
        }
    }

    /**
     * Re-reads a retryable failure with bounded backoff while the read is still owned, then
     * exposes manual Retry. An account switch, newer read, disposal or runtime change wins.
     * A blocked transcript's backoff runs on [controllerScope], so it never holds the caller's
     * mutation lock or stalls the group-state stream; other retries keep the old inline re-read.
     */
    private suspend fun handleMemberReadFailure(
        account: String,
        generation: Long,
        runtimeGeneration: Int,
        failure: Throwable,
        attempt: Int,
    ) {
        val kind = classifyGroupRosterReadFailure(failure)
        if (kind == GroupRosterReadFailureKind.SUPERSEDED && !group.acceptsInviteResults()) return
        val transcriptBlocked = !hasKnownTranscriptPresentation
        val retryDelay =
            groupRosterReadRetryDelayMs(kind, attempt, transcriptBlocked)
                .takeUnless { failure.isUseAfterEviction() }
        val backgroundBackoff = transcriptBlocked && kind != GroupRosterReadFailureKind.SUPERSEDED
        if (retryDelay != null) {
            logRosterRead("retry", kind, attempt)
            if (backgroundBackoff) {
                controllerScope.launch { retryMemberReadAfter(retryDelay, generation, runtimeGeneration, attempt) }
            } else {
                retryMemberReadAfter(retryDelay, generation, runtimeGeneration, attempt)
            }
        } else {
            settleMemberReadFailure(account, generation, runtimeGeneration, failure, attempt)
        }
    }

    /** Waits [delayMs], then re-reads only if the failed read is still the current owned read. */
    private suspend fun retryMemberReadAfter(
        delayMs: Long,
        generation: Long,
        runtimeGeneration: Int,
        attempt: Int,
    ) {
        if (delayMs > 0L) delay(delayMs)
        if (ownsCurrentMemberRead(generation, runtimeGeneration)) {
            refreshMembers(automaticRetryAttempt = attempt + 1)
        }
    }

    /** Settles a failure atomically with its roster generation, without holding a lock over IO. */
    private fun settleMemberReadFailure(
        account: String,
        generation: Long,
        runtimeGeneration: Int,
        failure: Throwable,
        attempt: Int,
    ) {
        memberRosterRefreshGeneration.runIfCurrent(generation) {
            if (!ownsCurrentMemberRead(generation, runtimeGeneration)) return@runIfCurrent
            if (failure.isUseAfterEviction()) {
                markActiveAccountRemovedFromMembers(account)
            } else {
                val kind = classifyGroupRosterReadFailure(failure)
                memberRosterLoadTracker.transition(GroupRosterRefreshEvent.FAILED)
                if (memberRosterState == GroupRosterLoadState.FAILED) {
                    rosterBlockReason = GroupRosterBlockReason.ReadFailed(kind, attempts = attempt + 1)
                }
                if (inviteAcceptanceResolutionPending) inviteAcceptanceResolutionState = GroupRosterLoadState.FAILED
                logRosterRead("failed", kind, attempt)
                if (BuildConfig.DEBUG) Log.w("DMConversation", "refresh members failed", failure)
            }
        }
    }

    /** Logs a roster read branch with gate facts only, never account, group or member IDs. */
    private fun logRosterRead(
        event: String,
        kind: GroupRosterReadFailureKind?,
        attempt: Int,
    ) {
        Log.w(
            "DMConversation",
            groupRosterReadDiagnostic(
                event = event,
                kind = kind,
                attempt = attempt,
                transcriptBlocked = !hasKnownTranscriptPresentation,
                initialSnapshot = initialMemberSnapshot != null,
                targetAccountActive = conversationAccountRef == appState.activeAccountRef,
            ),
        )
    }

    /** Applies the engine's eviction proof and invalidates any in-flight group mutation result. */
    private fun markActiveAccountRemovedFromMembers(account: String) {
        val activeAccountIdHex = conversationAccountIdHex ?: return
        mutationPresentation.authoritativeGroupApplied()
        inviteConfirmationAuthority.invalidate()
        observedBlockedLeave = false
        cancelledLeaveAwaitingRoster = false
        // Engine-confirmed removal (UseAfterEviction). Record the same
        // authoritative local-left marker the leaveGroup() success path sets so
        // a later authoritative roster result that races ahead of eviction can't re-add
        // self (issue #787).
        recordSelfLeft()
        members = selfMembership.rosterHonoringSelfLeft(members, activeAccountIdHex)
        acceptedInvitePeerAccount = null
        membersLoaded = true
        membersVerified = true
        inviteAcceptanceAwaitingAuthority = null
        inviteConfirmationUnresolved = false
        memberRosterLoadTracker.transition(GroupRosterRefreshEvent.SUCCEEDED)
        rosterBlockReason = null
        // UseAfterEviction is the engine's authoritative signal that this
        // conversation can no longer accept composer writes. Invalidate an
        // in-flight immutable dictation target before any late provider result
        // can reach the draft persistence path.
        appState.conversationDictation.onTargetRemoved(account, group.groupIdHex)
        appState.cacheGroupMemberSnapshot(account, group.groupIdHex, members)
    }

    /**
     * Latch the authoritative local self-leave marker (issue #787). Set on a
     * confirmed self-leave (leaveGroup success) or engine eviction
     * ([markActiveAccountRemovedFromMembers]); honoured by [isSelfMember] and
     * roster application so a transient round-trip can't restore self
     * before the engine eviction is observed locally. A removed device
     * ([SelfMembershipFfi.DEVICE_REMOVED]) keeps its account on the roster.
     */
    private fun recordSelfLeft() {
        selfMembership.recordSelfLeft(accountStaysOnRoster = group.isDeviceRemovedCopy())
    }

    private fun Throwable.isUseAfterEviction(): Boolean {
        // Stopgap until Marmot exposes a typed UniFFI error/code for
        // GroupStateError::UseAfterEviction. Keep this in sync with the Rust
        // OpenMLS group-state error variant name.
        val text =
            generateSequence(this) { it.cause }
                .joinToString(separator = "\n") { error ->
                    listOfNotNull(error.message, error.javaClass.simpleName).joinToString(" ")
                }
        return "UseAfterEviction" in text || ("GroupStateError" in text && "eviction" in text.lowercase())
    }

    private fun applyGroupDetails(
        account: String,
        details: GroupDetailsFfi,
        freshLeaveReadback: Boolean,
    ): AppliedGroupDetails? {
        val resolution = resolveAuthoritativeGroupRoster(details, conversationAccountIdHex)
        return applyResolvedGroupRoster(
            account = account,
            resolution = resolution,
            returnedRowCount = details.members.size,
            returnedMembership = details.group.selfMembership,
            freshLeaveReadback = freshLeaveReadback,
        )
    }

    private fun applyGroupRoster(
        account: String,
        roster: GroupRosterFfi,
    ): AppliedGroupDetails? {
        val resolution = resolveAuthoritativeGroupRoster(group, roster, conversationAccountIdHex)
        return applyResolvedGroupRoster(
            account = account,
            resolution = resolution,
            returnedRowCount = roster.members.size,
            returnedMembership = roster.selfMembership,
        )
    }

    /** Applies a verified roster, or records the failed invariant that keeps the transcript gated. */
    private fun applyResolvedGroupRoster(
        account: String,
        resolution: GroupRosterResolution,
        returnedRowCount: Int,
        returnedMembership: SelfMembershipFfi,
        freshLeaveReadback: Boolean = false,
    ): AppliedGroupDetails? {
        val previousRetention = group.disappearingMessageSecs
        val previousGroup = group
        resolution.invariant?.let { invariant ->
            memberRosterLoadTracker.transition(GroupRosterRefreshEvent.INCONSISTENT)
            rosterBlockReason = GroupRosterBlockReason.Inconsistent(invariant)
            logGroupRosterInvariant(
                resolution = resolution,
                invariant = invariant,
                returnedRowCount = returnedRowCount,
                returnedMembership = returnedMembership,
            )
            return null
        }
        val applied = resolution.applied
        val restoredAfterCancel = reconcileBlockedLeaveReadback(resolution, freshLeaveReadback)
        mutationPresentation.authoritativeGroupApplied()
        val reconciled =
            if (restoredAfterCancel) applied.group else reconcileTerminalSelfMembership(applied.group, previousGroup)
        group = inviteConfirmationAuthority.reconcile(reconciled, establishesAcceptance = false)
        if (previousRetention != group.disappearingMessageSecs) {
            publishTimelineFromIndexes()
        }
        // The reconciled group record carries the newest terminal self-membership
        // observed by any snapshot. When it reports a non-member (evicted or
        // voluntarily left), latch the self-left marker so the composer goes
        // read-only and the roster line below drops self — no longer relying
        // solely on the UseAfterEviction string-match round-trip.
        if (group.selfMembership.isNonMember()) {
            recordSelfLeft()
        }
        // Once a self-leave has been recorded locally, refuse to re-add self
        // from a details round-trip that still predates the engine eviction —
        // otherwise the full roster (self included) would restore the member
        // count and re-enable the composer right after a leave (issue #787).
        members = selfMembership.rosterHonoringSelfLeft(applied.members, conversationAccountIdHex)
        if (
            members.any { member ->
                member.memberIdHex.isNotBlank() &&
                    !GroupProjector.isActiveAccountMember(member, conversationAccountIdHex)
            }
        ) {
            acceptedInvitePeerAccount = null
        }
        membersLoaded = true
        membersVerified = true
        inviteAcceptanceAwaitingAuthority = null
        if (!inviteAcceptanceResolutionPending) inviteAcceptanceResolutionState = GroupRosterLoadState.READY
        memberRosterLoadTracker.transition(GroupRosterRefreshEvent.SUCCEEDED)
        rosterBlockReason = null
        cacheAppliedGroupMembers(appState, account, group.groupIdHex, members)
        return AppliedGroupDetails(group = group, members = members)
    }

    /** Retires a provisional leave only after the current native roster proves the outcome. */
    private fun reconcileBlockedLeaveReadback(
        resolution: GroupRosterResolution,
        freshLeaveReadback: Boolean,
    ): Boolean {
        val incoming = resolution.applied.group
        if (incoming.leaveBlocked) observedBlockedLeave = true
        val restored =
            freshLeaveReadback &&
                observedBlockedLeave &&
                resolution.containsLocalMember &&
                incoming.isJoinedAfterLeave() &&
                !group.selfMembership.isGroupEviction()
        val settled =
            incoming.selfMembership.isGroupEviction() ||
                incoming.disbanded ||
                (freshLeaveReadback && incoming.hasSettledDeparture() && !resolution.containsLocalMember)
        if (restored || settled) {
            observedBlockedLeave = false
            cancelledLeaveAwaitingRoster = false
        }
        if (restored) selfMembership.clearSelfLeft()
        return restored
    }

    private fun logGroupRosterInvariant(
        resolution: GroupRosterResolution,
        invariant: GroupRosterInvariant,
        returnedRowCount: Int,
        returnedMembership: SelfMembershipFfi,
    ) {
        fun normalizedIds(rows: List<AppGroupMemberRecordFfi>): Set<String> =
            rows
                .mapNotNull { row ->
                    row.memberIdHex
                        .trim()
                        .takeIf(String::isNotEmpty)
                        ?.lowercase()
                }.toSet()

        val returnedIds = normalizedIds(resolution.applied.members)
        // Before the first successful details read, `members` is the chat-list /
        // shared snapshot seed. Compare sets here, but log counts only: member IDs
        // and the group ID are intentionally excluded from this diagnostic.
        val cachedIds = normalizedIds(members)
        val cachedContainsLocal =
            members.any { member ->
                conversationAccountIdHex?.let { accountId ->
                    member.memberIdHex.equals(accountId, ignoreCase = true)
                } ?: member.local
            }
        Log.e(
            "DMConversation",
            "joined roster invariant" +
                " reason=${invariant.name.lowercase()}" +
                " rows=$returnedRowCount" +
                " unique=${resolution.uniqueMemberCount}" +
                " mls=${resolution.mlsMemberCount}" +
                " cached_unique=${cachedIds.size}" +
                " overlap=${returnedIds.intersect(cachedIds).size}" +
                " cached_only=${cachedIds.subtract(returnedIds).size}" +
                " returned_only=${returnedIds.subtract(cachedIds).size}" +
                " returned_self=${resolution.containsLocalMember}" +
                " cached_self=$cachedContainsLocal" +
                " current_membership=${group.selfMembership.name.lowercase()}" +
                " returned_membership=${returnedMembership.name.lowercase()}",
        )
    }

    /** Returns whether committed details were applied, invalidating any roster refresh launched earlier. */
    private fun applyMutationDetails(
        account: String,
        details: GroupDetailsFfi,
        freshLeaveReadback: Boolean = false,
    ): Boolean {
        memberRosterRefreshGeneration.advance()
        val applied = applyGroupDetails(account, details, freshLeaveReadback) ?: return false
        appState.applyLocalGroupDetails(account, applied.group, applied.members)
        return true
    }

    private suspend fun watchAgentTextStream(
        account: String,
        streamId: String,
    ) {
        val text = StringBuilder()
        var subscription: AgentStreamSubscription? = null
        try {
            runCatchingCancellable {
                val streamSubscription =
                    appState.marmotIo {
                        watchAgentTextStream(
                            accountRef = account,
                            groupIdHex = group.groupIdHex,
                            streamIdHex = streamId,
                            serverCertDer = null,
                            insecureLocal = false,
                        )
                    }
                subscription = streamSubscription
                while (true) {
                    val update =
                        withContext(Dispatchers.IO) {
                            streamSubscription.next()
                        } ?: break
                    if (streamId in removedStreamIds) {
                        break
                    }
                    // When the developer toggle is on, surface every live
                    // agent-stream update as a transient inline debug row. No-op
                    // (and allocation-free past the boolean read) when off.
                    appendStreamDebugEvent(streamId, update)
                    when (update) {
                        is AgentStreamUpdateFfi.Chunk -> {
                            appendCappedAgentStreamPreview(text, update.text)
                            updateStreamPreview(streamId, text.toString(), MessageStatus.Streaming)
                        }
                        is AgentStreamUpdateFfi.Finished -> {
                            text.clear()
                            text.append(update.text)
                            // Parse once on completion only — per-chunk parsing
                            // would be an FFI round-trip per token batch for a
                            // document that's still mutating. Chunks render as
                            // plain text; the finished message gets markdown.
                            updateStreamPreview(
                                streamId,
                                text.toString(),
                                MessageStatus.Sent,
                                tokens = appState.parseMarkdownOrEmpty(update.text),
                            )
                        }
                        is AgentStreamUpdateFfi.Failed -> {
                            updateStreamPreview(streamId, copy.streamFailed(), MessageStatus.Failed)
                        }
                        // Typed Hermes-agent variants (Progress / Record / Status)
                        // are surfaced only through the streaming-debug rows above;
                        // they carry no user-visible preview text, so drop them here
                        // and let the loop keep consuming the next chunk.
                        is AgentStreamUpdateFfi.Progress,
                        is AgentStreamUpdateFfi.Record,
                        is AgentStreamUpdateFfi.Status,
                        -> Unit
                    }
                }
            }.onFailure { throwable ->
                updateStreamPreview(
                    streamId,
                    agentStreamFailureText(throwable, copy),
                    MessageStatus.Failed,
                )
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { subscription?.close() }
            }
            activeStreamIds.remove(streamId)
        }
    }

    /**
     * Surface one live agent-stream update as a transient inline streaming-debug
     * row. Each row is a display-only synthetic [TimelineMessage] tagged
     * [STREAM_DEBUG_DIRECTION] so it never inflates unread counts, marks-read, or
     * reacts; the conversation renders it as a [StreamDebugEventRow] keyed off the
     * [STREAM_DEBUG_ID_PREFIX] id. Gated entirely on
     * [WhiteNoiseAppState.streamingDebugEnabled]: a no-op (past a single boolean
     * read) when the toggle is off, so the timeline is byte-identical to today.
     * Surfaces every live Chunk / Status / Progress / Record / Finished / Failed.
     */
    private fun appendStreamDebugEvent(
        streamId: String,
        update: AgentStreamUpdateFfi,
    ) {
        if (!appState.streamingDebugEnabled) return
        val event = StreamDebugEventFormatter.of(update)
        streamDebugEventSequence += 1uL
        val now = nowSeconds()
        // Zero-pad the sequence so same-second rows keep insertion order under
        // the `id` tiebreak in `compareTimelineMessages`.
        val id = "$STREAM_DEBUG_ID_PREFIX$streamId:$now:${streamDebugEventSequence.toString().padStart(20, '0')}"
        val record =
            AppMessageRecordFfi(
                messageIdHex = id,
                direction = STREAM_DEBUG_DIRECTION,
                groupIdHex = group.groupIdHex,
                sender = inferStreamSender(streamId),
                plaintext = event.detail,
                contentTokens = EMPTY_MARKDOWN_DOCUMENT,
                kind = STREAM_DEBUG_KIND,
                tags = listOf(MessageProjector.streamTag(streamId), MessageTagFfi(listOf("dbg", event.eventKind))),
                sourceEpoch = null,
                retentionSeconds = null,
                retentionExpiresAt = null,
                recordedAt = now,
                receivedAt = now,
                sticker = null,
            )
        val item =
            TimelineMessage(
                id = id,
                record = record,
                status = MessageStatus.Sent,
                timelineOrder = nextOptimisticTimelineOrder(),
            )
        streamDebugTimelineItems[id] = item
        // Bound the retained rows: a long-lived agent-heavy conversation could
        // otherwise accrete debug rows without limit while the toggle stays on.
        var evicted = false
        while (streamDebugTimelineItems.size > MAX_STREAM_DEBUG_ROWS) {
            streamDebugTimelineItems.remove(streamDebugTimelineItems.keys.first())
            evicted = true
        }
        if (evicted) {
            // A row dropped out of the middle of the published list — only a full
            // rebuild can drop it from the timeline.
            publishTimelineFromIndexes()
        } else {
            // The new row sorts to the tail (highest recordedAt + timelineOrder),
            // so append it in place rather than re-sorting the whole timeline on
            // every stream update — the slot trick updateStreamPreview uses.
            timeline = timeline + item.withOptimisticEditStatus()
        }
    }

    /**
     * Re-publish the timeline after the streaming-debug toggle may have changed.
     * Clears the transient debug rows when the effective toggle is off so they
     * don't linger once the developer turns it back off. Called from the
     * conversation screen on the `streamingDebugEnabled` transition. When on,
     * this is a plain republish.
     */
    fun refreshStreamingDebugPresentation() {
        if (!appState.streamingDebugEnabled) {
            if (streamDebugTimelineItems.isEmpty()) return
            streamDebugTimelineItems.clear()
            streamDebugEventSequence = 0uL
        }
        publishTimelineFromIndexes()
    }

    /** Creates or updates the timeline row that shows an agent stream's latest text and status. */
    private fun updateStreamPreview(
        streamId: String,
        plaintext: String,
        status: MessageStatus,
        tokens: MarkdownDocumentFfi? = null,
    ) {
        if (streamId in removedStreamIds) return
        val id = "stream:$streamId"
        val existingItem = optimisticMessages[id] ?: timelineItemsById[id]
        val existing = existingItem?.record
        val record =
            (
                existing ?: AppMessageRecordFfi(
                    messageIdHex = "stream-$streamId",
                    direction = "received",
                    groupIdHex = group.groupIdHex,
                    sender = inferStreamSender(streamId),
                    plaintext = "",
                    contentTokens = EMPTY_MARKDOWN_DOCUMENT,
                    kind = 1200uL,
                    tags = listOf(MessageProjector.streamTag(streamId)),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = nowSeconds(),
                    receivedAt = nowSeconds(),
                    sticker = null,
                )
            ).copy(
                plaintext = plaintext,
                // Tokens must always describe the plaintext beside them.
                // When the caller didn't parse this revision (streaming
                // chunks, failure copy), reset to empty — carrying forward a
                // previous revision's tokens would render stale markdown
                // against the new text. Empty falls back to plain rendering.
                contentTokens = tokens ?: EMPTY_MARKDOWN_DOCUMENT,
            )
        val updated =
            TimelineMessage(
                id,
                record,
                status,
                timelineOrder = existingItem?.timelineOrder ?: nextOptimisticTimelineOrder(),
            )
        optimisticMessages[id] = updated
        // A streaming chunk only mutates this one item's text, and none of its
        // sort keys (recordedAt, timelineOrder, id) change across chunks — so
        // its timeline slot is fixed. Replace the slot in place instead of
        // rebuilding + re-sorting the whole timeline per chunk on the Main
        // thread. Finished/Failed (and the first chunk, which has no slot yet)
        // take the full publish so status-driven reconciliation stays on the
        // canonical path. See #145.
        val slot = if (status == MessageStatus.Streaming) timeline.indexOfFirst { it.id == id } else -1
        if (slot >= 0) {
            timeline = timeline.toMutableList().apply { set(slot, updated) }
        } else {
            publishTimelineFromIndexes()
        }
    }

    // The authoritative sender for a stream comes from the projected timeline
    // record (the kind:1200 event that introduced the streamId). If we're
    // synthesizing a preview record because no projection has landed yet,
    // walk timelineRecords for a record carrying the same stream tag — that
    // covers the common case where the kind:1200 event arrived before the
    // first chunk. Falls back to empty for the genuine cold-start window,
    // which the projection will overwrite as soon as it lands.
    private fun inferStreamSender(streamId: String): String {
        val tagValues = MessageProjector.streamTag(streamId).values
        return timelineRecords.values
            .firstOrNull { record -> record.tags.any { it.values == tagValues } }
            ?.sender
            .orEmpty()
    }

    /** Current wall-clock seconds from the controller's lifecycle-stable clock. */
    private fun nowSeconds(): ULong = (clockMillis() / 1000L).toULong()

    init {
        if (startOnConstruction) start()
    }

    companion object {
        // Streaming-debug rows. Synthetic timeline ids carry this prefix so the
        // conversation can render them as StreamDebugEventRow and so they
        // sort/key distinctly from real messages.
        internal const val STREAM_DEBUG_ID_PREFIX = "dbg:stream:"

        // Synthetic `direction` for debug rows: anything other than "received"
        // keeps them out of unread counts (firstUnreadReceivedIndex /
        // countUnreadIncoming only tally "received" rows).
        private const val STREAM_DEBUG_DIRECTION = "debug"

        // Sentinel kind for debug-row synthetic records. ULong.MAX_VALUE is not a
        // real Nostr kind; these records are identified by their id prefix and
        // never round-trip to the engine, so the value is display-only.
        private const val STREAM_DEBUG_KIND: ULong = ULong.MAX_VALUE

        // Cap retained transient debug rows while the toggle stays on, oldest-first.
        private const val MAX_STREAM_DEBUG_ROWS = 200

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
