package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Final execution scope and picker identities share the same selection algebra. */
class GlobalSearchChatSenderFilterTest {
    /** The folder restriction reaches every consumer, including an empty intersection. */
    @Test
    fun folderTypeAndNamedChatsIntersectWithoutTurningEmptyIntoAll() {
        val a = leftScopeRow("AA")
        val b = leftScopeRow("BB")
        val dm = leftScopeRow("CC").let { it.copy(projection = it.projection?.copy(conversationKind = ChatConversationKindFfi.DIRECT)) }
        val rows = listOf(a, b, dm)
        val state = GlobalSearchState(chatFilters = setOf(GlobalSearchChatFilter("aa", "A"), GlobalSearchChatFilter("cc", "C")))

        assertEquals(listOf(a), globalSearchScopedChats(rows, state, setOf("AA", "BB")))
        assertTrue(globalSearchScopedChats(rows, state, emptySet()).isEmpty())
        assertEquals(listOf(a, dm), globalSearchScopedChats(rows, state, null))
        assertEquals(listOf(a), globalSearchScopedChats(rows, state.copy(chatTypeFilters = setOf(GlobalSearchChatType.GROUPS)), null))
    }

    /** A selected chat narrows the roster; self is one canonical key, not a local account label. */
    @Test
    fun namedChatScopeDeterminesSenderUnionAndSelfIdentity() {
        val a = leftScopeRow("aa").copy(memberSnapshot = roster("ABC", "ME"))
        val b = leftScopeRow("bb").copy(memberSnapshot = roster("DEF", "abc"))
        val state = GlobalSearchState(chatFilters = setOf(GlobalSearchChatFilter("AA", "A")))
        val scoped = globalSearchScopedChats(listOf(a, b), state, null)

        assertEquals(setOf("abc", "me"), globalSearchSenderIds(scoped, "ME"))
        assertEquals(setOf("abc", "def", "me"), globalSearchSenderIds(listOf(a, b), "me"))
        assertEquals(setOf("new-self"), globalSearchSenderIds(emptyList(), "new-self"))
        assertTrue(globalSearchSenderIds(emptyList(), " ").isEmpty())
    }

    /** A renamed entity retains its selection; an absent one stays visible and removable. */
    @Test
    fun absentSelectedRowsAreNotReplacedByAnotherPersonsSameName() {
        val choices =
            globalSearchPickerChoices(
                items = listOf(WhiteNoisePickerItem("other", "Alice")),
                selectedLabels = mapOf("selected" to "Alice"),
                loading = false,
                unavailable = "Unavailable",
            )
        assertEquals(listOf("selected", "other"), choices.map { it.id })
        assertEquals("Alice · Unavailable", choices.first().title)
        assertTrue(choices.first().enabled)
        assertEquals("Alice", globalSearchPickerChoices(emptyList(), mapOf("selected" to "Alice"), true, "Unavailable").single().title)
    }

    /** Label hydration does not change the dataset or reset the viewport. */
    @Test
    fun labelChangesDoNotChangeFilterDatasetButIdentityChangesDo() {
        val state = GlobalSearchState(senderFilters = setOf(GlobalSearchSenderFilter("abc", "Alice")))
        val renamed = GlobalSearchTransitions.reconcileLabels(state, emptyMap(), mapOf("abc" to "New name"))
        assertEquals(state.viewportFilters(), renamed.viewportFilters())
        assertTrue(state.viewportFilters() != state.copy(senderFilters = emptySet()).viewportFilters())
    }

    private fun roster(vararg ids: String): GroupMemberSnapshot =
        GroupMemberSnapshot(ids.map { AppGroupMemberRecordFfi(memberIdHex = it, account = "local-label", local = false) })
}
