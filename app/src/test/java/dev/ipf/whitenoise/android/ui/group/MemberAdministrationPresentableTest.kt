package dev.ipf.whitenoise.android.ui.group

import dev.ipf.whitenoise.android.state.GroupRosterLoadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The presentation gate enables member administration from a warm seed while
 * the authoritative roster is still loading, keeps a cold open gated, and
 * never enables from a roster that failed or disagreed with itself.
 */
class MemberAdministrationPresentableTest {
    /** A warm seed proving membership enables the action before the roster round-trip completes. */
    @Test
    fun aSeededMemberPresentsEnabledWhileTheRosterIsStillLoading() {
        assertTrue(memberAdministrationPresentable(GroupRosterLoadState.LOADING, seededSelfMember = true))
    }

    /** With no snapshot and no projected membership, the loading gate holds. */
    @Test
    fun aColdOpenWithoutAMembershipSignalKeepsTheLoadingGate() {
        assertFalse(memberAdministrationPresentable(GroupRosterLoadState.LOADING, seededSelfMember = false))
    }

    /** READY enables regardless of how the controller was seeded. */
    @Test
    fun anAuthoritativeRosterAlwaysPresentsEnabled() {
        assertTrue(memberAdministrationPresentable(GroupRosterLoadState.READY, seededSelfMember = false))
        assertTrue(memberAdministrationPresentable(GroupRosterLoadState.READY, seededSelfMember = true))
    }

    /** A roster that failed or disagreed with itself withdraws the action even when the seed says member. */
    @Test
    fun aFailedOrInconsistentRosterNeverPresentsEnabledEvenWithASeed() {
        listOf(GroupRosterLoadState.FAILED, GroupRosterLoadState.INCONSISTENT).forEach { state ->
            val seeded = memberAdministrationPresentable(state, seededSelfMember = true)
            val cold = memberAdministrationPresentable(state, seededSelfMember = false)
            assertFalse("$state must not enable from a seed", seeded)
            assertFalse("$state must not enable cold", cold)
        }
    }
}
