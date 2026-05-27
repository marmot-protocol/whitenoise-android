package dev.ipf.darkmatter.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.darkmatter.R
import dev.ipf.darkmatter.core.GroupProjector
import dev.ipf.darkmatter.core.MessageProjector
import dev.ipf.darkmatter.core.ReactionTally
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import dev.ipf.marmotkit.ChatsSubscription
import dev.ipf.marmotkit.GroupStateSubscription
import dev.ipf.marmotkit.MessageUpdateFfi
import dev.ipf.marmotkit.MessagesSubscription
import dev.ipf.marmotkit.RuntimeMessageReceivedFfi
import dev.ipf.darkmatter.core.MessageTextCopy

data class ChatListItem(
    val group: AppGroupRecordFfi,
    val latest: AppMessageRecordFfi?,
    val otherMemberAccount: String?,
    val memberCount: Int,
    val memberSnapshot: GroupMemberSnapshot?,
) {
    val id: String = group.groupIdHex
}

internal fun sortChatListItems(items: List<ChatListItem>): List<ChatListItem> {
    return items.sortedWith(
        compareByDescending<ChatListItem> { it.group.pendingConfirmation }
            .thenByDescending { it.latest?.recordedAt ?: 0uL }
            .thenBy { it.group.name.ifBlank { it.group.groupIdHex }.lowercase() },
    )
}

internal fun latestMessagesAfterStreamUpdate(
    latestByGroup: Map<String, AppMessageRecordFfi>,
    update: MessageUpdateFfi,
    recordedAt: ULong,
): Map<String, AppMessageRecordFfi> {
    return latestMessagesAfterRecord(latestByGroup, appMessageRecordFromStreamUpdate(update, recordedAt))
}

private fun latestMessagesAfterRecord(
    latestByGroup: Map<String, AppMessageRecordFfi>,
    record: AppMessageRecordFfi,
): Map<String, AppMessageRecordFfi> {
    val current = latestByGroup[record.groupIdHex]
    if (current != null && current.recordedAt > record.recordedAt) return latestByGroup
    return latestByGroup + (record.groupIdHex to record)
}

internal fun appMessageRecordFromStreamUpdate(update: MessageUpdateFfi, recordedAt: ULong): AppMessageRecordFfi {
    return appMessageRecordFromReceived(streamReceived(update), recordedAt)
}

private fun streamReceived(update: MessageUpdateFfi): RuntimeMessageReceivedFfi {
    return when (update) {
        is MessageUpdateFfi.Message -> update.received
        is MessageUpdateFfi.AgentStreamStarted -> update.received
    }
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

private fun currentUnixSeconds(): ULong = (System.currentTimeMillis() / 1000L).toULong()

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
)

data class ConversationControllerCopy(
    val waitingForStream: String = "Waiting for stream...",
    val streamFailedFormat: String = "Stream failed: %1\$s",
) {
    fun streamFailed(message: String): String = String.format(streamFailedFormat, message)
}

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
    private var groups = listOf<AppGroupRecordFfi>()
    private var latestByGroup = mapOf<String, AppMessageRecordFfi>()
    private var memberInfoByGroup = mapOf<String, GroupMemberSnapshot>()

    suspend fun bind(accountRef: String?) {
        chatsDebug { "bind account=${accountRef?.take(8)}" }
        this.accountRef = accountRef
        groups = emptyList()
        latestByGroup = emptyMap()
        memberInfoByGroup = emptyMap()
        recompute()
        error = null

        if (accountRef == null) return
        isLoading = true
        var chatsSubscription: ChatsSubscription? = null
        var messagesSubscription: MessagesSubscription? = null
        try {
            val chatStream = appState.marmotIo { subscribeChats(accountRef, includeArchived = true) }
            chatsSubscription = chatStream
            val messageStream = appState.marmotIo { subscribeMessages(accountRef, groupIdHex = null) }
            messagesSubscription = messageStream
            groups = withContext(Dispatchers.IO) {
                chatStream.snapshot()
            }
            groups.forEach(::requestGroupProfiles)
            chatsDebug {
                "snapshot account=${accountRef.take(8)} groups=${groups.size} ${groups.map { it.debugSummary() }}"
            }
            seedCachedMembers()
            refreshLatest(recomputeAfter = false)
            chatsDebug { "latest snapshot account=${accountRef.take(8)} groups=${latestByGroup.size}" }
            isLoading = false
            recompute()

            coroutineScope {
                launch {
                    refreshMembers(groups.map { it.groupIdHex })
                }
                launch {
                    while (isActive) {
                        val update = withContext(Dispatchers.IO) {
                            chatStream.next()
                        } ?: break
                        requestGroupProfiles(update)
                        chatsDebug { "chat update account=${accountRef.take(8)} ${update.debugSummary()}" }
                        val hasMemberSnapshot = seedCachedMember(update.groupIdHex)
                        foldGroup(update, recomputeNow = hasMemberSnapshot)
                        refreshMembers(listOf(update.groupIdHex))
                    }
                }
                launch {
                    while (isActive) {
                        val update = withContext(Dispatchers.IO) {
                            messageStream.next()
                        } ?: break
                        val record = appMessageRecordFromStreamUpdate(update, currentUnixSeconds())
                        chatsDebug {
                            "message update account=${accountRef.take(8)} group=${record.groupIdHex.take(8)} " +
                                "message=${record.messageIdHex.take(8)} kind=${record.kind} sender=${record.sender.take(8)}"
                        }
                        appState.requestProfile(record.sender)
                        latestByGroup = latestMessagesAfterRecord(latestByGroup, record)
                        recompute()
                    }
                }
            }
        } catch (throwable: Throwable) {
            chatsDebug(throwable) { "bind failed account=${accountRef.take(8)}: ${throwable.message ?: throwable.javaClass.simpleName}" }
            isLoading = false
            error = throwable.message ?: throwable.javaClass.simpleName
        } finally {
            withContext(Dispatchers.IO) {
                runCatching { messagesSubscription?.close() }
                runCatching { chatsSubscription?.close() }
            }
        }
    }

    suspend fun refreshLatest(recomputeAfter: Boolean = true) {
        val account = accountRef ?: return
        runCatching {
            val recent = appState.marmotIo { messages(account, groupIdHex = null, limit = 400u) }
            appState.requestProfiles(recent.map { it.sender })
            latestByGroup = recent
                .groupBy { it.groupIdHex }
                .mapValues { (_, messages) -> messages.maxBy { it.recordedAt } }
            if (recomputeAfter) recompute()
        }
    }

    private fun foldGroup(record: AppGroupRecordFfi, recomputeNow: Boolean = true) {
        groups = if (groups.any { it.groupIdHex == record.groupIdHex }) {
            groups.map { if (it.groupIdHex == record.groupIdHex) record else it }
        } else {
            groups + record
        }
        if (recomputeNow) recompute()
    }

    private fun requestGroupProfiles(group: AppGroupRecordFfi) {
        appState.requestProfiles(
            listOfNotNull(group.welcomerAccountIdHex) + group.admins,
        )
    }

    private fun seedCachedMembers(): Boolean {
        if (groups.isEmpty()) return true
        val cachedCount = groups.count { seedCachedMember(it.groupIdHex) }
        val complete = cachedCount == groups.size
        if (complete) {
            recompute()
        }
        return complete
    }

    private fun seedCachedMember(groupIdHex: String): Boolean {
        val account = accountRef ?: return false
        val snapshot = appState.cachedGroupMemberSnapshot(account, groupIdHex) ?: return false
        memberInfoByGroup = memberInfoByGroup + (groupIdHex to snapshot)
        return true
    }

    private suspend fun refreshMembers(groupIds: List<String>) {
        val account = accountRef ?: return
        val loaded = coroutineScope {
            groupIds.distinct().map { groupIdHex ->
                async { loadMembers(account, groupIdHex) }
            }.awaitAll().filterNotNull()
        }
        if (loaded.isNotEmpty()) {
            memberInfoByGroup = memberInfoByGroup + loaded.toMap()
            recompute()
        }
    }

    private suspend fun loadMembers(account: String, groupIdHex: String): Pair<String, GroupMemberSnapshot>? {
        return runCatching {
            val members = appState.marmotIo { groupMembers(account, groupIdHex) }
            val snapshot = appState.cacheGroupMemberSnapshot(account, groupIdHex, members)
            appState.requestProfiles(members.map { it.memberIdHex })
            groupIdHex to snapshot
        }.getOrNull()
    }

    private fun recompute() {
        val active = appState.activeAccount?.accountIdHex
        val all = groups.map { group ->
            val snapshot = memberInfoByGroup[group.groupIdHex]
            ChatListItem(
                group = group,
                latest = latestByGroup[group.groupIdHex],
                otherMemberAccount = snapshot?.otherMemberAccount(active),
                memberCount = snapshot?.memberCount ?: 0,
                memberSnapshot = snapshot,
            )
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

private inline fun chatsDebug(message: () -> String) {
    Log.i("DMChats", message())
}

private inline fun chatsDebug(error: Throwable, message: () -> String) {
    Log.e("DMChats", message(), error)
}

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
    var sendInFlight by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val messageById = linkedMapOf<String, AppMessageRecordFfi>()
    private val reactionRecords = linkedMapOf<String, AppMessageRecordFfi>()
    private val activeStreamIds = mutableSetOf<String>()

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
        get() = appState.activeAccount?.accountIdHex?.let { group.admins.contains(it) } == true

    val canLeaveGroup: Boolean
        get() = GroupProjector.canLeaveGroup(group, appState.activeAccount?.accountIdHex)

    suspend fun start() {
        val account = appState.activeAccountRef ?: return
        isLoading = true
        error = null
        var messagesSubscription: MessagesSubscription? = null
        var groupSubscription: GroupStateSubscription? = null
        try {
            val messageStream = appState.marmotIo { subscribeMessages(account, group.groupIdHex) }
            messagesSubscription = messageStream
            val snapshotStreamIds = linkedSetOf<String>()
            val snapshot = withContext(Dispatchers.IO) {
                messageStream.snapshot()
            }
            snapshot.forEach { record ->
                ingest(record)
                when {
                    MessageProjector.isStreamStart(record) -> {
                        MessageProjector.streamId(record)?.let { snapshotStreamIds.add(it) }
                    }
                    MessageProjector.isStreamFinal(record) -> {
                        MessageProjector.streamId(record)?.let { snapshotStreamIds.remove(it) }
                    }
                }
            }

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
                            messageStream.next()
                        } ?: break
                        fold(update) { streamId ->
                            launch { watchAgentTextStream(account, streamId) }
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
        } catch (throwable: Throwable) {
            isLoading = false
            error = throwable.message ?: throwable.javaClass.simpleName
        } finally {
            withContext(Dispatchers.IO) {
                runCatching { groupSubscription?.close() }
                runCatching { messagesSubscription?.close() }
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
        upsert(TimelineMessage("msg:$tempId", optimistic, MessageStatus.Pending))
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
            replace("msg:$tempId", TimelineMessage("msg:$confirmedId", confirmed, MessageStatus.Sent))
        } catch (throwable: Throwable) {
            replace("msg:$tempId", TimelineMessage("msg:$tempId", optimistic, MessageStatus.Failed))
            appState.present(R.string.toast_send_failed, AppText.Plain(throwable.message ?: throwable.javaClass.simpleName))
        } finally {
            sendInFlight = false
        }
    }

    suspend fun toggleReaction(emoji: String, message: AppMessageRecordFfi) {
        val account = appState.activeAccountRef ?: return
        val target = message.messageIdHex.takeIf { it.isNotBlank() } ?: return
        val alreadyMine = reactions[target]?.any { it.emoji == emoji && it.mine } == true
        val synthetic = AppMessageRecordFfi(
            messageIdHex = "optimistic-${UUID.randomUUID()}",
            direction = "sent",
            groupIdHex = group.groupIdHex,
            sender = appState.activeAccount?.accountIdHex ?: "",
            plaintext = if (alreadyMine) "" else emoji,
            kind = if (alreadyMine) 5uL else 7uL,
            tags = listOf(MessageProjector.eventTag(target)),
            recordedAt = nowSeconds(),
            receivedAt = nowSeconds(),
        )
        reactionRecords[synthetic.messageIdHex] = synthetic
        recomputeReactions()
        try {
            if (alreadyMine) {
                appState.marmotIo { unreactFromMessage(account, group.groupIdHex, target) }
            } else {
                appState.marmotIo { reactToMessage(account, group.groupIdHex, target, emoji) }
            }
        } catch (throwable: Throwable) {
            reactionRecords.remove(synthetic.messageIdHex)
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
        val account = appState.activeAccountRef ?: return false
        if (!canLeaveGroup) {
            appState.present(R.string.toast_make_another_admin_before_leaving, R.string.toast_group_needs_admin)
            return false
        }
        return runCatching {
            val activeAccountIdHex = appState.activeAccount?.accountIdHex
            if (GroupProjector.requiresSelfDemoteBeforeLeave(group, activeAccountIdHex)) {
                appState.marmotIo { selfDemoteAdmin(account, group.groupIdHex) }
                group = group.copy(admins = group.admins.filterNot { it == activeAccountIdHex })
            }
            appState.marmotIo { leaveGroup(account, group.groupIdHex) }
            appState.present(R.string.toast_left_chat)
            true
        }.getOrElse {
            appState.present(R.string.toast_couldnt_leave_chat, AppText.Plain(it.message ?: it.javaClass.simpleName))
            false
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

    suspend fun setArchived(archived: Boolean) {
        val account = appState.activeAccountRef ?: return
        runCatching {
            group = appState.marmotIo { setGroupArchived(account, group.groupIdHex, archived) }
            appState.present(if (archived) R.string.toast_chat_archived else R.string.toast_chat_restored)
        }.onFailure {
            appState.present(R.string.toast_couldnt_update_chat, AppText.Plain(it.message ?: it.javaClass.simpleName))
        }
    }

    suspend fun updateGroupProfile(name: String, description: String) {
        val account = appState.activeAccountRef ?: return
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

    suspend fun inviteMembers(memberRefs: List<String>) {
        val account = appState.activeAccountRef ?: return
        val refs = memberRefs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (refs.isEmpty()) return
        runCatching {
            appState.marmotIo { inviteMembers(account, group.groupIdHex, refs) }
            refreshMembers()
            appState.present(R.string.toast_invite_sent)
        }.onFailure {
            appState.present(R.string.toast_couldnt_add_members, AppText.Plain(it.message ?: it.javaClass.simpleName))
        }
    }

    suspend fun removeMember(member: AppGroupMemberRecordFfi) {
        val account = appState.activeAccountRef ?: return
        val target = GroupProjector.memberRef(member)
        runCatching {
            appState.marmotIo { removeMembers(account, group.groupIdHex, listOf(target)) }
            refreshMembers()
            appState.present(R.string.toast_member_removed)
        }.onFailure {
            appState.present(R.string.toast_couldnt_remove_member, AppText.Plain(it.message ?: it.javaClass.simpleName))
        }
    }

    suspend fun setMemberAdmin(member: AppGroupMemberRecordFfi, admin: Boolean) {
        val account = appState.activeAccountRef ?: return
        val target = GroupProjector.memberRef(member)
        if (!admin && isAdmin(member) && group.admins.size <= 1) {
            appState.present(R.string.toast_keep_one_admin, R.string.toast_promote_before_removing_admin)
            return
        }
        runCatching {
            if (admin) {
                appState.marmotIo { promoteAdmin(account, group.groupIdHex, target) }
                group = group.copy(admins = (group.admins + target).distinct())
                appState.present(R.string.toast_admin_added)
            } else {
                appState.marmotIo { demoteAdmin(account, group.groupIdHex, target) }
                group = group.copy(admins = group.admins.filterNot { it == target || it == member.memberIdHex })
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

    fun replyPreview(record: AppMessageRecordFfi, copy: MessageTextCopy = MessageTextCopy.Default): Pair<String, String>? {
        val targetMessageId = MessageProjector.replyTargetMessageId(record) ?: return null
        val target = messageById[targetMessageId] ?: return null
        return appState.displayName(target.sender) to MessageProjector.displayBody(target, copy)
    }

    private fun fold(update: MessageUpdateFfi, startStreamWatch: (String) -> Unit) {
        when (update) {
            is MessageUpdateFfi.Message -> ingest(receivedToRecord(update.received))
            is MessageUpdateFfi.AgentStreamStarted -> {
                val record = receivedToRecord(update.received)
                ingestStreamStart(record)
                val streamId = MessageProjector.streamId(record)
                if (streamId != null && activeStreamIds.add(streamId)) {
                    startStreamWatch(streamId)
                }
            }
        }
    }

    private fun ingest(record: AppMessageRecordFfi, status: MessageStatus = MessageStatus.Received) {
        appState.requestProfile(record.sender)
        if (record.messageIdHex.isNotBlank()) messageById[record.messageIdHex] = record
        when {
            MessageProjector.isStreamStart(record) -> ingestStreamStart(record)
            MessageProjector.isStreamFinal(record) -> {
                MessageProjector.streamId(record)?.let { streamId ->
                    activeStreamIds.remove(streamId)
                    remove("stream:$streamId")
                }
                upsert(TimelineMessage("msg:${record.messageIdHex}", record, status))
            }
            MessageProjector.isReaction(record) -> {
                reactionRecords[record.messageIdHex.ifBlank { UUID.randomUUID().toString() }] = record
                recomputeReactions()
            }
            MessageProjector.isDelete(record) -> {
                deletedMessageIds = deletedMessageIds + MessageProjector.deletedTargetMessageIds(record)
                reactionRecords[record.messageIdHex.ifBlank { UUID.randomUUID().toString() }] = record
                recomputeReactions()
            }
            else -> upsert(TimelineMessage("msg:${record.messageIdHex}", record, status))
        }
    }

    private fun ingestStreamStart(record: AppMessageRecordFfi) {
        val streamId = MessageProjector.streamId(record)
        val id = streamId?.let { "stream:$it" } ?: "msg:${record.messageIdHex}"
        val preview = record.copy(plaintext = record.plaintext.ifBlank { copy.waitingForStream })
        upsert(TimelineMessage(id, preview, MessageStatus.Streaming))
    }

    private fun upsert(message: TimelineMessage) {
        timeline = (timeline.filterNot { it.id == message.id } + message)
            .sortedBy { it.record.recordedAt }
    }

    private fun replace(id: String, replacement: TimelineMessage) {
        timeline = timeline.map { if (it.id == id) replacement else it }
            .sortedBy { it.record.recordedAt }
    }

    private fun remove(id: String) {
        timeline = timeline.filterNot { it.id == id }
    }

    private fun recomputeReactions() {
        val mine = appState.activeAccount?.accountIdHex
        val messages = reactionRecords.values.toList()
        reactions = messageById.keys.associateWith { target ->
            MessageProjector.reactionTallies(messages, target, mine)
        }.filterValues { it.isNotEmpty() }
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

    private fun receivedToRecord(received: RuntimeMessageReceivedFfi): AppMessageRecordFfi {
        return appMessageRecordFromReceived(received, nowSeconds())
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
        val existing = timeline.firstOrNull { it.id == id }?.record
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
        if (timeline.any { it.id == id }) {
            replace(id, TimelineMessage(id, record, status))
        } else {
            upsert(TimelineMessage(id, record, status))
        }
    }

    private fun nowSeconds(): ULong = (System.currentTimeMillis() / 1000L).toULong()
}
