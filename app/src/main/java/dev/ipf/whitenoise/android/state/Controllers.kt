package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AgentStreamSubscription
import dev.ipf.marmotkit.AgentStreamUpdateFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
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
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ChatListMessageSearch
import dev.ipf.whitenoise.android.core.ConversationTranscriptExport
import dev.ipf.whitenoise.android.core.ConversationTranscriptTimelineReader
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupSystemEvents
import dev.ipf.whitenoise.android.core.MessageBodyMatch
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.core.ReplyNavigation
import dev.ipf.whitenoise.android.core.StreamDebugEventFormatter
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.core.TimelineReplyDisplay
import dev.ipf.whitenoise.android.core.aggregateEdits
import dev.ipf.whitenoise.android.core.replyMediaKindFromMime
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.MediaReferenceParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
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
    /**
     * Known removal evidence that the [memberSnapshot] roster alone can't
     * carry: a successful self-leave (including leaving as the sole member,
     * which caches an *empty* roster) or a loaded roster that omits self.
     * Set by [ChatsController] when removal is established; lets
     * [removedFromGroup] treat a known-empty-post-removal roster as real
     * removal while a null/failed-fetch empty roster stays non-removed.
     */
    val removed: Boolean = false,
) {
    val id: String = group.groupIdHex

    val projectedTitle: String?
        get() = projection?.title?.takeIf { it.isNotBlank() }

    val latestAt: ULong?
        // Prefer the last message's timeline timestamp. When a chat has no last
        // message, fall back to the last *read* timeline position before the
        // projection's `updatedAt`:
        //
        //   - `lastReadTimelineAt` is sourced from the read state, not the
        //     message store (see storage-sqlite chat_list
        //     `rebuild_chat_list_row_for_group_tx`), so it survives a
        //     disappearing-message prune that empties the chat. Keying on it
        //     keeps an all-expired chat sorted where its last visible message
        //     sat instead of jumping to the top (issue #849).
        //   - `updatedAt` is a cache-version field bumped to `now` on *every*
        //     row rebuild — including a prune — so it must not be the primary
        //     sort fallback. It still backs a genuinely new chat that has no
        //     message and no read state yet: there it equals the group-create
        //     time, sorting the new DM/group to the top (issue #321).
        //
        // All three are the same unix-seconds unit as `timelineAt`.
        get() =
            projection?.lastMessage?.timelineAt
                ?: projection?.lastReadTimelineAt
                ?: projection?.updatedAt
                ?: latest?.recordedAt

    val unreadCount: ULong
        get() = projection?.unreadCount ?: 0uL

    val hasUnread: Boolean
        get() = projection?.hasUnread ?: false

    /** At least one unread message in this chat mentions the active account. */
    val unreadMention: Boolean
        get() = projection?.unreadMention ?: false

    /**
     * Whether the active account is no longer a member of this group. Two
     * independent signals establish this:
     *
     *  - [removed]: an explicit marker [ChatsController] sets once removal is
     *    *known* — a self-leave (including leaving as the sole member, which
     *    caches an empty roster) or a roster fetch that loaded and omits self.
     *    This is what lets a genuinely-empty post-removal roster suppress the
     *    badge, since an empty [memberSnapshot] alone is ambiguous.
     *  - a *loaded, non-empty* [memberSnapshot] that omits self — the engine's
     *    `groupMembers` roster landed and self isn't in it.
     *
     * A null snapshot (fetch hasn't landed) and an empty snapshot *without* the
     * [removed] marker (a best-effort fetch failure) are both treated as "not
     * yet known", so neither suppresses the row's badge. Returns false with a
     * blank/absent active account, matching [GroupProjector] semantics.
     */
    fun removedFromGroup(activeAccountIdHex: String?): Boolean {
        val active = activeAccountIdHex?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (removed) return true
        val snapshot = memberSnapshot?.takeIf { it.members.isNotEmpty() } ?: return false
        return !snapshot.containsAccount(active)
    }

    /**
     * The unread badge to render for this row given the active account. A group
     * the user has been removed from keeps a frozen [unreadCount] in the
     * projection (the engine stops advancing reads once self is evicted), which
     * reads as a stale alert; suppress it to zero so a removed group shows no
     * badge (#625).
     */
    fun effectiveUnreadCount(activeAccountIdHex: String?): ULong = if (removedFromGroup(activeAccountIdHex)) 0uL else unreadCount

    /** [hasUnread] with the removed-group suppression applied (#625). */
    fun effectiveHasUnread(activeAccountIdHex: String?): Boolean = hasUnread && !removedFromGroup(activeAccountIdHex)

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
    removed: Boolean = false,
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
                    contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
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
        removed = removed,
    )
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
 * Distinct, non-blank account ids whose profile presentation a timeline page
 * needs painted: every message author, every reply-preview author, and every
 * reaction author. First-seen order is preserved so the warm pass below favors
 * the rows nearest the top of the page. Used to pre-warm local profile
 * presentations before the first composition so sender names + avatars don't
 * pop in a few frames after the message bubble. See #609.
 */
internal fun timelineRecordProfileSenders(records: Iterable<TimelineMessageRecordFfi>): List<String> {
    val senders = linkedSetOf<String>()

    fun add(id: String) {
        if (id.isNotBlank()) senders.add(id)
    }
    records.forEach { record ->
        add(record.sender)
        record.replyPreview?.let { add(it.sender) }
        record.reactions.userReactions.forEach { add(it.sender) }
    }
    return senders.toList()
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
        disappearingMessageSecs = 0uL,
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
    messageById[optimistic.messageIdHex] = optimistic
    optimisticMessages[key] =
        TimelineMessage(
            key,
            optimistic,
            MessageStatus.Failed,
            timelineOrder = timelineOrder,
        )
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

internal fun mediaCacheKey(
    account: String,
    groupIdHex: String,
    messageIdHex: String,
    attachmentIndex: Int,
): String = "$account|$groupIdHex|$messageIdHex|$attachmentIndex"

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
 * Message ids of unread received mentions in [timeline], oldest first — drives
 * the in-conversation jump-to-mention chip. Same anchor semantics as
 * [countUnreadIncoming]: a null [readAnchorMessageId], or one that has fallen out
 * of the loaded window, counts from the first loaded row. Without a reliable
 * ordering signal for an out-of-window watermark, counting (occasionally an
 * already-read mention) is preferred over hiding genuinely-unread ones. Only
 * kind-9 chat rows can be mentions; [mentionsActiveAccount] is passed in so this
 * stays pure and the ui-layer NIP-27 detection isn't pulled into the state layer.
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
    return timeline
        .drop(anchorIdx + 1)
        .filter { it.record.direction == "received" && it.record.kind == 9uL && mentionsActiveAccount(it) }
        .mapNotNull { it.record.messageIdHex.takeIf { id -> id.isNotBlank() } }
}

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
    val candidate = timeline.getOrNull(candidateIndex)
    val candidateId = candidate?.record?.messageIdHex
    if (candidateId.isNullOrBlank()) return currentAnchorId
    // Synthetic streaming-debug rows carry a non-hex id and never mark read;
    // don't let one become the read anchor or it would pin the pointer off a
    // real message until the next chat row advances it.
    if (candidateId.startsWith(ConversationController.STREAM_DEBUG_ID_PREFIX)) return currentAnchorId
    if (currentAnchorId == null) return candidateId
    val anchorIdx = timeline.indexOfFirst { it.record.messageIdHex == currentAnchorId }
    return if (anchorIdx < 0 || candidateIndex > anchorIdx) candidateId else currentAnchorId
}

data class ConversationControllerCopy(
    val waitingForStream: String = "Waiting for stream...",
    val streamFailedFormat: String = "Stream failed: %1\$s",
    val couldntAddMemberDuplicateFormat: String =
        "Couldn't add %1\$s. They're already a member, or their signing key conflicts with an existing member.",
) {
    fun streamFailed(message: String): String = String.format(streamFailedFormat, message)

    fun couldntAddMemberDuplicate(name: String): String = String.format(couldntAddMemberDuplicateFormat, name)
}

internal data class AppliedGroupDetails(
    val group: AppGroupRecordFfi,
    val members: List<AppGroupMemberRecordFfi>,
)

internal fun applyAuthoritativeGroupDetails(details: GroupDetailsFfi): AppliedGroupDetails =
    AppliedGroupDetails(
        group = details.group,
        members =
            details.members.map {
                AppGroupMemberRecordFfi(
                    memberIdHex = it.memberIdHex,
                    account = it.account,
                    local = it.local,
                )
            },
    )

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
    private val appState: WhiteNoiseAppState,
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

    private fun chatRowKey(groupIdHex: String): String = groupIdHex.lowercase()

    private val chatRowsByGroup = LinkedHashMap<String, ChatListRowFfi>()
    private val chatRows: Collection<ChatListRowFfi>
        get() = chatRowsByGroup.values
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

    // Groups the active account is known to have been removed/left from, keyed
    // by group id hex. Set on a confirmed self-leave or when a loaded roster
    // omits self; lets [ChatListItem.removedFromGroup] treat a genuinely-empty
    // post-removal roster as real removal (a fetch-failure empty roster, which
    // never adds an id here, stays non-removed). Cleared on every bind.
    private var removedGroupIds: Set<String> = emptySet()

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
    private var isCleared = false

    private val liveSubscriptionLock = Any()
    private var activeChatListSubscription: ChatListSubscription? = null
    private var activeChatsSubscription: ChatsSubscription? = null
    private var bindJob: Job? = null

    suspend fun closeLiveSubscriptionsForAccountTeardown(accountRef: String) {
        val teardown =
            synchronized(liveSubscriptionLock) {
                if (!shouldTeardownLiveSubscriptionsForAccount(accountRef, this.accountRef, boundAccountRef)) {
                    null
                } else {
                    this.accountRef = null
                    boundAccountRef = null
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

    suspend fun bind(accountRef: String?) {
        if (isCleared) return
        val currentBindJob = coroutineContext[Job]
        synchronized(liveSubscriptionLock) { bindJob = currentBindJob }
        chatsDebug { "bind account=${accountRef?.take(8)}" }
        this.accountRef = accountRef
        this.boundAccountRef = accountRef
        resetBackingState()
        bindEpoch += 1L
        recompute()
        error = null

        if (accountRef == null) {
            synchronized(liveSubscriptionLock) {
                if (bindJob === currentBindJob) {
                    bindJob = null
                }
            }
            return
        }
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
            while (coroutineContext.isActive && shouldRetryLiveSubscriptionForAccount(accountRef, boundAccountRef)) {
                var chatListSubscription: ChatListSubscription? = null
                var chatsSubscription: ChatsSubscription? = null
                try {
                    val chatListStream =
                        appState.marmotIo { subscribeChatList(accountRef, includeArchived = true) }
                    chatListSubscription = chatListStream
                    val chatStream =
                        appState.marmotIo { subscribeChats(accountRef, includeArchived = true) }
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
                    synchronized(liveSubscriptionLock) {
                        if (activeChatListSubscription === chatListSubscription) {
                            activeChatListSubscription = null
                        }
                        if (activeChatsSubscription === chatsSubscription) {
                            activeChatsSubscription = null
                        }
                    }
                }
                if (!coroutineContext.isActive || !shouldRetryLiveSubscriptionForAccount(accountRef, boundAccountRef)) break
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
            synchronized(liveSubscriptionLock) {
                if (bindJob === currentBindJob) {
                    bindJob = null
                }
            }
            chatsDebug { "unbind account=${accountRef.take(8)} (chat-list + chats subscriptions closed)" }
        }
    }

    private fun foldGroup(record: AppGroupRecordFfi) {
        groupRecordsById = groupRecordsById + (record.groupIdHex to record)
        scheduleRecompute()
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
    fun applyOptimisticSentPreview(
        groupIdHex: String,
        preview: ChatListMessagePreviewFfi,
    ) {
        if (accountRef == null) return
        val row = chatRowsByGroup[chatRowKey(groupIdHex)] ?: return
        chatRowsByGroup[chatRowKey(groupIdHex)] =
            row.copy(
                lastMessage = preview,
                updatedAt = maxOf(row.updatedAt, preview.timelineAt),
            )
        scheduleRecompute()
    }

    fun applyLocalGroupUpdate(record: AppGroupRecordFfi) {
        if (accountRef == null) return
        val rowKey = chatRowKey(record.groupIdHex)
        if (groupRecordsById[record.groupIdHex] == null && !chatRowsByGroup.containsKey(rowKey)) return
        // chatListItemFromProjection reads row.archived / row.pendingConfirmation
        // (not just the group record), so patch both the chat row and the group
        // record to keep them consistent.
        chatRowsByGroup[rowKey]?.let { row ->
            chatRowsByGroup[rowKey] =
                row.copy(
                    archived = record.archived,
                    pendingConfirmation = record.pendingConfirmation,
                    groupName = record.name.ifBlank { row.groupName },
                )
        }
        foldGroup(record)
    }

    // Project every current chat row into a ChatListItem. Reads chatRows (kept
    // current by the bind loop even when recompute is deferred behind an open
    // conversation, #6), so on-demand callers — shared groups, DM lookup,
    // by-id resolution for navigation — see freshly created/updated groups
    // instead of the stale `items` snapshot.
    private fun currentProjectedItems(activeAccountIdHex: String? = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex): List<ChatListItem> =
        chatRows.map { row ->
            chatListItemFromProjection(
                row = row,
                group = groupRecordsById[row.groupIdHex],
                activeAccountIdHex = activeAccountIdHex,
                members = memberCacheByGroup[row.groupIdHex],
                previewTokens = chatRowPreviewMarkdownSource(row)?.let { previewTokensByText[it] },
                removed = row.groupIdHex in removedGroupIds,
            )
        }

    private fun boundAccountIdHex(): String? {
        val ref = accountRef ?: return null
        return appState.accounts.firstOrNull { it.label == ref }?.accountIdHex
    }

    private fun projectChatRow(
        row: ChatListRowFfi,
        activeAccountIdHex: String? = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex,
    ): ChatListItem =
        chatListItemFromProjection(
            row = row,
            group = groupRecordsById[row.groupIdHex],
            activeAccountIdHex = activeAccountIdHex,
            members = memberCacheByGroup[row.groupIdHex],
            previewTokens = chatRowPreviewMarkdownSource(row)?.let { previewTokensByText[it] },
            removed = row.groupIdHex in removedGroupIds,
        )

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

    /**
     * The confirmed 1:1 DM with [reference] (npub or hex), or null. A 1:1 is a
     * confirmed two-member group whose counterparty is the target; the
     * counterparty is stored as hex, so compare in both hex and npub forms.
     * Shared with the new-chat sheet so "open existing DM" and "don't create a
     * duplicate DM" agree on what counts as an existing one.
     */
    fun existingDirectChat(reference: String): ChatListItem? {
        val normalizedReference = reference.trim().takeIf { it.isNotEmpty() } ?: return null
        return chatRows
            .asSequence()
            .filter { !it.pendingConfirmation }
            .firstNotNullOfOrNull { row ->
                val members = memberCacheByGroup[row.groupIdHex] ?: return@firstNotNullOfOrNull null
                if (members.size != 2) return@firstNotNullOfOrNull null
                val other =
                    GroupProjector.otherMemberAccount(
                        members,
                        boundAccountIdHex() ?: appState.activeAccount?.accountIdHex,
                    ) ?: return@firstNotNullOfOrNull null
                if (other.equals(normalizedReference, ignoreCase = true) ||
                    appState.npub(other).equals(normalizedReference, ignoreCase = true)
                ) {
                    projectChatRow(row)
                } else {
                    null
                }
            }
    }

    fun chatItemForGroup(groupIdHex: String): ChatListItem? {
        val row = chatRowsByGroup[chatRowKey(groupIdHex)] ?: chatRows.firstOrNull { it.groupIdHex.equals(groupIdHex, ignoreCase = true) }
        return row?.let { projectChatRow(it) }
    }

    // Lightweight membership probe over the raw rows — no per-row ChatListItem
    // projection. awaitChatListItem's poll loop uses this to test whether a
    // freshly created group has surfaced yet without rebuilding the whole
    // projected list on every 50ms tick.
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
            currentProjectedItems().filterNot { it.group.pendingConfirmation },
        )

    private fun foldChatRow(row: ChatListRowFfi) {
        chatRowsByGroup[chatRowKey(row.groupIdHex)] = row
        scheduleRecompute()
    }

    private fun replaceChatRows(rows: List<ChatListRowFfi>) {
        chatRowsByGroup.clear()
        rows.forEach { row -> chatRowsByGroup[chatRowKey(row.groupIdHex)] = row }
    }

    private fun removeChatRow(groupIdHex: String) {
        chatRowsByGroup.remove(chatRowKey(groupIdHex))
        scheduleRecompute()
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
            appState.withGroupCommitLock(account, groupIdHex) {
                val updated = appState.marmotIo { setGroupArchived(account, groupIdHex, archived) }
                appState.applyLocalGroupUpdate(updated)
            }
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
            appState.withGroupCommitLock(account, groupIdHex) {
                if (GroupProjector.requiresSelfDemoteBeforeLeave(group, activeAccountIdHex, memberCount)) {
                    val demoteResult = appState.marmotIo { selfDemoteAdminDetailed(account, groupIdHex) }
                    demotedBeforeLeave = true
                    appState.applyLocalGroupUpdate(demoteResult.details.group)
                }
                appState.marmotIo { leaveGroup(account, groupIdHex) }
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
        val removedRow = chatRowsByGroup[chatRowKey(groupIdHex)]
        removeChatRow(groupIdHex)
        // Decide leave-first from the live roster, not the chat-list row's
        // removed heuristic (which can lag a leave done elsewhere) — a genuinely
        // left group then wipes directly instead of trying to re-leave. Fall back
        // to the caller's hint only if the membership read fails.
        val activeIdHex = appState.activeAccount?.accountIdHex
        val stillMember =
            runCatching { appState.marmotIo { groupMembers(account, groupIdHex) } }
                .getOrNull()
                ?.any { it.memberIdHex.equals(activeIdHex, ignoreCase = true) }
                ?: leaveFirstHint
        if (stillMember && !leaveGroup(groupIdHex)) {
            removedRow?.let { foldChatRow(it) }
            return false
        }
        // Free the app's decrypted media caches (in-memory L1 + on-disk L2) before
        // the engine wipe drops the reference mapping — deleteGroupLocal clears its
        // own rows/secrets but not these client-side blobs, which would otherwise
        // linger recoverable on disk.
        runCatching { appState.marmotIo { listMedia(account, groupIdHex, null) } }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { media ->
                withContext(Dispatchers.IO) {
                    media.forEach { rec ->
                        val key = mediaCacheKey(account, groupIdHex, rec.messageIdHex, rec.attachmentIndex.toInt())
                        appState.mediaPlaintextCache.remove(key)
                        appState.mediaThumbnailCache.remove(key)
                        appState.diskMediaCache.remove(key)
                    }
                    val tags = media.mapNotNull { it.reference.ciphertextSha256 }.toSet()
                    if (tags.isNotEmpty()) appState.diskMediaCache.removeByCiphertextTags(tags)
                }
            }
        val wipe = runCatching { appState.marmotIo { deleteGroupLocal(account, groupIdHex) } }
        wipe.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
            removedRow?.let { row -> foldChatRow(row) }
            appState.present(R.string.toast_couldnt_delete_chat, AppText.Plain(it.message ?: it.javaClass.simpleName))
            return false
        }
        // Wipe succeeded (or found nothing to remove) — the row was already
        // hidden optimistically on entry, so just confirm.
        appState.present(R.string.toast_chat_deleted_local)
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
        if (isCleared) return
        if (visible == chatListVisible) return
        chatListVisible = visible
        if (visible && pendingRecompute) recompute()
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
        bindEpoch += 1L
        val jobToCancel =
            synchronized(liveSubscriptionLock) {
                accountRef = null
                boundAccountRef = null
                bindJob.also { bindJob = null }
            }
        jobToCancel?.cancel()
        resetBackingState()
        items = emptyList()
        archivedItems = emptyList()
        isLoading = false
        error = null
        pendingRecompute = false
        recomputeScheduled = false
        recomputeScope.cancel()
    }

    private fun resetBackingState() {
        replaceChatRows(emptyList())
        groupRecordsById = emptyMap()
        memberCacheByGroup = emptyMap()
        removedGroupIds = emptySet()
        inFlightMemberFetches.clear()
        previewTokensByText = emptyMap()
        inFlightPreviewParses.clear()
    }

    private fun isActiveBindEpoch(epoch: Long): Boolean = !isCleared && bindEpoch == epoch && accountRef != null

    private fun recompute() {
        if (isCleared) return
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
            appState.updateAccountUnreadCount(
                unreadAccountRef,
                accountUnreadCount(projected, unreadAccountIdHex),
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
        val all = sortChatListItems(projected)
        items = all.filter { !it.group.archived }
        archivedItems = all.filter { it.group.archived }
        chatsDebug { "recompute visible=${items.size} archived=${archivedItems.size} total=${all.size}" }
        // For any group we don't yet have members cached for, fan out a
        // one-shot members fetch so unnamed titles and the profile sheet's
        // shared-groups list can resolve from local snapshots.
        schedulePendingMemberFetches()
        // Likewise, fan out off-main markdown parses for any preview text we
        // haven't tokenized yet; each completion folds back via
        // scheduleRecompute() so a burst coalesces into one rebuild.
        schedulePendingPreviewParses()
    }

    /**
     * Walk the current chat rows and, for any group without cached members or
     * an in-flight fetch, kick off a `groupMembers` FFI call. On success the
     * cache updates and `scheduleRecompute()` runs so row titles and
     * profile-sheet shared-group intersections see the local member snapshot,
     * with a burst of completions coalesced into one rebuild.
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
            recomputeScope.launch {
                try {
                    memberFetchGate.withPermit {
                        if (!isActiveBindEpoch(epoch)) return@withPermit
                        val members = appState.marmotIo { groupMembers(account, groupIdHex) }
                        if (isActiveBindEpoch(epoch)) {
                            members
                                .map { it.memberIdHex }
                                .filter { it.isNotBlank() }
                                .forEach(appState::requestProfile)
                            memberCacheByGroup = memberCacheByGroup + (groupIdHex to members)
                            // A loaded roster that omits self is known removal
                            // evidence (admin eviction / self-leave the engine
                            // has already applied). Marking it makes an empty
                            // self-only roster suppress the badge too, where the
                            // snapshot path alone reads empty as ambiguous.
                            val activeAccountIdHex = appState.activeAccount?.accountIdHex
                            if (activeAccountIdHex != null &&
                                members.none { GroupProjector.isActiveAccountMember(it, activeAccountIdHex) }
                            ) {
                                removedGroupIds = removedGroupIds + groupIdHex
                            }
                            // Coalesce: a burst of member-fetch completions on
                            // account open/switch would otherwise drive N
                            // un-debounced full recomputes. Defer into one.
                            scheduleRecompute()
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
                    if (isActiveBindEpoch(epoch)) inFlightMemberFetches.remove(groupIdHex)
                }
            }
        }
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
                    if (!isActiveBindEpoch(epoch)) return@launch
                    val tokens = appState.parseMarkdownOrEmpty(text)
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
}

/**
 * Parse [text] into the same Markdown AST the Rust core attaches to projected
 * records, for the state Android synthesizes locally (optimistic sends,
 * finished agent streams, chat-list previews). `parseMarkdown` is a blocking
 * FFI call, so it rides [WhiteNoiseAppState.marmotIo]'s `Dispatchers.IO` hop
 * instead of the main thread. Any failure degrades to an empty document —
 * empty docs render as plain text, so a parser error can never lose a
 * message body.
 */
internal suspend fun WhiteNoiseAppState.parseMarkdownOrEmpty(text: String): MarkdownDocumentFfi =
    try {
        marmotIo { parseMarkdown(text) }
    } catch (throwable: Throwable) {
        rethrowIfCancellation(throwable)
        MarkdownDocumentFfi(truncated = false, blocks = emptyList())
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
    if (BuildConfig.DEBUG) {
        Log.e("DMChats", message(), error)
    } else {
        Log.e("DMChats", "operation failed: ${error.javaClass.simpleName}")
    }
}

private val ConversationTimelinePageLimit = 50u

// Cap on the live-projected timeline window (≈4 pages). Bounds memory for a
// long-open, busy conversation while leaving ample scroll headroom before
// loadOlder() must re-fetch.
private const val LIVE_TIMELINE_WINDOW_CAP = 200

// One frame: long enough to collapse a chat-list sync burst into a single
// recompute, short enough to stay imperceptible.
private const val CHAT_LIST_RECOMPUTE_DEBOUNCE_MS = 16L

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

// DEBUG-only bound on the send-latency trace map (issue #913). Small: at most a
// handful of sends are in flight before their echo reconciles; the cap only
// guards against a burst of never-echoed sends leaking entries.
private const val SEND_TRACE_MAX_TRACKED = 64

class ConversationController(
    private val appState: WhiteNoiseAppState,
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

    // Hex of the account active when this conversation opened. Captured like
    // conversationAccountRef so display/permission/"is me" helpers stay tied to
    // the conversation's account instead of reading the live active account
    // (which can differ from the controller's account before teardown).
    private val conversationAccountIdHex = appState.activeAccount?.accountIdHex

    // True when the seeding snapshot positively places the active account in
    // the roster. Lets the bottom bar show the active composer immediately for
    // a known member while refreshMembers() verifies, without flashing the
    // active composer for a group the user has already left (whose cached
    // snapshot has self removed). Captured synchronously at construction — the
    // only membership signal available before the first refresh round-trips
    // (issue #545).
    val seededSelfMember: Boolean =
        initialMemberSnapshot?.members?.any {
            GroupProjector.isActiveAccountMember(it, conversationAccountIdHex)
        } == true

    // True when construction received a member snapshot at all — i.e. the local
    // roster for this group was already known synchronously (warm from the chat
    // list cache or the shared AppState snapshot). When true, `seededSelfMember`
    // is an AUTHORITATIVE membership signal: self is either in the snapshot
    // (member) or has been removed from it (the group the user left, #545).
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
    val seededMembershipKnown: Boolean = initialMemberSnapshot != null

    // Typed media references keyed by `messageIdHex`. Populated from Rust's
    // `listMedia` FFI — the only place the receive-side `source_epoch` is
    // surfaced (TimelineMessageRecordFfi / AppMessageRecordFfi don't expose
    // it). Without this, `downloadMedia` would be called with the imeta-tag
    // parser's `sourceEpoch = 0` fallback and the Rust core would error
    // with `missing encrypted media secret for epoch 0`.
    var mediaReferences: Map<String, List<MediaAttachmentReferenceFfi>> by mutableStateOf(emptyMap())
        private set
    var membersVerified by mutableStateOf(false)
        private set

    // Authoritative local self-leave marker (issue #787). Short-lived lifecycle
    // state (lives only as long as this controller, never persisted —
    // AGENTS.md); it mirrors ChatsController.removedGroupIds for the chat-list
    // row.
    //
    // The engine eviction (GroupStateError::UseAfterEviction) that
    // refreshMembers() relies on may not have landed locally yet right after a
    // self-leave, so a transient refreshMembers()/applyGroupDetails()
    // round-trip would otherwise re-read the full roster (self still present),
    // restore the member count and re-enable the composer. While set,
    // isSelfMember reads false and applyGroupDetails() refuses to re-add self,
    // keeping the left state durable.
    //
    // Seeded from an authoritative snapshot that already excludes self
    // (seededMembershipKnown && !seededSelfMember): re-opening a just-left group
    // builds a NEW controller whose own success path never ran, so without this
    // its first refreshMembers() would re-add self and revert the left state
    // (the exact #787 repro). A present snapshot only omits self once a
    // leave/eviction removed self from it, so this is a sound "not a member"
    // signal — the same one the composer gate uses for its initial NOTICE.
    private val selfMembership = ConversationSelfLeftState(seededMembershipKnown, seededSelfMember)
    var timeline by mutableStateOf<List<TimelineMessage>>(emptyList())
        private set

    // A snapshot map (not mutableStateOf<Map>) so a bubble reading one key isn't
    // recomposed when a different message's reactions change.
    private val reactionsState = mutableStateMapOf<String, List<ReactionTally>>()
    val reactions: Map<String, List<ReactionTally>> get() = reactionsState
    var deletedMessageIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var replyingTo by mutableStateOf<AppMessageRecordFfi?>(null)

    /** Per-target edit history for kind-1009 events, recomputed on every
     * timeline publish. The bubble reads `.latestText` and the "(edited · N)"
     * affordance reads `.count`. Null entry == message never edited. */
    var editsByTarget by mutableStateOf<Map<String, EditState>>(emptyMap())
        private set

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

    // DEBUG-only send-latency trace bookkeeping (issue #913): maps a pending
    // optimistic text message's temp id to (traceSequence, monotonicStartMs) so
    // the engine-echo reconcile that flips the bubble pending → sent
    // (upsertProjectedRecord) can log the accepted → echoed-reconcile latency —
    // the "self-echo drives the flip" candidate. Short-lived lifecycle state:
    // entries are added on optimistic send and removed on reconcile; bounded so
    // a burst of never-echoed sends can't grow it. Holds no protocol data (only
    // a local temp id, a one-run sequence string, and a monotonic long), so it
    // is not an Android-owned cache of White Noise data (AGENTS.md).
    private val sendTraceByTempId = linkedMapOf<String, SendTraceEntry>()
    private val inviteStreamScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Cached at start() so `loadOlderPage` / `loadNewerPage` can drive
    // `paginate_backwards` / `paginate_forwards` on the subscription. Per
    // the PR-#400 contract, the subscription owns the materialized window
    // (ordering, dedup, head-anchoring, cap, has_more_*); clients render
    // the returned page directly instead of merging against a hand-rolled
    // cursor.
    @Volatile
    private var timelineSubscription: TimelineMessagesSubscription? = null
    private val liveSubscriptionLock = Any()
    private var groupStateSubscription: GroupStateSubscription? = null
    private var startJob: Job? = null
    private var conversationScope: CoroutineScope? = null
    private var accountTeardownRequested = false
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
    private var streamDebugEventSequence: ULong = 0uL

    // Bounded LRU set: tombstones are capped so an agent-heavy conversation
    // kept open for a long time can't grow memory or per-batch filter cost
    // without bound. See #200.
    private val removedStreamIds = BoundedStreamTombstones()
    private var hasLoadedOlderPages = false

    // Last message id we successfully marked as read on the Rust side.
    // Dedupes scroll-driven [markReadUpTo] calls so settling on the same row
    // doesn't issue redundant FFI hops. Compose-observable so UI (the
    // jump-to-mention chip) can derive unread state off the engine read
    // watermark rather than the scroll position.
    var lastReadMessageId: String? by mutableStateOf(null)
        private set

    val title: String
        get() = title()

    fun title(copy: dev.ipf.whitenoise.android.core.GroupTitleCopy = dev.ipf.whitenoise.android.core.GroupTitleCopy.Default): String {
        val me = conversationAccountIdHex
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
            val me = conversationAccountIdHex
            val other = GroupProjector.otherMemberAccount(members, me)
            return GroupProjector.avatarAccount(group, other, members.size)
        }

    /**
     * Avatar URL for the conversation top bar. A group's own avatar wins; a 1:1
     * DM falls back to the peer's profile picture so the top bar matches the
     * chat-list row instead of showing a blank/initials placeholder (#837).
     */
    val avatarUrl: String?
        get() = group.avatarUrl ?: avatarAccount?.let { appState.avatarUrl(it) }

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
        get() = GroupProjector.isAdminRef(group, conversationAccountIdHex)

    val isSelfMember: Boolean
        get() = selfMembership.isSelfMember(members, conversationAccountIdHex)

    val canSendMessages: Boolean
        get() = membersVerified && isSelfMember

    val canLeaveGroup: Boolean
        get() = GroupProjector.canLeaveGroup(group, conversationAccountIdHex, members.size)

    /**
     * True when the active account is the only admin of a group that still has
     * other members, i.e. trapped: they can't revoke their own admin or leave
     * until they hand admin to someone else. The group-detail UI uses this to
     * surface the "Transfer admin" entry point from the blocked revoke / leave
     * paths (issue #417).
     */
    val isSoleAdminWithOtherMembers: Boolean
        get() = GroupProjector.isSoleAdminWithOtherMembers(group, conversationAccountIdHex, members.size)

    /** Members eligible to receive a transferred admin role from the active account. */
    fun transferAdminCandidates(): List<AppGroupMemberRecordFfi> = members.filter { GroupProjector.canTransferAdminTo(group, it, conversationAccountIdHex) }

    fun revokeWouldDepleteAdmins(member: AppGroupMemberRecordFfi): Boolean = GroupProjector.revokeWouldDepleteAdmins(group, member, members.size)

    fun canTransferAdminTo(member: AppGroupMemberRecordFfi): Boolean = GroupProjector.canTransferAdminTo(group, member, conversationAccountIdHex)

    suspend fun start() {
        val account = conversationAccountRef ?: return
        val currentStartJob = coroutineContext[Job]
        val shouldStart =
            synchronized(liveSubscriptionLock) {
                if (accountTeardownRequested) {
                    false
                } else {
                    startJob = currentStartJob
                    true
                }
            }
        if (!shouldStart) return
        isLoading = true
        error = null
        try {
            coroutineScope {
                conversationScope = this
                try {
                    // Converge workers in the background so the first timeline
                    // snapshot is not gated on a global, all-accounts relay
                    // round-trip (#441). Lifecycle-bound to this conversation:
                    // cancelled when start() is cancelled (user leaves). The live
                    // group-state + timeline subscriptions below still fold in
                    // peer commits as they arrive.
                    launch { appState.catchUpAccounts() }
                    // Local NIP-40 enforcement (#333) + secure delete (#334): on open
                    // and then at the next loaded row's expiry boundary, securely wipe
                    // plaintext past the retention window via the engine and re-publish
                    // so rows leave the open timeline while the user is still watching.
                    // The timer is lifecycle-bound to this conversation and is
                    // rescheduled by timeline/group-state publishes; when there is no
                    // loaded row near expiry it falls back to the old slow cadence.
                    launch { runForegroundDisappearingMessageSweep(account) }
                    runConversationSubscriptionLoop(account)
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
                markActiveAccountRemovedFromMembers(account)
                isLoading = false
                error = null
                return
            }
            isLoading = false
            error = throwable.message ?: throwable.javaClass.simpleName
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

    private suspend fun runForegroundDisappearingMessageSweep(account: String) {
        while (coroutineContext.isActive) {
            if (group.disappearingMessageSecs > 0uL) {
                lastForegroundSweepStartedAtMillis = System.currentTimeMillis()
                // Refresh references before pruning so evictExpiredMediaCaches can
                // map a pruned attachment's ciphertext back to its in-memory (L1)
                // plaintext/thumbnail entries. The open-snapshot path may not have
                // loaded them yet when this first sweep runs, and listMedia must be
                // read before secureDeleteExpired removes the rows.
                refreshMediaReferences()
                runCatching {
                    appState.marmotIo { secureDeleteExpired(account, group.groupIdHex) }
                }.onSuccess { result ->
                    // When the engine actually pruned rows, clear the
                    // conversation's accumulating tray card so it can't keep
                    // pointing at a now-vanished message. #333.
                    if (result.prunedMessages > 0uL) {
                        appState.dismissConversationNotifications(account, group.groupIdHex)
                    }
                    evictExpiredMediaCaches(account, result.mediaCiphertextSha256.toSet())
                }.onFailure { it.rethrowIfCancellation() }
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

    private fun foregroundDisappearingSweepDelayMillis(): Long =
        DisappearingMessageSweep.nextForegroundSweepDelayMillis(
            nowMillis = System.currentTimeMillis(),
            disappearingMessageSecs = group.disappearingMessageSecs,
            timelineAtSeconds = foregroundSweepTimelineAtSeconds(),
        )

    private fun foregroundSweepTimelineAtSeconds(): List<ULong> =
        buildList {
            optimisticMessages.values.forEach { add(it.record.recordedAt) }
            timelineItemsById.values.forEach { add(it.record.recordedAt) }
        }

    private fun shouldRunForegroundSweepAfterWake(wakeSignalReceived: Boolean): Boolean =
        DisappearingMessageSweep.shouldRunForegroundSweepAfterWake(
            wakeSignalReceived = wakeSignalReceived,
            nowMillis = System.currentTimeMillis(),
            lastSweepStartedAtMillis = lastForegroundSweepStartedAtMillis,
            disappearingMessageSecs = group.disappearingMessageSecs,
            timelineAtSeconds = foregroundSweepTimelineAtSeconds(),
        )

    private fun signalForegroundSweepScheduleChanged() {
        foregroundSweepScheduleSignals.trySend(Unit)
    }

    suspend fun closeLiveSubscriptionsForAccountTeardown(accountRef: String) {
        if (!shouldTeardownLiveSubscriptionsForAccount(accountRef, conversationAccountRef, conversationAccountRef)) return
        val teardown =
            synchronized(liveSubscriptionLock) {
                accountTeardownRequested = true
                val current = Triple(groupStateSubscription, timelineSubscription, startJob)
                groupStateSubscription = null
                timelineSubscription = null
                startJob = null
                current
            }
        val (groupSubscription, timelineStream, job) = teardown
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { groupSubscription?.close() }
            runCatching { timelineStream?.close() }
        }
        if (shouldCancelLiveSubscriptionJob(job, coroutineContext[Job])) {
            job?.cancelAndJoin()
        }
    }

    private fun isAccountTeardownRequested(): Boolean = synchronized(liveSubscriptionLock) { accountTeardownRequested }

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

    // Apply a fresh group-state snapshot/update, republishing the timeline when
    // the disappearing-message window changed. A controller seeded from
    // emptyGroupRecord() starts at retention 0, so the first real snapshot can
    // flip it on — without this republish the expiry filter wouldn't apply until
    // the next 60s sweep, flashing expired messages for up to a minute (#674).
    private fun applyGroupState(update: AppGroupRecordFfi) {
        val previousRetention = group.disappearingMessageSecs
        group = update
        if (previousRetention != update.disappearingMessageSecs) {
            publishTimelineFromIndexes()
        }
    }

    private suspend fun runGroupStateSubscriptionLoop(groupStream: GroupStateSubscription) {
        while (coroutineContext.isActive) {
            val update =
                withContext(Dispatchers.IO) {
                    groupStream.next()
                } ?: break
            applyGroupState(update)
            refreshMembers()
        }
    }

    private suspend fun closeConversationSubscriptionHandles(
        groupSubscription: GroupStateSubscription?,
        timelineStream: TimelineMessagesSubscription?,
    ) {
        synchronized(liveSubscriptionLock) {
            if (groupStateSubscription === groupSubscription) {
                groupStateSubscription = null
            }
            if (timelineSubscription === timelineStream) {
                timelineSubscription = null
            }
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
        val closingSubscription =
            synchronized(liveSubscriptionLock) {
                val current = timelineSubscription
                timelineSubscription = null
                current
            }
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
        // Per-send latency trace (issue #913). One-run-only sequence id + a
        // monotonic start so the phases of THIS send are correlatable in logcat
        // and back-to-back sends stay distinguishable, without any durable id.
        val trace = SendTrace.nextSequence()
        val traceStartMs = traceNowMs()
        sendTrace(trace, "accepted", 0L, null, "reply" to (replyTarget != null))
        val tempId = UUID.randomUUID().toString()
        rememberSendTrace(tempId, trace, traceStartMs)
        val now = nowSeconds()
        val optimistic =
            AppMessageRecordFfi(
                messageIdHex = tempId,
                direction = "sent",
                groupIdHex = group.groupIdHex,
                sender = conversationAccountIdHex ?: "",
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
        // Bump the chat-list row's preview in the same synchronous block as the
        // bubble, so a back-navigation to the list paints the new last-message
        // instead of a one-frame flash of the prior one (#900). Reuses the
        // already-parsed markdown from the optimistic record.
        appState.applyOptimisticSentPreview(
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
            ),
        )
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
        sendTrace(trace, "optimistic-shown", traceElapsedMs(traceStartMs))
        try {
            // Publish with a bounded retry sweep so a *transient* relay-pool gap
            // (socket teardown mid-reconnect, doze wake, network change) doesn't
            // surface as a user-visible "send failed" the instant the pool looks
            // empty (issue #294). A terminal/logic error fails on the first
            // attempt; only a sustained connectivity outage — every attempt
            // exhausted — keeps the hard failure. The optimistic bubble stays
            // Pending across retries, so the user sees "sending", not "failed".
            //
            // The commit lock serializes commit-producing FFI calls for the same
            // (account, group): a second back-to-back send BLOCKS here until the
            // prior send's full MLS-commit → relay round-trip returns. Timing the
            // lock-acquire separately from the FFI call makes that serialization
            // visible (issue #913 "back-to-back sends don't pipeline").
            val lockWaitStartMs = traceNowMs()
            val summary =
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    val lockHeldAtMs = traceNowMs()
                    sendTrace(
                        trace,
                        "commit-lock-acquired",
                        traceElapsedMs(traceStartMs),
                        lockHeldAtMs - lockWaitStartMs,
                    )
                    publishTextWithRetry(replyTarget, account, trimmed, trace, traceStartMs)
                }
            val confirmedId = summary.messageIds.firstOrNull() ?: tempId
            val confirmed = optimistic.copy(messageIdHex = confirmedId)
            if (confirmedId.isNotEmpty()) messageById[confirmedId] = confirmed
            optimisticMessages.remove(optimisticKey)
            messageById.remove(tempId)
            val insertedSent = shouldInsertSentOptimisticMessage(confirmedId, projectedMessageIds)
            if (insertedSent) {
                optimisticMessages["msg:$confirmedId"] =
                    TimelineMessage(
                        "msg:$confirmedId",
                        confirmed,
                        MessageStatus.Sent,
                        timelineOrder = optimisticOrder,
                    )
            }
            publishTimelineFromIndexes()
            // The publish returned and the pending clock flips to sent here (or,
            // when the engine echo already landed, the reconcile in
            // upsertProjectedRecord did it first — traced separately). `+…ms` is
            // the total accepted → sent-flip latency the issue measures.
            sendTrace(
                trace,
                "sent-flip",
                traceElapsedMs(traceStartMs),
                context = arrayOf("bubble" to (if (insertedSent) "local" else "echoed")),
            )
            // This success path removed the pending optimistic directly, so the
            // echo-reconcile may never fire for this temp id — drop its trace
            // entry so the bounded map doesn't retain it.
            forgetSendTrace(tempId)
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
            sendTrace(
                trace,
                "send-failed",
                traceElapsedMs(traceStartMs),
                context = arrayOf("error" to throwable.javaClass.simpleName),
            )
            forgetSendTrace(tempId)
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
        trace: String,
        traceStartMs: Long,
    ): dev.ipf.marmotkit.SendSummaryFfi {
        var lastTransient: Throwable? = null
        for (attempt in 1..SEND_RETRY_ATTEMPTS) {
            // Time the FFI hop itself (App → engine `send_message`: MLS commit +
            // encrypt + publish + relay ack round-trip, all synchronous inside
            // this call). This is the primary "long pole" candidate the issue
            // asks to measure — how long the `sendText`/`replyToMessage` call
            // blocks before returning (issue #913).
            val ffiStartMs = traceNowMs()
            sendTrace(trace, "ffi-start", ffiStartMs - traceStartMs, context = arrayOf("attempt" to attempt))
            try {
                val summary =
                    if (replyTarget != null) {
                        appState.marmotIo { replyToMessage(account, group.groupIdHex, replyTarget, trimmed) }
                    } else {
                        appState.marmotIo { sendText(account, group.groupIdHex, trimmed) }
                    }
                sendTrace(
                    trace,
                    "ffi-return",
                    traceElapsedMs(traceStartMs),
                    traceNowMs() - ffiStartMs,
                    "attempt" to attempt,
                    "msgIds" to summary.messageIds.size,
                )
                return summary
            } catch (throwable: Throwable) {
                throwable.rethrowIfCancellation()
                sendTrace(
                    trace,
                    "ffi-error",
                    traceElapsedMs(traceStartMs),
                    traceNowMs() - ffiStartMs,
                    "attempt" to attempt,
                    "error" to throwable.javaClass.simpleName,
                )
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
                sender = conversationAccountIdHex ?: "",
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
                    appState.withGroupCommitLock(account, group.groupIdHex) {
                        appState.marmotIo {
                            sendMediaAttachments(account, group.groupIdHex, references, retained.caption)
                        }
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
                val sessionStillValid =
                    shouldAcceptMediaUploadForAccount(
                        account,
                        mediaUploadSessionEpoch,
                        appState.activeAccountRef,
                        appState.mediaUploadSessionEpoch(),
                    )
                if (confirmedId.isNotEmpty() && sessionStillValid) {
                    retained.attachments.forEachIndexed { index, attachment ->
                        val confirmedKey = mediaCacheKey(account, confirmedId, index)
                        appState.mediaPlaintextCache.put(confirmedKey, attachment.plaintextBytes)
                        MediaPipeline
                            .decodeSampledBitmap(attachment.plaintextBytes, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
                            ?.let { appState.mediaThumbnailCache.put(confirmedKey, it) }
                        val bytesToPersist = attachment.plaintextBytes
                        val cacheGeneration = appState.diskMediaCache.generation()
                        // Tag with the uploaded blob's ciphertext hash so the
                        // expiry sweep can wipe this self-sent entry from disk by
                        // hash even after a restart / when its row isn't loaded.
                        val ciphertextTag = references.getOrNull(index)?.ciphertextSha256
                        appState.launchMutation {
                            withContext(Dispatchers.IO) {
                                appState.diskMediaCache.put(confirmedKey, bytesToPersist, cacheGeneration, ciphertextTag)
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
        val account = conversationAccountRef ?: return
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
        var reactionCommitted = false
        try {
            appState.withGroupCommitLock(account, group.groupIdHex) {
                if (alreadyMine) {
                    // Retract just the tapped emoji by deleting its own reaction
                    // event; the FFI unreact is target-only and clears the latest
                    // reaction, so it would drop the wrong emoji when a user holds
                    // more than one on the same message.
                    // activeAccountRef can be set while the account row hasn't loaded
                    // into `accounts` yet; surface that distinctly from an ambiguous
                    // reaction so the failure reason isn't misleading.
                    val me =
                        conversationAccountIdHex
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
                    reactionCommitted = true
                }
            }
        } catch (throwable: Throwable) {
            throwable.rethrowIfCancellation()
            optimisticReactionChanges.remove(optimisticId)
            recomputeReactions()
            appState.present(R.string.toast_reaction_failed, AppText.Plain(throwable.message ?: throwable.javaClass.simpleName))
        }
        if (reactionCommitted) {
            // Reacting is unambiguous evidence the user saw this message, so
            // advance the read marker through it. Keep this best-effort and
            // outside the reaction commit rollback path: a read-marker failure
            // must not remove a reaction that has already been published.
            runCatching { markReadUpTo(target) }
                .onFailure {
                    it.rethrowIfCancellation()
                    Log.w("DMConversation", "mark-read after reaction failed target=${target.take(8)}", it)
                }
        }
    }

    suspend fun deleteMessage(message: AppMessageRecordFfi) {
        val account = conversationAccountRef ?: return
        if (!canSendMessages) return
        val target = message.messageIdHex.takeIf { it.isNotBlank() } ?: return
        deletedMessageIds = deletedMessageIds + target
        try {
            appState.withGroupCommitLock(account, group.groupIdHex) {
                appState.marmotIo { deleteMessage(account, group.groupIdHex, target) }
            }
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
            appState.present(R.string.toast_couldnt_edit_message, AppText.Plain(throwable.message ?: throwable.javaClass.simpleName))
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

    // Evict decrypted media (L1 plaintext, decoded thumbnails, L2 disk) for the
    // attachments the engine just secure-deleted on expiry, matched by ciphertext
    // hash through the cached references. Without this the decrypted bytes stay
    // recoverable from the local caches after the row is gone (#674 review).
    private suspend fun evictExpiredMediaCaches(
        account: String,
        expiredCiphertextSha256: Set<String>,
    ) {
        if (expiredCiphertextSha256.isEmpty()) return
        // Map expired hashes to cache keys via the loaded references. Covers the
        // in-memory tiers (L1 plaintext + decoded thumbnails, keyed by cache key
        // and session-scoped, so anything resident is in mediaReferences) and the
        // on-disk entries for loaded messages — including untagged ones such as
        // self-sent media seeded at send time before its ciphertext hash existed.
        val loadedKeys =
            mediaReferences.flatMap { (messageIdHex, refs) ->
                refs.mapIndexedNotNull { index, ref ->
                    if (ref.ciphertextSha256 in expiredCiphertextSha256) mediaCacheKey(account, messageIdHex, index) else null
                }
            }
        loadedKeys.forEach { key ->
            appState.mediaPlaintextCache.remove(key)
            appState.mediaThumbnailCache.remove(key)
        }
        withContext(Dispatchers.IO) {
            loadedKeys.forEach { appState.diskMediaCache.remove(it) }
            // Plus any disk entry stamped with an expired ciphertext tag — the
            // only path that reaches media whose message isn't currently loaded
            // (no mediaReferences entry), including across process restarts. #674 review.
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
        if (appState.mediaPlaintextCache.get(cacheKey) != null) return true
        return appState.diskMediaCache.contains(cacheKey)
    }

    // Resolve-time SSRF guard before the native Blossom fetch. The imeta gate
    // only validates the literal host, so an attacker's public-looking locator
    // name can still resolve to loopback / RFC-1918. The native fetch re-resolves,
    // so this blocks the common static case (not an active mid-connection
    // rebind), matching the avatar/profile fetchers. The decision lives in
    // MediaReferenceParser so it's unit-testable with an injected resolver.
    private suspend fun assertMediaLocatorsResolveSafe(reference: MediaAttachmentReferenceFfi) {
        val unsafeHost =
            withContext(Dispatchers.IO) {
                MediaReferenceParser.firstUnsafeFetchableLocatorHost(reference.locators) { host ->
                    runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()
                }
            }
        if (unsafeHost != null) error("blocked private/loopback media locator")
    }

    /**
     * Fetch and decrypt a Blossom-stored attachment. Backed by the app-level
     * LRU ([WhiteNoiseAppState.mediaPlaintextCache], keyed via [mediaCacheKey])
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
                        assertMediaLocatorsResolveSafe(reference)
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
                    // lifetime as the FFI fetch). Tag the entry with the
                    // attachment's ciphertext hash so the expiry sweep can wipe
                    // it from disk later even if its message isn't loaded then.
                    withContext(Dispatchers.IO) {
                        appState.diskMediaCache.put(cacheKey, plaintext, cacheGeneration, reference.ciphertextSha256)
                    }
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
            // Tag with the uploaded blob's ciphertext hash (captured at upload)
            // so hash-based expiry eviction reaches this entry across sessions.
            val ciphertextTag = retained.uploadedReferences?.getOrNull(index)?.ciphertextSha256
            appState.launchMutation {
                withContext(Dispatchers.IO) {
                    appState.diskMediaCache.put(cacheKey, bytesToPersist, cacheGeneration, ciphertextTag)
                }
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
                optimisticMessages.remove(key)
                messageById.remove(tempId)
                val projectedAction = TimelineProjector.toAppMessageRecord(committedProjection)
                val projectedRecord =
                    projectedAction.withRecordedAtOverride(
                        localTimelineTimestampOverrides[committedProjection.messageIdHex],
                    )
                messageById[committedProjection.messageIdHex] = projectedRecord
                if (discardedDuringRetry.remove(key)) {
                    publishTimelineFromIndexes()
                    return
                }
                publishTimelineFromIndexes()
                return
            }
            val summary =
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    if (replyTarget != null) {
                        appState.marmotIo { replyToMessage(account, group.groupIdHex, replyTarget, text) }
                    } else {
                        appState.marmotIo { sendText(account, group.groupIdHex, text) }
                    }
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
            val account = conversationAccountRef ?: return false
            if (!canLeaveGroup) {
                appState.present(R.string.toast_make_another_admin_before_leaving, R.string.toast_group_needs_admin)
                return false
            }
            var demotedBeforeLeave = false
            runCatching {
                val activeAccountIdHex = conversationAccountIdHex
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    if (GroupProjector.requiresSelfDemoteBeforeLeave(group, activeAccountIdHex, members.size)) {
                        val demoteResult =
                            appState.marmotIo { selfDemoteAdminDetailed(account, group.groupIdHex) }
                        demotedBeforeLeave = true
                        applyMutationDetails(account, demoteResult.details)
                    }
                    appState.marmotIo { leaveGroup(account, group.groupIdHex) }
                }
                // Authoritative local self-leave: record it before the
                // synchronous snapshot drop so any subsequent
                // refreshMembers()/applyGroupDetails() round-trip that still
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
                    appState.presentText(AppText.Resource(R.string.toast_left_named, listOf(name)))
                } else {
                    appState.present(R.string.toast_left_chat)
                }
                true
            }.getOrElse {
                if (it is CancellationException) throw it
                val message = mutationError(it)
                lastMutationError = message
                if (demotedBeforeLeave) {
                    appState.present(R.string.toast_demoted_but_couldnt_leave, AppText.Plain(message))
                } else {
                    appState.present(R.string.toast_couldnt_leave_chat, AppText.Plain(message))
                }
                false
            }
        }

    fun dismissConversationNotifications() {
        val account = conversationAccountRef ?: return
        appState.dismissConversationNotifications(account, group.groupIdHex)
    }

    suspend fun acceptInvite(notify: Boolean = true): Boolean =
        withMutationLockResult(false) {
            val account = conversationAccountRef ?: return@withMutationLockResult false
            runCatching {
                group = appState.marmotIo { acceptGroupInvite(account, group.groupIdHex) }
                appState.applyLocalGroupUpdate(group)
                appState.dismissConversationNotifications(account, group.groupIdHex)
                // Accepting an invite (re-)joins the group, so clear any stale
                // local self-left latch before refreshMembers() so applyGroupDetails
                // is allowed to add self back to the roster (issue #787).
                selfMembership.clearSelfLeft()
                refreshMembers()
                refreshCurrentTimeline(account).forEach { streamId ->
                    if (activeStreamIds.add(streamId)) {
                        inviteStreamScope.launch { watchAgentTextStream(account, streamId) }
                    }
                }
                initializeReadState(account)
                if (notify) appState.present(R.string.toast_invite_accepted)
                true
            }.getOrElse {
                it.rethrowIfCancellation()
                appState.present(R.string.toast_couldnt_accept_invite, AppText.Plain(it.message ?: it.javaClass.simpleName))
                false
            }
        }

    suspend fun declineInvite(): Boolean =
        withMutationLockResult(false) {
            val account = conversationAccountRef ?: return@withMutationLockResult false
            runCatching {
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
            val account = conversationAccountRef ?: return@withMutationLockResult false
            runCatching {
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    val updated = appState.marmotIo { setGroupArchived(account, group.groupIdHex, archived) }
                    group = updated
                    appState.applyLocalGroupUpdate(updated)
                }
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
            val account = conversationAccountRef ?: return@withMutationLockResult false
            val updatedName = name.trim().takeIf { it.isNotEmpty() }
            val updatedDescription = description.trim().takeIf { it.isNotEmpty() }
            runCatching {
                appState.marmotIo {
                    updateGroupProfile(
                        account,
                        group.groupIdHex,
                        updatedName,
                        updatedDescription,
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
            val account = conversationAccountRef ?: return@withMutationLockResult false
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
            val account = conversationAccountRef ?: return@withMutationLockResult false
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
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    val inviteResult =
                        appState.marmotIo { inviteMembersDetailed(account, group.groupIdHex, refs) }
                    applyMutationDetails(account, inviteResult.details)
                    inviteSent = true
                    adminTargets.forEach { target ->
                        val promoteResult =
                            appState.marmotIo { promoteAdminDetailed(account, group.groupIdHex, target) }
                        applyMutationDetails(account, promoteResult.details)
                    }
                }
                appState.present(R.string.toast_invite_sent)
                true
            } catch (throwable: Throwable) {
                throwable.rethrowIfCancellation()
                val message = mutationError(throwable)
                if (inviteSent && addAsAdmin) {
                    // The invite is already out; keep the UI honest about the
                    // partial success and leave the row-level Admin switch as the
                    // retry path once the member appears in details.
                    lastMutationError = "Invite sent, but admin promotion failed: $message"
                    appState.present(R.string.toast_invite_sent_but_couldnt_add_admin, AppText.Plain(message))
                    true
                } else if (isDuplicateSignatureKeyError(message)) {
                    // MLS rejected the add commit because the proposed member
                    // already holds a seat (or their signing key collides with
                    // an existing member's). The UI pre-checks membership, but a
                    // race or key collision can still land here — surface plain
                    // language with the resolved name instead of the raw
                    // CreateCommitError(ProposalValidationError(...)) enum (#899).
                    val name = duplicateSignatureKeyDisplayName(refs, appState::displayName)
                    val friendly = copy.couldntAddMemberDuplicate(name)
                    lastMutationError = friendly
                    appState.present(R.string.toast_couldnt_add_members, AppText.Plain(friendly))
                    false
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
            val account = conversationAccountRef ?: return@withMutationLockResult false
            // remove_members signs a roster update for a Nostr pubkey, so use the
            // stable member id. memberRef may be a local account label.
            val target = member.memberIdHex
            try {
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    val result =
                        appState.marmotIo { removeMembersDetailed(account, group.groupIdHex, listOf(target)) }
                    applyMutationDetails(account, result.details)
                }
                appState.present(R.string.toast_member_removed)
                true
            } catch (throwable: Throwable) {
                throwable.rethrowIfCancellation()
                // The MLS roster commit can persist locally even when a follow-on
                // phase (relay directory fetch / runtime catch-up) fails with a
                // transient backend-busy lock — e.g. a concurrent read kicked off
                // as the user back-navigates out of the members list. Re-read engine
                // truth before reporting: if the target is actually gone the removal
                // succeeded, so converge the list in place and suppress the phantom
                // failure toast rather than claiming an error that didn't happen.
                refreshMembers()
                if (members.none { it.memberIdHex.equals(target, ignoreCase = true) }) {
                    appState.present(R.string.toast_member_removed)
                    true
                } else {
                    val message = mutationError(throwable)
                    lastMutationError = message
                    appState.present(R.string.toast_couldnt_remove_member, AppText.Plain(message))
                    false
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
            if (!admin && isAdmin(member) && group.admins.distinctBy { it.lowercase() }.size <= 1) {
                appState.present(R.string.toast_keep_one_admin, R.string.toast_promote_before_removing_admin)
                return@withMutationLockResult false
            }
            runCatching {
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    if (admin) {
                        val result =
                            appState.marmotIo { promoteAdminDetailed(account, group.groupIdHex, target) }
                        applyMutationDetails(account, result.details)
                        appState.present(R.string.toast_admin_added)
                    } else {
                        val result =
                            appState.marmotIo { demoteAdminDetailed(account, group.groupIdHex, target) }
                        applyMutationDetails(account, result.details)
                        appState.present(R.string.toast_admin_removed)
                    }
                }
                true
            }.onFailure {
                it.rethrowIfCancellation()
                val message = mutationError(it)
                lastMutationError = message
                appState.present(R.string.toast_couldnt_update_admin, AppText.Plain(message))
            }.getOrDefault(false)
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
            runCatching {
                appState.marmotIo { updateMessageRetention(account, group.groupIdHex, disappearingMessageSecs) }
                val previousRetention = group.disappearingMessageSecs
                group = group.copy(disappearingMessageSecs = disappearingMessageSecs)
                // The engine prunes plaintext older than the new window during the
                // call above. Reload the open timeline so the admin who just set
                // the timer sees the pruned state immediately, instead of only
                // after leaving and re-entering the chat. The retention change has
                // already succeeded, so a refresh failure must NOT flip this to a
                // failure toast — log it and fall back to an in-memory re-filter.
                runCatching { refreshCurrentTimeline(account) }
                    .onFailure { refreshError ->
                        refreshError.rethrowIfCancellation()
                        Log.w("DMConversation", "refresh after retention update failed for ${group.groupIdHex.take(8)}", refreshError)
                        publishTimelineFromIndexes()
                    }
                appState.present(R.string.toast_disappearing_messages_updated)
                true
            }.onFailure {
                it.rethrowIfCancellation()
                val message = mutationError(it)
                lastMutationError = message
                appState.present(R.string.toast_couldnt_update_disappearing, AppText.Plain(message))
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
            runCatching {
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    val result = appState.marmotIo { selfDemoteAdminDetailed(account, group.groupIdHex) }
                    applyMutationDetails(account, result.details)
                }
                appState.present(R.string.toast_admin_removed)
                true
            }.onFailure {
                it.rethrowIfCancellation()
                val message = mutationError(it)
                lastMutationError = message
                appState.present(R.string.toast_couldnt_update_admin, AppText.Plain(message))
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
                appState.present(R.string.toast_couldnt_update_admin, R.string.toast_cant_transfer_admin)
                return@withMutationLockResult false
            }
            // Tracks whether the grant landed before the self-demote attempt so
            // a self-demote failure reports the partial state honestly.
            var grantedBeforeDemote = false
            runCatching {
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    val promoteResult =
                        appState.marmotIo { promoteAdminDetailed(account, group.groupIdHex, target) }
                    grantedBeforeDemote = true
                    applyMutationDetails(account, promoteResult.details)
                    // The grant has already landed on the MLS group. If the scope is
                    // cancelled now, skipping the demote would strand both accounts as
                    // admin without telling the caller (rethrowIfCancellation below
                    // jumps past the partial-state branch). Run it to completion.
                    withContext(NonCancellable) {
                        val demoteResult =
                            appState.marmotIo { selfDemoteAdminDetailed(account, group.groupIdHex) }
                        applyMutationDetails(account, demoteResult.details)
                    }
                }
                appState.present(R.string.toast_admin_transferred)
                true
            }.onFailure {
                it.rethrowIfCancellation()
                val message = mutationError(it)
                lastMutationError = message
                if (grantedBeforeDemote) {
                    // Target is now an admin but we couldn't step down. Tell the user
                    // so they can retry the step-down (or revoke the grant).
                    appState.present(R.string.toast_granted_but_couldnt_step_down, AppText.Plain(message))
                } else {
                    appState.present(R.string.toast_couldnt_update_admin, AppText.Plain(message))
                }
            }.getOrDefault(false)
        }

    fun isAdmin(member: AppGroupMemberRecordFfi): Boolean = GroupProjector.isAdmin(group, member)

    fun memberDisplayName(member: AppGroupMemberRecordFfi): String = appState.displayName(member.memberIdHex)

    fun memberSubtitle(member: AppGroupMemberRecordFfi): String = appState.shortNpub(member.memberIdHex)

    fun memberAvatarUrl(member: AppGroupMemberRecordFfi): String? = appState.avatarUrl(member.memberIdHex)

    suspend fun groupMlsState(): AppGroupMlsStateFfi? {
        val account = conversationAccountRef ?: return null
        return runCatching {
            appState.marmotIo { groupMlsState(account, group.groupIdHex) }
        }.onFailure {
            if (it is CancellationException) throw it
            appState.present(R.string.toast_couldnt_load_mls_state, AppText.Plain(it.message ?: it.javaClass.simpleName))
        }.getOrNull()
    }

    suspend fun exportConversationTranscriptFile(cacheDir: java.io.File): java.io.File? {
        val account = conversationAccountRef ?: return null
        // One timestamp for the whole export so the JSON `exported_at` and the
        // file name stamp match instead of drifting across two now() reads.
        val exportedAt = java.time.Instant.now()
        return runCatching {
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
            it.rethrowIfCancellation()
            appState.present(R.string.toast_couldnt_export_transcript, AppText.Plain(it.message ?: it.javaClass.simpleName))
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

    private suspend fun applyTimelinePage(
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
            // Same unbounded-growth trim for the optimistic position/timestamp
            // overrides: the reconcile path below re-adds them for any optimistic
            // message that reconciles into this page, so scrolled-out entries
            // don't accumulate for the controller's lifetime.
            localTimelineOrderOverrides.keys.retainAll(optimisticIds)
            localTimelineTimestampOverrides.keys.retainAll(optimisticIds)
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
        // Materialize local profile presentations for everyone this page
        // references (message authors, reply-preview authors, reaction authors)
        // and AWAIT it before the publish below kicks off the first composition.
        // The requestProfile calls above are the gated *relay* refresh (network);
        // this is the ungated *local* read each row would otherwise lazily fire
        // on its own first paint. Awaiting it (rather than fire-and-forget) is
        // what actually closes the per-row sender name/avatar hydration flicker
        // for already-on-device history: a launch-and-return warm races this
        // synchronous publish and can still lose, so the row's first frame would
        // observe ProfilePresentation.Empty and pop the name/avatar in a frame
        // later. Blocking here guarantees the cache is populated before publish,
        // so the first composition paints the sender metadata. See #609.
        appState.warmProfilePresentationsBlocking(timelineRecordProfileSenders(page.messages))
        if (updatePagination) {
            hasMoreBefore = page.hasMoreBefore
        }
        pruneConfirmedOptimisticMessages()
        pruneConfirmedOptimisticReactions()
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
        // The engine streams Upserts for new messages but never Removes ones that
        // scroll out, so the live indexes grow without bound for a conversation
        // kept open while messages arrive. Cap the live window to the newest
        // entries; scrolling up triggers loadOlder(), whose replaceWindow rebuild
        // restores the authoritative window. Skipped once the user has expanded
        // the window via loadOlder so deliberately-loaded history isn't trimmed.
        if (!hasLoadedOlderPages) {
            trimLiveTimelineWindow(LIVE_TIMELINE_WINDOW_CAP)
        }
        // Live Upsert/Projection batches add to messageById but never trim it;
        // prune to the (now-bounded) window + optimistic records so it doesn't
        // grow unbounded for an actively-watched conversation (#373).
        pruneMessageByIdToWindow(messageById, timelineRecords.keys, optimisticMessages.values)
        pruneOptimisticEditsToWindow()
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
        val markReadFailure =
            runCatching {
                appState.marmotIo { markTimelineMessageRead(account, group.groupIdHex, trimmed) }
            }.exceptionOrNull()
        if (markReadFailure != null) {
            if (lastReadMessageId == trimmed) lastReadMessageId = previous
            if (markReadFailure is CancellationException) throw markReadFailure
            Log.w(
                "DMConversation",
                "mark read failed for ${group.groupIdHex.take(8)} message=${trimmed.take(8)}",
                markReadFailure,
            )
            return
        }
        runCatching {
            appState.dismissConversationNotifications(account, group.groupIdHex)
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w("DMConversation", "dismiss read notifications failed for ${group.groupIdHex.take(8)}", it)
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
        val mine = conversationAccountIdHex?.lowercase() ?: return
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
                // The engine echo just flipped this pending bubble to a
                // projected record. If we're tracing this send, this is the
                // "self-echo drives the sent flip" path (issue #913).
                traceEchoReconcile(optimisticId)
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

    // Drop the oldest projected records beyond [maxItems], using the same total
    // order the publish sorts by, so only the newest window is retained.
    private fun trimLiveTimelineWindow(maxItems: Int) {
        val overflow = timelineItemsById.size - maxItems
        if (overflow <= 0) return
        timelineItemsById.values
            .sortedWith(::compareTimelineMessages)
            .take(overflow)
            .mapNotNull { it.projected?.messageIdHex }
            .forEach(::removeProjectedRecord)
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
        // Local NIP-40 enforcement (#333): hide messages past the per-group
        // retention window. expiry = recordedAt + window, mirroring the engine's
        // prune cutoff (recorded_at < now - secs) so the visible timeline matches
        // what a sync-time prune will permanently remove. A recent optimistic send
        // never matches (recordedAt ≈ now). Window 0 (off) filters nothing.
        val window = group.disappearingMessageSecs
        val live =
            if (window == 0uL) {
                // Timer off: skip the expiry scan/allocation fast path entirely.
                optimisticMessages.values + projected
            } else {
                val nowMillis = System.currentTimeMillis()
                (optimisticMessages.values + projected).filter {
                    !DisappearingMessageSweep.isLocallyExpired(
                        nowMillis = nowMillis,
                        disappearingMessageSecs = window,
                        timelineAtSeconds = it.record.recordedAt,
                    )
                }
            }
        val aggregated = aggregateEdits(live.map { it.record })
        // Drop any optimistic edit the real kind-1009 has now caught up to:
        // once `aggregateEdits` reports the same latest text, the overlay is
        // redundant and would otherwise mask a later remote edit. Failed/Pending
        // overlays are kept until they resolve through editMessage.
        optimisticEdits.entries
            .filter { (target, edit) -> edit.status == MessageStatus.Sent && aggregated[target]?.latestText == edit.text }
            .map { it.key }
            .forEach(optimisticEdits::remove)
        timeline =
            (live + streamDebugTimelineItems.values)
                .map { it.withOptimisticEditStatus() }
                .distinctBy { it.id }
                .sortedWith(::compareTimelineMessages)
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
        // Lowercased sender sets for the same casing-drift reason as
        // recomputeReactions(). See #143.
        val mine = conversationAccountIdHex?.lowercase()
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

    private suspend fun refreshMembers() {
        val account = conversationAccountRef ?: return
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
        val account = conversationAccountRef ?: return
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
        val activeAccountIdHex = conversationAccountIdHex ?: return
        // Engine-confirmed removal (UseAfterEviction). Record the same
        // authoritative local-left marker the leaveGroup() success path sets so
        // a later applyGroupDetails() that races ahead of eviction can't re-add
        // self (issue #787).
        recordSelfLeft()
        val updatedMembers =
            members.filterNot {
                GroupProjector.isActiveAccountMember(it, activeAccountIdHex)
            }
        members = updatedMembers
        membersLoaded = true
        membersVerified = true
        appState.cacheGroupMemberSnapshot(account, group.groupIdHex, updatedMembers)
    }

    /**
     * Latch the authoritative local self-leave marker (issue #787). Set on a
     * confirmed self-leave (leaveGroup success) or engine eviction
     * ([markActiveAccountRemovedFromMembers]); honoured by [isSelfMember] and
     * [applyGroupDetails] so a transient roster round-trip can't restore self
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
    ) {
        val previousRetention = group.disappearingMessageSecs
        val applied = applyAuthoritativeGroupDetails(details)
        group = applied.group
        if (previousRetention != group.disappearingMessageSecs) {
            publishTimelineFromIndexes()
        }
        // Once a self-leave has been recorded locally, refuse to re-add self
        // from a details round-trip that still predates the engine eviction —
        // otherwise the full roster (self included) would restore the member
        // count and re-enable the composer right after a leave (issue #787).
        members = selfMembership.rosterHonoringSelfLeft(applied.members, conversationAccountIdHex)
        membersLoaded = true
        membersVerified = true
        appState.cacheGroupMemberSnapshot(account, group.groupIdHex, members)
        appState.requestProfiles(members.map { it.memberIdHex })
    }

    private fun applyMutationDetails(
        account: String,
        details: GroupDetailsFfi,
    ) {
        applyGroupDetails(account, details)
        appState.applyLocalGroupUpdate(details.group)
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
                        updateStreamPreview(streamId, copy.streamFailed(update.message), MessageStatus.Failed)
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
                contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                kind = STREAM_DEBUG_KIND,
                tags = listOf(MessageProjector.streamTag(streamId), MessageTagFfi(listOf("dbg", event.eventKind))),
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
                    contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
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
                contentTokens = tokens ?: MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
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

    // --- Send-latency trace (issue #913) -----------------------------------
    // Monotonic clock (unaffected by wall-clock adjustments) for measuring the
    // accepted → sent-flip window and the FFI hop inside it. All emission is
    // DEBUG-only and privacy-safe (see SendTrace): phase, sequence id, ms, and
    // small counts/booleans only.
    private fun traceNowMs(): Long = SystemClock.elapsedRealtime()

    private fun traceElapsedMs(startMs: Long): Long = traceNowMs() - startMs

    private fun sendTrace(
        sequence: String,
        phase: String,
        sinceStartMs: Long,
        spanMs: Long? = null,
        vararg context: Pair<String, Any?>,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            "DMConversation",
            "${SendTrace.TAG_PREFIX} ${SendTrace.line(sequence, phase, sinceStartMs, spanMs, *context)}",
        )
    }

    // Record (DEBUG-only) the trace start for an optimistic text send so the
    // engine-echo reconcile can time the accepted → echoed-reconcile flip.
    // Bounded so a burst of never-echoed sends can't grow the map.
    private fun rememberSendTrace(
        tempId: String,
        sequence: String,
        startMs: Long,
    ) {
        if (!BuildConfig.DEBUG) return
        sendTraceByTempId[tempId] = SendTraceEntry(sequence, startMs)
        while (sendTraceByTempId.size > SEND_TRACE_MAX_TRACKED) {
            val oldest = sendTraceByTempId.keys.firstOrNull() ?: break
            sendTraceByTempId.remove(oldest)
        }
    }

    private fun forgetSendTrace(tempId: String) {
        if (!BuildConfig.DEBUG) return
        sendTraceByTempId.remove(tempId)
    }

    // Called when the engine echo reconciles a pending optimistic bubble into a
    // projected record. If the reconciled optimistic id is one we're tracing,
    // log the accepted → echoed-reconcile latency — this is the path that flips
    // pending → sent when the self-echo lands before/instead of the send()
    // success block, the "subscription churn / self-echo drives the flip"
    // candidate in issue #913.
    private fun traceEchoReconcile(optimisticId: String) {
        if (!BuildConfig.DEBUG) return
        val entry = sendTraceByTempId.remove(optimisticId) ?: return
        sendTrace(entry.sequence, "echo-reconcile", traceElapsedMs(entry.startMs))
    }

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
