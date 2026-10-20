package dev.ipf.whitenoise.android.ui.settings

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.whitenoise.android.ui.navigation.MainShellProcessState
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * How the consent sheet closes, and what it does while it is open.
 *
 * The sheet is the first thing a new install shows and it has no confirm button, so closing it *is* the
 * receipt. The close therefore happens on the gesture rather than after the writes it triggers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class UsageDiagnosticsPromptDismissTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * The thin sheet reports its animation completion once;
     * production persistence is tested through the startup owner.
     */
    @Test
    fun closingReportsDoneOnce() {
        val state = privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED)
        runBlocking { state.refreshSecurityPrivacySettings() }
        var doneCount = 0
        composeRule.setContent {
            WhiteNoiseTheme { UsageDiagnosticsPrompt(state, onCloseStarted = {}) { doneCount += 1 } }
        }

        composeRule.onNodeWithContentDescription(CLOSE).performClick()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) { idleAnd { doneCount > 0 } }
        repeat(SETTLING_PASSES) { composeRule.waitForIdle() }

        assertEquals("the sheet must report done exactly once", 1, doneCount)
    }

    /**
     * Choosing an option leaves the sheet usable rather than tearing it down.
     *
     * This does not prove the sheet was never rebuilt: that rebuild shows as an animation, and a settled
     * frame has the sheet back where it started either way. It catches the coarser failure — a choice
     * leaving the sheet gone or its close unreachable — while the rebuild itself is prevented by
     * remembering the confirm-value-change lambda, documented where that is written.
     */
    @Test
    fun choosingAnOptionLeavesTheSheetUsable() {
        val state = privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED)
        runBlocking { state.refreshSecurityPrivacySettings() }
        var choiceStarted = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                UsageDiagnosticsPrompt(state, onChoiceStarted = { choiceStarted++ }, onCloseStarted = {}, onDone = {})
            }
        }

        composeRule.onAllNodes(isToggleable())[0].performClick()
        assertEquals("an explicit choice must pin the sheet before its receipt settles", 1, choiceStarted)
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) { idleAnd { !state.diagnostics.busy } }

        composeRule.onNodeWithContentDescription(CLOSE).assertIsDisplayed()
    }

    /** Exercises the production startup owner rather than an always-mounted callback-only sheet. */
    @Test
    fun failedStartupDismissalOffersRetryAndPersistsTheDecline() {
        var writes = 0
        val state =
            privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED, beforeSave = {
                writes += 1
                if (writes == 1) error("Rejected consent write")
            })
        runBlocking { state.refreshSecurityPrivacySettings() }
        composeRule.setContent { WhiteNoiseTheme { StartupUsageDiagnosticsPrompt(state) } }
        composeRule.onNodeWithContentDescription(CLOSE).performClick()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) { idleAnd { state.diagnostics.failed } }
        assertNull(state.diagnostics.observations.ticket())
        composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            idleAnd {
                !state.diagnostics.busy &&
                    state.diagnostics.snapshot
                        ?.settings
                        ?.decision == UsageDiagnosticsDecisionFfi.DECLINED
            }
        }
        composeRule.onNodeWithContentDescription(CLOSE).assertDoesNotExist()
        assertEquals("only the failed decline is retried", 2, writes)
        assertEquals(false, state.diagnostics.granted)
        runBlocking { state.refreshSecurityPrivacySettings() }
        composeRule.onNodeWithContentDescription(CLOSE).assertDoesNotExist()
    }

    /** The production owner hides while a decline is pending, then restores a deliberate retry surface. */
    @Test
    fun slowRejectedCloseHidesBeforeFailureAndReopensRecovery() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val state =
            privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED, beforeSave = {
                entered.countDown()
                check(release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                error("Rejected delayed decline")
            })
        runBlocking { state.refreshSecurityPrivacySettings() }
        composeRule.setContent { WhiteNoiseTheme { StartupUsageDiagnosticsPrompt(state) } }
        try {
            composeRule.onNodeWithContentDescription(CLOSE).performClick()
            composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
                idleAnd {
                    entered.count == 0L &&
                        composeRule.onAllNodesWithContentDescription(CLOSE).fetchSemanticsNodes().isEmpty()
                }
            }
            assertNull(state.diagnostics.observations.ticket())
        } finally {
            release.countDown()
        }
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) { idleAnd { state.diagnostics.failed } }
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        assertNull(state.diagnostics.observations.ticket())
    }

    /** Removing startup presentation during hide must not resurrect a successfully declined sheet. */
    @Test
    fun removedPresentationSettlesItsInFlightClose() = removedPresentationSettlesClose(rejectFirst = false)

    /** A removed presentation still exposes a failed close on return and settles only explicit Retry. */
    @Test
    fun removedPresentationRestoresFailedCloseForRetry() = removedPresentationSettlesClose(rejectFirst = true)

    private fun removedPresentationSettlesClose(rejectFirst: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes =
            java.util.concurrent.atomic
                .AtomicInteger()
        val state =
            privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED, beforeSave = {
                val attempt = writes.incrementAndGet()
                entered.countDown()
                check(release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                if (rejectFirst && attempt == 1) error("Rejected while presentation was removed")
            })
        val owner = StartupDiagnosticsCloseState()
        val presented = mutableStateOf(true)
        runBlocking { state.refreshSecurityPrivacySettings() }
        composeRule.setContent {
            WhiteNoiseTheme { if (presented.value) StartupUsageDiagnosticsPrompt(state, owner) }
        }
        try {
            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithContentDescription(CLOSE).performClick()
            presented.value = false
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.autoAdvance = true
            composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) { idleAnd { entered.count == 0L } }
        } finally {
            composeRule.mainClock.autoAdvance = true
            release.countDown()
        }
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            idleAnd {
                if (rejectFirst) {
                    state.diagnostics.failed
                } else {
                    state.diagnostics.snapshot
                        ?.settings
                        ?.decision == UsageDiagnosticsDecisionFfi.DECLINED
                }
            }
        }
        presented.value = true
        composeRule.waitForIdle()
        if (rejectFirst) {
            assertEquals(StartupDiagnosticsPromptState.CLOSE_ERROR, owner.prompt)
            assertNull(state.diagnostics.observations.ticket())
            composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()
            composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
                idleAnd {
                    state.diagnostics.snapshot
                        ?.settings
                        ?.decision == UsageDiagnosticsDecisionFfi.DECLINED
                }
            }
        }
        composeRule.onNodeWithContentDescription(CLOSE).assertDoesNotExist()
        assertEquals(if (rejectFirst) 2 else 1, writes.get())
        assertEquals(StartupDiagnosticsPromptState.CLOSED, owner.prompt)
    }

    /** A fresh Activity holder receives the in-flight close; a replacement runtime receives no old result. */
    @Test
    fun replacementHolderRetainsCloseUntilNativeSaveSettles() =
        runBlocking {
            val state = privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED)
            val process = MainShellProcessState(state)
            val first = MainShellStateHolder(state, SavedStateHandle(), process)
            val owner = first.startupDiagnosticsPrompt(state.runtimeGeneration)
            val attempt = owner.begin(false, true)!!
            owner.hidden()
            val replacement = MainShellStateHolder(state, SavedStateHandle(), process)
            val retained = replacement.startupDiagnosticsPrompt(state.runtimeGeneration)
            org.junit.Assert.assertSame(owner, retained)
            retained.save(attempt, { true }, { error("Audit already settled") }, { false })
            assertEquals(StartupDiagnosticsPromptState.CLOSE_ERROR, retained.prompt)
            retained.save(retained.retry()!!, { true }, { error("Do not repeat saved audit") }, { true })
            assertEquals(StartupDiagnosticsPromptState.CLOSED, retained.prompt)
            val newRuntime = replacement.startupDiagnosticsPrompt(state.runtimeGeneration + 1)
            org.junit.Assert.assertNotSame(retained, newRuntime)
            assertEquals(StartupDiagnosticsPromptState.AWAITING_RECEIPT, newRuntime.prompt)
        }

    private fun idleAnd(condition: () -> Boolean): Boolean {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    private companion object {
        const val CLOSE = "Close"
        const val TIMEOUT_MS = 5_000L
        const val SETTLING_PASSES = 3
    }
}
