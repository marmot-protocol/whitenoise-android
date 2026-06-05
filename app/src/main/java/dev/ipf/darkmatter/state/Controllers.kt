package dev.ipf.darkmatter.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.darkmatter.R
import dev.ipf.darkmatter.core.GroupProjector
import dev.ipf.darkmatter.core.MessageProjector
import dev.ipf.darkmatter.core.MessageTextCopy
import dev.ipf.darkmatter.core.ReactionTally
import dev.ipf.darkmatter.core.ReplyNavigation
import dev.ipf.darkmatter.core.TimelineProjector
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.darkmatter.media.ByteSizeLruCache
import dev.ipf.darkmatter.media.MediaPipeline
import dev.ipf.darkmatter.media.MediaReferenceParser
import dev.ipf.marmotkit.MediaReferenceFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.AgentStreamUpdateFfi
import dev.ipf.marmotkit.AgentStreamSubscription
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscription
import dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.ChatsSubscription
import dev.ipf.marmotkit.ForensicsDumpModeFfi
import dev.ipf.marmotkit.GroupStateSubscription
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessagesSubscription
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineSubscriptionUpdateFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi

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

internal fun sortChatListItems(items: List<ChatListItem>): List<ChatListItem> {
    return items.sortedWith(
        compareByDescending<ChatListItem> { it.group.pendingConfirmation }
            .thenByDescending { it.latestAt ?: 0uL }
            .thenBy { (it.projectedTitle ?: it.group.name.ifBlank { it.group.groupIdHex }).lowercase() },
    )
}

internal fun chatListItemFromProjection(
    row: ChatListRowFfi,
    group: AppGroupRecordFfi? = null,
): ChatListItem {
    val baseGroup = group ?: emptyGroupRecord(row)
    val displayGroup = baseGroup.copy(
        name = row.groupName.ifBlank { baseGroup.name },
        archived = row.archived,
        pendingConfirmation = row.pendingConfirmation,
    )
    return ChatListItem(
        group = displayGroup,
        latest = row.lastMessage?.let { preview ->
            AppMessageRecordFfi(
                messageIdHex = preview.messageIdHex,
                direction = "received",
                groupIdHex = row.groupIdHex,
                sender = preview.sender,
                plaintext = preview.plaintext,
                kind = preview.kind,
                tags = emptyList(),
                recordedAt = preview.timelineAt,
                receivedAt = preview.timelineAt,
            )
        },
        otherMemberAccount = null,
        memberCount = 0,
        memberSnapshot = null,
        projection = row,
    )
}

private fun emptyGroupRecord(row: ChatListRowFfi): AppGroupRecordFfi {
    return AppGroupRecordFfi(
        groupIdHex = row.groupIdHex,
        endpoint = "",
        name = row.groupName,
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "",
        archived = row.archived,
        pendingConfirmation = row.pendingConfirmation,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
    )
}

data class GroupMemberSnapshot(
    val members: List<AppGroupMemberRecordFfi>,
) {
    val memberCount: Int = members.size

    fun otherMemberAccount(activeAccountIdHex: String?): String? {
        return GroupProjector.otherMemberAccount(members, activeAccountIdHex)
    }
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

fun MessageStatus.outgoingIndicator(): OutgoingMessageIndicator? {
    return when (this) {
        MessageStatus.Pending -> OutgoingMessageIndicator.Sending
        MessageStatus.Received,
        MessageStatus.Sent -> OutgoingMessageIndicator.Sent
        MessageStatus.Failed -> OutgoingMessageIndicator.Failed
        MessageStatus.Streaming -> null
    }
}

data class TimelineMessage(
    val id: String,
    val record: AppMessageRecordFfi,
    val status: MessageStatus,
    val projected: TimelineMessageRecordFfi? = null,
    val timelineOrder: ULong = 0uL,
)

internal fun optimisticMessageIdForProjection(
    optimisticMessages: Collection<TimelineMessage>,
    projected: AppMessageRecordFfi,
    allowDelayedProjection: Boolean = false,
): String? {
    return optimisticMessages.firstOrNull { optimistic ->
        (optimistic.status == MessageStatus.Pending || optimistic.status == MessageStatus.Sent) &&
            optimistic.record.direction == projected.direction &&
            optimistic.record.groupIdHex == projected.groupIdHex &&
            optimistic.record.sender == projected.sender &&
            optimistic.record.plaintext == projected.plaintext &&
            optimistic.record.kind == projected.kind &&
            optimistic.record.tags == projected.tags &&
            (
                timestampsAreNear(optimistic.record.recordedAt, projected.recordedAt) ||
                    allowDelayedProjection && projected.recordedAt >= optimistic.record.recordedAt
            )
    }?.record?.messageIdHex
}

private fun timestampsAreNear(left: ULong, right: ULong): Boolean {
    return if (left >= right) left - right <= 1uL else right - left <= 1uL
}

internal fun shouldInsertSentOptimisticMessage(
    confirmedId: String,
    projectedMessageIds: Set<String>,
): Boolean {
    return confirmedId !in projectedMessageIds
}

internal fun compareTimelineMessages(left: TimelineMessage, right: TimelineMessage): Int {
    return compareValuesBy(left, right, { it.record.recordedAt }, { it.timelineOrder }, { it.id })
}

internal fun AppMessageRecordFfi.withRecordedAtOverride(recordedAt: ULong?): AppMessageRecordFfi {
    return recordedAt?.let { copy(recordedAt = it) } ?: this
}

/**
 * Index of the first (oldest) still-unread received message in [timeline]
 * given the chat-list projection's [unreadCount]. Returns -1 when nothing is
 * unread, the timeline is empty, or the loaded window holds fewer than
 * [unreadCount] received messages (caller falls back to the bottom).
 */
internal fun firstUnreadReceivedIndex(timeline: List<TimelineMessage>, unreadCount: Int): Int {
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
internal fun countUnreadIncoming(timeline: List<TimelineMessage>, readAnchorMessageId: String?): Int {
    if (timeline.isEmpty()) return 0
    val anchorIdx = readAnchorMessageId?.let { id ->
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

private data class OptimisticReactionChange(
    val targetMessageId: String,
    val emoji: String,
    val add: Boolean,
)

/** Compressed bytes + metadata retained for an in-flight/failed media send. */
private class RetainedMediaUpload(
    val jpegBytes: ByteArray,
    val mediaType: String,
    val fileName: String,
    val caption: String?,
    // Set once the Blossom upload succeeds. On a publish-only failure, retry
    // reuses this reference instead of re-uploading the blob (which would
    // orphan a duplicate on the Blossom server).
    var uploadedReference: MediaReferenceFfi? = null,
)

class ChatsController(private val appState: DarkMatterAppState) {
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

    suspend fun bind(accountRef: String?) {
        chatsDebug { "bind account=${accountRef?.take(8)}" }
        this.accountRef = accountRef
        this.boundAccountRef = accountRef
        chatRows = emptyList()
        groupRecordsById = emptyMap()
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
            chatRows = withContext(Dispatchers.IO) {
                chatListStream.snapshot()
            }
            chatRows.forEach(::requestChatRowProfiles)
            groupRecordsById = withContext(Dispatchers.IO) {
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
                        val update = withContext(Dispatchers.IO) {
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
                        val update = withContext(Dispatchers.IO) {
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
        chatRows = chatRows.map { row ->
            if (row.groupIdHex == record.groupIdHex) row.copy(archived = record.archived) else row
        }
        foldGroup(record)
    }

    private fun foldChatRow(row: ChatListRowFfi) {
        chatRows = if (chatRows.any { it.groupIdHex == row.groupIdHex }) {
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

    private fun requestGroupProfiles(group: AppGroupRecordFfi) {
        appState.requestProfiles(
            listOfNotNull(group.welcomerAccountIdHex) + group.admins,
        )
    }

    private fun requestChatRowProfiles(row: ChatListRowFfi) {
        row.lastMessage?.sender?.let(appState::requestProfile)
    }

    private fun recompute() {
        val all = chatRows.map { row ->
            chatListItemFromProjection(row, groupRecordsById[row.groupIdHex])
        }.let(::sortChatListItems)
        items = all.filter { !it.group.archived }
        archivedItems = all.filter { it.group.archived }
        chatsDebug { "recompute visible=${items.size} archived=${archivedItems.size} total=${all.size}" }
    }
}

private fun AppGroupRecordFfi.debugSummary(): String {
    return "id=${groupIdHex.take(8)} archived=$archived pending=$pendingConfirmation " +
        "welcomer=${welcomerAccountIdHex?.take(8)} relays=${relays.size} name=${name.ifBlank { "<blank>" }}"
}

private fun ChatListRowFfi.debugSummary(): String {
    return "id=${groupIdHex.take(8)} archived=$archived pending=$pendingConfirmation unread=$unreadCount " +
        "last=${lastMessage?.messageIdHex?.take(8)} title=${title.ifBlank { "<blank>" }}"
}

private fun TimelineUpdateTriggerFfi.recomputesReactions(): Boolean {
    return when (this) {
        TimelineUpdateTriggerFfi.REACTION_ADDED,
        TimelineUpdateTriggerFfi.REACTION_REMOVED,
        TimelineUpdateTriggerFfi.MESSAGE_DELETED,
        TimelineUpdateTriggerFfi.MESSAGE_EDITED_OR_REPROJECTED,
        TimelineUpdateTriggerFfi.SNAPSHOT_REFRESH -> true
        TimelineUpdateTriggerFfi.NEW_MESSAGE,
        TimelineUpdateTriggerFfi.REPLY_PREVIEW_CHANGED,
        TimelineUpdateTriggerFfi.AGENT_STREAM_STARTED,
        TimelineUpdateTriggerFfi.AGENT_STREAM_FINISHED,
        TimelineUpdateTriggerFfi.DELIVERY_OR_SEND_STATE_CHANGED,
        TimelineUpdateTriggerFfi.RECEIPT_CHANGED -> false
    }
}

private inline fun chatsDebug(message: () -> String) {
    Log.i("DMChats", message())
}

private inline fun chatsDebug(error: Throwable, message: () -> String) {
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

    private suspend inline fun <T> withMutationLockResult(defaultValue: T, block: () -> T): T {
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

    fun subtitle(justYou: String, oneMember: String, membersFormat: String): String {
        val count = members.size
        return when (count) {
            0 -> justYou
            1 -> oneMember
            else -> String.format(membersFormat, count)
        }
    }

    val isSelfAdmin: Boolean
        get() = GroupProjector.isAdminRef(group, appState.activeAccount?.accountIdHex)

    val canLeaveGroup: Boolean
        get() = GroupProjector.canLeaveGroup(group, appState.activeAccount?.accountIdHex)

    suspend fun start() {
        val account = conversationAccountRef ?: return
        isLoading = true
        error = null
        var timelineSubscription: TimelineMessagesSubscription? = null
        var groupSubscription: GroupStateSubscription? = null
        try {
            val timelineStream = appState.marmotIo {
                subscribeTimelineMessages(account, group.groupIdHex, ConversationTimelinePageLimit)
            }
            timelineSubscription = timelineStream
            val snapshot = withContext(Dispatchers.IO) { timelineStream.snapshot() }
            val snapshotStreamIds = snapshot?.let { applyTimelinePage(it, replaceWindow = true, updatePagination = true) }.orEmpty()
            initializeReadState(account)
            // Don't blanket-mark the absolute newest as read here — the UI
            // layer now drives mark-read as the user scrolls so partial-read
            // sessions retain accurate unread counts on the chat list.

            val groupStream = appState.marmotIo { subscribeGroupState(account, group.groupIdHex) }
            groupSubscription = groupStream
            val groupSnapshot = withContext(Dispatchers.IO) {
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
                        val update = withContext(Dispatchers.IO) {
                            timelineStream.nextUpdate()
                        } ?: break
                        val streamIds = when (update) {
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
                        val update = withContext(Dispatchers.IO) {
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

        val replyTarget = replyingTo?.messageIdHex?.takeIf { it.isNotBlank() }
        val tempId = UUID.randomUUID().toString()
        val now = nowSeconds()
        val optimistic = AppMessageRecordFfi(
            messageIdHex = tempId,
            direction = "sent",
            groupIdHex = group.groupIdHex,
            sender = appState.activeAccount?.accountIdHex ?: "",
            plaintext = trimmed,
            kind = 9uL,
            tags = replyTarget?.let {
                listOf(MessageProjector.eventTag(it), MessageProjector.quoteTag(it))
            }.orEmpty(),
            recordedAt = now,
            receivedAt = now,
        )
        val optimisticOrder = nextOptimisticTimelineOrder()
        optimisticMessages["msg:$tempId"] = TimelineMessage(
            "msg:$tempId",
            optimistic,
            MessageStatus.Pending,
            timelineOrder = optimisticOrder,
        )
        messageById[tempId] = optimistic
        publishTimelineFromIndexes()
        replyingTo = null
        try {
            val summary = if (replyTarget != null) {
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
                optimisticMessages["msg:$confirmedId"] = TimelineMessage(
                    "msg:$confirmedId",
                    confirmed,
                    MessageStatus.Sent,
                    timelineOrder = optimisticOrder,
                )
            }
            publishTimelineFromIndexes()
        } catch (throwable: Throwable) {
            optimisticMessages["msg:$tempId"] = TimelineMessage(
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
     * Encrypt+upload a JPEG to Blossom and then publish a kind-9 referencing
     * it. Two-step (upload then publish) — see the Phase-1 decision matrix —
     * so a publish-time failure doesn't force a re-upload, and the optimistic
     * bubble can flip to Failed with the existing retry/discard surface.
     *
     * Callers downscale before this point ([MediaPipeline.readDownscaledJpeg])
     * because the FFI takes the whole payload in memory.
     */
    suspend fun sendImageAttachment(
        jpegBytes: ByteArray,
        mediaType: String,
        fileName: String,
        caption: String?,
    ) {
        val account = conversationAccountRef ?: return
        if (jpegBytes.isEmpty()) return

        val tempId = UUID.randomUUID().toString()
        val key = "msg:$tempId"
        val now = nowSeconds()
        val trimmedCaption = caption?.trim()?.takeIf { it.isNotBlank() }
        // Body: the caption if present, otherwise a legible placeholder while
        // the upload runs. The real message arriving after publish carries the
        // imeta tag and supersedes this optimistic one.
        val body = trimmedCaption ?: "📎 $fileName"
        val optimistic = AppMessageRecordFfi(
            messageIdHex = tempId,
            direction = "sent",
            groupIdHex = group.groupIdHex,
            sender = appState.activeAccount?.accountIdHex ?: "",
            plaintext = body,
            kind = 9uL,
            // Sentinel tag distinguishes a pending-media optimistic record from
            // a normal text optimistic record. retryFailedSend uses it to route
            // into the media re-upload path (bytes are held in
            // [retainedMediaUploads] so retry doesn't need a re-attach).
            tags = listOf(MessageTagFfi(listOf("_media_pending", fileName, mediaType))),
            recordedAt = now,
            receivedAt = now,
        )
        val optimisticOrder = nextOptimisticTimelineOrder()
        // Retain the compressed bytes so a failed upload can be retried in
        // place, and so the sender's own bubble renders from memory after
        // confirm instead of round-tripping Blossom.
        retainedMediaUploads.put(key, RetainedMediaUpload(jpegBytes, mediaType, fileName, trimmedCaption))
        optimisticMessages[key] = TimelineMessage(
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
        val retained = retainedMediaUploads.get(key) ?: run {
            // Bytes are gone (evicted under cap, or process death) — can't
            // retry without a re-attach. Leave the bubble Failed.
            optimisticMessages[key] = TimelineMessage(key, optimistic, MessageStatus.Failed, timelineOrder = order)
            publishTimelineFromIndexes()
            appState.present(R.string.toast_reattach_to_retry_media)
            return
        }
        discardedDuringRetry.remove(key)
        try {
            // Reuse the reference if a prior attempt already uploaded the blob
            // (publish-only failure) — re-uploading would orphan a duplicate
            // on the Blossom server.
            val reference = retained.uploadedReference ?: appState.marmotIo {
                uploadMedia(
                    account,
                    group.groupIdHex,
                    MediaUploadRequestFfi(
                        fileName = retained.fileName,
                        mediaType = retained.mediaType,
                        plaintext = retained.jpegBytes,
                        caption = retained.caption,
                        send = false,
                        blossomServer = null,
                    ),
                ).reference
            }.also { retained.uploadedReference = it }
            // Discard window #1: blob is uploaded but not yet published. If the
            // user discarded here, bail BEFORE sendMediaReference so we don't
            // publish a kind-9 they cancelled (unlike a published event, an
            // unreferenced Blossom blob is inert).
            if (discardedDuringRetry.remove(key)) {
                optimisticMessages.remove(key)
                messageById.remove(tempId)
                retainedMediaUploads.remove(key)
                publishTimelineFromIndexes()
                return
            }
            val summary = appState.marmotIo {
                sendMediaReference(account, group.groupIdHex, reference, retained.caption)
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
            // Blossom round-trip and no decode spinner.
            if (confirmedId.isNotEmpty()) {
                appState.mediaPlaintextCache.put(mediaCacheKey(account, confirmedId), retained.jpegBytes)
                MediaPipeline.decodeSampledBitmap(retained.jpegBytes, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)?.let {
                    appState.mediaThumbnailCache.put(mediaCacheKey(account, confirmedId), it)
                }
            }
            retainedMediaUploads.remove(key)
            // Bridge the gap until the published event echoes back via the
            // projection: insert a confirmed *image* optimistic carrying the
            // imeta tag (built from the upload reference), keyed on confirmedId.
            // Same key as the eventual projected item, so the bubble never
            // disappears/reappears, and it renders from the seeded thumbnail.
            // pruneConfirmedOptimisticMessages reconciles it on arrival.
            if (confirmedId.isNotEmpty() && shouldInsertSentOptimisticMessage(confirmedId, projectedMessageIds)) {
                val confirmedRecord = optimistic.copy(
                    messageIdHex = confirmedId,
                    // Match what the published event carries (the caption we
                    // sent), not the "📎 filename" optimistic placeholder, so
                    // the bridge bubble is identical to the projected one.
                    plaintext = retained.caption.orEmpty(),
                    tags = listOf(MediaReferenceParser.toImetaTag(reference)),
                )
                messageById[confirmedId] = confirmedRecord
                optimisticMessages["msg:$confirmedId"] = TimelineMessage(
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
            optimisticMessages[key] = TimelineMessage(
                key,
                optimistic,
                MessageStatus.Failed,
                timelineOrder = order,
            )
            publishTimelineFromIndexes()
            appState.present(R.string.toast_send_failed, AppText.Plain(throwable.message ?: throwable.javaClass.simpleName))
        }
    }

    suspend fun toggleReaction(emoji: String, message: AppMessageRecordFfi) {
        val account = appState.activeAccountRef ?: return
        val target = message.messageIdHex.takeIf { it.isNotBlank() } ?: return
        val alreadyMine = reactions[target]?.any { it.emoji == emoji && it.mine } == true
        val optimisticId = UUID.randomUUID().toString()
        optimisticReactionChanges[optimisticId] = OptimisticReactionChange(
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
            optimisticReactionChanges.remove(optimisticId)
            recomputeReactions()
            appState.present(R.string.toast_reaction_failed, AppText.Plain(throwable.message ?: throwable.javaClass.simpleName))
        }
    }

    suspend fun deleteMessage(message: AppMessageRecordFfi) {
        val account = appState.activeAccountRef ?: return
        val target = message.messageIdHex.takeIf { it.isNotBlank() } ?: return
        deletedMessageIds = deletedMessageIds + target
        try {
            appState.marmotIo { deleteMessage(account, group.groupIdHex, target) }
        } catch (throwable: Throwable) {
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
    private val retainedMediaUploads = ByteSizeLruCache<String, RetainedMediaUpload>(
        maxBytes = MEDIA_RETAINED_MAX_BYTES,
        sizeOf = { it.jpegBytes.size },
    )

    /**
     * App-level cache key for a decrypted attachment. Scoped to
     * account+group+message (not bare messageIdHex) so a cache entry can only
     * ever satisfy a lookup from the same account and group that decrypted it —
     * defense-in-depth against an evicted/rejoined member replaying an old
     * event id to read plaintext it shouldn't.
     */
    private fun mediaCacheKey(account: String, messageIdHex: String): String =
        "$account|${group.groupIdHex}|$messageIdHex"

    /**
     * Fetch and decrypt a Blossom-stored attachment. Backed by the app-level
     * LRU ([DarkMatterAppState.mediaPlaintextCache], keyed via [mediaCacheKey])
     * so re-opening a conversation doesn't re-download media already fetched
     * this session. Throws on download/decrypt failure — the caller surfaces it.
     */
    suspend fun downloadAttachment(messageIdHex: String, reference: MediaReferenceFfi): ByteArray {
        // Resolve the account first so the cache key is never unanchored
        // ("|group|msg"), which a later sign-in could collide with.
        val account = conversationAccountRef ?: error("no active account")
        val cacheKey = mediaCacheKey(account, messageIdHex)
        appState.mediaPlaintextCache.get(cacheKey)?.let { return it }
        val result = appState.marmotIo { downloadMedia(account, group.groupIdHex, reference) }
        // Never cache empty plaintext — a zero-byte result would render as a
        // permanent broken image and short-circuit tap-to-retry.
        if (result.plaintext.isNotEmpty()) {
            appState.mediaPlaintextCache.put(cacheKey, result.plaintext)
        }
        return result.plaintext
    }

    /** Decoded thumbnail for [messageIdHex] if one is cached (renders with no
     *  spinner). Null when unanchored or not yet decoded. */
    fun thumbnailFor(messageIdHex: String): android.graphics.Bitmap? {
        val account = conversationAccountRef ?: return null
        return appState.mediaThumbnailCache.get(mediaCacheKey(account, messageIdHex))
    }

    /** Cache a decoded thumbnail so re-renders / re-entry skip the decode. */
    fun cacheThumbnail(messageIdHex: String, bitmap: android.graphics.Bitmap) {
        val account = conversationAccountRef ?: return
        appState.mediaThumbnailCache.put(mediaCacheKey(account, messageIdHex), bitmap)
    }

    /**
     * Compressed bytes for an in-flight/failed optimistic media send, so the
     * sender's bubble can preview the local image while it uploads. [messageIdHex]
     * is the optimistic record's temp id; null once the send confirms.
     */
    fun pendingMediaBytes(messageIdHex: String): ByteArray? =
        retainedMediaUploads.get("msg:$messageIdHex")?.jpegBytes

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
            val mediaOrder = current.timelineOrder ?: nextOptimisticTimelineOrder()
            val mediaTempId = current.record.messageIdHex
            optimisticMessages[key] = TimelineMessage(
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
        val order = current.timelineOrder ?: nextOptimisticTimelineOrder()
        discardedDuringRetry.remove(key)
        optimisticMessages[key] = TimelineMessage(
            key,
            refreshedRecord,
            MessageStatus.Pending,
            timelineOrder = order,
        )
        messageById[tempId] = refreshedRecord
        publishTimelineFromIndexes()
        try {
            val summary = if (replyTarget != null) {
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
                optimisticMessages["msg:$confirmedId"] = TimelineMessage(
                    "msg:$confirmedId",
                    confirmed,
                    MessageStatus.Sent,
                    timelineOrder = order,
                )
            }
            publishTimelineFromIndexes()
        } catch (throwable: Throwable) {
            if (discardedDuringRetry.remove(key)) {
                // User discarded mid-flight; don't restore the Failed bubble.
                optimisticMessages.remove(key)
                messageById.remove(tempId)
                publishTimelineFromIndexes()
                return
            }
            optimisticMessages[key] = TimelineMessage(
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

    suspend fun leaveGroup(): Boolean = withMutationLockResult(false) {
        val account = appState.activeAccountRef ?: return false
        if (!canLeaveGroup) {
            appState.present(R.string.toast_make_another_admin_before_leaving, R.string.toast_group_needs_admin)
            return false
        }
        runCatching {
            val activeAccountIdHex = appState.activeAccount?.accountIdHex
            if (GroupProjector.requiresSelfDemoteBeforeLeave(group, activeAccountIdHex)) {
                appState.marmotIo { selfDemoteAdmin(account, group.groupIdHex) }
                // Case-insensitive so hex-casing drift between the admin
                // list and the active account id doesn't leave the UI
                // showing you as admin after a successful self-demote.
                group = group.copy(
                    admins = group.admins.filterNot { it.equals(activeAccountIdHex, ignoreCase = true) },
                )
            }
            appState.marmotIo { leaveGroup(account, group.groupIdHex) }
            appState.present(R.string.toast_left_chat)
            true
        }.getOrElse {
            if (it is CancellationException) throw it
            appState.present(R.string.toast_couldnt_leave_chat, AppText.Plain(it.message ?: it.javaClass.simpleName))
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

    suspend fun setArchived(archived: Boolean) = withMutationLock {
        val account = appState.activeAccountRef ?: return@withMutationLock
        runCatching {
            val updated = appState.marmotIo { setGroupArchived(account, group.groupIdHex, archived) }
            group = updated
            appState.applyLocalGroupUpdate(updated)
            appState.present(if (archived) R.string.toast_chat_archived else R.string.toast_chat_restored)
        }.onFailure {
            appState.present(R.string.toast_couldnt_update_chat, AppText.Plain(it.message ?: it.javaClass.simpleName))
        }
    }

    suspend fun updateGroupProfile(name: String, description: String) = withMutationLock {
        val account = appState.activeAccountRef ?: return@withMutationLock
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
        }.onFailure {
            appState.present(R.string.toast_couldnt_update_group, AppText.Plain(it.message ?: it.javaClass.simpleName))
        }
    }

    suspend fun inviteMembers(memberRefs: List<String>) = withMutationLock {
        val account = appState.activeAccountRef ?: return@withMutationLock
        val refs = memberRefs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (refs.isEmpty()) return@withMutationLock
        runCatching {
            appState.marmotIo { inviteMembers(account, group.groupIdHex, refs) }
            refreshMembers()
            appState.present(R.string.toast_invite_sent)
        }.onFailure {
            appState.present(R.string.toast_couldnt_add_members, AppText.Plain(it.message ?: it.javaClass.simpleName))
        }
    }

    suspend fun removeMember(member: AppGroupMemberRecordFfi) = withMutationLock {
        val account = appState.activeAccountRef ?: return@withMutationLock
        val target = GroupProjector.memberRef(member)
        runCatching {
            appState.marmotIo { removeMembers(account, group.groupIdHex, listOf(target)) }
            refreshMembers()
            appState.present(R.string.toast_member_removed)
        }.onFailure {
            appState.present(R.string.toast_couldnt_remove_member, AppText.Plain(it.message ?: it.javaClass.simpleName))
        }
    }

    suspend fun setMemberAdmin(member: AppGroupMemberRecordFfi, admin: Boolean) = withMutationLock {
        val account = appState.activeAccountRef ?: return@withMutationLock
        // promote_admin / demote_admin sign the new admin list onto the MLS
        // group, so they require a Nostr pubkey hex — not a local-account
        // label. memberRef can return either; memberIdHex is always the hex.
        val target = member.memberIdHex
        if (!admin && isAdmin(member) && group.admins.size <= 1) {
            appState.present(R.string.toast_keep_one_admin, R.string.toast_promote_before_removing_admin)
            return@withMutationLock
        }
        runCatching {
            if (admin) {
                appState.marmotIo { promoteAdmin(account, group.groupIdHex, target) }
                group = group.copy(admins = (group.admins + target).distinct())
                appState.present(R.string.toast_admin_added)
            } else {
                appState.marmotIo { demoteAdmin(account, group.groupIdHex, target) }
                // Case-insensitive so admin hex casing variations don't keep
                // the local UI showing the member as admin until next refresh.
                group = group.copy(
                    admins = group.admins.filterNot { it.equals(target, ignoreCase = true) },
                )
                appState.present(R.string.toast_admin_removed)
            }
            refreshMembers()
        }.onFailure {
            appState.present(R.string.toast_couldnt_update_admin, AppText.Plain(it.message ?: it.javaClass.simpleName))
        }
    }

    fun isAdmin(member: AppGroupMemberRecordFfi): Boolean = GroupProjector.isAdmin(group, member)

    fun memberDisplayName(member: AppGroupMemberRecordFfi): String {
        return appState.displayName(member.memberIdHex)
    }

    fun memberSubtitle(member: AppGroupMemberRecordFfi): String {
        return appState.shortNpub(member.memberIdHex)
    }

    fun memberAvatarUrl(member: AppGroupMemberRecordFfi): String? {
        return appState.avatarUrl(member.memberIdHex)
    }

    suspend fun groupMlsState(): AppGroupMlsStateFfi? {
        val account = appState.activeAccountRef ?: return null
        return runCatching {
            appState.marmotIo { groupMlsState(account, group.groupIdHex) }
        }.onFailure {
            if (it is CancellationException) throw it
            appState.present(R.string.toast_couldnt_load_mls_state, AppText.Plain(it.message ?: it.javaClass.simpleName))
        }.getOrNull()
    }

    suspend fun groupForensicsJson(mode: ForensicsDumpModeFfi): String? {
        val account = appState.activeAccountRef ?: return null
        return runCatching {
            appState.marmotIo {
                groupForensicsJson(
                    account,
                    group.groupIdHex,
                    mode,
                    null,
                )
            }
        }.onFailure {
            appState.present(R.string.toast_couldnt_export_forensics_dump, AppText.Plain(it.message ?: it.javaClass.simpleName))
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

    fun replyTargetMessageId(item: TimelineMessage): String? {
        return ReplyNavigation.targetMessageId(item.record, item.projected)
    }

    fun timelineIndexOf(messageIdHex: String): Int {
        return timeline.indexOfFirst { it.record.messageIdHex == messageIdHex }
    }

    private suspend fun loadOlderPage(): Boolean {
        val account = appState.activeAccountRef ?: return false
        if (!hasMoreBefore || isLoadingOlder) return false
        val oldest = timelineRecords.values.minWithOrNull(
            compareBy<TimelineMessageRecordFfi> { it.timelineAt }.thenBy { it.messageIdHex },
        ) ?: return false
        isLoadingOlder = true
        return try {
            val page = appState.marmotIo {
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
        val page = appState.marmotIo {
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
        return applyTimelinePage(page, replaceWindow = true, updatePagination = true)
    }

    fun replyPreview(item: TimelineMessage, copy: MessageTextCopy = MessageTextCopy.Default): Pair<String, String>? {
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
        }
        page.messages.forEach { record ->
            val actionRecord = upsertProjectedRecord(
                record,
                reconcileOptimistic = replaceWindow,
                allowDelayedProjection = replaceWindow,
            )
            appState.requestProfile(record.sender)
            record.replyPreview?.let { appState.requestProfile(it.sender) }
            if (record.deleted) {
                deletedMessageIds = deletedMessageIds - record.messageIdHex
            }
            if (MessageProjector.isStreamFinal(actionRecord)) {
                MessageProjector.streamId(actionRecord)?.let { streamId ->
                    activeStreamIds.remove(streamId)
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
    }

    private fun applyTimelineChanges(changes: List<TimelineMessageChangeFfi>): List<String> {
        val streamIds = mutableListOf<String>()
        val reactionTargets = linkedSetOf<String>()
        changes.forEach { change ->
            when (change) {
                is TimelineMessageChangeFfi.Upsert -> {
                    val record = change.message
                    val actionRecord = upsertProjectedRecord(
                        record,
                        reconcileOptimistic = change.trigger == TimelineUpdateTriggerFfi.NEW_MESSAGE,
                        allowDelayedProjection = change.trigger == TimelineUpdateTriggerFfi.NEW_MESSAGE,
                    )
                    if (change.trigger.recomputesReactions()) {
                        reactionTargets.add(record.messageIdHex)
                    }
                    appState.requestProfile(record.sender)
                    record.replyPreview?.let { appState.requestProfile(it.sender) }
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
        return streamIds
    }

    private fun applyChatListProjection(trigger: ChatListUpdateTriggerFfi, row: ChatListRowFfi?) {
        val projected = row ?: return
        when (trigger) {
            ChatListUpdateTriggerFfi.ARCHIVE_CHANGED,
            ChatListUpdateTriggerFfi.PENDING_CONFIRMATION_CHANGED,
            ChatListUpdateTriggerFfi.NEW_GROUP,
            ChatListUpdateTriggerFfi.MEMBERSHIP_CHANGED,
            ChatListUpdateTriggerFfi.SNAPSHOT_REFRESH -> {
                group = group.copy(
                    name = projected.groupName.ifBlank { group.name },
                    archived = projected.archived,
                    pendingConfirmation = projected.pendingConfirmation,
                )
            }
            ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
            ChatListUpdateTriggerFfi.LAST_MESSAGE_DELETED,
            ChatListUpdateTriggerFfi.UNREAD_CHANGED,
            ChatListUpdateTriggerFfi.REMOVED -> Unit
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
        val latest = timelineRecords.values.maxWithOrNull(
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
    fun firstUnreadTimelineIndex(unreadCount: Int): Int =
        firstUnreadReceivedIndex(timeline, unreadCount)

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
            }
            .map { it.key }
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
        val previousItemId = timelineRecords[record.messageIdHex]?.let(::projectedItemId)
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
        )
            ?.takeIf { reconcileOptimistic }
            ?.let { optimisticId ->
            preserveOptimisticDisplayPosition(record.messageIdHex, optimisticId)
            optimisticMessages.remove("msg:$optimisticId")
            messageById.remove(optimisticId)
        }
        messageById[record.messageIdHex] = actionRecord
        val item = timelineMessageFromProjection(record, actionRecord)
        timelineItemsById[item.id] = item
        insertTimelineItemId(item.id, item)
        return actionRecord
    }

    private fun preserveOptimisticDisplayPosition(projectedId: String, optimisticId: String) {
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
        val displayRecord = if (streamId != null) {
            actionRecord.copy(plaintext = actionRecord.plaintext.ifBlank { copy.waitingForStream })
        } else {
            actionRecord
        }.withRecordedAtOverride(localTimelineTimestampOverrides[record.messageIdHex])
        return TimelineMessage(
            id = streamId?.let { "stream:$it" } ?: "msg:${record.messageIdHex}",
            record = displayRecord,
            status = when {
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

    private fun insertTimelineItemId(itemId: String, item: TimelineMessage) {
        val insertAt = timelineOrder.indexOfFirst { existingId ->
            val existing = timelineItemsById[existingId] ?: return@indexOfFirst false
            compareTimelineMessages(item, existing) < 0
        }
        if (insertAt == -1) {
            timelineOrder.add(itemId)
        } else {
            timelineOrder.add(insertAt, itemId)
        }
    }

    private fun publishTimelineFromIndexes() {
        val projected = timelineOrder.mapNotNull { timelineItemsById[it] }
        timeline = (optimisticMessages.values + projected)
            .distinctBy { it.id }
            .sortedWith(::compareTimelineMessages)
    }

    private fun nextOptimisticTimelineOrder(): ULong {
        return timeline.asSequence()
            .map { it.timelineOrder }
            .maxOrNull()
            ?.plus(1uL)
            ?: 1uL
    }

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
        reactions = sendersByTarget.mapValues { (_, byEmoji) ->
            byEmoji.mapNotNull { (emoji, senders) ->
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
        return sendersByEmoji.mapNotNull { (emoji, senders) ->
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
            val loaded = appState.marmotIo { groupMembers(account, group.groupIdHex) }
            members = loaded
            appState.cacheGroupMemberSnapshot(account, group.groupIdHex, loaded)
            appState.requestProfiles(members.map { it.memberIdHex })
        }
    }

    private suspend fun watchAgentTextStream(account: String, streamId: String) {
        val text = StringBuilder()
        var subscription: AgentStreamSubscription? = null
        try {
            val streamSubscription = appState.marmotIo {
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
                val update = withContext(Dispatchers.IO) {
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
                }
            }
        } catch (throwable: Throwable) {
            updateStreamPreview(
                streamId,
                copy.streamFailed(throwable.message ?: throwable.javaClass.simpleName),
                MessageStatus.Failed,
            )
        } finally {
            withContext(Dispatchers.IO) {
                subscription?.close()
            }
            activeStreamIds.remove(streamId)
        }
    }

    private fun updateStreamPreview(streamId: String, plaintext: String, status: MessageStatus) {
        if (streamId in removedStreamIds) return
        val id = "stream:$streamId"
        val existingItem = optimisticMessages[id] ?: timelineItemsById[id]
        val existing = existingItem?.record
        val record = (existing ?: AppMessageRecordFfi(
            messageIdHex = "stream-$streamId",
            direction = "received",
            groupIdHex = group.groupIdHex,
            sender = "",
            plaintext = "",
            kind = 1200uL,
            tags = listOf(MessageProjector.streamTag(streamId)),
            recordedAt = nowSeconds(),
            receivedAt = nowSeconds(),
        )).copy(plaintext = plaintext)
        optimisticMessages[id] = TimelineMessage(
            id,
            record,
            status,
            timelineOrder = existingItem?.timelineOrder ?: nextOptimisticTimelineOrder(),
        )
        publishTimelineFromIndexes()
    }

    private fun nowSeconds(): ULong = (System.currentTimeMillis() / 1000L).toULong()

    private companion object {
        // 32 MiB cap on retained compressed bytes for in-flight/failed
        // uploads. A few failed images stay retryable without letting an
        // undiscarded backlog accrete unbounded heap.
        const val MEDIA_RETAINED_MAX_BYTES: Long = 32L * 1024L * 1024L
    }
}
