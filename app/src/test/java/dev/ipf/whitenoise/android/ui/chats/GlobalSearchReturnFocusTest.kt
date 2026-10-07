package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
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
        val selected = mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                val next = remember { FocusRequester() }
                Column {
                    Box(globalSearchReturnFocusModifier(selected.value) { true }) {
                        Button(onClick = {}, modifier = Modifier.testTag("returned")) { Text("Returned result") }
                    }
                    Button(
                        onClick = { next.requestFocus() },
                        modifier = Modifier.focusRequester(next).testTag("next"),
                    ) { Text("Next result") }
                }
            }
        }
        composeRule.onNodeWithTag("returned").assertIsFocused()
        composeRule.onNodeWithTag("next").performClick().assertIsFocused()
        composeRule.runOnIdle { selected.value = false }
        composeRule.onNodeWithTag("returned").assertIsNotFocused()
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
