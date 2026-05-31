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
import dev.ipf.darkmatter.core.TimelineProjector
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AgentStreamUpdateFfi
import dev.ipf.marmotkit.AgentStreamSubscription
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscription
import dev.ipf.marmotkit.ChatsSubscription
import dev.ipf.marmotkit.ForensicsDumpModeFfi
import dev.ipf.marmotkit.GroupStateSubscription
import dev.ipf.marmotkit.MessageUpdateFfi
import dev.ipf.marmotkit.MessagesSubscription
import dev.ipf.marmotkit.RuntimeMessageReceivedFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessagesSubscription
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineSubscriptionUpdateFfi

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

private fun appMessageRecordFromReceived(
    received: RuntimeMessageReceivedFfi,
    recordedAt: ULong,
): AppMessageRecordFfi {
    return AppMessageRecordFfi(
        messageIdHex = received.message.messageIdHex,
        direction = "received",
        groupIdHex = received.message.groupIdHex,
        sender = received.message.sender,
        plaintext = received.message.plaintext,
        kind = received.message.kind,
        tags = received.message.tags,
        recordedAt = recordedAt,
        receivedAt = recordedAt,
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
)

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

class ChatsController(private val appState: DarkMatterAppState) {
    var items by mutableStateOf<List<ChatListItem>>(emptyList())
        private set
    var archivedItems by mutableStateOf<List<ChatListItem>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var accountRef: String? = null
    private var chatRows = listOf<ChatListRowFfi>()
    private var groupRecordsById = mapOf<String, AppGroupRecordFfi>()

    suspend fun bind(accountRef: String?) {
        chatsDebug { "bind account=${accountRef?.take(8)}" }
        this.accountRef = accountRef
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
                        val row = withContext(Dispatchers.IO) {
                            chatListStream.next()
                        } ?: break
                        requestChatRowProfiles(row)
                        chatsDebug { "chat list update account=${accountRef.take(8)} ${row.debugSummary()}" }
                        foldChatRow(row)
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
    var sendInFlight by mutableStateOf(false)
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

    private val messageById = linkedMapOf<String, AppMessageRecordFfi>()
    private val timelineRecords = linkedMapOf<String, TimelineMessageRecordFfi>()
    private val optimisticMessages = linkedMapOf<String, TimelineMessage>()
    private val optimisticReactionChanges = linkedMapOf<String, OptimisticReactionChange>()
    private val activeStreamIds = mutableSetOf<String>()
    private var hasLoadedOlderPages = false

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
        val account = appState.activeAccountRef ?: return
        isLoading = true
        error = null
        var timelineSubscription: TimelineMessagesSubscription? = null
        var streamStartSubscription: MessagesSubscription? = null
        var groupSubscription: GroupStateSubscription? = null
        try {
            val timelineStream = appState.marmotIo {
                subscribeTimelineMessages(account, group.groupIdHex, ConversationTimelinePageLimit)
            }
            timelineSubscription = timelineStream
            val snapshot = withContext(Dispatchers.IO) { timelineStream.snapshot() }
            val snapshotStreamIds = snapshot?.let { applyTimelinePage(it, replaceWindow = true, updatePagination = true) }.orEmpty()
            initializeReadState(account)
            markLatestVisibleRead(account)

            val groupStream = appState.marmotIo { subscribeGroupState(account, group.groupIdHex) }
            groupSubscription = groupStream
            val streamStartStream = appState.marmotIo { subscribeMessages(account, group.groupIdHex) }
            streamStartSubscription = streamStartStream
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
                                    applyTimelinePage(
                                        TimelinePageFfi(
                                            messages = projection.messages,
                                            hasMoreBefore = hasMoreBefore,
                                            hasMoreAfter = false,
                                        ),
                                        replaceWindow = false,
                                        updatePagination = false,
                                    )
                                } else {
                                    emptyList()
                                }
                            }
                        }
                        markLatestVisibleRead(account)
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
                            streamStartStream.next()
                        } ?: break
                        if (update is MessageUpdateFfi.AgentStreamStarted) {
                            val record = appMessageRecordFromReceived(update.received, nowSeconds())
                            val streamId = MessageProjector.streamId(record) ?: continue
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
            withContext(Dispatchers.IO) {
                runCatching { groupSubscription?.close() }
                runCatching { streamStartSubscription?.close() }
                runCatching { timelineSubscription?.close() }
            }
        }
    }

    suspend fun send(text: String) {
        val trimmed = text.trim()
        val account = appState.activeAccountRef ?: return
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
        optimisticMessages["msg:$tempId"] = TimelineMessage("msg:$tempId", optimistic, MessageStatus.Pending)
        messageById[tempId] = optimistic
        renderTimeline()
        replyingTo = null
        sendInFlight = true
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
            optimisticMessages["msg:$confirmedId"] = TimelineMessage("msg:$confirmedId", confirmed, MessageStatus.Sent)
            renderTimeline()
        } catch (throwable: Throwable) {
            optimisticMessages["msg:$tempId"] = TimelineMessage("msg:$tempId", optimistic, MessageStatus.Failed)
            renderTimeline()
            appState.present(R.string.toast_send_failed, AppText.Plain(throwable.message ?: throwable.javaClass.simpleName))
        } finally {
            sendInFlight = false
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

    suspend fun leaveGroup(): Boolean {
        if (mutationInFlight) return false
        val account = appState.activeAccountRef ?: return false
        if (!canLeaveGroup) {
            appState.present(R.string.toast_make_another_admin_before_leaving, R.string.toast_group_needs_admin)
            return false
        }
        mutationInFlight = true
        return try {
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
                appState.present(R.string.toast_couldnt_leave_chat, AppText.Plain(it.message ?: it.javaClass.simpleName))
                false
            }
        } finally {
            mutationInFlight = false
        }
    }

    suspend fun acceptInvite(): Boolean {
        val account = appState.activeAccountRef ?: return false
        return runCatching {
            group = appState.marmotIo { acceptGroupInvite(account, group.groupIdHex) }
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
            group = group.copy(name = name.trim(), description = description.trim())
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
        val account = appState.activeAccountRef ?: return
        if (!hasMoreBefore || isLoadingOlder) return
        val oldest = timelineRecords.values.minWithOrNull(
            compareBy<TimelineMessageRecordFfi> { it.timelineAt }.thenBy { it.messageIdHex },
        ) ?: return
        isLoadingOlder = true
        try {
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
        } catch (throwable: Throwable) {
            error = throwable.message ?: throwable.javaClass.simpleName
        } finally {
            isLoadingOlder = false
        }
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
        }
        page.messages.forEach { record ->
            timelineRecords[record.messageIdHex] = record
            val actionRecord = TimelineProjector.toAppMessageRecord(record)
            messageById[record.messageIdHex] = actionRecord
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
        val projected = timelineRecords.values.map { record ->
            val actionRecord = TimelineProjector.toAppMessageRecord(record)
            val streamId = MessageProjector.streamId(actionRecord).takeIf { MessageProjector.isStreamStart(actionRecord) }
            val displayRecord = if (streamId != null) {
                actionRecord.copy(plaintext = actionRecord.plaintext.ifBlank { copy.waitingForStream })
            } else {
                actionRecord
            }
            TimelineMessage(
                id = streamId?.let { "stream:$it" } ?: "msg:${record.messageIdHex}",
                record = displayRecord,
                status = when {
                    streamId != null -> MessageStatus.Streaming
                    MessageProjector.isMine(actionRecord, appState.activeAccount?.accountIdHex) -> MessageStatus.Sent
                    else -> MessageStatus.Received
                },
                projected = record,
            )
        }
        timeline = (optimisticMessages.values + projected)
            .distinctBy { it.id }
            .sortedWith(compareBy<TimelineMessage> { it.record.recordedAt }.thenBy { it.id })
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
        var text = ""
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
                when (update) {
                    is AgentStreamUpdateFfi.Chunk -> {
                        text += update.text
                        updateStreamPreview(streamId, text, MessageStatus.Streaming)
                    }
                    is AgentStreamUpdateFfi.Finished -> {
                        text = update.text
                        updateStreamPreview(streamId, text, MessageStatus.Sent)
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
        val id = "stream:$streamId"
        val existing = optimisticMessages[id]?.record ?: timeline.firstOrNull { it.id == id }?.record
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
        optimisticMessages[id] = TimelineMessage(id, record, status)
        renderTimeline()
    }

    private fun nowSeconds(): ULong = (System.currentTimeMillis() / 1000L).toULong()
}
