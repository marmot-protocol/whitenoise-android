package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListConditionFfi
import dev.ipf.marmotkit.ChatListFilterNodeFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ChatFolderQuery
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.chatFolderQuery
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Characterizes each legacy rule field: the old loaded-rows evaluator and the native query select the same chats from
 * one fixture. The native side is read with MarmotKit's documented view scopes and condition meanings, so a
 * translation that drifts from the old rule shows up here before it reaches a device.
 */
class ChatFolderQueryCharacterizationTest {
    private val chats =
        listOf(
            chat("plain"),
            chat("unread", unread = true),
            chat("mention", unread = true, mention = true),
            chat("pinned", pinned = true),
            chat("muted", muted = true, unread = true),
            chat("direct", kind = ChatConversationKindFfi.DIRECT, unread = true),
            chat("unknown-kind", kind = ChatConversationKindFfi.UNKNOWN),
            chat("archived", archived = true, unread = true),
            chat("archived-direct", archived = true, kind = ChatConversationKindFfi.DIRECT),
            chat("work", description = "Work chat"),
            chat("departed", departed = true),
        )

    /** Every legacy field, alone and combined, selects the same chats either way. */
    @Test fun everyLegacyFieldMatchesTheOldEvaluator() {
        val rules =
            listOf(
                ChatFolderRule(includeAll = true, includeMuted = true),
                ChatFolderRule(unreadOnly = true),
                ChatFolderRule(unreadOnly = true, includeMuted = true),
                ChatFolderRule(unreadMentionsOnly = true),
                ChatFolderRule(pinnedOnly = true),
                ChatFolderRule(groupsOnly = true),
                ChatFolderRule(directChatsOnly = true),
                ChatFolderRule(archivedOnly = true),
                ChatFolderRule(archivedOnly = true, directChatsOnly = true),
                ChatFolderRule(keyword = "work"),
                ChatFolderRule(keyword = "work", unreadOnly = true),
                ChatFolderRule(),
            )
        rules.forEach { rule -> assertSame(rule, manual = emptySet(), excluded = emptySet()) }
    }

    /**
     * The one documented difference: MarmotKit hides a departed group's badges, so an unread departed group no longer
     * counts as unread, while every other folder keeps it among active chats.
     */
    @Test fun departedGroupLosesOnlyItsUnreadBadge() {
        val departed = listOf(chat("left", departed = true, unread = true))
        val unread = requireNotNull(chatFolderQuery(ChatFolderRule(unreadOnly = true), emptySet(), emptySet()))
        val everyChat = requireNotNull(chatFolderQuery(ChatFolderRule(includeAll = true), emptySet(), emptySet()))
        assertEquals(emptySet<String>(), native(unread, departed))
        assertEquals(setOf("left"), native(everyChat, departed))
    }

    /** Manual members join from either side of the archive split, and exclusions win over both. */
    @Test fun manualMembersAndExclusionsMatchTheOldEvaluator() {
        assertSame(ChatFolderRule(groupsOnly = true), manual = setOf("archived-direct"), excluded = setOf("plain"))
        assertSame(ChatFolderRule(archivedOnly = true), manual = setOf("pinned"), excluded = setOf("archived"))
        assertSame(null, manual = setOf("archived", "direct"), excluded = setOf("direct"))
    }

    /** Asserts both evaluators select the same chats for [rule] with [manual] members and [excluded] chats. */
    private fun assertSame(
        rule: ChatFolderRule?,
        manual: Set<String>,
        excluded: Set<String>,
    ) {
        val query = requireNotNull(chatFolderQuery(rule, manual, excluded))
        val legacy =
            chatFolderChatIds(
                items = chats,
                manualChatIds = manual,
                rule = rule,
                activeAccountIdHex = null,
                isMuted = { id -> chats.single { it.id == id }.projection?.muted == true },
                displayTitle = { it.projection?.title.orEmpty() },
                excludedChatIds = excluded,
            )
        assertEquals("rule $rule", legacy, native(query))
    }

    /** The chats [query] selects from [from] across its views. */
    private fun native(
        query: ChatFolderQuery,
        from: List<ChatListItem> = chats,
    ): Set<String> =
        query.views.flatMapTo(HashSet()) { view ->
            from.filter { it.inView(view) && query.matches(0, it) }.map { it.id }
        }

    /** Whether node [index] of this query holds for [chat]. */
    private fun ChatFolderQuery.matches(
        index: Int,
        chat: ChatListItem,
    ): Boolean =
        when (val node = filter[index]) {
            is ChatListFilterNodeFfi.All -> node.children.all { matches(it.toInt(), chat) }
            is ChatListFilterNodeFfi.Any -> node.children.any { matches(it.toInt(), chat) }
            is ChatListFilterNodeFfi.Not -> !matches(node.child.toInt(), chat)
            is ChatListFilterNodeFfi.Condition -> chat.holds(node.condition)
            is ChatListFilterNodeFfi.GroupIds -> chat.id in node.groupIds
            is ChatListFilterNodeFfi.Text -> chat.mentions(node.text)
            is ChatListFilterNodeFfi.MessageText -> error("Folders never search message text")
        }
}

/** MarmotKit's scopes: Chats is neither archived nor departed, Archived is archived and not departed, Left departed. */
private fun ChatListItem.inView(view: ChatListViewFfi): Boolean {
    val departed = group.selfMembership == SelfMembershipFfi.LEFT
    return when (view) {
        ChatListViewFfi.CHATS -> !departed && !group.archived
        ChatListViewFfi.ARCHIVED -> !departed && group.archived
        ChatListViewFfi.LEFT -> departed
        ChatListViewFfi.UNREAD -> false
    }
}

/** MarmotKit's meaning of each condition the folder translation uses, read from the row it renders. */
private fun ChatListItem.holds(condition: ChatListConditionFfi): Boolean {
    val row = requireNotNull(projection)
    val departed = group.selfMembership == SelfMembershipFfi.LEFT
    return when (condition) {
        // Badges are hidden for departed chats, so neither unread nor mention ever holds there.
        ChatListConditionFfi.UNREAD -> row.hasUnread && !departed
        ChatListConditionFfi.MENTION -> row.unreadMention && row.hasUnread && !departed
        ChatListConditionFfi.PINNED -> row.pinned
        ChatListConditionFfi.MUTED -> row.muted
        ChatListConditionFfi.DIRECT -> row.conversationKind == ChatConversationKindFfi.DIRECT
        ChatListConditionFfi.GROUP -> row.conversationKind == ChatConversationKindFfi.GROUP
        ChatListConditionFfi.ARCHIVED -> group.archived && !departed
        ChatListConditionFfi.LEFT -> departed
        else -> error("Unused by legacy rules: $condition")
    }
}

/** Case-insensitive containment in the title or description, the part of MarmotKit's text match legacy rules read. */
private fun ChatListItem.mentions(text: String): Boolean {
    val needle = text.lowercase(Locale.ROOT)
    val title = projection?.title.orEmpty().lowercase(Locale.ROOT)
    return needle in title || needle in group.description.lowercase(Locale.ROOT)
}

/** One chat row with only the facts the folder rules read. */
private fun chat(
    id: String,
    unread: Boolean = false,
    mention: Boolean = false,
    pinned: Boolean = false,
    muted: Boolean = false,
    archived: Boolean = false,
    departed: Boolean = false,
    kind: ChatConversationKindFfi = ChatConversationKindFfi.GROUP,
    description: String = "",
): ChatListItem {
    val membership = if (departed) SelfMembershipFfi.LEFT else SelfMembershipFfi.MEMBER
    return ChatListItem(
        group = group(id, description, archived, membership),
        latest = null,
        otherMemberAccount = null,
        memberCount = 3,
        memberSnapshot = null,
        projection =
            row(id, membership, archived, kind).copy(
                hasUnread = unread,
                unreadCount = if (unread) 1uL else 0uL,
                unreadMention = mention,
                unreadMentionCount = if (mention) 1uL else 0uL,
                pinned = pinned,
                muted = muted,
            ),
    )
}

/** A rendered row for [id]. */
private fun row(
    id: String,
    membership: SelfMembershipFfi,
    archived: Boolean,
    kind: ChatConversationKindFfi,
) = ChatListRowFfi(
    selfMembership = membership,
    unreadMentionCount = 0uL,
    unreadMention = false,
    groupIdHex = id,
    archived = archived,
    pendingConfirmation = false,
    title = "Chat $id",
    groupName = "",
    avatarUrl = null,
    avatar = null,
    lastMessage = null,
    unreadCount = 0uL,
    hasUnread = false,
    firstUnreadMessageIdHex = null,
    lastReadMessageIdHex = null,
    lastReadTimelineAt = null,
    conversationCreatedAt = 0uL,
    activitySortAt = 0uL,
    updatedAt = 1uL,
    leaveRequestPending = false,
    leaveRequestedAtMs = null,
    manuallyMarkedUnread = false,
    conversationKind = kind,
    muted = false,
    mutedUntilMs = null,
    pinned = false,
    pinnedPosition = null,
    lifecycleState = GroupLifecycleStateFfi.STABLE,
    disbanding = false,
    disbandRequest = null,
)

/** The group record behind a row. */
private fun group(
    id: String,
    description: String,
    archived: Boolean,
    membership: SelfMembershipFfi,
) = AppGroupRecordFfi(
    selfMembership = membership,
    groupIdHex = id,
    protocolProfile = AppProtocolProfileFfi.LEGACY,
    profilePresent = false,
    endpoint = "endpoint-$id",
    name = "",
    description = description,
    admins = emptyList(),
    relays = emptyList(),
    nostrGroupIdHex = "nostr-$id",
    avatarUrl = null,
    avatarDim = null,
    avatarThumbhash = null,
    imageHashHex = null,
    encryptedMedia =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints = listOf(AppBlobEndpointFfi("blossom-v1", "https://b.example")),
        ),
    archived = archived,
    pendingConfirmation = false,
    unrecoverable = false,
    welcomerAccountIdHex = null,
    viaWelcomeMessageIdHex = null,
    disappearingMessageSecs = 0uL,
    leaveRequestPending = false,
    leaveRequestedAtMs = null,
    disbanding = false,
    disbanded = false,
    disbandRequest = null,
)
