package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListReturnHeadSnapTest {
    @Test
    fun openGroupFromChatListArmsConversationAndReplacesProfile() {
        val profile =
            presentProfileFromChatList(
                ChatListReturnHeadSnapState.Unarmed,
                visibleActiveListHeadId = "profile-head",
            )
        assertTrue(profile is ChatListReturnHeadSnapState.Profile)

        val armed = openGroupFromChatList(profile, visibleActiveListHeadId = "filtered-head")

        assertEquals(ChatListReturnHeadSnapState.Conversation("filtered-head"), armed)
    }

    @Test
    fun openGroupFromProfileSheetTransfersProfileHeadToConversation() {
        val state =
            openGroupFromProfileSheet(
                presentProfileFromChatList(
                    ChatListReturnHeadSnapState.Unarmed,
                    visibleActiveListHeadId = "profile-head",
                ),
            )

        assertEquals(ChatListReturnHeadSnapState.Conversation("profile-head"), state)
    }

    @Test
    fun openGroupFromProfileSheetResetsEveryNonProfileStateToUnarmed() {
        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            openGroupFromProfileSheet(ChatListReturnHeadSnapState.Unarmed),
        )
        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            openGroupFromProfileSheet(ChatListReturnHeadSnapState.Conversation("stale-row-head")),
        )
        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            openGroupFromProfileSheet(ChatListReturnHeadSnapState.Published("stale-published")),
        )
    }

    @Test
    fun dismissChatListProfileClearsProfileOnlyAndPreservesConversation() {
        val dismissed =
            dismissChatListProfile(
                presentProfileFromChatList(
                    ChatListReturnHeadSnapState.Unarmed,
                    visibleActiveListHeadId = "profile-head",
                ),
            )
        assertEquals(ChatListReturnHeadSnapState.Unarmed, dismissed)

        val preserved =
            dismissChatListProfile(ChatListReturnHeadSnapState.Conversation("row-head"))
        assertEquals(ChatListReturnHeadSnapState.Conversation("row-head"), preserved)
    }

    @Test
    fun resetAlwaysReturnsUnarmed() {
        assertEquals(ChatListReturnHeadSnapState.Unarmed, resetChatListReturnHeadSnap())
    }

    @Test
    fun nullOrArchivedVisibleHeadTransitionsToUnarmed() {
        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            presentProfileFromChatList(ChatListReturnHeadSnapState.Unarmed, visibleActiveListHeadId = null),
        )
        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            openGroupFromChatList(ChatListReturnHeadSnapState.Unarmed, visibleActiveListHeadId = null),
        )
        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            openGroupFromProfileSheet(
                presentProfileFromChatList(
                    ChatListReturnHeadSnapState.Unarmed,
                    visibleActiveListHeadId = null,
                ),
            ),
        )
    }

    @Test
    fun onChatListBecameVisiblePublishesConversationHeadOnlyAfterReturn() {
        val published =
            onChatListBecameVisible(ChatListReturnHeadSnapState.Conversation("filtered-head"))
        assertEquals(ChatListReturnHeadSnapState.Published("filtered-head"), published)

        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            onChatListBecameVisible(ChatListReturnHeadSnapState.Unarmed),
        )
        assertEquals(
            ChatListReturnHeadSnapState.Profile("profile-head"),
            onChatListBecameVisible(ChatListReturnHeadSnapState.Profile("profile-head")),
        )
        assertEquals(
            ChatListReturnHeadSnapState.Published("already-published"),
            onChatListBecameVisible(ChatListReturnHeadSnapState.Published("already-published")),
        )
    }

    @Test
    fun onConversationReturnHeadHandledConsumesPublishedExactlyOnce() {
        val consumed =
            onConversationReturnHeadHandled(
                ChatListReturnHeadSnapState.Published("filtered-head"),
            )
        assertEquals(ChatListReturnHeadSnapState.Unarmed, consumed)

        val unchanged =
            onConversationReturnHeadHandled(ChatListReturnHeadSnapState.Conversation("row-head"))
        assertEquals(ChatListReturnHeadSnapState.Conversation("row-head"), unchanged)
    }

    @Test
    fun publishedHeadIsReadOnlyFromPublishedState() {
        assertEquals(
            "filtered-head",
            publishedConversationReturnHead(ChatListReturnHeadSnapState.Published("filtered-head")),
        )
        assertNull(publishedConversationReturnHead(ChatListReturnHeadSnapState.Conversation("filtered-head")))
        assertNull(publishedConversationReturnHead(ChatListReturnHeadSnapState.Unarmed))
    }

    @Test
    fun returnPublishFlowIsConversationToPublishedToUnarmed() {
        val published =
            onChatListBecameVisible(
                openGroupFromChatList(
                    ChatListReturnHeadSnapState.Unarmed,
                    visibleActiveListHeadId = "filtered-head",
                ),
            )
        assertEquals(ChatListReturnHeadSnapState.Published("filtered-head"), published)
        assertEquals(
            ChatListReturnHeadSnapState.Unarmed,
            onConversationReturnHeadHandled(published),
        )
    }
}
