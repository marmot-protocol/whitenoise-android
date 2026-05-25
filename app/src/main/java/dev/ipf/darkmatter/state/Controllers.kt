package dev.ipf.darkmatter.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.darkmatter.core.GroupProjector
import dev.ipf.darkmatter.core.IdentityFormatter
import dev.ipf.darkmatter.core.MessageProjector
import dev.ipf.darkmatter.core.ReactionTally
import java.util.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AgentStreamUpdateFfi
import dev.ipf.marmotkit.AgentStreamSubscription
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MessageUpdateFfi
import dev.ipf.marmotkit.RuntimeMessageReceivedFfi

data class ChatListItem(
    val group: AppGroupRecordFfi,
    val latest: AppMessageRecordFfi?,
    val otherMemberAccount: String?,
    val memberCount: Int,
) {
    val id: String = group.groupIdHex
}

enum class MessageStatus {
    Received,
    Pending,
    Sent,
    Failed,
    Streaming,
}

data class TimelineMessage(
    val id: String,
    val record: AppMessageRecordFfi,
    val status: MessageStatus,
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
    private var groups = listOf<AppGroupRecordFfi>()
    private var latestByGroup = mapOf<String, AppMessageRecordFfi>()
    private var memberInfoByGroup = mapOf<String, Pair<Int, String?>>()

    suspend fun bind(accountRef: String?) {
        this.accountRef = accountRef
        groups = emptyList()
        latestByGroup = emptyMap()
        memberInfoByGroup = emptyMap()
        recompute()
        error = null

        if (accountRef == null) return
        isLoading = true
        try {
            val marmot = appState.marmot()
            val chatsSubscription = marmot.subscribeChats(accountRef, includeArchived = true)
            groups = chatsSubscription.snapshot()
            refreshLatest()
            for (group in groups) refreshMembers(group.groupIdHex)
            isLoading = false

            coroutineScope {
                launch {
                    while (isActive) {
                        val update = chatsSubscription.next() ?: break
                        foldGroup(update)
                        refreshMembers(update.groupIdHex)
                    }
                }
                launch {
                    val messagesSubscription = marmot.subscribeMessages(accountRef, groupIdHex = null)
                    while (isActive) {
                        messagesSubscription.next() ?: break
                        refreshLatest()
                    }
                }
            }
        } catch (throwable: Throwable) {
            isLoading = false
            error = throwable.message ?: throwable.javaClass.simpleName
        }
    }

    suspend fun refreshLatest() {
        val account = accountRef ?: return
        runCatching {
            val recent = appState.marmot().messages(account, groupIdHex = null, limit = 400u)
            appState.requestProfiles(recent.map { it.sender })
            latestByGroup = recent
                .groupBy { it.groupIdHex }
                .mapValues { (_, messages) -> messages.maxBy { it.recordedAt } }
            recompute()
        }
    }

    private fun foldGroup(record: AppGroupRecordFfi) {
        groups = if (groups.any { it.groupIdHex == record.groupIdHex }) {
            groups.map { if (it.groupIdHex == record.groupIdHex) record else it }
        } else {
            groups + record
        }
        recompute()
    }

    private suspend fun refreshMembers(groupIdHex: String) {
        val account = accountRef ?: return
        runCatching {
            val members = appState.marmot().groupMembers(account, groupIdHex)
            val me = appState.activeAccount?.accountIdHex
            val other = GroupProjector.otherMemberAccount(members, me)
            appState.requestProfiles(members.mapNotNull { it.account })
            memberInfoByGroup = memberInfoByGroup + (groupIdHex to (members.size to other))
            recompute()
        }
    }

    private fun recompute() {
        val all = groups.map { group ->
            val info = memberInfoByGroup[group.groupIdHex]
            ChatListItem(
                group = group,
                latest = latestByGroup[group.groupIdHex],
                otherMemberAccount = info?.second,
                memberCount = info?.first ?: 0,
            )
        }.sortedWith(
            compareByDescending<ChatListItem> { it.latest?.recordedAt ?: 0u }
                .thenBy { it.group.name.ifBlank { it.group.groupIdHex }.lowercase() },
        )
        items = all.filter { !it.group.archived }
        archivedItems = all.filter { it.group.archived }
    }
}

class ConversationController(
    private val appState: DarkMatterAppState,
    initialGroup: AppGroupRecordFfi,
) {
    var group by mutableStateOf(initialGroup)
        private set
    var members by mutableStateOf<List<AppGroupMemberRecordFfi>>(emptyList())
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
        get() {
            val me = appState.activeAccount?.accountIdHex
            val other = GroupProjector.otherMemberAccount(members, me)
            return GroupProjector.displayTitle(
                group = group,
                otherMemberAccount = other,
                memberCount = members.size,
                memberTitle = { appState.chatMemberTitle(it) },
            )
        }

    val subtitle: String
        get() {
            val count = members.size
            return when (count) {
                0 -> "Just you"
                1 -> "1 member"
                else -> "$count members"
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
        try {
            val marmot = appState.marmot()
            val messagesSubscription = marmot.subscribeMessages(account, group.groupIdHex)
            val snapshotStreamIds = linkedSetOf<String>()
            messagesSubscription.snapshot().forEach { record ->
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

            val groupSubscription = marmot.subscribeGroupState(account, group.groupIdHex)
            groupSubscription.snapshot()?.let { group = it }
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
                        val update = messagesSubscription.next() ?: break
                        fold(update) { streamId ->
                            launch { watchAgentTextStream(account, streamId) }
                        }
                    }
                }
                launch {
                    while (isActive) {
                        val update = groupSubscription.next() ?: break
                        group = update
                        refreshMembers()
                    }
                }
            }
        } catch (throwable: Throwable) {
            isLoading = false
            error = throwable.message ?: throwable.javaClass.simpleName
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
                appState.marmot().replyToMessage(account, group.groupIdHex, replyTarget, trimmed)
            } else {
                appState.marmot().sendText(account, group.groupIdHex, trimmed)
            }
            val confirmedId = summary.messageIds.firstOrNull() ?: tempId
            val confirmed = optimistic.copy(messageIdHex = confirmedId)
            if (confirmedId.isNotEmpty()) messageById[confirmedId] = confirmed
            replace("msg:$tempId", TimelineMessage("msg:$confirmedId", confirmed, MessageStatus.Sent))
        } catch (throwable: Throwable) {
            replace("msg:$tempId", TimelineMessage("msg:$tempId", optimistic, MessageStatus.Failed))
            appState.present("Send failed", throwable.message ?: throwable.javaClass.simpleName)
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
                appState.marmot().unreactFromMessage(account, group.groupIdHex, target)
            } else {
                appState.marmot().reactToMessage(account, group.groupIdHex, target, emoji)
            }
        } catch (throwable: Throwable) {
            reactionRecords.remove(synthetic.messageIdHex)
            recomputeReactions()
            appState.present("Reaction failed", throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    suspend fun deleteMessage(message: AppMessageRecordFfi) {
        val account = appState.activeAccountRef ?: return
        val target = message.messageIdHex.takeIf { it.isNotBlank() } ?: return
        deletedMessageIds = deletedMessageIds + target
        try {
            appState.marmot().deleteMessage(account, group.groupIdHex, target)
        } catch (throwable: Throwable) {
            deletedMessageIds = deletedMessageIds - target
            appState.present("Couldn't delete message", throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    suspend fun leaveGroup(): Boolean {
        val account = appState.activeAccountRef ?: return false
        if (!canLeaveGroup) {
            appState.present("Make another admin before leaving", "A group needs at least one admin.")
            return false
        }
        return runCatching {
            appState.marmot().leaveGroup(account, group.groupIdHex)
            appState.present("Left chat")
            true
        }.getOrElse {
            appState.present("Couldn't leave chat", it.message ?: it.javaClass.simpleName)
            false
        }
    }

    suspend fun setArchived(archived: Boolean) {
        val account = appState.activeAccountRef ?: return
        runCatching {
            group = appState.marmot().setGroupArchived(account, group.groupIdHex, archived)
            appState.present(if (archived) "Chat archived" else "Chat restored")
        }.onFailure {
            appState.present("Couldn't update chat", it.message ?: it.javaClass.simpleName)
        }
    }

    suspend fun updateGroupProfile(name: String, description: String) {
        val account = appState.activeAccountRef ?: return
        runCatching {
            appState.marmot().updateGroupProfile(
                account,
                group.groupIdHex,
                name.trim().takeIf { it.isNotEmpty() },
                description.trim().takeIf { it.isNotEmpty() },
            )
            group = group.copy(name = name.trim(), description = description.trim())
            appState.present("Group updated")
        }.onFailure {
            appState.present("Couldn't update group", it.message ?: it.javaClass.simpleName)
        }
    }

    suspend fun inviteMembers(memberRefs: List<String>) {
        val account = appState.activeAccountRef ?: return
        val refs = memberRefs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (refs.isEmpty()) return
        runCatching {
            appState.marmot().inviteMembers(account, group.groupIdHex, refs)
            refreshMembers()
            appState.present("Invite sent")
        }.onFailure {
            appState.present("Couldn't add members", it.message ?: it.javaClass.simpleName)
        }
    }

    suspend fun removeMember(member: AppGroupMemberRecordFfi) {
        val account = appState.activeAccountRef ?: return
        val target = GroupProjector.memberRef(member)
        runCatching {
            appState.marmot().removeMembers(account, group.groupIdHex, listOf(target))
            refreshMembers()
            appState.present("Member removed")
        }.onFailure {
            appState.present("Couldn't remove member", it.message ?: it.javaClass.simpleName)
        }
    }

    suspend fun setMemberAdmin(member: AppGroupMemberRecordFfi, admin: Boolean) {
        val account = appState.activeAccountRef ?: return
        val target = GroupProjector.memberRef(member)
        if (!admin && isAdmin(member) && group.admins.size <= 1) {
            appState.present("Keep one admin", "Promote another member before removing this admin.")
            return
        }
        runCatching {
            if (admin) {
                appState.marmot().promoteAdmin(account, group.groupIdHex, target)
                group = group.copy(admins = (group.admins + target).distinct())
                appState.present("Admin added")
            } else {
                appState.marmot().demoteAdmin(account, group.groupIdHex, target)
                group = group.copy(admins = group.admins.filterNot { it == target || it == member.memberIdHex })
                appState.present("Admin removed")
            }
            refreshMembers()
        }.onFailure {
            appState.present("Couldn't update admin", it.message ?: it.javaClass.simpleName)
        }
    }

    fun isAdmin(member: AppGroupMemberRecordFfi): Boolean = GroupProjector.isAdmin(group, member)

    fun memberDisplayName(member: AppGroupMemberRecordFfi): String {
        val account = member.account ?: return IdentityFormatter.short(member.memberIdHex)
        return appState.displayName(account)
    }

    fun memberSubtitle(member: AppGroupMemberRecordFfi): String {
        val account = member.account
        return when {
            account != null -> appState.shortNpub(account)
            else -> IdentityFormatter.short(member.memberIdHex)
        }
    }

    fun memberAvatarUrl(member: AppGroupMemberRecordFfi): String? {
        return member.account?.let { appState.avatarUrl(it) }
    }

    suspend fun groupMlsState(): AppGroupMlsStateFfi? {
        val account = appState.activeAccountRef ?: return null
        return runCatching {
            appState.marmot().groupMlsState(account, group.groupIdHex)
        }.onFailure {
            appState.present("Couldn't load MLS state", it.message ?: it.javaClass.simpleName)
        }.getOrNull()
    }

    fun replyPreview(record: AppMessageRecordFfi): Pair<String, String>? {
        val targetMessageId = MessageProjector.replyTargetMessageId(record) ?: return null
        val target = messageById[targetMessageId] ?: return null
        return appState.displayName(target.sender) to MessageProjector.displayBody(target)
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
        val preview = record.copy(plaintext = record.plaintext.ifBlank { "Waiting for stream..." })
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
            members = appState.marmot().groupMembers(account, group.groupIdHex)
            appState.requestProfiles(members.mapNotNull { it.account })
        }
    }

    private fun receivedToRecord(received: RuntimeMessageReceivedFfi): AppMessageRecordFfi {
        val now = nowSeconds()
        return AppMessageRecordFfi(
            messageIdHex = received.message.messageIdHex,
            direction = "received",
            groupIdHex = received.message.groupIdHex,
            sender = received.message.sender,
            plaintext = received.message.plaintext,
            kind = received.message.kind,
            tags = received.message.tags,
            recordedAt = now,
            receivedAt = now,
        )
    }

    private suspend fun watchAgentTextStream(account: String, streamId: String) {
        var text = ""
        var subscription: AgentStreamSubscription? = null
        try {
            subscription = appState.marmot().watchAgentTextStream(
                accountRef = account,
                groupIdHex = group.groupIdHex,
                streamIdHex = streamId,
                serverCertDer = null,
                insecureLocal = false,
            )
            while (true) {
                when (val update = subscription.next() ?: break) {
                    is AgentStreamUpdateFfi.Chunk -> {
                        text += update.text
                        updateStreamPreview(streamId, text, MessageStatus.Streaming)
                    }
                    is AgentStreamUpdateFfi.Finished -> {
                        text = update.text
                        updateStreamPreview(streamId, text, MessageStatus.Sent)
                    }
                    is AgentStreamUpdateFfi.Failed -> {
                        updateStreamPreview(streamId, "Stream failed: ${update.message}", MessageStatus.Failed)
                    }
                }
            }
        } catch (throwable: Throwable) {
            updateStreamPreview(
                streamId,
                "Stream failed: ${throwable.message ?: throwable.javaClass.simpleName}",
                MessageStatus.Failed,
            )
        } finally {
            subscription?.close()
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
