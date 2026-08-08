package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInviteNotificationIdentityRefreshStoreTest {
    @Test
    fun resolvedProfileSelectsOnlyMatchingInviteAndDoesNotRepeatAfterRefresh() {
        val store = GroupInviteNotificationIdentityRefreshStore()
        val aliceInvite = update("alice-invite", "Alice")
        val bobInvite = update("bob-invite", "Bob")
        store.rememberPosted(aliceInvite, displayedName = null, displayedAvatarUrl = null)
        store.rememberPosted(bobInvite, displayedName = null, displayedAvatarUrl = null)

        assertEquals(
            listOf(aliceInvite),
            store.refreshCandidates(
                senderAccountIdHex = "alice",
                resolvedName = "Alice",
                resolvedAvatarUrl = "https://example.com/alice.png",
            ),
        )
        assertTrue(
            store
                .refreshCandidates(
                    senderAccountIdHex = "ALICE",
                    resolvedName = "Alice",
                    resolvedAvatarUrl = "https://example.com/alice.png",
                ).isEmpty(),
        )

        store.markRefreshed(
            aliceInvite,
            displayedName = "Alice",
            displayedAvatarUrl = "https://example.com/alice.png",
        )
        assertTrue(
            store
                .refreshCandidates(
                    senderAccountIdHex = "ALICE",
                    resolvedName = "Alice",
                    resolvedAvatarUrl = "https://example.com/alice.png",
                ).isEmpty(),
        )
    }

    @Test
    fun failedRefreshCanBeRetried() {
        val store = GroupInviteNotificationIdentityRefreshStore()
        val invite = update("alice-invite", "Alice")
        store.rememberPosted(invite, displayedName = null, displayedAvatarUrl = null)

        assertEquals(listOf(invite), store.refreshCandidates("Alice", resolvedName = "Alice", resolvedAvatarUrl = null))
        store.release(invite.notificationKey)
        assertEquals(listOf(invite), store.refreshCandidates("Alice", resolvedName = "Alice", resolvedAvatarUrl = null))
    }

    @Test
    fun nonInviteUpdatesAreNeverTracked() {
        val store = GroupInviteNotificationIdentityRefreshStore()
        store.rememberPosted(
            update("message", "Alice").copy(trigger = NotificationTriggerFfi.NEW_MESSAGE),
            displayedName = null,
            displayedAvatarUrl = null,
        )

        assertTrue(store.refreshCandidates("Alice", resolvedName = "Alice", resolvedAvatarUrl = null).isEmpty())
    }

    private fun update(
        notificationKey: String,
        senderAccountIdHex: String,
    ) = NotificationUpdateFfi(
        notificationKey = notificationKey,
        conversationKey = "conversation:account:group",
        trigger = NotificationTriggerFfi.GROUP_INVITE,
        trafficClass = NotificationTrafficClassFfi.STANDARD,
        accountRef = "account",
        accountIdHex = "self",
        groupIdHex = "group",
        groupName = null,
        isDm = true,
        isMention = false,
        messageIdHex = null,
        sender = NotificationUserFfi(senderAccountIdHex, displayName = null, pictureUrl = null),
        receiver = NotificationUserFfi("self", displayName = "Me", pictureUrl = null),
        previewText = null,
        reactionEmoji = null,
        reactedToPreview = null,
        timestampMs = 1L,
        isFromSelf = false,
    )
}
