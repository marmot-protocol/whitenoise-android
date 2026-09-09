package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.state.GroupInviteNotificationIdentityRefreshStore.RefreshCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Store-level coverage for invite identity ownership, retries, and shared correction budgets. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupInviteNotificationIdentityRefreshStoreTest {
    /** Ensures the store retains only minimal identity metadata, never protocol payloads. */
    @Test
    fun refreshStoreDoesNotRetainProtocolNotificationUpdates() {
        val retainedFieldTypes =
            GroupInviteNotificationIdentityRefreshStore::class.java.declaredClasses
                .flatMap { it.declaredFields.toList() }
                .map { it.type }

        assertFalse(NotificationUpdateFfi::class.java in retainedFieldTypes)
    }

    /** Selects one matching invite and removes it after a terminal refresh. */
    @Test
    fun resolvedProfileSelectsOnlyMatchingInviteAndDoesNotRepeatAfterRefresh() {
        val store = GroupInviteNotificationIdentityRefreshStore()
        val aliceInvite = update("alice-invite", "Alice")
        val bobInvite = update("bob-invite", "Bob")
        store.rememberPosted(identity(aliceInvite), displayedName = null)
        store.rememberPosted(identity(bobInvite), displayedName = null)

        val candidate =
            store
                .refreshCandidates(
                    senderAccountIdHex = "alice",
                    resolvedName = "Alice",
                ).single()
        assertEquals(identity(aliceInvite), candidate.identity)
        assertTrue(
            store
                .refreshCandidates(
                    senderAccountIdHex = "ALICE",
                    resolvedName = "Alice",
                ).isEmpty(),
        )

        store.forget(candidate)
        assertTrue(
            store
                .refreshCandidates(
                    senderAccountIdHex = "ALICE",
                    resolvedName = "Alice",
                ).isEmpty(),
        )
    }

    /** Carries a failed content claim into the stored profile lane without losing the candidate. */
    @Test
    fun coordinatorFailureHandsTheSharedStorePermitToTheProfileLane() =
        runTest {
            val store = GroupInviteNotificationIdentityRefreshStore()
            val invite = update("alice-invite", "Alice")
            val sharedPermit = NotificationLateCorrectionPermit()
            store.rememberPosted(
                identity = identity(invite),
                displayedName = null,
                postEpoch = 11L,
                accountCacheEpoch = 12L,
                engineMuted = true,
                lateCorrectionPermit = sharedPermit,
            )
            val candidate = store.refreshCandidates("Alice", resolvedName = "Alice").single()

            assertTrue(sharedPermit.acquire())
            val profileClaim = async { candidate.lateCorrectionPermit.acquire() }
            runCurrent()
            assertFalse(profileClaim.isCompleted)
            sharedPermit.complete(written = false)
            runCurrent()

            assertTrue(profileClaim.await())
            candidate.lateCorrectionPermit.complete(written = true)
            assertEquals(11L, candidate.postEpoch)
            assertEquals(12L, candidate.accountCacheEpoch)
            assertTrue(candidate.engineMuted)
        }

    /** Makes a successful stored profile claim terminal for a waiting content lane. */
    @Test
    fun profileSuccessConsumesTheSharedStorePermitBeforeContent() =
        runTest {
            val store = GroupInviteNotificationIdentityRefreshStore()
            val invite = update("alice-invite", "Alice")
            val sharedPermit = NotificationLateCorrectionPermit()
            store.rememberPosted(
                identity = identity(invite),
                displayedName = null,
                lateCorrectionPermit = sharedPermit,
            )
            val candidate = store.refreshCandidates("Alice", resolvedName = "Alice").single()

            assertTrue(candidate.lateCorrectionPermit.acquire())
            val contentClaim = async { sharedPermit.acquire() }
            runCurrent()
            assertFalse(contentClaim.isCompleted)
            candidate.lateCorrectionPermit.complete(written = true)
            runCurrent()

            assertFalse(contentClaim.await())
        }

    /** Drops pending invite identity and correction ownership on account cache clear. */
    @Test
    fun accountCacheClearDropsPendingInviteIdentityAndClaims() {
        val store = GroupInviteNotificationIdentityRefreshStore()
        val invite = update("alice-invite", "Alice")
        store.rememberPosted(identity(invite), displayedName = null)
        assertRefreshCandidate(store, invite)

        store.clear()

        assertTrue(store.claimPendingRefreshes().isEmpty())
        assertTrue(store.refreshCandidates("Alice", resolvedName = "Alice").isEmpty())
    }

    /** Supersedes an older same-key candidate so ABA refresh cannot rewrite a repost. */
    @Test
    fun repostingTheSameInviteKeySupersedesAnOlderRefreshCandidate() {
        val store = GroupInviteNotificationIdentityRefreshStore()
        val invite = update("alice-invite", "Alice")
        store.rememberPosted(identity(invite), displayedName = null)
        val oldCandidate = store.refreshCandidates("Alice", resolvedName = "Alice v1").single()

        store.rememberPosted(identity(invite), displayedName = "Alice v1")
        val newCandidate = store.refreshCandidates("Alice", resolvedName = "Alice v2").single()

        assertFalse(store.isCurrent(oldCandidate))
        assertTrue(store.isCurrent(newCandidate))

        store.release(oldCandidate)
        store.forget(oldCandidate)

        assertTrue(store.isCurrent(newCandidate))
        assertTrue(store.claimPendingRefreshes().isEmpty())
    }

    /** Restores a claimed candidate after a non-terminal failed write. */
    @Test
    fun failedRefreshCanBeRetried() {
        val store = GroupInviteNotificationIdentityRefreshStore()
        val invite = update("alice-invite", "Alice")
        store.rememberPosted(identity(invite), displayedName = null)

        val candidate = refreshCandidate(store)
        store.release(candidate)
        assertRefreshCandidate(store, invite)
    }

    /** Releases the store claim when refresh work throws. */
    @Test
    fun thrownRefreshReleasesClaimForRetry() =
        runTest {
            val store = GroupInviteNotificationIdentityRefreshStore()
            val invite = update("alice-invite", "Alice")
            store.rememberPosted(identity(invite), displayedName = null)
            val candidate = refreshCandidate(store)

            val failure =
                runCatching {
                    store.runClaimedRefresh(candidate) {
                        error("lookup failed")
                    }
                }

            assertTrue(failure.exceptionOrNull() is IllegalStateException)
            assertRefreshCandidate(store, invite)
        }

    /** Releases the store claim when refresh work is cancelled. */
    @Test
    fun cancelledRefreshReleasesClaimForRetry() =
        runTest {
            val store = GroupInviteNotificationIdentityRefreshStore()
            val invite = update("alice-invite", "Alice")
            store.rememberPosted(identity(invite), displayedName = null)
            val candidate = refreshCandidate(store)

            val failure =
                runCatching {
                    store.runClaimedRefresh(candidate) {
                        throw CancellationException("refresh cancelled")
                    }
                }

            assertTrue(failure.exceptionOrNull() is CancellationException)
            assertRefreshCandidate(store, invite)
        }

    /** Leaves normal-completion retry or terminal disposition to the refresh block. */
    @Test
    fun normallyCompletedRefreshKeepsTheBlocksExplicitRetryDisposition() =
        runTest {
            val store = GroupInviteNotificationIdentityRefreshStore()
            val invite = update("alice-invite", "Alice")
            store.rememberPosted(identity(invite), displayedName = null)
            val candidate = refreshCandidate(store)

            store.runClaimedRefresh(candidate) {
                store.release(candidate)
            }

            assertRefreshCandidate(store, invite)
        }

    /** Refuses to retain ordinary messages in the invite-only store. */
    @Test
    fun nonInviteUpdatesAreNeverTracked() {
        val nonInvite = update("message", "Alice").copy(trigger = NotificationTriggerFfi.NEW_MESSAGE)

        assertTrue(
            postedGroupInviteIdentity(nonInvite, posted = true, redactContent = false, displayedName = null) == null,
        )
    }

    /** Requires the expected candidate and finalizes it according to [posted]. */
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

    /** Requires one candidate for the fixture sender's resolved profile update. */
    private fun refreshCandidate(store: GroupInviteNotificationIdentityRefreshStore): RefreshCandidate =
        store.refreshCandidates("Alice", resolvedName = "Alice").single()

    /** Converts a typed invite into the minimal persisted refresh identity. */
    private fun identity(update: NotificationUpdateFfi): GroupInviteNotificationIdentity =
        requireNotNull(
            postedGroupInviteIdentity(
                update = update,
                posted = true,
                redactContent = false,
                displayedName = null,
            ),
        ).identity

    /** Builds a deterministic invite or non-invite update for store isolation. */
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
