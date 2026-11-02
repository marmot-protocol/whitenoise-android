package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.ui.chats.ChatListReturnHead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListReturnHeadSnapTest {
    /** A direct list open arms the conversation head and replaces a pending profile head. */
    @Test
    fun openGroupFromChatListArmsConversationAndReplacesProfile() {
        val profile =
            presentProfileFromChatList(
                ChatListReturnHeadSnapState.Unarmed,
                visibleActiveListHead = head("profile-head"),
            )
        assertTrue(profile is ChatListReturnHeadSnapState.Profile)

        val armed = openGroupFromChatList(profile, visibleActiveListHead = head("filtered-head"))

        assertEquals(ChatListReturnHeadSnapState.Conversation(head("filtered-head")), armed)
    }

    /** A conversation opened from a list-armed profile sheet inherits the profile's head. */
    @Test
    fun openGroupFromProfileSheetTransfersProfileHeadToConversation() {
        val state =
            openGroupFromProfileSheet(
                presentProfileFromChatList(
                    ChatListReturnHeadSnapState.Unarmed,
                    visibleActiveListHead = head("profile-head"),
                ),
            )

        assertEquals(ChatListReturnHeadSnapState.Conversation(head("profile-head")), state)
    }

    /** A profile-sheet open without a list-armed profile clears any stale head. */
    @Test
    fun openGroupFromProfileSheetResetsEveryNonProfileStateToUnarmed() {
        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            openGroupFromProfileSheet(ChatListReturnHeadSnapState.Unarmed),
        )
        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            openGroupFromProfileSheet(ChatListReturnHeadSnapState.Conversation(head("stale-row-head"))),
        )
        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            openGroupFromProfileSheet(ChatListReturnHeadSnapState.Published(head("stale-published"))),
        )
    }

    /** A group created from the list arms the head held when New Message opened, replacing older provenance (#3529). */
    @Test
    fun openCreatedGroupArmsTheFlowOriginHead() {
        assertEquals(
            ChatListReturnHeadSnapState.Conversation(head("origin-head")),
            openCreatedGroup(ChatListReturnHeadSnapState.Unarmed, flowOriginHead = head("origin-head")),
        )
        assertEquals(
            ChatListReturnHeadSnapState.Conversation(head("origin-head")),
            openCreatedGroup(
                ChatListReturnHeadSnapState.Published(head("stale-published")),
                flowOriginHead = head("origin-head"),
            ),
        )
    }

    /** A group created without a list origin keeps the profile-sheet transfer and clears anything else. */
    @Test
    fun openCreatedGroupWithoutFlowOriginFallsBackToProfileSheetRules() {
        assertEquals(
            ChatListReturnHeadSnapState.Conversation(head("profile-head")),
            openCreatedGroup(ChatListReturnHeadSnapState.Profile(head("profile-head")), flowOriginHead = null),
        )
        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            openCreatedGroup(ChatListReturnHeadSnapState.Conversation(head("stale-row-head")), flowOriginHead = null),
        )
    }

    /** Dismissing the profile clears only a profile head and keeps an armed conversation. */
    @Test
    fun dismissChatListProfileClearsProfileOnlyAndPreservesConversation() {
        val dismissed =
            dismissChatListProfile(
                presentProfileFromChatList(
                    ChatListReturnHeadSnapState.Unarmed,
                    visibleActiveListHead = head("profile-head"),
                ),
            )
        assertEquals(ChatListReturnHeadSnapState.Unarmed, dismissed)

        val preserved =
            dismissChatListProfile(ChatListReturnHeadSnapState.Conversation(head("row-head")))
        assertEquals(ChatListReturnHeadSnapState.Conversation(head("row-head")), preserved)
    }

    /** Reset always returns the unarmed state. */
    @Test
    fun resetAlwaysReturnsUnarmed() {
        assertEquals(ChatListReturnHeadSnapState.Unarmed, resetChatListReturnHeadSnap())
    }

    /** A missing or archived visible head never arms the return snap. */
    @Test
    fun nullOrArchivedVisibleHeadTransitionsToUnarmed() {
        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            presentProfileFromChatList(ChatListReturnHeadSnapState.Unarmed, visibleActiveListHead = null),
        )
        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            openGroupFromChatList(ChatListReturnHeadSnapState.Unarmed, visibleActiveListHead = null),
        )
        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            openGroupFromProfileSheet(
                presentProfileFromChatList(
                    ChatListReturnHeadSnapState.Unarmed,
                    visibleActiveListHead = null,
                ),
            ),
        )
    }

    /** Only an armed conversation head is published when the list becomes visible again. */
    @Test
    fun onChatListBecameVisiblePublishesConversationHeadOnlyAfterReturn() {
        val published =
            onChatListBecameVisible(ChatListReturnHeadSnapState.Conversation(head("filtered-head")))
        assertEquals(ChatListReturnHeadSnapState.Published(head("filtered-head")), published)

        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            onChatListBecameVisible(ChatListReturnHeadSnapState.Unarmed),
        )
        assertEquals(
            ChatListReturnHeadSnapState.Profile(head("profile-head")),
            onChatListBecameVisible(ChatListReturnHeadSnapState.Profile(head("profile-head"))),
        )
        assertEquals(
            ChatListReturnHeadSnapState.Published(head("already-published")),
            onChatListBecameVisible(ChatListReturnHeadSnapState.Published(head("already-published"))),
        )
    }

    /** Handling the return consumes a published head once and leaves other states alone. */
    @Test
    fun onConversationReturnHeadHandledConsumesPublishedExactlyOnce() {
        val consumed =
            onConversationReturnHeadHandled(
                ChatListReturnHeadSnapState.Published(head("filtered-head")),
            )
        assertEquals(ChatListReturnHeadSnapState.Unarmed, consumed)

        val unchanged =
            onConversationReturnHeadHandled(ChatListReturnHeadSnapState.Conversation(head("row-head")))
        assertEquals(ChatListReturnHeadSnapState.Conversation(head("row-head")), unchanged)
    }

    /** The published head is readable only from the published state. */
    @Test
    fun publishedHeadIsReadOnlyFromPublishedState() {
        assertEquals(
            head("filtered-head"),
            publishedConversationReturnHead(ChatListReturnHeadSnapState.Published(head("filtered-head"))),
        )
        assertNull(publishedConversationReturnHead(ChatListReturnHeadSnapState.Conversation(head("filtered-head"))))
        assertNull(publishedConversationReturnHead(ChatListReturnHeadSnapState.Unarmed))
    }

    /** The return flow moves from conversation to published to unarmed. */
    @Test
    fun returnPublishFlowIsConversationToPublishedToUnarmed() {
        val published =
            onChatListBecameVisible(
                openGroupFromChatList(
                    ChatListReturnHeadSnapState.Unarmed,
                    visibleActiveListHead = head("filtered-head"),
                ),
            )
        assertEquals(ChatListReturnHeadSnapState.Published(head("filtered-head")), published)
        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            onConversationReturnHeadHandled(published),
        )
    }

    /** A captured head whose pinned head and first unpinned row share [id]. */
    private fun head(id: String): ChatListReturnHead = ChatListReturnHead(headId = id, activityHeadId = id)
}
