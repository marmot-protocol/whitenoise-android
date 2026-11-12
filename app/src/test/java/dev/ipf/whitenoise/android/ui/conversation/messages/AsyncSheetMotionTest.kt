package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import dev.ipf.whitenoise.android.ui.settings.AddRelaySheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Existing mutation owners explicitly report their accepted outcome; busy=false alone never closes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AsyncSheetMotionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun reportOutcomeWaitsForHideAndFinishesOnce() {
        val shown = mutableStateOf(true)
        val busy = mutableStateOf(false)
        var finish: (() -> Unit)? = null
        var dismissals = 0
        render {
            if (shown.value) {
                ReportMessageSheet(
                    onDismissRequest = {
                        dismissals++
                        shown.value = false
                    },
                    sending = busy.value,
                    onSubmit = { _, _, accepted ->
                        busy.value = true
                        finish = accepted
                    },
                )
            }
        }
        rule.onNodeWithTag("message.report.send").performClick()
        rule.runOnIdle {
            assertEquals(0, dismissals)
            busy.value = false
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            requireNotNull(finish).invoke()
            requireNotNull(finish).invoke()
        }
        rule.mainClock.advanceTimeBy(80)
        rule.runOnIdle { assertEquals(0, dismissals) }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(1, dismissals) }
        rule.onNodeWithTag(REPORT_SHEET_TEST_TAG).assertDoesNotExist()
    }

    @Test fun partialRelayOutcomeRetainsInputUntilExplicitSuccess() {
        val shown = mutableStateOf(true)
        val busy = mutableStateOf(false)
        var finish: (() -> Unit)? = null
        var submissions = 0
        var dismissals = 0
        render {
            if (shown.value) {
                AddRelaySheet(
                    existing = emptyList(),
                    busy = busy.value,
                    rejectedUrl = null,
                    onDismiss = {
                        dismissals++
                        shown.value = false
                    },
                    onAdd = { _, _, accepted ->
                        submissions++
                        busy.value = true
                        finish = accepted
                    },
                )
            }
        }
        rule.onNodeWithTag("relay.add.url").performTextInput("wss://relay.example.com")
        rule.onNodeWithTag("relay.add.submit").performClick()
        rule.runOnIdle { busy.value = false }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(0, dismissals)
            assertEquals(1, submissions)
        }
        rule.onNodeWithTag("relay.add.submit").performClick()
        rule.runOnIdle {
            busy.value = false
            assertEquals(2, submissions)
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { requireNotNull(finish).invoke() }
        rule.mainClock.advanceTimeBy(80)
        rule.runOnIdle { assertEquals(0, dismissals) }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun replacedReportOwnerCannotCloseTheNewSheet() {
        val owner = mutableStateOf(0)
        var finish: (() -> Unit)? = null
        var dismissals = 0
        render {
            ReportMessageSheet(
                onDismissRequest = { dismissals++ },
                ownerKey = owner.value,
                onSubmit = { _, _, accepted -> finish = accepted },
            )
        }
        rule.onNodeWithTag("message.report.send").performClick()
        rule.runOnIdle { owner.value++ }
        rule.waitForIdle()
        rule.runOnIdle { requireNotNull(finish).invoke() }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(0, dismissals) }
        rule.onNodeWithTag(REPORT_SHEET_TEST_TAG).assertExists()
    }

    @Test fun acceptedReportExitCannotBeInterruptedByUpwardDrag() {
        val shown = mutableStateOf(true)
        var finish: (() -> Unit)? = null
        var dismissals = 0
        render {
            if (shown.value) {
                ReportMessageSheet(
                    onDismissRequest = {
                        dismissals++
                        shown.value = false
                    },
                    onSubmit = { _, _, accepted -> finish = accepted },
                )
            }
        }
        rule.onNodeWithTag("message.report.send").performClick()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { requireNotNull(finish).invoke() }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag(REPORT_SHEET_TEST_TAG).performTouchInput { swipeUp(durationMillis = 64) }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(1, dismissals) }
        rule.onNodeWithTag(REPORT_SHEET_TEST_TAG).assertDoesNotExist()
    }

    private fun render(content: @Composable () -> Unit) {
        rule.setContent {
            WhiteNoiseTheme(
                darkTheme = true,
                amoled = true,
            ) { Box(Modifier.fillMaxSize().background(Color.Black)) { content() } }
        }
        rule.waitForIdle()
    }
}
