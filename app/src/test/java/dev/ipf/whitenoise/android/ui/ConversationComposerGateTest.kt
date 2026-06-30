package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.core.GroupProjector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the conversation bottom-bar membership-display gate (issues #545, #623,
 * #802). The gate must never paint a state it doesn't actually know during the
 * brief window before `refreshMembers()` confirms the roster:
 *
 * - #545: a group the user LEFT must never flash the active composer.
 * - #623 (inverse): a group the user IS a member of — especially an admin
 *   re-entering their own group, OR tapping another account's notification
 *   while its roster is still stale/cross-account — must not flash the "no
 *   longer a member" notice.
 * - #802: a pending invite shows explicit Join/Decline actions.
 *
 * The removed-member notice is shown only once `membersVerified` — i.e.
 * `refreshMembers()` has confirmed the roster. A merely-seeded roster that omits
 * self is NOT enough (it can be a stale/cross-account snapshot), so the gate
 * waits ([ComposerGate.PENDING]) — while a seed that positively contains self,
 * or an open from a message notification (which implies membership), shows the
 * composer immediately.
 */
class ConversationComposerGateTest {
    @Test
    fun pendingInviteShowsInviteActionsBeforeMembershipGate() {
        assertEquals(
            ComposerGate.INVITE,
            conversationComposerGate(
                pendingInvite = true,
                membersVerified = true,
                isSelfMember = true,
                seededSelfMember = true,
                seededMembershipKnown = true,
                assumeMemberUntilVerified = false,
            ),
        )
    }

    @Test
    fun verifiedNotMemberShowsNotice() {
        assertEquals(
            ComposerGate.NOTICE,
            conversationComposerGate(
                pendingInvite = false,
                membersVerified = true,
                isSelfMember = false,
                seededSelfMember = false,
                seededMembershipKnown = true,
                assumeMemberUntilVerified = false,
            ),
        )
    }

    @Test
    fun verifiedNotMemberWinsOverNotificationOptimism() {
        // A stale notification for a group the user has since left: once
        // refreshMembers() verifies non-membership, the notice wins even though
        // the open came from a notification.
        assertEquals(
            ComposerGate.NOTICE,
            conversationComposerGate(
                pendingInvite = false,
                membersVerified = true,
                isSelfMember = false,
                seededSelfMember = false,
                seededMembershipKnown = true,
                assumeMemberUntilVerified = true,
            ),
        )
    }

    @Test
    fun confirmedMemberShowsComposer() {
        assertEquals(
            ComposerGate.COMPOSER,
            conversationComposerGate(
                pendingInvite = false,
                membersVerified = true,
                isSelfMember = true,
                seededSelfMember = false,
                seededMembershipKnown = false,
                assumeMemberUntilVerified = false,
            ),
        )
    }

    @Test
    fun loadingKnownMemberShowsComposerWithoutFlash() {
        // Seed positively places self in the roster → show the composer at once,
        // no blank-bar flash while refreshMembers() verifies (#264 intent).
        assertEquals(
            ComposerGate.COMPOSER,
            conversationComposerGate(
                pendingInvite = false,
                membersVerified = false,
                isSelfMember = false,
                seededSelfMember = true,
                seededMembershipKnown = true,
                assumeMemberUntilVerified = false,
            ),
        )
    }

    @Test
    fun notificationOpenShowsComposerBeforeVerification() {
        // Tapping a message notification implies membership of that group, so the
        // composer shows immediately even with a stale/cross-account seed that
        // omits self — no placeholder or removed-notice flash on the switch.
        assertEquals(
            ComposerGate.COMPOSER,
            conversationComposerGate(
                pendingInvite = false,
                membersVerified = false,
                isSelfMember = false,
                seededSelfMember = false,
                seededMembershipKnown = true,
                assumeMemberUntilVerified = true,
            ),
        )
    }

    @Test
    fun unverifiedSeedWithoutSelfWaitsInsteadOfFlashingNotice() {
        // A seeded roster that omits self but is NOT yet verified, opened by a
        // path other than a notification (so no membership implication). The gate
        // waits rather than flashing the "removed" notice; it still never flashes
        // the composer (the #545 harm). Resolves on verification.
        assertEquals(
            ComposerGate.PENDING,
            conversationComposerGate(
                pendingInvite = false,
                membersVerified = false,
                isSelfMember = false,
                seededSelfMember = false,
                seededMembershipKnown = true,
                assumeMemberUntilVerified = false,
            ),
        )
    }

    @Test
    fun loadingColdOpenWaitsInsteadOfFlashing() {
        // #623: no cached snapshot at all → membership unknown → render nothing
        // and upgrade on the confirmed result.
        assertEquals(
            ComposerGate.PENDING,
            conversationComposerGate(
                pendingInvite = false,
                membersVerified = false,
                isSelfMember = false,
                seededSelfMember = false,
                seededMembershipKnown = false,
                assumeMemberUntilVerified = false,
            ),
        )
    }

    @Test
    fun confirmedMembershipAlwaysWinsOverColdSeed() {
        assertEquals(
            ComposerGate.COMPOSER,
            conversationComposerGate(
                pendingInvite = false,
                membersVerified = true,
                isSelfMember = true,
                seededSelfMember = false,
                seededMembershipKnown = false,
                assumeMemberUntilVerified = false,
            ),
        )
    }

    /**
     * The leave transform must produce a self-free seed (the #545 cache
     * invalidation), so once membership is verified the gate shows the notice
     * rather than the composer.
     */
    @Test
    fun leaveTransformSeedsNoSelfMemberAndVerifiedShowsNotice() {
        val self = "aa11"
        val other = "bb22"
        val rosterBeforeLeave = listOf(member(self), member(other))

        val seededBeforeLeave = rosterBeforeLeave.any { GroupProjector.isActiveAccountMember(it, self) }
        assertEquals(true, seededBeforeLeave)

        val rosterAfterLeave = GroupProjector.membersWithoutActiveAccount(rosterBeforeLeave, self)
        val seededAfterLeave = rosterAfterLeave.any { GroupProjector.isActiveAccountMember(it, self) }
        assertEquals(false, seededAfterLeave)

        // Unverified, non-notification open: wait (no composer flash, no notice).
        assertEquals(
            ComposerGate.PENDING,
            conversationComposerGate(
                pendingInvite = false,
                membersVerified = false,
                isSelfMember = false,
                seededSelfMember = seededAfterLeave,
                seededMembershipKnown = true,
                assumeMemberUntilVerified = false,
            ),
        )
        // Verified not-member: notice.
        assertEquals(
            ComposerGate.NOTICE,
            conversationComposerGate(
                pendingInvite = false,
                membersVerified = true,
                isSelfMember = false,
                seededSelfMember = seededAfterLeave,
                seededMembershipKnown = true,
                assumeMemberUntilVerified = false,
            ),
        )
    }

    private fun member(memberId: String) =
        AppGroupMemberRecordFfi(
            memberIdHex = memberId,
            account = memberId,
            local = false,
        )
}
