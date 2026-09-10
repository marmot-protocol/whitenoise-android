package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Verifies the native disclosure contract against Android's existing expandable renderer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MarkdownNativeDetailsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Closed details expose their summary; expanding retains styled body and authored spacing. */
    @Test
    fun closedDetailsExpandOnTap() {
        render(open = false)
        composeRule.onNodeWithText("First paragraph").assertDoesNotExist()
        capture("markdown_native_details_collapsed.png")
        composeRule.onNodeWithText("Release details").performClick()
        composeRule.onNodeWithText("First paragraph").assertIsDisplayed()
        composeRule.onNodeWithText("Second paragraph").assertIsDisplayed()
        capture("markdown_native_details_expanded.png")
    }

    /** The native open flag is honored on first composition and can still be collapsed. */
    @Test
    fun initiallyOpenDetailsCanCollapse() {
        render(open = true)
        composeRule.onNodeWithText("First paragraph").assertIsDisplayed()
        composeRule.onNodeWithText("Release details").performClick()
        composeRule.onNodeWithText("First paragraph").assertDoesNotExist()
    }

    /** Renders a deterministic typed disclosure with extra source spacing between body paragraphs. */
    private fun render(open: Boolean) {
        val summary = listOf(MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("Release details"))))
        val document =
            MarkdownDocumentFfi(
                blocks =
                    listOf(
                        MarkdownBlockFfi.Details(
                            summary = summary,
                            open = open,
                            body =
                                listOf(
                                    MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("First paragraph"))),
                                    MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("Second paragraph"))),
                                ),
                            blankLinesBefore = byteArrayOf(0, 2),
                        ),
                    ),
                truncated = false,
                blankLinesBefore = byteArrayOf(0),
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.width(360.dp).testTag("native-details")) {
                    MarkdownMessageBody(document)
                }
            }
        }
    }

    /** Captures the settled disclosure rather than an intermediate expansion animation. */
    private fun capture(name: String) {
        composeRule.onNodeWithTag("native-details").captureRoboImage("src/test/snapshots/$name")
    }
}
