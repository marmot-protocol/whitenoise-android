package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListConditionFfi.DIRECT
import dev.ipf.marmotkit.ChatListConditionFfi.MENTION
import dev.ipf.marmotkit.ChatListConditionFfi.MUTED
import dev.ipf.marmotkit.ChatListConditionFfi.PINNED
import dev.ipf.marmotkit.ChatListConditionFfi.UNREAD
import dev.ipf.marmotkit.ChatListFilterNodeFfi
import dev.ipf.marmotkit.ChatListViewFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How each folder rule field becomes a native chat-list query, and when a folder stays on the old evaluator. */
class ChatFolderQueryTest {
    /** The default Chats folder is every active chat, muted ones included. */
    @Test fun chatsDefaultMatchesEveryActiveChat() {
        val query = query(ChatFolderPreferences.defaultRuleFor(SystemFolderKind.CHATS))
        assertEquals(listOf(ChatListViewFfi.CHATS), query.views)
        assertEquals("All[Any[All[]]]", query.render())
        assertTrue(query.wholeView)
    }

    /** Unread, Groups and Archived defaults select their category, and drop muted chats unless asked. */
    @Test fun categoryDefaultsSelectTheirCategory() {
        val unread = ChatFolderPreferences.defaultRuleFor(SystemFolderKind.UNREAD)
        assertEquals(mutedSuffix("All[Any[All[UNREAD", unread), query(unread).render())
        val groups = ChatFolderPreferences.defaultRuleFor(SystemFolderKind.GROUPS)
        assertEquals(mutedSuffix("All[Any[All[Not[DIRECT]", groups), query(groups).render())
        val archived = query(ChatFolderPreferences.defaultRuleFor(SystemFolderKind.ARCHIVED))
        assertEquals(listOf(ChatListViewFfi.ARCHIVED), archived.views)
    }

    /** Each legacy category field becomes its native condition, and every selected category must match. */
    @Test fun eachLegacyCategoryMapsToItsCondition() {
        val cases =
            mapOf(
                ChatFolderRule(unreadOnly = true, includeMuted = true) to UNREAD,
                ChatFolderRule(unreadMentionsOnly = true, includeMuted = true) to MENTION,
                ChatFolderRule(pinnedOnly = true, includeMuted = true) to PINNED,
                ChatFolderRule(groupsOnly = true, includeMuted = true) to "Not[DIRECT]",
                ChatFolderRule(directChatsOnly = true, includeMuted = true) to DIRECT,
            )
        cases.forEach { (rule, condition) -> assertEquals("All[Any[All[$condition]]]", query(rule).render()) }
        val both = ChatFolderRule(unreadOnly = true, groupsOnly = true, includeMuted = true)
        assertEquals("All[Any[All[UNREAD, Not[DIRECT]]]]", query(both).render())
    }

    /** Leaving muted chats out adds Not(Muted), and a keyword becomes a text match narrowed by the categories. */
    @Test fun keywordAndMutedNarrowTheRule() {
        val rule = ChatFolderRule(keyword = "  Family ", unreadOnly = true)
        assertEquals("All[Any[All[Text(Family), UNREAD, Not[MUTED]]]]", query(rule).render())
    }

    /** A rule that selects nothing keeps the folder manual-only, and a missing rule is manual-only too. */
    @Test fun emptyRuleIsManualOnly() {
        assertEquals("All[Any[]]", query(ChatFolderRule()).render())
        assertEquals("All[Any[Ids(aa)]]", chatFolderQuery(null, setOf("AA"), emptySet())!!.render())
    }

    /**
     * Manual members are added and exclusions removed around the rule, with IDs lowercased. They count only on the
     * folder's side of the archive split, the rows the chat list draws the folder from.
     */
    @Test fun manualMembersAndExclusionsWrapTheRule() {
        val rule = ChatFolderRule(groupsOnly = true, includeMuted = true)
        val query = chatFolderQuery(rule, setOf("BB", "aa"), setOf("CC"))!!
        assertEquals(listOf(ChatListViewFfi.CHATS), query.views)
        assertEquals("All[Not[Ids(cc)], Any[Ids(aa,bb), All[Not[DIRECT]]]]", query.render())
    }

    /** A rule reads its side only: Chats, or Archived for an archived rule. Departed groups are left to loaded rows. */
    @Test fun ruleReadsItsSideOnly() {
        assertEquals(listOf(ChatListViewFfi.CHATS), query(ChatFolderRule(pinnedOnly = true)).views)
        assertEquals(listOf(ChatListViewFfi.ARCHIVED), query(ChatFolderRule(archivedOnly = true)).views)
    }

    /** A folder that fits only without the chip's unread narrowing stays on loaded rows, so its count never fails. */
    @Test fun unreadNarrowingMustFitTheLimits() {
        val deepest =
            (0 until 3).fold<Int, SmartFolderFilter>(SmartFolderFilter.Condition(FolderField.MUTED, not = true)) {
                inner,
                _,
                ->
                SmartFolderFilter.Group(children = listOf(inner))
            }
        val root = SmartFolderFilter.Group(children = listOf(deepest))
        assertNull(local(ChatFolderRule(smartFilter = SmartFolderCodec.encode(root))))
    }

    /** Participant rules stay on the loaded-rows evaluator until MarmotKit can match members. */
    @Test fun participantRulesStayLocal() {
        assertNull(local(ChatFolderRule(includeMemberPubkeys = setOf("a".repeat(64)))))
        val smart =
            SmartFolderFilter.Group(
                children =
                    listOf(
                        SmartFolderFilter.Condition(FolderField.PARTICIPANTS, FolderMode.ANY_OF, setOf("b".repeat(64))),
                    ),
            )
        assertNull(local(ChatFolderRule(smartFilter = SmartFolderCodec.encode(smart))))
    }

    /** More manual chats than MarmotKit accepts keep the folder on the old evaluator instead of failing. */
    @Test fun oversizedFoldersStayLocal() {
        val ids = (0 until 501).map { "%064x".format(it) }.toSet()
        assertNull(chatFolderQuery(ChatFolderRule(), ids, emptySet()))
    }

    /** Advanced filters span active and archived chats, mapping each field, None and Not onto native nodes. */
    @Test fun advancedFieldsMapToNativeNodes() {
        val fields =
            mapOf(
                FolderField.UNREAD to "UNREAD",
                FolderField.MENTIONS to "MENTION",
                FolderField.MUTED to "MUTED",
                FolderField.PINNED to "PINNED",
                FolderField.ARCHIVED to "ARCHIVED",
                FolderField.DRAFT to "HAS_DRAFT",
                FolderField.PENDING_SEND to "PENDING_SEND",
                FolderField.ACCEPTED to "All[Not[PENDING_INVITE], Not[LEFT], Not[PENDING_LEAVE], Not[DISBANDING]]",
            )
        fields.forEach { (field, node) ->
            val query = smart(SmartFolderFilter.Condition(field))
            assertEquals(listOf(ChatListViewFfi.CHATS, ChatListViewFfi.ARCHIVED), query.views)
            assertEquals("All[Any[All[$node]]]", query.render())
        }
        val noneUnread = smart(SmartFolderFilter.Condition(FolderField.UNREAD, FolderMode.NONE))
        assertEquals("All[Any[All[Not[UNREAD]]]]", noneUnread.render())
        val notPinned = smart(SmartFolderFilter.Condition(FolderField.PINNED, not = true))
        assertEquals("All[Any[All[Not[PINNED]]]]", notPinned.render())
        assertEquals(
            "All[Any[All[DIRECT]]]",
            smart(SmartFolderFilter.Condition(FolderField.TYPE, FolderMode.DIRECT)).render(),
        )
        val title = SmartFolderFilter.Condition(FolderField.TITLE, FolderMode.CONTAINS, setOf("work"))
        val titled = SmartFolderFilter.Group(children = listOf(title))
        val titleRule = ChatFolderRule(smartFilter = SmartFolderCodec.encode(titled))
        assertNull("a title-only match stays on loaded rows", local(titleRule))
    }

    /** Nested any/negated groups keep their shape, and an incomplete filter with an empty group stays manual-only. */
    @Test fun advancedGroupsKeepTheirShape() {
        val nested =
            SmartFolderFilter.Group(
                all = false,
                not = true,
                children = listOf(FolderField.MUTED, FolderField.DRAFT).map { SmartFolderFilter.Condition(it) },
            )
        assertEquals("All[Any[All[Not[Any[MUTED, HAS_DRAFT]]]]]", smart(nested).render())
        val root = SmartFolderFilter.Group(children = listOf(SmartFolderFilter.Group(not = true)))
        assertEquals("All[Any[]]", query(ChatFolderRule(smartFilter = SmartFolderCodec.encode(root))).render())
    }

    /** The unread narrowing wraps the whole folder, manual members and exclusions included. */
    @Test fun unreadNarrowsTheWholeFolder() {
        val query = chatFolderQuery(ChatFolderRule(pinnedOnly = true, includeMuted = true), setOf("aa"), emptySet())!!
        assertEquals("All[All[Any[Ids(aa), All[PINNED]]], UNREAD]", query.unread().render())
    }

    /** Every flattened child follows its parent, as MarmotKit requires. */
    @Test fun childrenFollowTheirParents() {
        val query = chatFolderQuery(ChatFolderRule(keyword = "a", pinnedOnly = true), setOf("aa"), setOf("bb"))!!
        query.filter.forEachIndexed { index, node -> children(node).forEach { assertTrue(it.toInt() > index) } }
    }

    /** The native query for [rule] alone, or null when the folder stays on loaded rows. */
    private fun local(rule: ChatFolderRule): ChatFolderQuery? = chatFolderQuery(rule, emptySet(), emptySet())

    /** The native query for [rule] alone. */
    private fun query(rule: ChatFolderRule): ChatFolderQuery = requireNotNull(local(rule))

    /** The native query for an advanced folder holding only [condition]. */
    private fun smart(condition: SmartFolderFilter): ChatFolderQuery {
        val filter = SmartFolderFilter.Group(children = listOf(condition))
        return query(ChatFolderRule(smartFilter = SmartFolderCodec.encode(filter)))
    }

    /** The expected rendering of a category default, with Not(Muted) when [rule] leaves muted chats out. */
    private fun mutedSuffix(
        prefix: String,
        rule: ChatFolderRule,
    ): String = prefix + (if (rule.includeMuted) "" else ", Not[MUTED]") + "]]]"

    /** The child indices of [node]. */
    private fun children(node: ChatListFilterNodeFfi): List<UInt> =
        when (node) {
            is ChatListFilterNodeFfi.All -> node.children
            is ChatListFilterNodeFfi.Any -> node.children
            is ChatListFilterNodeFfi.Not -> listOf(node.child)
            else -> emptyList()
        }

    /** A compact rendering of the flattened tree, rooted at node 0. */
    private fun ChatFolderQuery.render(index: Int = 0): String =
        when (val node = filter[index]) {
            is ChatListFilterNodeFfi.All -> "All[${node.children.joinToString { render(it.toInt()) }}]"
            is ChatListFilterNodeFfi.Any -> "Any[${node.children.joinToString { render(it.toInt()) }}]"
            is ChatListFilterNodeFfi.Not -> "Not[${render(node.child.toInt())}]"
            is ChatListFilterNodeFfi.Condition -> node.condition.name
            is ChatListFilterNodeFfi.GroupIds -> "Ids(${node.groupIds.joinToString(",")})"
            is ChatListFilterNodeFfi.Text -> "Text(${node.text})"
            is ChatListFilterNodeFfi.MessageText -> "MessageText(${node.text})"
        }
}
