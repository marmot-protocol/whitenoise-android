package dev.ipf.whitenoise.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays the startup read-error/recovery boundary without recording any consent choice. */
class StartupDiagnosticsPromptStateTest {
    /** Previously the startup open flag stayed latched after a successful retry of an existing receipt. */
    @Test
    fun errorOnlyPromptClosesWhenAnExistingDecisionLoads() {
        val error = StartupDiagnosticsPromptState.AWAITING_RECEIPT.refreshed(true, false, true)
        assertEquals(StartupDiagnosticsPromptState.READ_ERROR, error)
        assertTrue(error.visible)
        val recovered = error.refreshed(true, false, false)
        assertEquals(StartupDiagnosticsPromptState.CLOSED, recovered)
        assertFalse(recovered.visible)
    }

    /** Neither loading nor repeated failure can dismiss the only visible retry surface. */
    @Test
    fun failedOrUnfinishedRetryRemainsVisible() {
        val error = StartupDiagnosticsPromptState.READ_ERROR
        assertEquals(error, error.refreshed(false, false, false))
        assertEquals(error, error.refreshed(true, false, true))
    }

    /** A renewed usage or audit choice remains open after the first independent switch is saved. */
    @Test
    fun genuineChoiceNeverAutoClosesAfterSettlement() {
        val choice = StartupDiagnosticsPromptState.AWAITING_RECEIPT.refreshed(true, true, false)
        assertEquals(StartupDiagnosticsPromptState.CHOICE, choice)
        assertEquals(choice, choice.refreshed(true, false, false))
        assertEquals(choice, choice.refreshed(true, false, true))
    }

    /** An unreadable receipt can recover to renewal instead of recovering to an existing decision. */
    @Test
    fun recoveryRequiringConsentBecomesAPersistentChoice() {
        val choice = StartupDiagnosticsPromptState.READ_ERROR.refreshed(true, true, false)
        assertEquals(StartupDiagnosticsPromptState.CHOICE, choice)
        assertEquals(choice, choice.refreshed(true, false, false))
    }

    /** A valid startup receipt needs no prompt; later incidental settings reads cannot open another. */
    @Test
    fun settledOrDismissedPromptStaysClosedForTheRuntime() {
        val closed = StartupDiagnosticsPromptState.AWAITING_RECEIPT.refreshed(true, false, false)
        assertEquals(StartupDiagnosticsPromptState.CLOSED, closed)
        assertEquals(closed, closed.refreshed(true, false, true))
        assertEquals(closed, closed.refreshed(true, true, false))
    }

    /** Startup waits for evidence rather than interpreting an absent receipt as a declined choice. */
    @Test
    fun initialLoadingDoesNotOpenOrSettleThePrompt() {
        val awaiting = StartupDiagnosticsPromptState.AWAITING_RECEIPT
        assertEquals(awaiting, awaiting.refreshed(false, false, false))
        assertFalse(awaiting.visible)
    }
}
