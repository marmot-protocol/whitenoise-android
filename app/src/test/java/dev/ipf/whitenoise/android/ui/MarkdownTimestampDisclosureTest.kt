package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private const val TIMESTAMP_TEXT = "timestamp-text"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarkdownTimestampDisclosureTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val token = "<t:0:R>"
    private val disclosure = markdownTimestampAbsolute(0, 'F') + "\n" + token
    private val dismiss =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(
            R.string.dismiss,
        )

    /** An open tap disclosure stays open across relative-label refreshes and reopens after dismissal. */
    @Test
    fun disclosureSurvivesRefresh() {
        val text = mutableStateOf(timestampText("10 seconds ago"))
        composeRule.setContent { WhiteNoiseTheme { MarkdownTimestampText(text.value) } }
        composeRule.onNodeWithContentDescription(disclosure).performClick()
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()

        // Equal-length labels keep annotation ranges and cached clock callbacks unchanged.
        composeRule.runOnIdle { text.value = timestampText("11 seconds ago") }
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()
        composeRule.onNodeWithText(dismiss).performClick()
        composeRule.runOnIdle { text.value = timestampText("12 seconds ago") }
        composeRule.onNodeWithContentDescription(disclosure).performClick()
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()

        composeRule.runOnIdle { text.value = timestampText("1 minute ago") }
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()
    }

    /** Replacing the content without the disclosed token closes the dialog, and a later tap reopens it. */
    @Test
    fun removedTokenClearsDisclosure() {
        val text = mutableStateOf(timestampText("10 seconds ago"))
        composeRule.setContent { WhiteNoiseTheme { MarkdownTimestampText(text.value) } }
        composeRule.onNodeWithContentDescription(disclosure).performClick()

        composeRule.runOnIdle { text.value = AnnotatedString("replacement message") }
        composeRule.onNodeWithText(disclosure).assertDoesNotExist()
        composeRule.runOnIdle { text.value = timestampText("11 seconds ago") }
        composeRule.onNodeWithText(disclosure).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(disclosure).performClick()
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()
    }

    /** A press on the standalone label still opens disclosure when the label refreshes before release. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun labelPressSurvivesRefreshBeforeRelease() {
        val text = mutableStateOf(timestampText("59 seconds ago"))
        composeRule.setContent {
            WhiteNoiseTheme { MarkdownTimestampText(text.value, Modifier.testTag(TIMESTAMP_TEXT)) }
        }
        val label = composeRule.onNodeWithTag(TIMESTAMP_TEXT)
        // The right half of the line holds label glyphs, away from the leading clock. The refresh
        // changes the label's length, so its annotation ranges change as well as its text.
        label.performTouchInput { down(Offset(width * 0.6f, height / 2f)) }
        composeRule.runOnIdle { text.value = timestampText("1 minute ago") }
        label.performTouchInput { up() }
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()
    }

    /** Hovering the trailing half of a label glyph shows the tooltip, which stays up through a label refresh. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun hoverSurvivesRefresh() {
        val text = mutableStateOf(timestampText("59 seconds ago"))
        composeRule.setContent {
            WhiteNoiseTheme { MarkdownTimestampText(text.value, Modifier.testTag(TIMESTAMP_TEXT)) }
        }
        composeRule.onNodeWithTag(TIMESTAMP_TEXT).performMouseInput { moveTo(Offset(width * 0.6f, height / 2f)) }
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()

        composeRule.mainClock.autoAdvance = false
        val settled = composeRule.onNodeWithTag("timestamp.hover").captureToImage().asAndroidBitmap()
        composeRule.runOnIdle {
            text.value = timestampText("1 minute ago")
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        val refreshed = composeRule.onNodeWithTag("timestamp.hover").captureToImage().asAndroidBitmap()
        assertTrue("Relative label refresh must not restart tooltip growth", settled.sameAs(refreshed))
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()
        composeRule.mainClock.autoAdvance = true
        composeRule.runOnIdle { text.value = AnnotatedString("replacement message") }
        composeRule.onNodeWithText(disclosure).assertDoesNotExist()
    }

    /** Hover exit retains the disclosure until its final frame; a source replacement retires it. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun hoverExitRetainsFrameButSourceReplacementDoesNot() {
        val text = mutableStateOf(timestampText("59 seconds ago"))
        composeRule.setContent {
            WhiteNoiseTheme { MarkdownTimestampText(text.value, Modifier.testTag(TIMESTAMP_TEXT)) }
        }
        val label = composeRule.onNodeWithTag(TIMESTAMP_TEXT)
        label.performMouseInput { moveTo(Offset(width * 0.6f, height / 2f)) }
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()
        composeRule.mainClock.autoAdvance = false
        label.performMouseInput { exit() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("timestamp.hover").assertExists()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithTag("timestamp.hover").assertDoesNotExist()

        label.performMouseInput { moveTo(Offset(width * 0.6f, height / 2f)) }
        composeRule.mainClock.advanceTimeBy(500)
        label.performMouseInput { exit() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("timestamp.hover").assertExists()
        composeRule.runOnIdle { text.value = AnnotatedString("replacement message") }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("timestamp.hover").assertDoesNotExist()
        composeRule.mainClock.autoAdvance = true
    }

    /** Private source-render evidence: actual timestamp mouse entry and exit, not route simulation. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
    fun captureTimestampHoverGrowthAndExitFrames() {
        org.junit.Assume.assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        // Robolectric's default window shadow ignores display fitting for secondary windows.
        val property = "robolectric.windowManager.useRequestedSize"
        val previous = System.getProperty(property)
        System.setProperty(property, "true")
        try {
            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                    CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                MarkdownTimestampText(timestampText("59 seconds ago"), Modifier.testTag(TIMESTAMP_TEXT))
                            }
                        }
                    }
                }
            }
            val label = composeRule.onNodeWithTag(TIMESTAMP_TEXT)
            composeRule.onNodeWithTag("timestamp.hover").assertDoesNotExist()
            captureHoverFrames("timestamp-hover/growth") {
                label.performMouseInput { moveTo(Offset(width * 0.6f, height / 2f)) }
            }
            composeRule.onNodeWithTag("timestamp.hover").assertExists()
            composeRule.onNodeWithText(disclosure).assertIsDisplayed()
            captureHoverFrames("timestamp-hover/exit") { label.performMouseInput { exit() } }
            composeRule.onNodeWithTag("timestamp.hover").assertDoesNotExist()
            composeRule.mainClock.autoAdvance = true
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    private fun captureHoverFrames(
        name: String,
        action: () -> Unit,
    ) {
        check(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val folder = File("build/animation-frames/$name").apply { mkdirs() }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        captureScreenRoboImage(File(folder, "source.png").path)
        action()
        composeRule.runOnIdle { Snapshot.sendApplyNotifications() }
        repeat(32) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
    }

    /** Builds the annotated clock-and-label projection the renderer emits for [token]. */
    private fun timestampText(label: String) =
        buildAnnotatedString {
            appendInlineContent("markdown-timestamp-clock:" + token, "◷")
            append(" " + label)
            addStringAnnotation(TIMESTAMP_TAG, token, 0, length)
        }
}
