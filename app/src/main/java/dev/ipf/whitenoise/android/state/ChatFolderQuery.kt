package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListConditionFfi
import dev.ipf.marmotkit.ChatListFilterNodeFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListViewQueryFfi
import java.util.Locale

/** MarmotKit's query limits: nodes, nesting levels, group IDs and bytes of search text across one query. */
private const val QUERY_MAX_NODES = 64
private const val QUERY_MAX_DEPTH = 8
private const val QUERY_MAX_GROUP_IDS = 500
private const val QUERY_MAX_TEXT_BYTES = 1024

/**
 * One folder as a native chat-list query: the same filter over each view the folder covers. MarmotKit matches it in
 * SQL over every chat in a view before paging, so the folder's list, count and select-all agree. A legacy rule covers
 * active or archived chats, and an advanced rule covers both.
 */
internal data class ChatFolderQuery(
    val views: List<ChatListViewFfi>,
    val filter: List<ChatListFilterNodeFfi>,
) {
    /** Whether the folder is every chat in its one view, so the plain window already holds it and needs no other. */
    val wholeView: Boolean get() = views.size == 1 && filter == WHOLE_VIEW

    /** The query for one of [views]. */
    fun forView(view: ChatListViewFfi): ChatListViewQueryFfi = ChatListViewQueryFfi(view, filter)

    /** The same folder narrowed to chats showing an unread badge, for the folder chip's count. */
    fun unread(): ChatFolderQuery = copy(filter = flatten(FolderNode.All(listOf(unflatten(0), UNREAD))))

    /** Rebuilds the tree below [index] so a narrowed query can wrap it. */
    private fun unflatten(index: Int): FolderNode =
        when (val node = filter[index]) {
            is ChatListFilterNodeFfi.All -> FolderNode.All(node.children.map { unflatten(it.toInt()) })
            is ChatListFilterNodeFfi.Any -> FolderNode.Any(node.children.map { unflatten(it.toInt()) })
            is ChatListFilterNodeFfi.Not -> FolderNode.Not(unflatten(node.child.toInt()))
            is ChatListFilterNodeFfi.Condition -> FolderNode.Is(node.condition)
            is ChatListFilterNodeFfi.GroupIds -> FolderNode.Ids(node.groupIds)
            is ChatListFilterNodeFfi.Text -> FolderNode.Text(node.text)
            is ChatListFilterNodeFfi.MessageText -> error("Folders never search message text")
        }

    private companion object {
        val UNREAD = FolderNode.Is(ChatListConditionFfi.UNREAD)
        val WHOLE_VIEW = flatten(FolderNode.All(listOf(FolderNode.Any(listOf(FolderNode.All(emptyList()))))))
    }
}

/**
 * What a complete Select all reads: a whole native [view], or a folder's native query. [view] also names which view
 * the selection behaves like for its bulk actions, Chats for a folder spanning both active and archived chats.
 */
internal data class ChatListSelectAllScope(
    val view: ChatListViewFfi,
    val folder: ChatFolderQuery? = null,
) {
    companion object {
        /** The scope of every chat [folder] matches. */
        fun of(folder: ChatFolderQuery): ChatListSelectAllScope {
            val view = folder.views.singleOrNull() ?: ChatListViewFfi.CHATS
            return ChatListSelectAllScope(view, folder)
        }
    }
}

/**
 * Translates a folder into its native query: manual members are added and exclusions removed around the rule, as
 * `All[Not(excluded), Any[included, rule]]`. Returns null when the folder must stay on the loaded-rows evaluator —
 * its rule tests participants, which MarmotKit cannot match yet, or it exceeds MarmotKit's query limits.
 */
internal fun chatFolderQuery(
    rule: ChatFolderRule?,
    manualChatIds: Set<String>,
    excludedChatIds: Set<String>,
): ChatFolderQuery? {
    val advanced = rule?.smartFilter
    val decoded = advanced?.let { SmartFolderCodec.decode(it)?.takeIf(SmartFolderCodec::valid) }
    val legacyParticipants = advanced == null && rule?.includeMemberPubkeys?.isNotEmpty() == true
    if (legacyParticipants || decoded?.references(FolderField.PARTICIPANTS) == true) return null
    val match =
        when {
            rule == null -> null
            advanced != null -> decoded?.let(::smartFolderNode)
            else -> legacyRuleNode(rule)
        }
    val included = listOfNotNull(manualChatIds.toIdNode(), match)
    val root = FolderNode.All(listOfNotNull(excludedChatIds.toIdNode()?.let(FolderNode::Not), FolderNode.Any(included)))
    val views =
        when {
            advanced != null -> listOf(ChatListViewFfi.CHATS, ChatListViewFfi.ARCHIVED)
            rule?.archivedOnly == true -> listOf(ChatListViewFfi.ARCHIVED)
            else -> listOf(ChatListViewFfi.CHATS)
        }
    return root.takeIf(FolderNode::withinLimits)?.let { ChatFolderQuery(views, flatten(it)) }
}

/**
 * A legacy rule: its keyword, or every chat when it is a pure category rule, narrowed by each selected category. A
 * rule with neither matches nothing and the folder stays manual-only.
 */
private fun legacyRuleNode(rule: ChatFolderRule): FolderNode? {
    val keyword = rule.keyword?.trim()?.takeIf { it.isNotEmpty() }
    val categories =
        listOfNotNull(
            ChatListConditionFfi.UNREAD.takeIf { rule.unreadOnly },
            ChatListConditionFfi.MENTION.takeIf { rule.unreadMentionsOnly },
            ChatListConditionFfi.PINNED.takeIf { rule.pinnedOnly },
            ChatListConditionFfi.GROUP.takeIf { rule.groupsOnly },
            ChatListConditionFfi.DIRECT.takeIf { rule.directChatsOnly },
        ).map(FolderNode::Is)
    val selects = keyword != null || rule.includeAll || categories.isNotEmpty() || rule.archivedOnly
    if (!selects) return null
    val muted = FolderNode.Not(FolderNode.Is(ChatListConditionFfi.MUTED)).takeUnless { rule.includeMuted }
    return FolderNode.All(listOfNotNull(keyword?.let(FolderNode::Text)) + categories + listOfNotNull(muted))
}

/** An advanced filter tree, or null when it tests participants. An empty group matches nothing, even negated. */
private fun smartFolderNode(filter: SmartFolderFilter): FolderNode? =
    when (filter) {
        is SmartFolderFilter.Group -> {
            val children = filter.children.map { smartFolderNode(it) ?: return null }
            when {
                children.isEmpty() -> FolderNode.Any(emptyList())
                filter.all -> FolderNode.All(children).negatedIf(filter.not)
                else -> FolderNode.Any(children).negatedIf(filter.not)
            }
        }
        is SmartFolderFilter.Condition ->
            smartConditionNode(filter)?.negatedIf(filter.mode == FolderMode.NONE)?.negatedIf(filter.not)
    }

/** One advanced condition in its present sense, or null for participants. */
private fun smartConditionNode(condition: SmartFolderFilter.Condition): FolderNode? =
    when (condition.field) {
        FolderField.UNREAD -> FolderNode.Is(ChatListConditionFfi.UNREAD)
        FolderField.MENTIONS -> FolderNode.Is(ChatListConditionFfi.MENTION)
        FolderField.MUTED -> FolderNode.Is(ChatListConditionFfi.MUTED)
        FolderField.PINNED -> FolderNode.Is(ChatListConditionFfi.PINNED)
        FolderField.ARCHIVED -> FolderNode.Is(ChatListConditionFfi.ARCHIVED)
        FolderField.DRAFT -> FolderNode.Is(ChatListConditionFfi.HAS_DRAFT)
        FolderField.PENDING_SEND -> FolderNode.Is(ChatListConditionFfi.PENDING_SEND)
        FolderField.TYPE ->
            FolderNode.Is(
                if (condition.mode == FolderMode.DIRECT) ChatListConditionFfi.DIRECT else ChatListConditionFfi.GROUP,
            )
        FolderField.TITLE -> FolderNode.Text(condition.values.single())
        // A joined, settled membership: no invitation awaiting an answer and no leave or disband outstanding.
        FolderField.ACCEPTED ->
            FolderNode.All(
                listOf(
                    ChatListConditionFfi.PENDING_INVITE,
                    ChatListConditionFfi.PENDING_LEAVE,
                    ChatListConditionFfi.DISBANDING,
                ).map { FolderNode.Not(FolderNode.Is(it)) },
            )
        FolderField.PARTICIPANTS -> null
    }

/** A filter tree before it is flattened into MarmotKit's indexed node list. */
private sealed interface FolderNode {
    data class All(
        val children: List<FolderNode>,
    ) : FolderNode

    data class Any(
        val children: List<FolderNode>,
    ) : FolderNode

    data class Not(
        val child: FolderNode,
    ) : FolderNode

    data class Is(
        val condition: ChatListConditionFfi,
    ) : FolderNode

    data class Ids(
        val groupIds: List<String>,
    ) : FolderNode

    data class Text(
        val text: String,
    ) : FolderNode

    /** Every node in this tree, this one included. */
    fun nodes(): Sequence<FolderNode> =
        sequenceOf(this) +
            when (this) {
                is All -> children.asSequence().flatMap { it.nodes() }
                is Any -> children.asSequence().flatMap { it.nodes() }
                is Not -> child.nodes()
                else -> emptySequence()
            }

    /** Levels in this tree, counting this one. */
    fun depth(): Int =
        1 +
            when (this) {
                is All -> children.maxOfOrNull { it.depth() } ?: 0
                is Any -> children.maxOfOrNull { it.depth() } ?: 0
                is Not -> child.depth()
                else -> 0
            }

    /** Whether MarmotKit accepts this tree, checked here so an oversized folder stays on the old evaluator. */
    fun withinLimits(): Boolean {
        val nodes = nodes().toList()
        val ids = nodes.filterIsInstance<Ids>().sumOf { it.groupIds.size }
        val text = nodes.filterIsInstance<Text>().sumOf { it.text.toByteArray(Charsets.UTF_8).size }
        return nodes.size <= QUERY_MAX_NODES &&
            depth() <= QUERY_MAX_DEPTH &&
            ids <= QUERY_MAX_GROUP_IDS &&
            text <= QUERY_MAX_TEXT_BYTES
    }
}

/** Wraps this node in Not when [negate] is set. */
private fun FolderNode.negatedIf(negate: Boolean): FolderNode = if (negate) FolderNode.Not(this) else this

/** A group-ID node for these chats, normalized to lowercase, or null when there are none. */
private fun Set<String>.toIdNode(): FolderNode? =
    map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
        .takeIf { it.isNotEmpty() }
        ?.let(FolderNode::Ids)

/** Flattens [root] depth first, so node 0 is the root and every child follows its parent. */
private fun flatten(root: FolderNode): List<ChatListFilterNodeFfi> {
    val out = mutableListOf<ChatListFilterNodeFfi?>()

    fun emit(node: FolderNode): UInt {
        val index = out.size
        out += null
        out[index] =
            when (node) {
                is FolderNode.All -> ChatListFilterNodeFfi.All(node.children.map(::emit))
                is FolderNode.Any -> ChatListFilterNodeFfi.Any(node.children.map(::emit))
                is FolderNode.Not -> ChatListFilterNodeFfi.Not(emit(node.child))
                is FolderNode.Is -> ChatListFilterNodeFfi.Condition(node.condition)
                is FolderNode.Ids -> ChatListFilterNodeFfi.GroupIds(node.groupIds)
                is FolderNode.Text -> ChatListFilterNodeFfi.Text(node.text)
            }
        return index.toUInt()
    }
    emit(root)
    return out.map(::requireNotNull)
}

/** [folderId]'s native query for [accountRef], from its stored rule, manual members and exclusions. */
internal fun ChatFolderPreferences.folderQuery(
    accountRef: String,
    folderId: String,
): ChatFolderQuery? {
    val rule = folderRule(accountRef, folderId)
    return chatFolderQuery(rule, membershipFor(accountRef, folderId), excludedChats(accountRef, folderId))
}

/** Every folder of [accountRef] that has a native query, keyed by folder ID. */
internal fun ChatFolderPreferences.folderQueries(accountRef: String): Map<String, ChatFolderQuery> =
    foldersFor(accountRef).mapNotNull { folder -> folderQuery(accountRef, folder.id)?.let { folder.id to it } }.toMap()

/**
 * [folderId]'s member IDs for [accountRef]: MarmotKit's answer over every chat once it is current, otherwise [loaded],
 * the evaluation over loaded rows that participant folders and not-yet-read folders keep using.
 */
internal fun WhiteNoiseAppState.folderMemberIds(
    accountRef: String,
    folderId: String,
    loaded: () -> Set<String>,
): Set<String> = nativeFolderMembership(accountRef, folderId)?.idSet ?: loaded()

/** [folderId]'s current native membership for [accountRef], or null when its readers must evaluate loaded rows. */
internal fun WhiteNoiseAppState.nativeFolderMembership(
    accountRef: String,
    folderId: String,
): NativeFolderMembership? {
    val query = chatFolderPreferences.folderQuery(accountRef, folderId)
    return chatFolderMembership?.current(accountRef, folderId, query)
}
