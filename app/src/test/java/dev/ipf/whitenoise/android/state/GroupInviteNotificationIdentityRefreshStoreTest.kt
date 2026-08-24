package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInviteNotificationIdentityRefreshStoreTest {
    @Test
    fun refreshStoreDoesNotRetainProtocolNotificationUpdates() {
        val retainedFieldTypes =
            GroupInviteNotificationIdentityRefreshStore::class.java.declaredClasses
                .flatMap { it.declaredFields.toList() }
                .map { it.type }

        assertFalse(NotificationUpdateFfi::class.java in retainedFieldTypes)
    }

    @Test
    fun resolvedProfileSelectsOnlyMatchingInviteAndDoesNotRepeatAfterRefresh() {
        val store = GroupInviteNotificationIdentityRefreshStore()
        val aliceInvite = update("alice-invite", "Alice")
        val bobInvite = update("bob-invite", "Bob")
        store.rememberPosted(identity(aliceInvite), displayedName = null)
        store.rememberPosted(identity(bobInvite), displayedName = null)

        assertEquals(
            listOf(identity(aliceInvite)),
            store
                .refreshCandidates(
                    senderAccountIdHex = "alice",
                    resolvedName = "Alice",
                ).map { it.identity },
        )
        assertTrue(
            store
                .refreshCandidates(
                    senderAccountIdHex = "ALICE",
                    resolvedName = "Alice",
                ).isEmpty(),
        )

        store.completeRefresh(
            aliceInvite.notificationKey,
            displayedName = "Alice",
            contentRedacted = false,
        )
        assertTrue(
            store
                .refreshCandidates(
                    senderAccountIdHex = "ALICE",
                    resolvedName = "Alice",
                ).isEmpty(),
        )
    }

    @Test
    fun failedRefreshCanBeRetried() {
        val store = GroupInviteNotificationIdentityRefreshStore()
        val invite = update("alice-invite", "Alice")
        store.rememberPosted(identity(invite), displayedName = null)

        assertRefreshCandidate(store, invite)
        store.release(invite.notificationKey)
        assertRefreshCandidate(store, invite)
    }

    @Test
    fun thrownRefreshReleasesClaimForRetry() =
        runTest {
            val store = GroupInviteNotificationIdentityRefreshStore()
            val invite = update("alice-invite", "Alice")
            store.rememberPosted(identity(invite), displayedName = null)
            assertRefreshCandidate(store, invite)

            val failure =
                runCatching {
                    store.runClaimedRefresh(invite.notificationKey) {
                        error("lookup failed")
                    }
                }

            assertTrue(failure.exceptionOrNull() is IllegalStateException)
            assertRefreshCandidate(store, invite)
        }

    @Test
    fun cancelledRefreshReleasesClaimForRetry() =
        runTest {
            val store = GroupInviteNotificationIdentityRefreshStore()
            val invite = update("alice-invite", "Alice")
            store.rememberPosted(identity(invite), displayedName = null)
            assertRefreshCandidate(store, invite)

            val failure =
                runCatching {
                    store.runClaimedRefresh(invite.notificationKey) {
                        throw CancellationException("refresh cancelled")
                    }
                }

            assertTrue(failure.exceptionOrNull() is CancellationException)
            assertRefreshCandidate(store, invite)
        }

    @Test
    fun normallyCompletedRefreshUsesItsTerminalRelease() =
        runTest {
            val store = GroupInviteNotificationIdentityRefreshStore()
            val invite = update("alice-invite", "Alice")
            store.rememberPosted(identity(invite), displayedName = null)
            assertRefreshCandidate(store, invite)

            store.runClaimedRefresh(invite.notificationKey) {
                store.release(invite.notificationKey)
            }

            assertRefreshCandidate(store, invite)
        }

    @Test
    fun newerPresentationQueuedDuringRefreshRunsImmediatelyAfterOlderPost() {
        val store = GroupInviteNotificationIdentityRefreshStore()
        val invite = update("alice-invite", "Alice")
        store.rememberPosted(identity(invite), displayedName = null)

        val first =
            store
                .refreshCandidates("Alice", resolvedName = "Alice v1")
                .single()
        assertEquals("Alice v1", first.resolvedName)
        assertTrue(
            store
                .refreshCandidates("Alice", resolvedName = "Alice v2")
                .isEmpty(),
        )

        val followUp =
            store.completeRefresh(
                notificationKey = invite.notificationKey,
                displayedName = "Alice v1",
                contentRedacted = false,
            )

        assertEquals(identity(invite), followUp?.identity)
        assertEquals("Alice v2", followUp?.resolvedName)
        assertEquals(
            null,
            store.completeRefresh(
                notificationKey = invite.notificationKey,
                displayedName = "Alice v2",
                contentRedacted = false,
            ),
        )
    }

    @Test
    fun redactedInviteResolvedWhileLockedRefreshesAfterUnlock() {
        val store = GroupInviteNotificationIdentityRefreshStore()
        val invite = update("alice-invite", "Alice")
        store.rememberPosted(identity(invite), displayedName = null)
        val candidate =
            store
                .refreshCandidates("Alice", resolvedName = "Alice")
                .single()

        assertEquals(
            null,
            store.completeRefresh(
                notificationKey = invite.notificationKey,
                displayedName = null,
                contentRedacted = true,
            ),
        )

        assertEquals(listOf(candidate), store.claimPendingRefreshes())
        assertTrue(store.claimPendingRefreshes().isEmpty())
        assertEquals(
            null,
            store.completeRefresh(
                notificationKey = invite.notificationKey,
                displayedName = "Alice",
                contentRedacted = false,
            ),
        )
        assertTrue(store.refreshCandidates("Alice", resolvedName = "Alice").isEmpty())
    }

    @Test
    fun nonInviteUpdatesAreNeverTracked() {
        val nonInvite = update("message", "Alice").copy(trigger = NotificationTriggerFfi.NEW_MESSAGE)

        assertTrue(
            postedGroupInviteIdentity(nonInvite, posted = true, redactContent = false, displayedName = null) == null,
        )
    }

    private fun assertRefreshCandidate(
        store: GroupInviteNotificationIdentityRefreshStore,
        invite: NotificationUpdateFfi,
    ) {
        assertEquals(
            listOf(identity(invite)),
            store
                .refreshCandidates("Alice", resolvedName = "Alice")
                .map { it.identity },
        )
    }

    private fun identity(update: NotificationUpdateFfi): GroupInviteNotificationIdentity =
        requireNotNull(
            postedGroupInviteIdentity(
                update = update,
                posted = true,
                redactContent = false,
                displayedName = null,
            ),
        ).identity

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
