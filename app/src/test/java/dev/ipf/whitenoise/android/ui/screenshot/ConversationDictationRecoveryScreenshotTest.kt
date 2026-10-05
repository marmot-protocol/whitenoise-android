package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationPartialSendDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-mdpi")
class ConversationDictationRecoveryScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lightConfirmation() = capture("dictation_partial_send_light.png", dark = false, scale = 1f, rtl = false)

    @Test
    fun largeRtlDark() = capture("dictation_partial_send_large_rtl_dark.png", dark = true, scale = 2f, rtl = true)

    @Test
    fun retryAudioLight() =
        capture(
            "dictation_partial_send_retry_light.png",
            dark = false,
            scale = 1f,
            rtl = false,
            retryAudio = true,
        )

    @Test
    fun retryAudioLargeRtlDark() =
        capture(
            "dictation_partial_send_retry_large_rtl_dark.png",
            dark = true,
            scale = 2f,
            rtl = true,
            retryAudio = true,
        )

    private fun capture(
        name: String,
        dark: Boolean,
        scale: Float,
        rtl: Boolean,
        retryAudio: Boolean = false,
    ) {
        val recoveryLabel = if (retryAudio) "Retry" else "Open the speech service"
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = scale) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        ConversationDictationPartialSendDialog(
                            onDismiss = {},
                            onSend = {},
                            onOpenSettings = {},
                            settingsLabel = recoveryLabel,
                        )
                    }
                }
            }
        }
        if (System.getProperty("roborazzi.test.record") == "true") {
            java.io.File("build/outputs/roborazzi/diagnostics").mkdirs()
            composeRule
                .onNodeWithTag("dictation-partial-send-dialog")
                .captureRoboImage("build/outputs/roborazzi/diagnostics/$name")
        }
        assertWholeAction("Send recognized text")
        composeRule.onNodeWithText("Paste", useUnmergedTree = true).assertDoesNotExist()
        assertWholeAction(recoveryLabel)
        composeRule.onNodeWithTag("dictation-partial-send-dialog").captureRoboImage("src/test/snapshots/$name")
    }

    /** A partly clipped label passes assertIsDisplayed; action text must remain fully readable. */
    private fun assertWholeAction(label: String) {
        val node = composeRule.onNodeWithText(label, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.assertIsDisplayed()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        val layout = layouts.single()
        val visible = node.fetchSemanticsNode().boundsInRoot
        val details = "$label size=${layout.size} visible=$visible lines=${layout.lineCount}"
        assertFalse(details, layout.didOverflowHeight)
        // Intrinsic text width is rounded to integral pixels; a fractional-pixel overflow
        // must not hide a label. Check every line's actual width and its visible bounds.
        repeat(layout.lineCount) { line ->
            val width = layout.getLineRight(line) - layout.getLineLeft(line)
            org.junit.Assert.assertTrue(details, width <= layout.size.width + 1f)
            assertFalse(details, layout.isLineEllipsized(line))
        }
        assertEquals(details, layout.size.height.toFloat(), visible.height, 1f)
        assertEquals(details, layout.size.width.toFloat(), visible.width, 1f)
    }
}
