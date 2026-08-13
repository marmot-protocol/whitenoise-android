package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageTextSelectionToolbarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectionToolbarDismissesOnBackPress() {
        var dismissals = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageTextSelectionToolbar(
                    visible = true,
                    canSpeak = true,
                    selectionBoundsInWindow = Rect(0f, 48f, 220f, 96f),
                    onSpeak = {},
                    onDismissRequest = { dismissals++ },
                )
            }
        }
        composeRule.waitForIdle()

        pressBack()

        composeRule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun selectionToolbarShowsSpeakAloudAndInvokesCallback() {
        var speakClicks = 0
        composeRule.setContent {
            var bounds by rememberSelectionBounds()
            WhiteNoiseTheme {
                Box(Modifier.size(240.dp)) {
                    Box(
                        Modifier
                            .size(120.dp, 40.dp)
                            .onGloballyPositioned { bounds = it.boundsInWindow() }
                            .testTag("selection-body"),
                    )
                    MessageTextSelectionToolbar(
                        visible = true,
                        canSpeak = true,
                        selectionBoundsInWindow = bounds,
                        onSpeak = { speakClicks++ },
                        onDismissRequest = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag("message_text_selection_speak")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, speakClicks) }
    }

    @Test
    fun selectionToolbarRemainsDisplayedWhenSelectionMovesWithoutResizing() {
        lateinit var selectionBounds: MutableState<Rect>
        composeRule.setContent {
            selectionBounds = remember { mutableStateOf(Rect(0f, 48f, 220f, 96f)) }
            WhiteNoiseTheme {
                Surface(Modifier.fillMaxSize()) {
                    MessageTextSelectionToolbar(
                        visible = true,
                        canSpeak = true,
                        selectionBoundsInWindow = selectionBounds.value,
                        onSpeak = {},
                        onDismissRequest = {},
                    )
                }
            }
        }
        val toolbar = composeRule.onNodeWithTag("message_text_selection_toolbar")
        toolbar.assertIsDisplayed()

        composeRule.runOnIdle {
            selectionBounds.value = Rect(0f, 148f, 220f, 196f)
        }

        toolbar.assertIsDisplayed()
        composeRule
            .onAllNodes(isRoot())[0]
            .captureRoboImage("src/test/snapshots/message_text_selection_toolbar_moved_anchor.png")
    }

    @Test
    fun selectionToolbarHidesWhenMappingIsUnavailable() {
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageTextSelectionToolbar(
                    visible = true,
                    canSpeak = true,
                    selectionBoundsInWindow = null,
                    onSpeak = {},
                    onDismissRequest = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("message_text_selection_speak").assertDoesNotExist()
    }

    @Test
    fun selectionToolbarHidesWhenSpeakAloudIsUnavailable() {
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageTextSelectionToolbar(
                    visible = true,
                    canSpeak = false,
                    selectionBoundsInWindow = Rect(0f, 0f, 100f, 40f),
                    onSpeak = {},
                    onDismissRequest = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("message_text_selection_speak").assertDoesNotExist()
    }

    @Composable
    private fun rememberSelectionBounds(): MutableState<Rect?> = remember { mutableStateOf(null) }
}
