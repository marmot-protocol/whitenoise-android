package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AgentStreamSubscription
import dev.ipf.marmotkit.AgentStreamUpdateFfi
import dev.ipf.marmotkit.AppGroupMemberIdsFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.ChatPinStateFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupManagementStateFfi
import dev.ipf.marmotkit.GroupMutationResultFfi
import dev.ipf.marmotkit.GroupPushDebugInfoFfi
import dev.ipf.marmotkit.GroupRosterFfi
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
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.ChatListMessageSearch
import dev.ipf.whitenoise.android.core.ConversationSearchMatch
import dev.ipf.whitenoise.android.core.ConversationTranscriptExport
import dev.ipf.whitenoise.android.core.ConversationTranscriptTimelineReader
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.LeaveAction
import dev.ipf.whitenoise.android.core.MediaPreviewFallback
import dev.ipf.whitenoise.android.core.MessageBodyMatch
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.core.ReplyNavigation
import dev.ipf.whitenoise.android.core.StreamDebugEventFormatter
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.core.TimelineReplyDisplay
import dev.ipf.whitenoise.android.core.aggregateEdits
import dev.ipf.whitenoise.android.core.encryptedGroupAvatarCacheKey
import dev.ipf.whitenoise.android.core.replyBodyWithTypedMediaFallback
import dev.ipf.whitenoise.android.core.replyMediaKindFromMime
import dev.ipf.whitenoise.android.core.typedReplyMediaFallback
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import dev.ipf.whitenoise.android.media.GroupImageMutationFailure
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.media.REMOVE_GROUP_IMAGE_MUTATION_KEY
import dev.ipf.whitenoise.android.media.classifyGroupImageMutationFailure
import dev.ipf.whitenoise.android.media.mutationKey
import dev.ipf.whitenoise.android.media.shouldCommitPrimaryGroupImageMutation
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageDirectChatResolution
import dev.ipf.whitenoise.android.ui.chats.newchat.directChatPreferenceOrder
import dev.ipf.whitenoise.android.ui.chats.newchat.existingDirectChatFromProvenance
import dev.ipf.whitenoise.android.ui.chats.newchat.rankedDirectChatCandidates
import dev.ipf.whitenoise.android.ui.chats.newchat.resolveExistingDirectChatCandidates
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
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
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

enum class ChatListAvatarSource {
    LEGACY_URL,
    ENCRYPTED_GROUP,
    FALLBACK_URL,
}

data class ChatListAvatarSeed(
    val source: ChatListAvatarSource,
    val key: String,
    val image: ImageBitmap,
)

internal data class ChatListMemberPresentation(
    val otherMemberAccount: String?,
    val memberCount: Int,
    val activeAccountIsSoleMember: Boolean,
)

private fun chatListMemberPresentation(
    members: List<AppGroupMemberRecordFfi>,
    activeAccountIdHex: String?,
): ChatListMemberPresentation =
    ChatListMemberPresentation(
        otherMemberAccount = GroupProjector.otherMemberAccount(members, activeAccountIdHex),
        memberCount = GroupProjector.uniqueMemberCount(members),
        activeAccountIsSoleMember = GroupProjector.isSelfSoleMember(members, activeAccountIdHex),
    )

/**
 * Group record as the chat list should display it. A row carrying any avatar
 * signal is authoritative for the whole avatar identity — a URL↔encrypted
 * switch must clear the stale half. A row with no avatar payload at all is a
 * transient projection state: keep the record's last-known identity so a
 * resolved avatar never degrades to generated initials; a genuine
 * removal still propagates through the group record itself.
 */
private fun chatListDisplayGroup(
    row: ChatListRowFfi,
    baseGroup: AppGroupRecordFfi,
): AppGroupRecordFfi {
    val rowHasAvatarSignal = row.avatarUrl != null || row.avatar != null
    val avatarUrl = if (rowHasAvatarSignal) row.avatarUrl else baseGroup.avatarUrl
    return reconcileTerminalSelfMembership(
        update =
            baseGroup.copy(
                name = row.groupName.ifBlank { baseGroup.name },
                avatarUrl = avatarUrl,
                avatarDim = baseGroup.avatarDim.takeIf { avatarUrl == baseGroup.avatarUrl },
                avatarThumbhash = baseGroup.avatarThumbhash.takeIf { avatarUrl == baseGroup.avatarUrl },
                imageHashHex = if (rowHasAvatarSignal) row.avatar?.imageHashHex else baseGroup.imageHashHex,
                archived = row.archived,
                pendingConfirmation = row.pendingConfirmation,
                selfMembership = row.selfMembership,
            ),
        previous = baseGroup,
    )
}

/**
 * Build a `ChatListItem` from the FFI projection. [members] is the current
 * authoritative roster used for membership-sensitive fields. The optional
 * [presentationMembers] supplies only last-known, display-shaped values while
 * a newer authoritative roster is loading; it never populates
 * [ChatListItem.memberSnapshot] or exposes a stale roster to callers.
 */
internal fun chatListItemFromProjection(
    row: ChatListRowFfi,
    group: AppGroupRecordFfi? = null,
    activeAccountIdHex: String? = null,
    members: List<AppGroupMemberRecordFfi>? = null,
    presentationMembers: ChatListMemberPresentation? = null,
    previewTokens: MarkdownDocumentFfi? = null,
    resolvedMediaPreviewFallback: MediaPreviewFallback? = null,
    removed: Boolean = false,
    activitySequence: ULong = 0uL,
): ChatListItem {
    val baseGroup = group ?: emptyGroupRecord(row)
    val displayGroup = chatListDisplayGroup(row, baseGroup)
    val presentation = members?.let { chatListMemberPresentation(it, activeAccountIdHex) } ?: presentationMembers
    return ChatListItem(
        group = displayGroup,
        latest =
            row.lastMessage?.let { preview ->
                AppMessageRecordFfi(
                    messageIdHex = preview.messageIdHex,
                    direction = "received",
                    groupIdHex = row.groupIdHex,
                    sender = preview.sender,
                    plaintext = preview.plaintext,
                    // Deliberately empty: the chat-list preview's markdown
                    // rides [ChatListItem.previewTokens] (parsed async by
                    // ChatsController), not this synthesized record. Parsing
                    // here would force an FFI hop into a pure projection
                    // helper.
                    contentTokens = EMPTY_MARKDOWN_DOCUMENT,
                    kind = preview.kind,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = preview.timelineAt,
                    receivedAt = preview.timelineAt,
                )
            },
        otherMemberAccount =
            members?.let { GroupProjector.otherMemberAccount(it, activeAccountIdHex) },
        memberCount = members?.let(GroupProjector::uniqueMemberCount) ?: 0,
        memberSnapshot = members?.let(::GroupMemberSnapshot),
        presentationOtherMemberAccount = presentation?.otherMemberAccount,
        presentationMemberCount = presentation?.memberCount ?: 0,
        presentationActiveAccountIsSoleMember = presentation?.activeAccountIsSoleMember == true,
        projection = row,
        previewTokens = previewTokens,
        resolvedMediaPreviewFallback = resolvedMediaPreviewFallback,
        removed = removed,
        activitySequence = activitySequence,
    )
}

internal fun rollbackOptimisticChatListPreview(
    current: ChatListRowFfi,
    previous: ChatListRowFfi,
    optimisticMessageIdHex: String,
): ChatListRowFfi =
    if (current.lastMessage?.messageIdHex == optimisticMessageIdHex) {
        previous
    } else {
        current
    }

internal fun compareTimelineAtMessageIdHex(
    leftAt: ULong,
    leftId: String,
    rightAt: ULong,
    rightId: String,
): Int {
    val atCompare = leftAt.compareTo(rightAt)
    if (atCompare != 0) return atCompare
    return leftId.compareTo(rightId)
}

private fun compareOptionalTimelineAtMessageIdHex(
    leftAt: ULong?,
    leftId: String?,
    rightAt: ULong?,
    rightId: String?,
): Int? {
    if (leftAt == null || rightAt == null) return null
    if (leftId == null || rightId == null) return null
    return compareTimelineAtMessageIdHex(leftAt, leftId, rightAt, rightId)
}

private fun monotonicMaxTimelineAt(
    current: ULong?,
    incoming: ULong?,
): ULong? {
    if (incoming == null) return current
    if (current == null) return incoming
    return maxOf(current, incoming)
}

private fun mergeMarkReadReadWatermark(
    current: ChatListRowFfi,
    incoming: ChatListRowFfi,
): Pair<ULong?, String?> {
    val incomingAt = incoming.lastReadTimelineAt
    val incomingId = incoming.lastReadMessageIdHex
    if (incomingAt == null || incomingId == null) {
        return current.lastReadTimelineAt to current.lastReadMessageIdHex
    }
    return incomingAt to incomingId
}

/**
 * Reconcile a [markTimelineMessageRead] return row with the in-memory row.
 * Returns null when the incoming projection is strictly older than what is
 * already folded (a superseded concurrent mark-read).
 */
internal fun mergeMarkReadChatListRow(
    current: ChatListRowFfi,
    incoming: ChatListRowFfi,
): ChatListRowFfi? {
    val readCompare =
        compareOptionalTimelineAtMessageIdHex(
            incoming.lastReadTimelineAt,
            incoming.lastReadMessageIdHex,
            current.lastReadTimelineAt,
            current.lastReadMessageIdHex,
        )
    if (readCompare != null && readCompare < 0) {
        return null
    }
    val incomingLast = incoming.lastMessage
    val currentLast = current.lastMessage
    if (currentLast != null && incomingLast != null) {
        val lastCompare =
            compareTimelineAtMessageIdHex(
                incomingLast.timelineAt,
                incomingLast.messageIdHex,
                currentLast.timelineAt,
                currentLast.messageIdHex,
            )
        if (lastCompare < 0) {
            val (readTimelineAt, readMessageIdHex) = mergeMarkReadReadWatermark(current, incoming)
            return reconcileReadDerivedUnread(
                current.copy(
                    lastReadMessageIdHex = readMessageIdHex,
                    lastReadTimelineAt = readTimelineAt,
                ),
            )
        }
    }
    val (readTimelineAt, readMessageIdHex) = mergeMarkReadReadWatermark(current, incoming)
    return reconcileReadDerivedUnread(
        incoming.copy(
            lastMessage = incoming.lastMessage ?: current.lastMessage,
            lastReadMessageIdHex = readMessageIdHex,
            lastReadTimelineAt = readTimelineAt,
        ),
    )
}

private fun readWatermarkCoversLastMessage(row: ChatListRowFfi): Boolean {
    val last = row.lastMessage
    val readAt = row.lastReadTimelineAt
    val readId = row.lastReadMessageIdHex
    return last != null &&
        readAt != null &&
        readId != null &&
        compareTimelineAtMessageIdHex(
            readAt,
            readId,
            last.timelineAt,
            last.messageIdHex,
        ) >= 0
}

private fun hasReadDerivedUnread(row: ChatListRowFfi): Boolean =
    when {
        row.unreadCount > 0uL -> true
        row.hasUnread -> true
        row.firstUnreadMessageIdHex != null -> true
        row.unreadMentionCount > 0uL -> true
        else -> row.unreadMention
    }

private fun reconcileReadDerivedUnread(incoming: ChatListRowFfi): ChatListRowFfi =
    if (
        readWatermarkCoversLastMessage(incoming) &&
        hasReadDerivedUnread(incoming)
    ) {
        incoming.copy(
            unreadCount = 0uL,
            hasUnread = false,
            firstUnreadMessageIdHex = null,
            unreadMentionCount = 0uL,
            unreadMention = false,
        )
    } else {
        incoming
    }

/**
 * Field-wise reducer for live chat-list subscription rows. Subscription rows
 * are ordered full projections, so all non-read fields are authoritative — in
 * particular, a deletion may move [ChatListRowFfi.lastMessage] backwards or to
 * null. Only the read-dependent fields can be stale when a synchronous
 * [markTimelineMessageRead] return races a previously queued subscription row.
 */
internal fun reduceSubscriptionChatListRow(
    current: ChatListRowFfi,
    incoming: ChatListRowFfi,
    trigger: ChatListUpdateTriggerFfi,
): ChatListRowFfi {
    val incomingReadComplete = incoming.lastReadTimelineAt != null && incoming.lastReadMessageIdHex != null
    val currentReadComplete = current.lastReadTimelineAt != null && current.lastReadMessageIdHex != null
    val incomingReadCompare =
        compareOptionalTimelineAtMessageIdHex(
            incoming.lastReadTimelineAt,
            incoming.lastReadMessageIdHex,
            current.lastReadTimelineAt,
            current.lastReadMessageIdHex,
        )
    val incomingReadTrusted = incomingReadComplete && (!currentReadComplete || incomingReadCompare!! >= 0)
    if (incomingReadTrusted) return reconcileReadDerivedUnread(incoming)

    val newLastMessage = incoming.lastMessage
    val currentLastMessage = current.lastMessage
    val distinctNewLastMessage = observesDistinctNewLastMessage(current, incoming, trigger)
    val advancesLastMessage =
        newLastMessage != null &&
            (
                currentLastMessage == null ||
                    newLastMessage.timelineAt > currentLastMessage.timelineAt ||
                    (distinctNewLastMessage && newLastMessage.timelineAt == currentLastMessage.timelineAt)
            )
    val advancesPastRead =
        newLastMessage != null &&
            (
                !currentReadComplete ||
                    newLastMessage.timelineAt > current.lastReadTimelineAt!! ||
                    (distinctNewLastMessage && newLastMessage.timelineAt == current.lastReadTimelineAt)
            )
    val addsUnread =
        trigger == ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE &&
            advancesLastMessage &&
            advancesPastRead &&
            incoming.unreadCount > current.unreadCount

    val unreadCount =
        if (addsUnread && current.unreadCount != ULong.MAX_VALUE) {
            current.unreadCount + 1uL
        } else {
            current.unreadCount
        }
    val unreadMentionCount =
        if (
            addsUnread &&
            incoming.unreadMentionCount > current.unreadMentionCount &&
            current.unreadMentionCount != ULong.MAX_VALUE
        ) {
            current.unreadMentionCount + 1uL
        } else {
            current.unreadMentionCount
        }
    return incoming.copy(
        lastReadTimelineAt = current.lastReadTimelineAt,
        lastReadMessageIdHex = current.lastReadMessageIdHex,
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0uL,
        firstUnreadMessageIdHex =
            if (addsUnread && current.unreadCount == 0uL) {
                newLastMessage.messageIdHex
            } else {
                current.firstUnreadMessageIdHex
            },
        unreadMentionCount = unreadMentionCount,
        unreadMention = unreadMentionCount > 0uL,
    )
}

/**
 * Fold a successful [markTimelineMessageRead] row into the active chat list.
 * Does not gate on [ConversationController.lastReadMessageId]: concurrent
 * mark-read calls can complete out of order, and monotonic tuple merge rejects
 * rows that are already superseded.
 */
internal fun foldMarkReadReturnedRow(
    row: ChatListRowFfi,
    persistedLastReadTimelineAt: ULong?,
    applyChatListRow: (ChatListRowFfi) -> Unit,
): ULong? {
    applyChatListRow(row)
    return monotonicMaxTimelineAt(persistedLastReadTimelineAt, row.lastReadTimelineAt)
}

/**
 * The last-message text a chat row should run through the markdown parser,
 * or null when the row's preview line will show fallback copy instead of
 * the message body. Mirrors [ChatListItem.projectedPreviewText]'s generic
 * message-body arm exactly: a non-deleted row whose plaintext is non-blank
 * and whose kind is not one of the special-cased arms is rendered verbatim,
 * so its body — and only its body — may be parsed into preview tokens.
 * Edit (1009), agent-stream-start (1200), and group-system (1210) rows —
 * plus deleted/blank rows — surface derived copy, so their payloads must
 * never be parsed into preview tokens and styled in their place (issue #577).
 * Body kinds beyond plain chat (kind-1 legacy notes, kind-1209 agent-stream
 * finals, and any future body kind) still display their plaintext via
 * `projectedPreviewText`, so they keep markdown/mention/code rendering here.
 * Delegating the kind test to [MessageProjector.rendersRawBodyPreview] ties
 * this parse gate to the same plaintext `projectedPreviewText` would surface.
 */
internal fun chatRowPreviewMarkdownSource(row: ChatListRowFfi): String? {
    val preview = row.lastMessage ?: return null
    if (preview.deleted) return null
    if (!MessageProjector.rendersRawBodyPreview(preview.kind)) return null
    return preview.plaintext.takeIf { it.isNotBlank() }
}

/**
 * Message id of a row whose chat-list preview body is blank and must be
 * resolved from the local timeline before a typed media label can render.
 */
internal fun chatRowNeedsMediaKindResolve(row: ChatListRowFfi): String? {
    val preview = row.lastMessage ?: return null
    if (preview.deleted) return null
    if (preview.kind != 9uL) return null
    if (preview.plaintext.isNotBlank()) return null
    // The engine's typed attachment projection already labels this preview —
    // no local timeline read needed.
    if (preview.attachmentKind != null) return null
    return preview.messageIdHex.takeIf { it.isNotBlank() }
}

/**
 * Next optimistic timelineOrder: one past the max across both the published
 * timeline and the in-flight optimistic items. Including `pending` is what
 * stops back-to-back optimistic sends from colliding while a publish is still
 * coalescing (the published list is stale in that window). See #225.
 */
internal fun nextTimelineOrder(
    published: Sequence<ULong>,
    pending: Sequence<ULong>,
): ULong = (published + pending).maxOrNull()?.plus(1uL) ?: 1uL

data class GroupMemberSnapshot(
    val members: List<AppGroupMemberRecordFfi>,
) {
    val memberCount: Int = GroupProjector.uniqueMemberCount(members)

    /**
     * Case-folded member ids for set-membership rules. Folded once per roster
     * so evaluating F folder rules over this chat stops allocating F lowercase
     * copies of every member id.
     */
    internal val foldedMemberIds: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        members.mapTo(HashSet(members.size)) { it.memberIdHex.lowercase(Locale.ROOT) }
    }

    fun otherMemberAccount(activeAccountIdHex: String?): String? = GroupProjector.otherMemberAccount(members, activeAccountIdHex)

    fun containsAccount(accountIdHex: String): Boolean {
        val normalized = accountIdHex.trim().takeIf { it.isNotEmpty() } ?: return false
        return members.any { it.memberIdHex.equals(normalized, ignoreCase = true) }
    }
}

internal suspend fun loadGroupMemberIdsPages(
    groupIds: Iterable<String>,
    loadPage: suspend (List<String>) -> List<AppGroupMemberIdsFfi>,
): List<AppGroupMemberIdsFfi> {
    val requested =
        groupIds
            .asSequence()
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .toList()
    val loaded = ArrayList<AppGroupMemberIdsFfi>(requested.size)
    requested.chunked(GROUP_MEMBER_IDS_PAGE_SIZE).forEach { page ->
        val projections = loadPage(page)
        check(projections.size == page.size) {
            "member-id page returned ${projections.size} rows for ${page.size} groups"
        }
        page.zip(projections).forEach { (requestedGroupId, projection) ->
            check(projection.groupIdHex.equals(requestedGroupId, ignoreCase = true)) {
                "member-id page returned a different group than requested"
            }
            loaded += projection.copy(groupIdHex = requestedGroupId)
        }
    }
    return loaded
}

internal data class FirstFrameMemberFallbackResult<T>(
    val groupIdHex: String,
    val result: Result<T>,
)

internal data class FirstFrameMemberFallbackBatch<T>(
    val firstFrameResults: List<FirstFrameMemberFallbackResult<T>>,
    val remainingResults: Channel<FirstFrameMemberFallbackResult<T>>,
    val remainingCount: Int,
)

internal fun initialMemberFallbackGenerationIsCurrent(
    expectedAccount: String,
    expectedBindEpoch: Long,
    expectedCacheEpoch: Long,
    currentAccount: String?,
    currentBindEpoch: Long,
    currentCacheEpoch: Long,
    lifecycleActive: Boolean,
): Boolean =
    lifecycleActive &&
        currentAccount == expectedAccount &&
        currentBindEpoch == expectedBindEpoch &&
        currentCacheEpoch == expectedCacheEpoch

/**
 * Start one lifecycle-child local read per distinct group, bounded by
 * [maxConcurrent], and return the results available within [cutoffMillis].
 * Workers that miss the cutoff keep running under the caller's coroutine job;
 * [remainingResults] lets the owner fold those late answers in without
 * restarting any group read.
 */
internal suspend fun <T> loadFirstFrameMemberFallback(
    groupIds: Iterable<String>,
    cutoffMillis: Long,
    maxConcurrent: Int,
    nowMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
    load: suspend (String) -> T,
): FirstFrameMemberFallbackBatch<T> {
    require(cutoffMillis >= 0L)
    require(maxConcurrent > 0)
    val requested =
        groupIds
            .asSequence()
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .toList()
    val results = Channel<FirstFrameMemberFallbackResult<T>>(requested.size)
    if (requested.isEmpty()) {
        return FirstFrameMemberFallbackBatch(emptyList(), results, 0)
    }

    val gate = Semaphore(maxConcurrent)
    val lifecycleScope = CoroutineScope(coroutineContext)
    requested.forEach { groupIdHex ->
        lifecycleScope.launch {
            val result = runCatchingCancellable { gate.withPermit { load(groupIdHex) } }
            results.send(FirstFrameMemberFallbackResult(groupIdHex, result))
        }
    }

    val cutoffStartedAtMillis = nowMillis()
    val firstFrame = mutableListOf<FirstFrameMemberFallbackResult<T>>()
    var remaining = requested.size
    while (remaining > 0) {
        val completed = results.tryReceive().getOrNull()
        if (completed != null) {
            firstFrame += completed
            remaining -= 1
        } else {
            val elapsedMillis = (nowMillis() - cutoffStartedAtMillis).coerceAtLeast(0L)
            val waitMillis = cutoffMillis - elapsedMillis
            if (waitMillis <= 0L) break
            // Never suspend inside a channel receive: prompt cancellation may
            // consume an element before the caller records it. A short bounded
            // delay leaves every result either synchronously drained above or
            // available to the lifecycle-bound late collector.
            delay(minOf(waitMillis, FIRST_FRAME_FALLBACK_POLL_MILLIS))
        }
    }
    return FirstFrameMemberFallbackBatch(firstFrame, results, remaining)
}

/**
 * Return the projected counterparty ids whose locally persisted presentation
 * must be materialized before the first cached chat-list frame. The caller
 * supplies the direct-conversation classifier so this stays independent from
 * the heavier chat-list row model and remains straightforward to test.
 */
internal fun initialDirectPeerProfileIds(
    projections: Iterable<AppGroupMemberIdsFfi>,
    activeAccountIdHex: String?,
    isDirectConversation: (groupIdHex: String, memberCount: Int) -> Boolean,
): List<String> {
    val active = activeAccountIdHex?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    return projections
        .asSequence()
        .mapNotNull { projection ->
            val memberIds =
                projection.memberIdsHex
                    .asSequence()
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase() }
                    .toList()
            if (!isDirectConversation(projection.groupIdHex, memberIds.size)) return@mapNotNull null
            memberIds.singleOrNull { !it.equals(active, ignoreCase = true) }
        }.distinctBy { it.lowercase() }
        .toList()
}

internal fun memberRecordsFromIds(
    memberIdsHex: Iterable<String>,
    activeAccountIdHex: String?,
): List<AppGroupMemberRecordFfi> =
    memberIdsHex
        .asSequence()
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
        .map { memberIdHex ->
            AppGroupMemberRecordFfi(
                memberIdHex = memberIdHex,
                account = null,
                local = memberIdHex.equals(activeAccountIdHex, ignoreCase = true),
            )
        }.toList()

internal fun sharedChatListItemsWith(
    items: Iterable<ChatListItem>,
    targetAccountIdHex: String,
    activeAccountIdHex: String?,
): List<ChatListItem> {
    val active = activeAccountIdHex?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val target = targetAccountIdHex.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
    return items
        .filter { item ->
            val snapshot = item.memberSnapshot ?: return@filter false
            snapshot.containsAccount(active) && snapshot.containsAccount(target)
        }.distinctBy { it.group.groupIdHex.lowercase() }
}

internal fun profileAddableGroupItems(
    items: Iterable<ChatListItem>,
    targetAccountIdHex: String,
    activeAccountIdHex: String?,
): List<ChatListItem> =
    profileAddableGroupsState(
        items = items,
        targetAccountIdHex = targetAccountIdHex,
        activeAccountIdHex = activeAccountIdHex,
    ).groups

internal enum class ProfileGroupPickerLoadState {
    READY,
    LOADING,
    FAILED,
}

internal data class ProfileGroupPickerState(
    val groups: List<ChatListItem>,
    val pendingGroupIds: Set<String>,
    val loadState: ProfileGroupPickerLoadState,
) {
    companion object {
        fun empty(): ProfileGroupPickerState =
            ProfileGroupPickerState(
                groups = emptyList(),
                pendingGroupIds = emptySet(),
                loadState = ProfileGroupPickerLoadState.READY,
            )
    }
}

internal fun profileAddableGroupsState(
    items: Iterable<ChatListItem>,
    targetAccountIdHex: String,
    activeAccountIdHex: String?,
    failedGroupIds: Set<String> = emptySet(),
): ProfileGroupPickerState {
    val (active, target) =
        profileGroupPickerAccounts(activeAccountIdHex, targetAccountIdHex)
            ?: return ProfileGroupPickerState.empty()
    return profileGroupPickerState(
        candidates = profileManagedGroupCandidates(items, active),
        failedGroupIds = failedGroupIds,
    ) { _, snapshot ->
        snapshot.containsAccount(active) && !snapshot.containsAccount(target)
    }
}

internal fun profilePromotableGroupsState(
    items: Iterable<ChatListItem>,
    targetAccountIdHex: String,
    activeAccountIdHex: String?,
    failedGroupIds: Set<String> = emptySet(),
): ProfileGroupPickerState {
    val (active, target) =
        profileGroupPickerAccounts(activeAccountIdHex, targetAccountIdHex)
            ?: return ProfileGroupPickerState.empty()
    return profileGroupPickerState(
        candidates = profileManagedGroupCandidates(items, active),
        failedGroupIds = failedGroupIds,
    ) { item, snapshot ->
        snapshot.containsAccount(active) &&
            snapshot.containsAccount(target) &&
            !GroupProjector.isAdminRef(item.group, target)
    }
}

private fun profileGroupPickerAccounts(
    activeAccountIdHex: String?,
    targetAccountIdHex: String,
): Pair<String, String>? {
    val active = activeAccountIdHex?.trim().orEmpty()
    val target = targetAccountIdHex.trim()
    return if (active.isEmpty() || target.isEmpty() || active.equals(target, ignoreCase = true)) {
        null
    } else {
        active to target
    }
}

private fun profileManagedGroupCandidates(
    items: Iterable<ChatListItem>,
    activeAccountIdHex: String,
): List<ChatListItem> =
    items
        .filter { item ->
            !item.group.pendingConfirmation &&
                !item.removedFromGroup(activeAccountIdHex) &&
                !item.isDm() &&
                GroupProjector.isAdminRef(item.group, activeAccountIdHex)
        }.distinctBy { it.group.groupIdHex.lowercase() }

private fun profileGroupPickerState(
    candidates: List<ChatListItem>,
    failedGroupIds: Set<String>,
    eligible: (ChatListItem, GroupMemberSnapshot) -> Boolean,
): ProfileGroupPickerState {
    val groups = candidates.filter { item -> item.memberSnapshot?.let { eligible(item, it) } == true }
    val pendingGroupIds =
        candidates
            .filter { it.memberSnapshot == null }
            .mapTo(linkedSetOf()) { it.group.groupIdHex }
    val normalizedFailures = failedGroupIds.mapTo(mutableSetOf()) { it.lowercase() }
    val loadState =
        when {
            pendingGroupIds.any { it.lowercase() in normalizedFailures } -> ProfileGroupPickerLoadState.FAILED
            pendingGroupIds.isNotEmpty() -> ProfileGroupPickerLoadState.LOADING
            else -> ProfileGroupPickerLoadState.READY
        }
    return ProfileGroupPickerState(groups, pendingGroupIds, loadState)
}

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

enum class MessageStatus {
    Received,
    Pending,
    Sent,
    Failed,
    Streaming,
}

enum class OutgoingMessageIndicator {
    Sending,
    Sent,
    Failed,
}

/**
 * Chat-list delivery tick for the row's projected last message. The engine
 * scopes the state itself: NOT_APPLICABLE covers incoming messages, so no
 * sender comparison is needed here.
 */
fun ChatListMessageDeliveryStateFfi.outgoingIndicator(): OutgoingMessageIndicator? =
    when (this) {
        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE -> null
        ChatListMessageDeliveryStateFfi.PENDING -> OutgoingMessageIndicator.Sending
        ChatListMessageDeliveryStateFfi.DELIVERED -> OutgoingMessageIndicator.Sent
        ChatListMessageDeliveryStateFfi.FAILED -> OutgoingMessageIndicator.Failed
    }

fun MessageStatus.outgoingIndicator(): OutgoingMessageIndicator? =
    when (this) {
        MessageStatus.Pending -> OutgoingMessageIndicator.Sending
        MessageStatus.Received,
        MessageStatus.Sent,
        -> OutgoingMessageIndicator.Sent
        MessageStatus.Failed -> OutgoingMessageIndicator.Failed
        MessageStatus.Streaming -> null
    }

// Immutable so MessageBubble's `item` param is stable and bubbles can skip
// recomposition; the UniFFI record types are all-val data classes but carry
// no Compose stability information on their own. See #110.
@Immutable
data class TimelineMessage(
    val id: String,
    val record: AppMessageRecordFfi,
    val status: MessageStatus,
    val projected: TimelineMessageRecordFfi? = null,
    val timelineOrder: ULong = 0uL,
    /** Position supplied by MDK's authoritative bounded timeline window. */
    val authoritativeOrder: ULong? = null,
    /**
     * Immediate durable stream parent whose rendered row this row must follow.
     * Null for ordinary authoritative rows and unanchored local overlays.
     */
    val displayAfterMessageIdHex: String? = null,
    /**
     * Immutable retention snapshot captured when this local send was accepted.
     * It keeps the waiting timer accurate and supplies the bounded local expiry
     * fallback only until MarmotKit projects the authoritative source-epoch
     * duration and deadline.
     */
    val retentionAtSendSeconds: ULong? = null,
)

/**
 * Whether two timeline records would render the same bubble. Ephemeral
 * observation/order timestamps are deliberately ignored, while every
 * user-visible projection — including typed media — participates.
 */
internal fun timelineRecordsRenderEqual(
    a: TimelineMessageRecordFfi,
    b: TimelineMessageRecordFfi,
): Boolean = timelineRecordEnvelopeEqual(a, b) && timelineRecordContentEqual(a, b)

private fun timelineRecordEnvelopeEqual(
    a: TimelineMessageRecordFfi,
    b: TimelineMessageRecordFfi,
): Boolean =
    a.messageIdHex == b.messageIdHex &&
        a.sourceMessageIdHex == b.sourceMessageIdHex &&
        a.direction == b.direction &&
        a.groupIdHex == b.groupIdHex &&
        a.sender == b.sender &&
        a.kind == b.kind

private fun timelineRecordContentEqual(
    a: TimelineMessageRecordFfi,
    b: TimelineMessageRecordFfi,
): Boolean =
    a.plaintext == b.plaintext &&
        markdownDocumentsRenderEqual(a.contentTokens, b.contentTokens) &&
        a.tags == b.tags &&
        a.replyToMessageIdHex == b.replyToMessageIdHex &&
        a.replyPreview == b.replyPreview &&
        a.mediaJson == b.mediaJson &&
        a.media == b.media &&
        a.agentTextStreamJson == b.agentTextStreamJson &&
        a.deleted == b.deleted &&
        a.deletedByMessageIdHex == b.deletedByMessageIdHex &&
        a.invalidationStatus == b.invalidationStatus &&
        a.retentionSeconds == b.retentionSeconds &&
        a.retentionExpiresAt == b.retentionExpiresAt &&
        a.reactions == b.reactions

private fun markdownDocumentsRenderEqual(
    a: MarkdownDocumentFfi,
    b: MarkdownDocumentFfi,
): Boolean =
    a.truncated == b.truncated &&
        a.blocks == b.blocks &&
        a.blankLinesBefore.contentEquals(b.blankLinesBefore)

/**
 * Local optimistic state for an in-flight edit of one's own message: the new
 * body to display immediately and whether the kind-1009 publish is still
 * [MessageStatus.Pending] or has [MessageStatus.Failed]. [preEditText] is the
 * body shown before the edit (the latest applied version, or the original
 * plaintext) so a failure can revert the bubble verbatim.
 */
@Immutable
data class OptimisticEdit(
    val text: String,
    val preEditText: String,
    val status: MessageStatus,
)

/**
 * Preserve a failed optimistic text send as a local timeline row instead of
 * deleting the user's draft on publish failure. The failed row keeps the same
 * temp id/order so retry/delete affordances can operate on the live optimistic
 * state and the body remains copyable from the bubble.
 */
internal fun retainFailedOptimisticTextSend(
    optimisticMessages: MutableMap<String, TimelineMessage>,
    messageById: MutableMap<String, AppMessageRecordFfi>,
    key: String,
    optimistic: AppMessageRecordFfi,
    timelineOrder: ULong,
) {
    val retentionAtSendSeconds = optimisticMessages[key]?.retentionAtSendSeconds
    messageById[optimistic.messageIdHex] = optimistic
    optimisticMessages[key] =
        TimelineMessage(
            key,
            optimistic,
            MessageStatus.Failed,
            timelineOrder = timelineOrder,
            retentionAtSendSeconds = retentionAtSendSeconds,
        )
}

/**
 * Keep a local send-time retention hint only while it can bridge an actual
 * optimistic handoff or the conversation still has retention enabled. A null
 * authoritative projection after retention is disabled must retire any
 * remembered hint instead of re-seeding it from the previous UI row.
 */
internal fun retentionHintForProjection(
    projectedRetentionSeconds: ULong?,
    currentGroupRetentionSeconds: ULong,
    optimisticSnapshot: ULong?,
    rememberedSnapshot: ULong?,
): ULong? =
    when {
        projectedRetentionSeconds != null -> null
        optimisticSnapshot != null -> optimisticSnapshot
        currentGroupRetentionSeconds == 0uL -> null
        else -> rememberedSnapshot
    }

/**
 * Trim [messageById] to the records still referenced by the loaded window
 * ([windowIds]) or by in-flight [optimisticMessages], dropping projected records
 * that have scrolled out of the window.
 *
 * [messageById] holds full decrypted records (plaintext + parsed tokens). The
 * live Projection/Upsert path adds one per delivered message but never trims, so
 * a long-lived busy conversation accumulates decrypted content far beyond the
 * rendered window — a memory-growth and privacy-footprint issue (#373). This is
 * the same retain-set the window-replace path already applies on page load (#68).
 */
internal fun pruneMessageByIdToWindow(
    messageById: MutableMap<String, AppMessageRecordFfi>,
    windowIds: Set<String>,
    optimisticMessages: Collection<TimelineMessage>,
) {
    val retain = HashSet(windowIds)
    optimisticMessages.forEach { retain.add(it.record.messageIdHex) }
    messageById.keys.retainAll(retain)
}

internal fun retainedMessageOverlayTargets(
    timelineMessageIds: Collection<String>,
    optimisticMessageIds: Collection<String>,
    overlayTargetIds: Collection<String>,
): Set<String> {
    val present = HashSet(timelineMessageIds)
    present.addAll(optimisticMessageIds)
    return overlayTargetIds.filterTo(linkedSetOf()) { it in present }
}

data class ReactionParticipant(
    val sender: String,
    val emoji: String,
    val reactedAt: ULong,
)

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

private const val AGENT_STREAM_PREVIEW_MAX_CHARS = 16 * 1024

internal fun appendCappedAgentStreamPreview(
    text: StringBuilder,
    chunk: String,
    maxChars: Int = AGENT_STREAM_PREVIEW_MAX_CHARS,
) {
    if (maxChars <= 0) {
        text.clear()
        return
    }
    if (chunk.length >= maxChars) {
        text.clear()
        text.append(chunk.takeLast(maxChars))
        return
    }
    text.append(chunk)
    if (text.length > maxChars) {
        text.delete(0, text.length - maxChars)
    }
}

internal data class StreamFinalDisplayPosition(
    val recordedAt: ULong,
    val timelineOrder: ULong,
    val afterMessageId: String? = null,
)

internal fun anchoredStreamDisplayPosition(
    position: StreamFinalDisplayPosition,
    parentRecordedAt: ULong,
    parentTimelineOrder: ULong,
): StreamFinalDisplayPosition {
    val remainingOrder = ULong.MAX_VALUE - parentTimelineOrder
    val resolvedOrder =
        if (position.timelineOrder > remainingOrder) ULong.MAX_VALUE else parentTimelineOrder + position.timelineOrder
    return position.copy(recordedAt = parentRecordedAt, timelineOrder = resolvedOrder)
}

internal fun resolvedDurableStreamDisplayPosition(
    candidate: StreamFinalDisplayPosition,
    parentRecordedAt: ULong,
    parentTimelineOrder: ULong,
): StreamFinalDisplayPosition? =
    candidate
        .takeIf { it.recordedAt <= parentRecordedAt }
        ?.let {
            anchoredStreamDisplayPosition(
                position = it,
                parentRecordedAt = parentRecordedAt,
                parentTimelineOrder = parentTimelineOrder,
            )
        }

/**
 * Derive reload-stable ordering candidates for stream starts and finals from
 * their durable relationship chain:
 * final `stream-start` -> kind-1200 start `parent` -> prompt.
 *
 * The page is indexed before any record is projected, so skewed stream records
 * may precede their prompt in the engine's raw timestamp order without making
 * this result iteration-order dependent. The controller compares each child's
 * raw timestamp with its parent's effective local position before applying a
 * candidate. We intentionally do not infer a prompt when either durable link
 * is absent: nearest-message and receive-time guesses break historical sync
 * ordering.
 */
internal fun durableStreamDisplayPositions(records: List<TimelineMessageRecordFfi>): Map<String, StreamFinalDisplayPosition> {
    val recordsById = records.associateBy(TimelineMessageRecordFfi::messageIdHex)
    val positions = linkedMapOf<String, StreamFinalDisplayPosition>()
    val starts = mutableMapOf<String, TimelineMessageRecordFfi>()

    for (startRecord in records) {
        if (startRecord.kind != 1200uL || timelineTagValue(startRecord, "stream") == null) continue
        val parentId = timelineTagValue(startRecord, "parent") ?: continue
        val parentRecord = recordsById[parentId] ?: continue
        if (parentRecord.kind != 9uL || parentRecord.groupIdHex != startRecord.groupIdHex) continue
        starts[startRecord.messageIdHex] = startRecord
        positions[startRecord.messageIdHex] =
            StreamFinalDisplayPosition(
                recordedAt = startRecord.timelineAt,
                timelineOrder = 1uL,
                afterMessageId = parentRecord.messageIdHex,
            )
    }

    for (finalRecord in records) {
        if (finalRecord.kind != 9uL) continue
        val streamId = timelineTagValue(finalRecord, "stream") ?: continue
        val startId = timelineTagValue(finalRecord, "stream-start") ?: continue
        val startRecord = starts[startId] ?: continue
        if (
            startRecord.groupIdHex != finalRecord.groupIdHex ||
            startRecord.sender != finalRecord.sender ||
            timelineTagValue(startRecord, "stream") != streamId
        ) {
            continue
        }
        positions[finalRecord.messageIdHex] =
            StreamFinalDisplayPosition(
                recordedAt = finalRecord.timelineAt,
                timelineOrder = 1uL,
                afterMessageId = startRecord.messageIdHex,
            )
    }
    return positions
}

private fun timelineTagValue(
    record: TimelineMessageRecordFfi,
    name: String,
): String? =
    record.tags
        .firstOrNull { it.values.firstOrNull() == name }
        ?.values
        ?.getOrNull(1)

/**
 * A stream final replaces the already displayed preview bubble. Keep that
 * bubble's display position instead of adopting the bridge-authored final
 * timestamp, which may be earlier because the bridge and phone clocks differ.
 */
internal fun streamFinalDisplayPosition(
    finalRecord: AppMessageRecordFfi,
    displayedStream: TimelineMessage?,
): StreamFinalDisplayPosition? {
    if (!MessageProjector.isStreamFinal(finalRecord)) return null
    val streamId = MessageProjector.streamId(finalRecord) ?: return null
    val displayed = displayedStream?.takeIf { it.id == "stream:$streamId" } ?: return null
    return StreamFinalDisplayPosition(
        recordedAt = displayed.record.recordedAt,
        timelineOrder = displayed.timelineOrder,
    )
}

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

private suspend fun decodeMediaThumbnailOffMain(plaintextBytes: ByteArray) =
    withContext(Dispatchers.Default) {
        MediaPipeline.decodeSampledBitmap(
            plaintextBytes,
            MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
        )
    }

/**
 * Shared local group wipe used by chat-list Delete and sole-member Leave flows.
 * The engine drops its own rows/secrets, but Android owns decrypted media caches
 * and tray notifications, so clear those before the group references disappear.
 */
private suspend fun WhiteNoiseAppState.deleteGroupLocalWithClientCleanup(
    account: String,
    groupIdHex: String,
) {
    conversationDictation.onTargetRemoved(account, groupIdHex)
    evictGroupMediaCaches(account, groupIdHex)
    deleteDraftBeforeGroupRemoval(account, groupIdHex)
    marmotIo { deleteGroupLocal(account, groupIdHex) }
    dismissConversationNotifications(account, groupIdHex)
}

private suspend fun WhiteNoiseAppState.evictGroupMediaCaches(
    account: String,
    groupIdHex: String,
) {
    val media =
        runCatchingCancellable { marmotIo { listMedia(account, groupIdHex, null) } }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return
    val cacheKeys =
        media.map { rec ->
            mediaCacheKey(account, groupIdHex, rec.messageIdHex, rec.attachmentIndex.toInt())
        }
    // ByteSizeLruCache is backed by a non-thread-safe LinkedHashMap. Keep the
    // in-memory L1 removals main-confined even though the disk L2 eviction below
    // correctly runs on IO.
    removeMediaMemoryCacheKeys(
        cacheKeys = cacheKeys,
        dispatcher = Dispatchers.Main.immediate,
        removeEntry = ::removeMediaMemoryCacheEntry,
    )
    val tags = media.mapNotNull { it.reference.ciphertextSha256 }.toSet()
    withContext(Dispatchers.IO) {
        cacheKeys.forEach { diskMediaCache.remove(it) }
        if (tags.isNotEmpty()) diskMediaCache.removeByCiphertextTags(tags)
    }
}

internal fun optimisticMessageIdForProjection(
    optimisticMessages: Collection<TimelineMessage>,
    projected: AppMessageRecordFfi,
    allowDelayedProjection: Boolean = false,
): String? {
    // Bridge fast-path: `performMediaUpload` inserts an optimistic entry whose
    // `record.messageIdHex` is the confirmed event id returned by
    // `sendMediaAttachments`. That id equals the projection's id once the relay
    // echo arrives, so this pairing is exact — no need to fall back to the
    // shape-heuristic below, which would otherwise pick up a sibling pending
    // optimistic with the same `_media_pending` shape (the multi-document send
    // case where N bubbles share direction/sender/kind/recordedAt).
    optimisticMessages
        .firstOrNull { it.record.messageIdHex == projected.messageIdHex }
        ?.let { return it.record.messageIdHex }
    val projectedIsMedia = projected.tags.any { it.values.firstOrNull() == "imeta" }
    // When the projection is for a media send and multiple `_media_pending`
    // optimistics are in flight, the shape heuristic below can't safely pick
    // which sibling to reconcile — they all carry the same direction/kind/
    // sender/recordedAt and `optimisticMessages` is a `SnapshotStateMap`
    // with no insertion-order guarantee. Refuse the match and let
    // `performMediaUpload`'s bridge insert (keyed at `msg:$confirmedId`)
    // resolve the projection on the next publish pass via the fast-path
    // above. distinctBy in `publishTimelineFromIndexes` collapses the
    // transient duplicate (bridge vs. projected) deterministically.
    if (projectedIsMedia) {
        val pendingMediaCount =
            optimisticMessages.count {
                isSendableOptimisticStatus(it.status, allowDelayedProjection) &&
                    it.record.tags.any { tag -> tag.values.firstOrNull() == "_media_pending" }
            }
        if (pendingMediaCount > 1) return null
    }
    return optimisticMessages
        .firstOrNull { optimistic ->
            if (!isSendableOptimisticStatus(optimistic.status, allowDelayedProjection)) return@firstOrNull false
            if (optimistic.record.direction != projected.direction) return@firstOrNull false
            if (optimistic.record.groupIdHex != projected.groupIdHex) return@firstOrNull false
            if (!optimistic.record.sender.equals(projected.sender, ignoreCase = true)) return@firstOrNull false
            if (optimistic.record.kind != projected.kind) return@firstOrNull false
            val timestampsOk =
                timestampsAreNear(optimistic.record.recordedAt, projected.recordedAt) ||
                    (allowDelayedProjection && projected.recordedAt >= optimistic.record.recordedAt)
            if (!timestampsOk) return@firstOrNull false

            // Media-pending optimistic ↔ media projection: the pending bubble
            // carries plaintext = "📎 filename" + the "_media_pending" sentinel
            // tag, while the projection carries plaintext = caption (often
            // empty) + the real imeta tag. Plain field-by-field equality won't
            // match. The sentinel tag on the optimistic side plus an imeta tag
            // on the projection side, combined with the identity fields above,
            // uniquely identifies the pending → confirmed pair.
            val optimisticIsMediaPending = optimistic.record.tags.any { it.values.firstOrNull() == "_media_pending" }
            if (optimisticIsMediaPending && projectedIsMedia) return@firstOrNull true

            // Standard match for text sends: plaintext equal, and tags equal
            // ignoring engine-derived `p` (mention) tags. The optimistic record is
            // built from the typed text before the engine adds NIP-27 `p` tags for
            // `@npub1…` mentions, so requiring full tag equality leaves the
            // optimistic and projected copies unmatched — a transient double bubble
            // until the confirmed id lands. Reply tags (e/q) still must match.
            optimistic.record.plaintext == projected.plaintext &&
                optimistic.record.tags.filterNot { it.values.firstOrNull() == "p" } ==
                projected.tags.filterNot { it.values.firstOrNull() == "p" }
        }?.record
        ?.messageIdHex
}

/**
 * MDK assigns an app-event id before reporting [SendAcceptDispositionFfi.ACCEPTED_PENDING].
 * Use that id to settle retained media exactly; a content/timestamp heuristic cannot safely
 * distinguish multiple queued albums.
 */
internal fun acceptedPendingMediaOptimisticIdForProjection(
    projectedMessageIdHex: String,
    acceptedPendingMessageIdsByOptimisticId: Map<String, String>,
): String? =
    acceptedPendingMessageIdsByOptimisticId
        .entries
        .singleOrNull { (_, acceptedMessageIdHex) -> acceptedMessageIdHex == projectedMessageIdHex }
        ?.key

/**
 * Accepted-pending text keeps its local temporary id until its projection is
 * visible, while MDK already knows the canonical app-event id. Pairing on that
 * canonical id prevents identical queued text sends from stealing each other's
 * projections.
 */
internal fun acceptedPendingTextOptimisticIdForProjection(
    projectedMessageIdHex: String,
    acceptedPendingOptimisticIdsByMessageId: Map<String, String>,
): String? = acceptedPendingOptimisticIdsByMessageId[projectedMessageIdHex]

private fun isSendableOptimisticStatus(
    status: MessageStatus,
    allowFailed: Boolean,
): Boolean =
    status == MessageStatus.Pending ||
        status == MessageStatus.Sent ||
        (allowFailed && status == MessageStatus.Failed)

internal fun failedOptimisticMessageIdForInvalidatedProjection(
    optimisticMessages: Collection<TimelineMessage>,
    projected: AppMessageRecordFfi,
): String? =
    optimisticMessages
        .firstOrNull { optimistic ->
            if (optimistic.status != MessageStatus.Failed) return@firstOrNull false
            if (optimistic.record.direction != projected.direction) return@firstOrNull false
            if (optimistic.record.groupIdHex != projected.groupIdHex) return@firstOrNull false
            if (!optimistic.record.sender.equals(projected.sender, ignoreCase = true)) return@firstOrNull false
            if (optimistic.record.kind != projected.kind) return@firstOrNull false
            optimistic.record.plaintext == projected.plaintext &&
                optimistic.record.tags.filterNot { it.values.firstOrNull() == "p" } ==
                projected.tags.filterNot { it.values.firstOrNull() == "p" }
        }?.record
        ?.messageIdHex

internal fun invalidatedProjectionIdsMatchingMessage(
    timelineRecords: Map<String, TimelineMessageRecordFfi>,
    message: AppMessageRecordFfi,
): List<String> =
    timelineRecords.values
        .filter { projected ->
            projected.invalidationStatus != null &&
                messagesHaveSameRenderableSendShape(
                    left = TimelineProjector.toAppMessageRecord(projected),
                    right = message,
                )
        }.map { it.messageIdHex }

internal fun unpublishedProjectionIdsMatchingMessage(
    timelineRecords: Map<String, TimelineMessageRecordFfi>,
    message: AppMessageRecordFfi,
    activeAccountIdHex: String?,
): List<String> =
    timelineRecords.values
        .filter { projected ->
            !projected.deleted &&
                projected.invalidationStatus == null &&
                projected.sourceMessageIdHex == null &&
                messagesHaveSameRenderableSendShape(
                    left = TimelineProjector.toAppMessageRecord(projected),
                    right = message,
                ) &&
                MessageProjector.isMine(TimelineProjector.toAppMessageRecord(projected), activeAccountIdHex)
        }.map { it.messageIdHex }

private fun messagesHaveSameRenderableSendShape(
    left: AppMessageRecordFfi,
    right: AppMessageRecordFfi,
): Boolean {
    if (left.direction != right.direction) return false
    if (left.groupIdHex != right.groupIdHex) return false
    if (!left.sender.equals(right.sender, ignoreCase = true)) return false
    if (left.kind != right.kind) return false
    return left.plaintext == right.plaintext &&
        left.tags.filterNot { it.values.firstOrNull() == "p" } ==
        right.tags.filterNot { it.values.firstOrNull() == "p" }
}

/**
 * Find a projected timeline row that matches [optimistic] and is committed locally
 * but not yet published (`sourceMessageIdHex == null`). Used when retrying a failed
 * optimistic send so we drive `retryGroupConvergence` instead of minting a duplicate.
 */
internal fun committedButUnpublishedProjectionForOptimistic(
    timelineRecords: Map<String, TimelineMessageRecordFfi>,
    optimistic: AppMessageRecordFfi,
    activeAccountIdHex: String?,
): TimelineMessageRecordFfi? {
    val optimisticIsMediaPending = optimistic.tags.any { it.values.firstOrNull() == "_media_pending" }
    return timelineRecords.values.firstOrNull { projected ->
        if (projected.deleted || projected.sourceMessageIdHex != null) return@firstOrNull false
        if (projected.direction != "sent") return@firstOrNull false
        val projectedAction = TimelineProjector.toAppMessageRecord(projected)
        if (!MessageProjector.isMine(projectedAction, activeAccountIdHex)) return@firstOrNull false
        if (optimistic.groupIdHex != projectedAction.groupIdHex) return@firstOrNull false
        val projectedIsMedia = projectedAction.tags.any { it.values.firstOrNull() == "imeta" }
        if (optimisticIsMediaPending && projectedIsMedia) {
            timestampsAreNear(optimistic.recordedAt, projectedAction.recordedAt)
        } else {
            optimistic.plaintext == projectedAction.plaintext &&
                optimistic.tags == projectedAction.tags &&
                timestampsAreNear(optimistic.recordedAt, projectedAction.recordedAt)
        }
    }
}

private fun timestampsAreNear(
    left: ULong,
    right: ULong,
): Boolean = if (left >= right) left - right <= 1uL else right - left <= 1uL

internal fun shouldInsertSentOptimisticMessage(
    confirmedId: String,
    projectedMessageIds: Set<String>,
): Boolean = confirmedId !in projectedMessageIds

/** Publish succeeded without an id and its temp bubble still awaits the engine echo (#1315). */
internal fun textSendAwaitingEchoConfirmation(
    summaryMessageIds: List<String>,
    optimisticStillPresent: Boolean,
): Boolean = summaryMessageIds.isEmpty() && optimisticStillPresent

internal fun acceptedPendingTextAwaitingProjection(
    acceptDisposition: SendAcceptDispositionFfi,
    confirmedId: String,
    projectedMessageIds: Set<String>,
): Boolean =
    acceptDisposition == SendAcceptDispositionFfi.ACCEPTED_PENDING &&
        confirmedId !in projectedMessageIds

data class SuccessfulTextSendReconciliation(
    val confirmedId: String,
    val confirmed: AppMessageRecordFfi,
    val awaitingEcho: Boolean,
    /** MDK durably accepted the intent but has not assigned a published event id. */
    val acceptedPending: Boolean,
    val insertedSent: Boolean,
) {
    /** Keep the optimistic bubble until MDK's durable projection settles it. */
    val awaitingProjection: Boolean
        get() = awaitingEcho || acceptedPending
}

/**
 * Shared optimistic-state transition after a successful text/reply publish.
 * Used by the initial send path and [ConversationController.retryFailedSend] so
 * empty-summary late-echo semantics stay identical (#1315).
 */
internal fun reconcileSuccessfulTextSend(
    summaryMessageIds: List<String>,
    acceptDisposition: SendAcceptDispositionFfi,
    optimisticKey: String,
    tempId: String,
    optimisticRecord: AppMessageRecordFfi,
    optimisticMessages: MutableMap<String, TimelineMessage>,
    messageById: MutableMap<String, AppMessageRecordFfi>,
    projectedMessageIds: Set<String>,
    timelineOrder: ULong,
    acceptedPendingTextOptimisticIdsByMessageId: MutableMap<String, String>? = null,
): SuccessfulTextSendReconciliation {
    val retentionAtSendSeconds = optimisticMessages[optimisticKey]?.retentionAtSendSeconds
    val hasConfirmedId = summaryMessageIds.isNotEmpty()
    val confirmedId = summaryMessageIds.firstOrNull() ?: tempId
    // The authoritative projection can beat the accepted-pending FFI return.
    // In that ordering there is nothing left to await or bridge: settle through
    // the normal confirmed path using the exact canonical id already projected.
    val acceptedPending =
        acceptedPendingTextAwaitingProjection(
            acceptDisposition = acceptDisposition,
            confirmedId = confirmedId,
            projectedMessageIds = projectedMessageIds,
        )
    val awaitingEcho =
        !acceptedPending &&
            textSendAwaitingEchoConfirmation(
                summaryMessageIds,
                optimisticStillPresent = optimisticKey in optimisticMessages,
            )
    val confirmed = optimisticRecord.copy(messageIdHex = confirmedId)
    if ((hasConfirmedId || awaitingEcho) && confirmedId.isNotEmpty()) {
        messageById[confirmedId] = confirmed
    }
    rememberAcceptedPendingTextOptimisticId(
        acceptedPending = acceptedPending,
        confirmedId = confirmedId,
        tempId = tempId,
        acceptedPendingTextOptimisticIdsByMessageId = acceptedPendingTextOptimisticIdsByMessageId,
    )
    if (!awaitingEcho && !acceptedPending) {
        optimisticMessages.remove(optimisticKey)
        if (confirmedId != tempId) messageById.remove(tempId)
    }
    val insertedSent =
        !acceptedPending &&
            (
                awaitingEcho ||
                    (hasConfirmedId && shouldInsertSentOptimisticMessage(confirmedId, projectedMessageIds))
            )
    if (insertedSent) {
        val sentKey = if (awaitingEcho) optimisticKey else "msg:$confirmedId"
        optimisticMessages[sentKey] =
            TimelineMessage(
                sentKey,
                confirmed,
                MessageStatus.Sent,
                timelineOrder = timelineOrder,
                retentionAtSendSeconds = retentionAtSendSeconds,
            )
    }
    return SuccessfulTextSendReconciliation(
        confirmedId = confirmedId,
        confirmed = confirmed,
        awaitingEcho = awaitingEcho,
        acceptedPending = acceptedPending,
        insertedSent = insertedSent,
    )
}

private fun rememberAcceptedPendingTextOptimisticId(
    acceptedPending: Boolean,
    confirmedId: String,
    tempId: String,
    acceptedPendingTextOptimisticIdsByMessageId: MutableMap<String, String>?,
) {
    if (acceptedPending && confirmedId.isNotEmpty()) {
        acceptedPendingTextOptimisticIdsByMessageId?.set(confirmedId, tempId)
    }
}

/**
 * An optimistic-send position override (#1256) is a transient bridge: it pins a
 * confirmed row to where its optimistic bubble sat so the row doesn't jump on the
 * optimistic→confirmed handoff. Once the optimistic bubble is gone and the real
 * projection has landed the override has done its job — leaving it keyed to a
 * confirmed id pins that row at a stale position while its neighbours re-sort,
 * which renders a newer message above an older one (#1578). Returns the override
 * ids that are now orphaned and safe to release back to their true projected spot.
 */
internal fun orphanedOptimisticSendPreserveIds(
    optimisticSendPreserveIds: Set<String>,
    optimisticKeys: Set<String>,
    projectedMessageIds: Set<String>,
): Set<String> =
    optimisticSendPreserveIds.filterTo(mutableSetOf()) { id ->
        "msg:$id" !in optimisticKeys && id in projectedMessageIds
    }

internal fun confirmedOptimisticMessageKeys(
    optimisticKeys: Set<String>,
    projectedMessageIds: Set<String>,
): Set<String> {
    val projectedKeys = projectedMessageIds.mapTo(mutableSetOf()) { "msg:$it" }
    return optimisticKeys.filterTo(mutableSetOf()) { it in projectedKeys }
}

internal fun <T> newestMatchingController(
    controllers: Iterable<T>,
    matches: (T) -> Boolean,
): T? = controllers.lastOrNull(matches)

/**
 * Conversation-retained ownership for optimistic-send position bridges.
 *
 * The optimistic rows and their ordering overrides survive controller
 * replacement, so the marker identifying those overrides must survive too.
 */
internal class OptimisticSendPositionPreserves {
    private val ids = mutableSetOf<String>()

    fun add(id: String) {
        ids.add(id)
    }

    fun remove(id: String) {
        ids.remove(id)
    }

    fun retainAll(retainedIds: Set<String>) {
        ids.retainAll(retainedIds)
    }

    fun clear() {
        ids.clear()
    }

    operator fun contains(id: String): Boolean = id in ids

    fun snapshot(): Set<String> = ids.toSet()

    fun releaseOrphaned(
        optimisticKeys: Set<String>,
        projectedMessageIds: Set<String>,
    ): Set<String> =
        orphanedOptimisticSendPreserveIds(
            optimisticSendPreserveIds = ids,
            optimisticKeys = optimisticKeys,
            projectedMessageIds = projectedMessageIds,
        ).also { orphaned -> ids.removeAll(orphaned) }
}

internal fun deferTimelinePositionSettlement(
    scope: CoroutineScope,
    currentJob: Job?,
    awaitBoundary: suspend () -> Unit = { yield() },
    settle: () -> Unit,
): Job {
    if (currentJob?.isActive == true) return currentJob
    return scope.launch {
        awaitBoundary()
        settle()
    }
}

/** An adjacent pair whose rendered order reverses the engine timeline or arrival order. */
internal data class TimelineAdjacentInversion(
    val above: TimelineMessage,
    val below: TimelineMessage,
    val sourceTimelineInverted: Boolean,
    val arrivalInverted: Boolean,
)

/**
 * Finds rendered neighbours whose display order reverses either the engine's
 * authenticated timeline time or this device's observation (receive) order — the
 * #1578 symptom of a newer message above an older one. The sort is a total order,
 * so an inversion means the comparator read an overridden position rather than a
 * row's true send time. Optimistic rows are skipped: they have no authoritative
 * projection yet, so their position is legitimately provisional. Splitting source
 * vs. arrival inversion tells a stale local override (source-inverted, usually
 * carrying an override) apart from faithful engine timestamp skew on receive
 * (arrival-inverted only) — the issue's source #1 vs #3.
 */
internal fun adjacentTimelineInversions(ordered: List<TimelineMessage>): List<TimelineAdjacentInversion> =
    ordered
        .windowed(size = 2)
        .mapNotNull { (above, below) ->
            val aboveSource = above.projected ?: return@mapNotNull null
            val belowSource = below.projected ?: return@mapNotNull null
            val sourceTimelineInverted = aboveSource.timelineAt > belowSource.timelineAt
            val arrivalInverted = aboveSource.receivedAt > belowSource.receivedAt
            if (!sourceTimelineInverted && !arrivalInverted) return@mapNotNull null
            TimelineAdjacentInversion(
                above = above,
                below = below,
                sourceTimelineInverted = sourceTimelineInverted,
                arrivalInverted = arrivalInverted,
            )
        }

private fun TimelineMessage.projectedMessageIdHex(): String? = projected?.messageIdHex

/**
 * Records of the rows the engine actually projected, in window order.
 * Optimistic sends and stream-debug rows carry synthetic ids that vanish on
 * reconciliation or cleanup, so anything tracking timeline identity across
 * time — paging anchors, tail watermarks — must never see them.
 */
internal fun canonicalTimelineRecords(items: List<TimelineMessage>): List<AppMessageRecordFfi> =
    items.filter { it.projected != null }.map(TimelineMessage::record)

/**
 * Oldest live timeline message ids to drop so at most [maxLiveItems] non-[protectedIds]
 * rows remain. Deliberately-loaded history (captured when the user scrolls up via
 * [loadOlderPage]) is never trimmed; only rows added by live Upserts after that are
 * capped (#1163).
 */
internal fun timelineMessageIdsExceedingLiveCap(
    items: Collection<TimelineMessage>,
    protectedIds: Set<String>,
    maxLiveItems: Int,
): List<String> {
    if (maxLiveItems < 0) return emptyList()
    val live =
        items.filter {
            val id = it.projectedMessageIdHex()
            id != null && id !in protectedIds
        }
    val overflow = live.size - maxLiveItems
    if (overflow <= 0) return emptyList()
    return live
        .sortedWith(::compareTimelineMessages)
        .take(overflow)
        .mapNotNull { it.projectedMessageIdHex() }
}

internal fun AppMessageRecordFfi.withRecordedAtOverride(recordedAt: ULong?): AppMessageRecordFfi = recordedAt?.let { copy(recordedAt = it) } ?: this

/**
 * The timeline position to reuse when retrying a failed optimistic send.
 * [stored] is non-nullable (`TimelineMessage.timelineOrder` defaults to `0uL`),
 * so an elvis against it is dead code; `0uL` is the "unset" sentinel and is the
 * only case that should mint a fresh order via [freshOrder]. See #101.
 */
internal fun retriedTimelineOrder(
    stored: ULong,
    freshOrder: () -> ULong,
): ULong = stored.takeIf { it != 0uL } ?: freshOrder()

/**
 * Index of the first (oldest) still-unread received message in [timeline]
 * given the chat-list projection's [unreadCount]. Returns -1 when nothing is
 * unread, the timeline is empty, or the loaded window holds fewer than
 * [unreadCount] received messages (caller falls back to the bottom).
 */
internal fun firstUnreadReceivedIndex(
    timeline: List<TimelineMessage>,
    unreadCount: Int,
): Int {
    if (unreadCount <= 0 || timeline.isEmpty()) return -1
    var seen = 0
    for (index in timeline.indices.reversed()) {
        val record = timeline[index].record
        // Derived-state rows (kind 1009 edits, 1210 group system events)
        // arrive as `received` but never count as new chat — skip them so
        // an avatar change or in-place edit doesn't inflate the unread
        // badge or shift the "first unread" anchor away from real
        // messages.
        if (record.direction == "received" && !isDerivedStateKind(record.kind)) {
            seen += 1
            if (seen == unreadCount) return index
        }
    }
    // The loaded window doesn't contain enough received messages to satisfy
    // the unread count; signal "use the bottom" rather than the top, since
    // the read state still advances as the user scrolls.
    return -1
}

/**
 * Count of received messages positioned after the read anchor in [timeline].
 * A null anchor — or one that has fallen out of the loaded window — is
 * treated as "nothing read yet", so the count starts from the first row.
 * Anchoring on a message id (not an index) keeps the count stable when
 * load-older prepends shift every index by the same offset.
 */
internal fun countUnreadIncoming(
    timeline: List<TimelineMessage>,
    readAnchorMessageId: String?,
): Int {
    if (timeline.isEmpty()) return 0
    val anchorIdx =
        readAnchorMessageId?.let { id ->
            timeline.indexOfFirst { it.record.messageIdHex == id }
        } ?: -1
    return timeline.drop(anchorIdx + 1).count {
        it.record.direction == "received" && !isDerivedStateKind(it.record.kind)
    }
}

/**
 * Reconcile the chat-list projection against the loaded timeline and its
 * durable read watermark. A watermark present in the loaded window is stronger
 * evidence than a stale projection; without one, preserve counts larger than
 * the loaded window so pagination can still reveal the real boundary.
 */
internal fun reconciledConversationEntryUnreadCount(
    projectionUnread: Int,
    timeline: List<TimelineMessage>,
    readAnchorMessageId: String?,
): Int {
    val projected = projectionUnread.coerceAtLeast(0)
    return if (projected == 0 || timeline.isEmpty()) {
        projected
    } else {
        val loadedReceived =
            timeline.count { message ->
                message.record.direction == "received" && !isDerivedStateKind(message.record.kind)
            }
        val anchorLoaded =
            !readAnchorMessageId.isNullOrBlank() &&
                timeline.any { it.record.messageIdHex == readAnchorMessageId }
        if (!anchorLoaded && projected > loadedReceived) {
            projected
        } else {
            minOf(projected, countUnreadIncoming(timeline, readAnchorMessageId))
        }
    }
}

/** Privacy-safe snapshot when entry projection unread would mis-anchor the timeline. */
internal data class UnreadCountDivergenceReport(
    val projectionUnread: Int,
    val timelineUnread: Int,
    val loadedReceivedCount: Int,
)

/**
 * Detect an inflated entry unread count that fits the loaded received window and
 * would drive [firstUnreadReceivedIndex] toward the top. Returns null when the
 * counts agree, the timeline is empty, projection unread is not above timeline
 * unread, or projection unread exceeds the loaded window (falls back to bottom).
 */
internal fun unreadCountDivergenceReport(
    projectionUnread: Int,
    timeline: List<TimelineMessage>,
    readAnchorMessageId: String?,
): UnreadCountDivergenceReport? {
    if (timeline.isEmpty()) return null
    val timelineUnread = countUnreadIncoming(timeline, readAnchorMessageId)
    if (projectionUnread <= timelineUnread) return null
    val loadedReceived =
        timeline.count { row ->
            row.record.direction == "received" && !isDerivedStateKind(row.record.kind)
        }
    if (projectionUnread > loadedReceived) return null
    return UnreadCountDivergenceReport(
        projectionUnread = projectionUnread,
        timelineUnread = timelineUnread,
        loadedReceivedCount = loadedReceived,
    )
}

/**
 * Reports a send failure to the user without leaking engine internals. The
 * engine's message can name internal state machines and transitions (for
 * example an `illegal queue_app_message transition from PendingPublish`),
 * which is meaningless to a user and is not ours to put on screen; the raw
 * text stays in the log until the privacy-safe report path exists.
 */
internal fun presentSendFailure(
    appState: WhiteNoiseAppState,
    throwable: Throwable,
) {
    if (BuildConfig.DEBUG) {
        Log.w("DMSend", "send failed", throwable)
    } else {
        Log.w("DMSend", "send_failed")
    }
    val message = sendFailureMessageRes(throwable)
    when (throwable) {
        is MarmotKitException.GroupSendQueueFull,
        is MarmotKitException.GroupHydrationPending,
        -> appState.present(message)
        else -> appState.presentFailure(message, "MESSAGE_SEND", throwable)
    }
}

/**
 * The engine refuses a send outright when a group's outbound queue is full: the
 * message was never accepted, and the backlog clears on the group's own schedule
 * rather than on any timer this app could pick. That earns its own wording, since
 * the generic failure invites a retry the engine has already ruled out.
 *
 * A hydration-pending group is the opposite case — transient by design, the
 * runtime promotes it shortly after account readiness — so that one gets
 * wording that invites the retry instead of announcing a failure.
 */
@StringRes
internal fun sendFailureMessageRes(throwable: Throwable): Int =
    when (throwable) {
        is MarmotKitException.GroupSendQueueFull -> R.string.toast_send_queue_full
        is MarmotKitException.GroupHydrationPending -> R.string.toast_chat_still_loading
        else ->
            if (isTransientRelaySendError(throwable)) {
                R.string.toast_send_connection_failed
            } else {
                R.string.toast_send_failed
            }
    }

internal fun logUnreadCountDivergence(
    tag: String,
    report: UnreadCountDivergenceReport,
) {
    Log.w(
        tag,
        "unread divergence projection=${report.projectionUnread} timeline=${report.timelineUnread} " +
            "loadedReceived=${report.loadedReceivedCount} source=inflated_entry_projection",
    )
}

// Derived-state event kinds: rows that arrive as `received` from the
// network but represent state changes (edits, group system events), not
// new chat. They never inflate unread counts and never block read-anchor
// advancement.
internal fun isDerivedStateKind(kind: ULong): Boolean = kind == 1009uL || kind == 1210uL

/**
 * Message ids of unread received mentions in [timeline], oldest first — drives
 * the in-conversation jump-to-mention chip. A null [readAnchorMessageId] counts
 * from the first loaded row, but a non-null anchor that has fallen out of the
 * loaded window returns no ids. For this chip, hiding is safer than resurrecting
 * an already-read mention after a conversation is recreated. Only kind-9 chat
 * rows can be mentions; [mentionsActiveAccount] is passed in so this stays pure
 * and the ui-layer NIP-27 detection isn't pulled into the state layer.
 */
internal fun unreadReceivedMentionIds(
    timeline: List<TimelineMessage>,
    readAnchorMessageId: String?,
    mentionsActiveAccount: (TimelineMessage) -> Boolean,
): List<String> {
    if (timeline.isEmpty()) return emptyList()
    val anchorIdx =
        readAnchorMessageId?.let { id ->
            timeline.indexOfFirst { it.record.messageIdHex == id }
        } ?: -1
    if (readAnchorMessageId != null && anchorIdx < 0) return emptyList()
    return timeline
        .drop(anchorIdx + 1)
        .filter { it.record.direction == "received" && it.record.kind == 9uL && mentionsActiveAccount(it) }
        .mapNotNull { it.record.messageIdHex.takeIf { id -> id.isNotBlank() } }
}

/**
 * Whether send-time disappearing expiry should stay suspended for [record]
 * until the user scrolls past it (#797). Own sends always use send-time or a
 * display anchor; received rows after the persisted read watermark stay
 * visible even when their wire send-time expiry has passed.
 */
internal fun isDisappearingSendTimeExpiryDeferred(
    record: AppMessageRecordFfi,
    lastReadMessageId: String?,
    lastReadTimelineAt: ULong?,
    messageOrder: Map<String, Int>,
): Boolean {
    if (record.direction != "received") return false
    lastReadMessageId?.takeIf { it.isNotBlank() }?.let { anchorId ->
        val anchorIdx = messageOrder[anchorId]
        val msgIdx = messageOrder[record.messageIdHex]
        if (anchorIdx != null && msgIdx != null) return msgIdx > anchorIdx
    }
    if (lastReadTimelineAt != null) return record.recordedAt > lastReadTimelineAt
    return true
}

/**
 * Group-system rows are durable history about the conversation itself, including the event that
 * announces a retention change. Applying the current message window to those rows hides an old
 * retention event on controller recreation, then a session read anchor can make it reappear after
 * the next send. All other projected row kinds retain their existing expiry behavior.
 */
internal fun shouldApplyLocalDisappearingExpiry(record: AppMessageRecordFfi): Boolean = !MessageProjector.isGroupSystem(record)

/** Applies the row-owned deadline only to ordinary message history. */
internal fun isTimelineRecordLocallyExpired(
    nowMillis: Long,
    record: AppMessageRecordFfi,
    row: DisappearingMessageSweep.LocalExpiryRow,
): Boolean =
    shouldApplyLocalDisappearingExpiry(record) &&
        DisappearingMessageSweep.isLocallyExpired(
            nowMillis = nowMillis,
            row = row,
        )

internal fun firstMessageOrder(messageIds: Iterable<String>): Map<String, Int> =
    buildMap {
        messageIds.forEachIndexed { index, messageId -> putIfAbsent(messageId, index) }
    }

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

internal data class AppliedGroupDetails(
    val group: AppGroupRecordFfi,
    val members: List<AppGroupMemberRecordFfi>,
)

internal enum class GroupRosterLoadState {
    LOADING,
    READY,
    FAILED,
    INCONSISTENT,
}

internal enum class GroupRosterRefreshEvent {
    STARTED,
    SUCCEEDED,
    FAILED,
    INCONSISTENT,
}

internal fun reduceGroupRosterLoadState(
    current: GroupRosterLoadState,
    event: GroupRosterRefreshEvent,
): GroupRosterLoadState =
    when (event) {
        GroupRosterRefreshEvent.STARTED ->
            if (current == GroupRosterLoadState.READY) {
                current
            } else {
                GroupRosterLoadState.LOADING
            }
        GroupRosterRefreshEvent.SUCCEEDED -> GroupRosterLoadState.READY
        GroupRosterRefreshEvent.FAILED ->
            if (current == GroupRosterLoadState.READY) {
                current
            } else {
                GroupRosterLoadState.FAILED
            }
        GroupRosterRefreshEvent.INCONSISTENT -> GroupRosterLoadState.INCONSISTENT
    }

internal fun restoreGroupRosterLoadStateAfterCancellation(
    previous: GroupRosterLoadState,
    current: GroupRosterLoadState,
): GroupRosterLoadState =
    if (current != GroupRosterLoadState.LOADING) {
        current
    } else {
        previous.takeUnless { it == GroupRosterLoadState.LOADING } ?: GroupRosterLoadState.FAILED
    }

internal class GroupRosterLoadTracker(
    initial: GroupRosterLoadState,
) {
    var state by mutableStateOf(initial)
        private set

    private var lastSettledState =
        initial.takeUnless { it == GroupRosterLoadState.LOADING }
            ?: GroupRosterLoadState.FAILED

    fun transition(event: GroupRosterRefreshEvent) {
        state = reduceGroupRosterLoadState(state, event)
        if (state != GroupRosterLoadState.LOADING) {
            lastSettledState = state
        }
    }

    fun restoreAfterCancellation() {
        state =
            restoreGroupRosterLoadStateAfterCancellation(
                previous = lastSettledState,
                current = state,
            )
    }
}

internal enum class GroupRosterInvariant {
    GROUP_ID_MISMATCH,
    EMPTY_JOINED_ROSTER,
    LOCAL_MEMBER_MISSING,
    MEMBER_COUNT_MISMATCH,
}

internal data class GroupRosterResolution(
    val applied: AppliedGroupDetails,
    val invariant: GroupRosterInvariant?,
    val uniqueMemberCount: Int,
    val mlsMemberCount: UInt,
    val containsLocalMember: Boolean,
)

internal fun applyAuthoritativeGroupDetails(details: GroupDetailsFfi): AppliedGroupDetails =
    AppliedGroupDetails(
        group = details.group,
        members =
            GroupProjector.identityDistinctMembers(
                details.members.map { member ->
                    AppGroupMemberRecordFfi(
                        memberIdHex = member.memberIdHex,
                        account = member.account,
                        local = member.local,
                    )
                },
            ),
    )

internal fun resolveAuthoritativeGroupRoster(
    details: GroupDetailsFfi,
    activeAccountIdHex: String?,
): GroupRosterResolution {
    val applied = applyAuthoritativeGroupDetails(details)
    val uniqueMemberCount = GroupProjector.uniqueMemberCount(applied.members)
    val containsLocalMember =
        details.members.any { member ->
            member.isSelf ||
                activeAccountIdHex?.let { accountId ->
                    member.memberIdHex.equals(accountId, ignoreCase = true)
                } == true
        }
    val activeJoinedGroup =
        details.group.selfMembership == SelfMembershipFfi.MEMBER &&
            !details.group.pendingConfirmation
    val invariant =
        when {
            !activeJoinedGroup -> null
            uniqueMemberCount == 0 -> GroupRosterInvariant.EMPTY_JOINED_ROSTER
            !containsLocalMember -> GroupRosterInvariant.LOCAL_MEMBER_MISSING
            details.members.size.toLong() !=
                details.mlsState.memberCount.toLong() -> GroupRosterInvariant.MEMBER_COUNT_MISMATCH
            else -> null
        }
    return GroupRosterResolution(
        applied = applied,
        invariant = invariant,
        uniqueMemberCount = uniqueMemberCount,
        mlsMemberCount = details.mlsState.memberCount,
        containsLocalMember = containsLocalMember,
    )
}

/** Convert the lightweight MDK roster projection without a second details read. */
internal fun applyAuthoritativeGroupRoster(
    currentGroup: AppGroupRecordFfi,
    roster: GroupRosterFfi,
): AppliedGroupDetails =
    AppliedGroupDetails(
        group =
            currentGroup.copy(
                admins = roster.members.filter { it.isAdmin }.map { it.memberIdHex },
                selfMembership = roster.selfMembership,
                unrecoverable = roster.lifecycleState == GroupLifecycleStateFfi.UNRECOVERABLE,
                disbanded = roster.lifecycleState == GroupLifecycleStateFfi.DISBANDED,
            ),
        members =
            GroupProjector.identityDistinctMembers(
                roster.members.map { member ->
                    AppGroupMemberRecordFfi(
                        memberIdHex = member.memberIdHex,
                        account = member.account,
                        local = member.local,
                    )
                },
            ),
    )

internal fun resolveAuthoritativeGroupRoster(
    currentGroup: AppGroupRecordFfi,
    roster: GroupRosterFfi,
    activeAccountIdHex: String?,
): GroupRosterResolution {
    val applied = applyAuthoritativeGroupRoster(currentGroup, roster)
    val uniqueMemberCount = GroupProjector.uniqueMemberCount(applied.members)
    val matchesCurrentGroup =
        currentGroup.groupIdHex.trim().equals(roster.groupIdHex.trim(), ignoreCase = true)
    val containsLocalMember =
        roster.members.any { member ->
            member.isSelf ||
                activeAccountIdHex?.let { accountId ->
                    member.memberIdHex.equals(accountId, ignoreCase = true)
                } == true
        }
    val activeJoinedGroup =
        applied.group.selfMembership == SelfMembershipFfi.MEMBER &&
            !applied.group.pendingConfirmation
    val invariant =
        when {
            !matchesCurrentGroup -> GroupRosterInvariant.GROUP_ID_MISMATCH
            !activeJoinedGroup -> null
            uniqueMemberCount == 0 -> GroupRosterInvariant.EMPTY_JOINED_ROSTER
            !containsLocalMember -> GroupRosterInvariant.LOCAL_MEMBER_MISSING
            uniqueMemberCount.toLong() != roster.memberCount.toLong() ->
                GroupRosterInvariant.MEMBER_COUNT_MISMATCH
            else -> null
        }
    return GroupRosterResolution(
        applied = applied,
        invariant = invariant,
        uniqueMemberCount = uniqueMemberCount,
        mlsMemberCount = roster.memberCount,
        containsLocalMember = containsLocalMember,
    )
}

/**
 * Build a conversation-open [ChatListItem] from a targeted authoritative
 * [groupDetails] read. Used immediately after create returns a canonical group
 * id, before the broad chat-list subscription materializes a row (#1729).
 */
internal fun chatListItemFromAuthoritativeGroupDetails(
    details: GroupDetailsFfi,
    activeAccountIdHex: String?,
): ChatListItem {
    val applied = applyAuthoritativeGroupDetails(details)
    val members = applied.members
    return ChatListItem(
        group = applied.group,
        latest = null,
        otherMemberAccount = GroupProjector.otherMemberAccount(members, activeAccountIdHex),
        memberCount = GroupProjector.uniqueMemberCount(members),
        memberSnapshot = members.let(::GroupMemberSnapshot),
        projection = null,
    )
}

/**
 * Build the first notification-open frame solely from the exact local row.
 * Membership enrichment is intentionally absent from this boundary because it
 * is loaded by the conversation after navigation and may be queued behind
 * account activation. The row still preserves pre-read unread state.
 */
internal fun chatListItemFromNotificationProjection(projection: ChatListRowFfi): ChatListItem = chatListItemFromProjection(row = projection)

/**
 * When a provisional open (no chat-list row yet) is already foregrounded,
 * upgrade [open] to the authoritative projected row exactly once when it
 * arrives — no second navigation event and no duplicate list row.
 */
internal fun reconcileOpenChatWithAuthoritativeRow(
    open: ChatListItem,
    authoritative: ChatListItem,
): ChatListItem? =
    when {
        !open.group.groupIdHex.equals(authoritative.group.groupIdHex, ignoreCase = true) -> null
        open.projection != null -> null
        authoritative.projection == null -> null
        else -> authoritative
    }

/**
 * Upgrade a provisional foreground open using the live chat-list backing row,
 * without waiting for the frozen [ChatsController.items] snapshot while the
 * list is hidden behind an open conversation (#1729).
 */
internal fun reconcileProvisionalOpenChat(
    open: ChatListItem,
    chatsController: ChatsController,
): ChatListItem? {
    if (open.projection != null) return null
    val authoritative =
        chatsController
            .chatItemForGroup(open.group.groupIdHex)
            ?.takeIf { it.projection != null }
    return authoritative?.let { reconcileOpenChatWithAuthoritativeRow(open, it) }
}

internal fun groupStateUpdateRemovesSelf(
    previous: AppGroupRecordFfi,
    update: AppGroupRecordFfi,
): Boolean = !previous.selfMembership.isNonMember() && update.selfMembership.isNonMember()

internal fun cacheAppliedGroupMembers(
    appState: WhiteNoiseAppState,
    account: String,
    groupIdHex: String,
    members: List<AppGroupMemberRecordFfi>,
) {
    appState.cacheGroupMemberSnapshot(account, groupIdHex, members)
    appState.requestProfiles(members.map { it.memberIdHex })
}

internal data class AuthoritativeChatListMembers(
    val memberCacheByGroup: Map<String, List<AppGroupMemberRecordFfi>>,
    val removedGroupIds: Set<String>,
)

/**
 * Whether a roster returned from `groupMembers` is complete enough to cache.
 * An empty roster can be a transient catch-up result and must stay uncached so
 * the chat list retries instead of pinning an Unknown DM title. Once self-
 * removal is independently known, an empty roster is terminal and cacheable.
 * A joined direct conversation containing only self is likewise unresolved for
 * one grace retry: this absorbs transient hydration gaps without preserving a
 * departed peer forever.
 */
internal fun memberSnapshotReadyToCache(
    members: List<AppGroupMemberRecordFfi>,
    knownSelfRemoval: Boolean = false,
    directConversation: Boolean = false,
    activeAccountIdHex: String? = null,
    selfOnlyDirectGraceElapsed: Boolean = false,
): Boolean {
    if (knownSelfRemoval) return true
    val active = activeAccountIdHex?.trim()?.takeIf { it.isNotEmpty() }
    val directRosterReady =
        !directConversation ||
            active == null ||
            members.none { GroupProjector.isActiveAccountMember(it, active) } ||
            members.any { !GroupProjector.isActiveAccountMember(it, active) } ||
            selfOnlyDirectGraceElapsed
    return members.isNotEmpty() && directRosterReady
}

private fun isSelfOnlyDirectRoster(
    members: List<AppGroupMemberRecordFfi>,
    directConversation: Boolean,
    activeAccountIdHex: String?,
): Boolean = directConversation && GroupProjector.isSelfSoleMember(members, activeAccountIdHex)

internal fun memberSnapshotRetryDelayMillis(backoffTier: Int): Long {
    var delayMs = MEMBER_FETCH_INITIAL_RETRY_DELAY_MS
    repeat(backoffTier.coerceIn(0, MEMBER_FETCH_MAX_BACKOFF_TIER)) {
        delayMs = nextRetryBackoffMillis(delayMs, MEMBER_FETCH_MAX_RETRY_DELAY_MS)
    }
    return delayMs
}

internal fun applyAuthoritativeChatListMembers(
    groupIdHex: String,
    members: List<AppGroupMemberRecordFfi>,
    activeAccountIdHex: String?,
    memberCacheByGroup: Map<String, List<AppGroupMemberRecordFfi>>,
    removedGroupIds: Set<String>,
): AuthoritativeChatListMembers {
    val normalizedActive = activeAccountIdHex?.trim()?.takeIf { it.isNotEmpty() }
    val activeMissing =
        normalizedActive != null &&
            members.none { GroupProjector.isActiveAccountMember(it, normalizedActive) }
    return AuthoritativeChatListMembers(
        memberCacheByGroup = memberCacheByGroup + (groupIdHex to members),
        removedGroupIds = if (activeMissing) removedGroupIds + groupIdHex else removedGroupIds - groupIdHex,
    )
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

/**
 * Whether the engine's authoritative self-membership says the local account is
 * no longer in the group: [SelfMembershipFfi.REMOVED] (evicted) or
 * [SelfMembershipFfi.LEFT] (voluntary departure). Both are terminal non-member
 * states; [SelfMembershipFfi.MEMBER] is the only membership-preserving value.
 */
internal fun SelfMembershipFfi.isNonMember(): Boolean = this == SelfMembershipFfi.REMOVED || this == SelfMembershipFfi.LEFT

internal data class ConversationMembershipSeed(
    val members: List<AppGroupMemberRecordFfi>,
    val membersLoaded: Boolean,
    val seededSelfMember: Boolean,
    val seededMembershipKnown: Boolean,
    val membersVerified: Boolean,
)

internal fun conversationMembershipSeed(
    initialGroup: AppGroupRecordFfi,
    initialMemberSnapshot: GroupMemberSnapshot?,
    activeAccountIdHex: String?,
): ConversationMembershipSeed {
    val initialMembers = initialMemberSnapshot?.members.orEmpty()
    val projectedNonMember = initialGroup.selfMembership.isNonMember()
    val projectedMember = initialGroup.selfMembership == SelfMembershipFfi.MEMBER
    val seededMembers =
        if (projectedNonMember) {
            GroupProjector.membersWithoutActiveAccount(initialMembers, activeAccountIdHex)
        } else {
            initialMembers
        }
    val seededSelfMember =
        projectedMember ||
            (
                !projectedNonMember &&
                    initialMembers.any { GroupProjector.isActiveAccountMember(it, activeAccountIdHex) }
            )
    return ConversationMembershipSeed(
        members = seededMembers,
        membersLoaded = initialMemberSnapshot?.members?.isNotEmpty() == true,
        seededSelfMember = seededSelfMember,
        seededMembershipKnown = projectedMember || projectedNonMember || initialMemberSnapshot != null,
        membersVerified = projectedNonMember,
    )
}

internal class ConversationSelfLeftState(
    seededMembershipKnown: Boolean,
    seededSelfMember: Boolean,
) {
    var selfLeft by mutableStateOf(seededMembershipKnown && !seededSelfMember)
        private set

    fun recordSelfLeft() {
        selfLeft = true
    }

    fun clearSelfLeft() {
        selfLeft = false
    }

    fun isSelfMember(
        members: List<AppGroupMemberRecordFfi>,
        activeAccountIdHex: String?,
    ): Boolean = GroupProjector.isSelfStillMember(members, activeAccountIdHex, selfLeft)

    fun rosterHonoringSelfLeft(
        members: List<AppGroupMemberRecordFfi>,
        activeAccountIdHex: String?,
    ): List<AppGroupMemberRecordFfi> = GroupProjector.rosterHonoringSelfLeft(members, activeAccountIdHex, selfLeft)
}

internal fun agentStreamFailureText(
    throwable: Throwable,
    copy: ConversationControllerCopy,
): String {
    if (throwable is CancellationException) throw throwable
    return copy.streamFailed()
}

internal data class OptimisticReactionChange(
    val targetMessageId: String,
    val emoji: String,
    val add: Boolean,
)

/**
 * Apply a reaction overlay before waiting for the engine, and roll it back only
 * when the authoritative mutation fails. This small orchestration boundary is
 * deliberately independent of the FFI so the blocked-worker window can be
 * exercised as behavior rather than inferred from source ordering.
 */
internal suspend fun runOptimisticReactionMutation(
    applyOptimistic: () -> Unit,
    commit: suspend () -> Boolean,
    rollback: () -> Unit,
): Result<Boolean> {
    applyOptimistic()
    return try {
        Result.success(commit())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (throwable: Throwable) {
        rollback()
        Result.failure(throwable)
    }
}

internal fun confirmedOptimisticReactionKeys(
    activeAccountIdHex: String?,
    optimisticChanges: Map<String, OptimisticReactionChange>,
    confirmedSendersByTarget: Map<String, Map<String, Set<String>>>,
): Set<String> {
    val mine = activeAccountIdHex?.lowercase() ?: return emptySet()
    return optimisticChanges
        .filterValues { change ->
            val senders = confirmedSendersByTarget[change.targetMessageId]?.get(change.emoji).orEmpty()
            senders.any { it.equals(mine, ignoreCase = true) } == change.add
        }.keys
}

internal fun reactionTalliesForSenders(
    activeAccountIdHex: String?,
    confirmedSendersByEmoji: Map<String, Set<String>>,
    optimisticChanges: Collection<OptimisticReactionChange>,
): List<ReactionTally> {
    val mine = activeAccountIdHex?.lowercase()
    val sendersByEmoji = linkedMapOf<String, MutableSet<String>>()
    confirmedSendersByEmoji.forEach { (emoji, senders) ->
        sendersByEmoji.getOrPut(emoji) { linkedSetOf() }.addAll(senders.map(String::lowercase))
    }
    if (mine != null) {
        optimisticChanges.forEach { change ->
            val senders = sendersByEmoji.getOrPut(change.emoji) { linkedSetOf() }
            if (change.add) {
                senders.add(mine)
            } else {
                senders.remove(mine)
            }
        }
    }
    return sendersByEmoji
        .mapNotNull { (emoji, senders) ->
            if (senders.isEmpty()) {
                null
            } else {
                ReactionTally(
                    emoji = emoji,
                    count = senders.size,
                    mine = mine != null && senders.contains(mine),
                )
            }
        }.sortedWith(
            compareByDescending<ReactionTally> { it.count }
                .thenByDescending { it.mine }
                .thenBy { it.emoji },
        )
}

/**
 * One named attachment queued for upload as part of an album. The bytes are
 * pre-processed plaintext (e.g. downscaled JPEG for images, raw bytes for
 * documents) — the upload path encrypts these as-is, so callers must apply
 * any MIME-specific transforms (recompression, etc.) before constructing this.
 */
data class PendingAttachment(
    val plaintextBytes: ByteArray,
    val mediaType: String,
    val fileName: String,
    val dim: String? = null,
    val thumbhash: String? = null,
) {
    // Manual equality so two attachments with identical bytes content count as
    // equal — the default data-class equality on ByteArray uses reference
    // equality, which would surprise callers comparing pending attachments.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingAttachment) return false
        if (mediaType != other.mediaType) return false
        if (fileName != other.fileName) return false
        return plaintextBytes.contentEquals(other.plaintextBytes)
    }

    override fun hashCode(): Int {
        var result = plaintextBytes.contentHashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + fileName.hashCode()
        return result
    }
}

/**
 * Compressed bytes + metadata retained for an in-flight/failed media send.
 * The whole album is one unit: all attachments succeed/fail together, retry
 * re-runs the whole upload, discard drops them all. `uploadedReferences`
 * caches the per-attachment Blossom result so a publish-only failure retries
 * the publish without re-uploading every blob.
 */
internal class RetainedMediaUpload(
    val attachments: List<PendingAttachment>,
    val caption: String?,
    var uploadedReferences: List<MediaAttachmentReferenceFfi>? = null,
    var acceptedPending: Boolean = false,
    var acceptedPendingMessageIdHex: String? = null,
)

private data class OptimisticChatListPreviewEntry(
    val preview: ChatListMessagePreviewFfi,
    val activitySequence: ULong,
    val confirmedMessageIdHex: String? = null,
    val pendingAuthoritativeRow: ChatListRowFfi? = null,
)

private data class OptimisticChatListPreviewState(
    var baselineRow: ChatListRowFfi,
    var baselineActivitySequence: ULong,
    val entries: LinkedHashMap<String, OptimisticChatListPreviewEntry> = linkedMapOf(),
    var failedFallbackEntry: OptimisticChatListPreviewEntry? = null,
    val confirmedActivitySequenceById: LinkedHashMap<String, ULong> = linkedMapOf(),
    val baselineActivitySequenceByLastMessage: LinkedHashMap<ChatListLastMessageActivity, ULong> = linkedMapOf(),
)

private fun OptimisticChatListPreviewState.snapshot(): OptimisticChatListPreviewState =
    copy(
        entries = LinkedHashMap(entries),
        confirmedActivitySequenceById = LinkedHashMap(confirmedActivitySequenceById),
        baselineActivitySequenceByLastMessage = LinkedHashMap(baselineActivitySequenceByLastMessage),
    )

private data class ChatListLastMessageActivity(
    val activitySortAt: ULong,
    val timelineAt: ULong?,
    val messageIdHex: String?,
)

private data class OptimisticChatListPreviewMatch(
    val entryKey: String?,
    val activitySequence: ULong,
)

private data class RemovedChatRowSnapshot(
    val row: ChatListRowFfi,
    val activitySequence: ULong,
    val optimisticState: OptimisticChatListPreviewState?,
)

internal class OptimisticArchiveIntent(
    val bindEpoch: Long,
    val archived: Boolean,
)

/** Owns the active account's chat-list projection and live subscription lifecycle. */
class ChatsController private constructor(
    private val appState: WhiteNoiseAppState,
    private val memberSnapshotLoader: suspend (String, String) -> List<AppGroupMemberRecordFfi>,
    private val memberSnapshotRetryDelay: (Int) -> Long,
    private val groupArchivedUpdater: suspend (String, String, Boolean) -> AppGroupRecordFfi,
    initialAccountRef: String?,
    initialLocalSnapshot: AccountSwitchLocalSnapshot?,
    private val initialConnectionAttemptClaim: () -> Boolean,
) {
    private val liveSubscriptions = appState.chatListLiveSubscriptions()

    /** Creates a standalone controller whose initial subscription validation stays silent. */
    constructor(appState: WhiteNoiseAppState) :
        this(appState = appState, initialConnectionAttemptClaim = { false })

    /**
     * Creates the process-shell controller whose first live subscription may
     * claim the one cold-start connection presentation owned by that process.
     */
    internal constructor(
        appState: WhiteNoiseAppState,
        initialConnectionAttemptClaim: () -> Boolean,
    ) :
        this(
            appState = appState,
            memberSnapshotLoader = { accountRef, groupIdHex ->
                appState.marmotIo { groupMembers(accountRef, groupIdHex) }
            },
            memberSnapshotRetryDelay = ::memberSnapshotRetryDelayMillis,
            groupArchivedUpdater = { accountRef, groupIdHex, archived ->
                appState.marmotIo { setGroupArchived(accountRef, groupIdHex, archived) }
            },
            initialAccountRef = null,
            initialLocalSnapshot = appState.consumeAccountSwitchLocalSnapshot(appState.activeAccountRef),
            initialConnectionAttemptClaim = initialConnectionAttemptClaim,
        )

    /** Creates a test controller with injected member loading and no retained local snapshot. */
    internal constructor(
        appState: WhiteNoiseAppState,
        initialAccountRef: String,
        memberSnapshotRetryDelay: (Int) -> Long = ::memberSnapshotRetryDelayMillis,
        memberSnapshotLoader: suspend (String, String) -> List<AppGroupMemberRecordFfi>,
    ) : this(
        appState,
        memberSnapshotLoader,
        memberSnapshotRetryDelay,
        { accountRef, groupIdHex, archived ->
            appState.marmotIo { setGroupArchived(accountRef, groupIdHex, archived) }
        },
        initialAccountRef,
        null,
        { false },
    )

    /** Creates a test controller seeded with an account-switch local projection. */
    internal constructor(
        appState: WhiteNoiseAppState,
        initialAccountRef: String,
        initialLocalSnapshot: AccountSwitchLocalSnapshot,
        memberSnapshotRetryDelay: (Int) -> Long = ::memberSnapshotRetryDelayMillis,
        memberSnapshotLoader: suspend (String, String) -> List<AppGroupMemberRecordFfi>,
    ) : this(
        appState,
        memberSnapshotLoader,
        memberSnapshotRetryDelay,
        { accountRef, groupIdHex, archived ->
            appState.marmotIo { setGroupArchived(accountRef, groupIdHex, archived) }
        },
        initialAccountRef,
        initialLocalSnapshot,
        { false },
    )

    /** Creates a test controller with injected member loading and archive mutation behavior. */
    internal constructor(
        appState: WhiteNoiseAppState,
        initialAccountRef: String,
        memberSnapshotRetryDelay: (Int) -> Long = ::memberSnapshotRetryDelayMillis,
        memberSnapshotLoader: suspend (String, String) -> List<AppGroupMemberRecordFfi>,
        groupArchivedUpdater: suspend (String, String, Boolean) -> AppGroupRecordFfi,
    ) : this(
        appState,
        memberSnapshotLoader,
        memberSnapshotRetryDelay,
        groupArchivedUpdater,
        initialAccountRef,
        null,
        { false },
    )

    /** Consumes the process-scoped right to present the initial connection attempt. */
    internal fun claimInitialConnectionPresentation(): Boolean = initialConnectionAttemptClaim()

    var items by mutableStateOf<List<ChatListItem>>(emptyList())
        private set
    var archivedItems by mutableStateOf<List<ChatListItem>>(emptyList())
        private set
    // Starts true: a new controller has no snapshot yet. ChatsScreen must not
    // paint authoritative EmptyChats until bind() finishes the first local
    // snapshot (issue #1697).

    var isLoading by mutableStateOf(initialLocalSnapshot == null)
        private set

    /**
     * True once the bound account's local projection has returned, including
     * an authoritative empty result. Unlike `items.isNotEmpty()`, this remains
     * a valid warm-resume seed for accounts with no conversations.
     */
    var hasLoadedLocalSnapshot by mutableStateOf(initialLocalSnapshot != null)
        private set
    var error by mutableStateOf<ErrorPresentation?>(null)
        private set

    private val retryLoadSignal = Channel<Unit>(Channel.CONFLATED)

    // staleness-exempt: observable retry trigger consumed by the chat-list UI.
    var retryGeneration by mutableLongStateOf(0L)
        private set
    private var terminalLoadFailure = false

    /** Recovery generation represented by the latest published row snapshot. */
    var recoveryProjectionGeneration by mutableLongStateOf(0L)
        private set
    private val pendingRecoveryProjectionGeneration = RecoveryProjectionGenerationHandoff()

    fun retryLoad() {
        if (terminalLoadFailure) {
            terminalLoadFailure = false
            retryGeneration += 1L
        } else {
            retryLoadSignal.trySend(Unit)
        }
    }

    /** Publishes the terminal no-snapshot failure used by first-frame screen coverage. */
    @VisibleForTesting
    internal fun publishInitialLoadFailureForTest(failure: ErrorPresentation) {
        check(!hasLoadedLocalSnapshot) { "Only the no-snapshot fallback may publish an initial load failure" }
        isLoading = false
        error = failure
        terminalLoadFailure = true
    }

    /** The account this controller is currently bound to (observable so
     *  notification routing can tell when the right account's list is ready). */
    var boundAccountRef by mutableStateOf<String?>(initialLocalSnapshot?.accountRef ?: initialAccountRef)
        private set

    /**
     * Monotonic signal for [chatRowsByGroup] membership changes. Incremented when
     * a group id is added, removed, or replaced by snapshot — not on in-place
     * row updates — so foreground provisional opens can reconcile against live
     * backing rows while [items] stays frozen behind an open conversation (#1729).
     * staleness-exempt: this observable projection membership version is consumed by Compose.
     */
    var materializedGroupsRevision by mutableLongStateOf(0L)
        private set

    /** Complete observable forward-picker revision; staleness-exempt: it is a Compose version. */
    var forwardTargetsRevision by mutableLongStateOf(0L)
        private set

    // staleness-exempt: observable member projection version consumed by derived UI.
    var memberSnapshotsRevision by mutableLongStateOf(0L)
        private set

    private var accountRef: String? = initialLocalSnapshot?.accountRef ?: initialAccountRef
    private var pendingInitialLocalSnapshot = initialLocalSnapshot

    private fun chatRowKey(groupIdHex: String): String = groupIdHex.lowercase()

    private fun nextChatActivitySequence(): ULong {
        if (nextActivitySequence != ULong.MAX_VALUE) nextActivitySequence += 1uL
        return nextActivitySequence
    }

    private fun chatListActivityAdvanced(
        previous: ChatListRowFfi,
        current: ChatListRowFfi,
    ): Boolean = compareChatListActivity(previous, current) > 0

    /** Chooses when a materialized row receives a fresh in-memory order token. */
    private fun shouldAdvanceChatActivitySequence(
        current: ChatListRowFfi?,
        folded: ChatListRowFfi,
        observesNewActivity: Boolean,
    ): Boolean = current == null || chatListActivityAdvanced(current, folded) || observesNewActivity

    private fun compareChatListActivity(
        previous: ChatListRowFfi,
        current: ChatListRowFfi,
    ): Int {
        val lastMessageCompare = compareChatListLastMessageActivity(previous, current)
        return if (lastMessageCompare != 0) {
            lastMessageCompare
        } else {
            current.activitySortAt.compareTo(previous.activitySortAt)
        }
    }

    private fun compareChatListLastMessageActivity(
        previous: ChatListRowFfi,
        current: ChatListRowFfi,
    ): Int {
        val currentLast = current.lastMessage
        val previousLast = previous.lastMessage
        return when {
            currentLast == null && previousLast == null -> 0
            currentLast == null -> -1
            previousLast == null -> 1
            else ->
                compareTimelineAtMessageIdHex(
                    currentLast.timelineAt,
                    currentLast.messageIdHex,
                    previousLast.timelineAt,
                    previousLast.messageIdHex,
                )
        }
    }

    private fun rememberBaselineActivitySequence(
        state: OptimisticChatListPreviewState,
        row: ChatListRowFfi,
        activitySequence: ULong,
    ) {
        val activity = chatListLastMessageActivity(row)
        state.baselineActivitySequenceByLastMessage.remove(activity)
        state.baselineActivitySequenceByLastMessage[activity] = activitySequence
        while (state.baselineActivitySequenceByLastMessage.size > MAX_CHAT_LIST_ACTIVITY_SEQUENCE_HISTORY) {
            state.baselineActivitySequenceByLastMessage.remove(state.baselineActivitySequenceByLastMessage.keys.first())
        }
    }

    private fun chatListLastMessageActivity(row: ChatListRowFfi): ChatListLastMessageActivity =
        ChatListLastMessageActivity(
            activitySortAt = row.activitySortAt,
            timelineAt = row.lastMessage?.timelineAt,
            messageIdHex = row.lastMessage?.messageIdHex,
        )

    private fun representsSameChatListActivity(
        optimistic: ChatListMessagePreviewFfi,
        authoritative: ChatListMessagePreviewFfi,
    ): Boolean =
        optimistic.timelineAt == authoritative.timelineAt &&
            optimistic.sender == authoritative.sender &&
            optimistic.plaintext == authoritative.plaintext &&
            optimistic.kind == authoritative.kind &&
            optimistic.deleted == authoritative.deleted &&
            optimistic.attachmentKind == authoritative.attachmentKind &&
            optimistic.attachmentCount == authoritative.attachmentCount

    private fun matchingOptimisticPreview(
        state: OptimisticChatListPreviewState,
        row: ChatListRowFfi,
    ): OptimisticChatListPreviewMatch? {
        val authoritative = row.lastMessage ?: return null
        val matchingEntry =
            state.entries.entries.firstOrNull { (_, entry) ->
                entry.confirmedMessageIdHex == authoritative.messageIdHex
            } ?: state.entries.entries.firstOrNull { (_, entry) ->
                entry.confirmedMessageIdHex == null &&
                    representsSameChatListActivity(entry.preview, authoritative)
            }
        return matchingEntry?.let { (entryKey, entry) ->
            OptimisticChatListPreviewMatch(entryKey, entry.activitySequence)
        } ?: state.failedFallbackEntry
            ?.takeIf { entry -> representsSameChatListActivity(entry.preview, authoritative) }
            ?.let { entry ->
                OptimisticChatListPreviewMatch(entryKey = null, activitySequence = entry.activitySequence)
            }
            ?: state.confirmedActivitySequenceById[authoritative.messageIdHex]?.let { activitySequence ->
                OptimisticChatListPreviewMatch(entryKey = null, activitySequence = activitySequence)
            }
    }

    private fun optimisticMatchIsStale(
        match: OptimisticChatListPreviewMatch,
        baselineRow: ChatListRowFfi,
        row: ChatListRowFfi,
        baselineActivitySequence: ULong,
        activityCompare: Int,
    ): Boolean =
        match.activitySequence < baselineActivitySequence ||
            activityCompare < 0 ||
            (
                match.activitySequence == baselineActivitySequence &&
                    row.lastMessage?.messageIdHex != baselineRow.lastMessage?.messageIdHex
            )

    /**
     * Keep only the newest failed preview that a later provisional entry is
     * currently hiding. If that newer entry is abandoned, the row can reveal
     * this fallback instead of jumping to the pre-send baseline. The scalar
     * slot preserves the decrypted-preview memory bound: repeated failures
     * replace one fallback rather than accumulating one entry per send.
     */
    private fun pruneUnreachableFailedEntries(state: OptimisticChatListPreviewState) {
        state.failedFallbackEntry =
            state.failedFallbackEntry?.takeIf {
                it.activitySequence > state.baselineActivitySequence
            }
        val newestSequence = state.entries.values.maxOfOrNull { it.activitySequence } ?: return
        val newestSupersededFailure =
            state.entries.values
                .asSequence()
                .filter { entry ->
                    entry.confirmedMessageIdHex == null &&
                        entry.preview.deliveryState == ChatListMessageDeliveryStateFfi.FAILED &&
                        entry.activitySequence > state.baselineActivitySequence &&
                        entry.activitySequence < newestSequence
                }.plus(state.failedFallbackEntry?.let(::sequenceOf).orEmpty())
                .maxByOrNull { it.activitySequence }
        state.failedFallbackEntry = newestSupersededFailure
        state.entries.entries.removeAll { (_, entry) ->
            entry.confirmedMessageIdHex == null &&
                entry.preview.deliveryState == ChatListMessageDeliveryStateFfi.FAILED &&
                (entry.activitySequence <= state.baselineActivitySequence || entry.activitySequence < newestSequence)
        }
    }

    private fun retireCommittedOptimisticEntriesThrough(
        state: OptimisticChatListPreviewState,
        activitySequence: ULong,
    ) {
        state.entries.entries.removeAll { (_, entry) ->
            entry.confirmedMessageIdHex != null &&
                entry.activitySequence <= activitySequence
        }
    }

    /**
     * Folds [row] into the optimistic baseline and returns the row that is safe
     * to expose to conversation-scoped consumers. A pending matching echo is
     * intentionally returned while parked; a rejected stale replay returns the
     * already-accepted baseline instead.
     */
    private fun foldOptimisticChatListBaseline(
        state: OptimisticChatListPreviewState,
        row: ChatListRowFfi,
        acceptBackwardActivity: Boolean = false,
        observesNewActivity: Boolean = false,
    ): ChatListRowFfi {
        val activityCompare = compareChatListActivity(state.baselineRow, row)
        val match =
            matchingOptimisticPreview(state, row)
                ?.takeUnless { acceptBackwardActivity && activityCompare < 0 }
        // A failed send is terminal: its callback will never report a confirmed
        // id, so parking an authoritative row against it would strand the row
        // on FAILED and discard every later update for that message.
        val pendingEntryKey =
            match?.entryKey?.takeIf {
                val entry = state.entries[it]
                entry != null &&
                    entry.confirmedMessageIdHex == null &&
                    entry.preview.deliveryState != ChatListMessageDeliveryStateFfi.FAILED
            }
        if (pendingEntryKey != null) {
            // The stream can expose the locally committed row before send()
            // reports whether publishing succeeded. Keep it provisional so a
            // later failure still restores the pre-send baseline.
            state.entries[pendingEntryKey] = state.entries.getValue(pendingEntryKey).copy(pendingAuthoritativeRow = row)
            return row
        }
        var acceptRow = true
        if (match != null) {
            // A coalesced snapshot can skip every earlier successful send.
            // Retire older committed previews, whose compact id/sequence
            // tombstones still recognize queued echoes. Pending previews stay
            // until their callback can record a confirmed id or roll them back.
            retireCommittedOptimisticEntriesThrough(state, match.activitySequence)
            // The echo keeps the order assigned when the local send was
            // accepted. If a later authoritative activity already owns the
            // row, consuming this stale echo must not move the row backward.
            val staleMatch =
                optimisticMatchIsStale(
                    match,
                    state.baselineRow,
                    row,
                    state.baselineActivitySequence,
                    activityCompare,
                )
            if (staleMatch && !acceptBackwardActivity) {
                acceptRow = false
            } else {
                state.baselineActivitySequence = match.activitySequence
                rememberBaselineActivitySequence(state, row, match.activitySequence)
            }
        } else if (acceptBackwardActivity && foldSnapshotKeepingConfirmedSend(state, row)) {
            acceptRow = false
        } else {
            acceptRow =
                foldUnmatchedChatListRow(
                    state,
                    row,
                    activityCompare,
                    acceptBackwardActivity,
                    observesNewActivity,
                )
        }
        if (acceptRow) state.baselineRow = row
        return if (acceptRow) row else state.baselineRow
    }

    /**
     * Sequencing for a row that matches no optimistic preview: reuse the
     * sequence already accepted for this activity when it is known, take a
     * fresh one for genuinely newer activity, and otherwise keep the accepted
     * ordering. Returns whether the row may replace the baseline.
     */
    private fun foldUnmatchedChatListRow(
        state: OptimisticChatListPreviewState,
        row: ChatListRowFfi,
        activityCompare: Int,
        acceptBackwardActivity: Boolean,
        observesNewActivity: Boolean,
    ): Boolean {
        val knownActivitySequence =
            state.baselineActivitySequenceByLastMessage[chatListLastMessageActivity(row)]
        var acceptRow = true
        when {
            knownActivitySequence != null -> {
                if (knownActivitySequence < state.baselineActivitySequence && !acceptBackwardActivity) {
                    acceptRow = false
                } else {
                    state.baselineActivitySequence = knownActivitySequence
                    rememberBaselineActivitySequence(state, row, knownActivitySequence)
                }
            }

            activityCompare > 0 || observesNewActivity -> {
                state.baselineActivitySequence = nextChatActivitySequence()
                rememberBaselineActivitySequence(state, row, state.baselineActivitySequence)
                state.entries.entries.removeAll { (_, entry) ->
                    entry.confirmedMessageIdHex != null &&
                        entry.activitySequence < state.baselineActivitySequence
                }
            }

            activityCompare < 0 && acceptBackwardActivity -> {
                state.baselineActivitySequence = 0uL
                rememberBaselineActivitySequence(state, row, 0uL)
            }

            // Subscription rows remain authoritative for content even when
            // their last-message tuple compares backward. Keep the accepted
            // ordering sequence without leaving other fields stale after
            // same-second id order or preview loss.
            else -> rememberBaselineActivitySequence(state, row, state.baselineActivitySequence)
        }
        return acceptRow
    }

    /**
     * Handles a snapshot replay that lags a send the engine already confirmed.
     * Rewinding the row to the older last message would regress the list below
     * the conversation's own state, but dropping the row wholesale
     * would strand its unread and read-state — subscription rows stay
     * authoritative for content. So the confirmed send keeps the row's last
     * message and sort position while every other field comes from the fresh
     * snapshot. Returns false when this does not apply, leaving the caller's
     * normal handling in charge.
     */
    private fun foldSnapshotKeepingConfirmedSend(
        state: OptimisticChatListPreviewState,
        row: ChatListRowFfi,
    ): Boolean {
        val confirmedLastMessage =
            state.baselineRow.lastMessage?.takeIf { baselineLastMessage ->
                state.confirmedActivitySequenceById.containsKey(baselineLastMessage.messageIdHex) &&
                    row.lastMessage?.messageIdHex != baselineLastMessage.messageIdHex &&
                    compareChatListActivity(state.baselineRow, row) < 0
            } ?: return false
        val merged =
            row.copy(
                lastMessage = confirmedLastMessage,
                activitySortAt = state.baselineRow.activitySortAt,
            )
        state.baselineRow = merged
        rememberBaselineActivitySequence(state, merged, state.baselineActivitySequence)
        return true
    }

    private fun materializeOptimisticChatListPreview(
        rowKey: String,
        state: OptimisticChatListPreviewState,
    ) {
        pruneUnreachableFailedEntries(state)
        val latestEntry =
            state.entries.values
                .asSequence()
                .plus(state.failedFallbackEntry?.let(::sequenceOf).orEmpty())
                .maxByOrNull { it.activitySequence }
        val visibleEntry = latestEntry?.takeIf { it.activitySequence > state.baselineActivitySequence }
        chatRowsByGroup[rowKey] =
            visibleEntry?.let { entry ->
                state.baselineRow.copy(
                    lastMessage = entry.preview,
                    activitySortAt = maxOf(state.baselineRow.activitySortAt, entry.preview.timelineAt),
                    updatedAt = maxOf(state.baselineRow.updatedAt, entry.preview.timelineAt),
                )
            } ?: state.baselineRow
        activitySequenceByGroup[rowKey] = visibleEntry?.activitySequence ?: state.baselineActivitySequence
        if (
            state.entries.isEmpty() &&
            state.failedFallbackEntry == null &&
            state.confirmedActivitySequenceById.isEmpty()
        ) {
            optimisticChatListPreviewByGroup.remove(rowKey)
        }
    }

    private val chatRowsByGroup = LinkedHashMap<String, ChatListRowFfi>()
    private val chatRows: Collection<ChatListRowFfi>
        get() = chatRowsByGroup.values
    private var groupRecordsById = mapOf<String, AppGroupRecordFfi>()

    // Whole-second activity timestamps need an in-memory tie-break that follows
    // the order local/live activity is accepted. This is bounded to one scalar
    // per materialized row and cleared with the backing projection on bind.
    private val activitySequenceByGroup = mutableMapOf<String, ULong>()

    // staleness-exempt: ordered activity tie-breaker, not an async publication fence.
    private var nextActivitySequence = 0uL
    private val optimisticChatListPreviewByGroup = mutableMapOf<String, OptimisticChatListPreviewState>()
    private val optimisticArchiveByGroup = mutableMapOf<String, OptimisticArchiveIntent>()

    // Whether the chat list is on screen. While a conversation is foregrounded
    // the subscription stays warm (updates keep folding into the maps above),
    // but the recompute is deferred so list projection doesn't compete with the
    // conversation on the heaviest nav path. See #6.
    private var chatListVisible = true
    private var pendingRecompute = false

    // Lifecycle-scoped member snapshots for the current chat rows. Initial
    // bind seeds identifier-only rows in bounded `groupMemberIdsPage` calls;
    // later group invalidations and a failed initial page fall back to the
    // enriched per-group `groupMembers` reader. These snapshots drive unnamed
    // titles, folder rules, shared groups, and profile actions without becoming
    // a second persistent source of protocol truth.
    private var memberCacheByGroup: Map<String, List<AppGroupMemberRecordFfi>> = emptyMap()

    // A live group-record update invalidates the authoritative roster, but its
    // last rendered values remain useful for title/avatar continuity while a
    // replacement loads. Keep only the display-shaped values separate from the
    // authoritative cache so business rules cannot consume a stale roster.
    // Cleared on each bind and as soon as a complete replacement arrives.
    private var presentationMembersByGroup: Map<String, ChatListMemberPresentation> = emptyMap()

    // Groups the active account is known to have been removed/left from, keyed
    // by group id hex. Set on a confirmed self-leave or when a loaded roster
    // omits self; lets [ChatListItem.removedFromGroup] treat a genuinely-empty
    // post-removal roster as real removal (a fetch-failure empty roster, which
    // never adds an id here, stays non-removed). Cleared on every bind.
    private var removedGroupIds: Set<String> = emptySet()

    // Tracks groups whose member fetch is currently in flight, preventing
    // duplicate FFI work for the same group. Entries are added in
    // [schedulePendingMemberFetches] and removed in the same coroutine's
    // `finally`. Failed or transiently-empty loads schedule a backoff retry;
    // `bind()` clears the set alongside the cache to reset both at once.
    private val inFlightMemberFetches = mutableSetOf<String>()

    // Exponential-backoff tier per group for roster reads that fail or return a
    // roster too incomplete to cache (e.g. empty while catch-up is still
    // materializing members). The tier is capped, while retries continue until
    // a roster lands or the group/account lifecycle ends, so Unknown cannot
    // become permanent after a finite retry budget.
    private val memberFetchRetryBackoffTierByGroup = mutableMapOf<String, Int>()
    private val memberFetchRetryJobsByGroup = mutableMapOf<String, Job>()
    private val failedMemberFetches = mutableSetOf<String>()

    // A self-only direct roster gets one dedicated confirmation read before it
    // becomes authoritative. Keep this separate from the general backoff tier:
    // an earlier error or empty read must not consume the self-only grace retry.
    private val selfOnlyDirectGraceRetryGroups = mutableSetOf<String>()

    // Widening member snapshots to every group makes the chat-list projection
    // much more useful, but the app should not start one roster FFI call per
    // group on large accounts. Keep at most one fetch in flight per group while
    // bounding simultaneous FFI/IO work across all groups.
    private val memberFetchGate = Semaphore(MEMBER_FETCH_FANOUT)

    // Parsed markdown for each row's last-message preview, keyed by the exact
    // plaintext (tokens must always describe the text beside them — keying by
    // group or message id would go stale on edits). This is derived UI state
    // over the live rows, not a second store of protocol data: it's pruned to
    // the texts the current rows actually show (in
    // [schedulePendingPreviewParses]) and cleared on bind. A parse failure
    // caches the empty document, which renders as plaintext and stops the
    // row from re-parsing on every recompute.
    private var previewTokensByText = mapOf<String, MarkdownDocumentFfi>()

    // Resolved media fallbacks for blank chat-list preview rows, keyed by the
    // last message id. Derived UI state over the live rows (pruned to ids still
    // on screen), not a protocol cache — same lifecycle as [previewTokensByText].
    private var mediaPreviewFallbackByMessageId = mapOf<String, MediaPreviewFallback>()

    // Same single-state invariant as [inFlightMemberFetches], keyed by
    // preview plaintext: pending → in flight (here) → cached.
    private val inFlightPreviewParses = mutableSetOf<String>()
    private val inFlightMediaKindResolves = mutableSetOf<String>()
    private val previewParseGate = Semaphore(PREVIEW_PARSE_FANOUT)
    private val mediaKindResolveGate = Semaphore(MEDIA_KIND_RESOLVE_FANOUT)

    // Monotonically increments on every `bind()`. Captured by each
    // [schedulePendingMemberFetches] job; once a later bind has happened
    // (account switch, sign-out, or re-bind), the captured epoch no
    // longer matches and the job drops its result instead of poisoning
    // the new account's cache with stale members.
    private val bindLifetime = StalenessGuard()

    /** Current account-binding token passed through projection helpers. */
    private val bindEpoch: Long
        get() = bindLifetime.capture()

    // Monotonically increments whenever a live group update invalidates member
    // freshness. Member-fetch jobs capture it and drop results that started
    // before a newer group-details update, so a stale in-flight roster cannot
    // overwrite an authoritative local mutation or live group-state change (#825).
    private val memberCacheLifetime = StalenessGuard()

    /** Current authoritative member-cache token passed through loaders. */
    private val memberCacheEpoch: Long
        get() = memberCacheLifetime.capture()
    private var isCleared = false

    private val liveSubscriptionLock = Any()
    private var activeChatListSubscription: ChatListSubscriptionHandle? = null
    private var activeChatsSubscription: ChatsSubscriptionHandle? = null
    private var bindJob: Job? = null
    private val connectionOwner =
        ChatListConnectionOwner(appState) {
            synchronized(liveSubscriptionLock) {
                activeChatListSubscription != null &&
                    activeChatsSubscription != null &&
                    accountRef == boundAccountRef &&
                    accountRef == appState.activeAccountRef
            }
        }
    internal val connectionState: ChatListConnectionState get() = connectionOwner.state

    fun refreshConnectionReadiness() = connectionOwner.refresh(presentAttempt = true)

    fun revalidateConnectionReadiness() = connectionOwner.refresh(presentAttempt = false)

    fun invalidateConnectionReadiness() = connectionOwner.invalidate()

    /** Ends account-owned streams and invalidates roster results before closing their handles. */
    suspend fun closeLiveSubscriptionsForAccountTeardown(accountRef: String) {
        val teardown =
            synchronized(liveSubscriptionLock) {
                if (!shouldTeardownLiveSubscriptionsForAccount(accountRef, this.accountRef, boundAccountRef)) {
                    null
                } else {
                    this.accountRef = null
                    boundAccountRef = null
                    invalidateConnectionReadiness()
                    val current = Triple(activeChatListSubscription, activeChatsSubscription, bindJob)
                    activeChatListSubscription = null
                    activeChatsSubscription = null
                    current
                }
            } ?: return
        val (chatListSubscription, chatsSubscription, job) = teardown
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { chatListSubscription?.close() }
            runCatching { chatsSubscription?.close() }
        }
        if (shouldCancelLiveSubscriptionJob(job, coroutineContext[Job])) {
            job?.cancelAndJoin()
        }
    }

    /** Binds [accountRef] while preserving local projection and retrying live subscriptions. */
    suspend fun bind(
        accountRef: String?,
        preserveLoadedContent: Boolean = false,
    ) {
        if (isCleared) return
        val currentBindJob = coroutineContext[Job]
        synchronized(liveSubscriptionLock) { bindJob = currentBindJob }
        chatsDebug { "bind account=${accountRef?.take(8)}" }
        this.accountRef = accountRef
        this.boundAccountRef = accountRef
        val seededLocalSnapshot =
            pendingInitialLocalSnapshot?.takeIf { snapshot -> snapshot.accountRef == accountRef }
        pendingInitialLocalSnapshot = null
        val keepLoadedContent =
            shouldPreserveChatListProjection(
                hasSeededLocalSnapshot = seededLocalSnapshot != null,
                preserveLoadedContent = preserveLoadedContent,
                hasLoadedLocalSnapshot = hasLoadedLocalSnapshot,
            )
        isLoading = accountRef != null && !keepLoadedContent
        if (!keepLoadedContent) {
            hasLoadedLocalSnapshot = accountRef == null
            resetBackingState()
        }
        bindLifetime.advance()
        connectionOwner.reset(accountRef, bindEpoch)
        recompute(scheduleBackgroundEnrichment = seededLocalSnapshot == null)
        error = null
        terminalLoadFailure = false

        if (accountRef == null) {
            synchronized(liveSubscriptionLock) {
                if (bindJob === currentBindJob) {
                    bindJob = null
                }
            }
            return
        }
        appState.refreshDraftSummaries(accountRef)
        try {
            val catchUpGate = ChatListCatchUpGate()
            var retryDelayMs = LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS
            var localFramePresented = preserveLoadedContent && seededLocalSnapshot == null && keepLoadedContent
            var pendingReadinessCatchUp: Deferred<AccountCatchUpResult>? = null
            var initialSubscriptionProjection = true
            if (seededLocalSnapshot != null) {
                // Render the preinstalled one-shot seed before live or background enrichment.
                awaitRenderedChatListFrame()
                if (shouldRetryLiveSubscriptionForAccount(accountRef, boundAccountRef)) {
                    appState.recordAccountSwitchLocalSnapshotRendered(accountRef, chatRows.size)
                    localFramePresented = true
                    if (catchUpGate.claimInitial()) {
                        pendingReadinessCatchUp = appState.launchCatchUpAccounts()
                    }
                    // A performance-shaped handoff may contain only the
                    // rosters needed to render first-frame identity. Do not
                    // turn every deferred named-group roster into an N-call
                    // `groupMembers` fan-out here: the live subscription path
                    // immediately below owns one batched member-id hydration.
                    recompute(scheduleBackgroundEnrichment = false)
                }
            }
            while (coroutineContext.isActive && shouldRetryLiveSubscriptionForAccount(accountRef, boundAccountRef)) {
                var chatListSubscription: ChatListSubscriptionHandle? = null
                var chatsSubscription: ChatsSubscriptionHandle? = null
                var receivedLiveUpdate = false
                val connectionAttempt =
                    if (initialSubscriptionProjection) {
                        initialSubscriptionProjection = false
                        if (claimInitialConnectionPresentation()) {
                            connectionOwner.beginSessionAttempt(accountRef, bindEpoch)
                        } else {
                            connectionOwner.beginSubscriptionValidation(accountRef, bindEpoch)
                        }
                    } else {
                        connectionOwner.beginSessionAttempt(accountRef, bindEpoch)
                    }
                try {
                    val chatListStream = liveSubscriptions.openChatList(accountRef, true)
                    chatListSubscription = chatListStream
                    val chatStream = liveSubscriptions.openChats(accountRef, true)
                    chatsSubscription = chatStream
                    if (!shouldRetryLiveSubscriptionForAccount(accountRef, boundAccountRef)) break
                    synchronized(liveSubscriptionLock) {
                        if (shouldRetryLiveSubscriptionForAccount(accountRef, boundAccountRef)) {
                            activeChatListSubscription = chatListStream
                            activeChatsSubscription = chatStream
                        }
                    }
                    replaceChatRows(
                        withContext(Dispatchers.IO) {
                            chatListStream.snapshot()
                        },
                    )
                    appState.recordAccountSwitchLocalRowsReady(accountRef, chatRows.size)
                    chatRows.forEach(::requestChatRowProfiles)
                    groupRecordsById =
                        withContext(Dispatchers.IO) {
                            chatStream.snapshot()
                        }.associateBy { it.groupIdHex }
                    groupRecordsById.values.forEach(::requestGroupProfiles)
                    seedInitialMemberIdProjection(accountRef, bindEpoch)
                    recordMemberDerivedLocalReadyIfComplete()
                    chatsDebug {
                        "snapshot account=${accountRef.take(8)} rows=${chatRows.size} groups=${groupRecordsById.size} " +
                            "${chatRows.map { it.debugSummary() }}"
                    }
                    hasLoadedLocalSnapshot = true
                    isLoading = false
                    error = null
                    recompute()

                    // Draw the local projection before catch-up; live updates fold fresh state afterward.
                    if (!localFramePresented) {
                        awaitRenderedChatListFrame()
                        appState.recordAccountSwitchLocalSnapshotRendered(accountRef, chatRows.size)
                        localFramePresented = true
                    }
                    if (pendingReadinessCatchUp == null && catchUpGate.claimInitial()) {
                        pendingReadinessCatchUp = connectionOwner.launchCatchUp()
                    }
                    val readinessCatchUp = pendingReadinessCatchUp
                    pendingReadinessCatchUp = null
                    readinessCatchUp?.let(connectionOwner::observe)

                    coroutineScope {
                        runUntilFirstLiveSubscriptionEnds(
                            first = {
                                while (isActive) {
                                    val update =
                                        withContext(Dispatchers.IO) {
                                            chatListStream.nextUpdate()
                                        } ?: break
                                    appState.recoveryDiagnostics
                                        .recordChatListSubscriptionReceived()
                                        ?.let { generation ->
                                            pendingRecoveryProjectionGeneration.publish(generation)
                                        }
                                    receivedLiveUpdate = true
                                    connectionOwner.noteLiveUpdate(connectionAttempt)
                                    applyChatListSubscriptionUpdate(accountRef, update)
                                }
                            },
                            second = {
                                while (isActive) {
                                    val update =
                                        withContext(Dispatchers.IO) {
                                            chatStream.next()
                                        } ?: break
                                    receivedLiveUpdate = true
                                    connectionOwner.noteLiveUpdate(connectionAttempt)
                                    requestGroupProfiles(update)
                                    chatsDebug { "chat update account=${accountRef.take(8)} ${update.debugSummary()}" }
                                    foldGroup(update)
                                }
                            },
                        )
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (throwable: Throwable) {
                    chatsDebug(throwable) {
                        "live chat subscription failed account=${accountRef.take(8)}: " +
                            "${throwable.message ?: throwable.javaClass.simpleName}"
                    }
                    isLoading = false
                    val hasLoadedContent = chatRows.isNotEmpty()
                    error =
                        privacySafeErrorPresentation(
                            operationCode = if (hasLoadedContent) "CHAT_LIST_REFRESH" else "CHAT_LIST_LOAD",
                            throwable = throwable,
                            message =
                                AppText.Resource(
                                    if (hasLoadedContent) {
                                        R.string.error_loaded_content_may_be_out_of_date
                                    } else {
                                        R.string.error_try_again
                                    },
                                ),
                        )
                } finally {
                    synchronized(liveSubscriptionLock) {
                        if (activeChatListSubscription === chatListSubscription) {
                            activeChatListSubscription = null
                        }
                        if (activeChatsSubscription === chatsSubscription) {
                            activeChatsSubscription = null
                        }
                    }
                    connectionOwner.finishSessionAttempt(connectionAttempt)
                    // NonCancellable cleanup prevents a cancelled bind, retry, or account switch
                    // from leaking account-wide chat-list/chats handles (#270).
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { chatListSubscription?.close() }
                        runCatching { chatsSubscription?.close() }
                    }
                }
                if (!coroutineContext.isActive || !shouldRetryLiveSubscriptionForAccount(accountRef, boundAccountRef)) break
                // Reset only after a real live update, not after a successful
                // bind/snapshot. A relay that connects and immediately closes
                // should keep backing off instead of pinning retries at 500ms.
                retryDelayMs =
                    liveSubscriptionRetryDelayMillisAfterAttempt(
                        currentRetryDelayMs = retryDelayMs,
                        receivedUpdate = receivedLiveUpdate,
                    )
                chatsDebug { "chat subscriptions ended; retrying in ${retryDelayMs}ms account=${accountRef.take(8)}" }
                val userRequestedRetry = withTimeoutOrNull(retryDelayMs) { retryLoadSignal.receive() } != null
                retryDelayMs =
                    if (userRequestedRetry) {
                        LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS
                    } else {
                        nextLiveSubscriptionRetryDelayMillis(retryDelayMs)
                    }
            }
        } catch (cancel: CancellationException) {
            // Expected when LaunchedEffect re-keys (account switch, navigate
            // away). Re-throw so structured concurrency unwinds cleanly and we
            // don't log normal lifecycle events as bind failures.
            throw cancel
        } catch (throwable: Throwable) {
            chatsDebug(throwable) { "bind failed account=${accountRef.take(8)}: ${throwable.message ?: throwable.javaClass.simpleName}" }
            isLoading = false
            error = privacySafeErrorPresentation("CHAT_LIST_LOAD", throwable)
            terminalLoadFailure = true
        } finally {
            synchronized(liveSubscriptionLock) {
                if (bindJob === currentBindJob) {
                    bindJob = null
                }
            }
            chatsDebug { "unbind account=${accountRef.take(8)} (chat-list + chats subscriptions closed)" }
        }
    }

    private suspend fun awaitRenderedChatListFrame() {
        // Choreographer callbacks run before traversal. Two callbacks guarantee
        // the published local snapshot gets one complete draw before catch-up.
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

    private fun foldGroup(
        record: AppGroupRecordFfi,
        invalidateMembers: Boolean = true,
    ) {
        groupRecordsById = groupRecordsById + (record.groupIdHex to record)
        if (invalidateMembers) invalidateMemberCacheForGroup(record.groupIdHex)
        scheduleRecompute()
    }

    /** Invalidates authoritative roster state while retaining display-only continuity. */
    private fun invalidateMemberCacheForGroup(groupIdHex: String) {
        val hasRetryScheduled = memberFetchRetryJobsByGroup[groupIdHex]?.isActive == true
        val hasSnapshot =
            memberCacheByGroup.containsKey(groupIdHex) ||
                presentationMembersByGroup.containsKey(groupIdHex)
        val hasPendingFetch = groupIdHex in inFlightMemberFetches || hasRetryScheduled
        if (!hasSnapshot && !hasPendingFetch) return
        cancelMemberSnapshotRetry(groupIdHex)
        failedMemberFetches.remove(groupIdHex)
        selfOnlyDirectGraceRetryGroups.remove(groupIdHex)
        memberCacheByGroup[groupIdHex]?.let { members ->
            val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
            presentationMembersByGroup =
                presentationMembersByGroup +
                (groupIdHex to chatListMemberPresentation(members, activeAccountIdHex))
            memberCacheByGroup = memberCacheByGroup - groupIdHex
        }
        memberCacheLifetime.advance()
    }

    private fun memberSnapshotNeedsFetch(groupIdHex: String): Boolean = !memberCacheByGroup.containsKey(groupIdHex)

    /**
     * Seed every current chat row's identifier-only roster before publishing
     * the first local frame. MDK answers each page from its command-ready local
     * projection without profile enrichment or relay work, so member-derived
     * folders, shared groups, and existing-DM detection do not wait for an
     * N-call `groupMembers` fan-out (#1534).
     *
     * The page contract is all-or-nothing. If any requested group is unknown
     * or quarantined, fall back to eight bounded per-group local reads and hold
     * the first frame for at most 500 ms. Reads that miss the cutoff keep
     * running under this bind's lifecycle and publish in coalesced batches.
     */
    private suspend fun seedInitialMemberIdProjection(
        account: String,
        epoch: Long,
    ) {
        val groupIds = chatRows.map { it.groupIdHex }
        if (groupIds.isEmpty()) return
        val expectedCacheEpoch = memberCacheEpoch
        val projections = loadInitialMemberIdProjections(account, groupIds)
        if (projections == null) {
            seedInitialMemberFallback(account, epoch, expectedCacheEpoch, groupIds)
            return
        }
        if (initialMemberProjectionIsCurrent(account, epoch, expectedCacheEpoch)) {
            val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
            // Await only on-device DM peer presentation so the first row
            // cannot flash a short identity or empty avatar. Relay freshness
            // remains in requestProfile below. The degraded fallback uses this
            // same barrier for every roster resolved before the cutoff.
            appState.warmProfilePresentationsBlocking(
                initialDirectPeerProfileIds(projections, activeAccountIdHex),
            )
            if (initialMemberProjectionIsCurrent(account, epoch, expectedCacheEpoch)) {
                applyInitialMemberIdProjections(projections, activeAccountIdHex)
            }
        }
    }

    private suspend fun seedInitialMemberFallback(
        account: String,
        epoch: Long,
        cacheEpoch: Long,
        groupIds: List<String>,
    ) {
        val liveGroupIds = chatRows.mapTo(mutableSetOf()) { it.groupIdHex }
        val pending =
            groupIds
                .asSequence()
                .filter { it in liveGroupIds }
                .distinctBy { it.lowercase() }
                .filter { memberSnapshotNeedsFetch(it) }
                .filterNot { it in inFlightMemberFetches }
                .filterNot { memberFetchRetryJobsByGroup[it]?.isActive == true }
                .toList()
        if (pending.isEmpty()) return
        inFlightMemberFetches.addAll(pending)
        val cutoffStartedAtMs = SystemClock.elapsedRealtime()

        val fallback =
            loadFirstFrameMemberFallback(
                groupIds = pending,
                cutoffMillis = INITIAL_MEMBER_FALLBACK_CUTOFF_MS,
                maxConcurrent = INITIAL_MEMBER_FALLBACK_FANOUT,
            ) { groupIdHex ->
                // Share the controller-wide permit pool with ordinary and
                // retry reads. Otherwise a retry released by one failed
                // fallback could overlap eight still-running fallback reads.
                memberFetchGate.withPermit {
                    memberSnapshotLoader(account, groupIdHex)
                }
            }
        val remainingProfileWarmBudgetMillis =
            (
                INITIAL_MEMBER_FALLBACK_CUTOFF_MS -
                    (SystemClock.elapsedRealtime() - cutoffStartedAtMs)
            ).coerceAtLeast(0L)
        publishInitialMemberFallbackResults(
            results = fallback.firstFrameResults,
            account = account,
            epoch = epoch,
            cacheEpoch = cacheEpoch,
            scheduleRecomputeAfterPublish = false,
            profileWarmBudgetMillis = remainingProfileWarmBudgetMillis,
        )
        if (fallback.remainingCount == 0) return

        CoroutineScope(coroutineContext).launch {
            var remaining = fallback.remainingCount
            while (remaining > 0) {
                val completed = mutableListOf(fallback.remainingResults.receive())
                remaining -= 1
                while (remaining > 0) {
                    val next = fallback.remainingResults.tryReceive().getOrNull() ?: break
                    completed += next
                    remaining -= 1
                }
                publishInitialMemberFallbackResults(
                    results = completed,
                    account = account,
                    epoch = epoch,
                    cacheEpoch = cacheEpoch,
                    scheduleRecomputeAfterPublish = true,
                )
            }
        }
    }

    private suspend fun publishInitialMemberFallbackResults(
        results: List<FirstFrameMemberFallbackResult<List<AppGroupMemberRecordFfi>>>,
        account: String,
        epoch: Long,
        cacheEpoch: Long,
        scheduleRecomputeAfterPublish: Boolean,
        profileWarmBudgetMillis: Long? = null,
    ) {
        when {
            results.isEmpty() -> Unit
            rejectStaleInitialMemberFallbackResults(results, account, epoch, cacheEpoch) -> Unit
            !warmInitialMemberFallbackProfiles(results, profileWarmBudgetMillis) -> {
                continueInitialMemberFallbackProfileWarm(results, account, epoch, cacheEpoch)
            }
            rejectStaleInitialMemberFallbackResults(results, account, epoch, cacheEpoch) -> Unit
            else -> {
                applyInitialMemberFallbackResults(results, epoch, cacheEpoch)
                scheduleRecomputeIfRequested(scheduleRecomputeAfterPublish)
            }
        }
    }

    private fun rejectStaleInitialMemberFallbackResults(
        results: List<FirstFrameMemberFallbackResult<List<AppGroupMemberRecordFfi>>>,
        account: String,
        epoch: Long,
        cacheEpoch: Long,
    ): Boolean {
        val stale = !initialMemberProjectionIsCurrent(account, epoch, cacheEpoch)
        if (stale && isActiveBindEpoch(epoch)) {
            results.forEach { inFlightMemberFetches.remove(it.groupIdHex) }
            schedulePendingMemberFetches(results.map { it.groupIdHex })
        }
        return stale
    }

    private suspend fun warmInitialMemberFallbackProfiles(
        results: List<FirstFrameMemberFallbackResult<List<AppGroupMemberRecordFfi>>>,
        budgetMillis: Long?,
    ): Boolean {
        val liveResults =
            results.filter { result ->
                chatRowsByGroup.containsKey(chatRowKey(result.groupIdHex))
            }
        val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
        val projections =
            liveResults.mapNotNull { result ->
                result.result.getOrNull()?.let { members ->
                    AppGroupMemberIdsFfi(result.groupIdHex, members.map { it.memberIdHex }, adminIdsHex = emptyList())
                }
            }
        val peerProfileIds = initialDirectPeerProfileIds(projections, activeAccountIdHex)
        return when {
            peerProfileIds.isEmpty() -> true
            budgetMillis == null -> {
                appState.warmProfilePresentationsBlocking(peerProfileIds)
                true
            }
            budgetMillis == 0L -> false
            else ->
                withTimeoutOrNull(budgetMillis) {
                    appState.warmProfilePresentationsBlocking(peerProfileIds)
                    true
                } == true
        }
    }

    private suspend fun continueInitialMemberFallbackProfileWarm(
        results: List<FirstFrameMemberFallbackResult<List<AppGroupMemberRecordFfi>>>,
        account: String,
        epoch: Long,
        cacheEpoch: Long,
    ) {
        // The 500 ms first-frame budget is authoritative. Keep these rosters
        // unresolved for that frame rather than publishing a DM peer without
        // its locally persisted name/avatar, then finish the same consumed
        // results under the bind lifecycle.
        CoroutineScope(coroutineContext).launch {
            publishInitialMemberFallbackResults(
                results = results,
                account = account,
                epoch = epoch,
                cacheEpoch = cacheEpoch,
                scheduleRecomputeAfterPublish = true,
            )
        }
    }

    private fun applyInitialMemberFallbackResults(
        results: List<FirstFrameMemberFallbackResult<List<AppGroupMemberRecordFfi>>>,
        epoch: Long,
        cacheEpoch: Long,
    ) {
        results.forEach { completed ->
            val groupIdHex = completed.groupIdHex
            inFlightMemberFetches.remove(groupIdHex)
            if (!chatRowsByGroup.containsKey(chatRowKey(groupIdHex))) return@forEach
            completed.result.fold(
                onSuccess = { members ->
                    applyFetchedMemberSnapshot(
                        groupIdHex = groupIdHex,
                        members = members,
                        epoch = epoch,
                        cacheEpoch = cacheEpoch,
                        scheduleRecomputeAfterPublish = false,
                    )
                },
                onFailure = { throwable ->
                    chatsDebug(throwable) {
                        "initial member fallback failed group=${groupIdHex.take(8)}: " +
                            (throwable.message ?: throwable.javaClass.simpleName)
                    }
                    markMemberSnapshotFetchFailed(groupIdHex)
                    scheduleMemberSnapshotRetry(groupIdHex, epoch)
                },
            )
        }
    }

    private fun scheduleRecomputeIfRequested(requested: Boolean) {
        if (requested) scheduleRecompute()
    }

    private suspend fun loadInitialMemberIdProjections(
        account: String,
        groupIds: List<String>,
    ): List<AppGroupMemberIdsFfi>? =
        runCatchingCancellable {
            loadGroupMemberIdsPages(groupIds) { page ->
                appState.marmotIo { groupMemberIdsPage(account, page) }
            }
        }.onFailure { error ->
            chatsDebug(error) {
                "initial member-id projection failed account=${account.take(8)}: " +
                    (error.message ?: error.javaClass.simpleName)
            }
        }.getOrNull()

    private fun initialMemberProjectionIsCurrent(
        account: String,
        epoch: Long,
        expectedCacheEpoch: Long,
    ): Boolean =
        initialMemberFallbackGenerationIsCurrent(
            expectedAccount = account,
            expectedBindEpoch = epoch,
            expectedCacheEpoch = expectedCacheEpoch,
            currentAccount = accountRef,
            currentBindEpoch = bindEpoch,
            currentCacheEpoch = memberCacheEpoch,
            lifecycleActive = isActiveBindEpoch(epoch),
        )

    private fun initialDirectPeerProfileIds(
        projections: List<AppGroupMemberIdsFfi>,
        activeAccountIdHex: String?,
    ): List<String> {
        val rowsByGroup = chatRows.associateBy { chatRowKey(it.groupIdHex) }
        return initialDirectPeerProfileIds(
            projections = projections,
            activeAccountIdHex = activeAccountIdHex,
        ) { groupIdHex, memberCount ->
            val row = rowsByGroup[chatRowKey(groupIdHex)]
            row != null && GroupProjector.isDm(row.conversationKind, memberCount, row.groupName)
        }
    }

    private fun applyInitialMemberIdProjections(
        projections: List<AppGroupMemberIdsFfi>,
        activeAccountIdHex: String?,
        requestProfileRefresh: Boolean = true,
    ) {
        val updatedCache = memberCacheByGroup.toMutableMap()
        var updatedRemovedGroupIds = removedGroupIds
        projections.forEach { projection ->
            val groupIdHex = projection.groupIdHex
            val members = memberRecordsFromIds(projection.memberIdsHex, activeAccountIdHex)
            // Same readiness gate as the streaming path: an empty or
            // self-only-DM roster from the initial page can be a transient
            // catch-up result. Caching it would pin the row on the Unknown
            // fallback forever — memberSnapshotNeedsFetch never retries a
            // cached key — and drop the last-known presentation for nothing.
            // Skipped groups are refetched by the post-bind enrichment sweep,
            // which owns the full gate and retry machinery.
            if (!initialPageRosterReadyToCache(projection.groupIdHex, members, activeAccountIdHex)) {
                return@forEach
            }
            updatedCache[groupIdHex] = members
            updatedRemovedGroupIds =
                if (
                    activeAccountIdHex != null &&
                    members.none { GroupProjector.isActiveAccountMember(it, activeAccountIdHex) }
                ) {
                    updatedRemovedGroupIds + groupIdHex
                } else {
                    updatedRemovedGroupIds - groupIdHex
                }
            if (requestProfileRefresh) {
                members.map { it.memberIdHex }.forEach(appState::requestProfile)
            }
            presentationMembersByGroup = presentationMembersByGroup - groupIdHex
            cancelMemberSnapshotRetry(groupIdHex)
            memberFetchRetryBackoffTierByGroup.remove(groupIdHex)
            failedMemberFetches.remove(groupIdHex)
            selfOnlyDirectGraceRetryGroups.remove(groupIdHex)
        }
        memberCacheByGroup = updatedCache
        removedGroupIds = updatedRemovedGroupIds
        memberSnapshotsRevision += 1L
    }

    // The initial member-id page is gated with the same conversation context
    // the streaming path derives: a self-only roster for a direct conversation
    // is as transient as an empty one and must be retried, not cached.
    private fun initialPageRosterReadyToCache(
        groupIdHex: String,
        members: List<AppGroupMemberRecordFfi>,
        activeAccountIdHex: String?,
    ): Boolean =
        memberSnapshotReadyToCache(
            members = members,
            knownSelfRemoval = knownSelfRemovalFor(groupIdHex),
            directConversation = directConversationCandidateFor(groupIdHex, members),
            activeAccountIdHex = activeAccountIdHex,
            selfOnlyDirectGraceElapsed = groupIdHex in selfOnlyDirectGraceRetryGroups,
        )

    /**
     * Whether this account's removal from the group is already known locally,
     * which makes an empty roster terminal rather than a hydration gap.
     */
    private fun knownSelfRemovalFor(groupIdHex: String): Boolean =
        groupIdHex in removedGroupIds ||
            chatRowsByGroup[chatRowKey(groupIdHex)]?.selfMembership?.isNonMember() == true ||
            groupRecordsById[groupIdHex]?.selfMembership?.isNonMember() == true

    /**
     * Whether the group should be treated as a direct conversation for roster
     * readiness, including the unresolved case where the projection has not
     * classified an unnamed low-headcount conversation yet. Shared by the
     * initial-page and streaming gates so the two cannot drift — an earlier
     * copy of this heuristic living in only one of them is what let a
     * self-only DM roster be cached and pin an Unknown title.
     */
    private fun directConversationCandidateFor(
        groupIdHex: String,
        members: List<AppGroupMemberRecordFfi>,
    ): Boolean {
        val row = chatRowsByGroup[chatRowKey(groupIdHex)]
        val memberCount = GroupProjector.uniqueMemberCount(members)
        val groupName = row?.groupName ?: groupRecordsById[groupIdHex]?.name.orEmpty()
        val unresolvedDirectConversation =
            row?.conversationKind == ChatConversationKindFfi.UNKNOWN &&
                memberCount <= 1 &&
                GroupProjector.isUnnamed(groupName)
        return unresolvedDirectConversation ||
            GroupProjector.isDm(
                conversationKind = row?.conversationKind,
                memberCount = memberCount,
                name = groupName,
            )
    }

    private fun recordMemberDerivedLocalReadyIfComplete() {
        val account = accountRef ?: return
        if (chatRows.all { memberCacheByGroup.containsKey(it.groupIdHex) }) {
            appState.recordAccountSwitchMemberDerivedLocalReady(account, chatRows.size)
        }
    }

    // Marmot's `set_group_archived` writes local state + saves but emits no
    // ProjectionUpdated event, so the chat-list snapshot stays stale until the
    // next account switch (issue: unarchive doesn't move chat out of archived
    // section). Callers in ConversationController forward the updated record
    // here via AppState so the chat list reflects the new archived flag.
    // Optimistically bump a group's chat-list row to a just-sent message so
    // returning to the list paints the new preview immediately, instead of one
    // frame of the prior last-message before the chat-list stream catches up.
    // The real stream update reconciles this shortly after. See #900.
    internal fun applyOptimisticSentPreview(
        groupIdHex: String,
        preview: ChatListMessagePreviewFfi,
    ): Boolean {
        val rowKey = chatRowKey(groupIdHex)
        val row = chatRowsByGroup[rowKey].takeIf { accountRef != null } ?: return false
        val state =
            optimisticChatListPreviewByGroup.getOrPut(rowKey) {
                val baselineActivitySequence = activitySequenceByGroup[rowKey] ?: 0uL
                OptimisticChatListPreviewState(
                    baselineRow = row,
                    baselineActivitySequence = baselineActivitySequence,
                ).also { state ->
                    rememberBaselineActivitySequence(state, row, baselineActivitySequence)
                }
            }
        if (state.failedFallbackEntry?.preview?.messageIdHex == preview.messageIdHex) {
            state.failedFallbackEntry = null
        }
        state.entries[preview.messageIdHex] =
            OptimisticChatListPreviewEntry(
                preview = preview,
                activitySequence = nextChatActivitySequence(),
            )
        materializeOptimisticChatListPreview(rowKey, state)
        scheduleRecompute()
        return true
    }

    /**
     * Rebind a pending optimistic preview's parsed Markdown once the async
     * hydration lands, keeping the preview's tokens equal to what a projected
     * echo of the same message will carry. Deliberately narrower than
     * [applyOptimisticSentPreview]: no activity bump, and an entry already
     * confirmed or retired keeps its authoritative state.
     */
    internal fun hydrateOptimisticSentPreviewTokens(
        groupIdHex: String,
        messageIdHex: String,
        tokens: MarkdownDocumentFfi,
    ) {
        val rowKey = chatRowKey(groupIdHex)
        val state = optimisticChatListPreviewByGroup[rowKey].takeIf { accountRef != null } ?: return
        val entry = state.entries[messageIdHex]?.takeIf { it.confirmedMessageIdHex == null } ?: return
        state.entries[messageIdHex] = entry.copy(preview = entry.preview.copy(contentTokens = tokens))
        materializeOptimisticChatListPreview(rowKey, state)
        scheduleRecompute()
    }

    internal fun commitOptimisticSentPreview(
        groupIdHex: String,
        optimisticMessageIdHex: String,
        confirmedMessageIdHex: String,
    ) {
        val rowKey = chatRowKey(groupIdHex)
        val state = optimisticChatListPreviewByGroup[rowKey].takeIf { accountRef != null } ?: return
        val entry = state.entries[optimisticMessageIdHex] ?: return
        state.failedFallbackEntry =
            state.failedFallbackEntry?.takeIf {
                it.activitySequence >= entry.activitySequence
            }
        state.confirmedActivitySequenceById[confirmedMessageIdHex] = entry.activitySequence
        while (state.confirmedActivitySequenceById.size > MAX_CHAT_LIST_ACTIVITY_SEQUENCE_HISTORY) {
            state.confirmedActivitySequenceById.remove(state.confirmedActivitySequenceById.keys.first())
        }
        state.entries[optimisticMessageIdHex] =
            entry.copy(
                preview =
                    entry.preview.copy(
                        messageIdHex = confirmedMessageIdHex,
                        deliveryState = ChatListMessageDeliveryStateFfi.DELIVERED,
                    ),
                confirmedMessageIdHex = confirmedMessageIdHex,
                pendingAuthoritativeRow = null,
            )
        // Re-evaluate an echo that arrived while this send was still pending.
        // An exact confirmed id retires this entry; an id mismatch is offered to
        // the next still-pending identical send instead.
        entry.pendingAuthoritativeRow?.let { row -> foldOptimisticChatListBaseline(state, row) }
        state.entries[optimisticMessageIdHex]?.let { committedEntry ->
            state.entries.entries.removeAll { (messageIdHex, candidate) ->
                messageIdHex != optimisticMessageIdHex &&
                    candidate.confirmedMessageIdHex != null &&
                    candidate.activitySequence < committedEntry.activitySequence
            }
            val supersededByNewerCommit =
                state.entries.any { (messageIdHex, candidate) ->
                    messageIdHex != optimisticMessageIdHex &&
                        candidate.confirmedMessageIdHex != null &&
                        candidate.activitySequence > committedEntry.activitySequence
                }
            if (committedEntry.activitySequence <= state.baselineActivitySequence || supersededByNewerCommit) {
                state.entries.remove(optimisticMessageIdHex)
            }
        }
        materializeOptimisticChatListPreview(rowKey, state)
        scheduleRecompute()
    }

    /**
     * Flips a still-tracked optimistic preview to FAILED instead of erasing
     * it. A failed send must stay visible as failed on the row — silently
     * reverting the list to the prior message while the conversation shows a
     * failed bubble breaks trust in the list. Genuine abandonment
     * (discard, own eviction) still uses [rollbackOptimisticSentPreview].
     */
    internal fun failOptimisticSentPreview(
        groupIdHex: String,
        optimisticMessageIdHex: String,
    ) {
        val rowKey = chatRowKey(groupIdHex)
        val state = optimisticChatListPreviewByGroup[rowKey].takeIf { accountRef != null } ?: return
        val entry = state.entries[optimisticMessageIdHex] ?: return
        state.entries[optimisticMessageIdHex] =
            entry.copy(
                preview = entry.preview.copy(deliveryState = ChatListMessageDeliveryStateFfi.FAILED),
            )
        materializeOptimisticChatListPreview(rowKey, state)
        scheduleRecompute()
    }

    internal fun rollbackOptimisticSentPreview(
        groupIdHex: String,
        optimisticMessageIdHex: String,
    ) {
        val rowKey = chatRowKey(groupIdHex)
        val state = optimisticChatListPreviewByGroup[rowKey].takeIf { accountRef != null } ?: return
        val removedEntry = state.entries.remove(optimisticMessageIdHex) != null
        val removedFallback = state.failedFallbackEntry?.preview?.messageIdHex == optimisticMessageIdHex
        if (removedFallback) state.failedFallbackEntry = null
        if (!removedEntry && !removedFallback) return
        materializeOptimisticChatListPreview(rowKey, state)
        scheduleRecompute()
    }

    fun applyLocalGroupUpdate(record: AppGroupRecordFfi) {
        applyLocalGroupProjection(record, members = null)
    }

    fun applyLocalGroupDetails(
        record: AppGroupRecordFfi,
        members: List<AppGroupMemberRecordFfi>,
    ) {
        applyLocalGroupProjection(record, members)
    }

    /** Applies a local group mutation and invalidates any older roster fetch. */
    private fun applyLocalGroupProjection(
        record: AppGroupRecordFfi,
        members: List<AppGroupMemberRecordFfi>?,
    ) {
        if (accountRef == null) return
        val rowKey = chatRowKey(record.groupIdHex)
        if (groupRecordsById[record.groupIdHex] == null && !chatRowsByGroup.containsKey(rowKey)) return
        if (members != null) {
            memberCacheLifetime.advance()
            presentationMembersByGroup = presentationMembersByGroup - record.groupIdHex
            cancelMemberSnapshotRetry(record.groupIdHex)
            memberFetchRetryBackoffTierByGroup.remove(record.groupIdHex)
            failedMemberFetches.remove(record.groupIdHex)
            selfOnlyDirectGraceRetryGroups.remove(record.groupIdHex)
            members
                .map { it.memberIdHex }
                .filter { it.isNotBlank() }
                .forEach(appState::requestProfile)
            val updated =
                applyAuthoritativeChatListMembers(
                    groupIdHex = record.groupIdHex,
                    members = members,
                    activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex,
                    memberCacheByGroup = memberCacheByGroup,
                    removedGroupIds = removedGroupIds,
                )
            memberCacheByGroup = updated.memberCacheByGroup
            removedGroupIds = updated.removedGroupIds
            memberSnapshotsRevision += 1L
        }
        // chatListItemFromProjection reads row.archived / row.pendingConfirmation
        // (not just the group record), so patch both the chat row and the group
        // record to keep them consistent.
        (optimisticChatListPreviewByGroup[rowKey]?.baselineRow ?: chatRowsByGroup[rowKey])?.let { row ->
            val updated =
                row.copy(
                    archived = record.archived,
                    pendingConfirmation = record.pendingConfirmation,
                    groupName = record.name.ifBlank { row.groupName },
                )
            optimisticChatListPreviewByGroup[rowKey]?.let { state ->
                state.baselineRow = updated
                materializeOptimisticChatListPreview(rowKey, state)
            } ?: run {
                chatRowsByGroup[rowKey] = updated
            }
        }
        foldGroup(record, invalidateMembers = members == null)
    }

    fun applyProfileGroupDetails(
        account: String,
        details: GroupDetailsFfi,
    ) {
        if (accountRef == null) return
        val applied = applyAuthoritativeGroupDetails(details)
        val groupIdHex = applied.group.groupIdHex
        if (groupRecordsById[groupIdHex] == null && !chatRowsByGroup.containsKey(chatRowKey(groupIdHex))) return
        cacheAppliedGroupMembers(appState, account, groupIdHex, applied.members)
        applyLocalGroupProjection(applied.group, applied.members)
    }

    // Project every current chat row into a ChatListItem. Reads chatRows (kept
    // current by the bind loop even when recompute is deferred behind an open
    // conversation, #6), so on-demand callers — shared groups, DM lookup,
    // by-id resolution for navigation — see freshly created/updated groups
    // instead of the stale `items` snapshot.
    // Last-known roster presentation for a row whose live caches are cold: a
    // fresh bind (cold start, account switch, controller recreation) starts
    // with empty per-bind maps while the account-scoped snapshot cache may
    // still hold the roster from the previous session's group-details or
    // conversation reads. Falling back keeps a previously resolved title and
    // avatar on screen instead of the Unknown placeholder until hydration
    // catches up. No new state: this reads an existing cache.
    private fun lastKnownPresentation(
        groupIdHex: String,
        activeAccountIdHex: String?,
    ): ChatListMemberPresentation? {
        // An authoritative roster wins downstream — skip the fallback lookup
        // entirely so hydrated rows pay no per-recompute cache reads.
        if (memberCacheByGroup.containsKey(groupIdHex)) return null
        return presentationMembersByGroup[groupIdHex]
            ?: appState
                .cachedGroupMemberSnapshot(accountRef, groupIdHex)
                ?.members
                ?.let { chatListMemberPresentation(it, activeAccountIdHex) }
    }

    private fun currentProjectedItems(activeAccountIdHex: String? = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex): List<ChatListItem> =
        chatRows.map { authoritativeRow ->
            val row = optimisticArchiveRow(authoritativeRow)
            chatListItemFromProjection(
                row = row,
                group = optimisticArchiveGroup(row.groupIdHex, groupRecordsById[row.groupIdHex]),
                activeAccountIdHex = activeAccountIdHex,
                members = memberCacheByGroup[row.groupIdHex],
                presentationMembers = lastKnownPresentation(row.groupIdHex, activeAccountIdHex),
                previewTokens = chatRowPreviewMarkdownSource(row)?.let { previewTokensByText[it] },
                resolvedMediaPreviewFallback = row.lastMessage?.messageIdHex?.let { mediaPreviewFallbackByMessageId[it] },
                removed = row.groupIdHex in removedGroupIds,
                activitySequence = activitySequenceByGroup[chatRowKey(row.groupIdHex)] ?: 0uL,
            )
        }

    private fun boundAccountIdHex(): String? {
        val ref = accountRef ?: return null
        return appState.accounts.firstOrNull { it.label == ref }?.accountIdHex
    }

    private fun projectChatRow(
        authoritativeRow: ChatListRowFfi,
        activeAccountIdHex: String? = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex,
    ): ChatListItem {
        val row = optimisticArchiveRow(authoritativeRow)
        return chatListItemFromProjection(
            row = row,
            group = optimisticArchiveGroup(row.groupIdHex, groupRecordsById[row.groupIdHex]),
            activeAccountIdHex = activeAccountIdHex,
            members = memberCacheByGroup[row.groupIdHex],
            presentationMembers = lastKnownPresentation(row.groupIdHex, activeAccountIdHex),
            previewTokens = chatRowPreviewMarkdownSource(row)?.let { previewTokensByText[it] },
            resolvedMediaPreviewFallback = row.lastMessage?.messageIdHex?.let { mediaPreviewFallbackByMessageId[it] },
            removed = row.groupIdHex in removedGroupIds,
            activitySequence = activitySequenceByGroup[chatRowKey(row.groupIdHex)] ?: 0uL,
        )
    }

    private fun optimisticArchiveRow(row: ChatListRowFfi): ChatListRowFfi =
        optimisticArchiveByGroup[chatRowKey(row.groupIdHex)]
            ?.let { intent -> row.copy(archived = intent.archived) }
            ?: row

    private fun optimisticArchiveGroup(
        groupIdHex: String,
        group: AppGroupRecordFfi?,
    ): AppGroupRecordFfi? =
        optimisticArchiveByGroup[chatRowKey(groupIdHex)]
            ?.let { intent -> group?.copy(archived = intent.archived) }
            ?: group

    fun sharedGroupsWith(
        accountIdHex: String,
        activeAccountIdHex: String?,
    ): List<ChatListItem> {
        val normalizedAccount = accountIdHex.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val normalizedActive = activeAccountIdHex?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return chatRows
            .asSequence()
            .filter { row ->
                val members = memberCacheByGroup[row.groupIdHex] ?: return@filter false
                members.any { it.memberIdHex.equals(normalizedAccount, ignoreCase = true) } &&
                    members.any { it.memberIdHex.equals(normalizedActive, ignoreCase = true) }
            }.map { projectChatRow(it, activeAccountIdHex) }
            .toList()
    }

    fun profileAddableGroups(
        targetAccountIdHex: String,
        activeAccountIdHex: String?,
    ): List<ChatListItem> = profileAddableGroupsState(targetAccountIdHex, activeAccountIdHex).groups

    internal fun profileAddableGroupsState(
        targetAccountIdHex: String,
        activeAccountIdHex: String?,
    ): ProfileGroupPickerState =
        profileAddableGroupsState(
            items = currentProjectedItems(activeAccountIdHex),
            targetAccountIdHex = targetAccountIdHex,
            activeAccountIdHex = activeAccountIdHex,
            failedGroupIds = failedMemberFetches,
        )

    internal fun profilePromotableGroupsState(
        targetAccountIdHex: String,
        activeAccountIdHex: String?,
    ): ProfileGroupPickerState =
        profilePromotableGroupsState(
            items = currentProjectedItems(activeAccountIdHex),
            targetAccountIdHex = targetAccountIdHex,
            activeAccountIdHex = activeAccountIdHex,
            failedGroupIds = failedMemberFetches,
        )

    /**
     * The confirmed, still-active 1:1 DM with [reference] (npub or hex), or
     * null. A match must be an *implicit* DM in its current MLS state: no
     * custom group name, the active account still a member, and the live roster
     * exactly `{me, target}` (see [GroupProjector.isImplicitDmWith]). The
     * counterparty is stored as hex, so compare in both hex and npub forms.
     *
     * This is deliberately strict (#825): a two-person conversation that was
     * renamed, or one the target was removed from (leaving the user talking to
     * themselves), is no longer a DM with that person, so Start-DM must open a
     * fresh DM instead of resurfacing the stale group. There is intentionally
     * no closest-historical-match fallback. Shared with the new-chat sheet so
     * "open existing DM" and "don't create a duplicate DM" agree on what counts
     * as an existing one.
     */
    fun existingDirectChat(reference: String): ChatListItem? = existingDirectChatCandidates(reference).firstOrNull()

    /**
     * Every current implicit DM with [reference], best first per
     * [directChatPreferenceOrder]. Ordering matters because duplicates are
     * reachable — both sides can each have created a DM — and the tap that opens
     * one must land on the same conversation every time.
     */
    private fun existingDirectChatCandidates(reference: String): List<ChatListItem> {
        val normalizedReference = reference.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
        return chatRows
            .asSequence()
            .filter { !it.pendingConfirmation }
            .mapNotNull { row ->
                val members = memberCacheByGroup[row.groupIdHex] ?: return@mapNotNull null
                val match =
                    GroupProjector.isImplicitDmWith(
                        members = members,
                        name = row.groupName,
                        activeAccountIdHex = activeAccountIdHex,
                        targetIdHex = normalizedReference,
                        equivalentTarget = { other -> appState.npub(other).equals(normalizedReference, ignoreCase = true) },
                    )
                if (match) projectChatRow(row) else null
            }.sortedWith(directChatPreferenceOrder)
            .toList()
    }

    fun chatItemForGroup(groupIdHex: String): ChatListItem? {
        val row = chatRowsByGroup[chatRowKey(groupIdHex)] ?: chatRows.firstOrNull { it.groupIdHex.equals(groupIdHex, ignoreCase = true) }
        return row?.let { projectChatRow(it) }
    }

    /**
     * Revalidate picker provenance against the current backing row and an
     * authoritative local [groupDetails] read (#1701). Uses [chatRowsByGroup],
     * not the debounced [items] snapshot.
     */
    internal suspend fun resolveProvenanceDirectChat(
        provenanceGroupIdHex: String?,
        targetReference: String,
    ): NewMessageDirectChatResolution {
        val unavailable = NewMessageDirectChatResolution(item = null, createRequired = false)
        val account = accountRef ?: return unavailable
        return resolveDirectChatGroup(
            account = account,
            bindAccount = account,
            epoch = bindEpoch,
            activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex,
            groupIdHex = provenanceGroupIdHex,
            targetReference = targetReference,
        )
    }

    /**
     * Authoritatively search every current direct-chat row except stale picker
     * provenance. This covers identifier/QR taps and cold member-cache misses,
     * while refusing creation if any candidate could not be read locally.
     */
    internal suspend fun resolveExistingDirectChat(
        targetReference: String,
        excludingGroupIdHex: String? = null,
    ): NewMessageDirectChatResolution {
        val unavailable = NewMessageDirectChatResolution(item = null, createRequired = false)
        val account = accountRef ?: return unavailable
        val bindAccount = account
        val epoch = bindEpoch
        val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
        val candidateGroupIds =
            rankedDirectChatCandidates(
                candidates =
                    chatRows
                        .asSequence()
                        .filterNot { it.pendingConfirmation }
                        .map(::projectChatRow)
                        .asIterable(),
                excludingGroupIdHex = excludingGroupIdHex,
            ).map(ChatListItem::id)
        return resolveExistingDirectChatCandidates(candidateGroupIds) { groupIdHex ->
            resolveDirectChatGroup(
                account = account,
                bindAccount = bindAccount,
                epoch = epoch,
                activeAccountIdHex = activeAccountIdHex,
                groupIdHex = groupIdHex,
                targetReference = targetReference,
            )
        }
    }

    private suspend fun resolveDirectChatGroup(
        account: String,
        bindAccount: String,
        epoch: Long,
        activeAccountIdHex: String?,
        groupIdHex: String?,
        targetReference: String,
    ): NewMessageDirectChatResolution {
        val normalizedTarget = targetReference.trim()
        return existingDirectChatFromProvenance(
            provenanceGroupIdHex = groupIdHex,
            targetReference = targetReference,
            activeAccountIdHex = activeAccountIdHex,
            equivalentTarget = { other -> appState.npub(other).equals(normalizedTarget, ignoreCase = true) },
            chatItemForGroup = ::chatItemForGroup,
            authoritativeGroupDetails = { currentGroupIdHex ->
                runCatchingCancellable {
                    appState.marmotIo { groupDetails(account, currentGroupIdHex) }
                }.getOrNull()?.let(::applyAuthoritativeGroupDetails)
            },
            accountStillBound = { accountRef == bindAccount && isActiveBindEpoch(epoch) },
        )
    }

    // Lightweight membership probe over the raw rows — no per-row ChatListItem
    // projection.
    fun containsGroup(groupIdHex: String): Boolean = chatRowsByGroup.containsKey(chatRowKey(groupIdHex))

    /**
     * Chats the active account can forward a message into, recent first.
     *
     * A forward fans a fresh send into each selected group (encrypted under
     * that group's own state — see [WhiteNoiseAppState.forwardText]), so the
     * targets are the same confirmed conversations the chat list shows: an
     * invite (`pendingConfirmation`) is not yet a joined group the account can
     * send into, so it is excluded. Archived chats are included by default so a
     * forward can reach a muted/parked conversation (the issue leaves
     * archived-target handling open; allowing it keeps the picker complete and
     * is the less surprising default). Ordering reuses [sortChatListItems] so
     * the picker matches the chat list's "recent first" feel.
     */
    fun forwardTargets(): List<ChatListItem> =
        sortChatListItems(
            currentProjectedItems().filter { item ->
                isEligibleForwardTarget(
                    item = item,
                    activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex,
                )
            },
        )

    /** Applies one ordered update emitted by the active chat-list subscription. */
    @VisibleForTesting
    internal fun applyChatListSubscriptionUpdate(
        accountRef: String,
        update: ChatListSubscriptionUpdateFfi,
    ) {
        when (update) {
            is ChatListSubscriptionUpdateFfi.Row -> {
                val row = update.row
                requestChatRowProfiles(row)
                chatsDebug {
                    "chat list update account=${accountRef.take(8)} trigger=${update.trigger} ${row.debugSummary()}"
                }
                foldChatRow(row, update.trigger)
            }
            is ChatListSubscriptionUpdateFfi.Snapshot -> {
                chatsDebug {
                    "chat list snapshot account=${accountRef.take(8)} " +
                        "trigger=${update.trigger} rows=${update.rows.size}"
                }
                // Contract: atomically replace the held rows and drop any prior
                // row absent from the snapshot.
                update.rows.forEach(::requestChatRowProfiles)
                replaceChatRows(update.rows)
                scheduleRecompute()
            }
            is ChatListSubscriptionUpdateFfi.RemoveRow -> {
                chatsDebug {
                    "chat list remove account=${accountRef.take(8)} trigger=${update.trigger} id=${update.groupIdHex.take(8)}"
                }
                removeChatRow(update.groupIdHex)
            }
        }
    }

    /** Folds one authoritative row and mirrors it into any mounted conversation. */
    private fun foldChatRow(
        row: ChatListRowFfi,
        trigger: ChatListUpdateTriggerFfi? = null,
    ) {
        if (trigger == ChatListUpdateTriggerFfi.MUTE_CHANGED || trigger == ChatListUpdateTriggerFfi.SNAPSHOT_REFRESH) {
            appState.acceptAuthoritativeMuteProjection(accountRef, row.groupIdHex, row.muted, row.mutedUntilMs)
        }
        val key = chatRowKey(row.groupIdHex)
        val state = optimisticChatListPreviewByGroup[key]
        val current = state?.baselineRow ?: chatRowsByGroup[key]
        val folded =
            when {
                current == null -> row
                trigger != null -> reduceSubscriptionChatListRow(current, row, trigger)
                else -> row
            }
        val observesNewActivity = observesSubscriptionActivity(current, folded, trigger)
        if (current != null && row.unreadCount > folded.unreadCount) {
            logStaleChatListUnreadRejected(
                keptUnread = folded.unreadCount,
                rejectedUnread = row.unreadCount,
            )
        }
        if (folded == current) {
            appState.publishConversationChatListRow(accountRef, folded)
            return
        }
        val membershipChanged = current == null
        val effectivePublicationRow =
            if (state == null) {
                chatRowsByGroup[key] = folded
                if (shouldAdvanceChatActivitySequence(current, folded, observesNewActivity)) {
                    activitySequenceByGroup[key] = nextChatActivitySequence()
                }
                folded
            } else {
                foldOptimisticChatListBaseline(
                    state,
                    folded,
                    acceptBackwardActivity = trigger == ChatListUpdateTriggerFfi.LAST_MESSAGE_DELETED,
                    observesNewActivity = observesNewActivity,
                ).also {
                    materializeOptimisticChatListPreview(key, state)
                }
            }
        appState.publishConversationChatListRow(accountRef, effectivePublicationRow)
        if (membershipChanged) noteMaterializedGroupMembershipChanged()
        scheduleRecompute()
    }

    /** Replaces one snapshot row and returns the metadata row safe to publish. */
    private fun replaceChatRow(
        key: String,
        row: ChatListRowFfi,
        previous: ChatListRowFfi?,
        previousSequence: ULong?,
        wasMaterialized: Boolean,
        optimisticState: OptimisticChatListPreviewState?,
    ): ChatListRowFfi {
        if (optimisticState != null) {
            return foldOptimisticChatListBaseline(optimisticState, row, acceptBackwardActivity = true).also {
                materializeOptimisticChatListPreview(key, optimisticState)
            }
        }
        chatRowsByGroup[key] = row
        activitySequenceByGroup[key] =
            when {
                previous == null && wasMaterialized -> nextChatActivitySequence()
                previous != null && chatListActivityAdvanced(previous, row) -> nextChatActivitySequence()
                else -> previousSequence ?: 0uL
            }
        return row
    }

    /**
     * Fold a chat-list row returned synchronously from a mark-read FFI call.
     * The chat-list subscription normally carries unread deltas, but
     * [markTimelineMessageRead]'s authoritative projection is its return value;
     * applying it here keeps badges and reopen dividers current even when no
     * OS notification was posted (issue #1251).
     */
    fun applyChatListRow(row: ChatListRowFfi) {
        if (accountRef == null) return
        val key = chatRowKey(row.groupIdHex)
        val current = optimisticChatListPreviewByGroup[key]?.baselineRow ?: chatRowsByGroup[key]
        if (current == null) {
            foldChatRow(row)
            return
        }
        val merged = mergeMarkReadChatListRow(current, row) ?: return
        foldChatRow(merged)
    }

    /** Replaces the chat-list window and refreshes mounted conversation metadata. */
    private fun replaceChatRows(rows: List<ChatListRowFfi>) {
        rows.forEach {
            appState.acceptAuthoritativeMuteProjection(accountRef, it.groupIdHex, it.muted, it.mutedUntilMs)
        }
        val previousRowsByGroup =
            chatRowsByGroup.keys.associateWith { key ->
                optimisticChatListPreviewByGroup[key]?.baselineRow ?: chatRowsByGroup.getValue(key)
            }
        val previousKeys = previousRowsByGroup.keys
        val previousSequences =
            previousRowsByGroup.keys.associateWith { key ->
                optimisticChatListPreviewByGroup[key]?.baselineActivitySequence
                    ?: activitySequenceByGroup[key]
                    ?: 0uL
            }
        val wasMaterialized = previousRowsByGroup.isNotEmpty()
        chatRowsByGroup.clear()
        activitySequenceByGroup.clear()
        rows.forEach { row ->
            val key = chatRowKey(row.groupIdHex)
            val previous = previousRowsByGroup[key]
            val effectivePublicationRow =
                replaceChatRow(
                    key = key,
                    row = row,
                    previous = previous,
                    previousSequence = previousSequences[key],
                    wasMaterialized = wasMaterialized,
                    optimisticState = optimisticChatListPreviewByGroup[key],
                )
            appState.publishConversationChatListRow(accountRef, effectivePublicationRow)
        }
        optimisticChatListPreviewByGroup.keys.retainAll(chatRowsByGroup.keys)
        val liveGroupIds = rows.mapTo(mutableSetOf()) { it.groupIdHex }
        presentationMembersByGroup = presentationMembersByGroup.filterKeys { it in liveGroupIds }
        selfOnlyDirectGraceRetryGroups.retainAll(liveGroupIds)
        memberFetchRetryJobsByGroup.keys
            .filterNot { it in liveGroupIds }
            .toList()
            .forEach(::cancelMemberSnapshotRetry)
        memberFetchRetryBackoffTierByGroup.keys.retainAll(liveGroupIds)
        failedMemberFetches.retainAll(liveGroupIds)
        if (previousKeys != chatRowsByGroup.keys.toSet()) {
            noteMaterializedGroupMembershipChanged()
        }
    }

    private fun snapshotChatRowForRemoval(groupIdHex: String): RemovedChatRowSnapshot? {
        val rowKey = chatRowKey(groupIdHex)
        val optimisticState = optimisticChatListPreviewByGroup[rowKey]
        val row = optimisticState?.baselineRow ?: chatRowsByGroup[rowKey] ?: return null
        return RemovedChatRowSnapshot(
            row = row,
            activitySequence = optimisticState?.baselineActivitySequence ?: activitySequenceByGroup[rowKey] ?: 0uL,
            optimisticState = optimisticState?.snapshot(),
        )
    }

    private fun removeChatRow(groupIdHex: String) {
        val rowKey = chatRowKey(groupIdHex)
        val removedRow = chatRowsByGroup.remove(rowKey)
        if (removedRow != null) {
            activitySequenceByGroup.remove(rowKey)
            optimisticChatListPreviewByGroup.remove(rowKey)
            cancelMemberSnapshotRetry(removedRow.groupIdHex)
            memberFetchRetryBackoffTierByGroup.remove(removedRow.groupIdHex)
            failedMemberFetches.remove(removedRow.groupIdHex)
            selfOnlyDirectGraceRetryGroups.remove(removedRow.groupIdHex)
            presentationMembersByGroup = presentationMembersByGroup - removedRow.groupIdHex
            noteMaterializedGroupMembershipChanged()
            scheduleRecompute()
        }
    }

    private fun restoreRemovedChatRow(snapshot: RemovedChatRowSnapshot) {
        val rowKey = chatRowKey(snapshot.row.groupIdHex)
        if (chatRowsByGroup.containsKey(rowKey)) return
        chatRowsByGroup[rowKey] = snapshot.row
        activitySequenceByGroup[rowKey] = snapshot.activitySequence
        snapshot.optimisticState?.let { state ->
            optimisticChatListPreviewByGroup[rowKey] = state
            materializeOptimisticChatListPreview(rowKey, state)
        }
        noteMaterializedGroupMembershipChanged()
        scheduleRecompute()
    }

    private fun noteMaterializedGroupMembershipChanged() {
        materializedGroupsRevision += 1L
    }

    /**
     * Search message bodies across the given chats' local timelines for
     * [rawQuery] (issue #290). Returns, per matched group, the first
     * (newest-first, the order the FFI search returns) searchable message
     * whose plaintext contains the needle, with a highlighted snippet for
     * the chat row's secondary line and the message id for tap-through
     * scroll-to-message.
     *
     * Local-only by construction: this drives the `timelineMessages` FFI
     * search primitive, which reads the account's local SQLite store (the
     * source of truth) and triggers no relay fetch. Per the AGENTS.md
     * source-of-truth rule we add no Android-side message cache — each call
     * re-queries the engine.
     *
     * Per-chat queries fan out concurrently (bounded by [SEARCH_FANOUT]) off
     * the main thread. The FFI `search` field already narrows the scan in
     * the engine; we additionally gate each returned row through
     * [ChatListMessageSearch.isSearchableBody] so reactions, deletes,
     * edits, stream-start, and kind-1210 system rows can never surface as a
     * body match even if the engine's text index includes them.
     *
     * Returns an empty map for a blank needle or when no account is bound.
     * Cancellation propagates (the caller debounces and cancels superseded
     * queries).
     */
    suspend fun searchMessageBodies(
        chats: List<ChatListItem>,
        rawQuery: String,
    ): Map<String, MessageBodyMatch> {
        val account = accountRef ?: return emptyMap()
        val needle = rawQuery.trim()
        if (needle.isEmpty()) return emptyMap()
        val ciNeedle = needle.lowercase()
        return withContext(Dispatchers.IO) {
            val semaphore = Semaphore(SEARCH_FANOUT)
            coroutineScope {
                val deferred =
                    chats.map { item ->
                        async {
                            semaphore.withPermit {
                                searchOneChat(account, item.group.groupIdHex, needle, ciNeedle)
                            }
                        }
                    }
                deferred.awaitAll().filterNotNull().associateBy { it.groupIdHex }
            }
        }
    }

    private suspend fun searchOneChat(
        account: String,
        groupIdHex: String,
        needle: String,
        ciNeedle: String,
    ): MessageBodyMatch? {
        // The FFI `search` field narrows to rows whose text matches the needle,
        // but it can't filter by kind/deleted — that gating happens client-side
        // via [ChatListMessageSearch.isSearchableBody]. So a single small page
        // is unsafe: if the newest SEARCH_PER_CHAT_LIMIT needle hits are all
        // excluded rows (reactions, deletes, kind:1210 system events, …) but an
        // older kind:1/9/1209 body also matches, a one-shot query would return
        // null and drop the chat. Page backwards through the needle-matching
        // rows until the first eligible body match surfaces or the local
        // timeline is exhausted, capped at SEARCH_MAX_PAGES so a pathological
        // history (thousands of excluded hits) can't pin an IO thread.
        // The engine rejects a beforeMessageId without a matching before, so the
        // cursor is the oldest row's (timelineAt, id) pair — both null on page
        // one. Passing the id alone silently failed every page-two scan.
        var cursorBefore: ULong? = null
        var cursorMessageId: String? = null
        var pagesScanned = 0
        while (pagesScanned < SEARCH_MAX_PAGES) {
            val page =
                runCatching {
                    appState.marmotIo {
                        timelineMessages(
                            account,
                            TimelineMessageQueryFfi(
                                groupIdHex = groupIdHex,
                                search = needle,
                                before = cursorBefore,
                                beforeMessageId = cursorMessageId,
                                after = null,
                                afterMessageId = null,
                                limit = SEARCH_PER_CHAT_LIMIT,
                            ),
                        )
                    }
                }.getOrElse { throwable ->
                    if (throwable is CancellationException) throw throwable
                    // A single chat's search failing (e.g. transient engine
                    // error) must not blank the whole result set — drop just
                    // this chat and let the others surface.
                    chatsDebug(throwable) {
                        "message-body search failed group=${groupIdHex.take(8)}: " +
                            (throwable.message ?: throwable.javaClass.simpleName)
                    }
                    return null
                }
            pagesScanned++
            val match =
                ChatListMessageSearch.firstEligibleBodyMatch(
                    page.messages.map { record ->
                        object : ChatListMessageSearch.SearchableRecord {
                            override val kind = record.kind
                            override val deleted = record.deleted
                            override val plaintext = record.plaintext
                            override val messageIdHex = record.messageIdHex
                            override val timelineAt = record.timelineAt
                        }
                    },
                    ciNeedle,
                )
            if (match != null) {
                val snippet = ChatListMessageSearch.buildSnippet(match.plaintext, needle) ?: return null
                return MessageBodyMatch(
                    groupIdHex = groupIdHex,
                    messageIdHex = match.messageIdHex,
                    snippet = snippet,
                    timelineAt = match.timelineAt,
                )
            }
            // No eligible match in this page. Stop if the engine has no older
            // rows, or if the page was empty (defensive: nothing to page from).
            if (!page.hasMoreBefore || page.messages.isEmpty()) return null
            // Cursor to the oldest row in this page so the next query returns
            // strictly older needle hits. Use the minimum timelineAt (tie-broken
            // by id) rather than assuming a fixed array order.
            val oldest = page.messages.minWith(compareBy({ it.timelineAt }, { it.messageIdHex }))
            cursorBefore = oldest.timelineAt
            cursorMessageId = oldest.messageIdHex
        }
        return null
    }

    /**
     * Flip the archived flag on `groupIdHex` from the chat-list surface
     * (swipe / long-press menu). Mirrors `ConversationController.setArchived`
     * but takes the id by parameter since the caller doesn't have an open
     * conversation. Standard mutation-lock + toast pattern; local group
     * record is updated immediately so the row reflows without waiting on
     * the projection echo.
     *
     * Pass `notify = false` to suppress the built-in success toast. The
     * swipe-to-archive path uses this so it can surface its own
     * actionable "Chat archived — Undo" snackbar instead of the plain
     * confirmation (see #296); the long-press menu keeps `notify = true`.
     * The failure toast always fires regardless of `notify`, since a
     * silent failure would leave the user with no signal.
     */
    suspend fun setArchived(
        groupIdHex: String,
        archived: Boolean,
        notify: Boolean = true,
    ): Boolean = setArchived(listOf(groupIdHex), archived, notify) > 0

    /**
     * Bulk archive/unarchive counterpart. Every eligible row receives its
     * presentation-only intent before the first engine call starts, so the
     * whole selection leaves the current folder in one frame. The authoritative
     * chat/group maps remain untouched until Marmot confirms each mutation.
     */
    @Suppress("ReturnCount") // Guard returns avoid starting or reporting an empty archive batch.
    suspend fun setArchived(
        groupIds: Collection<String>,
        archived: Boolean,
        notify: Boolean = true,
    ): Int {
        val account = accountRef ?: return 0
        val epoch = bindEpoch
        val intents = beginOptimisticArchive(groupIds, archived, epoch)
        if (intents.isEmpty()) return 0

        var succeeded = 0
        try {
            intents.forEach { (groupIdHex, intent) ->
                val applied =
                    runCatchingCancellable {
                        appState.withGroupCommitLock(account, groupIdHex) {
                            val updated = groupArchivedUpdater(account, groupIdHex, archived)
                            if (isActiveBindEpoch(epoch)) {
                                applyLocalGroupUpdate(updated)
                                true
                            } else {
                                false
                            }
                        }
                    }.onFailure { error ->
                        if (isActiveBindEpoch(epoch)) {
                            appState.presentFailure(R.string.toast_couldnt_update_chat, "CHAT_ARCHIVE_UPDATE", error)
                        }
                    }.getOrDefault(false)

                if (applied) succeeded += 1
                finishOptimisticArchive(groupIdHex, intent)
            }
        } finally {
            // Cancellation (for example an account/runtime switch) must not
            // strand presentation overlays for commits that never started.
            intents.forEach { (groupIdHex, intent) -> finishOptimisticArchive(groupIdHex, intent) }
        }
        if (notify && succeeded > 0 && isActiveBindEpoch(epoch)) {
            appState.presentTransient(if (archived) R.string.toast_chat_archived else R.string.toast_chat_restored)
        }
        return succeeded
    }

    /**
     * Begin a single-row presentation-only archive intent on behalf of a
     * conversation surface (top bar or Group Details), so the chat-list row
     * moves in the same frame the surface acknowledges the action. Returns null
     * when the row is absent or already presents [archived]; the caller must
     * finish the returned intent once its engine commit settles either way.
     */
    internal fun beginConversationArchiveIntent(
        groupIdHex: String,
        archived: Boolean,
    ): OptimisticArchiveIntent? = beginOptimisticArchive(listOf(groupIdHex), archived, bindEpoch).singleOrNull()?.second

    /** Retire a conversation-surface archive intent; stale or rebound intents are ignored. */
    internal fun finishConversationArchiveIntent(
        groupIdHex: String,
        intent: OptimisticArchiveIntent,
    ) = finishOptimisticArchive(groupIdHex, intent)

    private fun beginOptimisticArchive(
        groupIds: Collection<String>,
        archived: Boolean,
        epoch: Long,
    ): List<Pair<String, OptimisticArchiveIntent>> {
        val intents =
            groupIds
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinctBy { it.lowercase() }
                .mapNotNull { groupIdHex ->
                    val rowKey = chatRowKey(groupIdHex)
                    val currentRow = chatRowsByGroup[rowKey] ?: return@mapNotNull null
                    val currentArchived = optimisticArchiveByGroup[rowKey]?.archived ?: currentRow.archived
                    if (currentArchived == archived) return@mapNotNull null
                    val intent =
                        OptimisticArchiveIntent(
                            bindEpoch = epoch,
                            archived = archived,
                        )
                    optimisticArchiveByGroup[rowKey] = intent
                    groupIdHex to intent
                }
        if (intents.isNotEmpty()) recompute()
        return intents
    }

    private fun finishOptimisticArchive(
        groupIdHex: String,
        intent: OptimisticArchiveIntent,
    ) {
        val rowKey = chatRowKey(groupIdHex)
        if (isActiveBindEpoch(intent.bindEpoch) && optimisticArchiveByGroup[rowKey] === intent) {
            optimisticArchiveByGroup.remove(rowKey)
            recompute()
        }
    }

    /**
     * Leave `groupIdHex` from the chat-list long-press menu. Mirrors the
     * conversation-screen guard: a sole admin in a multi-member group is
     * blocked (the group would lose its only admin); a sole admin in a
     * single-member group self-demotes before the leave so the engine
     * doesn't refuse the publish. Both paths share `GroupProjector`'s
     * pure predicates so the safety levels stay aligned — see
     * [ConversationController.leaveGroup] for the canonical reference.
     *
     * The chat-list row doesn't carry a member count (`memberCount = 0`
     * in `chatListItemFromProjection`), so this fetches members via the
     * `groupMembers` FFI before evaluating the guard. The fetch is the
     * only added IO vs the conversation path.
     */
    suspend fun leaveGroup(groupIdHex: String): Boolean {
        val account = accountRef ?: return false
        val group = groupRecordsById[groupIdHex] ?: return false
        val activeAccountIdHex = appState.activeAccount?.accountIdHex
        // Tracks whether selfDemoteAdmin succeeded before the leaveGroup
        // attempt. If leaveGroup then fails, we surface a partial-failure
        // toast that names the inconsistency (user is demoted but still
        // in the group) rather than the generic "couldn't leave" copy.
        var demotedBeforeLeave = false
        return runCatchingCancellable {
            val members = appState.marmotIo { groupMembers(account, groupIdHex) }
            val memberCount = GroupProjector.uniqueMemberCount(members)
            // #811: when the live roster is just you there is no one to
            // coordinate an MLS commit with, so a normal leave would fail (and
            // the sole-admin transfer block must not apply either). Dissolve the
            // group with local cleanup instead of an MLS leave/self-demote.
            val soleMember =
                GroupProjector.shouldDissolveAsSoleMember(
                    members,
                    activeAccountIdHex,
                )
            if (!soleMember && !GroupProjector.canLeaveGroup(group, activeAccountIdHex, memberCount)) {
                appState.present(
                    R.string.toast_make_another_admin_before_leaving,
                    R.string.toast_group_needs_admin,
                )
                return@runCatchingCancellable false
            }
            appState.withGroupCommitLock(account, groupIdHex) {
                if (soleMember) {
                    appState.deleteGroupLocalWithClientCleanup(account, groupIdHex)
                } else {
                    if (GroupProjector.requiresSelfDemoteBeforeLeave(group, activeAccountIdHex, memberCount)) {
                        withContext(NonCancellable) {
                            val demoteResult =
                                appState.marmotIo(MarmotTraceSection.SELF_DEMOTE_ADMIN) {
                                    selfDemoteAdminDetailed(account, groupIdHex)
                                }
                            demotedBeforeLeave = true
                            appState.applyLocalGroupUpdate(demoteResult.details.group)
                            appState.marmotIo { leaveGroup(account, groupIdHex) }
                        }
                    } else {
                        appState.marmotIo { leaveGroup(account, groupIdHex) }
                    }
                }
            }
            // Invalidate both snapshot sources that seed the next
            // ConversationController so re-opening the just-left group renders
            // the disabled notice immediately instead of flashing the active
            // composer (issue #545): the shared AppState snapshot (the
            // cachedGroupMemberSnapshot fallback) and this controller's own
            // memberCacheByGroup entry (which builds ChatListItem.memberSnapshot).
            // schedulePendingMemberFetches() skips groups already cached, so a
            // stale positive entry would otherwise survive until the next bind.
            appState.removeActiveAccountFromGroupMemberSnapshot(account, groupIdHex)
            if (activeAccountIdHex != null) {
                memberCacheByGroup =
                    memberCacheByGroup +
                    (groupIdHex to GroupProjector.membersWithoutActiveAccount(members, activeAccountIdHex))
                // Known removal: a self-leave omits self from the roster even
                // when that leaves it empty (sole-member leave). Mark it so the
                // badge stays suppressed instead of reading the empty roster as
                // a fetch failure.
                removedGroupIds = removedGroupIds + groupIdHex
                recompute()
            }
            appState.presentTransient(R.string.toast_left_chat)
            true
        }.onFailure {
            if (demotedBeforeLeave) {
                // User was demoted but we couldn't complete the leave.
                // Tell them so they know to ask another admin to restore
                // their role (or retry); the generic "couldn't leave"
                // toast misses that they're now mid-state.
                appState.presentFailure(R.string.toast_demoted_but_couldnt_leave, "CHAT_LEAVE_AFTER_DEMOTE", it)
            } else {
                appState.presentFailure(R.string.toast_couldnt_leave_chat, "CHAT_LEAVE", it)
            }
        }.getOrDefault(false)
    }

    /**
     * Local-only chat-list wipe: hide the row optimistically, run client cleanup
     * + [deleteGroupLocal], and never touch MLS membership. Used by bulk Delete
     * local (#1169) so still-member groups stay joined.
     */
    suspend fun deleteGroupLocalFromChatList(
        groupIdHex: String,
        notify: Boolean = true,
    ): Boolean {
        val account = accountRef ?: return false
        val removedSnapshot = snapshotChatRowForRemoval(groupIdHex)
        removeChatRow(groupIdHex)
        val wipe = runCatching { appState.deleteGroupLocalWithClientCleanup(account, groupIdHex) }
        wipe.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
            removedSnapshot?.let(::restoreRemovedChatRow)
            appState.presentFailure(R.string.toast_couldnt_delete_chat, "CHAT_LOCAL_DELETE", it)
            return false
        }
        if (notify) {
            appState.presentTransient(R.string.toast_chat_deleted_local)
        }
        return true
    }

    /**
     * When [leaveFirst] (the user is still a member), leave the group first and
     * abort the wipe if the leave fails; a left group wipes directly. The wipe is
     * local-only and never touches MLS state, so the row can reappear from a later
     * message while the user remains a member.
     */
    suspend fun deleteGroupFromChatList(
        groupIdHex: String,
        leaveFirstHint: Boolean,
    ): Boolean {
        val account = accountRef ?: return false
        // Hide the row immediately on confirm so it can't be tapped (reopening the
        // group being deleted) during the 1-2s of leave/wipe work; restore it if
        // the delete fails. See #894.
        val removedSnapshot = snapshotChatRowForRemoval(groupIdHex)
        removeChatRow(groupIdHex)
        // Decide leave-first from the live roster, not the chat-list row's
        // removed heuristic (which can lag a leave done elsewhere) — a genuinely
        // left group then wipes directly instead of trying to re-leave. Fall back
        // to the caller's hint only if the membership read fails.
        val activeIdHex = appState.activeAccount?.accountIdHex
        val liveMembers =
            runCatchingCancellable { appState.marmotIo { groupMembers(account, groupIdHex) } }
                .getOrNull()
        val stillMember =
            liveMembers?.any { GroupProjector.isActiveAccountMember(it, activeIdHex) }
                ?: leaveFirstHint
        // #811: a sole-member group has no one to commit an MLS leave with, so
        // skip the leave entirely and let the deleteGroupLocal wipe below
        // dissolve it. Require the live roster; cached snapshots can be stale.
        val soleMember =
            GroupProjector.shouldDissolveAsSoleMember(
                liveMembers,
                activeIdHex,
            )
        if (stillMember && !soleMember && !leaveGroup(groupIdHex)) {
            removedSnapshot?.let(::restoreRemovedChatRow)
            return false
        }
        val wipe = runCatching { appState.deleteGroupLocalWithClientCleanup(account, groupIdHex) }
        wipe.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
            removedSnapshot?.let(::restoreRemovedChatRow)
            appState.presentFailure(R.string.toast_couldnt_delete_chat, "CHAT_LEAVE_AND_DELETE", it)
            return false
        }
        // Wipe succeeded (or found nothing to remove) — the row was already
        // hidden optimistically on entry, so just confirm.
        appState.presentTransient(R.string.toast_chat_deleted_local)
        return true
    }

    /**
     * The members eligible to receive admin when the active account is the sole
     * admin of [groupIdHex] with others still present, or null for the normal
     * path (not sole admin, roster read failed, or no one to transfer to). Reads
     * the live roster, matching [leaveGroup]'s own membership read (#1131).
     */
    suspend fun soleAdminTransferCandidates(groupIdHex: String): List<AppGroupMemberRecordFfi>? {
        val account = accountRef ?: return null
        val group = groupRecordsById[groupIdHex] ?: return null
        val activeAccountIdHex = appState.activeAccount?.accountIdHex
        val members =
            runCatchingCancellable { appState.marmotIo { groupMembers(account, groupIdHex) } }
                .getOrNull()
                ?: return null
        if (GroupProjector.shouldDissolveAsSoleMember(members, activeAccountIdHex)) return null
        val uniqueMemberCount = GroupProjector.uniqueMemberCount(members)
        if (!GroupProjector.isSoleAdminWithOtherMembers(group, activeAccountIdHex, uniqueMemberCount)) return null
        return members.filter { GroupProjector.canTransferAdminTo(group, it, activeAccountIdHex) }.ifEmpty { null }
    }

    /**
     * Sole-admin escape hatch for chat-list Delete (#1131): grant admin to
     * [newAdmin], self-demote, then leave and wipe locally — one action, under the
     * group commit lock. Self-contained rather than composing [leaveGroup]: that
     * re-reads the stale [groupRecordsById] (applyLocalGroupUpdate refreshes the
     * row projection, not the record map) and would re-block on the pre-transfer
     * admin list. After the transfer the active account is a non-admin member with
     * [newAdmin] in charge, so a plain MLS leave always applies.
     */
    suspend fun transferAdminThenDeleteFromChatList(
        groupIdHex: String,
        newAdmin: AppGroupMemberRecordFfi,
    ): Boolean {
        val account = accountRef ?: return false
        val group = groupRecordsById[groupIdHex] ?: return false
        val activeAccountIdHex = appState.activeAccount?.accountIdHex
        if (!GroupProjector.canTransferAdminTo(group, newAdmin, activeAccountIdHex)) {
            appState.present(R.string.toast_couldnt_update_admin, R.string.toast_cant_transfer_admin, copyable = true)
            return false
        }
        // Hide the row immediately so it can't be reopened mid-operation; restore
        // it if the transfer/leave fails (mirrors deleteGroupFromChatList, #894).
        val removedSnapshot = snapshotChatRowForRemoval(groupIdHex)
        removeChatRow(groupIdHex)
        var grantedBeforeLeave = false
        val left =
            runCatching {
                appState.withGroupCommitLock(account, groupIdHex) {
                    val promote =
                        appState.marmotIo(MarmotTraceSection.PROMOTE_ADMIN) {
                            promoteAdminDetailed(account, groupIdHex, newAdmin.memberIdHex)
                        }
                    grantedBeforeLeave = true
                    appState.applyLocalGroupUpdate(promote.details.group)
                    // Grant has landed on the MLS group; finish demote + leave even
                    // if the scope is cancelled so we never strand two admins or a
                    // half-completed leave.
                    withContext(NonCancellable) {
                        val demote =
                            appState.marmotIo(MarmotTraceSection.SELF_DEMOTE_ADMIN) {
                                selfDemoteAdminDetailed(account, groupIdHex)
                            }
                        appState.applyLocalGroupUpdate(demote.details.group)
                        appState.marmotIo { leaveGroup(account, groupIdHex) }
                    }
                }
                true
            }.getOrElse {
                if (it is CancellationException) throw it
                appState.presentFailure(
                    if (grantedBeforeLeave) R.string.toast_granted_but_couldnt_step_down else R.string.toast_couldnt_update_admin,
                    if (grantedBeforeLeave) "CHAT_DELETE_AFTER_ADMIN_GRANT" else "CHAT_DELETE_ADMIN_TRANSFER",
                    it,
                )
                removedSnapshot?.let(::restoreRemovedChatRow)
                false
            }
        if (!left) return false
        // Left the group; drop local data. Best-effort — the row is already gone
        // and we're out of the group, so a wipe failure only leaves local remnants.
        runCatching { appState.deleteGroupLocalWithClientCleanup(account, groupIdHex) }
            .exceptionOrNull()
            ?.let { if (it is CancellationException) throw it }
        appState.presentTransient(R.string.toast_chat_deleted_local)
        return true
    }

    /**
     * Flip the chat-list row for [groupIdHex] to its left state after a leave
     * initiated from another surface (the conversation Details screen), where
     * the engine pushes no chat-list update for a self-leave so the row would
     * otherwise stay active until the next bind (issue #767).
     *
     * Mirrors the row-state updates the chat-list [leaveGroup] makes itself:
     * fold the group into [removedGroupIds] (the authoritative removal marker
     * [ChatListItem.removedFromGroup] honours regardless of the cached roster)
     * and, when a roster is already cached, drop self from it so the snapshot
     * agrees, then [recompute] so the row re-projects immediately. No-op when
     * no account is bound or the group isn't on this controller's chat list.
     */
    fun markGroupLeft(groupIdHex: String) {
        if (accountRef == null) return
        if (chatRows.none { it.groupIdHex == groupIdHex }) return
        val activeAccountIdHex = appState.activeAccount?.accountIdHex
        memberCacheByGroup[groupIdHex]?.let { cached ->
            if (activeAccountIdHex != null) {
                memberCacheByGroup =
                    memberCacheByGroup +
                    (groupIdHex to GroupProjector.membersWithoutActiveAccount(cached, activeAccountIdHex))
            }
        }
        removedGroupIds = removedGroupIds + groupIdHex
        recompute()
    }

    /**
     * Mark the chat's unread count to zero by advancing the read pointer to
     * its latest projected message. No-op when the chat has no unread or no
     * known last-message id. Called from the long-press "Mark as read"
     * action — the per-conversation scroll-driven path remains the
     * normal mechanism while a chat is open.
     */
    suspend fun markAllRead(item: ChatListItem): Boolean {
        val account = accountRef ?: return false
        val lastId =
            item.projection
                ?.lastMessage
                ?.messageIdHex
                ?.takeIf { it.isNotBlank() } ?: return false
        return runCatchingCancellable {
            val row = appState.marmotIo { markTimelineMessageRead(account, item.group.groupIdHex, lastId) }
            row?.let(::applyChatListRow)
            appState.dismissConversationNotifications(account, item.group.groupIdHex)
            true
        }.onFailure {
            // Quiet for the user (marking read is an idempotent
            // affordance and surfacing a toast on every flake would be
            // noisy) — but still log the failure so the trace surfaces
            // in `adb logcat` when someone reports "mark read does
            // nothing". `take(8)` on the group id keeps the privacy
            // posture: no full ids in logs.
            if (BuildConfig.DEBUG) Log.w("DMChatsController", "markAllRead failed", it)
        }.getOrDefault(false)
    }

    /** Flag the chat unread until it is next read; the engine owns the clear. */
    suspend fun markUnread(item: ChatListItem): Boolean {
        // A left/removed/leave-pending chat offers no unread affordances.
        val account =
            accountRef?.takeUnless { item.removedFromGroup(appState.activeAccount?.accountIdHex) } ?: return false
        return runCatchingCancellable {
            val row = appState.marmotIo { setChatManuallyUnread(account, item.group.groupIdHex, true) }
            row?.let(::applyChatListRow)
            true
        }.onFailure {
            // Same quiet posture as markAllRead: log-only, no toast.
            if (BuildConfig.DEBUG) Log.w("DMChatsController", "markUnread failed", it)
        }.getOrDefault(false)
    }

    /** Pin or unpin one chat; newly pinned chats enter at the top of the pinned block. */
    suspend fun setPinned(
        item: ChatListItem,
        pinned: Boolean,
    ): Boolean {
        val account = accountRef ?: return false
        return runCatchingCancellable {
            val state = appState.marmotIo { setChatPinned(account, item.group.groupIdHex, pinned) }
            applyPinState(state)
            true
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w("DMChatsController", "setPinned failed", it)
        }.getOrDefault(false)
    }

    /** Atomically replace the pinned block's manual order; ids must cover the pinned set exactly. */
    suspend fun setPinnedOrder(orderedGroupIds: List<String>): Boolean {
        val account = accountRef ?: return false
        return runCatchingCancellable {
            val state = appState.marmotIo { setPinnedChatOrder(account, orderedGroupIds) }
            applyPinState(state)
            true
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w("DMChatsController", "setPinnedOrder failed", it)
        }.getOrDefault(false)
    }

    /**
     * Fold the engine's normalized pin order into the held rows so the list
     * reorders immediately; the PIN_ORDER_CHANGED trigger (or a snapshot)
     * re-delivers the same state authoritatively moments later.
     */
    private fun applyPinState(state: ChatPinStateFfi) {
        val positionByGroup =
            state.orderedGroupIds
                .withIndex()
                .associate { (index, id) -> id.lowercase() to index.toUInt() }
        var changed = false
        chatRowsByGroup.keys.toList().forEach { key ->
            val position = positionByGroup[key]
            val optimisticState = optimisticChatListPreviewByGroup[key]
            val row = optimisticState?.baselineRow ?: chatRowsByGroup[key] ?: return@forEach
            val updated =
                if (position != null) {
                    row.copy(pinned = true, pinnedPosition = position)
                } else if (row.pinned) {
                    row.copy(pinned = false, pinnedPosition = null)
                } else {
                    row
                }
            if (updated == row) return@forEach
            changed = true
            if (optimisticState == null) {
                chatRowsByGroup[key] = updated
            } else {
                optimisticState.baselineRow = updated
                materializeOptimisticChatListPreview(key, optimisticState)
            }
        }
        if (changed) scheduleRecompute()
    }

    private fun requestGroupProfiles(group: AppGroupRecordFfi) {
        appState.requestProfiles(
            listOfNotNull(group.welcomerAccountIdHex) + group.admins,
        )
    }

    private fun requestChatRowProfiles(row: ChatListRowFfi) {
        row.lastMessage?.sender?.let(appState::requestProfile)
    }

    private fun preWarmNotificationAvatars(item: ChatListItem) {
        val conversationAvatar =
            ProfileSanitizer.protocolImageUrl(item.group.avatarUrl)
                ?: ProfileSanitizer.protocolImageUrl(item.projection?.avatarUrl)
        AvatarImageLoader.preWarm(conversationAvatar)
        GroupProjector
            .avatarAccount(item.group, item.presentationOtherMemberAccount, item.presentationMemberCount)
            ?.let(appState::preWarmProfileAvatar)
        item.latest?.sender?.let(appState::preWarmProfileAvatar)
    }

    @Suppress("ReturnCount") // Mirrors [GroupAvatar] URL-over-encrypted precedence with early exits.
    private fun firstFrameAvatarSeed(item: ChatListItem): ChatListAvatarSeed? {
        val legacyUrl = ProfileSanitizer.protocolImageUrl(item.group.avatarUrl)
        if (legacyUrl != null) {
            return AvatarImageLoader.peek(legacyUrl)?.let { image ->
                ChatListAvatarSeed(ChatListAvatarSource.LEGACY_URL, legacyUrl, image)
            }
        }

        val encryptedCacheKey = encryptedGroupAvatarCacheKey(accountRef, item.group)
        if (encryptedCacheKey != null) {
            GroupAvatarImageLoader.peek(encryptedCacheKey)?.let { image ->
                return ChatListAvatarSeed(ChatListAvatarSource.ENCRYPTED_GROUP, encryptedCacheKey, image)
            }
        }

        val fallbackUrl =
            GroupProjector
                .avatarAccount(item.group, item.presentationOtherMemberAccount, item.presentationMemberCount)
                ?.let { appState.avatarUrl(it) }
        return fallbackUrl?.let { url ->
            AvatarImageLoader.peek(url)?.let { image ->
                ChatListAvatarSeed(ChatListAvatarSource.FALLBACK_URL, url, image)
            }
        }
    }

    /**
     * Called by the shell when a conversation is foregrounded (`false`) or the
     * chat list is back on screen (`true`). The subscription stays alive either
     * way — returning shows the current list with no reload — only the
     * recompute is paused while hidden and flushed once on return. See #6.
     */
    fun setChatListVisible(visible: Boolean) {
        if (isCleared) return
        if (visible == chatListVisible) return
        chatListVisible = visible
        // Flush any folded backing updates synchronously on return so the shell's
        // one-shot head snapshot compares against the current projection, not a
        // stale list left over while scheduleRecompute's debounce was pending (#1313).
        if (visible && (pendingRecompute || recomputeScheduled)) {
            recompute()
        }
    }

    /**
     * A draft started or cleared, so the chat's effective sort time changed.
     * Re-sort — deferred while the list is hidden (you are in the conversation
     * drafting), and flushed when the list returns, like every other recompute.
     */
    fun onDraftSortOrderChanged() {
        if (isCleared) return
        recompute()
    }

    private var recomputeScheduled = false
    private val recomputeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Coalesce the per-fold recompute. The chat-list stream emits a flurry of Row
    // updates during a sync burst (open / reconnect catch-up); running the full
    // O(n log n) sort + member/preview/invite fan-out on each one janks the main
    // thread. Fold updates land in the backing maps synchronously; this defers the
    // projection rebuild one frame so a burst collapses into a single recompute,
    // mirroring the conversation timeline's coalescing.
    private fun scheduleRecompute() {
        if (isCleared || recomputeScheduled) return
        recomputeScheduled = true
        recomputeScope.launch {
            try {
                delay(CHAT_LIST_RECOMPUTE_DEBOUNCE_MS)
            } finally {
                recomputeScheduled = false
            }
            recompute()
        }
    }

    /**
     * Dispose every reference this controller owns. The chat-list screen calls
     * this once when it disposes the controller, which happens on every account
     * switch and [WhiteNoiseAppState.runtimeGeneration] bump (sign-out,
     * destructive wipe). Without it the controller-owned jobs — and the
     * [ChatsController] state they keep alive, whose projection holds decrypted
     * message previews — would leak for the process lifetime. Mirrors
     * [ConversationController.onCleared].
     */
    fun onCleared() {
        if (isCleared) return
        isCleared = true
        bindLifetime.advance()
        val jobToCancel =
            synchronized(liveSubscriptionLock) {
                accountRef = null
                boundAccountRef = null
                bindJob.also { bindJob = null }
            }
        jobToCancel?.cancel()
        connectionOwner.clear()
        pendingInitialLocalSnapshot = null
        resetBackingState()
        items = emptyList()
        archivedItems = emptyList()
        hasLoadedLocalSnapshot = false
        isLoading = false
        error = null
        pendingRecompute = false
        recomputeScheduled = false
        pendingRecoveryProjectionGeneration.clear()
        recoveryProjectionGeneration = 0L
        recomputeScope.cancel()
    }

    private fun resetBackingState() {
        replaceChatRows(emptyList())
        groupRecordsById = emptyMap()
        activitySequenceByGroup.clear()
        nextActivitySequence = 0uL
        optimisticChatListPreviewByGroup.clear()
        optimisticArchiveByGroup.clear()
        memberCacheByGroup = emptyMap()
        presentationMembersByGroup = emptyMap()
        memberSnapshotsRevision += 1L
        removedGroupIds = emptySet()
        inFlightMemberFetches.clear()
        memberFetchRetryJobsByGroup.values.forEach(Job::cancel)
        memberFetchRetryJobsByGroup.clear()
        memberFetchRetryBackoffTierByGroup.clear()
        failedMemberFetches.clear()
        selfOnlyDirectGraceRetryGroups.clear()
        previewTokensByText = emptyMap()
        inFlightPreviewParses.clear()
        mediaPreviewFallbackByMessageId = emptyMap()
        inFlightMediaKindResolves.clear()
    }

    /** Checks that suspended work still belongs to the live account binding. */
    private fun isActiveBindEpoch(epoch: Long): Boolean = !isCleared && bindLifetime.isCurrent(epoch) && accountRef != null

    private fun recompute(scheduleBackgroundEnrichment: Boolean = true) {
        if (isCleared) return
        // currentProjectedItems() reads backing maps that remain warm while the
        // visible chat-list projection is intentionally frozen. On-demand UI
        // consumers key on this revision so those hidden updates stay live.
        forwardTargetsRevision += 1L
        val unreadAccountRef = accountRef
        // Project once and reuse for both the per-account aggregate and the
        // visible list, so the aggregate sees the same removed-group
        // suppression the row badge does (#625). The projection is cheap
        // relative to the FFI fan-out it gates, and is needed even while hidden
        // so a removed group's frozen unread can't keep lighting the
        // cross-account dot behind an open conversation.
        val unreadAccountIdHex = boundAccountIdHex()
        val projected = currentProjectedItems(unreadAccountIdHex)
        if (unreadAccountRef != null) {
            appState.updateAccountUnreadProjection(
                accountRef = unreadAccountRef,
                unreadCount = accountUnreadCount(projected, unreadAccountIdHex),
                hasManualUnread = accountHasManualUnread(projected, unreadAccountIdHex),
            )
        }
        // Hidden behind an open conversation: keep folding updates into the
        // backing maps (done by the caller) but defer the projection rebuild +
        // member/preview fan-out until the list returns, then run once. See #6.
        if (!chatListVisible) {
            pendingRecompute = true
            return
        }
        pendingRecompute = false
        val all =
            sortChatListItems(projected) { item ->
                accountRef?.let { appState.draftStore.draftedAtSecondsFor(it, item.group.groupIdHex) }
            }
        val visible =
            all.filter { !it.group.archived }.mapIndexed { index, item ->
                item.copy(
                    firstFrameAvatar =
                        if (index < CHAT_LIST_AVATAR_WARM_ROWS) {
                            firstFrameAvatarSeed(item)
                        } else {
                            null
                        },
                )
            }
        val archived = all.filter { it.group.archived }.map { it.copy(firstFrameAvatar = null) }
        val avatarWarmTargets = visible.take(CHAT_LIST_AVATAR_WARM_ROWS)
        items = visible
        archivedItems = archived
        val recoveryGeneration = pendingRecoveryProjectionGeneration.consume()
        if (recoveryGeneration > 0L) {
            if (
                appState.recoveryDiagnostics.recordChatListProjectionPublished(
                    generation = recoveryGeneration,
                    count = visible.size + archived.size,
                )
            ) {
                recoveryProjectionGeneration = recoveryGeneration
            }
        }
        // Limit speculative network work to the recent visible conversations the
        // user is likely to receive from next. The app-state notification stream
        // independently warms each ingested sender/conversation on cold UI-less
        // process starts.
        if (scheduleBackgroundEnrichment) {
            avatarWarmTargets.forEach(::preWarmNotificationAvatars)
        }
        chatsDebug { "recompute visible=${items.size} archived=${archivedItems.size} total=${all.size}" }
        // For any group we don't yet have members cached for, fan out a
        // one-shot members fetch so unnamed titles and the profile sheet's
        // shared-groups list can resolve from local snapshots.
        if (scheduleBackgroundEnrichment) schedulePendingMemberFetches()
        // Likewise, fan out off-main markdown parses for any preview text we
        // haven't tokenized yet; each completion folds back via
        // scheduleRecompute() so a burst coalesces into one rebuild.
        if (scheduleBackgroundEnrichment) {
            schedulePendingPreviewParses()
            schedulePendingMediaKindResolves()
        }
    }

    /**
     * Walk the current chat rows and, for any group without cached members or
     * an in-flight fetch, kick off a `groupMembers` FFI call. On success the
     * cache updates and `scheduleRecompute()` runs so row titles and
     * profile-sheet shared-group intersections see the local member snapshot,
     * with a burst of completions coalesced into one rebuild.
     */
    internal fun requestMemberSnapshots(groupIds: Iterable<String>) {
        schedulePendingMemberFetches(groupIds)
    }

    internal fun retryMemberSnapshots(groupIds: Iterable<String>) {
        val targets = groupIds.distinct().toList()
        targets.forEach(::cancelMemberSnapshotRetry)
        if (failedMemberFetches.removeAll(targets.toSet())) memberSnapshotsRevision += 1L
        schedulePendingMemberFetches(targets)
    }

    /** Starts bounded roster reads stamped with both binding and cache lifetimes. */
    private fun schedulePendingMemberFetches(groupIds: Iterable<String> = chatRows.map { it.groupIdHex }) {
        val account = accountRef ?: return
        val epoch = bindEpoch
        val cacheEpoch = memberCacheEpoch
        val liveGroupIds = chatRows.mapTo(mutableSetOf()) { it.groupIdHex }
        val pending =
            groupIds
                .asSequence()
                .distinct()
                .filter { it in liveGroupIds }
                .filter { memberSnapshotNeedsFetch(it) }
                .filterNot { it in inFlightMemberFetches }
                .filterNot { memberFetchRetryJobsByGroup[it]?.isActive == true }
                .toList()
        if (pending.isEmpty()) return
        if (failedMemberFetches.removeAll(pending.toSet())) memberSnapshotsRevision += 1L
        inFlightMemberFetches.addAll(pending)
        pending.forEach { groupIdHex ->
            recomputeScope.launch {
                try {
                    memberFetchGate.withPermit {
                        if (!isActiveBindEpoch(epoch)) return@withPermit
                        val members = memberSnapshotLoader(account, groupIdHex)
                        applyFetchedMemberSnapshot(
                            groupIdHex = groupIdHex,
                            members = members,
                            epoch = epoch,
                            cacheEpoch = cacheEpoch,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (throwable: Throwable) {
                    chatsDebug(throwable) {
                        "member fetch failed group=${groupIdHex.take(8)}: " +
                            (throwable.message ?: throwable.javaClass.simpleName)
                    }
                    if (isActiveBindEpoch(epoch)) {
                        markMemberSnapshotFetchFailed(groupIdHex)
                        scheduleMemberSnapshotRetry(groupIdHex, epoch)
                    }
                } finally {
                    // Only mutate the in-flight set if this job still
                    // belongs to the current bind. A later bind() has
                    // already cleared the set; removing again would be
                    // a no-op but obscures the invariant.
                    if (isActiveBindEpoch(epoch)) {
                        inFlightMemberFetches.remove(groupIdHex)
                        if (
                            !memberCacheLifetime.isCurrent(cacheEpoch) &&
                            memberSnapshotNeedsFetch(groupIdHex)
                        ) {
                            // The chat list can be hidden behind an open
                            // conversation while the system-share picker is
                            // visible. Retry the invalidated targeted fetch
                            // directly: recompute() deliberately skips member
                            // fan-out while hidden, and the picker's effect key
                            // is unchanged until a roster lands.
                            schedulePendingMemberFetches(listOf(groupIdHex))
                        }
                    }
                }
            }
        }
    }

    /** Publishes a roster only while both its binding and invalidation tokens remain current. */
    private fun applyFetchedMemberSnapshot(
        groupIdHex: String,
        members: List<AppGroupMemberRecordFfi>,
        epoch: Long,
        cacheEpoch: Long,
        scheduleRecomputeAfterPublish: Boolean = true,
    ) {
        if (!isActiveBindEpoch(epoch) || !memberCacheLifetime.isCurrent(cacheEpoch)) return
        val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
        val knownSelfRemoval = knownSelfRemovalFor(groupIdHex)
        val directConversationCandidate = directConversationCandidateFor(groupIdHex, members)
        val selfOnlyDirectRoster =
            isSelfOnlyDirectRoster(
                members = members,
                directConversation = directConversationCandidate,
                activeAccountIdHex = activeAccountIdHex,
            )
        if (
            !memberSnapshotReadyToCache(
                members = members,
                knownSelfRemoval = knownSelfRemoval,
                directConversation = directConversationCandidate,
                activeAccountIdHex = activeAccountIdHex,
                selfOnlyDirectGraceElapsed = groupIdHex in selfOnlyDirectGraceRetryGroups,
            )
        ) {
            if (selfOnlyDirectRoster) selfOnlyDirectGraceRetryGroups.add(groupIdHex)
            markMemberSnapshotFetchFailed(groupIdHex)
            scheduleMemberSnapshotRetry(groupIdHex, epoch)
            return
        }
        members
            .map { it.memberIdHex }
            .filter { it.isNotBlank() }
            .forEach(appState::requestProfile)
        memberCacheByGroup = memberCacheByGroup + (groupIdHex to members)
        failedMemberFetches.remove(groupIdHex)
        presentationMembersByGroup = presentationMembersByGroup - groupIdHex
        cancelMemberSnapshotRetry(groupIdHex)
        memberFetchRetryBackoffTierByGroup.remove(groupIdHex)
        selfOnlyDirectGraceRetryGroups.remove(groupIdHex)
        memberSnapshotsRevision += 1L
        recordMemberDerivedLocalReadyIfComplete()
        // A loaded roster that omits self is known removal evidence (admin
        // eviction / self-leave the engine has already applied). Marking it
        // makes an empty self-only roster suppress the badge too, where the
        // snapshot path alone reads empty as ambiguous.
        if (activeAccountIdHex != null &&
            members.none { GroupProjector.isActiveAccountMember(it, activeAccountIdHex) }
        ) {
            removedGroupIds = removedGroupIds + groupIdHex
        }
        // Coalesce: a burst of member-fetch completions on account open/switch
        // would otherwise drive N un-debounced full recomputes. Defer into one.
        scheduleRecomputeIfRequested(scheduleRecomputeAfterPublish)
    }

    private fun markMemberSnapshotFetchFailed(groupIdHex: String) {
        if (failedMemberFetches.add(groupIdHex)) memberSnapshotsRevision += 1L
    }

    private fun scheduleMemberSnapshotRetry(
        groupIdHex: String,
        epoch: Long,
    ) {
        memberFetchRetryJobsByGroup[groupIdHex]?.let { existing ->
            if (existing.isActive) return
            memberFetchRetryJobsByGroup.remove(groupIdHex)
        }
        val backoffTier = memberFetchRetryBackoffTierByGroup.getOrDefault(groupIdHex, 0)
        val shouldRetry =
            isActiveBindEpoch(epoch) &&
                memberSnapshotNeedsFetch(groupIdHex) &&
                chatRowsByGroup.containsKey(chatRowKey(groupIdHex))
        if (!shouldRetry) return
        memberFetchRetryBackoffTierByGroup[groupIdHex] =
            if (backoffTier >= MEMBER_FETCH_MAX_BACKOFF_TIER) {
                MEMBER_FETCH_MAX_BACKOFF_TIER
            } else {
                backoffTier + 1
            }
        val retryJob =
            recomputeScope.launch(start = CoroutineStart.LAZY) {
                val currentJob = coroutineContext[Job]
                val shouldFetch =
                    try {
                        delay(memberSnapshotRetryDelay(backoffTier))
                        isActiveBindEpoch(epoch) &&
                            memberSnapshotNeedsFetch(groupIdHex) &&
                            chatRowsByGroup.containsKey(chatRowKey(groupIdHex))
                    } finally {
                        if (memberFetchRetryJobsByGroup[groupIdHex] === currentJob) {
                            memberFetchRetryJobsByGroup.remove(groupIdHex)
                        }
                    }
                if (shouldFetch) schedulePendingMemberFetches(listOf(groupIdHex))
            }
        memberFetchRetryJobsByGroup[groupIdHex] = retryJob
        retryJob.start()
    }

    private fun cancelMemberSnapshotRetry(groupIdHex: String) {
        memberFetchRetryJobsByGroup.remove(groupIdHex)?.cancel()
    }

    /**
     * Walk the current chat rows and, for any preview plaintext without
     * cached tokens or an in-flight parse, kick off the `parseMarkdown` FFI
     * call off-main. On completion the cache updates and `scheduleRecompute()`
     * runs so the row re-emits with its styled preview (a burst of completions
     * coalescing into one rebuild). List emission never
     * waits on a parse: rows surface immediately with plaintext and upgrade
     * when the tokens land. Failures cache the empty document (renders as
     * plaintext, no retry storm). The cache is pruned to the texts still on
     * screen so live-update churn can't grow it without bound.
     */
    private fun schedulePendingPreviewParses() {
        if (accountRef == null) return
        val epoch = bindEpoch
        val liveTexts = chatRows.mapNotNullTo(mutableSetOf(), ::chatRowPreviewMarkdownSource)
        if (previewTokensByText.keys.any { it !in liveTexts }) {
            previewTokensByText = previewTokensByText.filterKeys { it in liveTexts }
        }
        val pending =
            liveTexts
                .filterNot { it in previewTokensByText }
                .filterNot { it in inFlightPreviewParses }
        if (pending.isEmpty()) return
        inFlightPreviewParses.addAll(pending)
        pending.forEach { text ->
            recomputeScope.launch {
                try {
                    val tokens =
                        previewParseGate.withPermit {
                            if (!isActiveBindEpoch(epoch)) return@withPermit null
                            appState.parseMarkdownOrEmpty(text)
                        } ?: return@launch
                    if (!isActiveBindEpoch(epoch)) return@launch
                    previewTokensByText = previewTokensByText + (text to tokens)
                    // Coalesce: a burst of preview-parse completions on account
                    // open/switch would otherwise drive N un-debounced full
                    // recomputes. Defer into one.
                    scheduleRecompute()
                } finally {
                    // Same epoch discipline as the member fetches: a later
                    // bind() already cleared the set, so only the owning
                    // epoch may mutate it.
                    if (isActiveBindEpoch(epoch)) inFlightPreviewParses.remove(text)
                }
            }
        }
    }

    /**
     * Walk the current chat rows and, for any blank kind-9 preview without a
     * cached media fallback or an in-flight resolve, read the latest local
     * timeline record off-main so [projectedPreviewText] can preserve media
     * filename and typed/generic label fallbacks. The chat-list projection
     * stores plaintext only, so imeta tags must be read from the message store
     * (source of truth) on demand.
     */
    private fun schedulePendingMediaKindResolves() {
        if (accountRef == null) return
        val epoch = bindEpoch
        val account = accountRef!!
        val liveMessageIds = chatRows.mapNotNull(::chatRowNeedsMediaKindResolve).toSet()
        if (mediaPreviewFallbackByMessageId.keys.any { it !in liveMessageIds }) {
            mediaPreviewFallbackByMessageId = mediaPreviewFallbackByMessageId.filterKeys { it in liveMessageIds }
        }
        val pendingRows =
            chatRows.filter { row ->
                val messageId = chatRowNeedsMediaKindResolve(row) ?: return@filter false
                messageId !in mediaPreviewFallbackByMessageId && messageId !in inFlightMediaKindResolves
            }
        if (pendingRows.isEmpty()) return
        pendingRows.forEach { row ->
            val messageId = chatRowNeedsMediaKindResolve(row) ?: return@forEach
            inFlightMediaKindResolves.add(messageId)
            val groupIdHex = row.groupIdHex
            recomputeScope.launch {
                try {
                    val page =
                        mediaKindResolveGate.withPermit {
                            if (!isActiveBindEpoch(epoch)) return@withPermit null
                            try {
                                appState.marmotIo {
                                    timelineMessages(
                                        account,
                                        TimelineMessageQueryFfi(
                                            groupIdHex = groupIdHex,
                                            search = null,
                                            before = null,
                                            beforeMessageId = null,
                                            after = null,
                                            afterMessageId = null,
                                            limit = 1u,
                                        ),
                                    )
                                }
                            } catch (throwable: Throwable) {
                                rethrowIfCancellation(throwable)
                                null
                            }
                        }
                    if (!isActiveBindEpoch(epoch)) return@launch
                    val fallback =
                        page
                            ?.messages
                            ?.firstOrNull()
                            ?.takeIf { it.messageIdHex == messageId }
                            ?.let { MessageProjector.mediaPreviewFallback(TimelineProjector.toAppMessageRecord(it)) }
                            ?: return@launch
                    if (!isActiveBindEpoch(epoch)) return@launch
                    mediaPreviewFallbackByMessageId = mediaPreviewFallbackByMessageId + (messageId to fallback)
                    scheduleRecompute()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Best-effort. Leave the fallback absent so the next bind
                    // can retry; the row keeps the generic media preview.
                } finally {
                    if (isActiveBindEpoch(epoch)) inFlightMediaKindResolves.remove(messageId)
                }
            }
        }
    }

    private fun applyAccountSwitchLocalSnapshot(snapshot: AccountSwitchLocalSnapshot) {
        accountRef = snapshot.accountRef
        boundAccountRef = snapshot.accountRef
        replaceChatRows(snapshot.rows)
        groupRecordsById = snapshot.groups.associateBy { it.groupIdHex }
        applyInitialMemberIdProjections(
            projections = snapshot.memberIds,
            activeAccountIdHex = snapshot.activeAccountIdHex,
            requestProfileRefresh = false,
        )
        hasLoadedLocalSnapshot = true
        isLoading = false
        error = null
        recompute(scheduleBackgroundEnrichment = false)
    }

    init {
        pendingInitialLocalSnapshot?.let(::applyAccountSwitchLocalSnapshot)
    }
}

internal fun shouldPreserveChatListProjection(
    hasSeededLocalSnapshot: Boolean,
    preserveLoadedContent: Boolean,
    hasLoadedLocalSnapshot: Boolean,
): Boolean = hasSeededLocalSnapshot || (preserveLoadedContent && hasLoadedLocalSnapshot)

internal fun isEligibleForwardTarget(
    item: ChatListItem,
    activeAccountIdHex: String?,
): Boolean = !item.group.pendingConfirmation && !item.removedFromGroup(activeAccountIdHex)

private fun AppGroupRecordFfi.debugSummary(): String =
    "id=${groupIdHex.take(8)} archived=$archived pending=$pendingConfirmation " +
        "welcomer=${welcomerAccountIdHex?.take(8)} relays=${relays.size} name=${name.ifBlank { "<blank>" }}"

private fun ChatListRowFfi.debugSummary(): String =
    "id=${groupIdHex.take(8)} archived=$archived pending=$pendingConfirmation unread=$unreadCount " +
        "last=${lastMessage?.messageIdHex?.take(8)} title=${title.ifBlank { "<blank>" }}"

private fun logStaleChatListUnreadRejected(
    keptUnread: ULong,
    rejectedUnread: ULong,
) {
    Log.w(
        "DMChats",
        "stale chat-list unread rejected kept=$keptUnread rejected=$rejectedUnread source=client_stale_fold",
    )
}

private fun TimelineUpdateTriggerFfi.recomputesReactions(): Boolean =
    when (this) {
        TimelineUpdateTriggerFfi.REACTION_ADDED,
        TimelineUpdateTriggerFfi.REACTION_REMOVED,
        TimelineUpdateTriggerFfi.MESSAGE_DELETED,
        TimelineUpdateTriggerFfi.MESSAGE_EDITED_OR_REPROJECTED,
        TimelineUpdateTriggerFfi.SNAPSHOT_REFRESH,
        -> true
        TimelineUpdateTriggerFfi.NEW_MESSAGE,
        TimelineUpdateTriggerFfi.REPLY_PREVIEW_CHANGED,
        TimelineUpdateTriggerFfi.AGENT_STREAM_STARTED,
        TimelineUpdateTriggerFfi.AGENT_STREAM_FINISHED,
        TimelineUpdateTriggerFfi.DELIVERY_OR_SEND_STATE_CHANGED,
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

private inline fun chatsDebug(message: () -> String) {
    // Debug-only so operational INFO logs don't ship in release logcat. See #39.
    if (BuildConfig.DEBUG) Log.i("DMChats", message())
}

private inline fun chatsDebug(
    error: Throwable,
    message: () -> String,
) {
    if (BuildConfig.DEBUG) {
        Log.e("DMChats", message(), error)
    } else {
        Log.e("DMChats", "operation_failed")
    }
}

private val ConversationTimelinePageLimit = 50u

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

// One frame: long enough to collapse a chat-list sync burst into a single
// recompute, short enough to stay imperceptible.
private const val CHAT_LIST_RECOMPUTE_DEBOUNCE_MS = 16L
private const val GROUP_HYDRATION_RETRY_DELAY_MS = 750L
private const val MAX_CHAT_LIST_ACTIVITY_SEQUENCE_HISTORY = 64
private const val CHAT_LIST_AVATAR_WARM_ROWS = 24

// Chat-list message-body search (issue #290). [SEARCH_FANOUT] caps the number
// of per-chat `timelineMessages` FFI queries running at once so a large chat
// list doesn't flood the IO dispatcher; [SEARCH_PER_CHAT_LIMIT] is the page
// size for each backward-paging query. The engine's `search` field narrows to
// needle-matching rows but can't filter by kind/deleted, so a single page is
// unsafe (newer excluded hits could hide an older eligible body); searchOneChat
// pages backwards up to [SEARCH_MAX_PAGES] pages until the first eligible body
// match surfaces or the local timeline is exhausted, bounding worst-case work.
private const val SEARCH_FANOUT = 6
private val SEARCH_PER_CHAT_LIMIT = 5u
private const val SEARCH_MAX_PAGES = 20

// Maximum number of `groupMembers` FFI roster reads running at once from the
// chat-list projection. Keeps large accounts from flooding IO at startup while
// still letting shared-group snapshots materialize in the background.
private const val MEMBER_FETCH_FANOUT = 4
private const val INITIAL_MEMBER_FALLBACK_FANOUT = 8
private const val INITIAL_MEMBER_FALLBACK_CUTOFF_MS = 500L
private const val FIRST_FRAME_FALLBACK_POLL_MILLIS = 5L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val GROUP_MEMBER_IDS_PAGE_SIZE = 100
private const val MEMBER_FETCH_INITIAL_RETRY_DELAY_MS = 250L
private const val MEMBER_FETCH_MAX_RETRY_DELAY_MS = 300_000L
private const val MEMBER_FETCH_MAX_BACKOFF_TIER = 11
private const val PREVIEW_PARSE_FANOUT = 4
private const val MEDIA_KIND_RESOLVE_FANOUT = 4

// Cap on how many subscription windows one coalesced batch can absorb. A
// runaway producer shouldn't be able to wedge the UI behind an unbounded
// drain loop; this keeps latency-to-first-paint bounded.
private const val TIMELINE_BATCH_CAP = 32

// Window to wait for additional subscription windows to coalesce into the
// current batch. 6ms is roughly the slack on a 120Hz frame budget (8.33ms)
// minus the apply+publish work, so we soak up updates that arrive within
// one frame without delaying the next paint.
private const val TIMELINE_BATCH_DRAIN_MS = 6L

// DEBUG-only bound on the send-latency trace map (issue #913). Small: at most a
// handful of sends are in flight before their echo reconciles; the cap only
// guards against a burst of never-echoed sends leaking entries.
private const val SEND_TRACE_MAX_TRACKED = 64

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

/**
 * Short-lived presentation intent for a roster mutation that MDK has not
 * reconciled yet. The authoritative [ConversationController.members] and
 * [ConversationController.group] values are never changed by this overlay.
 */
internal sealed interface OptimisticGroupRosterMutation {
    data class Invite(
        val memberRefs: List<String>,
    ) : OptimisticGroupRosterMutation

    data class Remove(
        val memberIdHex: String,
    ) : OptimisticGroupRosterMutation

    data class SetAdmin(
        val memberIdHex: String,
        val admin: Boolean,
    ) : OptimisticGroupRosterMutation
}

private enum class GroupAdministrationCommitOutcome {
    COMMITTED,
    ROSTER_CHANGED,
    KEEP_ONE_ADMIN,
    NO_CHANGE,
}

internal suspend fun canonicalGroupInviteRefs(
    memberRefs: List<String>,
    resolveAccountIdHex: suspend (String) -> String?,
): List<String> {
    val canonicalRefs = mutableListOf<String>()
    val seenInputs = mutableSetOf<String>()
    val seenAccountIds = mutableSetOf<String>()
    memberRefs.forEach { rawRef ->
        val memberRef = rawRef.trim()
        if (memberRef.isEmpty() || !seenInputs.add(memberRef)) return@forEach
        val accountIdHex =
            resolveAccountIdHex(memberRef)
                ?: throw IllegalArgumentException("Invalid member reference")
        if (seenAccountIds.add(accountIdHex.lowercase())) canonicalRefs += accountIdHex
    }
    return canonicalRefs
}

internal fun projectedGroupMembers(
    authoritativeMembers: List<AppGroupMemberRecordFfi>,
    mutation: OptimisticGroupRosterMutation?,
): List<AppGroupMemberRecordFfi> =
    if (mutation is OptimisticGroupRosterMutation.Remove) {
        authoritativeMembers.filterNot {
            it.memberIdHex.equals(mutation.memberIdHex, ignoreCase = true)
        }
    } else {
        authoritativeMembers
    }

internal fun pendingGroupInviteRefs(
    authoritativeMembers: List<AppGroupMemberRecordFfi>,
    mutation: OptimisticGroupRosterMutation?,
): List<String> {
    val refs = (mutation as? OptimisticGroupRosterMutation.Invite)?.memberRefs.orEmpty()
    if (refs.isEmpty()) return emptyList()
    val memberIds = authoritativeMembers.map { it.memberIdHex.lowercase() }.toSet()
    return refs.filterNot { it.lowercase() in memberIds }
}

internal fun projectedGroupAdmin(
    authoritativeAdmin: Boolean,
    memberIdHex: String,
    mutation: OptimisticGroupRosterMutation?,
): Boolean =
    (mutation as? OptimisticGroupRosterMutation.SetAdmin)
        ?.takeIf { it.memberIdHex.equals(memberIdHex, ignoreCase = true) }
        ?.admin
        ?: authoritativeAdmin

/**
 * Owns one lifecycle-bound optimistic roster mutation. Projection always uses
 * the caller's latest authoritative values, so rollback cannot restore a stale
 * roster if an MDK subscription update arrives while the commit is pending.
 */
internal class OptimisticGroupRosterMutationTracker {
    var current by mutableStateOf<OptimisticGroupRosterMutation?>(null)
        private set

    private val mutations = StalenessGuard()

    /** Projects [mutation] until its own completion, without clearing a newer mutation. */
    suspend fun <T> track(
        mutation: OptimisticGroupRosterMutation,
        block: suspend () -> T,
    ): T {
        val token = mutations.advance { current = mutation }
        return try {
            block()
        } finally {
            mutations.runIfCurrent(token) { current = null }
        }
    }
}

internal fun conversationStartsLoading(
    startOnConstruction: Boolean,
    accountRefOverride: String?,
    activeAccountRef: String?,
): Boolean = startOnConstruction && (accountRefOverride ?: activeAccountRef) != null

internal fun isTerminalOpenFailure(throwable: Throwable): Boolean = throwable is ConversationInitialLoadException

internal fun shouldOfferConversationLoadRetry(throwable: Throwable): Boolean = !isTerminalOpenFailure(throwable)

internal typealias MediaUploader =
    suspend (String, String, MediaUploadRequestFfi) -> MediaUploadResultFfi

internal typealias MediaImetaTagsBuilder =
    suspend (String, String, List<MediaAttachmentReferenceFfi>) -> List<MessageTagFfi>

internal typealias MediaPublisher =
    suspend (String, String, List<MediaAttachmentReferenceFfi>, String?) -> SendSummaryFfi

internal typealias InviteAcceptor = suspend (String, String) -> AppGroupRecordFfi

private data class RecoveryStampedTimelineWindow(
    val page: TimelinePageFfi,
    val recoveryGeneration: Long?,
)

class ConversationController(
    internal val appState: WhiteNoiseAppState,
    initialGroup: AppGroupRecordFfi,
    internal val initialMemberSnapshot: GroupMemberSnapshot? = null,
    initialChatListRow: ChatListRowFfi? = null,
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
    private val textPublisher: suspend (String?, String, String, String) -> SendSummaryFfi =
        { replyTarget, account, groupIdHex, text ->
            if (replyTarget != null) {
                appState.marmotIo { replyToMessage(account, groupIdHex, replyTarget, text) }
            } else {
                appState.marmotIo { sendText(account, groupIdHex, text) }
            }
        },
    private val mediaUploader: MediaUploader = { account, groupIdHex, request ->
        appState.marmotIo { uploadMedia(account, groupIdHex, request) }
    },
    private val mediaImetaTagsBuilder: MediaImetaTagsBuilder = { account, groupIdHex, references ->
        appState.marmotIo {
            references.map { reference -> buildMediaImetaTag(account, groupIdHex, reference) }
        }
    },
    private val mediaPublisher: MediaPublisher = { account, groupIdHex, references, caption ->
        appState.marmotIo { sendMediaAttachments(account, groupIdHex, references, caption) }
    },
    private val markdownParser: suspend (String) -> MarkdownDocumentFfi = { appState.parseMarkdownOrEmpty(it) },
    private val groupArchivedUpdater: suspend (String, String, Boolean) -> AppGroupRecordFfi =
        { account, groupIdHex, archived ->
            appState.marmotIo { setGroupArchived(account, groupIdHex, archived) }
        },
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val inviteAcceptor: InviteAcceptor = { account, groupIdHex ->
        appState.marmotIo(MarmotTraceSection.ACCEPT_GROUP_INVITE) {
            acceptGroupInvite(account, groupIdHex)
        }
    },
) {
    private val liveSubscriptions = appState.conversationLiveSubscriptions()

    var group by mutableStateOf(reconcileTerminalSelfMembership(initialGroup, initialGroup))
        private set

    /** The accepted in-flight archive/restore, presentation-only; identity-compared on settle. */
    private class ConversationArchiveIntent(
        val archived: Boolean,
    )

    private var pendingArchiveIntent by mutableStateOf<ConversationArchiveIntent?>(null)

    // Main-confined count of authoritative group applications (subscription
    // updates and details/rebind round-trips). A mutation captures it before
    // suspending in engine I/O and applies its returned record only while the
    // count is unchanged, so a newer authoritative update always wins over a
    // late completion.
    private var groupAuthorityEpoch = 0L

    // A typed native refusal proves only that this Welcome is no longer
    // pending. Keep its action retired until an authoritative roster resolves
    // whether the account is already a member or has left/been removed.
    private var inviteAcceptanceAwaitingAuthority by mutableStateOf<InviteAcceptanceGeneration?>(null)

    internal val inviteAcceptanceResolutionPending: Boolean
        get() = inviteAcceptanceAwaitingAuthority != null

    private val ownsInviteAcceptanceResult: Boolean
        get() = !controllerCleared && !isAccountTeardownRequested()

    /** Retains one acceptance attempt only while its owner and optimistic generation are unchanged. */
    private fun ownsInviteAttempt(
        authorityEpoch: Long,
        optimisticGroup: AppGroupRecordFfi,
    ): Boolean = ownsInviteAcceptanceResult && groupAuthorityEpoch == authorityEpoch && group == optimisticGroup

    /**
     * Archived state the conversation surfaces should present: the accepted
     * in-flight archive/restore intent when one exists, else the authoritative
     * group. Failure or cancellation only clears the intent, so the newest
     * authoritative state shows through instead of a captured snapshot.
     */
    val presentedArchived: Boolean
        get() = pendingArchiveIntent?.archived ?: group.archived

    private var acceptedInvitePeerAccount by mutableStateOf<String?>(null)

    /**
     * Latest chat-list projection for this conversation, kept live even while
     * the chat list itself is hidden (its controller freezes item recomputes
     * off-screen). Read for row-scoped state a conversation surface needs
     * fresh, like the engine's durable mute.
     */
    var latestChatListRow by mutableStateOf(initialChatListRow)
        private set

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

    private val optimisticGroupRosterMutation = OptimisticGroupRosterMutationTracker()

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

    private val memberRosterRefreshGeneration = StalenessGuard()

    // Invalidated when a timeline page or live subscription batch lands so an
    // in-flight full-page refresh cannot clobber newer state (#1849).
    private val timelineWindowGeneration = StalenessGuard()

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
    private val selfMembership = ConversationSelfLeftState(seededMembershipKnown, seededSelfMember)

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
    var replyingTo by mutableStateOf<AppMessageRecordFfi?>(null)

    /** Per-target edit history for kind-1009 events, recomputed on every
     * timeline publish. The bubble reads `.latestText` and the "(edited · N)"
     * affordance reads `.count`. Null entry == message never edited. */
    var editsByTarget by mutableStateOf<Map<String, EditState>>(emptyMap())
        internal set

    /**
     * Local optimistic edits keyed by target message id, applied immediately on
     * confirm so the bubble flips to the edited text without waiting for the
     * kind-1009 to round-trip through the engine (the echo can lag ~1s). Merged
     * over [aggregateEdits]' output on every publish, then dropped once the real
     * edit lands in the timeline. A [MessageStatus.Pending] entry drives a
     * brief sending indicator on the target bubble; [MessageStatus.Failed]
     * reverts the displayed text to the pre-edit body and lights the same
     * retry/discard affordance a failed send shows. Mirrors the
     * [optimisticMessages] map: local-first display, reconciled on engine echo.
     */
    private val optimisticEdits = mutableStateMapOf<String, OptimisticEdit>()

    /** Set when the user has tapped Edit on a kind-9 they sent — the composer
     * banner reflects this and the next [send] routes through [editMessage]
     * instead of producing a new chat. Cleared on submit, cancel, or
     * navigation away. */
    var editingMessageId by mutableStateOf<String?>(null)

    // Production controllers start their local subscription during
    // construction. Reflect that synchronously so the first composition cannot
    // mistake the not-yet-started coroutine for an authoritative empty chat.
    var isLoading by
        mutableStateOf(
            conversationStartsLoading(startOnConstruction, accountRefOverride, appState.activeAccountRef),
        )
        private set
    var isLoadingOlder by mutableStateOf(false)
        private set
    var hasMoreBefore by mutableStateOf(false)
        private set
    private var hasMoreAfter by mutableStateOf(false)

    // Single guard for archive/leave/member-management mutations so the UI can
    // disable buttons while one is in flight and prevent double-submits.
    var mutationInFlight by mutableStateOf(false)
        private set
    var lastMutationError by mutableStateOf<ErrorPresentation?>(null)
        private set
    private var subscriptionError by mutableStateOf<ErrorPresentation?>(null)
    private var pageError by mutableStateOf<ErrorPresentation?>(null)
    val error: ErrorPresentation?
        get() = pageError ?: subscriptionError
    internal val errorEdge: ConversationLoadFailureEdge
        get() = conversationLoadFailureEdge(pageError != null, failedPageDirection)
    private val retryLoadSignal = Channel<Unit>(Channel.CONFLATED)

    // staleness-exempt: observable retry trigger; nullable snapshots below retain its value.
    var retryGeneration by mutableLongStateOf(0L)
        private set
    private var terminalLoadFailure = false
    private var failedPageDirection: ConversationSearchPageDirection? = null

    // Drops re-entrant calls so a rapid double-tap can't enqueue duplicate
    // FFI work even before Compose re-evaluates `enabled = !mutationInFlight`.
    // mutationsScope runs on Main.immediate, so the check + set is sequential
    // within a single coroutine and atomic across coroutines on the same
    // dispatcher.
    private suspend inline fun withMutationLock(block: () -> Unit) {
        if (mutationInFlight) return
        mutationInFlight = true
        try {
            block()
        } finally {
            mutationInFlight = false
        }
    }

    fun clearLastMutationError() {
        lastMutationError = null
    }

    private fun recordMutationFailure(
        @StringRes title: Int,
        operationCode: String,
        throwable: Throwable,
        detail: AppText = AppText.Resource(R.string.error_try_again),
    ) {
        lastMutationError = privacySafeErrorPresentation(operationCode, throwable, detail)
        appState.presentFailure(title, operationCode, throwable, detail)
    }

    private fun Throwable.rethrowIfCancellation() {
        if (this is CancellationException) throw this
    }

    private suspend inline fun <T> withMutationLockResult(
        defaultValue: T,
        block: () -> T,
    ): T {
        if (mutationInFlight) return defaultValue
        mutationInFlight = true
        return try {
            block()
        } finally {
            mutationInFlight = false
        }
    }

    private val conversationAccountRef = accountRefOverride ?: appState.activeAccountRef
    internal val boundAccountRef: String?
        get() = conversationAccountRef

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
    private val timelineRecords = linkedMapOf<String, TimelineMessageRecordFfi>()
    private val timelineItemsById = linkedMapOf<String, TimelineMessage>()
    private val timelineOrder = mutableListOf<String>()
    private val authoritativeTimelineOrderByMessageId = linkedMapOf<String, ULong>()
    private val durableStreamDisplayParentByMessageId = mutableMapOf<String, String>()
    private val optimisticMessages = appState.optimisticMessages(conversationAccountRef, initialGroup.groupIdHex)
    private val durableAcceptanceCallbacks =
        appState.durableAcceptanceCallbacks(conversationAccountRef, initialGroup.groupIdHex)
    private val initialTimeline =
        initialConversationTimeline(
            preview = initialTimelinePreview,
            groupIdHex = initialGroup.groupIdHex,
            pendingConfirmation = group.pendingConfirmation,
            optimisticMessages = optimisticMessages.values,
        )

    /** Whether construction had enough complete local UI state to paint a real bubble. */
    val hadInitialTimelineSeed: Boolean = initialTimeline.isNotEmpty()

    /** True only until MDK's first authoritative page replaces the construction seed. */
    var initialTimelineSeedActive by mutableStateOf(hadInitialTimelineSeed)
        private set

    var timeline by mutableStateOf(initialTimeline)
        private set

    /** Recovery generation represented by the latest authoritative timeline. */
    var recoveryProjectionGeneration by mutableLongStateOf(0L)
        private set

    var hasPublishedAuthoritativeTimeline by mutableStateOf(false)
        private set

    // Test-only seam: navigation promotes a chat-list tap only after the
    // authoritative page publishes, and screen-level tests can't run the FFI
    // subscription that flips this in production.
    @VisibleForTesting
    internal fun markAuthoritativeTimelinePublishedForTest() {
        hasPublishedAuthoritativeTimeline = true
    }

    /** Local sender metadata needed for a settled first presentation is ready. */
    var hasPreparedInitialPresentation by mutableStateOf(false)
        private set

    /** The engine proved this conversation is no longer available to the account. */
    var terminalConversationUnavailable by mutableStateOf(false)
        private set
    private val projectedMessageIds = appState.projectedMessageIds(conversationAccountRef, initialGroup.groupIdHex)
    private val localTimelineOrderOverrides = appState.timelineOrderOverrides(conversationAccountRef, initialGroup.groupIdHex)
    private val localTimelineTimestampOverrides =
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
    private val pendingProjectionsAwaitingBridge =
        appState.pendingProjectionsAwaitingBridge(conversationAccountRef, initialGroup.groupIdHex)
    private val optimisticReactionChanges = linkedMapOf<String, OptimisticReactionChange>()

    // DEBUG-only send-latency trace bookkeeping (issue #913): maps a pending
    // optimistic text message's temp id to (traceSequence, monotonicStartMs) so
    // the engine-echo reconcile that flips the bubble pending → sent
    // (upsertProjectedRecord) can log the accepted → echoed-reconcile latency —
    // the "self-echo drives the flip" candidate. Short-lived lifecycle state:
    // entries are added on optimistic send and removed on reconcile; bounded so
    // a burst of never-echoed sends can't grow it. Holds no protocol data (only
    // a local temp id, a one-run sequence string, and a monotonic long), so it
    // is not an Android-owned cache of White Noise data (AGENTS.md).
    private val sendTraceByTempId = linkedMapOf<String, PerformanceTrace>()
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val initialTimelineSubscriptionRead =
        SingleFlightBoundedInitialResourceRead<ConversationTimelineSubscriptionHandle>(
            closeUnclaimed = { lateStream -> runCatching { lateStream.close() } },
        )
    private val initialTimelineSnapshotRead = SingleFlightBoundedInitialSnapshotRead<TimelinePageFfi?>()
    private val initialPresentationWarmCoordinator =
        ConversationInitialPresentationWarmCoordinator(
            scope = controllerScope,
            budgetMillis = INITIAL_PRESENTATION_PROFILE_WARM_BUDGET_MILLIS,
            warm = appState::warmProfilePresentationsBlocking,
            onReady = { hasPreparedInitialPresentation = true },
        )
    private val inviteStreamScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val attachmentTransferScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val attachmentTransfers = AttachmentTransferCoordinator(attachmentTransferScope)

    // Cached at start() so `loadOlderPage` / `loadNewerPage` can drive
    // `paginate_backwards` / `paginate_forwards` on the subscription. Per
    // the PR-#400 contract, the subscription owns the materialized window
    // (ordering, dedup, head-anchoring, cap, has_more_*); clients render
    // the returned page directly instead of merging against a hand-rolled
    // cursor.
    @Volatile
    private var timelineSubscription: ConversationTimelineSubscriptionHandle? = null
    private val liveSubscriptionLock = Any()
    private val timelineSubscriptionActiveCallMutex = Mutex()
    private var groupStateSubscription: ConversationGroupStateSubscriptionHandle? = null
    private var startJob: Job? = null

    // staleness-exempt: captured subscription-start token, not a counter owner.
    private var lastStartedGeneration: Long? = null
    private var conversationScope: CoroutineScope? = null
    private var accountTeardownRequested = false
    private var controllerCleared = false
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
    private var hasLoadedOlderPages = false

    // Snapshot of message ids in the deliberately-loaded history window after the
    // last successful loadOlderPage(). Live Upserts after that are capped separately
    // so indexes and messageById cannot grow without bound (#1163).
    private val protectedTimelineMessageIds = mutableSetOf<String>()

    // Last message id we successfully marked as read on the Rust side.
    // Dedupes scroll-driven [markReadUpTo] calls so settling on the same row
    // doesn't issue redundant FFI hops. Compose-observable so UI (the
    // jump-to-mention chip) can derive unread state off the engine read
    // watermark rather than the scroll position.
    var lastReadMessageId: String? by
        mutableStateOf(initialChatListRow?.lastReadMessageIdHex?.takeIf { it.isNotBlank() })
        private set

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
            conversationKind = latestChatListRow?.conversationKind,
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
     * Avatar URL for the conversation top bar. A group's own avatar wins; a 1:1
     * DM falls back to the peer's profile picture so the top bar matches the
     * chat-list row instead of showing a blank/initials placeholder (#837).
     */
    val avatarUrl: String?
        get() = group.avatarUrl ?: avatarAccount?.let { appState.avatarUrl(it) }

    // Deduped case-insensitively, mirroring the projector's classification —
    // a hex-casing-drifted duplicate must not inflate the visible headcount.
    val memberCount: Int get() = GroupProjector.uniqueMemberCount(members)
    val isDm: Boolean
        get() =
            GroupProjector.isDm(latestChatListRow?.conversationKind, memberCount, group.name) ||
                (
                    initialIsDm &&
                        !membersVerified &&
                        latestChatListRow?.conversationKind != ChatConversationKindFfi.GROUP
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
        get() = GroupProjector.isDm(latestChatListRow?.conversationKind, memberCount, group.name)

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
    ): MessageDeleteCapability =
        messageDeleteCapability(
            isDirectConversation = isDirectConversation,
            mine = isMessageMine(message),
            selfIsAdmin = isSelfAdmin,
            localDeleteSupported = message.messageIdHex.isNotBlank(),
            remoteDeleteSupported = canSendMessages && message.messageIdHex.isNotBlank(),
            alreadyDeleted = alreadyDeleted,
        )

    fun isMessageMine(message: AppMessageRecordFfi): Boolean = MessageProjector.isMine(message, conversationAccountIdHex)

    val isSelfMember: Boolean
        get() =
            !inviteAcceptanceResolutionPending &&
                selfMembership.isSelfMember(members, conversationAccountIdHex)

    val canSendMessages: Boolean
        // The engine gates all ordinary outbound work while a disband
        // converges and forever after it lands; mirror that on the composer.
        get() = membersVerified && isSelfMember && !group.unrecoverable && !group.disbanding && !group.disbanded

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
        val candidate =
            controllerScope.launch(start = CoroutineStart.LAZY) {
                runStart(account)
            }
        val shouldStart =
            synchronized(liveSubscriptionLock) {
                val generation = retryGeneration
                val alreadyStartedForGeneration =
                    startJob?.isActive == true || lastStartedGeneration == generation
                if (accountTeardownRequested || controllerCleared || alreadyStartedForGeneration) {
                    false
                } else {
                    lastStartedGeneration = generation
                    startJob = candidate
                    true
                }
            }
        if (shouldStart) {
            candidate.start()
        } else {
            candidate.cancel()
        }
    }

    private suspend fun runStart(account: String) {
        val currentStartJob = coroutineContext[Job]
        isLoading = true
        terminalConversationUnavailable = false
        subscriptionError = null
        pageError = null
        terminalLoadFailure = false
        try {
            coroutineScope {
                conversationScope = this
                try {
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
                    try {
                        runConversationSubscriptionLoop(account)
                    } finally {
                        // A terminal initial-open failure returns normally from
                        // the subscription loop. Stop its infinite sibling so
                        // runStart can finish and release its lifecycle state.
                        foregroundSweepJob.cancel()
                    }
                } finally {
                    conversationScope = null
                }
            }
        } catch (cancel: CancellationException) {
            // Expected when the conversation screen leaves the composition.
            // Re-throw so cancellation propagates and we don't log it as an
            // error.
            throw cancel
        } catch (throwable: Throwable) {
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
        } finally {
            conversationScope = null
            cleanupConversationSubscriptions()
            synchronized(liveSubscriptionLock) {
                if (startJob === currentStartJob) {
                    startJob = null
                }
            }
        }
    }

    private fun discardInitialTimelineSeedForFailure(preserveOptimisticMessages: Boolean) {
        if (!shouldDiscardInitialTimelineSeedForFailure(hasPublishedAuthoritativeTimeline)) return
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

    private fun publishAuthoritativeEmptyInitialTimeline() {
        hasPublishedAuthoritativeTimeline = true
        hasPreparedInitialPresentation = true
        initialTimelineSeedActive = false
        publishTimelineFromIndexes()
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

    suspend fun closeLiveSubscriptionsForAccountTeardown(accountRef: String) {
        if (!shouldTeardownLiveSubscriptionsForAccount(accountRef, conversationAccountRef, conversationAccountRef)) return
        val teardown =
            synchronized(liveSubscriptionLock) {
                accountTeardownRequested = true
                memberRosterRefreshGeneration.advance()
                val current = Triple(groupStateSubscription, timelineSubscription, startJob)
                groupStateSubscription = null
                startJob = null
                current
            }
        val (groupSubscription, timelineStream, job) = teardown
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { groupSubscription?.close() }
        }
        closeTimelineSubscriptionSafely(timelineStream)
        if (shouldCancelLiveSubscriptionJob(job, coroutineContext[Job])) {
            job?.cancelAndJoin()
        }
    }

    private fun isAccountTeardownRequested(): Boolean = synchronized(liveSubscriptionLock) { accountTeardownRequested }

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
        val snapshot = initialTimelineSnapshotRead.await { timelineStream.snapshot() }
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
            val streamIds =
                applyTimelinePage(
                    snapshot,
                    replaceWindow = true,
                    updatePagination = true,
                )
            initializeReadState(account)
            publishRecoveryTimelineProjection(recoveryGeneration)
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
        var retryDelayMs = LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS
        while (coroutineContext.isActive && !isAccountTeardownRequested()) {
            val (shouldExit, connected) = runConversationSubscriptionIteration(account)
            if (shouldExit) return
            if (connected) retryDelayMs = LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS
            if (!coroutineContext.isActive || isAccountTeardownRequested()) break
            val userRequestedRetry = withTimeoutOrNull(retryDelayMs) { retryLoadSignal.receive() } != null
            retryDelayMs =
                if (userRequestedRetry) {
                    LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS
                } else {
                    nextLiveSubscriptionRetryDelayMillis(retryDelayMs)
                }
        }
    }

    /**
     * One connect/reconnect attempt. Returns whether the caller should exit
     * until explicit retry (account evicted or a native open timed out) and
     * whether the subscriptions connected successfully (so the retry backoff
     * can reset).
     */
    private suspend fun runConversationSubscriptionIteration(account: String): Pair<Boolean, Boolean> {
        var groupSubscription: ConversationGroupStateSubscriptionHandle? = null
        var timelineStream: ConversationTimelineSubscriptionHandle? = null
        try {
            timelineStream =
                initialTimelineSubscriptionRead.await {
                    liveSubscriptions.openTimeline(account, group.groupIdHex, ConversationTimelinePageLimit)
                }
            val stopAfterTimelineOpen =
                synchronized(liveSubscriptionLock) {
                    if (accountTeardownRequested) {
                        true
                    } else {
                        timelineSubscription = timelineStream
                        false
                    }
                }
            if (stopAfterTimelineOpen) return true to false
            val snapshotStreamIds = publishInitialTimelineSnapshot(account, timelineStream)
            // Don't blanket-mark the absolute newest as read here — the UI
            // layer now drives mark-read as the user scrolls so partial-read
            // sessions retain accurate unread counts on the chat list.

            val groupStream = liveSubscriptions.openGroupState(account, group.groupIdHex)
            groupSubscription = groupStream
            val stopAfterGroupOpen =
                synchronized(liveSubscriptionLock) {
                    if (accountTeardownRequested) {
                        true
                    } else {
                        groupStateSubscription = groupStream
                        false
                    }
                }
            if (stopAfterGroupOpen) return true to false
            val groupSnapshot =
                withContext(Dispatchers.IO) {
                    groupStream.snapshot()
                }
            groupSnapshot?.let(::applyGroupState)
            refreshMembers()
            isLoading = false
            subscriptionError = null
            var connected = false

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
                        connected = true
                    },
                    first = {
                        runTimelineSubscriptionPipeline(account, timelineStream)
                    },
                    second = {
                        runGroupStateSubscriptionLoop(groupStream)
                    },
                )
            }
            return false to connected
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
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
            subscriptionError =
                privacySafeErrorPresentation(
                    operationCode = if (timelineRecords.isEmpty()) "CONVERSATION_LOAD" else "CONVERSATION_REFRESH",
                    throwable = throwable,
                    message =
                        if (initialOpenTimedOut) {
                            AppText.Resource(R.string.error_restart_app_before_retry)
                        } else if (timelineRecords.isEmpty()) {
                            AppText.Resource(R.string.error_try_again)
                        } else {
                            AppText.Resource(R.string.error_loaded_content_may_be_out_of_date)
                        },
                    retryable = shouldOfferConversationLoadRetry(throwable),
                )
            if (initialOpenTimedOut) {
                // A timed-out FFI call may still occupy its native blocking
                // worker. Automatic retries would accumulate more stuck calls;
                // stop here. The user must restart rather than launch another
                // native open while the single-flight owner is unresolved.
                initialTimelineSubscriptionRead.cancel()
                terminalLoadFailure = true
                return true to false
            }
        } finally {
            closeConversationSubscriptionHandles(groupSubscription, timelineStream)
        }
        return false to false
    }

    // Apply a fresh group-state snapshot/update, republishing the timeline when
    // the current policy changes so optimistic-send state is immediately
    // reconciled. Each row's pinned deadline remains independent of the current
    // policy, so historical rows are never reinterpreted by this republish.

    /** Test entry to the subscription-update application path. */
    @VisibleForTesting
    internal fun applyGroupStateForTest(update: AppGroupRecordFfi) = applyGroupState(update)

    /** Applies a canonical group update while retaining only a still-unresolved stale action fence. */
    private fun applyGroupState(update: AppGroupRecordFfi) {
        val previousGroup = group
        val previousRetention = group.disappearingMessageSecs
        groupAuthorityEpoch += 1L
        val reconciled =
            reconcileTerminalSelfMembership(
                update = update,
                previous = previousGroup,
            )
        val awaitingAuthority = inviteAcceptanceAwaitingAuthority
        val freshTerminalReinvite = isDistinctWelcomeReinvite(previousGroup, update)
        group =
            if (awaitingAuthority?.matches(reconciled) == true && reconciled.pendingConfirmation) {
                reconciled.copy(pendingConfirmation = false)
            } else {
                inviteAcceptanceAwaitingAuthority = null
                reconciled
            }
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

    private suspend fun runGroupStateSubscriptionLoop(groupStream: ConversationGroupStateSubscriptionHandle) {
        while (coroutineContext.isActive) {
            val update =
                withContext(Dispatchers.IO) {
                    groupStream.next()
                } ?: break
            val previousGroup = group
            applyGroupState(update)
            if (groupStateUpdateRemovesSelf(previousGroup, update)) {
                conversationAccountRef?.let(::markActiveAccountRemovedFromMembers)
            }
            refreshMembers()
        }
    }

    private suspend fun closeConversationSubscriptionHandles(
        groupSubscription: ConversationGroupStateSubscriptionHandle?,
        timelineStream: ConversationTimelineSubscriptionHandle?,
    ) {
        synchronized(liveSubscriptionLock) {
            if (groupStateSubscription === groupSubscription) {
                groupStateSubscription = null
            }
        }
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { groupSubscription?.close() }
        }
        closeTimelineSubscriptionSafely(timelineStream)
    }

    private suspend fun cleanupConversationSubscriptions() {
        // Do NOT cancel inviteStreamScope here: it is not owned by a single
        // start() invocation (see onCleared / #279). start()'s subscription
        // loops can end while the screen is still composed, and acceptInvite()
        // — which launches into inviteStreamScope from an independent mutation
        // scope — may fire afterward.
        val closingSubscription =
            synchronized(liveSubscriptionLock) {
                val current = timelineSubscription
                current
            }
        closeTimelineSubscriptionSafely(closingSubscription)
    }

    private suspend fun closeTimelineSubscriptionSafely(timelineStream: ConversationTimelineSubscriptionHandle?) {
        if (timelineStream == null) return
        withContext(NonCancellable) {
            timelineSubscriptionActiveCallMutex.withLock {
                synchronized(liveSubscriptionLock) {
                    if (timelineSubscription === timelineStream) {
                        timelineSubscription = null
                    }
                }
                withContext(Dispatchers.IO) {
                    runCatching { timelineStream.close() }
                }
            }
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
        synchronized(liveSubscriptionLock) {
            controllerCleared = true
            memberRosterRefreshGeneration.advance()
        }
        initialTimelineSubscriptionRead.cancel()
        initialTimelineSnapshotRead.cancel()
        controllerScope.cancel()
        inviteStreamScope.cancel()
        attachmentTransferScope.cancel()
    }

    internal fun matchesConversation(
        accountRef: String?,
        groupIdHex: String,
    ): Boolean = conversationAccountRef == accountRef && group.groupIdHex == groupIdHex

    /** Applies the chat-list subscription's current row to this mounted conversation. */
    internal fun applyAuthoritativeChatListRow(
        accountRef: String?,
        row: ChatListRowFfi,
    ) {
        if (!matchesConversation(accountRef, row.groupIdHex)) return
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
        val pump =
            async {
                try {
                    while (isActive) {
                        val page =
                            withContext(Dispatchers.IO) {
                                timelineStream.nextWindow()
                            } ?: break
                        timelineWindows.send(
                            RecoveryStampedTimelineWindow(
                                page = page,
                                recoveryGeneration = appState.recoveryDiagnostics.recordTimelineSubscriptionReceived(),
                            ),
                        )
                    }
                } finally {
                    timelineWindows.close()
                }
            }
        try {
            while (isActive) {
                val first =
                    timelineWindows.receiveCatching().getOrNull()
                        ?: break
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
                    val more =
                        timelineWindows.tryReceive().getOrNull()
                            ?: withTimeoutOrNull(TIMELINE_BATCH_DRAIN_MS) {
                                timelineWindows.receiveCatching().getOrNull()
                            } ?: break
                    batch += more
                }
                val newest = batch.last()
                val streamIdsLaunched =
                    applyTimelinePage(
                        newest.page,
                        replaceWindow = true,
                        updatePagination = true,
                    )
                publishRecoveryTimelineProjection(batch.mapNotNull { it.recoveryGeneration }.maxOrNull())
                // Scroll-driven mark-read in the UI layer handles
                // the user-visible read pointer.
                streamIdsLaunched.forEach { streamId ->
                    if (activeStreamIds.add(streamId)) {
                        launch { watchAgentTextStream(account, streamId) }
                    }
                }
            }
        } finally {
            if (pump.isActive) {
                pump.cancel()
            }
        }
        pump.await()
    }

    /**
     * Parse the sent text into the same Markdown AST projected records carry
     * and rebind it onto the already-published optimistic bubble and chat-list
     * preview. Only the bubble's first paint is decoupled from this FFI hop —
     * an accepted Send must paint on the next frame even when the IO lane is
     * congested — while the network publish still runs after the parse, so the
     * send's total latency matches the previous parse-first ordering.
     * A parse failure keeps the plain-text presentation, and
     * a bubble already reconciled or rolled back is left alone. Returns the
     * record now backing the bubble so later reconciliation and failure
     * retention keep the styled document instead of the pre-parse snapshot.
     */
    private suspend fun hydrateOptimisticSendMarkdown(
        optimisticKey: String,
        tempId: String,
        text: String,
    ): AppMessageRecordFfi? {
        val tokens =
            runCatchingCancellable { markdownParser(text) }
                .getOrNull()
                ?.takeIf { it.blocks.isNotEmpty() }
        val pending = optimisticMessages[optimisticKey]
        if (tokens == null || pending == null || pending.record.messageIdHex != tempId) return null
        val hydrated = pending.record.copy(contentTokens = tokens)
        optimisticMessages[optimisticKey] = pending.copy(record = hydrated)
        messageById[tempId] = hydrated
        publishTimelineFromIndexes()
        appState.hydrateOptimisticSentPreviewTokens(conversationAccountRef, group.groupIdHex, tempId, tokens)
        return hydrated
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
        onDurablyAccepted: () -> Unit = {},
    ) {
        val trimmed = text.trim()
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
            editingMessageId = null
            editMessage(editTarget, trimmed)
            return
        }

        val replyTarget = replyingTo?.messageIdHex?.takeIf { it.isNotBlank() }
        // WNPerf assigns an opaque process-local operation id only while the
        // user has explicitly enabled the bounded diagnostic session.
        val trace = PerformanceDiagnostics.begin(PerformanceOperation.TEXT_SEND)
        sendTrace(trace, PerformancePhase.ACCEPTED, elapsedMs = 0L, result = PerformanceResult.PENDING)
        val tempId = UUID.randomUUID().toString()
        rememberSendTrace(tempId, trace)
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
        durableAcceptanceCallbacks[optimisticKey] = onDurablyAccepted
        messageById[tempId] = optimistic
        publishTimelineFromIndexes()
        // Bump the chat-list row's preview in the same synchronous block as the
        // bubble, so a back-navigation to the list paints the new last-message
        // instead of a one-frame flash of the prior one (#900). Reuses the
        // already-parsed markdown from the optimistic record.
        val previewApplied =
            appState.applyOptimisticSentPreview(
                conversationAccountRef,
                group.groupIdHex,
                ChatListMessagePreviewFfi(
                    messageIdHex = tempId,
                    sender = conversationAccountIdHex ?: "",
                    senderDisplayName = null,
                    plaintext = trimmed,
                    contentTokens = optimistic.contentTokens,
                    kind = 9uL,
                    timelineAt = now,
                    deleted = false,
                    attachmentKind = null,
                    attachmentCount = 0u,
                    deliveryState = ChatListMessageDeliveryStateFfi.PENDING,
                ),
            )
        if (!previewApplied) {
            // No bound row to bump (pre-first-frame open, brand-new group,
            // account-pinned window) — the engine echo will still update the
            // list, but keep the drop visible in the send trace.
            sendTrace(trace, PerformancePhase.CHAT_LIST_PREVIEW_DROPPED, result = PerformanceResult.FAILURE)
        }
        replyingTo = null
        // The optimistic bubble is now in the projection and published — the
        // send has visibly started. Only now is it safe to clear the input and
        // draft (issue #264): clearing earlier, synchronously in the UI on the
        // mere act of dispatching this coroutine, lost the text whenever a
        // guard above bailed before this point.
        onAccepted()
        // Optimistic bubble + chat-list preview are now published: the pending
        // clock is on screen. Everything after this is the "clock lingers"
        // window the issue is about.
        sendTrace(trace, PerformancePhase.OPTIMISTIC_SHOWN, result = PerformanceResult.PENDING)
        // Starts as the plain-rendered record so every failure path below has a
        // valid record even if hydration is cut short; the styled rebind lands
        // inside the same try region as the publish it precedes.
        var publishedRecord = optimistic
        try {
            publishedRecord = hydrateOptimisticSendMarkdown(optimisticKey, tempId, trimmed) ?: optimistic
            // Publish with a retry sweep so a *transient* relay-pool gap
            // (socket teardown mid-reconnect, doze wake, network change) doesn't
            // surface as a user-visible "send failed" the instant the pool looks
            // empty (issue #294). A terminal/logic error fails on the first
            // attempt; a sustained proven pre-publish connectivity outage keeps
            // retrying while this send's coroutine is active. The optimistic
            // bubble stays Pending, so the user sees "sending", not "failed".
            //
            // Each FFI attempt owns the conversation commit lock, but retry
            // backoff does not. Other mutations remain usable while offline.
            val summary = publishTextWithRetry(replyTarget, account, trimmed, trace)
            completeDurableAcceptance(optimisticKey)
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
                forgetSendTrace(tempId)
            }
        } catch (throwable: Throwable) {
            throwable.rethrowIfCancellation()
            if (throwable.isUseAfterEviction()) {
                // The engine realized our own eviction while replaying to send:
                // we are no longer a member, so this message can never publish.
                // Drop the optimistic bubble (a retry would only re-fail) and
                // flip to the read-only removed state — the same realization the
                // conversation-open and refresh paths perform — instead of
                // surfacing the raw backend error.
                rollbackOptimisticChatListPreview(tempId)
                optimisticMessages.remove(optimisticKey)
                durableAcceptanceCallbacks.remove(optimisticKey)
                messageById.remove(tempId)
                retentionAtSendByMessageId.remove(tempId)
                forgetSendTrace(tempId)
                publishTimelineFromIndexes()
                markActiveAccountRemovedFromMembers(account)
                onTerminalFailure()
                return
            }
            if (isAmbiguousRelayDeliveryError(throwable)) {
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
                Log.w(
                    "ConversationController",
                    "message delivery uncertain; keeping pending type=${throwable.javaClass.simpleName}",
                )
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
            forgetSendTrace(tempId)
            presentSendFailure(appState, throwable)
            onTerminalFailure()
        }
    }

    /**
     * Publish a text/reply message, keeping it pending and re-sending only when
     * the failure proves the event never reached a relay
     * ([isTransientRelaySendError] — connect-phase failures). Because each
     * attempt re-enters the high-level FFI send and the runtime builds a fresh
     * inner app event per call, retrying any ambiguous post-send failure could
     * duplicate a message; the classifier is narrowed to connect-phase reasons
     * precisely so this re-send is idempotent. Terminal errors and ambiguous
     * post-send failures rethrow immediately on the first attempt. Between
     * attempts it uses capped exponential backoff to give the relay pool time
     * to (re)connect, and logs the relay-health snapshot at the retry decision
     * point — aggregate connection counts only, no relay URLs/account/group/
     * message ids — so the intermittent failure window from #294 is diagnosable
     * from logcat without leaking PII.
     */
    private suspend fun publishTextWithRetry(
        replyTarget: String?,
        account: String,
        trimmed: String,
        trace: PerformanceTrace?,
    ): dev.ipf.marmotkit.SendSummaryFfi =
        appState.withConversationTextSendOrder(account, group.groupIdHex) {
            retryPendingConversationSend(
                connectivityRecoveryGeneration = appState.validatedConnectivityRecoveryGeneration,
                onTransientFailure = { attempt, _ -> logSendRetry(trace, attempt) },
            ) { attempt ->
                // Serialize only this commit-producing FFI attempt. Releasing the
                // commit lock before retry backoff keeps reactions and other
                // mutations usable; the outer text-order lock keeps later text
                // sends behind this one until its outcome is known.
                val lockWaitStartMs = trace?.let { traceNowMs() }
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    val lockHeldAtMs = trace?.let { traceNowMs() }
                    sendTrace(
                        trace,
                        PerformancePhase.COMMIT_LOCK_ACQUIRED,
                        durationMs = lockHeldAtMs?.minus(lockWaitStartMs ?: lockHeldAtMs) ?: 0L,
                        attempt = attempt,
                    )
                    // Time the FFI hop itself (App → engine `send_message`: MLS
                    // commit + encrypt + publish + relay ack round-trip, all
                    // synchronous inside this call). This is the primary "long
                    // pole" candidate the issue asks to measure — how long the
                    // `sendText`/`replyToMessage` call blocks before returning
                    // (issue #913).
                    val ffiStartMs = trace?.let { traceNowMs() }
                    sendTrace(
                        trace,
                        PerformancePhase.FFI_START,
                        result = PerformanceResult.PENDING,
                        layer = PerformanceLayer.FFI,
                        attempt = attempt,
                    )
                    try {
                        val summary = textPublisher(replyTarget, account, group.groupIdHex, trimmed)
                        sendTrace(
                            trace,
                            PerformancePhase.FFI_RETURN,
                            durationMs = ffiStartMs?.let { traceNowMs() - it } ?: 0L,
                            layer = PerformanceLayer.FFI,
                            attempt = attempt,
                            count = summary.messageIds.size,
                        )
                        sendTrace(
                            trace,
                            PerformancePhase.TRANSPORT_COMPLETE,
                            layer = PerformanceLayer.TRANSPORT,
                            count = summary.messageIds.size,
                        )
                        summary
                    } catch (throwable: Throwable) {
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
            queueDepth = health?.let { it.totalRelays.toInt() - it.connected.toInt() },
        )
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
        val seeded = queueAttachments(attachments, caption) ?: return
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
     * matching [uploadQueued] call to drive the FFI work.
     */
    suspend fun queueAttachments(
        attachments: List<PendingAttachment>,
        caption: String?,
    ): QueuedAttachmentSend? {
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
        if (!canSendMessages || attachments.isEmpty()) return null
        if (attachments.any { it.plaintextBytes.isEmpty() }) return null
        if (albumExceedsRetainedCap(attachments)) {
            appState.present(R.string.media_album_too_large)
            return null
        }
        val tempId = UUID.randomUUID().toString()
        val key = "msg:$tempId"
        val now = nowSeconds()
        val retentionAtSendSeconds = rememberRetentionAtSend(tempId, group.disappearingMessageSecs)
        val trimmedCaption = caption?.trim()?.takeIf { it.isNotBlank() }
        val placeholderName =
            if (attachments.size == 1) {
                attachments.first().fileName
            } else {
                "${attachments.size} attachments"
            }
        val body = trimmedCaption ?: "📎 $placeholderName"
        val optimistic =
            pendingAttachmentRecord(
                tempId = tempId,
                body = body,
                attachments = attachments,
                now = now,
            )
        val optimisticOrder = nextOptimisticTimelineOrder()
        retainedMediaUploads.put(key, RetainedMediaUpload(attachments, trimmedCaption))
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
        messageById[tempId] = optimistic
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
        return QueuedAttachmentSend(account, key, tempId, optimisticOrder, optimistic)
    }

    private suspend fun pendingAttachmentRecord(
        tempId: String,
        body: String,
        attachments: List<PendingAttachment>,
        now: ULong,
    ): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = tempId,
            direction = "sent",
            groupIdHex = group.groupIdHex,
            sender = conversationAccountIdHex ?: "",
            plaintext = body,
            contentTokens = appState.parseMarkdownOrEmpty(body),
            kind = 9uL,
            tags =
                attachments.map {
                    MessageTagFfi(listOf("_media_pending", it.fileName, it.mediaType))
                },
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = now,
            receivedAt = now,
        )

    /**
     * Drive the upload + publish for a previously [queueAttachments]-seeded slot.
     * [onDurablyAccepted] survives controller replacement and runs once MDK
     * accepts the logical send, including accepted-pending ownership.
     */
    suspend fun uploadQueued(
        seeded: QueuedAttachmentSend,
        onDurablyAccepted: (() -> Unit)? = null,
    ) {
        // `activeUploadKeys` was added at `queueAttachments` time so that
        // EVERY seeded slot — even the ones still waiting for an earlier
        // upload to finish — survives a dispose-time
        // `clearRetainedUploads`. Removal happens at performMediaUpload's
        // terminal paths.
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
        val retentionAtSendSeconds = optimisticMessages[key]?.retentionAtSendSeconds
        val uploadJob = appState.trackInFlightMediaUpload(conversationAccountRef, group.groupIdHex, key)
        try {
            if (
                !shouldAcceptMediaUploadForAccount(
                    account,
                    mediaUploadSessionEpoch,
                    appState.activeAccountRef,
                    appState.mediaUploadSessionEpoch(),
                )
            ) {
                optimisticMessages.remove(key)
                durableAcceptanceCallbacks.remove(key)
                messageById.remove(tempId)
                retentionAtSendByMessageId.remove(tempId)
                retainedMediaUploads.remove(key)
                activeUploadKeys.remove(key)
                rollbackOptimisticChatListPreview(tempId)
                publishTimelineFromIndexes()
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
                    activeUploadKeys.remove(key)
                    failOptimisticChatListPreview(tempId)
                    publishTimelineFromIndexes()
                    appState.present(R.string.toast_reattach_to_retry_media)
                    return
                }
            discardedDuringRetry.remove(key)
            try {
                // Reuse the references if a prior attempt already uploaded the
                // blobs (publish-only failure) — re-uploading would orphan
                // duplicates on the Blossom server.
                val references =
                    retained.uploadedReferences ?: mediaUploader(
                        account,
                        group.groupIdHex,
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
                        ),
                    ).attachments
                        .map { it.reference }
                        .also { uploaded ->
                            if (uploaded.size != retained.attachments.size) {
                                error(
                                    "media upload returned ${uploaded.size} references " +
                                        "for ${retained.attachments.size} attachments",
                                )
                            }
                        }.also { retained.uploadedReferences = it }
                // Discard window #1: blobs uploaded but not yet published. If the
                // user discarded here, bail BEFORE sendMediaAttachments so we don't
                // publish a kind-9 they cancelled (unlike a published event, an
                // unreferenced Blossom blob is inert).
                if (discardedDuringRetry.remove(key)) {
                    optimisticMessages.remove(key)
                    durableAcceptanceCallbacks.remove(key)
                    messageById.remove(tempId)
                    retentionAtSendByMessageId.remove(tempId)
                    retainedMediaUploads.remove(key)
                    activeUploadKeys.remove(key)
                    rollbackOptimisticChatListPreview(tempId)
                    publishTimelineFromIndexes()
                    return
                }
                // MarmotKit owns the encrypted-media wire format. Build the
                // optimistic bridge tags through the same native API that
                // validates and publishes the projected attachments.
                val imetaTags = mediaImetaTagsBuilder(account, group.groupIdHex, references)
                val summary =
                    appState.withGroupCommitLock(account, group.groupIdHex) {
                        mediaPublisher(account, group.groupIdHex, references, retained.caption)
                    }
                completeDurableAcceptance(key)
                if (summary.acceptDisposition == SendAcceptDispositionFfi.ACCEPTED_PENDING) {
                    // MDK now owns a durable, unpublished media intent. It still
                    // returns the canonical app-event id, which lets a later
                    // projection settle this exact bubble even when other media
                    // sends are queued at the same time.
                    val acceptedPendingMessageIdHex =
                        summary.messageIds.firstOrNull()?.takeIf(HEX_MESSAGE_ID::matches)
                    // A retry can be discarded while the FFI call is suspended.
                    // MDK cannot retract an accepted intent, but the user chose
                    // to discard this local bubble, so don't retain its bytes or
                    // use the canonical id to restore a deferred projection.
                    if (discardedDuringRetry.remove(key)) {
                        acceptedPendingMessageIdHex?.let(pendingProjectionsAwaitingBridge::remove)
                        optimisticMessages.remove(key)
                        messageById.remove(tempId)
                        retentionAtSendByMessageId.remove(tempId)
                        retainedMediaUploads.remove(key)
                        activeUploadKeys.remove(key)
                        rollbackOptimisticChatListPreview(tempId)
                        publishTimelineFromIndexes()
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
                        publishTimelineFromIndexes()
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
                val confirmedId = summary.messageIds.firstOrNull() ?: tempId
                transferRetentionAtSend(tempId, confirmedId)
                appState.commitOptimisticSentPreview(
                    accountRef = conversationAccountRef,
                    groupIdHex = group.groupIdHex,
                    optimisticMessageIdHex = tempId,
                    confirmedMessageIdHex = confirmedId,
                )
                optimisticMessages.remove(key)
                messageById.remove(tempId)
                // INVARIANT: the discard re-check must run BEFORE any cache mutation
                // below, so a mid-flight discard never seeds the just-sent bytes.
                if (discardedDuringRetry.remove(key)) {
                    // User discarded after publish committed; drop the local
                    // optimistic + bytes. The published event may still echo back
                    // via projection (publish already succeeded — not retractable).
                    retainedMediaUploads.remove(key)
                    activeUploadKeys.remove(key)
                    publishTimelineFromIndexes()
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
                        val confirmedKey = mediaCacheKey(account, confirmedId, index)
                        appState.cacheMediaPlaintext(confirmedKey, attachment.plaintextBytes)
                        // Offload the multi-MB ARGB decode to Default; the
                        // main-confined thumbnail-cache put resumes on Main.
                        // Mirrors the receive/render path in WhiteNoiseApp.
                        val decoded = decodeMediaThumbnailOffMain(attachment.plaintextBytes)
                        if (!mediaUploadSessionStillCurrent(account)) return@forEachIndexed
                        if (decoded != null) {
                            appState.cacheMediaThumbnail(confirmedKey, decoded)
                        }
                        val bytesToPersist = attachment.plaintextBytes
                        val publicationToken = appState.diskMediaCache.capturePublicationToken()
                        // Tag with the uploaded blob's ciphertext hash so the
                        // expiry sweep can wipe this self-sent entry from disk by
                        // hash even after a restart / when its row isn't loaded.
                        // No hash → no durable copy: the expiry sweep evicts disk
                        // strictly by tag, so an untagged entry would outlive its
                        // retention window. L1 still serves this session.
                        val ciphertextTag = references.getOrNull(index)?.ciphertextSha256
                        if (ciphertextTag != null) {
                            appState.launchMutation {
                                withContext(Dispatchers.IO) {
                                    appState.diskMediaCache.put(confirmedKey, bytesToPersist, publicationToken, ciphertextTag)
                                }
                            }
                        }
                    }
                }
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
                                tags = imetaTags,
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
            } catch (throwable: Throwable) {
                // Coroutine cancellation (e.g. leaving the screen) is not a send
                // failure — rethrow so it isn't surfaced as a Failed bubble/toast.
                if (throwable is CancellationException) throw throwable
                if (discardedDuringRetry.remove(key)) {
                    optimisticMessages.remove(key)
                    durableAcceptanceCallbacks.remove(key)
                    messageById.remove(tempId)
                    retentionAtSendByMessageId.remove(tempId)
                    retainedMediaUploads.remove(key)
                    activeUploadKeys.remove(key)
                    rollbackOptimisticChatListPreview(tempId)
                    publishTimelineFromIndexes()
                    return
                }
                optimisticMessages[key] =
                    TimelineMessage(
                        key,
                        optimistic,
                        MessageStatus.Failed,
                        timelineOrder = order,
                        retentionAtSendSeconds = retentionAtSendSeconds,
                    )
                failOptimisticChatListPreview(tempId)
                // Failed bubble shown but bytes are retained for a possible
                // retry — KEEP the key in `activeUploadKeys` so a screen
                // dispose can't wipe the bytes out from under a retry tap.
                // The key drains when the user retries (terminal performMediaUpload
                // path runs) or explicitly discards.
                publishTimelineFromIndexes()
                if (BuildConfig.DEBUG) Log.w("DMConversation", "media upload failed", throwable)
                presentSendFailure(appState, throwable)
            }
        } finally {
            appState.untrackInFlightMediaUpload(conversationAccountRef, group.groupIdHex, key, uploadJob)
        }
    }

    private fun reactionAccountIfAccepted(): String? {
        val accountRef = conversationAccountRef
        if (
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

    private suspend fun commitReactionMutation(
        account: String,
        target: String,
        emoji: String,
        alreadyMine: Boolean,
    ): Boolean {
        appState.withGroupCommitLock(account, group.groupIdHex) {
            if (alreadyMine) {
                retractOwnReaction(account, target, emoji)
            } else {
                appState.marmotIo { reactToMessage(account, group.groupIdHex, target, emoji) }
            }
        }
        return !alreadyMine
    }

    private suspend fun retractOwnReaction(
        account: String,
        target: String,
        emoji: String,
    ) {
        // Retract just the tapped emoji by deleting its own reaction event; the
        // FFI target-only unreact would drop the wrong emoji when the user holds
        // more than one reaction on the same message.
        val me = conversationAccountIdHex ?: error("no active account to retract reaction")
        val ownReactions =
            timelineRecords[target]
                ?.reactions
                ?.userReactions
                .orEmpty()
                .filter { it.sender.equals(me, ignoreCase = true) }
        val reactionEventId =
            ownReactions
                .firstOrNull { it.emoji == emoji && it.reactionMessageIdHex.isNotBlank() }
                ?.reactionMessageIdHex
        when {
            reactionEventId != null ->
                appState.marmotIo { deleteMessage(account, group.groupIdHex, reactionEventId) }
            ownReactions.size == 1 && ownReactions.first().emoji == emoji ->
                appState.marmotIo { unreactFromMessage(account, group.groupIdHex, target) }
            else -> error("no reaction event to retract for $emoji")
        }
    }

    suspend fun toggleReaction(
        emoji: String,
        message: AppMessageRecordFfi,
    ) {
        val account = reactionAccountIfAccepted() ?: return
        val target =
            message.messageIdHex.takeIf { it.isNotBlank() }
                ?: run {
                    appState.present(R.string.toast_reaction_failed)
                    return
                }
        val alreadyMine = reactions[target]?.any { it.emoji == emoji && it.mine } == true
        val optimisticId = UUID.randomUUID().toString()
        val optimisticChange =
            OptimisticReactionChange(
                targetMessageId = target,
                emoji = emoji,
                add = !alreadyMine,
            )
        val mutation =
            runOptimisticReactionMutation(
                applyOptimistic = {
                    optimisticReactionChanges[optimisticId] = optimisticChange
                    recomputeReactions()
                },
                commit = { commitReactionMutation(account, target, emoji, alreadyMine) },
                rollback = {
                    optimisticReactionChanges.remove(optimisticId)
                    recomputeReactions()
                },
            )
        mutation.onFailure { throwable ->
            appState.presentFailure(R.string.toast_reaction_failed, "MESSAGE_REACTION", throwable)
        }
        val reactionCommitted = mutation.getOrDefault(false)
        if (reactionCommitted) {
            // Reacting is unambiguous evidence the user saw this message, so
            // advance the read marker through it. Keep this best-effort and
            // outside the reaction commit rollback path: a read-marker failure
            // must not remove a reaction that has already been published.
            runCatchingCancellable { markReadUpTo(target) }
                .onFailure {
                    if (BuildConfig.DEBUG) Log.w("DMConversation", "mark-read after reaction failed", it)
                }
        }
    }

    suspend fun deleteMessage(
        message: AppMessageRecordFfi,
        presentFailure: Boolean = true,
    ): Boolean = deleteMessageResult(message, presentFailure).isSuccess

    /** Structured variant used by batch delete so retry diagnostics retain a safe failure category. */
    internal suspend fun deleteMessageResult(
        message: AppMessageRecordFfi,
        presentFailure: Boolean = true,
    ): Result<Unit> {
        val account = conversationAccountRef
        return when {
            account == null -> Result.failure(IllegalStateException("Conversation account unavailable"))
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
     * client-side via [aggregateEdits]. Trim is applied before send so a
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
                appState.marmotIo { editMessage(account, group.groupIdHex, target, trimmed) }
            }
            // Publish accepted: drop the Pending indicator but keep the text
            // overlay so the bubble doesn't flicker back to the old body in the
            // gap before the kind-1009 lands in the timeline. The overlay is
            // pruned once `aggregateEdits` reflects the same latest text.
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
            optimisticEdits[target]
                ?.takeIf { it.status == MessageStatus.Pending && it.text == trimmed }
                ?.let { optimisticEdits[target] = OptimisticEdit(trimmed, preEditText, MessageStatus.Failed) }
            publishTimelineFromIndexes()
            appState.presentFailure(R.string.toast_couldnt_edit_message, "MESSAGE_EDIT", throwable)
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

    private fun completeDurableAcceptance(optimisticKey: String) {
        durableAcceptanceCallbacks.remove(optimisticKey)?.invoke()
    }

    private fun rollbackOptimisticChatListPreview(optimisticMessageIdHex: String) {
        appState.rollbackOptimisticSentPreview(conversationAccountRef, group.groupIdHex, optimisticMessageIdHex)
    }

    private fun failOptimisticChatListPreview(optimisticMessageIdHex: String) {
        appState.failOptimisticSentPreview(conversationAccountRef, group.groupIdHex, optimisticMessageIdHex)
    }

    // The pending chat-list preview an outgoing send bumps its row with.
    private fun sentPreview(
        tempId: String,
        plaintext: String,
        contentTokens: MarkdownDocumentFfi,
        timelineAt: ULong,
    ): ChatListMessagePreviewFfi =
        ChatListMessagePreviewFfi(
            messageIdHex = tempId,
            sender = conversationAccountIdHex ?: "",
            senderDisplayName = null,
            plaintext = plaintext,
            contentTokens = contentTokens,
            kind = 9uL,
            timelineAt = timelineAt,
            deleted = false,
            attachmentKind = null,
            attachmentCount = 0u,
            deliveryState = ChatListMessageDeliveryStateFfi.PENDING,
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
    ): String = mediaCacheKey(account, group.groupIdHex, messageIdHex, attachmentIndex)

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
                record.media.mapIndexedNotNull { index, reference ->
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

    /**
     * Yes/no probe: are the decrypted bytes for [messageIdHex] /
     * [attachmentIndex] already resident in either cache tier? Lets a file
     * bubble decide whether to surface the download chevron without firing
     * an FFI hop. Strictly peek — never schedules a download or seeds the
     * cache. Returns false when there's no active account.
     */
    fun hasCachedAttachment(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean {
        val account = conversationAccountRef ?: return false
        val cacheKey = mediaCacheKey(account, messageIdHex, attachmentIndex)
        if (appState.cachedMediaPlaintext(cacheKey) != null) return true
        return appState.diskMediaCache.contains(cacheKey)
    }

    /** Resolve a cold disk-index state without performing main-thread I/O. */
    suspend fun hasCachedAttachmentAfterHydration(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean {
        val account = conversationAccountRef ?: return false
        return appState.hasCachedAttachmentAfterHydration(
            AttachmentTransferRequest(account, group.groupIdHex, messageIdHex, attachmentIndex),
        )
    }

    /** Observable presentation state owned outside the attachment composable. */
    internal fun attachmentTransferState(
        messageIdHex: String,
        attachmentIndex: Int,
        initiallyAvailable: Boolean,
    ): StateFlow<AttachmentTransferState> =
        attachmentTransfers.acquireState(
            key = attachmentTransferKey(messageIdHex, attachmentIndex),
            initiallyAvailable = initiallyAvailable,
        )

    /** Release presentation state when its attachment composable leaves composition. */
    internal fun releaseAttachmentTransferState(
        messageIdHex: String,
        attachmentIndex: Int,
    ) {
        attachmentTransfers.releaseState(attachmentTransferKey(messageIdHex, attachmentIndex))
    }

    /** Suspends until this attachment, rather than any process-wide cache key, becomes available. */
    internal suspend fun awaitNextAttachmentAvailability(
        messageIdHex: String,
        attachmentIndex: Int,
    ) {
        attachmentTransfers.awaitNextAvailability(attachmentTransferKey(messageIdHex, attachmentIndex))
    }

    /**
     * Request one controller-owned transfer. Auto-download and tap-to-open
     * callers receive the same [Deferred], while truthful post-load probing
     * decides whether the result survived in either local cache tier.
     */
    internal fun requestAttachmentTransfer(
        messageIdHex: String,
        attachmentIndex: Int,
        reference: MediaAttachmentReferenceFfi,
        retainedPlaintext: ByteArray? = null,
        priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Interactive,
    ): Deferred<ByteArray> {
        val account = conversationAccountRef
        if (retainedPlaintext == null && reference.sourceEpoch != 0uL && account != null) {
            appState.enqueueAttachmentDownload(
                AttachmentTransferRequest(account, group.groupIdHex, messageIdHex, attachmentIndex),
                priority,
            )
        }
        return attachmentTransfers.request(
            key = attachmentTransferKey(messageIdHex, attachmentIndex),
            load = {
                retainedPlaintext
                    ?: downloadAttachment(messageIdHex, attachmentIndex, reference, priority)
            },
            availableAfterLoad = {
                retainedPlaintext != null ||
                    hasCachedAttachmentAfterHydration(messageIdHex, attachmentIndex)
            },
        )
    }

    internal fun requestAttachmentOpen(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean {
        val account = conversationAccountRef ?: return false
        return appState.attachmentOpens.requestOpen(
            AttachmentTransferRequest(account, group.groupIdHex, messageIdHex, attachmentIndex),
        )
    }

    internal fun attachmentOpenRequest(
        messageIdHex: String,
        attachmentIndex: Int,
    ): AttachmentOpenRequest? {
        val account = conversationAccountRef ?: return null
        return appState.attachmentOpens.openRequest(
            AttachmentTransferRequest(account, group.groupIdHex, messageIdHex, attachmentIndex),
        )
    }

    internal fun hasAttachmentOpenIntent(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean {
        val request = attachmentOpenRequest(messageIdHex, attachmentIndex) ?: return false
        return appState.attachmentOpens.hasIntent(request)
    }

    internal suspend fun consumeAttachmentOpenIntent(
        messageIdHex: String,
        attachmentIndex: Int,
    ): Boolean {
        val request = attachmentOpenRequest(messageIdHex, attachmentIndex) ?: return false
        return appState.attachmentOpens.consume(request)
    }

    internal fun restoreAttachmentOpenIntent(
        messageIdHex: String,
        attachmentIndex: Int,
    ) {
        val request = attachmentOpenRequest(messageIdHex, attachmentIndex) ?: return
        appState.attachmentOpens.restore(request)
    }

    /** Upgrade an optimistic imeta fallback before download/save uses its epoch. */
    internal suspend fun authoritativeAttachmentReference(
        messageIdHex: String,
        attachmentIndex: Int,
        fallback: MediaAttachmentReferenceFfi,
    ): MediaAttachmentReferenceFfi {
        if (fallback.sourceEpoch != 0uL) return fallback
        val account = conversationAccountRef ?: error("no active account")
        val request = AttachmentTransferRequest(account, group.groupIdHex, messageIdHex, attachmentIndex)
        repeat(ATTACHMENT_REFERENCE_RESOLVE_ATTEMPTS) { attempt ->
            appState
                .resolveAttachmentReference(request)
                ?.takeIf { it.sourceEpoch != 0uL }
                ?.let { return it }
            if (attempt < ATTACHMENT_REFERENCE_RESOLVE_ATTEMPTS - 1) {
                delay(ATTACHMENT_REFERENCE_RESOLVE_BACKOFF_MILLIS)
            }
        }
        throw AttachmentReferenceNotReadyException()
    }

    internal suspend fun downloadAttachment(
        messageIdHex: String,
        attachmentIndex: Int,
        reference: MediaAttachmentReferenceFfi,
        priority: AttachmentDownloadPriority,
    ): ByteArray {
        val account = conversationAccountRef ?: error("no active account")
        val request = AttachmentTransferRequest(account, group.groupIdHex, messageIdHex, attachmentIndex)
        if (priority == AttachmentDownloadPriority.Interactive) {
            appState.enqueueAttachmentDownload(request, priority)
        }
        return appState.downloadAttachmentPlaintext(
            request = request,
            reference = reference,
            priority = priority,
        )
    }

    suspend fun downloadAttachment(
        messageIdHex: String,
        attachmentIndex: Int,
        reference: MediaAttachmentReferenceFfi,
    ): ByteArray =
        downloadAttachment(
            messageIdHex,
            attachmentIndex,
            reference,
            AttachmentDownloadPriority.Interactive,
        )

    /** Decoded thumbnail for [messageIdHex] if one is cached (renders with no
     *  spinner). Null when unanchored or not yet decoded. */
    fun thumbnailFor(
        messageIdHex: String,
        attachmentIndex: Int,
    ): android.graphics.Bitmap? {
        val account = conversationAccountRef ?: return null
        return appState.cachedMediaThumbnail(mediaCacheKey(account, messageIdHex, attachmentIndex))
    }

    /** Cache a decoded thumbnail so re-renders / re-entry skip the decode. */
    fun cacheThumbnail(
        messageIdHex: String,
        attachmentIndex: Int,
        bitmap: android.graphics.Bitmap,
    ) {
        val account = conversationAccountRef ?: return
        appState.cacheMediaThumbnail(mediaCacheKey(account, messageIdHex, attachmentIndex), bitmap)
    }

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
            val cacheKey = mediaCacheKey(account, projectedMessageIdHex, index)
            appState.cacheMediaPlaintext(cacheKey, attachment.plaintextBytes)
            // Seed the L1 plaintext synchronously above (the bit that stops the
            // bubble's LaunchedEffect from re-downloading from Blossom), but
            // offload the multi-MB ARGB thumbnail decode off this main-thread
            // reconcile. The main-confined thumbnail-cache put resumes on Main
            // via launchMutation's Main.immediate scope. Mirrors the
            // receive/render path in WhiteNoiseApp.
            val plaintextBytes = attachment.plaintextBytes
            appState.launchMutation {
                val decoded = decodeMediaThumbnailOffMain(plaintextBytes)
                if (decoded != null && mediaUploadSessionStillCurrent(account)) {
                    appState.cacheMediaThumbnail(cacheKey, decoded)
                }
            }
            val bytesToPersist = attachment.plaintextBytes
            val publicationToken = appState.diskMediaCache.capturePublicationToken()
            // Tag with the uploaded blob's ciphertext hash (captured at upload)
            // so hash-based expiry eviction reaches this entry across sessions.
            // No hash → no durable copy: disk expiry eviction is strictly
            // tag-scoped, so an untagged entry would outlive its window.
            val ciphertextTag = retained.uploadedReferences?.getOrNull(index)?.ciphertextSha256
            if (ciphertextTag != null) {
                appState.launchMutation {
                    withContext(Dispatchers.IO) {
                        appState.diskMediaCache.put(cacheKey, bytesToPersist, publicationToken, ciphertextTag)
                    }
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
     */
    suspend fun retryFailedSend(item: TimelineMessage) {
        val key = item.id
        // A failed edit's bubble is the target's projected row (no
        // optimisticMessages entry); its retry re-runs the edit publish rather
        // than re-sending a new message. editMessage flips the overlay back to
        // Pending, so a double-tap finds it non-Failed and the guard below exits.
        val failedEdit = optimisticEdits[item.record.messageIdHex]?.takeIf { it.status == MessageStatus.Failed }
        if (failedEdit != null) {
            editMessage(item.record.messageIdHex, failedEdit.text)
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
            performMediaUpload(account, key, mediaTempId, mediaOrder, current.record)
            return
        }
        val tempId = current.record.messageIdHex
        val text = current.record.plaintext.takeIf { it.isNotBlank() } ?: return
        val replyTarget = MessageProjector.replyTargetMessageId(current.record)
        val refreshedRecord = current.record.copy()
        val order = retriedTimelineOrder(current.timelineOrder) { nextOptimisticTimelineOrder() }
        discardedDuringRetry.remove(key)
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
                preserveOptimisticDisplayPosition(committedProjection.messageIdHex, tempId)
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    appState.marmotIo { retryGroupConvergence(account, group.groupIdHex) }
                }
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
                val projectedAction = TimelineProjector.toAppMessageRecord(committedProjection)
                val projectedRecord =
                    projectedAction.withRecordedAtOverride(
                        localTimelineTimestampOverrides[committedProjection.messageIdHex],
                    )
                messageById[committedProjection.messageIdHex] = projectedRecord
                invalidatedProjectionIdsMatchingMessage(timelineRecords, projectedRecord)
                    .forEach(::removeProjectedRecord)
                if (discardedDuringRetry.remove(key)) {
                    publishTimelineFromIndexes()
                    return
                }
                publishTimelineFromIndexes()
                return
            }
            retryTrace = PerformanceDiagnostics.begin(PerformanceOperation.TEXT_SEND)
            sendTrace(
                retryTrace,
                PerformancePhase.MANUAL_RETRY,
                elapsedMs = 0L,
                result = PerformanceResult.PENDING,
            )
            val summary = publishTextWithRetry(replyTarget, account, text, retryTrace)
            completeDurableAcceptance(key)
            if (discardedDuringRetry.remove(key)) {
                // User discarded mid-flight; drop the result entirely.
                optimisticMessages.remove(key)
                messageById.remove(tempId)
                retentionAtSendByMessageId.remove(tempId)
                rollbackOptimisticChatListPreview(tempId)
                publishTimelineFromIndexes()
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
        throwable.rethrowIfCancellation()
        if (BuildConfig.DEBUG) Log.w("DMConversation", "retryFailedSend failed", throwable)
        when {
            discardedDuringRetry.remove(key) -> {
                // User discarded mid-flight; don't restore the Failed bubble.
                optimisticMessages.remove(key)
                durableAcceptanceCallbacks.remove(key)
                messageById.remove(tempId)
                retentionAtSendByMessageId.remove(tempId)
                rollbackOptimisticChatListPreview(tempId)
                publishTimelineFromIndexes()
            }
            throwable.isUseAfterEviction() -> {
                rollbackOptimisticChatListPreview(tempId)
                optimisticMessages.remove(key)
                durableAcceptanceCallbacks.remove(key)
                messageById.remove(tempId)
                retentionAtSendByMessageId.remove(tempId)
                publishTimelineFromIndexes()
                markActiveAccountRemovedFromMembers(account)
            }
            isAmbiguousRelayDeliveryError(throwable) -> {
                // Publication may already have reached a relay. Keep the
                // existing row Pending; another high-level send could mint a
                // duplicate event.
                sendTrace(
                    retryTrace,
                    PerformancePhase.DELIVERY_UNCERTAIN,
                    result = PerformanceResult.PENDING,
                    layer = PerformanceLayer.TRANSPORT,
                )
                publishTimelineFromIndexes()
            }
            else -> {
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
                presentSendFailure(appState, throwable)
            }
        }
    }

    /**
     * Drops a failed outgoing send from the local timeline. Purely client-side
     * cleanup; the message was never accepted by the relay so there's nothing
     * to retract. Only tracks the id in [discardedDuringRetry] when status is
     * Pending (retry in flight); otherwise the set would grow unbounded with
     * keys no retry coroutine ever consults.
     */
    fun discardFailedSend(item: TimelineMessage) {
        val key = item.id
        // Discarding a failed edit drops the local overlay, reverting the
        // bubble to its pre-edit body. The original message is untouched —
        // only the unsent kind-1009 edit is abandoned.
        if (optimisticEdits[item.record.messageIdHex]?.status == MessageStatus.Failed) {
            optimisticEdits.remove(item.record.messageIdHex)
            publishTimelineFromIndexes()
            return
        }
        // Re-read live state. If the user taps Retry then Discard before the
        // bubble recomposes, the captured item.status is still Failed while
        // the live state has moved to Pending — the Failed branch would
        // no-op past discardedDuringRetry.add, and the in-flight retry would
        // re-insert the confirmed message on completion. tempId likewise
        // comes from the live record because retry refreshes messageIdHex.
        val current = optimisticMessages[key]
        val status = current?.status ?: item.status
        val tempId = current?.record?.messageIdHex ?: item.record.messageIdHex
        when (status) {
            MessageStatus.Failed -> durableAcceptanceCallbacks.remove(key)
            MessageStatus.Pending ->
                if (current != null) {
                    discardedDuringRetry.add(key)
                } else {
                    durableAcceptanceCallbacks.remove(key)
                }
            else -> return
        }
        rollbackOptimisticChatListPreview(tempId)
        optimisticMessages.remove(key)
        messageById.remove(tempId)
        retentionAtSendByMessageId.remove(tempId)
        // Free any retained attachment bytes for a discarded media send.
        retainedMediaUploads.remove(key)
        activeUploadKeys.remove(key)
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
            val account = conversationAccountRef ?: return false
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
                return false
            }
            var demotedBeforeLeave = false
            runCatching {
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    if (soleMember) {
                        appState.deleteGroupLocalWithClientCleanup(account, group.groupIdHex)
                    } else {
                        if (GroupProjector.requiresSelfDemoteBeforeLeave(group, activeAccountIdHex, liveMemberCount)) {
                            withContext(NonCancellable) {
                                val demoteResult = selfDemoteBeforeLeave(account)
                                demotedBeforeLeave = true
                                applyMutationDetails(account, demoteResult.details)
                                appState.marmotIo { leaveGroup(account, group.groupIdHex) }
                            }
                        } else {
                            appState.marmotIo { leaveGroup(account, group.groupIdHex) }
                        }
                    }
                }
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

    private suspend fun selfDemoteBeforeLeave(account: String): GroupMutationResultFfi =
        appState.marmotIo(MarmotTraceSection.SELF_DEMOTE_ADMIN) {
            selfDemoteAdminDetailed(account, group.groupIdHex)
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
            if (!ownsInviteAcceptanceResult || !canAcceptRenderedInvite(previousGroup, generation)) {
                return@withMutationLockResult false
            }
            val authorityEpochBefore = groupAuthorityEpoch
            val optimisticGroup = optimisticAcceptedInvite(previousGroup)
            group = optimisticGroup
            appState.applyLocalGroupUpdate(optimisticGroup, account)
            val attempt =
                InviteAcceptanceAttempt(account, generation, previousGroup, optimisticGroup, authorityEpochBefore)
            val acceptedGroup = resolveInviteAcceptance(attempt)
            if (acceptedGroup == null) return@withMutationLockResult false
            if (
                !ownsInviteAttempt(authorityEpochBefore, optimisticGroup) ||
                !acceptedInviteMatchesGeneration(acceptedGroup, generation)
            ) {
                if (ownsInviteAttempt(authorityEpochBefore, optimisticGroup)) {
                    inviteAcceptanceAwaitingAuthority = generation
                    refreshMembers()
                }
                return@withMutationLockResult false
            }
            acceptedInvitePeerAccount = invitePeerAccount
            group = acceptedGroup
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
                if (ownsInviteAttempt(attempt.authorityEpoch, attempt.optimisticGroup)) {
                    inviteAcceptor(attempt.account, attempt.generation.groupIdHex)
                } else {
                    null
                }
            }
        }.getOrElse { failure ->
            if (failure is MarmotKitException.GroupInviteNotPending) {
                if (ownsInviteAttempt(attempt.authorityEpoch, attempt.optimisticGroup)) {
                    inviteAcceptanceAwaitingAuthority = attempt.generation
                    appState.applyLocalGroupUpdate(group, attempt.account)
                    refreshMembers()
                }
                return@getOrElse null
            }
            val currentGroup = group
            group = rollbackOptimisticAcceptedInvite(currentGroup, attempt.optimisticGroup, attempt.previousGroup)
            val ownsPresentation = ownsInviteAcceptanceResult
            if (ownsPresentation && group != currentGroup) appState.applyLocalGroupUpdate(group, attempt.account)
            failure.rethrowIfCancellation()
            if (ownsPresentation) {
                appState.presentFailure(R.string.toast_couldnt_accept_invite, "GROUP_INVITE_ACCEPT", failure)
            }
            null
        }

    suspend fun declineInvite(): Boolean =
        withMutationLockResult(false) {
            val account = conversationAccountRef ?: return@withMutationLockResult false
            runCatching {
                appState.marmotIo { declineGroupInvite(account, group.groupIdHex) }
                appState.dismissConversationNotifications(account, group.groupIdHex)
                group = group.copy(pendingConfirmation = false, archived = true)
                appState.applyLocalGroupUpdate(group)
                presentConversationTransient(R.string.toast_invite_declined)
                true
            }.getOrElse {
                it.rethrowIfCancellation()
                appState.presentFailure(R.string.toast_couldnt_decline_invite, "GROUP_INVITE_DECLINE", it)
                false
            }
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
            val intent = ConversationArchiveIntent(archived)
            pendingArchiveIntent = intent
            val chatListIntent = appState.beginChatListArchiveIntent(account, groupIdHex, archived)
            val authorityEpochBefore = groupAuthorityEpoch
            try {
                runCatchingCancellable {
                    appState.withGroupCommitLock(account, groupIdHex) {
                        val updated = groupArchivedUpdater(account, groupIdHex, archived)
                        // A newer authoritative application (subscription update
                        // or details round-trip) that landed while this commit
                        // was in flight wins over the late success; the engine's
                        // own archive-change event reconverges the projection.
                        if (groupAuthorityEpoch == authorityEpochBefore) {
                            group = updated
                            appState.applyLocalGroupUpdate(updated)
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
                if (pendingArchiveIntent === intent) pendingArchiveIntent = null
                appState.finishChatListArchiveIntent(account, groupIdHex, chatListIntent)
            }
        }

    suspend fun deleteGroupLocal(): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            runCatchingCancellable {
                appState.deleteGroupLocalWithClientCleanup(account, group.groupIdHex)
                presentConversationTransient(R.string.toast_chat_deleted_local)
                true
            }.onFailure {
                recordMutationFailure(R.string.toast_couldnt_delete_chat, "GROUP_LOCAL_DELETE", it)
            }.getOrDefault(false)
        }

    suspend fun updateGroupProfile(
        name: String,
        description: String,
    ): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            val updatedName = name.trim().takeIf { it.isNotEmpty() }
            val updatedDescription = description.trim().takeIf { it.isNotEmpty() }
            runCatchingCancellable {
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    appState.marmotIo {
                        updateGroupProfile(
                            account,
                            group.groupIdHex,
                            updatedName,
                            updatedDescription,
                        )
                    }
                }
                presentConversationTransient(R.string.toast_group_updated)
                true
            }.onFailure {
                recordMutationFailure(R.string.toast_couldnt_update_group, "GROUP_PROFILE_UPDATE", it)
            }.getOrDefault(false)
        }

    suspend fun updateGroupAvatarUrl(url: String?): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            // The Rust side validates + normalizes the URL (https-only, no
            // private hosts). We only set the URL here; dim/thumbhash are
            // optimization hints we don't compute on Android, so clear them.
            val normalized = url?.trim()?.takeIf { it.isNotEmpty() }
            var encryptedImageCleared = group.imageHashHex == null || normalized == null
            runCatchingCancellable {
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    appState.marmotIo {
                        updateGroupAvatarUrl(account, group.groupIdHex, normalized, null, null)
                    }
                    // A public avatar supersedes the encrypted member-only
                    // component. Remove that component after the public URL is
                    // durable so clearing the URL on another client cannot
                    // resurrect an obsolete private image.
                    if (normalized != null && group.imageHashHex != null) {
                        encryptedImageCleared =
                            runCatchingCancellable {
                                appState.marmotIo {
                                    clearGroupImage(account, group.groupIdHex)
                                }
                            }.onFailure {
                                if (BuildConfig.DEBUG) Log.w("DMConversation", "encrypted avatar cleanup failed", it)
                            }.isSuccess
                    }
                }
                // Reflect the change locally so the avatar updates immediately,
                // without waiting for the group-state subscription to converge.
                group = groupWithPublicAvatar(group, normalized, encryptedImageCleared)
                presentConversationTransient(R.string.toast_group_updated)
                true
            }.onFailure {
                recordMutationFailure(R.string.toast_couldnt_update_group, "GROUP_AVATAR_URL_UPDATE", it)
            }.getOrDefault(false)
        }

    internal suspend fun updateGroupImage(draft: ImageUploadDraft?): Boolean {
        val requestedMutationKey =
            draft?.let {
                withContext(Dispatchers.Default) { it.mutationKey() }
            } ?: REMOVE_GROUP_IMAGE_MUTATION_KEY
        return withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            var attemptedLegacyClear = false
            runCatchingCancellable {
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    if (shouldCommitPrimaryGroupImageMutation(
                            requestedMutationKey = requestedMutationKey,
                            pendingLegacyClearMutationKey = pendingLegacyAvatarClearAfterImageMutationKey,
                            hasProjectedEncryptedImage = group.imageHashHex != null,
                        )
                    ) {
                        if (draft != null) {
                            appState.marmotIo {
                                updateGroupImage(
                                    account,
                                    group.groupIdHex,
                                    draft.plaintext,
                                    draft.mediaType,
                                )
                            }
                        } else {
                            appState.marmotIo {
                                clearGroupImage(account, group.groupIdHex)
                            }
                        }
                    }

                    // URL avatars win over encrypted images. Clear the legacy
                    // component only after the encrypted mutation succeeds so
                    // a partial failure never leaves the group image-less.
                    if (!group.avatarUrl.isNullOrBlank() || pendingLegacyAvatarClearAfterImageMutationKey != null) {
                        pendingLegacyAvatarClearAfterImageMutationKey = requestedMutationKey
                        attemptedLegacyClear = true
                        appState.marmotIo {
                            updateGroupAvatarUrl(account, group.groupIdHex, null, null, null)
                        }
                        pendingLegacyAvatarClearAfterImageMutationKey = null
                    }
                }
                refreshMembers()
                presentConversationTransient(R.string.toast_group_updated)
                true
            }.onFailure {
                presentGroupImageMutationFailure(it, requestedMutationKey, attemptedLegacyClear)
            }.getOrDefault(false)
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
            if (failure == GroupImageMutationFailure.UploadCleanup) {
                R.string.toast_group_image_uploaded_cleanup_failed
            } else {
                R.string.toast_couldnt_update_group
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

    private suspend fun resolveCanonicalInviteRefs(memberRefs: List<String>): List<String>? =
        try {
            canonicalGroupInviteRefs(memberRefs, appState::resolveAccountIdHex)
        } catch (throwable: Throwable) {
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

    @Suppress("LongMethod") // Invite and optional promotion form one optimistic roster transaction.
    suspend fun inviteMembers(
        memberRefs: List<String>,
        addAsAdmin: Boolean = false,
    ): Boolean {
        return withMutationLockResult(false) {
            lastMutationError = null
            if (!canAdministerMembersFromAuthoritativeRoster()) return@withMutationLockResult false
            val account = conversationAccountRef ?: return@withMutationLockResult false
            val refs = resolveCanonicalInviteRefs(memberRefs) ?: return@withMutationLockResult false
            if (refs.isEmpty()) return@withMutationLockResult false
            optimisticGroupRosterMutation.track(OptimisticGroupRosterMutation.Invite(refs)) {
                var inviteSent = false
                try {
                    val adminTargets = if (addAsAdmin) refs else emptyList()
                    val outcome =
                        appState.withGroupCommitLock(account, group.groupIdHex) {
                            if (!canAdministerMembersFromAuthoritativeRoster()) {
                                return@withGroupCommitLock GroupAdministrationCommitOutcome.ROSTER_CHANGED
                            }
                            val inviteResult =
                                appState.marmotIo(MarmotTraceSection.INVITE_MEMBERS) {
                                    inviteMembersDetailed(account, group.groupIdHex, refs)
                                }
                            applyMutationDetails(account, inviteResult.details)
                            inviteSent = true
                            adminTargets.forEach { target ->
                                val promoteResult =
                                    appState.marmotIo(MarmotTraceSection.PROMOTE_ADMIN) {
                                        promoteAdminDetailed(account, group.groupIdHex, target)
                                    }
                                applyMutationDetails(account, promoteResult.details)
                            }
                            GroupAdministrationCommitOutcome.COMMITTED
                        }
                    if (outcome == GroupAdministrationCommitOutcome.ROSTER_CHANGED) {
                        presentRosterChanged(R.string.toast_couldnt_add_members)
                        return@track false
                    }
                    presentConversationTransient(R.string.toast_invite_sent)
                    true
                } catch (throwable: Throwable) {
                    throwable.rethrowIfCancellation()
                    val rawMessage = throwable.message.orEmpty()
                    if (inviteSent && addAsAdmin) {
                        // The invite is already out; keep the UI honest about the
                        // partial success and leave the row-level Admin switch as the
                        // retry path once the member appears in details.
                        recordMutationFailure(
                            R.string.toast_invite_sent_but_couldnt_add_admin,
                            "GROUP_INVITE_ADMIN_PROMOTION",
                            throwable,
                        )
                        true
                    } else if (isDuplicateSignatureKeyError(rawMessage)) {
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
                        recordMutationFailure(R.string.toast_couldnt_add_members, "GROUP_INVITE_MEMBER", throwable)
                        false
                    }
                }
            }
        }
    }

    suspend fun removeMember(member: AppGroupMemberRecordFfi): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            // remove_members signs a roster update for a Nostr pubkey, so use the
            // stable member id. memberRef may be a local account label.
            val target = member.memberIdHex
            optimisticGroupRosterMutation.track(OptimisticGroupRosterMutation.Remove(target)) {
                try {
                    val outcome =
                        appState.withGroupCommitLock(account, group.groupIdHex) {
                            authoritativeAdministrationTarget(target)
                                ?: return@withGroupCommitLock GroupAdministrationCommitOutcome.ROSTER_CHANGED
                            val result =
                                appState.marmotIo(MarmotTraceSection.REMOVE_MEMBERS) {
                                    removeMembersDetailed(account, group.groupIdHex, listOf(target))
                                }
                            applyMutationDetails(account, result.details)
                            GroupAdministrationCommitOutcome.COMMITTED
                        }
                    if (outcome == GroupAdministrationCommitOutcome.ROSTER_CHANGED) {
                        presentRosterChanged(R.string.toast_couldnt_remove_member)
                        return@track false
                    }
                    presentConversationTransient(R.string.toast_member_removed)
                    true
                } catch (throwable: Throwable) {
                    throwable.rethrowIfCancellation()
                    // The commit may have persisted before a follow-on publish
                    // failed. One authoritative details read is enough here; an MLS
                    // replay probe would duplicate bridge work and does not affect
                    // whether this specific target remains in the local roster.
                    refreshMembers()
                    if (members.none { it.memberIdHex.equals(target, ignoreCase = true) }) {
                        presentConversationTransient(R.string.toast_member_removed)
                        true
                    } else {
                        recordMutationFailure(R.string.toast_couldnt_remove_member, "GROUP_REMOVE_MEMBER", throwable)
                        false
                    }
                }
            }
        }

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
                                    appState.marmotIo(MarmotTraceSection.PROMOTE_ADMIN) {
                                        promoteAdminDetailed(account, group.groupIdHex, target)
                                    }
                                applyMutationDetails(account, result.details)
                            } else {
                                val result =
                                    appState.marmotIo(MarmotTraceSection.DEMOTE_ADMIN) {
                                        demoteAdminDetailed(account, group.groupIdHex, target)
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
                    appState.marmotIo { updateMessageRetention(account, group.groupIdHex, disappearingMessageSecs) }
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

    /** Publishes only the newest management-capability request for this conversation. */
    suspend fun refreshManagementState() {
        val account = conversationAccountRef ?: return
        val requestToken = managementStateLifetime.advance()
        runCatchingCancellable {
            appState.marmotIo { groupManagementState(account, group.groupIdHex) }
        }.onSuccess { refreshed ->
            managementStateLifetime.runIfCurrent(requestToken) { managementState = refreshed }
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w("DMConversation", "management state refresh failed", it)
        }
    }

    /** Install and require the lifecycle component in one admin commit. */
    suspend fun enableGroupDisbanding(): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = conversationAccountRef ?: return@withMutationLockResult false
            runCatchingCancellable {
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    val result = appState.marmotIo { enableGroupDisbanding(account, group.groupIdHex) }
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
                    appState.marmotIo { disbandGroup(account, group.groupIdHex) }
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
                            appState.marmotIo(MarmotTraceSection.SELF_DEMOTE_ADMIN) {
                                selfDemoteAdminDetailed(account, group.groupIdHex)
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
                            appState.marmotIo(MarmotTraceSection.PROMOTE_ADMIN) {
                                promoteAdminDetailed(account, group.groupIdHex, target)
                            }
                        grantedBeforeDemote = true
                        applyMutationDetails(account, promoteResult.details)
                        // The grant has already landed on the MLS group. If the scope is
                        // cancelled now, skipping the demote would strand both accounts as
                        // admin without telling the caller (runCatchingCancellable propagates
                        // cancellation past the partial-state branch). Run it to completion.
                        withContext(NonCancellable) {
                            val demoteResult =
                                appState.marmotIo(MarmotTraceSection.SELF_DEMOTE_ADMIN) {
                                    selfDemoteAdminDetailed(account, group.groupIdHex)
                                }
                            applyMutationDetails(account, demoteResult.details)
                        }
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

    fun isAdmin(member: AppGroupMemberRecordFfi): Boolean =
        projectedGroupAdmin(
            authoritativeAdmin = GroupProjector.isAdmin(group, member),
            memberIdHex = member.memberIdHex,
            mutation = optimisticGroupRosterMutation.current,
        )

    internal fun isAuthoritativeAdmin(member: AppGroupMemberRecordFfi): Boolean = GroupProjector.isAdmin(group, member)

    fun memberDisplayName(member: AppGroupMemberRecordFfi): String = appState.displayName(member.memberIdHex)

    fun memberSubtitle(member: AppGroupMemberRecordFfi): String = appState.shortNpub(member.memberIdHex)

    fun memberAvatarUrl(member: AppGroupMemberRecordFfi): String? = appState.avatarUrl(member.memberIdHex)

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
                                ): TimelinePageFfi = appState.marmotIo { timelineMessages(accountRef, query) }
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

    suspend fun loadOlder() {
        loadOlderPage()
    }

    /** True when the canonical timeline holds more history after the loaded window. */
    val hasMoreAfterTimeline: Boolean
        get() = hasMoreAfter

    /** Pages the subscription window older; true when the window actually advanced. */
    suspend fun loadOlderTimelinePage(): Boolean = loadOlderPage()

    /** Pages the subscription window newer; true when the window actually advanced. */
    suspend fun loadNewerTimelinePage(): Boolean = loadNewerPage()

    suspend fun loadUntilMessageAvailable(
        messageIdHex: String,
        maxOlderPages: Int = ReplyNavigation.MaxOlderPages,
    ): Boolean {
        var loadedPageCount = 0
        while (
            ReplyNavigation.shouldLoadOlder(
                targetLoaded = timelineRecords.containsKey(messageIdHex),
                hasMoreBefore = hasMoreBefore,
                loadedPageCount = loadedPageCount,
                maxOlderPages = maxOlderPages,
            )
        ) {
            if (!loadOlderPage()) break
            loadedPageCount += 1
        }
        return timelineRecords.containsKey(messageIdHex)
    }

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
                    ConversationSearchPageDirection.OLDER -> loadOlderPage()
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
     * Resolve attachment references without duplicating MarmotKit's projection
     * state. A projected list is authoritative even when empty; tag parsing is
     * reserved for optimistic/compatibility records that do not have a
     * projected row yet.
     */
    fun mediaReferencesFor(item: TimelineMessage): List<MediaAttachmentReferenceFfi> = item.projected?.media ?: mediaReferencesFor(item.record)

    fun mediaReferencesFor(record: AppMessageRecordFfi): List<MediaAttachmentReferenceFfi> =
        timelineRecords[record.messageIdHex]?.media
            ?: MediaReferenceSupport.parseAllImetaTags(
                tags = record.tags,
                sourceEpoch = record.sourceEpoch ?: 0uL,
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

    private suspend fun loadOlderPage(): Boolean {
        if (!hasMoreBefore || isLoadingOlder) return false
        val subscription = timelineSubscription ?: return false
        val priorMessageIds = timelineRecords.keys.toSet()
        // A previous loadOlderPage failure leaves `error` set; clear it now
        // that we're actually retrying, otherwise the stale banner sits over
        // a successful retry and a developer can't distinguish "still broken"
        // from "we forgot to clear it".
        pageError = null
        isLoadingOlder = true
        return try {
            // The subscription's paginate_backwards extends the runtime's
            // materialized window backwards by `count` and returns the new
            // authoritative window — already deduped, sorted, head-anchored,
            // and cap-trimmed. We render it directly via replaceWindow=true.
            val page = paginateOlderIfSubscriptionActive(subscription) ?: return false
            hasLoadedOlderPages = true
            failedPageDirection = null
            applyTimelinePage(page, replaceWindow = true, updatePagination = true)
            protectedTimelineMessageIds.clear()
            protectedTimelineMessageIds.addAll(timelineRecords.keys)
            // "Made progress" = the window grew OR shifted to include older
            // ids. paginateBackwards() returns a bounded/capped full window,
            // so size can stay constant while content still advances backward.
            timelineRecords.size > priorMessageIds.size ||
                timelineRecords.keys.any { it !in priorMessageIds }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            failedPageDirection = ConversationSearchPageDirection.OLDER
            pageError =
                privacySafeErrorPresentation(
                    "CONVERSATION_PAGE_OLDER",
                    throwable,
                    AppText.Resource(R.string.error_loaded_content_kept),
                )
            false
        } finally {
            isLoadingOlder = false
        }
    }

    private suspend fun loadNewerPage(): Boolean {
        val subscription = timelineSubscription
        if (!hasMoreAfter || isLoadingOlder || subscription == null) return false
        val priorMessageIds = timelineRecords.keys.toSet()
        pageError = null
        isLoadingOlder = true
        return try {
            val page = paginateNewerIfSubscriptionActive(subscription)
            if (page == null) {
                false
            } else {
                applyTimelinePage(page, replaceWindow = true, updatePagination = true)
                failedPageDirection = null
                protectedTimelineMessageIds.clear()
                if (hasLoadedOlderPages) {
                    protectedTimelineMessageIds.addAll(timelineRecords.keys)
                }
                timelineRecords.size > priorMessageIds.size ||
                    timelineRecords.keys.any { it !in priorMessageIds }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            failedPageDirection = ConversationSearchPageDirection.NEWER
            pageError =
                privacySafeErrorPresentation(
                    "CONVERSATION_PAGE_NEWER",
                    throwable,
                    AppText.Resource(R.string.error_loaded_content_kept),
                )
            false
        } finally {
            isLoadingOlder = false
        }
    }

    suspend fun retryLoadFailure() {
        if (subscriptionError?.retryable == false) return
        when (failedPageDirection?.takeIf { pageError != null }) {
            ConversationSearchPageDirection.OLDER -> loadOlderPage()
            ConversationSearchPageDirection.NEWER -> loadNewerPage()
            null ->
                if (terminalLoadFailure) {
                    terminalLoadFailure = false
                    retryGeneration += 1L
                } else {
                    retryLoadSignal.trySend(Unit)
                }
        }
    }

    private suspend fun paginateOlderIfSubscriptionActive(subscription: ConversationTimelineSubscriptionHandle): TimelinePageFfi? =
        timelineSubscriptionActiveCallMutex.withLock {
            val stillActive =
                synchronized(liveSubscriptionLock) {
                    !accountTeardownRequested && timelineSubscription === subscription
                }
            if (!stillActive) return@withLock null
            withContext(Dispatchers.IO) {
                subscription.paginateBackwards(ConversationTimelinePageLimit)
            }
        }

    private suspend fun paginateNewerIfSubscriptionActive(subscription: ConversationTimelineSubscriptionHandle): TimelinePageFfi? =
        timelineSubscriptionActiveCallMutex.withLock {
            val stillActive =
                synchronized(liveSubscriptionLock) {
                    !accountTeardownRequested && timelineSubscription === subscription
                }
            if (!stillActive) return@withLock null
            withContext(Dispatchers.IO) {
                subscription.paginateForwards(ConversationTimelinePageLimit)
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
                ?: appState.marmotIo {
                    timelineMessages(
                        account,
                        TimelineMessageQueryFfi(
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
        return applyTimelinePage(page, replaceWindow = true, updatePagination = true)
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

    /** Applies one timeline page and invalidates suspended whole-window refreshes. */
    private suspend fun applyTimelinePage(
        page: TimelinePageFfi,
        replaceWindow: Boolean,
        updatePagination: Boolean,
    ): List<String> {
        timelineWindowGeneration.advance()
        val pageMessages = page.messages
        if (replaceWindow) trimStateForWindowReplacement()
        authoritativeTimelineOrderByMessageId.clear()
        pageMessages.forEachIndexed { index, record ->
            // Keep MDK's optimistic-head position for pending local projections.
            // Only terminally invalidated rows without accepted-history evidence
            // become timestamped overlays; otherwise an old failed send can
            // displace a newly confirmed bubble at the bottom of the timeline.
            if (record.usesAuthoritativePageOrder()) {
                authoritativeTimelineOrderByMessageId[record.messageIdHex] = index.toULong()
            }
            val actionRecord =
                upsertProjectedRecord(
                    record,
                    reconcileOptimistic = replaceWindow,
                    allowDelayedProjection = replaceWindow,
                )
            appState.requestProfile(record.sender)
            record.replyPreview?.let { appState.requestProfile(it.sender) }
            appState.requestProfiles(record.reactions.userReactions.map { it.sender })
            if (record.deleted) {
                deletedMessageIds = deletedMessageIds - record.messageIdHex
            }
            if (MessageProjector.isStreamFinal(actionRecord)) {
                MessageProjector.streamId(actionRecord)?.let { streamId ->
                    activeStreamIds.remove(streamId)
                    // Mark removed so a late AgentStreamUpdateFfi.Finished event
                    // can't recreate the optimistic preview as a duplicate. See #25.
                    removedStreamIds.add(streamId)
                    optimisticMessages.remove("stream:$streamId")
                }
            }
        }
        applyDurableStreamPositions(durableStreamDisplayPositions(timelineRecords.values.toList()))
        if (updatePagination) {
            hasMoreBefore = page.hasMoreBefore
            hasMoreAfter = page.hasMoreAfter
        }
        pruneReadAnchorsToWindow()
        pruneConfirmedOptimisticMessages()
        pruneRetentionAtSendToWindow()
        pruneConfirmedOptimisticReactions()
        pruneMessageOverlaysToWindow()
        recomputeReactions()
        // A non-replaceWindow page (older-history load once hasLoadedOlderPages
        // is set) skips the replaceWindow trim above, so prune messageById to the
        // current window + optimistic records here too (#373).
        pruneMessageByIdToWindow(messageById, timelineRecords.keys, optimisticMessages.values)
        pruneOptimisticEditsToWindow()
        // The pass above already projected every record into the by-id and
        // order indexes (and reconciled optimistics), exactly like the live
        // update paths do — so publish directly. A second full rebuild here
        // re-projected every held record on each page load. See #74.
        val preparingInitialPresentation = !hasPreparedInitialPresentation
        hasPublishedAuthoritativeTimeline = true
        initialTimelineSeedActive = false
        publishTimelinePageBeforeMarkdownHydration(pageMessages)
        scheduleProfilePresentationWarm(
            records = pageMessages,
            markInitialPresentationReady = preparingInitialPresentation,
        )
        return pageMessages
            .map { TimelineProjector.toAppMessageRecord(it) }
            .filter { MessageProjector.isStreamStart(it) }
            .mapNotNull { MessageProjector.streamId(it) }
            // Don't relaunch a watcher for a stream whose final record was in
            // this same page — it was just marked removed. See #25.
            .filterNot { it in removedStreamIds }
    }

    /**
     * Publish protocol rows immediately, then warm existing local profile
     * presentation state. MainShell keeps a prepared chat-list tap on its source
     * page until this initial warm completes, preventing sender metadata from
     * remeasuring bubbles during the route transition.
     */
    private fun scheduleProfilePresentationWarm(
        records: List<TimelineMessageRecordFfi>,
        markInitialPresentationReady: Boolean,
    ) {
        if (!markInitialPresentationReady) return
        initialPresentationWarmCoordinator.prepare(initialPresentationProfileSenders(records))
    }

    private fun publishTimelinePageBeforeMarkdownHydration(records: List<TimelineMessageRecordFfi>) {
        val scope = conversationScope
        if (scope == null) {
            publishTimelineFromIndexes()
            return
        }
        publishTimelineBeforeMarkdownHydration(
            scope = scope,
            records = records,
            publish = ::publishTimelineFromIndexes,
            hydrate = ::hydrateTimelineMarkdown,
            applyHydrated = ::applyHydratedTimelineMarkdown,
        )
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
        pruneRetentionAtSendToWindow()
        // Live Upsert/Projection batches add to messageById but never trim it;
        // prune to the (now-bounded) window + optimistic records so it doesn't
        // grow unbounded for an actively-watched conversation (#373).
        pruneMessageByIdToWindow(messageById, timelineRecords.keys, optimisticMessages.values)
        pruneOptimisticEditsToWindow()
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
     * Reuses the controller's [lastReadMessageId] dedupe so a quiet scroll
     * (settled on the same row) doesn't issue redundant FFI hops.
     */
    suspend fun markReadUpTo(messageId: String) {
        val trimmed = messageId.takeIf { it.isNotBlank() } ?: return
        // Optimistic messages carry a Kotlin UUID as their messageIdHex
        // ("xxxxxxxx-xxxx-..."). The FFI rejects anything that isn't a 64-char
        // hex blob (InvalidHex at the first '-'). Skip — the projection will
        // call markReadUpTo again with the confirmed hex id once it echoes.
        if (!HEX_MESSAGE_ID.matches(trimmed)) return
        if (trimmed == lastReadMessageId) return
        val account = conversationAccountRef ?: return
        val previous = lastReadMessageId
        lastReadMessageId = trimmed
        val markReadResult =
            runCatching {
                appState.marmotIo { markTimelineMessageRead(account, group.groupIdHex, trimmed) }
            }
        val markReadFailure = markReadResult.exceptionOrNull()
        if (markReadFailure != null) {
            if (lastReadMessageId == trimmed) lastReadMessageId = previous
            if (markReadFailure is CancellationException) throw markReadFailure
            if (BuildConfig.DEBUG) Log.w("DMConversation", "mark read failed", markReadFailure)
            return
        }
        markReadResult.getOrNull()?.let { row ->
            persistedLastReadTimelineAt =
                foldMarkReadReturnedRow(
                    row = row,
                    persistedLastReadTimelineAt = persistedLastReadTimelineAt,
                    applyChatListRow = { appState.applyChatListRowFromMarkRead(account, it) },
                )
        }
        val anchoredAtSeconds = (clockMillis() / 1_000L).toULong()
        anchorReadExpiryUpTo(trimmed, anchoredAtSeconds)
        runCatchingCancellable {
            appState.dismissConversationNotifications(account, group.groupIdHex)
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w("DMConversation", "dismiss read notifications failed", it)
        }
    }

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

    private fun pruneConfirmedOptimisticReactions() {
        confirmedOptimisticReactionKeys(
            activeAccountIdHex = conversationAccountIdHex,
            optimisticChanges = optimisticReactionChanges,
            confirmedSendersByTarget = baseReactionSenders(),
        ).forEach(optimisticReactionChanges::remove)
    }

    private fun upsertProjectedRecord(
        record: TimelineMessageRecordFfi,
        reconcileOptimistic: Boolean = false,
        allowDelayedProjection: Boolean = false,
        displayedProjectedStreamItemIds: Set<String> = emptySet(),
    ): AppMessageRecordFfi {
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
            return TimelineProjector.toAppMessageRecord(record)
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
        val draftAction = TimelineProjector.toAppMessageRecord(record)
        val projectedIsMediaUpsert = draftAction.tags.any { it.values.firstOrNull() == "imeta" }
        val hasAcceptedPendingTextBridge =
            reconcileOptimistic && record.messageIdHex in acceptedPendingTextOptimisticIds
        val acceptedPendingTextOptimisticId =
            if (hasAcceptedPendingTextBridge) {
                acceptedPendingTextOptimisticIdForProjection(record.messageIdHex)
            } else {
                null
            }
        val acceptedPendingMediaOptimisticId =
            acceptedPendingMediaOptimisticIdForProjection(record.messageIdHex).takeIf { reconcileOptimistic }
        val acceptedPendingOptimisticId =
            acceptedPendingTextOptimisticId ?: acceptedPendingMediaOptimisticId
        val hasExactBridge =
            optimisticMessages.values.any { it.record.messageIdHex == record.messageIdHex } ||
                hasAcceptedPendingTextBridge ||
                acceptedPendingOptimisticId != null
        if (projectedIsMediaUpsert && !hasExactBridge && reconcileOptimistic) {
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
        if (
            record.sourceMessageIdHex == null &&
            record.invalidationStatus == null &&
            failedOptimisticMessageIdForInvalidatedProjection(optimisticMessages.values, actionRecord) != null
        ) {
            timelineRecords[record.messageIdHex] = record
            projectedMessageIds.add(record.messageIdHex)
            messageById[record.messageIdHex] = actionRecord
            return actionRecord
        }
        if (record.invalidationStatus == null) {
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
        val reconciledOptimisticId =
            acceptedPendingOptimisticId
                ?: if (hasAcceptedPendingTextBridge) {
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
            messageById.remove(optimisticId)
            if (optimisticId == acceptedPendingTextOptimisticId) {
                acceptedPendingTextOptimisticIds.remove(record.messageIdHex)
            }
            // The engine echo just flipped this pending bubble to a
            // projected record. If we're tracing this send, this is the
            // "self-echo drives the sent flip" path (issue #913).
            traceEchoReconcile(optimisticId)
        }
        messageById[record.messageIdHex] = actionRecord
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
     * Drops completed optimistic-send bridges so projected rows settle back to
     * their authoritative positions in MDK's current bounded window.
     */
    private fun releaseOrphanedOptimisticSendPreserves(): Boolean {
        val orphaned =
            optimisticSendPositionPreserves.releaseOrphaned(
                optimisticKeys = optimisticMessages.keys,
                projectedMessageIds = projectedMessageIds,
            )
        if (orphaned.isEmpty()) return false
        orphaned.forEach { id ->
            localTimelineOrderOverrides.remove(id)
            localTimelineTimestampOverrides.remove(id)
            preservedTimelinePositionOverrideIds.remove(id)
            refreshProjectedTimelinePosition(id)
        }
        return true
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
    private fun removeProjectedRecord(messageIdHex: String) {
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
        pruneReadAnchorsToWindow()
    }

    private fun pruneReadAnchorsToWindow() {
        if (readAnchoredAtSeconds.isEmpty()) return
        val retained = HashSet(timelineRecords.keys)
        optimisticMessages.values.forEach { retained.add(it.record.messageIdHex) }
        readAnchoredAtSeconds.keys.retainAll(retained)
    }

    private fun pruneRetentionAtSendToWindow() {
        if (retentionAtSendByMessageId.isEmpty()) return
        val retained = HashSet(timelineRecords.keys)
        optimisticMessages.values.forEach { retained.add(it.record.messageIdHex) }
        retentionAtSendByMessageId.keys.retainAll(retained)
    }

    // Drop optimistic edits whose target message has left the window (no longer
    // in timelineRecords nor backed by an optimistic record). The status-based
    // prune in publishTimelineFromIndexesInternal can't fire once aggregated[target]
    // goes null, so a never-echoed Pending edit would otherwise leak (#691).
    private fun pruneOptimisticEditsToWindow() {
        if (optimisticEdits.isEmpty()) return
        val present = HashSet(timelineRecords.keys)
        optimisticMessages.values.forEach { present.add(it.record.messageIdHex) }
        optimisticEdits.keys.retainAll { it in present }
    }

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
                        if (record.sourceMessageIdHex == null) {
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
        val aggregated = aggregateEdits(visible.map { it.record })
        // Drop any optimistic edit the real kind-1009 has now caught up to:
        // once `aggregateEdits` reports the same latest text, the overlay is
        // redundant and would otherwise mask a later remote edit. Failed/Pending
        // overlays are kept until they resolve through editMessage.
        optimisticEdits.entries
            .filter { (target, edit) -> edit.status == MessageStatus.Sent && aggregated[target]?.latestText == edit.text }
            .map { it.key }
            .forEach(optimisticEdits::remove)
        timeline =
            orderTimelineMessagesForDisplay(
                (visible + streamDebugTimelineItems.values).map { it.withOptimisticEditStatus() },
            )
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
     * new affordance. Only overrides a confirmed (Sent) own bubble — a still
     * in-flight optimistic *send* keeps its own status until that send resolves.
     */
    private fun TimelineMessage.withOptimisticEditStatus(): TimelineMessage {
        val edit = optimisticEdits[record.messageIdHex] ?: return this
        if (status != MessageStatus.Sent) return this
        return when (edit.status) {
            MessageStatus.Pending -> copy(status = MessageStatus.Pending)
            MessageStatus.Failed -> copy(status = MessageStatus.Failed)
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
            sendersByTarget
                .mapValues { (_, byEmoji) ->
                    byEmoji
                        .mapNotNull { (emoji, senders) ->
                            if (senders.isEmpty()) {
                                null
                            } else {
                                ReactionTally(
                                    emoji = emoji,
                                    count = senders.size,
                                    mine = mine != null && senders.contains(mine),
                                )
                            }
                        }.sortedWith(
                            compareByDescending<ReactionTally> { it.count }
                                .thenByDescending { it.mine }
                                .thenBy { it.emoji },
                        )
                }.filterValues { it.isNotEmpty() }
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

    private fun reactionTalliesFor(targetMessageId: String): List<ReactionTally> {
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

    fun reactionParticipantsFor(targetMessageId: String): List<ReactionParticipant> {
        val mine = conversationAccountIdHex
        val participants =
            timelineRecords[targetMessageId]
                ?.reactions
                ?.userReactions
                ?.map {
                    ReactionParticipant(
                        sender = it.sender,
                        emoji = it.emoji,
                        reactedAt = it.reactedAt,
                    )
                }?.toMutableList() ?: mutableListOf()

        if (mine != null) {
            optimisticReactionChanges.values
                .filter { it.targetMessageId == targetMessageId }
                .forEach { change ->
                    participants.removeAll {
                        it.sender.equals(mine, ignoreCase = true) && it.emoji == change.emoji
                    }
                    if (change.add) {
                        participants +=
                            ReactionParticipant(
                                sender = mine,
                                emoji = change.emoji,
                                reactedAt = nowSeconds(),
                            )
                    }
                }
        }

        return participants.sortedWith(
            compareBy<ReactionParticipant> { !it.sender.equals(mine, ignoreCase = true) }
                .thenBy { it.reactedAt }
                .thenBy { it.sender.lowercase() }
                .thenBy { it.emoji },
        )
    }

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
        if (ownsInviteAcceptanceResult) {
            withMutationLockResult(Unit) { if (inviteAcceptanceAwaitingAuthority != null) refreshMembers() }
        }
    }

    /** Starts a roster read only while this controller still owns its account presentation. */
    private fun beginMemberRosterRefresh(): Long? =
        synchronized(liveSubscriptionLock) {
            if (accountTeardownRequested || controllerCleared) null else memberRosterRefreshGeneration.advance()
        }

    /** Publishes the latest authoritative roster while rejecting older refresh completions. */
    private suspend fun refreshMembers(retryOnHydrationPending: Boolean = true) {
        val account = conversationAccountRef ?: return
        val refreshGeneration = beginMemberRosterRefresh() ?: return
        memberRosterLoadTracker.transition(GroupRosterRefreshEvent.STARTED)
        try {
            runCatchingCancellable {
                // One projection replaces the serialized groupMlsState() eviction and groupDetails() roster reads.
                // It carries membership, admins, epoch/revision, lifecycle, and self-membership together.
                val roster = groupRosterReader(account, group.groupIdHex)
                memberRosterRefreshGeneration.runIfCurrent(refreshGeneration) {
                    val applied = applyGroupRoster(account, roster) ?: return@runIfCurrent
                    appState.applyLocalGroupDetails(account, applied.group, applied.members)
                }
            }.onFailure { throwable ->
                if (!memberRosterRefreshGeneration.isCurrent(refreshGeneration)) {
                    return@onFailure
                }
                if (retryOnHydrationPending && throwable is MarmotKitException.GroupHydrationPending) {
                    // Deferred hydration answers early reads with a retryable pending state; the runtime
                    // promotes the group shortly after account readiness, so wait once instead of showing
                    // a failed roster.
                    delay(GROUP_HYDRATION_RETRY_DELAY_MS)
                    if (memberRosterRefreshGeneration.isCurrent(refreshGeneration)) {
                        refreshMembers(retryOnHydrationPending = false)
                    }
                    return@onFailure
                }
                memberRosterRefreshGeneration.runIfCurrent(refreshGeneration) {
                    if (throwable.isUseAfterEviction()) {
                        markActiveAccountRemovedFromMembers(account)
                    } else {
                        memberRosterLoadTracker.transition(GroupRosterRefreshEvent.FAILED)
                        if (BuildConfig.DEBUG) Log.w("DMConversation", "refresh members failed", throwable)
                    }
                }
            }
        } catch (cancel: CancellationException) {
            memberRosterRefreshGeneration.runIfCurrent(refreshGeneration) {
                memberRosterLoadTracker.restoreAfterCancellation()
            }
            throw cancel
        }
    }

    /** Applies the engine's eviction proof and invalidates any in-flight group mutation result. */
    private fun markActiveAccountRemovedFromMembers(account: String) {
        val activeAccountIdHex = conversationAccountIdHex ?: return
        groupAuthorityEpoch += 1L
        // Engine-confirmed removal (UseAfterEviction). Record the same
        // authoritative local-left marker the leaveGroup() success path sets so
        // a later authoritative roster result that races ahead of eviction can't re-add
        // self (issue #787).
        recordSelfLeft()
        val updatedMembers =
            members.filterNot {
                GroupProjector.isActiveAccountMember(it, activeAccountIdHex)
            }
        members = updatedMembers
        acceptedInvitePeerAccount = null
        membersLoaded = true
        membersVerified = true
        inviteAcceptanceAwaitingAuthority = null
        memberRosterLoadTracker.transition(GroupRosterRefreshEvent.SUCCEEDED)
        // UseAfterEviction is the engine's authoritative signal that this
        // conversation can no longer accept composer writes. Invalidate an
        // in-flight immutable dictation target before any late provider result
        // can reach the draft persistence path.
        appState.conversationDictation.onTargetRemoved(account, group.groupIdHex)
        appState.cacheGroupMemberSnapshot(account, group.groupIdHex, updatedMembers)
    }

    /**
     * Latch the authoritative local self-leave marker (issue #787). Set on a
     * confirmed self-leave (leaveGroup success) or engine eviction
     * ([markActiveAccountRemovedFromMembers]); honoured by [isSelfMember] and
     * roster application so a transient round-trip can't restore self
     * before the engine eviction is observed locally.
     */
    private fun recordSelfLeft() {
        selfMembership.recordSelfLeft()
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
    ): AppliedGroupDetails? {
        val resolution = resolveAuthoritativeGroupRoster(details, conversationAccountIdHex)
        return applyResolvedGroupRoster(
            account = account,
            resolution = resolution,
            returnedRowCount = details.members.size,
            returnedMembership = details.group.selfMembership,
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

    private fun applyResolvedGroupRoster(
        account: String,
        resolution: GroupRosterResolution,
        returnedRowCount: Int,
        returnedMembership: SelfMembershipFfi,
    ): AppliedGroupDetails? {
        val previousRetention = group.disappearingMessageSecs
        val previousGroup = group
        resolution.invariant?.let { invariant ->
            memberRosterLoadTracker.transition(GroupRosterRefreshEvent.INCONSISTENT)
            logGroupRosterInvariant(
                resolution = resolution,
                invariant = invariant,
                returnedRowCount = returnedRowCount,
                returnedMembership = returnedMembership,
            )
            return null
        }
        val applied = resolution.applied
        groupAuthorityEpoch += 1L
        group =
            reconcileTerminalSelfMembership(
                update = applied.group,
                previous = previousGroup,
            )
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
        memberRosterLoadTracker.transition(GroupRosterRefreshEvent.SUCCEEDED)
        cacheAppliedGroupMembers(appState, account, group.groupIdHex, members)
        return AppliedGroupDetails(group = group, members = members)
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

    /** Applies committed group details and invalidates any roster refresh launched earlier. */
    private fun applyMutationDetails(
        account: String,
        details: GroupDetailsFfi,
    ) {
        memberRosterRefreshGeneration.advance()
        val applied = applyGroupDetails(account, details) ?: return
        appState.applyLocalGroupDetails(account, applied.group, applied.members)
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

    // --- Send-latency trace (issues #913, #2224) -----------------------------
    private fun traceNowMs(): Long = SystemClock.elapsedRealtime()

    private fun sendTrace(
        trace: PerformanceTrace?,
        phase: PerformancePhase,
        elapsedMs: Long? = null,
        durationMs: Long = 0L,
        result: PerformanceResult = PerformanceResult.SUCCESS,
        layer: PerformanceLayer = PerformanceLayer.ANDROID,
        attempt: Int? = null,
        queueDepth: Int? = null,
        count: Int? = null,
    ) {
        if (trace == null) return
        PerformanceDiagnostics.record(
            trace = trace,
            phase = phase,
            elapsedMs = elapsedMs ?: (traceNowMs() - trace.startedAtMs),
            durationMs = durationMs,
            result = result,
            layer = layer,
            attempt = attempt,
            queueDepth = queueDepth,
            count = count,
        )
    }

    // Record the trace for an optimistic text send so the
    // engine-echo reconcile can time the accepted → echoed-reconcile flip.
    // Bounded so a burst of never-echoed sends can't grow the map.
    private fun rememberSendTrace(
        tempId: String,
        trace: PerformanceTrace?,
    ) {
        if (trace == null) return
        sendTraceByTempId[tempId] = trace
        while (sendTraceByTempId.size > SEND_TRACE_MAX_TRACKED) {
            val oldest = sendTraceByTempId.keys.firstOrNull() ?: break
            sendTraceByTempId.remove(oldest)
        }
    }

    private fun forgetSendTrace(tempId: String) {
        sendTraceByTempId.remove(tempId)
    }

    // Called when the engine echo reconciles a pending optimistic bubble into a
    // projected record. If the reconciled optimistic id is one we're tracing,
    // log the accepted → echoed-reconcile latency — this is the path that flips
    // pending → sent when the self-echo lands before/instead of the send()
    // success block, the "subscription churn / self-echo drives the flip"
    // candidate in issue #913.
    private fun traceEchoReconcile(optimisticId: String) {
        val trace = sendTraceByTempId.remove(optimisticId) ?: return
        sendTrace(trace, PerformancePhase.ECHO_RECONCILE)
    }

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

        private const val ATTACHMENT_REFERENCE_RESOLVE_ATTEMPTS = 3
        private const val ATTACHMENT_REFERENCE_RESOLVE_BACKOFF_MILLIS = 150L

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
