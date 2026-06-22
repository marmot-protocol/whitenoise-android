package dev.ipf.darkmatter.ui

import dev.ipf.darkmatter.core.GroupProjector
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the conversation bottom-bar membership-display decision (issue #545).
 *
 * After leaving a group, re-opening it briefly rendered the active composer for
 * ~1s before flipping to the "no longer a member" notice, because the gate
 * defaulted to the composer during the membership-load window. The fix renders
 * the disabled notice by default while loading and upgrades to the composer
 * only when self is believed to be a member — confirmed, or known from the
 * seeding snapshot ([shouldShowComposer]).
 *
 * `true` → active composer; `false` → RemovedMemberComposerNotice.
 */
class ConversationComposerGateTest {
    @Test
    fun confirmedNotMemberShowsNotice() {
        assertFalse(
            shouldShowComposer(
                membersLoaded = true,
                isSelfMember = false,
                seededSelfMember = false,
            ),
        )
    }

    @Test
    fun confirmedMemberShowsComposer() {
        assertTrue(
            shouldShowComposer(
                membersLoaded = true,
                isSelfMember = true,
                seededSelfMember = false,
            ),
        )
    }

    @Test
    fun loadingKnownMemberShowsComposerWithoutFlash() {
        // Seed positively places self in the roster → no blank-bar flash while
        // refreshMembers() verifies (preserves the #264 intent).
        assertTrue(
            shouldShowComposer(
                membersLoaded = false,
                isSelfMember = false,
                seededSelfMember = true,
            ),
        )
    }

    @Test
    fun loadingLeftGroupShowsNoticeImmediately() {
        // #545: the left group's cached snapshot has self removed, so the
        // disabled notice renders at once instead of flashing the composer.
        assertFalse(
            shouldShowComposer(
                membersLoaded = false,
                isSelfMember = false,
                seededSelfMember = false,
            ),
        )
    }

    @Test
    fun loadingColdOpenDefaultsToNotice() {
        // No cached snapshot at all (first-ever open / fresh install) →
        // seededSelfMember is false, so we default to the disabled state and
        // upgrade only after a confirmed-member result, per the issue's
        // requested tradeoff.
        assertFalse(
            shouldShowComposer(
                membersLoaded = false,
                isSelfMember = false,
                seededSelfMember = false,
            ),
        )
    }

    /**
     * Regression for the adversarial-review blocking finding (#545): the pure
     * predicate above only checks the gate given a `seededSelfMember` value —
     * it does not prove the leave path actually produces a self-free seed.
     *
     * Before the fix, the successful-leave paths did not invalidate the cached
     * member snapshot, so re-opening the just-left group seeded a roster that
     * still contained self (`seededSelfMember == true`) and
     * `shouldShowComposer(false, false, true)` flashed the active composer.
     *
     * This pins the end-to-end invariant: a snapshot that DID contain self,
     * after the leave transform [GroupProjector.membersWithoutActiveAccount],
     * seeds `seededSelfMember == false`, so the gate renders the notice
     * immediately during the membership-load window.
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
        assertTrue(seededBeforeLeave)

        // The successful-leave transform the cache-invalidation now applies.
        val rosterAfterLeave =
            GroupProjector.membersWithoutActiveAccount(rosterBeforeLeave, self)
        val seededAfterLeave =
            rosterAfterLeave.any { GroupProjector.isActiveAccountMember(it, self) }

        assertFalse(seededAfterLeave)
        // The seed the next ConversationController computes is now false, so
        // the bottom bar shows the notice without an active-composer flash.
        assertFalse(
            shouldShowComposer(
                membersLoaded = false,
                isSelfMember = false,
                seededSelfMember = seededAfterLeave,
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
