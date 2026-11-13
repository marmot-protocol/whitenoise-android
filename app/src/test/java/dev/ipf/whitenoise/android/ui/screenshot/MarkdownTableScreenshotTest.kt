package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownAlignmentFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownTableCellFfi
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Content-sized Markdown table columns (#3562): asymmetric widths, wrapped rows and shared column
 * boundaries across a ragged row, captured in message bubbles.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MarkdownTableScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A light incoming bubble keeps short columns compact and wraps only the paragraph column. */
    @Test
    fun bubbleTableLight() {
        captureBubble("light")
    }

    /** The dark bubble shares the same allocation and divider contrast. */
    @Test
    fun bubbleTableDark() {
        captureBubble("dark", dark = true)
    }

    /** AMOLED keeps the shared allocation on its pure-black surface. */
    @Test
    fun bubbleTableAmoled() {
        captureBubble("amoled", dark = true, amoled = true)
    }

    /** Large RTL text mirrors the columns and wraps every column inside a narrow bubble. */
    @Test
    fun bubbleTableLargeRtl() {
        captureBubble("large_rtl", scale = 1.5f, rtl = true)
    }

    /** Renders the fixture inside an incoming-bubble surface of the conversation's bubble width. */
    private fun captureBubble(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        scale: Float = 1f,
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    Surface(Modifier.testTag(TAG)) {
                        Box(Modifier.padding(16.dp)) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.width(280.dp),
                            ) {
                                MarkdownMessageBody(document = document(), modifier = Modifier.padding(12.dp))
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/markdown_table_bubble_$name.png")
    }

    private companion object {
        const val TAG = "markdown-table-fixture"
        const val SOURCE =
            "| # | Task | Owner | Hours |\n| --- | --- | :---: | ---: |\n" +
                "| 1 | Draft the release notes and confirm every *linked* issue is closed | Ana | 3 |\n" +
                "| 2 | Verify `checksums` | Bo | 12 |\n| 3 | Publish |"

        /** One plain-text cell. */
        fun cell(text: String) = MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text(text)))

        /** Short index and owner columns, a paragraph task column, a right-aligned number and a ragged row. */
        fun document() =
            MarkdownDocumentFfi(
                blocks =
                    listOf(
                        MarkdownBlockFfi.Table(
                            alignments =
                                listOf(
                                    MarkdownAlignmentFfi.NONE,
                                    MarkdownAlignmentFfi.NONE,
                                    MarkdownAlignmentFfi.CENTER,
                                    MarkdownAlignmentFfi.RIGHT,
                                ),
                            header = listOf(cell("#"), cell("Task"), cell("Owner"), cell("Hours")),
                            rows =
                                listOf(
                                    listOf(
                                        cell("1"),
                                        MarkdownTableCellFfi(
                                            listOf(
                                                MarkdownInlineFfi.Text("Draft the release notes and confirm every "),
                                                MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text("linked"))),
                                                MarkdownInlineFfi.Text(" issue is closed"),
                                            ),
                                        ),
                                        cell("Ana"),
                                        cell("3"),
                                    ),
                                    listOf(
                                        cell("2"),
                                        MarkdownTableCellFfi(
                                            listOf(
                                                MarkdownInlineFfi.Text("Verify "),
                                                MarkdownInlineFfi.Code("checksums"),
                                            ),
                                        ),
                                        cell("Bo"),
                                        cell("12"),
                                    ),
                                    listOf(cell("3"), cell("Publish")),
                                ),
                        ),
                    ),
                truncated = false,
                blankLinesBefore = byteArrayOf(),
            )
    }
}
