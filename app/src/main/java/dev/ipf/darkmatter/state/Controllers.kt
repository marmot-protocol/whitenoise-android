package dev.ipf.darkmatter.state

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.darkmatter.BuildConfig
import dev.ipf.darkmatter.R
import dev.ipf.darkmatter.core.ChatListMessageSearch
import dev.ipf.darkmatter.core.EditState
import dev.ipf.darkmatter.core.GroupProjector
import dev.ipf.darkmatter.core.GroupSystemEvents
import dev.ipf.darkmatter.core.MessageBodyMatch
import dev.ipf.darkmatter.core.MessageProjector
import dev.ipf.darkmatter.core.MessageTextCopy
import dev.ipf.darkmatter.core.ReactionTally
import dev.ipf.darkmatter.core.ReplyNavigation
import dev.ipf.darkmatter.core.TimelineProjector
import dev.ipf.darkmatter.core.TimelineReplyDisplay
import dev.ipf.darkmatter.core.aggregateEdits
import dev.ipf.darkmatter.core.replyMediaKindFromMime
import dev.ipf.darkmatter.media.MediaPipeline
import dev.ipf.darkmatter.media.MediaReferenceParser
import dev.ipf.marmotkit.AgentStreamSubscription
import dev.ipf.marmotkit.AgentStreamUpdateFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscription
import dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.ChatsSubscription
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupStateSubscription
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessagesSubscription
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineSubscriptionUpdateFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class ChatListItem(
    val group: AppGroupRecordFfi,
    val latest: AppMessageRecordFfi?,
    val otherMemberAccount: String?,
    val memberCount: Int,
    val memberSnapshot: GroupMemberSnapshot?,
    val projection: ChatListRowFfi? = null,
    /**
     * Markdown AST for the last-message preview line, parsed off-main by
     * [ChatsController] from the same plaintext [projectedPreviewText]
     * returns. Null (or an empty document) while the parse is in flight or
     * failed — the row then renders the raw plaintext exactly as before.
     * Only attached when the preview would show the message body itself
     * (non-deleted, non-blank), so fallback copy is never styled.
     */
    val previewTokens: MarkdownDocumentFfi? = null,
) {
    val id: String = group.groupIdHex

    val projectedTitle: String?
        get() = projection?.title?.takeIf { it.isNotBlank() }

    val latestAt: ULong?
        // Prefer the last message's timeline timestamp. A freshly-created chat
        // has no message yet, so fall back to the projection's `updatedAt`
        // (set to `now` when the engine first projects the row on group create
        // — see storage-sqlite chat_list `rebuild_chat_list_row_for_group_tx`,
        // same unix-seconds unit as `timelineAt`). Without this fallback a new
        // DM/group would sort with `0uL` and land at the bottom of the chat
        // list instead of the top (issue #321).
        get() = projection?.lastMessage?.timelineAt ?: projection?.updatedAt ?: latest?.recordedAt

    val unreadCount: ULong
        get() = projection?.unreadCount ?: 0uL

    val hasUnread: Boolean
        get() = projection?.hasUnread ?: false

    fun projectedPreviewText(
        copy: MessageTextCopy = MessageTextCopy.Default,
        empty: String = "No messages yet",
    ): String {
        val preview = projection?.lastMessage ?: return MessageProjector.previewText(latest, copy, empty)
        return when {
            preview.deleted -> copy.deleted
            preview.kind == 1200uL -> preview.plaintext.ifBlank { copy.agentStreamStarted }
            // Kind-1009 edits are an in-place mutation of an existing
            // message body; they must not bump the chat-list preview to
            // "edit content" nor reorder the conversation. The original
            // [latest] message stays projected — drop this row's edit
            // payload from the preview text path.
            preview.kind == 1009uL -> MessageProjector.previewText(latest, copy, empty)
            // Before the generic plaintext arm: a kind-1210 last message would
            // otherwise leak its raw JSON content into the chat list.
            MessageProjector.isGroupSystemKind(preview.kind) ->
                GroupSystemEvents.previewText(preview.plaintext, copy.groupSystem)
            preview.plaintext.isNotBlank() -> preview.plaintext
            else -> copy.message
        }
    }
}

internal fun sortChatListItems(items: List<ChatListItem>): List<ChatListItem> =
    items.sortedWith(
        compareByDescending<ChatListItem> { it.group.pendingConfirmation }
            .thenByDescending { it.latestAt ?: 0uL }
            .thenBy { chatListItemSortKey(it) },
    )

/**
 * Sort tie-breaker key. Mirrors the gating that the UI uses to derive a
 * display title: named groups sort by the projected/raw name; unnamed
 * groups sort by the peer account (stable; co-locates the same DM-shaped
 * conversation across renders) or, lacking that, the member count. The
 * raw group hex must never leak into the sort key — that's what the UI
 * stopped showing, and the sort would otherwise drift away from it.
 */
internal fun chatListItemSortKey(item: ChatListItem): String =
    if (item.group.name.isNotBlank()) {
        (item.projectedTitle ?: item.group.name).lowercase()
    } else {
        (item.otherMemberAccount ?: "~${item.memberCount}").lowercase()
    }

/**
 * Build a `ChatListItem` from the FFI projection. The optional [members]
 * snapshot lets the caller hand in the group's member roster fetched
 * separately (the chat-list FFI doesn't include members on each row).
 * When present, the snapshot drives the `otherMemberAccount` /
 * `memberCount` fields that `GroupProjector.displayTitle` reads and stays on
 * the item as [ChatListItem.memberSnapshot] for local shared-group
 * intersections — that's how an unnamed group resolves to "Group of N people"
 * or the other member's display name instead of leaking the group hex into the
 * UI.
 * Without it, those fields fall back to null/0 and the local projection
 * shows a short group hex until [ChatsController]'s async members fetch
 * fills the cache.
 */
internal fun chatListItemFromProjection(
    row: ChatListRowFfi,
    group: AppGroupRecordFfi? = null,
    activeAccountIdHex: String? = null,
    members: List<AppGroupMemberRecordFfi>? = null,
    previewTokens: MarkdownDocumentFfi? = null,
): ChatListItem {
    val baseGroup = group ?: emptyGroupRecord(row)
    val displayGroup =
        baseGroup.copy(
            name = row.groupName.ifBlank { baseGroup.name },
            archived = row.archived,
            pendingConfirmation = row.pendingConfirmation,
        )
    val otherMember =
        members?.let { GroupProjector.otherMemberAccount(it, activeAccountIdHex) }
    val memberCount = members?.size ?: 0
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
                    contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
                    kind = preview.kind,
                    tags = emptyList(),
                    recordedAt = preview.timelineAt,
                    receivedAt = preview.timelineAt,
                )
            },
        otherMemberAccount = otherMember,
        memberCount = memberCount,
        memberSnapshot = members?.let(::GroupMemberSnapshot),
        projection = row,
        previewTokens = previewTokens,
    )
}

/**
 * The last-message text a chat row should run through the markdown parser,
 * or null when the row's preview line will show fallback copy instead of
 * the message body (no last message, deleted, or blank plaintext). Keeping
 * this predicate beside [chatListItemFromProjection] ties the parse gate to
 * the same plaintext `projectedPreviewText` would surface.
 */
internal fun chatRowPreviewMarkdownSource(row: ChatListRowFfi): String? {
    val preview = row.lastMessage ?: return null
    if (preview.deleted) return null
    return preview.plaintext.takeIf { it.isNotBlank() }
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

private fun emptyGroupRecord(row: ChatListRowFfi): AppGroupRecordFfi =
    AppGroupRecordFfi(
        groupIdHex = row.groupIdHex,
        endpoint = "",
        name = row.groupName,
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "",
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        encryptedMedia = defaultEncryptedMediaComponent(),
        archived = row.archived,
        pendingConfirmation = row.pendingConfirmation,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
    )

private fun defaultEncryptedMediaComponent(): AppGroupEncryptedMediaComponentFfi =
    AppGroupEncryptedMediaComponentFfi(
        componentId = 0x8008u,
        component = "marmot.group.encrypted-media.v1",
        required = true,
        mediaFormat = "encrypted-media-v1",
        allowedLocatorKinds = listOf("blossom-v1"),
        defaultBlobEndpoints =
            listOf(
                AppBlobEndpointFfi(
                    locatorKind = "blossom-v1",
                    baseUrl = "https://blossom.primal.net",
                ),
            ),
    )

data class GroupMemberSnapshot(
    val members: List<AppGroupMemberRecordFfi>,
) {
    val memberCount: Int = members.size

    fun otherMemberAccount(activeAccountIdHex: String?): String? = GroupProjector.otherMemberAccount(members, activeAccountIdHex)

    fun containsAccount(accountIdHex: String): Boolean {
        val normalized = accountIdHex.trim().takeIf { it.isNotEmpty() } ?: return false
        return members.any { it.memberIdHex.equals(normalized, ignoreCase = true) }
    }
}

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
    messageById[optimistic.messageIdHex] = optimistic
    optimisticMessages[key] =
        TimelineMessage(
            key,
            optimistic,
            MessageStatus.Failed,
            timelineOrder = timelineOrder,
        )
}

data class ReactionParticipant(
    val sender: String,
    val emoji: String,
    val reactedAt: ULong,
)

/**
 * Whether a text [send] can commit an optimistic record. Pulled out as a pure
 * predicate so the UI's "clear the input" decision is driven by the *same*
 * answer the controller uses to decide whether to insert the optimistic bubble
 * — the two must never disagree, otherwise the input clears while the message
 * is silently dropped (issue #264).
 *
 * `accountRef` null → no active account bound yet; blank text → nothing to send;
 * `canSend` false → membership not yet verified (the composer is intentionally
 * shown during the `refreshMembers()` load window) or the user is not a member.
 */
internal fun canAcceptTextSend(
    accountRef: String?,
    trimmed: String,
    canSend: Boolean,
): Boolean = accountRef != null && trimmed.isNotEmpty() && canSend

/**
 * How many times a text/reply send retries the FFI publish before surfacing a
 * user-visible failure, and how long it waits between attempts. The send path
 * in the Marmot runtime already retries individual relay sockets, but a publish
 * that begins during a *transient* connectivity gap (doze wake, network change,
 * background-connection toggle mid-reconnect) can see an empty/under-connected
 * pool at the single instant it fans out and fail fast with a *connect-phase*
 * failure — even though a relay reconnects a moment later (issue #294). One
 * bounded retry sweep across [SEND_RETRY_ATTEMPTS], gated by
 * [isTransientRelaySendError], gives the pool that moment before we tell the
 * user the send failed, so a momentary gap no longer surfaces as a hard error
 * while a sustained outage (all attempts exhausted) still does. Only failures
 * that prove the event never left the device are retried, so a re-send can
 * never duplicate a message that actually reached a relay.
 */
internal const val SEND_RETRY_ATTEMPTS: Int = 3
internal val SEND_RETRY_BACKOFF_MS: Long = 700L

/**
 * Whether [throwable] is a relay-connectivity failure that proves the event was
 * **never transmitted to any relay**, and is therefore safe to re-send by
 * re-entering the high-level FFI send. Recognizes only the *connect-phase*
 * reasons the Nostr transport surfaces when the relay pool is momentarily empty
 * or still handshaking at fan-out time (issue #294).
 *
 * IDEMPOTENCY IS THE CONTRACT. The bounded retry in [publishTextWithRetry]
 * retries by calling `sendText` / `replyToMessage` again, and each call builds a
 * **new** inner app event in the Marmot runtime
 * (`marmot-app::runtime::send_message`) — there is no caller-supplied
 * idempotency key. So we may only retry failures that happen *before* the
 * transport ever calls `send_event_to`; otherwise a relay that accepted the
 * first event but whose ack was lost/late would receive a second, distinct
 * event and peers would see a duplicate user message (adversarial review of
 * PR #299). The deliberately EXCLUDED post-send / ambiguous reasons are:
 *   - `send event timed out`          — `send_event_to` was called; the frame
 *                                        may have landed, only the OK ack timed
 *                                        out (transport-nostr-adapter
 *                                        `sdk_client.rs` "send event timed out").
 *   - `relay did not acknowledge event` — the relay returned the event in
 *                                        `output.failed`; it WAS transmitted.
 *   - `publish timed out after Ns: accepted X of required Y` /
 *     `insufficient publish acknowledgements: accepted X of required Y`
 *                                      — the same string is emitted whether
 *                                        `accepted` is 0 or > 0, so we cannot
 *                                        prove nothing landed.
 *   - `TransportClosed`               — surfaces from BOTH the worker
 *                                        command-send channel (pre-publish) and
 *                                        the response channel *after* the worker
 *                                        may have already published
 *                                        (`marmot-app::runtime` response await);
 *                                        the UniFFI variant carries an empty
 *                                        message so the two are indistinguishable,
 *                                        hence ambiguous.
 * A manual retry affordance (a user re-tapping send) is the right place to
 * recover those ambiguous cases, because the user can see whether the message
 * actually went through — an automatic re-send cannot.
 *
 * String-matched on the FFI error message + cause chain because the UniFFI
 * surface flattens these into [dev.ipf.marmotkit.MarmotKitException.Publish] /
 * `.Runtime` without a typed connectivity code. Keep the matched phrases in sync
 * with the transport-nostr-adapter connect-phase reasons (`connect relay timed
 * out`, `connection refused`, `connection reset`, `no relay endpoints`).
 * Under-matching reverts to fail-fast (the message just isn't auto-retried);
 * over-matching risks duplicate sends, so the predicate is deliberately narrow.
 */
internal fun isTransientRelaySendError(throwable: Throwable): Boolean {
    val text =
        generateSequence(throwable) { it.cause }
            .joinToString(separator = "\n") { error ->
                listOfNotNull(error.message, error.javaClass.simpleName).joinToString(" ")
            }.lowercase()
    // Connect-phase only: the transport raises these before it ever calls
    // `send_event_to`, so the event provably never reached a relay and a
    // re-send cannot duplicate it.
    return ("connect relay" in text && ("timed out" in text || "timeout" in text)) ||
        ("connection refused" in text) ||
        ("connection reset" in text) ||
        ("no relay endpoints" in text)
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
                it.status == MessageStatus.Pending &&
                    it.record.tags.any { tag -> tag.values.firstOrNull() == "_media_pending" }
            }
        if (pendingMediaCount > 1) return null
    }
    return optimisticMessages
        .firstOrNull { optimistic ->
            val sendable = optimistic.status == MessageStatus.Pending || optimistic.status == MessageStatus.Sent
            if (!sendable) return@firstOrNull false
            if (optimistic.record.direction != projected.direction) return@firstOrNull false
            if (optimistic.record.groupIdHex != projected.groupIdHex) return@firstOrNull false
            if (optimistic.record.sender != projected.sender) return@firstOrNull false
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

            // Standard match for text sends: plaintext + tags equal.
            optimistic.record.plaintext == projected.plaintext &&
                optimistic.record.tags == projected.tags
        }?.record
        ?.messageIdHex
}

private fun timestampsAreNear(
    left: ULong,
    right: ULong,
): Boolean = if (left >= right) left - right <= 1uL else right - left <= 1uL

internal fun shouldInsertSentOptimisticMessage(
    confirmedId: String,
    projectedMessageIds: Set<String>,
): Boolean = confirmedId !in projectedMessageIds

internal fun compareTimelineMessages(
    left: TimelineMessage,
    right: TimelineMessage,
): Int =
    compareValuesBy(left, right, {
        it.record.recordedAt
    }, { it.timelineOrder }, { it.id })

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

// Derived-state event kinds: rows that arrive as `received` from the
// network but represent state changes (edits, group system events), not
// new chat. They never inflate unread counts and never block read-anchor
// advancement.
private fun isDerivedStateKind(kind: ULong): Boolean = kind == 1009uL || kind == 1210uL

/**
 * Monotonic read-anchor advance. Returns the candidate row's id (the row at
 * [candidateIndex]) only when it is strictly deeper than the current anchor's
 * live position — or when there is no current anchor, or the current anchor
 * has fallen out of the loaded window. Otherwise returns [currentAnchorId]
 * unchanged, so scrolling up can never move the read pointer backwards.
 */
internal fun nextReadAnchor(
    timeline: List<TimelineMessage>,
    currentAnchorId: String?,
    candidateIndex: Int,
): String? {
    val candidateId = timeline.getOrNull(candidateIndex)?.record?.messageIdHex
    if (candidateId.isNullOrBlank()) return currentAnchorId
    if (currentAnchorId == null) return candidateId
    val anchorIdx = timeline.indexOfFirst { it.record.messageIdHex == currentAnchorId }
    return if (anchorIdx < 0 || candidateIndex > anchorIdx) candidateId else currentAnchorId
}

data class ConversationControllerCopy(
    val waitingForStream: String = "Waiting for stream...",
    val streamFailedFormat: String = "Stream failed: %1\$s",
) {
    fun streamFailed(message: String): String = String.format(streamFailedFormat, message)
}

internal fun agentStreamFailureText(
    throwable: Throwable,
    copy: ConversationControllerCopy,
): String {
    if (throwable is CancellationException) throw throwable
    return copy.streamFailed(throwable.message ?: throwable.javaClass.simpleName)
}

private data class OptimisticReactionChange(
    val targetMessageId: String,
    val emoji: String,
    val add: Boolean,
)

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
)

class ChatsController(
    private val appState: DarkMatterAppState,
) {
    var items by mutableStateOf<List<ChatListItem>>(emptyList())
        private set
    var archivedItems by mutableStateOf<List<ChatListItem>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The account this controller is currently bound to (observable so
     *  notification routing can tell when the right account's list is ready). */
    var boundAccountRef by mutableStateOf<String?>(null)
        private set

    private var accountRef: String? = null
    private var chatRows = listOf<ChatListRowFfi>()
    private var groupRecordsById = mapOf<String, AppGroupRecordFfi>()

    // Whether the chat list is on screen. While a conversation is foregrounded
    // the subscription stays warm (updates keep folding into the maps above),
    // but the recompute is deferred so list projection doesn't compete with the
    // conversation on the heaviest nav path. See #6.
    private var chatListVisible = true
    private var pendingRecompute = false

    // Per-group member snapshots fetched via the `groupMembers` FFI.
    // The chat-list FFI doesn't include member rosters on each row, so
    // these snapshots drive both unnamed-group title fallback and local
    // shared-groups derivation for the profile sheet. Filled lazily on first
    // `recompute()` per group; re-fetched on bind.
    private var memberCacheByGroup: Map<String, List<AppGroupMemberRecordFfi>> = emptyMap()

    // Tracks groups whose member fetch is currently in flight, so we don't
    // fan out duplicate work for the same group. Invariant: an id sits in
    // exactly one state at a time — pending (not in either set), in-flight
    // (here), or cached (in [memberCacheByGroup]). Entries are added in
    // [schedulePendingMemberFetches] and removed in the same coroutine's
    // `finally` so a failed fetch can be retried on the next recompute;
    // `bind()` clears the set alongside the cache to reset both at once.
    private val inFlightMemberFetches = mutableSetOf<String>()

    // Widening member snapshots to every group makes the chat-list projection
    // much more useful, but the app should not start one roster FFI call per
    // group on large accounts. Keep the one-shot per-group invariant above,
    // while bounding simultaneous FFI/IO work.
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

    // Same single-state invariant as [inFlightMemberFetches], keyed by
    // preview plaintext: pending → in flight (here) → cached.
    private val inFlightPreviewParses = mutableSetOf<String>()

    // Monotonically increments on every `bind()`. Captured by each
    // [schedulePendingMemberFetches] job; once a later bind has happened
    // (account switch, sign-out, or re-bind), the captured epoch no
    // longer matches and the job drops its result instead of poisoning
    // the new account's cache with stale members.
    private var bindEpoch: Long = 0L

    suspend fun bind(accountRef: String?) {
        chatsDebug { "bind account=${accountRef?.take(8)}" }
        this.accountRef = accountRef
        this.boundAccountRef = accountRef
        chatRows = emptyList()
        groupRecordsById = emptyMap()
        memberCacheByGroup = emptyMap()
        inFlightMemberFetches.clear()
        previewTokensByText = emptyMap()
        inFlightPreviewParses.clear()
        bindEpoch += 1L
        recompute()
        error = null

        if (accountRef == null) return
        isLoading = true
        try {
            // Converge this (just-bound) account's store before we snapshot it.
            // One SQLite store exists per account-device identity, so on a
            // multi-account device an inactive account never ingests a sibling
            // account's group rename / avatar / membership commit until its own
            // worker catches up. Without this, switching to a second account in
            // a shared group shows the pre-rename title (#252) — the projection
            // we're about to read is stale, not wrong. catchUpAccounts pumps
            // every running worker, so the snapshot below sees the converged
            // state instead of us caching the change Android-side. Best-effort
            // (swallows failures internally) so an offline/slow relay can't
            // block the chat list from rendering its last-known projection.
            appState.catchUpAccounts()
            var retryDelayMs = LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS
            while (coroutineContext.isActive) {
                var chatListSubscription: ChatListSubscription? = null
                var chatsSubscription: ChatsSubscription? = null
                try {
                    val chatListStream =
                        appState.marmotIo { subscribeChatList(accountRef, includeArchived = true) }
                    chatListSubscription = chatListStream
                    val chatStream =
                        appState.marmotIo { subscribeChats(accountRef, includeArchived = true) }
                    chatsSubscription = chatStream
                    chatRows =
                        withContext(Dispatchers.IO) {
                            chatListStream.snapshot()
                        }
                    chatRows.forEach(::requestChatRowProfiles)
                    groupRecordsById =
                        withContext(Dispatchers.IO) {
                            chatStream.snapshot()
                        }.associateBy { it.groupIdHex }
                    groupRecordsById.values.forEach(::requestGroupProfiles)
                    chatsDebug {
                        "snapshot account=${accountRef.take(8)} rows=${chatRows.size} groups=${groupRecordsById.size} " +
                            "${chatRows.map { it.debugSummary() }}"
                    }
                    isLoading = false
                    error = null
                    recompute()
                    retryDelayMs = LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS

                    coroutineScope {
                        runUntilFirstLiveSubscriptionEnds(
                            first = {
                                while (isActive) {
                                    val update =
                                        withContext(Dispatchers.IO) {
                                            chatListStream.nextUpdate()
                                        } ?: break
                                    when (update) {
                                        is ChatListSubscriptionUpdateFfi.Row -> {
                                            val row = update.row
                                            requestChatRowProfiles(row)
                                            chatsDebug {
                                                "chat list update account=${accountRef.take(8)} trigger=${update.trigger} ${row.debugSummary()}"
                                            }
                                            foldChatRow(row)
                                        }
                                        is ChatListSubscriptionUpdateFfi.RemoveRow -> {
                                            chatsDebug {
                                                "chat list remove account=${accountRef.take(8)} trigger=${update.trigger} id=${update.groupIdHex.take(8)}"
                                            }
                                            removeChatRow(update.groupIdHex)
                                        }
                                    }
                                }
                            },
                            second = {
                                while (isActive) {
                                    val update =
                                        withContext(Dispatchers.IO) {
                                            chatStream.next()
                                        } ?: break
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
                    if (chatRows.isEmpty()) {
                        isLoading = false
                        error = throwable.message ?: throwable.javaClass.simpleName
                    }
                } finally {
                    // NonCancellable: a cancelled bind must still close subscriptions
                    // so a retry loop or account switch cannot leak account-wide
                    // chat-list/chats handles. (Originally surfaced in #270.)
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { chatListSubscription?.close() }
                        runCatching { chatsSubscription?.close() }
                    }
                }
                if (!coroutineContext.isActive) break
                chatsDebug { "chat subscriptions ended; retrying in ${retryDelayMs}ms account=${accountRef.take(8)}" }
                delay(retryDelayMs)
                retryDelayMs = nextLiveSubscriptionRetryDelayMillis(retryDelayMs)
            }
        } catch (cancel: CancellationException) {
            // Expected when LaunchedEffect re-keys (account switch, navigate
            // away). Re-throw so structured concurrency unwinds cleanly and we
            // don't log normal lifecycle events as bind failures.
            throw cancel
        } catch (throwable: Throwable) {
            chatsDebug(throwable) { "bind failed account=${accountRef.take(8)}: ${throwable.message ?: throwable.javaClass.simpleName}" }
            isLoading = false
            error = throwable.message ?: throwable.javaClass.simpleName
        } finally {
            chatsDebug { "unbind account=${accountRef?.take(8) ?: "<none>"} (chat-list + chats subscriptions closed)" }
        }
    }

    private fun foldGroup(record: AppGroupRecordFfi) {
        groupRecordsById = groupRecordsById + (record.groupIdHex to record)
        recompute()
    }

    // Marmot's `set_group_archived` writes local state + saves but emits no
    // ProjectionUpdated event, so the chat-list snapshot stays stale until the
    // next account switch (issue: unarchive doesn't move chat out of archived
    // section). Callers in ConversationController forward the updated record
    // here via AppState so the chat list reflects the new archived flag.
    fun applyLocalGroupUpdate(record: AppGroupRecordFfi) {
        if (accountRef == null) return
        if (groupRecordsById[record.groupIdHex] == null) return
        // chatListItemFromProjection reads row.archived (not group.archived), so
        // patch both the chat row and the group record to keep them consistent.
        chatRows =
            chatRows.map { row ->
                if (row.groupIdHex == record.groupIdHex) row.copy(archived = record.archived) else row
            }
        foldGroup(record)
    }

    // Project every current chat row into a ChatListItem. Reads chatRows (kept
    // current by the bind loop even when recompute is deferred behind an open
    // conversation, #6), so on-demand callers — shared groups, DM lookup,
    // by-id resolution for navigation — see freshly created/updated groups
    // instead of the stale `items` snapshot.
    private fun currentProjectedItems(): List<ChatListItem> {
        val me = appState.activeAccount?.accountIdHex
        return chatRows.map { row ->
            chatListItemFromProjection(
                row = row,
                group = groupRecordsById[row.groupIdHex],
                activeAccountIdHex = me,
                members = memberCacheByGroup[row.groupIdHex],
                previewTokens = chatRowPreviewMarkdownSource(row)?.let { previewTokensByText[it] },
            )
        }
    }

    fun sharedGroupsWith(
        accountIdHex: String,
        activeAccountIdHex: String?,
    ): List<ChatListItem> = sharedChatListItemsWith(currentProjectedItems(), accountIdHex, activeAccountIdHex)

    /**
     * The confirmed 1:1 DM with [reference] (npub or hex), or null. A 1:1 is a
     * confirmed two-member group whose counterparty is the target; the
     * counterparty is stored as hex, so compare in both hex and npub forms.
     * Shared with the new-chat sheet so "open existing DM" and "don't create a
     * duplicate DM" agree on what counts as an existing one.
     */
    fun existingDirectChat(reference: String): ChatListItem? =
        currentProjectedItems().firstOrNull { chat ->
            chat.memberCount == 2 &&
                !chat.group.pendingConfirmation &&
                chat.otherMemberAccount?.let { other ->
                    other.equals(reference, ignoreCase = true) ||
                        appState.npub(other).equals(reference, ignoreCase = true)
                } == true
        }

    fun chatItemForGroup(groupIdHex: String): ChatListItem? = currentProjectedItems().firstOrNull { it.group.groupIdHex.equals(groupIdHex, ignoreCase = true) }

    /**
     * Chats the active account can forward a message into, recent first.
     *
     * A forward fans a fresh send into each selected group (encrypted under
     * that group's own state — see [DarkMatterAppState.forwardText]), so the
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
            currentProjectedItems().filterNot { it.group.pendingConfirmation },
        )

    private fun foldChatRow(row: ChatListRowFfi) {
        chatRows =
            if (chatRows.any { it.groupIdHex == row.groupIdHex }) {
                chatRows.map { if (it.groupIdHex == row.groupIdHex) row else it }
            } else {
                chatRows + row
            }
        recompute()
    }

    private fun removeChatRow(groupIdHex: String) {
        chatRows = chatRows.filterNot { it.groupIdHex == groupIdHex }
        recompute()
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
        var beforeMessageId: String? = null
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
                                before = null,
                                beforeMessageId = beforeMessageId,
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
                )
            }
            // No eligible match in this page. Stop if the engine has no older
            // rows, or if the page was empty (defensive: nothing to page from).
            if (!page.hasMoreBefore || page.messages.isEmpty()) return null
            // Cursor to the oldest row in this page so the next query returns
            // strictly older needle hits. Use the minimum timelineAt (tie-broken
            // by id) rather than assuming a fixed array order.
            beforeMessageId =
                page.messages
                    .minWith(compareBy({ it.timelineAt }, { it.messageIdHex }))
                    .messageIdHex
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
    ): Boolean {
        val account = accountRef ?: return false
        return runCatching {
            val updated = appState.marmotIo { setGroupArchived(account, groupIdHex, archived) }
            appState.applyLocalGroupUpdate(updated)
            if (notify) {
                appState.present(if (archived) R.string.toast_chat_archived else R.string.toast_chat_restored)
            }
            true
        }.onFailure {
            if (it is CancellationException) throw it
            appState.present(R.string.toast_couldnt_update_chat, AppText.Plain(it.message ?: it.javaClass.simpleName))
        }.getOrDefault(false)
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
        return runCatching {
            val members = appState.marmotIo { groupMembers(account, groupIdHex) }
            val memberCount = members.size
            if (!GroupProjector.canLeaveGroup(group, activeAccountIdHex, memberCount)) {
                appState.present(
                    R.string.toast_make_another_admin_before_leaving,
                    R.string.toast_group_needs_admin,
                )
                return@runCatching false
            }
            if (GroupProjector.requiresSelfDemoteBeforeLeave(group, activeAccountIdHex, memberCount)) {
                appState.marmotIo { selfDemoteAdmin(account, groupIdHex) }
                demotedBeforeLeave = true
                // Mirror the conversation-controller path: drop the local
                // admin entry so the cached record matches the engine's
                // post-demote state even if the subsequent leaveGroup
                // fails (the user is no longer an admin regardless of
                // whether they end up leaving). Case-insensitive because
                // admin-list hex casing can drift from the active
                // account id, same as in [ConversationController.leaveGroup].
                if (activeAccountIdHex != null) {
                    groupRecordsById[groupIdHex]?.let { cached ->
                        val patched =
                            cached.copy(
                                admins =
                                    cached.admins.filterNot {
                                        it.equals(activeAccountIdHex, ignoreCase = true)
                                    },
                            )
                        groupRecordsById = groupRecordsById + (groupIdHex to patched)
                        recompute()
                    }
                }
            }
            appState.marmotIo { leaveGroup(account, groupIdHex) }
            appState.present(R.string.toast_left_chat)
            true
        }.onFailure {
            if (it is CancellationException) throw it
            val errorText = AppText.Plain(it.message ?: it.javaClass.simpleName)
            if (demotedBeforeLeave) {
                // User was demoted but we couldn't complete the leave.
                // Tell them so they know to ask another admin to restore
                // their role (or retry); the generic "couldn't leave"
                // toast misses that they're now mid-state.
                appState.present(R.string.toast_demoted_but_couldnt_leave, errorText)
            } else {
                appState.present(R.string.toast_couldnt_leave_chat, errorText)
            }
        }.getOrDefault(false)
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
        return runCatching {
            appState.marmotIo { markTimelineMessageRead(account, item.group.groupIdHex, lastId) }
            appState.dismissConversationNotifications(account, item.group.groupIdHex)
            true
        }.onFailure {
            if (it is CancellationException) throw it
            // Quiet for the user (marking read is an idempotent
            // affordance and surfacing a toast on every flake would be
            // noisy) — but still log the failure so the trace surfaces
            // in `adb logcat` when someone reports "mark read does
            // nothing". `take(8)` on the group id keeps the privacy
            // posture: no full ids in logs.
            Log.w(
                "DMChatsController",
                "markAllRead failed for group=${item.group.groupIdHex.take(8)}",
                it,
            )
        }.getOrDefault(false)
    }

    private fun requestGroupProfiles(group: AppGroupRecordFfi) {
        appState.requestProfiles(
            listOfNotNull(group.welcomerAccountIdHex) + group.admins,
        )
    }

    private fun requestChatRowProfiles(row: ChatListRowFfi) {
        row.lastMessage?.sender?.let(appState::requestProfile)
    }

    /**
     * Called by the shell when a conversation is foregrounded (`false`) or the
     * chat list is back on screen (`true`). The subscription stays alive either
     * way — returning shows the current list with no reload — only the
     * recompute is paused while hidden and flushed once on return. See #6.
     */
    fun setChatListVisible(visible: Boolean) {
        if (visible == chatListVisible) return
        chatListVisible = visible
        if (visible && pendingRecompute) recompute()
    }

    private fun recompute() {
        val unreadAccountRef = accountRef
        if (unreadAccountRef != null) {
            appState.updateAccountUnreadCount(unreadAccountRef, accountUnreadCount(chatRows))
        }
        // Hidden behind an open conversation: keep folding updates into the
        // backing maps (done by the caller) but defer the projection rebuild +
        // member/preview fan-out until the list returns, then run once. See #6.
        if (!chatListVisible) {
            pendingRecompute = true
            return
        }
        pendingRecompute = false
        val all = sortChatListItems(currentProjectedItems())
        items = all.filter { !it.group.archived }
        archivedItems = all.filter { it.group.archived }
        chatsDebug { "recompute visible=${items.size} archived=${archivedItems.size} total=${all.size}" }
        // For any group we don't yet have members cached for, fan out a
        // one-shot members fetch so unnamed titles and the profile sheet's
        // shared-groups list can resolve from local snapshots.
        schedulePendingMemberFetches()
        // Likewise, fan out off-main markdown parses for any preview text we
        // haven't tokenized yet; each completion folds back via recompute().
        schedulePendingPreviewParses()
    }

    /**
     * Walk the current chat rows and, for any group without cached members or
     * an in-flight fetch, kick off a `groupMembers` FFI call. On success the
     * cache updates and `recompute()` runs again so row titles and profile-sheet
     * shared-group intersections see the local member snapshot.
     */
    private fun schedulePendingMemberFetches() {
        val account = accountRef ?: return
        val epoch = bindEpoch
        val pending =
            chatRows
                .asSequence()
                .map { it.groupIdHex }
                .filterNot { memberCacheByGroup.containsKey(it) }
                .filterNot { it in inFlightMemberFetches }
                .toList()
        if (pending.isEmpty()) return
        inFlightMemberFetches.addAll(pending)
        pending.forEach { groupIdHex ->
            appState.launchMutation {
                try {
                    memberFetchGate.withPermit {
                        if (bindEpoch != epoch) return@withPermit
                        val members = appState.marmotIo { groupMembers(account, groupIdHex) }
                        if (bindEpoch == epoch) {
                            members
                                .map { it.memberIdHex }
                                .filter { it.isNotBlank() }
                                .forEach(appState::requestProfile)
                            memberCacheByGroup = memberCacheByGroup + (groupIdHex to members)
                            recompute()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Best-effort. Leave the cache empty so a future
                    // bind retries; the row falls back to the short
                    // hex projector branch until then.
                } finally {
                    // Only mutate the in-flight set if this job still
                    // belongs to the current bind. A later bind() has
                    // already cleared the set; removing again would be
                    // a no-op but obscures the invariant.
                    if (bindEpoch == epoch) inFlightMemberFetches.remove(groupIdHex)
                }
            }
        }
    }

    /**
     * Walk the current chat rows and, for any preview plaintext without
     * cached tokens or an in-flight parse, kick off the `parseMarkdown` FFI
     * call off-main. On completion the cache updates and `recompute()` runs
     * again so the row re-emits with its styled preview. List emission never
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
            appState.launchMutation {
                try {
                    val tokens = appState.parseMarkdownOrEmpty(text)
                    if (bindEpoch != epoch) return@launchMutation
                    previewTokensByText = previewTokensByText + (text to tokens)
                    recompute()
                } finally {
                    // Same epoch discipline as the member fetches: a later
                    // bind() already cleared the set, so only the owning
                    // epoch may mutate it.
                    if (bindEpoch == epoch) inFlightPreviewParses.remove(text)
                }
            }
        }
    }
}

/**
 * Parse [text] into the same Markdown AST the Rust core attaches to projected
 * records, for the state Android synthesizes locally (optimistic sends,
 * finished agent streams, chat-list previews). `parseMarkdown` is a blocking
 * FFI call, so it rides [DarkMatterAppState.marmotIo]'s `Dispatchers.IO` hop
 * instead of the main thread. Any failure degrades to an empty document —
 * empty docs render as plain text, so a parser error can never lose a
 * message body.
 */
internal suspend fun DarkMatterAppState.parseMarkdownOrEmpty(text: String): MarkdownDocumentFfi =
    try {
        marmotIo { parseMarkdown(text) }
    } catch (throwable: Throwable) {
        rethrowIfCancellation(throwable)
        MarkdownDocumentFfi(blocks = emptyList())
    }

private fun AppGroupRecordFfi.debugSummary(): String =
    "id=${groupIdHex.take(8)} archived=$archived pending=$pendingConfirmation " +
        "welcomer=${welcomerAccountIdHex?.take(8)} relays=${relays.size} name=${name.ifBlank { "<blank>" }}"

private fun ChatListRowFfi.debugSummary(): String =
    "id=${groupIdHex.take(8)} archived=$archived pending=$pendingConfirmation unread=$unreadCount " +
        "last=${lastMessage?.messageIdHex?.take(8)} title=${title.ifBlank { "<blank>" }}"

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
        // New triggers from the typed Hermes-agent / group-system surface.
        // None of these mutate reaction tallies, so they fall in the false
        // bucket — kept explicit so a future trigger that *does* change
        // reactions fails the exhaustiveness check rather than silently
        // missing a recompute.
        TimelineUpdateTriggerFfi.AGENT_ACTIVITY,
        TimelineUpdateTriggerFfi.AGENT_OPERATION,
        TimelineUpdateTriggerFfi.GROUP_SYSTEM,
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
    Log.e("DMChats", message(), error)
}

private val ConversationTimelinePageLimit = 50u

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

// Cap on how many subscription updates one coalesced batch can absorb. A
// runaway producer shouldn't be able to wedge the UI behind an unbounded
// drain loop; this keeps latency-to-first-paint bounded.
private const val TIMELINE_BATCH_CAP = 32

// Window to wait for additional subscription updates to coalesce into the
// current batch. 6ms is roughly the slack on a 120Hz frame budget (8.33ms)
// minus the apply+publish work, so we soak up updates that arrive within
// one frame without delaying the next paint.
private const val TIMELINE_BATCH_DRAIN_MS = 6L

class ConversationController(
    private val appState: DarkMatterAppState,
    initialGroup: AppGroupRecordFfi,
    initialMemberSnapshot: GroupMemberSnapshot? = null,
    private val copy: ConversationControllerCopy = ConversationControllerCopy(),
) {
    var group by mutableStateOf(initialGroup)
        private set
    var members by mutableStateOf<List<AppGroupMemberRecordFfi>>(initialMemberSnapshot?.members.orEmpty())
        private set

    // Use cached members immediately to avoid a blank composer gap while the
    // first refresh verifies the roster.
    var membersLoaded by mutableStateOf(initialMemberSnapshot?.members?.isNotEmpty() == true)
        private set

    // Typed media references keyed by `messageIdHex`. Populated from Rust's
    // `listMedia` FFI — the only place the receive-side `source_epoch` is
    // surfaced (TimelineMessageRecordFfi / AppMessageRecordFfi don't expose
    // it). Without this, `downloadMedia` would be called with the imeta-tag
    // parser's `sourceEpoch = 0` fallback and the Rust core would error
    // with `missing encrypted media secret for epoch 0`.
    var mediaReferences: Map<String, List<MediaAttachmentReferenceFfi>> by mutableStateOf(emptyMap())
        private set
    private var membersVerified by mutableStateOf(false)
    var timeline by mutableStateOf<List<TimelineMessage>>(emptyList())
        private set
    var reactions by mutableStateOf<Map<String, List<ReactionTally>>>(emptyMap())
        private set
    var deletedMessageIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var replyingTo by mutableStateOf<AppMessageRecordFfi?>(null)

    /** Per-target edit history for kind-1009 events, recomputed on every
     * timeline publish. The bubble reads `.latestText` and the "(edited · N)"
     * affordance reads `.count`. Null entry == message never edited. */
    var editsByTarget by mutableStateOf<Map<String, EditState>>(emptyMap())
        private set

    /** Set when the user has tapped Edit on a kind-9 they sent — the composer
     * banner reflects this and the next [send] routes through [editMessage]
     * instead of producing a new chat. Cleared on submit, cancel, or
     * navigation away. */
    var editingMessageId by mutableStateOf<String?>(null)
    var isLoading by mutableStateOf(false)
        private set
    var isLoadingOlder by mutableStateOf(false)
        private set
    var hasMoreBefore by mutableStateOf(false)
        private set

    // Single guard for archive/leave/member-management mutations so the UI can
    // disable buttons while one is in flight and prevent double-submits.
    var mutationInFlight by mutableStateOf(false)
        private set
    var lastMutationError by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

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

    private fun mutationError(throwable: Throwable): String = throwable.message ?: throwable.javaClass.simpleName

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

    private val conversationAccountRef = appState.activeAccountRef
    private val mediaUploadSessionEpoch = appState.mediaUploadSessionEpoch()
    private val messageById = linkedMapOf<String, AppMessageRecordFfi>()
    private val timelineRecords = linkedMapOf<String, TimelineMessageRecordFfi>()
    private val timelineItemsById = linkedMapOf<String, TimelineMessage>()
    private val timelineOrder = mutableListOf<String>()
    private val optimisticMessages = appState.optimisticMessages(conversationAccountRef, initialGroup.groupIdHex)
    private val projectedMessageIds = appState.projectedMessageIds(conversationAccountRef, initialGroup.groupIdHex)
    private val localTimelineOrderOverrides = appState.timelineOrderOverrides(conversationAccountRef, initialGroup.groupIdHex)
    private val localTimelineTimestampOverrides =
        appState.timelineTimestampOverrides(conversationAccountRef, initialGroup.groupIdHex)

    // Holding pen for media projection echoes that arrive while their
    // matching bridge is still mid-`sendMediaAttachments`. Shared via
    // AppState so that if the user navigates out of the chat between echo
    // and bridge insert, the OLD controller's `performMediaUpload` still
    // sees the stash that the NEW controller's subscription contributed
    // to (or vice-versa).
    private val pendingProjectionsAwaitingBridge =
        appState.pendingProjectionsAwaitingBridge(conversationAccountRef, initialGroup.groupIdHex)
    private val optimisticReactionChanges = linkedMapOf<String, OptimisticReactionChange>()
    private val inviteStreamScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Cached at start() so `loadOlderPage` / `loadNewerPage` can drive
    // `paginate_backwards` / `paginate_forwards` on the subscription. Per
    // the PR-#400 contract, the subscription owns the materialized window
    // (ordering, dedup, head-anchoring, cap, has_more_*); clients render
    // the returned page directly instead of merging against a hand-rolled
    // cursor.
    @Volatile
    private var timelineSubscription: TimelineMessagesSubscription? = null
    private val activeStreamIds = mutableSetOf<String>()

    // Bounded LRU set: tombstones are capped so an agent-heavy conversation
    // kept open for a long time can't grow memory or per-batch filter cost
    // without bound. See #200.
    private val removedStreamIds = BoundedStreamTombstones()
    private var hasLoadedOlderPages = false

    // Last message id we successfully marked as read on the Rust side.
    // Dedupes scroll-driven [markReadUpTo] calls so settling on the same row
    // doesn't issue redundant FFI hops.
    private var lastReadMessageId: String? = null

    val title: String
        get() = title()

    fun title(copy: dev.ipf.darkmatter.core.GroupTitleCopy = dev.ipf.darkmatter.core.GroupTitleCopy.Default): String {
        val me = appState.activeAccount?.accountIdHex
        val other = GroupProjector.otherMemberAccount(members, me)
        return GroupProjector.displayTitle(
            group = group,
            otherMemberAccount = other,
            memberCount = members.size,
            memberTitle = { appState.chatMemberTitle(it) },
            copy = copy,
        )
    }

    val inviteAccount: String?
        get() {
            val me = appState.activeAccount?.accountIdHex
            val other = GroupProjector.otherMemberAccount(members, me)
            return GroupProjector.inviteAccount(group, other)
        }

    // A nameless two-member conversation, classified the same way the chat list
    // and notifications do. The header title is already the counterparty's name,
    // so the "2 members" subtitle is redundant noise here.
    val isDm: Boolean
        get() = GroupProjector.isDm(members.size, group.name)

    val subtitle: String
        get() = subtitle(justYou = "Just you", oneMember = "1 member", membersFormat = "%1\$d members")

    fun subtitle(
        justYou: String,
        oneMember: String,
        membersFormat: String,
    ): String {
        val count = members.size
        return when (count) {
            0 -> justYou
            1 -> oneMember
            else -> String.format(membersFormat, count)
        }
    }

    val isSelfAdmin: Boolean
        get() = GroupProjector.isAdminRef(group, appState.activeAccount?.accountIdHex)

    val isSelfMember: Boolean
        get() = members.any { GroupProjector.isActiveAccountMember(it, appState.activeAccount?.accountIdHex) }

    val canSendMessages: Boolean
        get() = membersVerified && isSelfMember

    val canLeaveGroup: Boolean
        get() = GroupProjector.canLeaveGroup(group, appState.activeAccount?.accountIdHex, members.size)

    suspend fun start() {
        val account = conversationAccountRef ?: return
        isLoading = true
        error = null
        // Converge workers before the first timeline snapshot so MLS commits
        // and kind-1210 rows from peers are in the store (same rationale as
        // chat-list bind / iOS conversation open).
        appState.catchUpAccounts()
        try {
            runConversationSubscriptionLoop(account)
        } catch (cancel: CancellationException) {
            // Expected when the conversation screen leaves the composition.
            // Re-throw so cancellation propagates and we don't log it as an
            // error.
            throw cancel
        } catch (throwable: Throwable) {
            if (throwable.isUseAfterEviction()) {
                markActiveAccountRemovedFromMembers(account)
                isLoading = false
                error = null
                return
            }
            isLoading = false
            error = throwable.message ?: throwable.javaClass.simpleName
        } finally {
            cleanupConversationSubscriptions()
        }
    }

    /**
     * Retry loop for the timeline + group-state live subscriptions. Extracted
     * from [start] so R8 can compile the smaller suspend entrypoint (the
     * monolithic method hit an invalid stack-map-table bug in release builds).
     */
    private suspend fun runConversationSubscriptionLoop(account: String) {
        var retryDelayMs = LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS
        while (coroutineContext.isActive) {
            val (shouldExit, connected) = runConversationSubscriptionIteration(account)
            if (shouldExit) return
            if (connected) retryDelayMs = LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS
            if (!coroutineContext.isActive) break
            delay(retryDelayMs)
            retryDelayMs = nextLiveSubscriptionRetryDelayMillis(retryDelayMs)
        }
    }

    /**
     * One connect/reconnect attempt. Returns whether the caller should exit
     * entirely (account evicted) and whether the subscriptions connected
     * successfully (so the retry backoff can reset).
     */
    private suspend fun runConversationSubscriptionIteration(account: String): Pair<Boolean, Boolean> {
        var groupSubscription: GroupStateSubscription? = null
        var timelineStream: TimelineMessagesSubscription? = null
        try {
            timelineStream =
                appState.marmotIo {
                    subscribeTimelineMessages(account, group.groupIdHex, ConversationTimelinePageLimit)
                }
            timelineSubscription = timelineStream
            val timelineReconnect = timelineRecords.isNotEmpty()
            val snapshotStreamIds =
                if (timelineReconnect) {
                    emptyList()
                } else {
                    val snapshot = withContext(Dispatchers.IO) { timelineStream.snapshot() }
                    snapshot
                        ?.let {
                            val streamIds =
                                applyTimelinePage(
                                    it,
                                    replaceWindow = true,
                                    updatePagination = true,
                                )
                            refreshMediaReferences()
                            initializeReadState(account)
                            streamIds
                        }.orEmpty()
                }
            // Don't blanket-mark the absolute newest as read here — the UI
            // layer now drives mark-read as the user scrolls so partial-read
            // sessions retain accurate unread counts on the chat list.

            val groupStream =
                appState.marmotIo { subscribeGroupState(account, group.groupIdHex) }
            groupSubscription = groupStream
            val groupSnapshot =
                withContext(Dispatchers.IO) {
                    groupStream.snapshot()
                }
            groupSnapshot?.let { group = it }
            refreshMembers()
            isLoading = false
            error = null
            var connected = false

            coroutineScope {
                snapshotStreamIds.forEach { streamId ->
                    if (activeStreamIds.add(streamId)) {
                        launch { watchAgentTextStream(account, streamId) }
                    }
                }
                connected = true
                runUntilFirstLiveSubscriptionEnds(
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
                markActiveAccountRemovedFromMembers(account)
                isLoading = false
                error = null
                return true to false
            }
            if (timelineRecords.isEmpty()) {
                isLoading = false
                error = throwable.message ?: throwable.javaClass.simpleName
            }
        } finally {
            closeConversationSubscriptionHandles(groupSubscription, timelineStream)
        }
        return false to false
    }

    private suspend fun runGroupStateSubscriptionLoop(groupStream: GroupStateSubscription) {
        while (coroutineContext.isActive) {
            val update =
                withContext(Dispatchers.IO) {
                    groupStream.next()
                } ?: break
            group = update
            refreshMembers()
        }
    }

    private suspend fun closeConversationSubscriptionHandles(
        groupSubscription: GroupStateSubscription?,
        timelineStream: TimelineMessagesSubscription?,
    ) {
        if (timelineSubscription === timelineStream) {
            timelineSubscription = null
        }
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { groupSubscription?.close() }
            runCatching { timelineStream?.close() }
        }
    }

    private suspend fun cleanupConversationSubscriptions() {
        // Do NOT cancel inviteStreamScope here: it is not owned by a single
        // start() invocation (see onCleared / #279). start()'s subscription
        // loops can end while the screen is still composed, and acceptInvite()
        // — which launches into inviteStreamScope from an independent mutation
        // scope — may fire afterward.
        val closingSubscription = timelineSubscription
        timelineSubscription = null
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { closingSubscription?.close() }
        }
    }

    /**
     * Cancel controller-owned scopes that outlive a single [start] call. The
     * conversation screen calls this once when it disposes the controller.
     *
     * [inviteStreamScope]'s only launcher is [acceptInvite], invoked from a
     * separate mutation scope that can fire after [start]'s loops have ended
     * while the invite screen is still composed. Cancelling it in [start]'s
     * teardown left a dead scope, so the accepted invite's agent-stream watchers
     * never launched yet were marked active in [activeStreamIds] and never
     * retried — the streaming previews stayed stuck (#279).
     */
    fun onCleared() {
        inviteStreamScope.cancel()
    }

    private suspend fun CoroutineScope.runTimelineSubscriptionPipeline(
        account: String,
        timelineStream: TimelineMessagesSubscription,
    ) {
        val timelineUpdates = Channel<TimelineSubscriptionUpdateFfi>(capacity = Channel.BUFFERED)
        val pump =
            async {
                try {
                    while (isActive) {
                        val update =
                            withContext(Dispatchers.IO) {
                                timelineStream.nextUpdate()
                            } ?: break
                        timelineUpdates.send(update)
                    }
                } finally {
                    timelineUpdates.close()
                }
            }
        try {
            while (isActive) {
                val first = timelineUpdates.receiveCatching().getOrNull() ?: break
                // Drain any updates that arrived within roughly one
                // 120Hz frame budget into a single batch. The runtime
                // can emit several updates back-to-back during a
                // sync burst (e.g. on conversation open, a Page
                // followed by a flurry of Projections); applying
                // them one at a time triggers N expensive recompose
                // passes (sort + dedup + edits aggregate). Batching
                // collapses them into one publish at the end. The
                // timeout only wraps the local channel receive; the
                // UniFFI nextUpdate() call above is always awaited to
                // completion so a timed-out drain can't consume and
                // drop a Rust subscription update.
                val batch = mutableListOf(first)
                while (batch.size < TIMELINE_BATCH_CAP) {
                    val more =
                        timelineUpdates.tryReceive().getOrNull()
                            ?: withTimeoutOrNull(TIMELINE_BATCH_DRAIN_MS) {
                                timelineUpdates.receiveCatching().getOrNull()
                            } ?: break
                    batch += more
                }
                val streamIdsLaunched = mutableListOf<String>()
                coalesceTimelinePublishes {
                    for (update in batch) {
                        val streamIds =
                            when (update) {
                                is TimelineSubscriptionUpdateFfi.Page -> {
                                    val replaceWindow = !hasLoadedOlderPages
                                    applyTimelinePage(
                                        update.page,
                                        replaceWindow = replaceWindow,
                                        updatePagination = replaceWindow,
                                    )
                                }
                                is TimelineSubscriptionUpdateFfi.Projection -> {
                                    val projection = update.update.update
                                    if (projection.groupIdHex == group.groupIdHex) {
                                        applyChatListProjection(
                                            projection.chatListTrigger,
                                            projection.chatListRow,
                                        )
                                        applyTimelineChanges(projection.changes)
                                    } else {
                                        emptyList()
                                    }
                                }
                            }
                        streamIdsLaunched += streamIds
                    }
                }
                // Refresh media references at most once per batch.
                // `listMedia` is an unbounded scan — gate on whether
                // any update in the batch actually touched media so
                // text-only / reaction-only bursts don't pay for it.
                val touchedMedia =
                    batch.any { update ->
                        when (update) {
                            is TimelineSubscriptionUpdateFfi.Page -> pageContainsMedia(update.page)
                            is TimelineSubscriptionUpdateFfi.Projection ->
                                if (update.update.update.groupIdHex == group.groupIdHex) {
                                    changesContainMedia(update.update.update.changes)
                                } else {
                                    false
                                }
                        }
                    }
                if (touchedMedia) refreshMediaReferences()
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
     * Send a text message. [onAccepted] runs only once the optimistic bubble
     * has been committed to the projection and published — i.e. the send has
     * actually started. The caller uses it to clear the input/draft and scroll
     * to newest. It is deliberately NOT invoked when a guard rejects the send
     * (no account yet, blank text, or membership not yet verified) so the UI
     * keeps the user's text instead of clearing it into the void (issue #264).
     * The edit path also leaves [onAccepted] uncalled: the composer restores its
     * pre-edit draft via the `editingMessageId` LaunchedEffect, not by clearing.
     */
    suspend fun send(
        text: String,
        onAccepted: () -> Unit = {},
    ) {
        val trimmed = text.trim()
        val accountRef = conversationAccountRef
        if (!canAcceptTextSend(accountRef, trimmed, canSendMessages)) {
            // The only guard a user with non-blank text can realistically hit is
            // membership not yet verified (the composer is shown during the
            // `refreshMembers()` load window). Surface it instead of dropping
            // the message silently — the input is preserved by not calling
            // onAccepted, and the toast tells the user to retry in a moment.
            if (accountRef != null && trimmed.isNotEmpty()) {
                appState.present(R.string.toast_send_not_ready)
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
        val tempId = UUID.randomUUID().toString()
        val now = nowSeconds()
        val optimistic =
            AppMessageRecordFfi(
                messageIdHex = tempId,
                direction = "sent",
                groupIdHex = group.groupIdHex,
                sender = appState.activeAccount?.accountIdHex ?: "",
                plaintext = trimmed,
                // Parse locally so the optimistic bubble renders the same
                // markdown the projected record will carry once the send
                // round-trips — no plain→styled flash on confirm.
                contentTokens = appState.parseMarkdownOrEmpty(trimmed),
                kind = 9uL,
                tags =
                    replyTarget
                        ?.let {
                            listOf(MessageProjector.eventTag(it), MessageProjector.quoteTag(it))
                        }.orEmpty(),
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
            )
        messageById[tempId] = optimistic
        publishTimelineFromIndexes()
        replyingTo = null
        // The optimistic bubble is now in the projection and published — the
        // send has visibly started. Only now is it safe to clear the input and
        // draft (issue #264): clearing earlier, synchronously in the UI on the
        // mere act of dispatching this coroutine, lost the text whenever a
        // guard above bailed before this point.
        onAccepted()
        try {
            // Publish with a bounded retry sweep so a *transient* relay-pool gap
            // (socket teardown mid-reconnect, doze wake, network change) doesn't
            // surface as a user-visible "send failed" the instant the pool looks
            // empty (issue #294). A terminal/logic error fails on the first
            // attempt; only a sustained connectivity outage — every attempt
            // exhausted — keeps the hard failure. The optimistic bubble stays
            // Pending across retries, so the user sees "sending", not "failed".
            val summary =
                publishTextWithRetry(replyTarget, account, trimmed)
            val confirmedId = summary.messageIds.firstOrNull() ?: tempId
            val confirmed = optimistic.copy(messageIdHex = confirmedId)
            if (confirmedId.isNotEmpty()) messageById[confirmedId] = confirmed
            optimisticMessages.remove(optimisticKey)
            messageById.remove(tempId)
            if (shouldInsertSentOptimisticMessage(confirmedId, projectedMessageIds)) {
                optimisticMessages["msg:$confirmedId"] =
                    TimelineMessage(
                        "msg:$confirmedId",
                        confirmed,
                        MessageStatus.Sent,
                        timelineOrder = optimisticOrder,
                    )
            }
            publishTimelineFromIndexes()
        } catch (throwable: Throwable) {
            throwable.rethrowIfCancellation()
            retainFailedOptimisticTextSend(
                optimisticMessages = optimisticMessages,
                messageById = messageById,
                key = optimisticKey,
                optimistic = optimistic,
                timelineOrder = optimisticOrder,
            )
            publishTimelineFromIndexes()
            appState.present(R.string.toast_send_failed, AppText.Plain(throwable.message ?: throwable.javaClass.simpleName))
        }
    }

    /**
     * Publish a text/reply message, re-sending across [SEND_RETRY_ATTEMPTS] only
     * when the failure proves the event never reached a relay
     * ([isTransientRelaySendError] — connect-phase failures). Because each
     * attempt re-enters the high-level FFI send and the runtime builds a fresh
     * inner app event per call, retrying any ambiguous post-send failure could
     * duplicate a message; the classifier is narrowed to connect-phase reasons
     * precisely so this re-send is idempotent. Terminal errors and ambiguous
     * post-send failures rethrow immediately on the first attempt. Between
     * attempts it waits [SEND_RETRY_BACKOFF_MS] to give the relay pool time to
     * (re)connect, and logs the relay-health snapshot at the retry decision
     * point — aggregate connection counts only, no relay URLs/account/group/
     * message ids — so the intermittent failure window from #294 is diagnosable
     * from logcat without leaking PII.
     */
    private suspend fun publishTextWithRetry(
        replyTarget: String?,
        account: String,
        trimmed: String,
    ): dev.ipf.marmotkit.SendSummaryFfi {
        var lastTransient: Throwable? = null
        for (attempt in 1..SEND_RETRY_ATTEMPTS) {
            try {
                return if (replyTarget != null) {
                    appState.marmotIo { replyToMessage(account, group.groupIdHex, replyTarget, trimmed) }
                } else {
                    appState.marmotIo { sendText(account, group.groupIdHex, trimmed) }
                }
            } catch (throwable: Throwable) {
                throwable.rethrowIfCancellation()
                if (!isTransientRelaySendError(throwable)) throw throwable
                lastTransient = throwable
                logSendRetry(attempt, throwable)
                if (attempt < SEND_RETRY_ATTEMPTS) {
                    kotlinx.coroutines.delay(SEND_RETRY_BACKOFF_MS)
                }
            }
        }
        // Budget exhausted on a sustained connectivity gap: surface the failure.
        throw lastTransient ?: IllegalStateException("send retry budget exhausted")
    }

    /**
     * Trace a transient send retry with the current relay-health snapshot.
     * Aggregate connection-state counts only (no relay URLs, account/group/
     * message ids, or payload) so the #294 failure window is observable without
     * violating the repo's privacy posture. Best-effort: a failure to read
     * health must never escalate a send retry into an error.
     */
    private suspend fun logSendRetry(
        attempt: Int,
        throwable: Throwable,
    ) {
        if (!BuildConfig.DEBUG) return
        val health = runCatching { appState.marmotIo { relayHealth() } }.getOrNull()
        val healthSummary =
            health?.let {
                "total=${it.totalRelays} connected=${it.connected} connecting=${it.connecting} " +
                    "pending=${it.pending} disconnected=${it.disconnected} terminated=${it.terminated}"
            } ?: "unavailable"
        Log.d(
            "ConversationController",
            "transient send failure (attempt $attempt/$SEND_RETRY_ATTEMPTS): " +
                "${throwable.javaClass.simpleName} relayHealth[$healthSummary]",
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
        val trimmedCaption = caption?.trim()?.takeIf { it.isNotBlank() }
        val placeholderName =
            if (attachments.size == 1) {
                attachments.first().fileName
            } else {
                "${attachments.size} attachments"
            }
        val body = trimmedCaption ?: "📎 $placeholderName"
        val optimistic =
            AppMessageRecordFfi(
                messageIdHex = tempId,
                direction = "sent",
                groupIdHex = group.groupIdHex,
                sender = appState.activeAccount?.accountIdHex ?: "",
                plaintext = body,
                contentTokens = appState.parseMarkdownOrEmpty(body),
                kind = 9uL,
                tags =
                    attachments.map {
                        MessageTagFfi(listOf("_media_pending", it.fileName, it.mediaType))
                    },
                recordedAt = now,
                receivedAt = now,
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
            )
        messageById[tempId] = optimistic
        publishTimelineFromIndexes()
        return QueuedAttachmentSend(account, key, tempId, optimisticOrder, optimistic)
    }

    /** Drive the upload + publish for a previously [queueAttachments]-seeded slot. */
    suspend fun uploadQueued(seeded: QueuedAttachmentSend) {
        // `activeUploadKeys` was added at `queueAttachments` time so that
        // EVERY seeded slot — even the ones still waiting for an earlier
        // upload to finish — survives a dispose-time
        // `clearRetainedUploads`. Removal happens at performMediaUpload's
        // terminal paths.
        performMediaUpload(seeded.account, seeded.key, seeded.tempId, seeded.optimisticOrder, seeded.optimistic)
    }

    /**
     * Shared upload→publish path for both first send and retry. Reads the
     * compressed bytes from [retainedMediaUploads] (keyed by [key]) so it
     * survives a Failed→retry round-trip. On success: drops the optimistic +
     * retained entry, then seeds [mediaPlaintextCache] with the just-uploaded
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
                messageById.remove(tempId)
                retainedMediaUploads.remove(key)
                activeUploadKeys.remove(key)
                publishTimelineFromIndexes()
                return
            }
            val retained =
                retainedMediaUploads.get(key) ?: run {
                    // Bytes are gone (evicted under cap, or process death) — can't
                    // retry without a re-attach. Leave the bubble Failed and drop
                    // the in-flight marker so a future dispose can clean up.
                    optimisticMessages[key] = TimelineMessage(key, optimistic, MessageStatus.Failed, timelineOrder = order)
                    activeUploadKeys.remove(key)
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
                    retained.uploadedReferences ?: appState
                        .marmotIo {
                            uploadMedia(
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
                            ).attachments.map { it.reference }.also { uploaded ->
                                if (uploaded.size != retained.attachments.size) {
                                    error(
                                        "media upload returned ${uploaded.size} references " +
                                            "for ${retained.attachments.size} attachments",
                                    )
                                }
                            }
                        }.also { retained.uploadedReferences = it }
                // Discard window #1: blobs uploaded but not yet published. If the
                // user discarded here, bail BEFORE sendMediaAttachments so we don't
                // publish a kind-9 they cancelled (unlike a published event, an
                // unreferenced Blossom blob is inert).
                if (discardedDuringRetry.remove(key)) {
                    optimisticMessages.remove(key)
                    messageById.remove(tempId)
                    retainedMediaUploads.remove(key)
                    activeUploadKeys.remove(key)
                    publishTimelineFromIndexes()
                    return
                }
                val summary =
                    appState.marmotIo {
                        sendMediaAttachments(account, group.groupIdHex, references, retained.caption)
                    }
                val confirmedId = summary.messageIds.firstOrNull() ?: tempId
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
                if (confirmedId.isNotEmpty()) {
                    retained.attachments.forEachIndexed { index, attachment ->
                        val confirmedKey = mediaCacheKey(account, confirmedId, index)
                        appState.mediaPlaintextCache.put(confirmedKey, attachment.plaintextBytes)
                        MediaPipeline
                            .decodeSampledBitmap(attachment.plaintextBytes, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
                            ?.let { appState.mediaThumbnailCache.put(confirmedKey, it) }
                        val bytesToPersist = attachment.plaintextBytes
                        val cacheGeneration = appState.diskMediaCache.generation()
                        appState.launchMutation {
                            withContext(Dispatchers.IO) { appState.diskMediaCache.put(confirmedKey, bytesToPersist, cacheGeneration) }
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
                            tags = references.map { MediaReferenceParser.toImetaTag(it) },
                        )
                    messageById[confirmedId] = confirmedRecord
                    optimisticMessages["msg:$confirmedId"] =
                        TimelineMessage(
                            "msg:$confirmedId",
                            confirmedRecord,
                            MessageStatus.Sent,
                            timelineOrder = order,
                        )
                    // Stamp the position overrides up front so the projection's
                    // timeline position matches the optimistic order. Drain any
                    // projection echo that arrived while this send was still
                    // mid-`sendMediaAttachments` — at that point the heuristic
                    // refused the match and the projection was stashed in
                    // `pendingProjectionsAwaitingBridge` to avoid a position-0
                    // render flip. Now that the overrides are stamped, the
                    // build below will produce a TimelineMessage at the right
                    // position.
                    localTimelineOrderOverrides[confirmedId] = order
                    localTimelineTimestampOverrides[confirmedId] = optimistic.recordedAt
                    val deferredProjection = pendingProjectionsAwaitingBridge.remove(confirmedId)
                    if (deferredProjection != null) {
                        timelineRecords[confirmedId] = deferredProjection
                        projectedMessageIds.add(confirmedId)
                        val deferredAction = TimelineProjector.toAppMessageRecord(deferredProjection)
                        messageById[confirmedId] = deferredAction
                        val deferredItem = timelineMessageFromProjection(deferredProjection, deferredAction)
                        timelineItemsById[deferredItem.id] = deferredItem
                        insertTimelineItemId(deferredItem.id)
                    } else {
                        // Race-winner / replay path: if the projected timeline
                        // item is already in `timelineItemsById` (built before
                        // the override existed, e.g., a duplicate-emit), rebuild
                        // it now with the override applied.
                        val existingProjected = timelineRecords[confirmedId]
                        if (existingProjected != null) {
                            val itemId = projectedItemId(existingProjected)
                            if (timelineItemsById.containsKey(itemId)) {
                                timelineItemsById[itemId] = timelineMessageFromProjection(existingProjected)
                            }
                        }
                    }
                }
                publishTimelineFromIndexes()
            } catch (throwable: Throwable) {
                // Coroutine cancellation (e.g. leaving the screen) is not a send
                // failure — rethrow so it isn't surfaced as a Failed bubble/toast.
                if (throwable is CancellationException) throw throwable
                if (discardedDuringRetry.remove(key)) {
                    optimisticMessages.remove(key)
                    messageById.remove(tempId)
                    retainedMediaUploads.remove(key)
                    activeUploadKeys.remove(key)
                    publishTimelineFromIndexes()
                    return
                }
                optimisticMessages[key] =
                    TimelineMessage(
                        key,
                        optimistic,
                        MessageStatus.Failed,
                        timelineOrder = order,
                    )
                // Failed bubble shown but bytes are retained for a possible
                // retry — KEEP the key in `activeUploadKeys` so a screen
                // dispose can't wipe the bytes out from under a retry tap.
                // The key drains when the user retries (terminal performMediaUpload
                // path runs) or explicitly discards.
                publishTimelineFromIndexes()
                Log.w("DMConversation", "media upload failed for ${group.groupIdHex.take(8)}", throwable)
                appState.present(R.string.toast_send_failed, AppText.Plain(throwable.message ?: throwable.javaClass.simpleName))
            }
        } finally {
            appState.untrackInFlightMediaUpload(conversationAccountRef, group.groupIdHex, key, uploadJob)
        }
    }

    suspend fun toggleReaction(
        emoji: String,
        message: AppMessageRecordFfi,
    ) {
        val account = appState.activeAccountRef ?: return
        if (!canSendMessages) return
        val target = message.messageIdHex.takeIf { it.isNotBlank() } ?: return
        val alreadyMine = reactions[target]?.any { it.emoji == emoji && it.mine } == true
        val optimisticId = UUID.randomUUID().toString()
        optimisticReactionChanges[optimisticId] =
            OptimisticReactionChange(
                targetMessageId = target,
                emoji = emoji,
                add = !alreadyMine,
            )
        recomputeReactions()
        try {
            if (alreadyMine) {
                // Retract just the tapped emoji by deleting its own reaction
                // event; the FFI unreact is target-only and clears the latest
                // reaction, so it would drop the wrong emoji when a user holds
                // more than one on the same message.
                // activeAccountRef can be set while the account row hasn't loaded
                // into `accounts` yet; surface that distinctly from an ambiguous
                // reaction so the failure reason isn't misleading.
                val me =
                    appState.activeAccount?.accountIdHex
                        ?: error("no active account to retract reaction")
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
                    // Target-only unreact is safe only when this is the single
                    // reaction to clear; with several it could drop another emoji.
                    ownReactions.size == 1 && ownReactions.first().emoji == emoji ->
                        appState.marmotIo { unreactFromMessage(account, group.groupIdHex, target) }
                    // No event id yet (optimistic/unsynced) and ambiguous — fail so
                    // the optimistic toggle reverts instead of retracting the wrong one.
                    else -> error("no reaction event to retract for $emoji")
                }
            } else {
                appState.marmotIo { reactToMessage(account, group.groupIdHex, target, emoji) }
            }
        } catch (throwable: Throwable) {
            throwable.rethrowIfCancellation()
            optimisticReactionChanges.remove(optimisticId)
            recomputeReactions()
            appState.present(R.string.toast_reaction_failed, AppText.Plain(throwable.message ?: throwable.javaClass.simpleName))
        }
    }

    suspend fun deleteMessage(message: AppMessageRecordFfi) {
        val account = appState.activeAccountRef ?: return
        if (!canSendMessages) return
        val target = message.messageIdHex.takeIf { it.isNotBlank() } ?: return
        deletedMessageIds = deletedMessageIds + target
        try {
            appState.marmotIo { deleteMessage(account, group.groupIdHex, target) }
        } catch (throwable: Throwable) {
            throwable.rethrowIfCancellation()
            deletedMessageIds = deletedMessageIds - target
            appState.present(R.string.toast_couldnt_delete_message, AppText.Plain(throwable.message ?: throwable.javaClass.simpleName))
        }
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
        val account = appState.activeAccountRef ?: return
        if (!canSendMessages) return
        val target = targetMessageId.takeIf { it.isNotBlank() } ?: return
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        try {
            appState.marmotIo { editMessage(account, group.groupIdHex, target, trimmed) }
        } catch (throwable: Throwable) {
            throwable.rethrowIfCancellation()
            appState.present(R.string.toast_couldnt_edit_message, AppText.Plain(throwable.message ?: throwable.javaClass.simpleName))
        }
    }

    /**
     * Latest text to display for a kind-9 chat row: the most-recent
     * kind-1009 edit's content when one exists, otherwise the original
     * plaintext. Bubble + reply preview both read through this so an edit
     * shows everywhere the original would have.
     */
    fun displayedText(record: AppMessageRecordFfi): String = editsByTarget[record.messageIdHex]?.latestText ?: record.plaintext

    // Tracks optimistic ids the user discarded while a retry was in flight.
    // The retry coroutine consults this set before re-inserting a failed
    // record, so a discard during retry doesn't get clobbered by the catch
    // path putting the message back as Failed.
    private val discardedDuringRetry = mutableSetOf<String>()

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
    ): String = "$account|${group.groupIdHex}|$messageIdHex|$attachmentIndex"

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
        if (appState.mediaPlaintextCache.get(cacheKey) != null) return true
        return appState.diskMediaCache.contains(cacheKey)
    }

    /**
     * Fetch and decrypt a Blossom-stored attachment. Backed by the app-level
     * LRU ([DarkMatterAppState.mediaPlaintextCache], keyed via [mediaCacheKey])
     * so re-opening a conversation doesn't re-download media already fetched
     * this session. Throws on download/decrypt failure — the caller surfaces it.
     */
    suspend fun downloadAttachment(
        messageIdHex: String,
        attachmentIndex: Int,
        reference: MediaAttachmentReferenceFfi,
    ): ByteArray {
        // Resolve the account first so the cache key is never unanchored
        // ("|group|msg|idx"), which a later sign-in could collide with.
        val account = conversationAccountRef ?: error("no active account")
        val cacheKey = mediaCacheKey(account, messageIdHex, attachmentIndex)
        // L1: in-memory LRU (hottest cache, instant return).
        appState.mediaPlaintextCache.get(cacheKey)?.let { return it }
        // L2: disk LRU (survives process restart). Read off the main thread
        // since file I/O on big JPEGs can take 5-30ms.
        val onDisk = withContext(Dispatchers.IO) { appState.diskMediaCache.get(cacheKey) }
        if (onDisk != null) {
            appState.mediaPlaintextCache.put(cacheKey, onDisk)
            return onDisk
        }
        // The actual Blossom fetch runs on `mutationsScope` so it continues
        // after the caller is cancelled (e.g. the user tapped a file and
        // swiped the app away). Memoized so a re-tap or sibling tile that
        // hits the same key shares the same Deferred — no double-fetch.
        val groupIdHex = group.groupIdHex
        val deferred =
            appState.memoizedDownload(cacheKey) {
                // Capture before the fetch so a sign-out wipe mid-download
                // rejects the L2 persist below. See #154.
                val cacheGeneration = appState.diskMediaCache.generation()
                val result =
                    runCatching {
                        appState.marmotIo { downloadMedia(account, groupIdHex, reference) }
                    }.onFailure {
                        if (it is CancellationException) throw it
                        // Strip query/path tail so any signed tokens or
                        // capabilities in the locator don't end up in logs.
                        val host =
                            reference.locators
                                .firstOrNull()
                                ?.value
                                ?.let { url -> url.substringAfter("://", "").substringBefore('/') }
                                .orEmpty()
                        Log.w(
                            "DMConversation",
                            "downloadAttachment failed for ${groupIdHex.take(8)} message=${messageIdHex.take(8)} host=$host",
                            it,
                        )
                    }.getOrThrow()
                // Never cache empty plaintext — a zero-byte result would
                // render as a permanent broken image and short-circuit
                // tap-to-retry.
                if (result.plaintext.isNotEmpty()) {
                    appState.mediaPlaintextCache.put(cacheKey, result.plaintext)
                    val plaintext = result.plaintext
                    // Persist to L2 still on this background scope (same
                    // lifetime as the FFI fetch).
                    withContext(Dispatchers.IO) { appState.diskMediaCache.put(cacheKey, plaintext, cacheGeneration) }
                }
                result.plaintext
            }
        return deferred.await()
    }

    /** Decoded thumbnail for [messageIdHex] if one is cached (renders with no
     *  spinner). Null when unanchored or not yet decoded. */
    fun thumbnailFor(
        messageIdHex: String,
        attachmentIndex: Int,
    ): android.graphics.Bitmap? {
        val account = conversationAccountRef ?: return null
        return appState.mediaThumbnailCache.get(mediaCacheKey(account, messageIdHex, attachmentIndex))
    }

    /** Cache a decoded thumbnail so re-renders / re-entry skip the decode. */
    fun cacheThumbnail(
        messageIdHex: String,
        attachmentIndex: Int,
        bitmap: android.graphics.Bitmap,
    ) {
        val account = conversationAccountRef ?: return
        appState.mediaThumbnailCache.put(mediaCacheKey(account, messageIdHex, attachmentIndex), bitmap)
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
        val account = conversationAccountRef ?: return
        // Seed every attachment under its own (messageId, attachmentIndex)
        // key so the projected album bubble's per-tile cache lookups all
        // hit immediately on reconcile.
        retained.attachments.forEachIndexed { index, attachment ->
            val cacheKey = mediaCacheKey(account, projectedMessageIdHex, index)
            appState.mediaPlaintextCache.put(cacheKey, attachment.plaintextBytes)
            MediaPipeline
                .decodeSampledBitmap(attachment.plaintextBytes, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
                ?.let { appState.mediaThumbnailCache.put(cacheKey, it) }
            val bytesToPersist = attachment.plaintextBytes
            val cacheGeneration = appState.diskMediaCache.generation()
            appState.launchMutation {
                withContext(Dispatchers.IO) { appState.diskMediaCache.put(cacheKey, bytesToPersist, cacheGeneration) }
            }
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
                TimelineMessage(
                    key,
                    current.record,
                    MessageStatus.Pending,
                    timelineOrder = mediaOrder,
                )
            // Re-mark this slot as "in flight" — if the previous attempt's
            // Failed branch had drained the bytes via a dispose-time clear,
            // this would still no-op (performMediaUpload bails on missing
            // bytes), but normally the bytes are retained for retry.
            activeUploadKeys.add(key)
            appState.trackInFlightMediaUpload(conversationAccountRef, group.groupIdHex, key)
            publishTimelineFromIndexes()
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
            TimelineMessage(
                key,
                refreshedRecord,
                MessageStatus.Pending,
                timelineOrder = order,
            )
        messageById[tempId] = refreshedRecord
        publishTimelineFromIndexes()
        try {
            val summary =
                if (replyTarget != null) {
                    appState.marmotIo { replyToMessage(account, group.groupIdHex, replyTarget, text) }
                } else {
                    appState.marmotIo { sendText(account, group.groupIdHex, text) }
                }
            val confirmedId = summary.messageIds.firstOrNull() ?: tempId
            val confirmed = refreshedRecord.copy(messageIdHex = confirmedId)
            optimisticMessages.remove(key)
            messageById.remove(tempId)
            if (discardedDuringRetry.remove(key)) {
                // User discarded mid-flight; drop the result entirely.
                publishTimelineFromIndexes()
                return
            }
            if (confirmedId.isNotEmpty()) messageById[confirmedId] = confirmed
            if (shouldInsertSentOptimisticMessage(confirmedId, projectedMessageIds)) {
                optimisticMessages["msg:$confirmedId"] =
                    TimelineMessage(
                        "msg:$confirmedId",
                        confirmed,
                        MessageStatus.Sent,
                        timelineOrder = order,
                    )
            }
            publishTimelineFromIndexes()
        } catch (throwable: Throwable) {
            throwable.rethrowIfCancellation()
            Log.w(
                "DMConversation",
                "retryFailedSend failed for ${group.groupIdHex.take(8)} key=${key.take(8)}",
                throwable,
            )
            if (discardedDuringRetry.remove(key)) {
                // User discarded mid-flight; don't restore the Failed bubble.
                optimisticMessages.remove(key)
                messageById.remove(tempId)
                publishTimelineFromIndexes()
                return
            }
            optimisticMessages[key] =
                TimelineMessage(
                    key,
                    refreshedRecord,
                    MessageStatus.Failed,
                    timelineOrder = order,
                )
            publishTimelineFromIndexes()
            appState.present(R.string.toast_send_failed, AppText.Plain(throwable.message ?: throwable.javaClass.simpleName))
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
            MessageStatus.Failed -> Unit
            MessageStatus.Pending -> discardedDuringRetry.add(key)
            else -> return
        }
        optimisticMessages.remove(key)
        messageById.remove(tempId)
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
            val account = appState.activeAccountRef ?: return false
            if (!canLeaveGroup) {
                appState.present(R.string.toast_make_another_admin_before_leaving, R.string.toast_group_needs_admin)
                return false
            }
            runCatching {
                val activeAccountIdHex = appState.activeAccount?.accountIdHex
                if (GroupProjector.requiresSelfDemoteBeforeLeave(group, activeAccountIdHex, members.size)) {
                    appState.marmotIo { selfDemoteAdmin(account, group.groupIdHex) }
                    // Case-insensitive so hex-casing drift between the admin
                    // list and the active account id doesn't leave the UI
                    // showing you as admin after a successful self-demote.
                    group =
                        group.copy(
                            admins = group.admins.filterNot { it.equals(activeAccountIdHex, ignoreCase = true) },
                        )
                }
                appState.marmotIo { leaveGroup(account, group.groupIdHex) }
                val name = displayName?.takeIf { it.isNotBlank() }
                if (name != null) {
                    appState.presentText(AppText.Resource(R.string.toast_left_named, listOf(name)))
                } else {
                    appState.present(R.string.toast_left_chat)
                }
                true
            }.getOrElse {
                if (it is CancellationException) throw it
                val message = mutationError(it)
                lastMutationError = message
                appState.present(R.string.toast_couldnt_leave_chat, AppText.Plain(message))
                false
            }
        }

    suspend fun acceptInvite(): Boolean {
        val account = appState.activeAccountRef ?: return false
        return runCatching {
            group = appState.marmotIo { acceptGroupInvite(account, group.groupIdHex) }
            appState.dismissConversationNotifications(account, group.groupIdHex)
            refreshMembers()
            refreshCurrentTimeline(account).forEach { streamId ->
                if (activeStreamIds.add(streamId)) {
                    inviteStreamScope.launch { watchAgentTextStream(account, streamId) }
                }
            }
            initializeReadState(account)
            appState.present(R.string.toast_invite_accepted)
            true
        }.getOrElse {
            it.rethrowIfCancellation()
            appState.present(R.string.toast_couldnt_accept_invite, AppText.Plain(it.message ?: it.javaClass.simpleName))
            false
        }
    }

    suspend fun declineInvite(): Boolean {
        val account = appState.activeAccountRef ?: return false
        return runCatching {
            appState.marmotIo { declineGroupInvite(account, group.groupIdHex) }
            appState.dismissConversationNotifications(account, group.groupIdHex)
            group = group.copy(pendingConfirmation = false, archived = true)
            appState.applyLocalGroupUpdate(group)
            appState.present(R.string.toast_invite_declined)
            true
        }.getOrElse {
            it.rethrowIfCancellation()
            appState.present(R.string.toast_couldnt_decline_invite, AppText.Plain(it.message ?: it.javaClass.simpleName))
            false
        }
    }

    suspend fun setArchived(archived: Boolean): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = appState.activeAccountRef ?: return@withMutationLockResult false
            runCatching {
                val updated = appState.marmotIo { setGroupArchived(account, group.groupIdHex, archived) }
                group = updated
                appState.applyLocalGroupUpdate(updated)
                appState.present(if (archived) R.string.toast_chat_archived else R.string.toast_chat_restored)
                true
            }.onFailure {
                it.rethrowIfCancellation()
                val message = mutationError(it)
                lastMutationError = message
                appState.present(R.string.toast_couldnt_update_chat, AppText.Plain(message))
            }.getOrDefault(false)
        }

    suspend fun updateGroupProfile(
        name: String,
        description: String,
    ): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = appState.activeAccountRef ?: return@withMutationLockResult false
            runCatching {
                appState.marmotIo {
                    updateGroupProfile(
                        account,
                        group.groupIdHex,
                        name.trim().takeIf { it.isNotEmpty() },
                        description.trim().takeIf { it.isNotEmpty() },
                    )
                }
                appState.present(R.string.toast_group_updated)
                true
            }.onFailure {
                it.rethrowIfCancellation()
                val message = mutationError(it)
                lastMutationError = message
                appState.present(R.string.toast_couldnt_update_group, AppText.Plain(message))
            }.getOrDefault(false)
        }

    suspend fun updateGroupAvatarUrl(url: String?): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = appState.activeAccountRef ?: return@withMutationLockResult false
            // The Rust side validates + normalizes the URL (https-only, no
            // private hosts). We only set the URL here; dim/thumbhash are
            // optimization hints we don't compute on Android, so clear them.
            val normalized = url?.trim()?.takeIf { it.isNotEmpty() }
            runCatching {
                appState.marmotIo {
                    updateGroupAvatarUrl(account, group.groupIdHex, normalized, null, null)
                }
                // Reflect the change locally so the avatar updates immediately,
                // without waiting for the group-state subscription to converge.
                group = group.copy(avatarUrl = normalized, avatarDim = null, avatarThumbhash = null)
                appState.present(R.string.toast_group_updated)
                true
            }.onFailure {
                it.rethrowIfCancellation()
                val message = mutationError(it)
                lastMutationError = message
                appState.present(R.string.toast_couldnt_update_group, AppText.Plain(message))
            }.getOrDefault(false)
        }

    suspend fun inviteMembers(
        memberRefs: List<String>,
        addAsAdmin: Boolean = false,
    ): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = appState.activeAccountRef ?: return@withMutationLockResult false
            val refs = memberRefs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (refs.isEmpty()) return@withMutationLockResult false
            val adminTargets =
                if (addAsAdmin) {
                    refs.map { ref ->
                        appState.marmotIo { accountIdHex(ref) }
                            ?: throw IllegalArgumentException("Invalid member reference")
                    }
                } else {
                    emptyList()
                }
            var inviteSent = false
            try {
                appState.marmotIo { inviteMembers(account, group.groupIdHex, refs) }
                inviteSent = true
                adminTargets.forEach { target ->
                    appState.marmotIo { promoteAdmin(account, group.groupIdHex, target) }
                }
                refreshMembers()
                appState.present(R.string.toast_invite_sent)
                true
            } catch (throwable: Throwable) {
                throwable.rethrowIfCancellation()
                val message = mutationError(throwable)
                if (inviteSent && addAsAdmin) {
                    // The invite is already out; keep the UI honest about the
                    // partial success and leave the row-level Admin switch as the
                    // retry path once the member appears in details.
                    refreshMembers()
                    lastMutationError = "Invite sent, but admin promotion failed: $message"
                    appState.present(R.string.toast_invite_sent_but_couldnt_add_admin, AppText.Plain(message))
                    true
                } else {
                    lastMutationError = message
                    appState.present(R.string.toast_couldnt_add_members, AppText.Plain(message))
                    false
                }
            }
        }

    suspend fun removeMember(member: AppGroupMemberRecordFfi): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = appState.activeAccountRef ?: return@withMutationLockResult false
            // remove_members signs a roster update for a Nostr pubkey, so use the
            // stable member id. memberRef may be a local account label.
            val target = member.memberIdHex
            runCatching {
                appState.marmotIo { removeMembers(account, group.groupIdHex, listOf(target)) }
                // The group-state subscription should eventually converge from the
                // published MLS commit. Update this controller immediately so the
                // admin does not need an account switch to see the removed row leave.
                removeMemberLocally(account, target)
                appState.present(R.string.toast_member_removed)
                true
            }.onFailure {
                it.rethrowIfCancellation()
                val message = mutationError(it)
                lastMutationError = message
                appState.present(R.string.toast_couldnt_remove_member, AppText.Plain(message))
            }.getOrDefault(false)
        }

    suspend fun setMemberAdmin(
        member: AppGroupMemberRecordFfi,
        admin: Boolean,
    ): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = appState.activeAccountRef ?: return@withMutationLockResult false
            // promote_admin / demote_admin sign the new admin list onto the MLS
            // group, so they require a Nostr pubkey hex — not a local-account
            // label. memberRef can return either; memberIdHex is always the hex.
            val target = member.memberIdHex
            if (!admin && isAdmin(member) && group.admins.distinctBy { it.lowercase() }.size <= 1) {
                appState.present(R.string.toast_keep_one_admin, R.string.toast_promote_before_removing_admin)
                return@withMutationLockResult false
            }
            runCatching {
                if (admin) {
                    appState.marmotIo { promoteAdmin(account, group.groupIdHex, target) }
                    val updatedAdmins = group.admins.toMutableList()
                    if (updatedAdmins.none { it.equals(target, ignoreCase = true) }) {
                        updatedAdmins += target
                    }
                    group = group.copy(admins = updatedAdmins)
                    appState.present(R.string.toast_admin_added)
                } else {
                    appState.marmotIo { demoteAdmin(account, group.groupIdHex, target) }
                    // Case-insensitive so admin hex casing variations don't keep
                    // the local UI showing the member as admin until next refresh.
                    group =
                        group.copy(
                            admins = group.admins.filterNot { it.equals(target, ignoreCase = true) },
                        )
                    appState.present(R.string.toast_admin_removed)
                }
                refreshMembers()
                true
            }.onFailure {
                it.rethrowIfCancellation()
                val message = mutationError(it)
                lastMutationError = message
                appState.present(R.string.toast_couldnt_update_admin, AppText.Plain(message))
            }.getOrDefault(false)
        }

    suspend fun stepDownAsAdmin(): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = appState.activeAccountRef ?: return@withMutationLockResult false
            val activeAccountIdHex = appState.activeAccount?.accountIdHex ?: return@withMutationLockResult false
            if (!GroupProjector.isAdminRef(group, activeAccountIdHex)) return@withMutationLockResult false
            if (group.admins.distinctBy { it.lowercase() }.size <= 1) {
                appState.present(R.string.toast_keep_one_admin, R.string.toast_promote_before_removing_admin)
                return@withMutationLockResult false
            }
            runCatching {
                appState.marmotIo { selfDemoteAdmin(account, group.groupIdHex) }
                // Case-insensitive so hex-casing drift between the admin list
                // and the active account id doesn't leave the row switch on
                // after a successful self-demote.
                group =
                    group.copy(
                        admins = group.admins.filterNot { it.equals(activeAccountIdHex, ignoreCase = true) },
                    )
                refreshMembers()
                appState.present(R.string.toast_admin_removed)
                true
            }.onFailure {
                it.rethrowIfCancellation()
                val message = mutationError(it)
                lastMutationError = message
                appState.present(R.string.toast_couldnt_update_admin, AppText.Plain(message))
            }.getOrDefault(false)
        }

    fun isAdmin(member: AppGroupMemberRecordFfi): Boolean = GroupProjector.isAdmin(group, member)

    fun memberDisplayName(member: AppGroupMemberRecordFfi): String = appState.displayName(member.memberIdHex)

    fun memberSubtitle(member: AppGroupMemberRecordFfi): String = appState.shortNpub(member.memberIdHex)

    fun memberAvatarUrl(member: AppGroupMemberRecordFfi): String? = appState.avatarUrl(member.memberIdHex)

    suspend fun groupMlsState(): AppGroupMlsStateFfi? {
        val account = appState.activeAccountRef ?: return null
        return runCatching {
            appState.marmotIo { groupMlsState(account, group.groupIdHex) }
        }.onFailure {
            if (it is CancellationException) throw it
            appState.present(R.string.toast_couldnt_load_mls_state, AppText.Plain(it.message ?: it.javaClass.simpleName))
        }.getOrNull()
    }

    suspend fun loadOlder() {
        loadOlderPage()
    }

    suspend fun loadUntilMessageAvailable(messageIdHex: String): Boolean {
        var loadedPageCount = 0
        while (
            ReplyNavigation.shouldLoadOlder(
                targetLoaded = timelineRecords.containsKey(messageIdHex),
                hasMoreBefore = hasMoreBefore,
                loadedPageCount = loadedPageCount,
            )
        ) {
            if (!loadOlderPage()) break
            loadedPageCount += 1
        }
        return timelineRecords.containsKey(messageIdHex)
    }

    fun replyTargetMessageId(item: TimelineMessage): String? = ReplyNavigation.targetMessageId(item.record, item.projected)

    private suspend fun loadOlderPage(): Boolean {
        if (!hasMoreBefore || isLoadingOlder) return false
        val subscription = timelineSubscription ?: return false
        val priorMessageIds = timelineRecords.keys.toSet()
        // A previous loadOlderPage failure leaves `error` set; clear it now
        // that we're actually retrying, otherwise the stale banner sits over
        // a successful retry and a developer can't distinguish "still broken"
        // from "we forgot to clear it".
        error = null
        isLoadingOlder = true
        return try {
            // The subscription's paginate_backwards extends the runtime's
            // materialized window backwards by `count` and returns the new
            // authoritative window — already deduped, sorted, head-anchored,
            // and cap-trimmed. We render it directly via replaceWindow=true.
            val page =
                withContext(Dispatchers.IO) {
                    subscription.paginateBackwards(ConversationTimelinePageLimit)
                }
            hasLoadedOlderPages = true
            applyTimelinePage(page, replaceWindow = true, updatePagination = true)
            // The cached `mediaReferences` map only carries entries for
            // messages that have been listMedia-projected at some prior
            // point. Older-page rows landing fresh here would otherwise
            // fall back to the imeta-tag parser, which hard-codes
            // `sourceEpoch = 0` and breaks decryption on every image. Gate
            // on whether the page actually contains a media-bearing row so
            // a text-only history pull doesn't trigger the unbounded scan.
            if (pageContainsMedia(page)) refreshMediaReferences()
            // "Made progress" = the window grew OR shifted to include older
            // ids. paginateBackwards() returns a bounded/capped full window,
            // so size can stay constant while content still advances backward.
            timelineRecords.size > priorMessageIds.size ||
                timelineRecords.keys.any { it !in priorMessageIds }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            error = throwable.message ?: throwable.javaClass.simpleName
            false
        } finally {
            isLoadingOlder = false
        }
    }

    private suspend fun refreshCurrentTimeline(account: String): List<String> {
        val page =
            appState.marmotIo {
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
        hasLoadedOlderPages = false
        val streamIds = applyTimelinePage(page, replaceWindow = true, updatePagination = true)
        // Full-window replacement: re-seed the typed media cache too so any
        // freshly-projected media in the new window resolves to its real
        // `sourceEpoch` instead of the imeta-tag fallback of 0.
        if (pageContainsMedia(page)) refreshMediaReferences()
        return streamIds
    }

    /**
     * Resolved reply target as (sender pubkey, display body). Returns the raw
     * sender — not a display name — so the caller can cache this projection in
     * `remember` while name resolution stays live for late profile loads.
     */
    fun replyPreview(
        item: TimelineMessage,
        copy: MessageTextCopy = MessageTextCopy.Default,
    ): TimelineReplyDisplay? {
        item.projected?.let { record ->
            TimelineProjector.replyPreview(record, copy)?.let { preview -> return preview }
        }
        val targetMessageId = MessageProjector.replyTargetMessageId(item.record) ?: return null
        val target = messageById[targetMessageId] ?: return null
        val refs = MediaReferenceParser.parseAllImetaTags(target.tags)
        val mediaKind = replyMediaKindFromMime(refs.firstOrNull()?.mediaType)
        return TimelineReplyDisplay(
            sender = target.sender,
            body = MessageProjector.displayBody(target, copy),
            mediaKind = mediaKind,
        )
    }

    private fun applyTimelinePage(
        page: TimelinePageFfi,
        replaceWindow: Boolean,
        updatePagination: Boolean,
    ): List<String> {
        if (replaceWindow) {
            timelineRecords.clear()
            timelineItemsById.clear()
            timelineOrder.clear()
            projectedMessageIds.clear()
            // Drop stale projected records so messageById doesn't grow unbounded
            // as older pages are loaded; keep in-flight optimistic records so a
            // pending send still reconciles across a window replacement. See #68.
            val optimisticIds = optimisticMessages.values.mapTo(mutableSetOf()) { it.record.messageIdHex }
            messageById.keys.retainAll(optimisticIds)
        }
        page.messages.forEach { record ->
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
        if (updatePagination) {
            hasMoreBefore = page.hasMoreBefore
        }
        pruneConfirmedOptimisticMessages()
        pruneConfirmedOptimisticReactions()
        recomputeReactions()
        // The pass above already projected every record into the by-id and
        // order indexes (and reconciled optimistics), exactly like the live
        // update paths do — so publish directly. A second full rebuild here
        // re-projected every held record on each page load. See #74.
        publishTimelineFromIndexes()
        return page.messages
            .map { TimelineProjector.toAppMessageRecord(it) }
            .filter { MessageProjector.isStreamStart(it) }
            .mapNotNull { MessageProjector.streamId(it) }
            // Don't relaunch a watcher for a stream whose final record was in
            // this same page — it was just marked removed. See #25.
            .filterNot { it in removedStreamIds }
    }

    private fun applyTimelineChanges(changes: List<TimelineMessageChangeFfi>): List<String> {
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
                    deletedMessageIds = deletedMessageIds - change.messageIdHex
                    optimisticMessages.remove("msg:${change.messageIdHex}")
                    optimisticReactionChanges.entries
                        .filter { (_, reaction) -> reaction.targetMessageId == change.messageIdHex }
                        .map { it.key }
                        .forEach(optimisticReactionChanges::remove)
                }
            }
        }
        pruneConfirmedOptimisticMessages()
        pruneConfirmedOptimisticReactions()
        recomputeReactions(reactionTargets)
        publishTimelineFromIndexes()
        // Don't relaunch a watcher for a stream finalized in this same batch
        // (start + final records together) — it was just marked removed. See #25.
        return streamIds.filterNot { it in removedStreamIds }
    }

    private fun applyChatListProjection(
        trigger: ChatListUpdateTriggerFfi,
        row: ChatListRowFfi?,
    ) {
        val projected = row ?: return
        when (trigger) {
            ChatListUpdateTriggerFfi.ARCHIVE_CHANGED,
            ChatListUpdateTriggerFfi.PENDING_CONFIRMATION_CHANGED,
            ChatListUpdateTriggerFfi.NEW_GROUP,
            ChatListUpdateTriggerFfi.MEMBERSHIP_CHANGED,
            ChatListUpdateTriggerFfi.SNAPSHOT_REFRESH,
            -> {
                group =
                    group.copy(
                        name = projected.groupName.ifBlank { group.name },
                        archived = projected.archived,
                        pendingConfirmation = projected.pendingConfirmation,
                    )
            }
            ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
            ChatListUpdateTriggerFfi.LAST_MESSAGE_DELETED,
            ChatListUpdateTriggerFfi.UNREAD_CHANGED,
            ChatListUpdateTriggerFfi.REMOVED,
            -> Unit
        }
    }

    private suspend fun initializeReadState(account: String) {
        runCatching {
            appState.marmotIo { initializeChatReadState(account, group.groupIdHex) }
        }.onFailure {
            Log.w("DMConversation", "initialize read state failed for ${group.groupIdHex.take(8)}", it)
        }
    }

    private suspend fun markLatestVisibleRead(account: String) {
        val latest =
            timelineRecords.values.maxWithOrNull(
                compareBy<TimelineMessageRecordFfi> { it.timelineAt }.thenBy { it.messageIdHex },
            ) ?: return
        val messageId = latest.messageIdHex.takeIf { it.isNotBlank() } ?: return
        runCatching {
            appState.marmotIo { markTimelineMessageRead(account, group.groupIdHex, messageId) }
            appState.dismissConversationNotifications(account, group.groupIdHex)
        }.onFailure {
            Log.w("DMConversation", "mark read failed for ${group.groupIdHex.take(8)} message=${messageId.take(8)}", it)
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
        runCatching {
            appState.marmotIo { markTimelineMessageRead(account, group.groupIdHex, trimmed) }
            appState.dismissConversationNotifications(account, group.groupIdHex)
        }.onFailure {
            lastReadMessageId = previous
            if (it is CancellationException) throw it
            Log.w("DMConversation", "mark read failed for ${group.groupIdHex.take(8)} message=${trimmed.take(8)}", it)
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
        val projectedIds = timelineRecords.keys.mapTo(mutableSetOf()) { "msg:$it" }
        optimisticMessages.keys.filter { it in projectedIds }.forEach(optimisticMessages::remove)
    }

    private fun pruneConfirmedOptimisticReactions() {
        val mine = appState.activeAccount?.accountIdHex?.lowercase() ?: return
        val base = baseReactionSenders()
        optimisticReactionChanges.entries
            .filter { (_, change) ->
                val senders = base[change.targetMessageId]?.get(change.emoji).orEmpty()
                senders.contains(mine) == change.add
            }.map { it.key }
            .forEach(optimisticReactionChanges::remove)
    }

    private fun upsertProjectedRecord(
        record: TimelineMessageRecordFfi,
        reconcileOptimistic: Boolean = false,
        allowDelayedProjection: Boolean = false,
    ): AppMessageRecordFfi {
        // Defensive guard against the Rust core re-emitting an identical
        // record (own-publish + own-relay-echo both fire
        // `timeline_changes_for_event` for the same kind-9). Without this,
        // the remove-then-reinsert pair below briefly empties the bubble
        // from the timeline and Compose renders the "vanished + reappeared"
        // frame as a visible duplicate flash on large media bubbles.
        // Upstream fix tracked separately. See docs/design/darkmatter-double-upsert.md.
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
        if (
            existing != null &&
            recordsRenderEqual(existing, record) &&
            previousItemId != null &&
            timelineItemsById.containsKey(previousItemId)
        ) {
            return TimelineProjector.toAppMessageRecord(record)
        }
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
        val hasExactBridge =
            optimisticMessages.values.any { it.record.messageIdHex == record.messageIdHex }
        if (projectedIsMediaUpsert && !hasExactBridge && reconcileOptimistic) {
            val pendingMediaCount =
                optimisticMessages.values.count {
                    it.status == MessageStatus.Pending &&
                        it.record.tags.any { tag -> tag.values.firstOrNull() == "_media_pending" }
                }
            if (pendingMediaCount > 1) {
                pendingProjectionsAwaitingBridge[record.messageIdHex] = record
                return draftAction
            }
        }
        timelineRecords[record.messageIdHex] = record
        projectedMessageIds.add(record.messageIdHex)
        val actionRecord = draftAction
        preserveOptimisticDisplayPosition(record.messageIdHex, record.messageIdHex)
        optimisticMessageIdForProjection(
            optimisticMessages.values,
            actionRecord,
            allowDelayedProjection = allowDelayedProjection,
        )?.takeIf { reconcileOptimistic }
            ?.let { optimisticId ->
                preserveOptimisticDisplayPosition(record.messageIdHex, optimisticId)
                val optimisticKey = "msg:$optimisticId"
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
            }
        messageById[record.messageIdHex] = actionRecord
        val item = timelineMessageFromProjection(record, actionRecord)
        timelineItemsById[item.id] = item
        insertTimelineItemId(item.id)
        return actionRecord
    }

    private fun preserveOptimisticDisplayPosition(
        projectedId: String,
        optimisticId: String,
    ) {
        val optimistic = optimisticMessages["msg:$optimisticId"] ?: return
        localTimelineOrderOverrides[projectedId] = optimistic.timelineOrder
        localTimelineTimestampOverrides[projectedId] = optimistic.record.recordedAt
    }

    private fun removeProjectedRecord(messageIdHex: String) {
        val itemId = timelineRecords[messageIdHex]?.let(::projectedItemId) ?: "msg:$messageIdHex"
        timelineRecords.remove(messageIdHex)
        projectedMessageIds.remove(messageIdHex)
        localTimelineOrderOverrides.remove(messageIdHex)
        localTimelineTimestampOverrides.remove(messageIdHex)
        timelineItemsById.remove(itemId)
        timelineOrder.remove(itemId)
    }

    private fun timelineMessageFromProjection(
        record: TimelineMessageRecordFfi,
        actionRecord: AppMessageRecordFfi = TimelineProjector.toAppMessageRecord(record),
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
                    MessageProjector.isMine(actionRecord, appState.activeAccount?.accountIdHex) -> MessageStatus.Sent
                    else -> MessageStatus.Received
                },
            projected = record,
            timelineOrder = localTimelineOrderOverrides[record.messageIdHex] ?: 0uL,
        )
    }

    private fun projectedItemId(record: TimelineMessageRecordFfi): String {
        val actionRecord = TimelineProjector.toAppMessageRecord(record)
        val streamId = MessageProjector.streamId(actionRecord).takeIf { MessageProjector.isStreamStart(actionRecord) }
        return streamId?.let { "stream:$it" } ?: "msg:${record.messageIdHex}"
    }

    private fun insertTimelineItemId(itemId: String) {
        // Append in O(1). Position is irrelevant: publishTimelineFromIndexes
        // re-sorts the whole list with compareTimelineMessages (a total order),
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

    private inline fun coalesceTimelinePublishes(block: () -> Unit) {
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
        if (publishSuppressionDepth > 0) {
            publishPending = true
            return
        }
        publishTimelineFromIndexesInternal()
    }

    private fun publishTimelineFromIndexesInternal() {
        val projected = timelineOrder.mapNotNull { timelineItemsById[it] }
        timeline =
            (optimisticMessages.values + projected)
                .distinctBy { it.id }
                .sortedWith(::compareTimelineMessages)
        editsByTarget = aggregateEdits(timeline.map { it.record })
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
        val mine = appState.activeAccount?.accountIdHex?.lowercase()
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
        reactions =
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
    }

    private fun recomputeReactions(targetMessageIds: Set<String>) {
        if (targetMessageIds.isEmpty()) return
        val next = reactions.toMutableMap()
        targetMessageIds.forEach { target ->
            val tallies = reactionTalliesFor(target)
            if (tallies.isEmpty()) {
                next.remove(target)
            } else {
                next[target] = tallies
            }
        }
        reactions = next
    }

    private fun reactionTalliesFor(targetMessageId: String): List<ReactionTally> {
        // Lowercased sender sets for the same casing-drift reason as
        // recomputeReactions(). See #143.
        val mine = appState.activeAccount?.accountIdHex?.lowercase()
        val sendersByEmoji = linkedMapOf<String, MutableSet<String>>()
        timelineRecords[targetMessageId]?.reactions?.byEmoji?.forEach { summary ->
            sendersByEmoji.getOrPut(summary.emoji) { linkedSetOf() }.addAll(summary.senders.map { it.lowercase() })
        }
        if (mine != null) {
            optimisticReactionChanges.values
                .filter { it.targetMessageId == targetMessageId }
                .forEach { change ->
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

    fun reactionParticipantsFor(targetMessageId: String): List<ReactionParticipant> {
        val mine = appState.activeAccount?.accountIdHex
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

    private suspend fun refreshMembers() {
        val account = appState.activeAccountRef ?: return
        runCatching {
            // Force OpenMLS replay before trusting cached group details. For an
            // evicted account this is where Rust currently reports
            // GroupStateError::UseAfterEviction, which we map to read-only UI.
            appState.marmotIo { groupMlsState(account, group.groupIdHex) }
            val details = appState.marmotIo { groupDetails(account, group.groupIdHex) }
            applyGroupDetails(account, details)
        }.onFailure {
            if (it is CancellationException) throw it
            if (it.isUseAfterEviction()) {
                markActiveAccountRemovedFromMembers(account)
                return
            }
            Log.w("DMConversation", "refresh members failed for ${group.groupIdHex.take(8)}", it)
        }
    }

    /**
     * Whether [page] holds at least one record carrying a media attachment.
     * Used to gate the hot-path call to [refreshMediaReferences] so a
     * text-only / reaction-only timeline update doesn't trigger a full
     * `listMedia(group, null)` SQLite scan on every projection batch.
     */
    private fun pageContainsMedia(page: TimelinePageFfi): Boolean = page.messages.any(::recordCarriesMedia)

    private fun changesContainMedia(changes: List<TimelineMessageChangeFfi>): Boolean =
        changes.any { change ->
            change is TimelineMessageChangeFfi.Upsert && recordCarriesMedia(change.message)
        }

    /** Cheap structural check: a kind:9 record whose tag list includes an
     *  `imeta` entry is a media-bearing message under encrypted-media-v1. */
    private fun recordCarriesMedia(record: TimelineMessageRecordFfi): Boolean = record.kind == 9uL && record.tags.any { it.values.firstOrNull() == "imeta" }

    /**
     * Pull typed media references from Rust and cache them by `messageIdHex`.
     * Required so `downloadMedia` is called with the real `sourceEpoch`; the
     * imeta-tag parser fallback hard-codes `sourceEpoch = 0` (no field in the
     * wire format) and would otherwise fail every receive-side decryption
     * with `missing encrypted media secret for epoch 0`.
     */
    private suspend fun refreshMediaReferences() {
        val account = appState.activeAccountRef ?: return
        runCatching {
            appState.marmotIo { listMedia(account, group.groupIdHex, null) }
        }.onSuccess { records ->
            // Group by messageId (one message → N attachments); sort each
            // bucket by attachmentIndex so the bubble renders in album order.
            mediaReferences =
                records
                    .groupBy { it.messageIdHex }
                    .mapValues { (_, group) ->
                        group.sortedBy { it.attachmentIndex }.map { it.reference }
                    }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w("DMConversation", "listMedia failed for ${group.groupIdHex.take(8)}", it)
        }
    }

    private fun markActiveAccountRemovedFromMembers(account: String) {
        val activeAccountIdHex = appState.activeAccount?.accountIdHex ?: return
        val updatedMembers =
            members.filterNot {
                GroupProjector.isActiveAccountMember(it, activeAccountIdHex)
            }
        members = updatedMembers
        membersLoaded = true
        membersVerified = true
        group =
            group.copy(
                admins = group.admins.filterNot { it.equals(activeAccountIdHex, ignoreCase = true) },
            )
        appState.cacheGroupMemberSnapshot(account, group.groupIdHex, updatedMembers)
    }

    private fun removeMemberLocally(
        account: String,
        target: String,
    ) {
        val updatedMembers = members.filterNot { it.memberIdHex.equals(target, ignoreCase = true) }
        members = updatedMembers
        membersLoaded = true
        membersVerified = true
        group =
            group.copy(
                admins = group.admins.filterNot { it.equals(target, ignoreCase = true) },
            )
        appState.cacheGroupMemberSnapshot(account, group.groupIdHex, updatedMembers)
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
    ) {
        group = details.group
        members =
            details.members.map {
                AppGroupMemberRecordFfi(
                    memberIdHex = it.memberIdHex,
                    account = it.account,
                    local = it.local,
                )
            }
        membersLoaded = true
        membersVerified = true
        appState.cacheGroupMemberSnapshot(account, group.groupIdHex, members)
        appState.requestProfiles(members.map { it.memberIdHex })
    }

    private suspend fun watchAgentTextStream(
        account: String,
        streamId: String,
    ) {
        val text = StringBuilder()
        var subscription: AgentStreamSubscription? = null
        try {
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
                when (update) {
                    is AgentStreamUpdateFfi.Chunk -> {
                        text.append(update.text)
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
                        updateStreamPreview(streamId, copy.streamFailed(update.message), MessageStatus.Failed)
                    }
                    // Typed Hermes-agent variants (Progress / Record / Status)
                    // arrived with the recent Rust regen. We don't surface
                    // them in the streaming preview yet — drop silently so
                    // the loop keeps consuming the next chunk.
                    is AgentStreamUpdateFfi.Progress,
                    is AgentStreamUpdateFfi.Record,
                    is AgentStreamUpdateFfi.Status,
                    -> Unit
                }
            }
        } catch (throwable: Throwable) {
            updateStreamPreview(
                streamId,
                agentStreamFailureText(throwable, copy),
                MessageStatus.Failed,
            )
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { subscription?.close() }
            }
            activeStreamIds.remove(streamId)
        }
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
                    contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
                    kind = 1200uL,
                    tags = listOf(MessageProjector.streamTag(streamId)),
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
                contentTokens = tokens ?: MarkdownDocumentFfi(blocks = emptyList()),
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

    private fun nowSeconds(): ULong = (System.currentTimeMillis() / 1000L).toULong()

    /**
     * Whether two timeline records would render the same bubble. Compares
     * the user-visible fields — id, body, tags (incl. imeta), reply preview,
     * media descriptor, reactions, direction, sender — and ignores the
     * ephemeral fields (`receivedAt`, `timelineAt`) that vary between the
     * Rust core's duplicate emits of the same logical event. Used by the
     * `upsertProjectedRecord` short-circuit to avoid the remove-then-
     * reinsert flash on no-op upserts.
     */
    private fun recordsRenderEqual(
        a: TimelineMessageRecordFfi,
        b: TimelineMessageRecordFfi,
    ): Boolean =
        a.messageIdHex == b.messageIdHex &&
            a.sourceMessageIdHex == b.sourceMessageIdHex &&
            a.direction == b.direction &&
            a.groupIdHex == b.groupIdHex &&
            a.sender == b.sender &&
            a.plaintext == b.plaintext &&
            a.kind == b.kind &&
            a.tags == b.tags &&
            a.replyToMessageIdHex == b.replyToMessageIdHex &&
            a.replyPreview == b.replyPreview &&
            a.mediaJson == b.mediaJson &&
            a.agentTextStreamJson == b.agentTextStreamJson &&
            a.reactions == b.reactions

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
