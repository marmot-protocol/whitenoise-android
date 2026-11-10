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
    /** Whether the folder is every chat in the views it covers, so the plain windows already hold it. */
    val wholeView: Boolean get() = filter == WHOLE_VIEW

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
 * its rule tests participants or an advanced title, which MarmotKit cannot match exactly yet, or it exceeds
 * MarmotKit's query limits.
 */
internal fun chatFolderQuery(
    rule: ChatFolderRule?,
    manualChatIds: Set<String>,
    excludedChatIds: Set<String>,
): ChatFolderQuery? {
    val advanced = rule?.smartFilter
    val decoded = advanced?.let { SmartFolderCodec.decode(it)?.takeIf(SmartFolderCodec::valid) }
    if (rule.needsLoadedRows(decoded)) return null
    val match = if (advanced != null) decoded?.let(::smartFolderNode) else rule?.let(::legacyRuleNode)
    val included = listOfNotNull(manualChatIds.toIdNode(), match)
    val root = FolderNode.All(listOfNotNull(excludedChatIds.toIdNode()?.let(FolderNode::Not), FolderNode.Any(included)))
    val views = folderViews(advanced = advanced != null, archived = rule?.archivedOnly == true)
    // The chip's unread count wraps the root one level deeper, so it must fit as well.
    val fits = FolderNode.All(listOf(root, FolderNode.Is(ChatListConditionFfi.UNREAD))).withinLimits()
    return root.takeIf { fits }?.let { ChatFolderQuery(views, flatten(it)) }
}

/**
 * Whether this rule needs a test MarmotKit cannot express yet, so the folder stays on loaded rows: participants, in
 * the legacy or [decoded] advanced form, or an advanced title match, which MarmotKit's text node would widen to the
 * description, group name and peer name.
 */
private fun ChatFolderRule?.needsLoadedRows(decoded: SmartFolderFilter?): Boolean {
    val legacy = this?.smartFilter == null && this?.includeMemberPubkeys?.isNotEmpty() == true
    val advanced = decoded?.let { it.references(FolderField.PARTICIPANTS) || it.references(FolderField.TITLE) }
    return legacy || advanced == true
}

/**
 * The views whose rows a folder shows, the same side of the archive split the chat list draws it from: Chats, or
 * Archived for an archived rule, and both for an advanced rule. Departed groups sit in MarmotKit's Left scope, which
 * carries no archive side, so their loaded rows keep the loaded-rows evaluator (see [departedFolderMatches]).
 */
private fun folderViews(
    advanced: Boolean,
    archived: Boolean,
): List<ChatListViewFfi> =
    when {
        advanced -> listOf(ChatListViewFfi.CHATS, ChatListViewFfi.ARCHIVED)
        archived -> listOf(ChatListViewFfi.ARCHIVED)
        else -> listOf(ChatListViewFfi.CHATS)
    }

/**
 * A legacy rule: its keyword, or every chat when it is a pure category rule, narrowed by each selected category. A
 * rule with neither matches nothing and the folder stays manual-only.
 */
private fun legacyRuleNode(rule: ChatFolderRule): FolderNode? {
    val keyword = rule.keyword?.trim()?.takeIf { it.isNotEmpty() }
    val categories =
        listOfNotNull(
            FolderNode.Is(ChatListConditionFfi.UNREAD).takeIf { rule.unreadOnly },
            FolderNode.Is(ChatListConditionFfi.MENTION).takeIf { rule.unreadMentionsOnly },
            FolderNode.Is(ChatListConditionFfi.PINNED).takeIf { rule.pinnedOnly },
            // Not Direct rather than Group, so a chat whose kind is still unknown stays a group chat, as before.
            FolderNode.Not(FolderNode.Is(ChatListConditionFfi.DIRECT)).takeIf { rule.groupsOnly },
            FolderNode.Is(ChatListConditionFfi.DIRECT).takeIf { rule.directChatsOnly },
        )
    val selects = keyword != null || rule.includeAll || categories.isNotEmpty() || rule.archivedOnly
    if (!selects) return null
    val muted = FolderNode.Not(FolderNode.Is(ChatListConditionFfi.MUTED)).takeUnless { rule.includeMuted }
    return FolderNode.All(listOfNotNull(keyword?.let(FolderNode::Text)) + categories + listOfNotNull(muted))
}

/** An advanced filter tree, or null for participants or a title. An empty group matches nothing, even negated. */
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

/** One advanced condition in its present sense, or null for participants and titles. */
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
        // Kept on loaded rows by needsLoadedRows, so it never reaches the native tree.
        FolderField.TITLE -> null
        // A joined, settled membership: no pending invitation, not departed, no leave or disband outstanding.
        FolderField.ACCEPTED ->
            FolderNode.All(
                listOf(
                    ChatListConditionFfi.PENDING_INVITE,
                    ChatListConditionFfi.LEFT,
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
