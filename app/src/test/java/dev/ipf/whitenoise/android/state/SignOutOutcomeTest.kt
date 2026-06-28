package dev.ipf.whitenoise.android.state

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

class NextNavAccountRefTest {
    @Test
    fun keepsLastRealRefAcrossWipeTeardownNull() {
        // #807 regression: Sign Out & Wipe drains the wiped account's streams
        // first, transiently nulling activeAccountRef (#610) before it lands on
        // the next account. The shell's previous-ref tracker must retain the
        // last real account across that null so the eventual settle onto the
        // next account is still seen as a distinct-account change and the
        // now-deleted account's Identity & Keys screen is popped (#547).
        val afterTeardown = nextNavAccountRef(previous = "alice", current = null)
        assertEquals("alice", afterTeardown)
        // Settling onto the next account is now a real alice -> bob change.
        assertTrue(shouldResetNavOnAccountChange(previous = afterTeardown, current = "bob"))
    }

    @Test
    fun adoptsTheSettledAccount() {
        // Once an account is active, the tracker advances to it so an unrelated
        // recomposition with the same ref doesn't pop the current screen.
        assertEquals("bob", nextNavAccountRef(previous = "alice", current = "bob"))
        assertEquals("alice", nextNavAccountRef(previous = null, current = "alice"))
    }

    @Test
    fun retainsRefWhenDroppingToNoAccounts() {
        // Dropping to no accounts (last sign-out / single-account wipe) settles
        // on null. The shell tears down at AppPhase.Onboarding regardless, so
        // retaining the old ref here is harmless and avoids a spurious null.
        assertEquals("alice", nextNavAccountRef(previous = "alice", current = null))
    }
}
