package dev.ipf.darkmatter.ui

import dev.ipf.darkmatter.core.GroupProjector
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the conversation bottom-bar membership-display gate (issues #545 and
 * #623). The gate must never paint a state it doesn't actually know during the
 * brief window before `refreshMembers()` confirms the roster:
 *
 * - #545: a group the user LEFT must not flash the active composer.
 * - #623 (inverse): a group the user IS a member of — especially an admin
 *   re-entering their own group — must not flash the "no longer a member"
 *   notice.
 *
 * [ComposerGate.COMPOSER] → active composer; [ComposerGate.NOTICE] →
 * RemovedMemberComposerNotice; [ComposerGate.PENDING] → render nothing and wait
 * for the confirmed result.
 */
class ConversationComposerGateTest {
    @Test
    fun confirmedNotMemberShowsNotice() {
        assertEquals(
            ComposerGate.NOTICE,
            conversationComposerGate(
                membersLoaded = true,
                isSelfMember = false,
                seededSelfMember = false,
                seededMembershipKnown = true,
            ),
        )
    }

    @Test
    fun confirmedMemberShowsComposer() {
        assertEquals(
            ComposerGate.COMPOSER,
            conversationComposerGate(
                membersLoaded = true,
                isSelfMember = true,
                seededSelfMember = false,
                seededMembershipKnown = false,
            ),
        )
    }

    @Test
    fun loadingKnownMemberShowsComposerWithoutFlash() {
        // Seed positively places self in the roster → no blank-bar flash while
        // refreshMembers() verifies (preserves the #264 intent).
        assertEquals(
            ComposerGate.COMPOSER,
            conversationComposerGate(
                membersLoaded = false,
                isSelfMember = false,
                seededSelfMember = true,
                seededMembershipKnown = true,
            ),
        )
    }

    @Test
    fun loadingLeftGroupShowsNoticeImmediately() {
        // #545: the left group's cached snapshot is present but has self removed
        // (seededMembershipKnown = true, seededSelfMember = false), so the
        // disabled notice renders at once instead of flashing the composer.
        assertEquals(
            ComposerGate.NOTICE,
            conversationComposerGate(
                membersLoaded = false,
                isSelfMember = false,
                seededSelfMember = false,
                seededMembershipKnown = true,
            ),
        )
    }

    @Test
    fun loadingColdOpenWaitsInsteadOfFlashing() {
        // #623: no cached snapshot at all (first-ever open / fresh process / a
        // chat-list row tapped before its background member fetch landed) →
        // membership is unknown, so render NOTHING and upgrade on the confirmed
        // result. The old gate defaulted this case to the notice, which flashed
        // the "no longer a member" state for a member (the inverse of #545).
        assertEquals(
            ComposerGate.PENDING,
            conversationComposerGate(
                membersLoaded = false,
                isSelfMember = false,
                seededSelfMember = false,
                seededMembershipKnown = false,
            ),
        )
    }

    @Test
    fun confirmedMembershipAlwaysWinsOverColdSeed() {
        // Once refreshMembers() confirms self is a member, the composer shows
        // regardless of the (now irrelevant) cold seed.
        assertEquals(
            ComposerGate.COMPOSER,
            conversationComposerGate(
                membersLoaded = true,
                isSelfMember = true,
                seededSelfMember = false,
                seededMembershipKnown = false,
            ),
        )
    }

    /**
     * Regression for the #545 adversarial-review blocking finding: the pure gate
     * above only checks the decision given a `seededSelfMember` value — it does
     * not prove the leave path actually produces a self-free seed.
     *
     * Before that fix, the successful-leave paths did not invalidate the cached
     * member snapshot, so re-opening the just-left group seeded a roster that
     * still contained self (`seededSelfMember == true`) and flashed the active
     * composer.
     *
     * This pins the end-to-end invariant: a snapshot that DID contain self,
     * after the leave transform [GroupProjector.membersWithoutActiveAccount],
     * seeds `seededSelfMember == false` while staying a present (known) snapshot,
     * so the gate renders the notice immediately during the membership-load
     * window.
     */
    @Test
    fun snapshotInvalidatedOnLeaveSeedsNoSelfMember() {
        val self = "aa11"
        val other = "bb22"
        val rosterBeforeLeave =
            listOf(
                member(self),
                member(other),
            )

        // Sanity: pre-leave the seed would (correctly) place self in the group.
        val seededBeforeLeave =
            rosterBeforeLeave.any { GroupProjector.isActiveAccountMember(it, self) }
        assertEquals(true, seededBeforeLeave)

        // The successful-leave transform the cache-invalidation now applies.
        val rosterAfterLeave =
            GroupProjector.membersWithoutActiveAccount(rosterBeforeLeave, self)
        val seededAfterLeave =
            rosterAfterLeave.any { GroupProjector.isActiveAccountMember(it, self) }

        assertEquals(false, seededAfterLeave)
        // The snapshot is still present (the row's roster is known), so the seed
        // is authoritative: the gate shows the notice without an active-composer
        // flash.
        assertEquals(
            ComposerGate.NOTICE,
            conversationComposerGate(
                membersLoaded = false,
                isSelfMember = false,
                seededSelfMember = seededAfterLeave,
                seededMembershipKnown = true,
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
