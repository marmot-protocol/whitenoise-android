package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real return-focus modifier only owns its initial visible return, not later user actions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GlobalSearchReturnFocusTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var inputModeManager: InputModeManager

    @Test
    fun returnedFocusTargetsTheActualClickableChatRow() {
        val selected = mutableStateOf(false)
        composeRule.setContent {
            inputModeManager = LocalInputModeManager.current
            WhiteNoiseTheme {
                ChatRowLayout(
                    title = "Found message",
                    timestampAt = 0uL,
                    rowHasUnread = false,
                    selectionMode = false,
                    selected = true,
                    leadingContent = {},
                    supportingContent = { Text("Matching message preview") },
                    supportingMetadata = null,
                    modifier = globalSearchReturnFocusModifier(selected.value) { true }.testTag("result-row"),
                )
            }
        }
        composeRule.runOnIdle {
            assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard))
            selected.value = true
        }
        composeRule.onNodeWithTag("result-row").assertIsFocused()
    }

    @Test
    fun quickBackRefreshesSelectionWithoutDisposingSearch() {
        val owner = GlobalSearchSelectionOwner(mutableStateOf(GlobalSearchSelectedResult()))
        var restored: GlobalSearchSelectedResult? = null
        composeRule.setContent {
            val snapshot = rememberReturnedSearchSelection(owner)
            SideEffect { restored = snapshot }
        }
        val selected = GlobalSearchSelectedResult("group", "message")
        composeRule.runOnIdle { owner.selected = selected }
        composeRule.runOnIdle { assertEquals(GlobalSearchSelectedResult(), restored) }
        composeRule.runOnIdle { owner.onConversationReturned() }
        composeRule.runOnIdle { assertEquals(selected, restored) }
    }

    @Test
    fun visibleReturnedResultReceivesFocusWithoutTrappingIt() {
        val selected = mutableStateOf(false)
        composeRule.setContent {
            inputModeManager = LocalInputModeManager.current
            WhiteNoiseTheme {
                Column {
                    Button(
                        onClick = {},
                        modifier = globalSearchReturnFocusModifier(selected.value) { true }.testTag("returned"),
                    ) { Text("Returned result") }
                    Button(
                        onClick = {},
                        modifier = Modifier.testTag("next"),
                    ) { Text("Next result") }
                }
            }
        }
        // SystemDefined Material focus targets intentionally reject keyboard focus in Touch mode.
        composeRule.runOnIdle {
            assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard))
            selected.value = true
        }
        composeRule.onNodeWithTag("returned").assertIsFocused()
        composeRule.onNodeWithTag("next").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithTag("next").assertIsFocused()
        composeRule.runOnIdle { selected.value = false }
        composeRule.onNodeWithTag("returned").assertIsNotFocused()
    }

    @Test
    fun remountedRowCannotStealFocusUntilAnotherExplicitReturn() {
        val mounted = mutableStateOf(false)
        val owner = GlobalSearchSelectionOwner(mutableStateOf(GlobalSearchSelectedResult("group", "message")))
        composeRule.setContent {
            inputModeManager = LocalInputModeManager.current
            WhiteNoiseTheme {
                Column {
                    if (mounted.value) {
                        Button(
                            onClick = {},
                            modifier =
                                globalSearchReturnFocusModifier(
                                    restoredSelection = true,
                                    returnGeneration = owner.returnGeneration,
                                    consumeReturn = { owner.consumeReturnFocus(owner.returnGeneration) },
                                ) { true }.testTag("recycled"),
                        ) { Text("Returned result") }
                    }
                    Button(onClick = {}, modifier = Modifier.testTag("other")) { Text("Other result") }
                }
            }
        }
        composeRule.runOnIdle {
            assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard))
            mounted.value = true
        }
        composeRule.onNodeWithTag("recycled").assertIsFocused()
        composeRule.onNodeWithTag("other").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithTag("other").assertIsFocused()
        composeRule.runOnIdle { mounted.value = false }
        composeRule.runOnIdle { mounted.value = true }
        composeRule.onNodeWithTag("other").assertIsFocused()
        composeRule.onNodeWithTag("recycled").assertIsNotFocused()
        composeRule.runOnIdle { owner.onConversationReturned() }
        composeRule.onNodeWithTag("recycled").assertIsFocused()
    }

    @Test
    fun focusConsumptionRejectsRepeatedAndSupersededReturns() {
        val owner = GlobalSearchSelectionOwner(mutableStateOf(GlobalSearchSelectedResult()))
        assertTrue(owner.consumeReturnFocus(0L))
        assertFalse(owner.consumeReturnFocus(0L))
        owner.onConversationReturned()
        assertFalse(owner.consumeReturnFocus(0L))
        assertTrue(owner.consumeReturnFocus(1L))
        assertFalse(owner.consumeReturnFocus(1L))
    }

    @Test
    fun neverComposedReturnedRowCannotAcquireFocusWhenScrolledIntoViewLater() {
        val mounted = mutableStateOf(false)
        val ready = mutableStateOf(false)
        val owner = GlobalSearchSelectionOwner(mutableStateOf(GlobalSearchSelectedResult("group", "message")))
        composeRule.setContent {
            inputModeManager = LocalInputModeManager.current
            globalSearchReturnFocusExpiryEffect(owner, ready.value) { mounted.value }
            WhiteNoiseTheme {
                Column {
                    if (mounted.value) {
                        Button(
                            onClick = {},
                            modifier =
                                globalSearchReturnFocusModifier(
                                    restoredSelection = true,
                                    returnGeneration = owner.returnGeneration,
                                    consumeReturn = { owner.consumeReturnFocus(owner.returnGeneration) },
                                ) { true }.testTag("late-row"),
                        ) { Text("Old selected result") }
                    }
                    Button(onClick = {}, modifier = Modifier.testTag("current")) { Text("Current control") }
                }
            }
        }
        composeRule.runOnIdle {
            assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard))
            ready.value = true
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("current").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.runOnIdle { mounted.value = true }
        composeRule.onNodeWithTag("current").assertIsFocused()
        composeRule.onNodeWithTag("late-row").assertIsNotFocused()
    }

    @Test
    fun offscreenSelectionDoesNotRequestFocusOrScroll() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(globalSearchReturnFocusModifier(true) { false }) {
                    Button(onClick = {}, modifier = Modifier.testTag("offscreen")) { Text("Old result") }
                }
            }
        }
        composeRule.onNodeWithTag("offscreen").assertIsNotFocused()
    }
}
