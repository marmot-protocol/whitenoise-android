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

    /** A close failure wins regardless of whether the sheet animation finished first. */
    @Test
    fun failedCloseStaysRetryableInEitherCompletionOrder() =
        kotlinx.coroutines.runBlocking {
            for (hiddenFirst in listOf(false, true)) {
                val owner = StartupDiagnosticsCloseState()
                owner.prompt = StartupDiagnosticsPromptState.CHOICE
                val attempt = owner.begin(false, true)!!
                if (hiddenFirst) owner.hidden()
                owner.save(attempt, { true }, { error("Audit was already settled") }, { false })
                if (!hiddenFirst) owner.hidden()
                assertEquals(StartupDiagnosticsPromptState.CLOSE_ERROR, owner.prompt)
                assertTrue(owner.failed)
                assertEquals(owner.prompt, owner.prompt.refreshed(true, false, false))
            }
        }

    /** Saving one independent receipt never repeats it when the remaining decline is retried. */
    @Test
    fun explicitRetryWritesOnlyTheUnsettledChoice() =
        kotlinx.coroutines.runBlocking {
            val owner = StartupDiagnosticsCloseState()
            var audits = 0
            var usages = 0
            val attempt = owner.begin(true, true)!!
            owner.hidden()
            owner.save(attempt, { true }, {
                audits++
                true
            }, {
                usages++
                false
            })
            assertEquals(StartupDiagnosticsPromptState.CLOSE_ERROR, owner.prompt)
            val retry = owner.retry()!!
            assertEquals(null, owner.retry())
            owner.save(retry, { true }, {
                audits++
                true
            }, {
                usages++
                true
            })
            assertEquals(1, audits)
            assertEquals(2, usages)
            assertEquals(StartupDiagnosticsPromptState.CLOSED, owner.prompt)
        }

    /** An audit failure leaves telemetry unsettled until the explicit retry succeeds. */
    @Test
    fun auditFailureRetainsBothPendingOperations() =
        kotlinx.coroutines.runBlocking {
            val owner = StartupDiagnosticsCloseState()
            var usages = 0
            val attempt = owner.begin(true, true)!!
            owner.save(attempt, { true }, { false }, {
                usages++
                true
            })
            owner.hidden()
            assertEquals(0, usages)
            owner.save(owner.retry()!!, { true }, { true }, {
                usages++
                true
            })
            assertEquals(1, usages)
            assertEquals(StartupDiagnosticsPromptState.CLOSED, owner.prompt)
        }

    /** A newer explicit choice or runtime cannot inherit an old dismissal's UI result or second write. */
    @Test
    fun replacementAndExplicitChoiceInvalidateOldClose() =
        kotlinx.coroutines.runBlocking {
            for (replaceRuntime in listOf(false, true)) {
                val owner = StartupDiagnosticsCloseState()
                var current = true
                var usages = 0
                val attempt = owner.begin(true, true)!!
                owner.save(attempt, { current }, {
                    if (replaceRuntime) current = false else owner.choiceStarted()
                    true
                }, {
                    usages++
                    true
                })
                if (!replaceRuntime) {
                    owner.hidden()
                    assertEquals(StartupDiagnosticsPromptState.CHOICE, owner.prompt)
                }
                assertEquals(0, usages)
            }
        }

    /** A slow successful save stays closed and a rapid second close starts no second transaction. */
    @Test
    fun successfulCloseDoesNotReopenOrDuplicateWrites() =
        kotlinx.coroutines.runBlocking {
            val owner = StartupDiagnosticsCloseState()
            val attempt = owner.begin(false, true)!!
            assertEquals(null, owner.begin(false, true))
            owner.hidden()
            assertEquals(StartupDiagnosticsPromptState.CLOSED, owner.prompt)
            owner.save(attempt, { true }, { error("No audit choice") }, { true })
            assertEquals(StartupDiagnosticsPromptState.CLOSED, owner.prompt)
            assertFalse(owner.failed)
        }
}
