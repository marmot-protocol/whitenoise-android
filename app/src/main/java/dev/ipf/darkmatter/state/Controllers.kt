package dev.ipf.darkmatter.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.darkmatter.BuildConfig
import dev.ipf.darkmatter.R
import dev.ipf.darkmatter.core.GroupProjector
import dev.ipf.darkmatter.core.MessageProjector
import dev.ipf.darkmatter.core.MessageTextCopy
import dev.ipf.darkmatter.core.ReactionTally
import dev.ipf.darkmatter.core.ReplyNavigation
import dev.ipf.darkmatter.core.TimelineProjector
import dev.ipf.darkmatter.media.ByteSizeLruCache
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ChatListItem(
    val group: AppGroupRecordFfi,
    val latest: AppMessageRecordFfi?,
    val otherMemberAccount: String?,
    val memberCount: Int,
    val memberSnapshot: GroupMemberSnapshot?,
    val projection: ChatListRowFfi? = null,
) {
    val id: String = group.groupIdHex

    val projectedTitle: String?
        get() = projection?.title?.takeIf { it.isNotBlank() }

    val latestAt: ULong?
        get() = projection?.lastMessage?.timelineAt ?: latest?.recordedAt

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
 * `memberCount` fields that `GroupProjector.displayTitle` reads — that's
 * how an unnamed group resolves to "Group of N people" or the other
 * member's display name instead of leaking the group hex into the UI.
 * Without it, both fields fall back to null/0 and the local projection
 * shows a short group hex until [ChatsController]'s async members fetch
 * fills the cache.
 */
internal fun chatListItemFromProjection(
    row: ChatListRowFfi,
    group: AppGroupRecordFfi? = null,
    activeAccountIdHex: String? = null,
    members: List<AppGroupMemberRecordFfi>? = null,
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
                    contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
                    kind = preview.kind,
                    tags = emptyList(),
                    recordedAt = preview.timelineAt,
                    receivedAt = preview.timelineAt,
                )
            },
        otherMemberAccount = otherMember,
        memberCount = memberCount,
        memberSnapshot = null,
        projection = row,
    )
}

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

data class TimelineMessage(
    val id: String,
    val record: AppMessageRecordFfi,
    val status: MessageStatus,
    val projected: TimelineMessageRecordFfi? = null,
    val timelineOrder: ULong = 0uL,
)

data class ReactionParticipant(
    val sender: String,
    val emoji: String,
    val reactedAt: ULong,
)

internal fun optimisticMessageIdForProjection(
    optimisticMessages: Collection<TimelineMessage>,
    projected: AppMessageRecordFfi,
    allowDelayedProjection: Boolean = false,
): String? {
    val projectedIsMedia = projected.tags.any { it.values.firstOrNull() == "imeta" }
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
        if (timeline[index].record.direction == "received") {
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
    return timeline.drop(anchorIdx + 1).count { it.record.direction == "received" }
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
private class RetainedMediaUpload(
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

    // Per-group member snapshots fetched via the `groupMembers` FFI.
    // The chat-list FFI doesn't include member rosters on each row, so
    // for unnamed groups we'd otherwise have nothing to feed the
    // GroupProjector.displayTitle fallback and the UI would leak the
    // raw group id hex. Filled lazily on first `recompute()` per group;
    // re-fetched on bind.
    private var memberCacheByGroup: Map<String, List<AppGroupMemberRecordFfi>> = emptyMap()

    // Tracks groups whose member fetch is currently in flight, so we don't
    // fan out duplicate work for the same group. Invariant: an id sits in
    // exactly one state at a time — pending (not in either set), in-flight
    // (here), or cached (in [memberCacheByGroup]). Entries are added in
    // [schedulePendingMemberFetches] and removed in the same coroutine's
    // `finally` so a failed fetch can be retried on the next recompute;
    // `bind()` clears the set alongside the cache to reset both at once.
    private val inFlightMemberFetches = mutableSetOf<String>()

    suspend fun bind(accountRef: String?) {
        chatsDebug { "bind account=${accountRef?.take(8)}" }
        this.accountRef = accountRef
        this.boundAccountRef = accountRef
        chatRows = emptyList()
        groupRecordsById = emptyMap()
        memberCacheByGroup = emptyMap()
        inFlightMemberFetches.clear()
        recompute()
        error = null

        if (accountRef == null) return
        isLoading = true
        var chatListSubscription: ChatListSubscription? = null
        var chatsSubscription: ChatsSubscription? = null
        try {
            val chatListStream = appState.marmotIo { subscribeChatList(accountRef, includeArchived = true) }
            chatListSubscription = chatListStream
            val chatStream = appState.marmotIo { subscribeChats(accountRef, includeArchived = true) }
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
            recompute()

            coroutineScope {
                launch {
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
                }
                launch {
                    while (isActive) {
                        val update =
                            withContext(Dispatchers.IO) {
                                chatStream.next()
                            } ?: break
                        requestGroupProfiles(update)
                        chatsDebug { "chat update account=${accountRef.take(8)} ${update.debugSummary()}" }
                        foldGroup(update)
                    }
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
            error = throwable.message ?: throwable.javaClass.simpleName
        } finally {
            withContext(Dispatchers.IO) {
                runCatching { chatListSubscription?.close() }
                runCatching { chatsSubscription?.close() }
            }
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
     * Flip the archived flag on `groupIdHex` from the chat-list surface
     * (swipe / long-press menu). Mirrors `ConversationController.setArchived`
     * but takes the id by parameter since the caller doesn't have an open
     * conversation. Standard mutation-lock + toast pattern; local group
     * record is updated immediately so the row reflows without waiting on
     * the projection echo.
     */
    suspend fun setArchived(
        groupIdHex: String,
        archived: Boolean,
    ): Boolean {
        val account = accountRef ?: return false
        return runCatching {
            val updated = appState.marmotIo { setGroupArchived(account, groupIdHex, archived) }
            appState.applyLocalGroupUpdate(updated)
            appState.present(if (archived) R.string.toast_chat_archived else R.string.toast_chat_restored)
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

    private fun recompute() {
        val me = appState.activeAccount?.accountIdHex
        val all =
            chatRows
                .map { row ->
                    chatListItemFromProjection(
                        row = row,
                        group = groupRecordsById[row.groupIdHex],
                        activeAccountIdHex = me,
                        members = memberCacheByGroup[row.groupIdHex],
                    )
                }.let(::sortChatListItems)
        items = all.filter { !it.group.archived }
        archivedItems = all.filter { it.group.archived }
        chatsDebug { "recompute visible=${items.size} archived=${archivedItems.size} total=${all.size}" }
        // For any unnamed group we don't yet have members cached for,
        // fan out a one-shot members fetch so the title can resolve from
        // the local projector. Only unnamed groups need this — named
        // groups display `group.name` directly without member data.
        schedulePendingMemberFetches()
    }

    /**
     * Walk the current chat rows and, for any unnamed group without
     * cached members or an in-flight fetch, kick off a `groupMembers`
     * FFI call. On success the cache updates and `recompute()` runs
     * again so the row reshuffles into its proper title.
     */
    private fun schedulePendingMemberFetches() {
        val account = accountRef ?: return
        val pending =
            chatRows
                .asSequence()
                .filter {
                    val groupName = groupRecordsById[it.groupIdHex]?.name ?: it.groupName
                    groupName.isBlank()
                }.map { it.groupIdHex }
                .filterNot { memberCacheByGroup.containsKey(it) }
                .filterNot { it in inFlightMemberFetches }
                .toList()
        if (pending.isEmpty()) return
        inFlightMemberFetches.addAll(pending)
        pending.forEach { groupIdHex ->
            appState.launchMutation {
                try {
                    val members = appState.marmotIo { groupMembers(account, groupIdHex) }
                    members
                        .map { it.memberIdHex }
                        .filter { it.isNotBlank() }
                        .forEach(appState::requestProfile)
                    memberCacheByGroup = memberCacheByGroup + (groupIdHex to members)
                    recompute()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Best-effort. Leave the cache empty so a future
                    // bind retries; the row falls back to the short
                    // hex projector branch until then.
                } finally {
                    inFlightMemberFetches.remove(groupIdHex)
                }
            }
        }
    }
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
    private val messageById = linkedMapOf<String, AppMessageRecordFfi>()
    private val timelineRecords = linkedMapOf<String, TimelineMessageRecordFfi>()
    private val timelineItemsById = linkedMapOf<String, TimelineMessage>()
    private val timelineOrder = mutableListOf<String>()
    private val optimisticMessages = appState.optimisticMessages(conversationAccountRef, initialGroup.groupIdHex)
    private val projectedMessageIds = appState.projectedMessageIds(conversationAccountRef, initialGroup.groupIdHex)
    private val localTimelineOrderOverrides = appState.timelineOrderOverrides(conversationAccountRef, initialGroup.groupIdHex)
    private val localTimelineTimestampOverrides =
        appState.timelineTimestampOverrides(conversationAccountRef, initialGroup.groupIdHex)
    private val optimisticReactionChanges = linkedMapOf<String, OptimisticReactionChange>()
    private val inviteStreamScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val activeStreamIds = mutableSetOf<String>()
    private val removedStreamIds = mutableSetOf<String>()
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
        var timelineSubscription: TimelineMessagesSubscription? = null
        var groupSubscription: GroupStateSubscription? = null
        try {
            val timelineStream =
                appState.marmotIo {
                    subscribeTimelineMessages(account, group.groupIdHex, ConversationTimelinePageLimit)
                }
            timelineSubscription = timelineStream
            val snapshot = withContext(Dispatchers.IO) { timelineStream.snapshot() }
            val snapshotStreamIds = snapshot?.let { applyTimelinePage(it, replaceWindow = true, updatePagination = true) }.orEmpty()
            refreshMediaReferences()
            initializeReadState(account)
            // Don't blanket-mark the absolute newest as read here — the UI
            // layer now drives mark-read as the user scrolls so partial-read
            // sessions retain accurate unread counts on the chat list.

            val groupStream = appState.marmotIo { subscribeGroupState(account, group.groupIdHex) }
            groupSubscription = groupStream
            val groupSnapshot =
                withContext(Dispatchers.IO) {
                    groupStream.snapshot()
                }
            groupSnapshot?.let { group = it }
            refreshMembers()
            isLoading = false

            coroutineScope {
                snapshotStreamIds.forEach { streamId ->
                    if (activeStreamIds.add(streamId)) {
                        launch { watchAgentTextStream(account, streamId) }
                    }
                }
                launch {
                    while (isActive) {
                        val update =
                            withContext(Dispatchers.IO) {
                                timelineStream.nextUpdate()
                            } ?: break
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
                        // New messages may include media; refresh the cached
                        // references so the bubble's download path finds the
                        // correct `sourceEpoch`. The `listMedia(group, null)`
                        // FFI scan is unbounded — gate it on "this update
                        // actually touches media" so text-only / reaction-only
                        // updates don't trigger a full-table scan every time
                        // (a real cost in media-heavy groups). The Page
                        // initial snapshot always refreshes (the cache is
                        // empty on first load anyway).
                        val touchedMedia =
                            when (update) {
                                is TimelineSubscriptionUpdateFfi.Page -> pageContainsMedia(update.page)
                                is TimelineSubscriptionUpdateFfi.Projection ->
                                    if (update.update.update.groupIdHex == group.groupIdHex) {
                                        changesContainMedia(update.update.update.changes)
                                    } else {
                                        false
                                    }
                            }
                        if (touchedMedia) refreshMediaReferences()
                        // Scroll-driven mark-read in the UI layer handles the
                        // user-visible read pointer. The projection-update
                        // path no longer force-marks the absolute newest as
                        // read; if the user is scrolled up reading older
                        // history, an incoming message must remain unread
                        // until they actually scroll to it.
                        streamIds.forEach { streamId ->
                            if (activeStreamIds.add(streamId)) {
                                launch { watchAgentTextStream(account, streamId) }
                            }
                        }
                    }
                }
                launch {
                    while (isActive) {
                        val update =
                            withContext(Dispatchers.IO) {
                                groupStream.next()
                            } ?: break
                        group = update
                        refreshMembers()
                    }
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
            inviteStreamScope.cancel()
            withContext(Dispatchers.IO) {
                runCatching { groupSubscription?.close() }
                runCatching { timelineSubscription?.close() }
            }
        }
    }

    suspend fun send(text: String) {
        val trimmed = text.trim()
        val account = conversationAccountRef ?: return
        if (trimmed.isEmpty()) return
        if (!canSendMessages) return

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
                contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
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
        optimisticMessages["msg:$tempId"] =
            TimelineMessage(
                "msg:$tempId",
                optimistic,
                MessageStatus.Pending,
                timelineOrder = optimisticOrder,
            )
        messageById[tempId] = optimistic
        publishTimelineFromIndexes()
        replyingTo = null
        try {
            val summary =
                if (replyTarget != null) {
                    appState.marmotIo { replyToMessage(account, group.groupIdHex, replyTarget, trimmed) }
                } else {
                    appState.marmotIo { sendText(account, group.groupIdHex, trimmed) }
                }
            val confirmedId = summary.messageIds.firstOrNull() ?: tempId
            val confirmed = optimistic.copy(messageIdHex = confirmedId)
            if (confirmedId.isNotEmpty()) messageById[confirmedId] = confirmed
            optimisticMessages.remove("msg:$tempId")
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
            optimisticMessages["msg:$tempId"] =
                TimelineMessage(
                    "msg:$tempId",
                    optimistic,
                    MessageStatus.Failed,
                    timelineOrder = optimisticOrder,
                )
            publishTimelineFromIndexes()
            appState.present(R.string.toast_send_failed, AppText.Plain(throwable.message ?: throwable.javaClass.simpleName))
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
        val account = conversationAccountRef ?: return
        if (!canSendMessages || attachments.isEmpty()) return
        if (attachments.any { it.plaintextBytes.isEmpty() }) return
        // Reject albums whose summed plaintext exceeds the retained-uploads
        // LRU cap BEFORE inserting an optimistic bubble. The cache's
        // evict-until-under-cap pass would otherwise drop the just-inserted
        // entry on its own oversize, and the user would see the bubble
        // immediately flip to "reattach to retry" with no retainable bytes.
        if (albumExceedsRetainedCap(attachments)) {
            appState.present(R.string.media_album_too_large)
            return
        }

        val tempId = UUID.randomUUID().toString()
        val key = "msg:$tempId"
        val now = nowSeconds()
        val trimmedCaption = caption?.trim()?.takeIf { it.isNotBlank() }
        // Body: the caption if present, otherwise a legible placeholder while
        // the upload runs. The real message arriving after publish carries the
        // imeta tags and supersedes this optimistic one.
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
                contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
                kind = 9uL,
                // One `_media_pending` tag per attachment. retryFailedSend
                // detects ANY of these as a media-retry trigger and re-runs
                // the whole album through performMediaUpload.
                tags =
                    attachments.map {
                        MessageTagFfi(listOf("_media_pending", it.fileName, it.mediaType))
                    },
                recordedAt = now,
                receivedAt = now,
            )
        val optimisticOrder = nextOptimisticTimelineOrder()
        // Retain the compressed bytes for the whole album so a failed upload
        // can be retried in place. uploadedReferences caches the Blossom
        // results so a publish-only failure doesn't re-upload every blob.
        retainedMediaUploads.put(key, RetainedMediaUpload(attachments, trimmedCaption))
        optimisticMessages[key] =
            TimelineMessage(
                key,
                optimistic,
                MessageStatus.Pending,
                timelineOrder = optimisticOrder,
            )
        messageById[tempId] = optimistic
        publishTimelineFromIndexes()
        performMediaUpload(account, key, tempId, optimisticOrder, optimistic)
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
        val retained =
            retainedMediaUploads.get(key) ?: run {
                // Bytes are gone (evicted under cap, or process death) — can't
                // retry without a re-attach. Leave the bubble Failed.
                optimisticMessages[key] = TimelineMessage(key, optimistic, MessageStatus.Failed, timelineOrder = order)
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
                                            dim = null,
                                            thumbhash = null,
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
                    appState.launchMutation {
                        withContext(Dispatchers.IO) { appState.diskMediaCache.put(confirmedKey, bytesToPersist) }
                    }
                }
            }
            retainedMediaUploads.remove(key)
            // Bridge the gap until the published event echoes back via the
            // projection: insert a confirmed *image* optimistic carrying the
            // imeta tags (one per uploaded reference), keyed on confirmedId.
            // Same key as the eventual projected item, so the bubble never
            // disappears/reappears, and it renders from the seeded thumbnail.
            // pruneConfirmedOptimisticMessages reconciles it on arrival.
            if (confirmedId.isNotEmpty() && shouldInsertSentOptimisticMessage(confirmedId, projectedMessageIds)) {
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
            publishTimelineFromIndexes()
            appState.present(R.string.toast_send_failed, AppText.Plain(throwable.message ?: throwable.javaClass.simpleName))
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
                appState.marmotIo { unreactFromMessage(account, group.groupIdHex, target) }
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
     */
    private val retainedMediaUploads =
        ByteSizeLruCache<String, RetainedMediaUpload>(
            maxBytes = MEDIA_RETAINED_MAX_BYTES,
            sizeOf = { upload -> upload.attachments.sumOf { it.plaintextBytes.size } },
        )

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
        val result =
            runCatching {
                appState.marmotIo { downloadMedia(account, group.groupIdHex, reference) }
            }.onFailure {
                if (it is CancellationException) throw it
                Log.w(
                    "DMConversation",
                    "downloadAttachment failed for ${group.groupIdHex.take(8)} message=${messageIdHex.take(8)}",
                    it,
                )
            }.getOrThrow()
        // Never cache empty plaintext — a zero-byte result would render as a
        // permanent broken image and short-circuit tap-to-retry.
        if (result.plaintext.isNotEmpty()) {
            appState.mediaPlaintextCache.put(cacheKey, result.plaintext)
            // Persist to L2 in the background. The L1 write above means the
            // bubble already renders; the disk write only needs to finish
            // before the user kills the app, which is many seconds away.
            val plaintext = result.plaintext
            appState.launchMutation {
                withContext(Dispatchers.IO) { appState.diskMediaCache.put(cacheKey, plaintext) }
            }
        }
        return result.plaintext
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
            appState.launchMutation {
                withContext(Dispatchers.IO) { appState.diskMediaCache.put(cacheKey, bytesToPersist) }
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
        retainedMediaUploads.clear()
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
        val account = conversationAccountRef ?: return
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
        publishTimelineFromIndexes()
    }

    suspend fun leaveGroup(): Boolean =
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
                appState.present(R.string.toast_left_chat)
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
            appState.present(R.string.toast_couldnt_accept_invite, AppText.Plain(it.message ?: it.javaClass.simpleName))
            false
        }
    }

    suspend fun declineInvite(): Boolean {
        val account = appState.activeAccountRef ?: return false
        return runCatching {
            appState.marmotIo { declineGroupInvite(account, group.groupIdHex) }
            group = group.copy(pendingConfirmation = false, archived = true)
            appState.present(R.string.toast_invite_declined)
            true
        }.getOrElse {
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

    suspend fun inviteMembers(memberRefs: List<String>): Boolean =
        withMutationLockResult(false) {
            lastMutationError = null
            val account = appState.activeAccountRef ?: return@withMutationLockResult false
            val refs = memberRefs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (refs.isEmpty()) return@withMutationLockResult false
            runCatching {
                appState.marmotIo { inviteMembers(account, group.groupIdHex, refs) }
                refreshMembers()
                appState.present(R.string.toast_invite_sent)
                true
            }.onFailure {
                it.rethrowIfCancellation()
                val message = mutationError(it)
                lastMutationError = message
                appState.present(R.string.toast_couldnt_add_members, AppText.Plain(message))
            }.getOrDefault(false)
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
            if (!admin && isAdmin(member) && group.admins.size <= 1) {
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

    fun timelineIndexOf(messageIdHex: String): Int = timeline.indexOfFirst { it.record.messageIdHex == messageIdHex }

    private suspend fun loadOlderPage(): Boolean {
        val account = appState.activeAccountRef ?: return false
        if (!hasMoreBefore || isLoadingOlder) return false
        val oldest =
            timelineRecords.values.minWithOrNull(
                compareBy<TimelineMessageRecordFfi> { it.timelineAt }.thenBy { it.messageIdHex },
            ) ?: return false
        // A previous loadOlderPage failure leaves `error` set; clear it now
        // that we're actually retrying, otherwise the stale banner sits over
        // a successful retry and a developer can't distinguish "still broken"
        // from "we forgot to clear it".
        error = null
        isLoadingOlder = true
        return try {
            val page =
                appState.marmotIo {
                    timelineMessages(
                        account,
                        TimelineMessageQueryFfi(
                            groupIdHex = group.groupIdHex,
                            search = null,
                            before = oldest.timelineAt,
                            beforeMessageId = oldest.messageIdHex,
                            after = null,
                            afterMessageId = null,
                            limit = ConversationTimelinePageLimit,
                        ),
                    )
                }
            hasLoadedOlderPages = true
            applyTimelinePage(page, replaceWindow = false, updatePagination = true)
            // The cached `mediaReferences` map only carries entries for
            // messages that have been listMedia-projected at some prior
            // point. Older-page rows landing fresh here would otherwise
            // fall back to the imeta-tag parser, which hard-codes
            // `sourceEpoch = 0` and breaks decryption on every image. Gate
            // on whether the page actually contains a media-bearing row so
            // a text-only history pull doesn't trigger the unbounded scan.
            if (pageContainsMedia(page)) refreshMediaReferences()
            page.messages.isNotEmpty()
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

    fun replyPreview(
        item: TimelineMessage,
        copy: MessageTextCopy = MessageTextCopy.Default,
    ): Pair<String, String>? {
        item.projected?.let { record ->
            TimelineProjector.replyPreview(record, copy)?.let { preview ->
                return appState.displayName(preview.sender) to preview.body
            }
        }
        val targetMessageId = MessageProjector.replyTargetMessageId(item.record) ?: return null
        val target = messageById[targetMessageId] ?: return null
        return appState.displayName(target.sender) to MessageProjector.displayBody(target, copy)
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
        renderTimeline()
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
        lastReadMessageId = trimmed
        runCatching {
            appState.marmotIo { markTimelineMessageRead(account, group.groupIdHex, trimmed) }
        }.onFailure {
            lastReadMessageId = null
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
        val mine = appState.activeAccount?.accountIdHex ?: return
        val base = baseReactionSenders()
        optimisticReactionChanges.entries
            .filter { (_, change) ->
                val senders = base[change.targetMessageId]?.get(change.emoji).orEmpty()
                senders.contains(mine) == change.add
            }.map { it.key }
            .forEach(optimisticReactionChanges::remove)
    }

    private fun renderTimeline() {
        timelineItemsById.clear()
        timelineOrder.clear()
        timelineRecords.values.forEach(::upsertProjectedRecord)
        publishTimelineFromIndexes()
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
        // Also require the by-id index to already hold the item, otherwise
        // renderTimeline (clears timelineItemsById + timelineOrder but not
        // timelineRecords, then re-calls this fn for each record) would
        // short-circuit on every record and leave the timeline empty.
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
        timelineRecords[record.messageIdHex] = record
        projectedMessageIds.add(record.messageIdHex)
        val actionRecord = TimelineProjector.toAppMessageRecord(record)
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

    private fun publishTimelineFromIndexes() {
        val projected = timelineOrder.mapNotNull { timelineItemsById[it] }
        timeline =
            (optimisticMessages.values + projected)
                .distinctBy { it.id }
                .sortedWith(::compareTimelineMessages)
    }

    private fun nextOptimisticTimelineOrder(): ULong =
        timeline
            .asSequence()
            .map { it.timelineOrder }
            .maxOrNull()
            ?.plus(1uL)
            ?: 1uL

    private fun recomputeReactions() {
        val mine = appState.activeAccount?.accountIdHex
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
        val mine = appState.activeAccount?.accountIdHex
        val sendersByEmoji = linkedMapOf<String, MutableSet<String>>()
        timelineRecords[targetMessageId]?.reactions?.byEmoji?.forEach { summary ->
            sendersByEmoji.getOrPut(summary.emoji) { linkedSetOf() }.addAll(summary.senders)
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
                byEmoji.getOrPut(summary.emoji) { linkedSetOf() }.addAll(summary.senders)
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
                        updateStreamPreview(streamId, text.toString(), MessageStatus.Sent)
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
            ).copy(plaintext = plaintext)
        optimisticMessages[id] =
            TimelineMessage(
                id,
                record,
                status,
                timelineOrder = existingItem?.timelineOrder ?: nextOptimisticTimelineOrder(),
            )
        publishTimelineFromIndexes()
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
