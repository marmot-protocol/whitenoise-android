package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
