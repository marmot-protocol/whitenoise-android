package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignOutOutcomeTest {
    @Test
    fun switchesToARemainingAccountAndStaysReady() {
        val outcome = signOutOutcome(accountLabels = listOf("alice", "bob"), activeRef = "alice")
        assertEquals("bob", outcome.nextActiveRef)
        assertEquals(AppPhase.Ready, outcome.phase)
    }

    @Test
    fun signingOutTheLastAccountDropsToOnboarding() {
        // Regression for #11: previously phase stayed Ready with no active
        // account, leaving a broken MainShell.
        val outcome = signOutOutcome(accountLabels = listOf("alice"), activeRef = "alice")
        assertNull(outcome.nextActiveRef)
        assertEquals(AppPhase.Onboarding, outcome.phase)
    }

    @Test
    fun signingOutWithNoAccountsLeftGoesToOnboarding() {
        val outcome = signOutOutcome(accountLabels = emptyList(), activeRef = "alice")
        assertNull(outcome.nextActiveRef)
        assertEquals(AppPhase.Onboarding, outcome.phase)
    }
}

class ShouldResetNavOnAccountChangeTest {
    @Test
    fun resetsWhenSwitchingBetweenTwoDistinctAccounts() {
        // #547: Sign Out & Wipe of the active account while another remains
        // (and the manual #316 switcher) keeps AppPhase.Ready and switches the
        // active ref. The shell stays mounted, so its deep nav (Identity & Keys
        // for the now-deleted account) must be popped to the chat-list root.
        assertTrue(shouldResetNavOnAccountChange(previous = "alice", current = "bob"))
    }

    @Test
    fun doesNotResetOnInitialComposition() {
        // First composition / process-death rebuild reports a null previous,
        // so the saved screen/conversation (#386) is preserved, not popped.
        assertFalse(shouldResetNavOnAccountChange(previous = null, current = "alice"))
    }

    @Test
    fun doesNotResetWhenAccountUnchanged() {
        // Recomposition with the same active ref (e.g. an unrelated state
        // change) must not clobber the current screen.
        assertFalse(shouldResetNavOnAccountChange(previous = "alice", current = "alice"))
    }

    @Test
    fun doesNotResetWhenAccountGoesAway() {
        // The no-accounts case drops to AppPhase.Onboarding, which tears the
        // shell down at the top-level router; no in-shell reset is needed.
        assertFalse(shouldResetNavOnAccountChange(previous = "alice", current = null))
    }
}
