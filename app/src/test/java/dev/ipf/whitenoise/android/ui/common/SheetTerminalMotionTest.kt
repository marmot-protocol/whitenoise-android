package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the real Material hide and veto contract, rather than a second animation model. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SheetTerminalMotionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun firstTerminalActionWaitsForHiddenAndRunsOnlyOnce() {
        var completions = 0
        render {
            val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val terminal = rememberSheetTerminalMotion(sheet)
            WhiteNoiseModalBottomSheet(onDismissRequest = { terminal.close { completions++ } }, sheetState = sheet) {
                TextButton(onClick = {
                    terminal.close {
                        assertEquals(SheetValue.Hidden, sheet.currentValue)
                        completions++
                    }
                    terminal.close { completions += 100 }
                }) { Text("Close sheet") }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Close sheet").performClick()
        rule.runOnIdle { assertEquals(0, completions) }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(1, completions) }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(1, completions) }
    }

    @Test fun vetoCommitsNothingAndTheCurrentOwnerCanRetry() {
        val allowHide = mutableStateOf(false)
        var completions = 0
        render {
            val confirm = remember { { value: SheetValue -> value != SheetValue.Hidden || allowHide.value } }
            val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirm)
            val terminal = rememberSheetTerminalMotion(sheet)
            WhiteNoiseModalBottomSheet(onDismissRequest = {}, sheetState = sheet) {
                TextButton(onClick = { terminal.close { completions++ } }) { Text("Close sheet") }
            }
        }
        rule.onNodeWithText("Close sheet").performClick()
        rule.runOnIdle {
            assertEquals(0, completions)
            allowHide.value = true
        }
        rule.onNodeWithText("Close sheet").performClick()
        rule.runOnIdle { assertEquals(1, completions) }
    }

    @Test fun successfulMutationWaitsForBusyVetoAndRejectsDuplicateInput() {
        val busy = mutableStateOf(true)
        var completions = 0
        render {
            val sheet =
                rememberModalBottomSheetState(
                    skipPartiallyExpanded = true,
                    confirmValueChange = { it != SheetValue.Hidden || !busy.value },
                )
            val terminal = rememberSheetTerminalMotion(sheet)
            WhiteNoiseModalBottomSheet(onDismissRequest = {}, sheetState = sheet) {
                TextButton(onClick = {
                    terminal.closeWhenReady({ !busy.value }) {
                        assertEquals(SheetValue.Hidden, sheet.currentValue)
                        completions++
                    }
                    terminal.close { completions += 100 }
                }) { Text("Complete mutation") }
            }
        }
        rule.onNodeWithText("Complete mutation").performClick()
        rule.runOnIdle {
            assertEquals(0, completions)
            busy.value = false
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1, completions) }
    }

    @Test fun acceptedAsyncSuccessCannotBeInterruptedBySheetDrag() {
        val busy = mutableStateOf(true)
        var completions = 0
        lateinit var terminal: SheetTerminalMotion
        render {
            val sheet =
                rememberModalBottomSheetState(
                    skipPartiallyExpanded = true,
                    confirmValueChange = { it != SheetValue.Hidden || !busy.value },
                )
            terminal = rememberSheetTerminalMotion(sheet)
            WhiteNoiseModalBottomSheet(
                onDismissRequest = { terminal.close { completions += 100 } },
                sheetState = sheet,
                interactionEnabled = terminal.acceptsInput && !busy.value,
            ) { Text("Accepted image") }
        }
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            terminal.closeWhenReady({ !busy.value }) { completions++ }
            busy.value = false
        }
        // Compose the closed input ingress before sending the competing gesture.
        rule.mainClock.advanceTimeByFrame()
        rule
            .onNodeWithTag("sheet.dragHandle", useUnmergedTree = true)
            .performTouchInput { swipeUp(durationMillis = 80) }
        rule.runOnIdle {
            assertEquals(false, terminal.acceptsInput)
            terminal.close { completions += 100 }
        }
        rule.mainClock.advanceTimeBy(1000)
        rule.runOnIdle { assertEquals(1, completions) }
    }

    @Test fun sourceReplacementCancelsTheAcceptedResult() {
        val owner = mutableStateOf(0)
        var completions = 0
        render {
            key(owner.value) {
                val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val terminal = rememberSheetTerminalMotion(sheet)
                WhiteNoiseModalBottomSheet(onDismissRequest = {}, sheetState = sheet) {
                    TextButton(onClick = { terminal.close { completions++ } }) { Text("Close sheet") }
                }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Close sheet").performClick()
        rule.runOnIdle { owner.value++ }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(0, completions) }
    }

    private fun render(content: @Composable () -> Unit) {
        rule.setContent {
            WhiteNoiseTheme(
                darkTheme = true,
                amoled = true,
            ) { Box(Modifier.fillMaxSize()) { content() } }
        }
        rule.waitForIdle()
    }
}
