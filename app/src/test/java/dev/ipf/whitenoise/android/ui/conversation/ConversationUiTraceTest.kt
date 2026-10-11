package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowTrace
import java.time.Duration

/** Verifies opt-in admission and balanced synchronous attribution without changing rendering ownership. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationUiTraceTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Clears process-local session and platform trace state before each independent case. */
    @Before
    fun resetTracing() {
        PerformanceDiagnostics.stop()
        ShadowTrace.reset()
        ShadowTrace.setEnabled(true)
    }

    /** Leaves no active opt-in session or fake platform tracing for another test. */
    @After
    fun stopTracing() {
        PerformanceDiagnostics.stop()
        ShadowTrace.reset()
    }

    /** Inactive diagnostics preserve return values without recording any transcript section. */
    @Test
    fun stoppedSessionDoesNotRecordUiWork() {
        assertEquals(17, traceConversationUiSection(ConversationUiTrace.ROW) { 17 })
        assertTrue(uiSections().isEmpty())
    }

    /** Either disabled platform tracing or an expired consent window prevents new sections. */
    @Test
    fun disabledSystemTraceAndExpiredSessionDoNotRecord() {
        assertTrue(PerformanceDiagnostics.start().active)
        ShadowTrace.setEnabled(false)
        traceConversationUiSection(ConversationUiTrace.ROW) { Unit }
        ShadowTrace.setEnabled(true)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(31))
        traceConversationUiSection(ConversationUiTrace.TEXT) { Unit }
        assertFalse(PerformanceDiagnostics.isActive())
        assertTrue(uiSections().isEmpty())
    }

    /** A session expiring inside admitted work cannot unbalance the stack or replace the original exception. */
    @Test
    fun exceptionAfterExpiryClosesTheAdmittedSection() {
        assertTrue(PerformanceDiagnostics.start().active)
        val failure = IllegalStateException("fixture failure")
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                traceConversationUiSection(ConversationUiTrace.ROW) {
                    assertTrue(ShadowTrace.getCurrentSections().contains(ConversationUiTrace.ROW.label))
                    ShadowSystemClock.advanceBy(Duration.ofMinutes(31))
                    traceConversationUiSection(ConversationUiTrace.TEXT) { Unit }
                    throw failure
                }
            }
        assertSame(failure, thrown)
        assertFalse(ShadowTrace.getCurrentSections().contains(ConversationUiTrace.ROW.label))
        assertEquals(listOf(ConversationUiTrace.ROW.label), uiSections())
    }

    /** A child restart executes its text marker even when its parent row is skipped. */
    @Test
    fun childOnlyRecompositionRetainsItsOwnAttribution() {
        assertTrue(PerformanceDiagnostics.start().active)
        val value = mutableStateOf(0)
        var parentRuns = 0
        composeRule.setContent {
            traceConversationUiSection(ConversationUiTrace.ROW) {
                parentRuns++
                AttributedText(value)
            }
        }
        var previousParents = 0
        var previousText = 0
        composeRule.runOnIdle {
            previousParents = parentRuns
            previousText = uiSections().count { it == ConversationUiTrace.TEXT.label }
            assertTrue(previousText > 0)
            value.value = 1
        }
        composeRule.runOnIdle {
            assertEquals(previousParents, parentRuns)
            assertTrue(uiSections().count { it == ConversationUiTrace.TEXT.label } > previousText)
        }
    }

    /** Keeps the state read in the child restart scope rather than its caller. */
    @Composable
    @Suppress("FunctionNaming")
    private fun AttributedText(value: State<Int>) {
        traceConversationUiSection(ConversationUiTrace.TEXT) { Text(value.value.toString()) }
    }

    /** Excludes framework tracing so assertions concern only this closed transcript vocabulary. */
    private fun uiSections(): List<String> =
        ShadowTrace.getPreviousSections().filter {
            it.startsWith("WhiteNoise.conversation.")
        }
}
