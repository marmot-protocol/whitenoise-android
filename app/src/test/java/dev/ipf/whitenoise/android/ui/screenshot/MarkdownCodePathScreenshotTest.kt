package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownCodeBlockKindFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Measures the native code leaf while capturing the constrained production Markdown renderer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MarkdownCodePathScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Fenced paths keep source line boundaries and expose their complete endings. */
    @Test
    fun codePathsNarrowLight() {
        capture(Snapshot("narrow_light"))
    }

    /** The same overflow treatment remains readable on a dark code surface. */
    @Test
    fun codePathsNarrowDark() {
        capture(Snapshot("narrow_dark", dark = true))
    }

    /** AMOLED retains its existing pure-black source surface. */
    @Test
    fun codePathsNarrowAmoled() {
        capture(Snapshot("narrow_amoled", dark = true, amoled = true))
    }

    /** Indented Unicode code remains reachable beside RTL prose at twice the app's font size. */
    @Test
    fun codePathsNarrowLargeRtl() {
        capture(Snapshot("narrow_large_rtl", scale = 2f, rtl = true, kind = MarkdownCodeBlockKindFfi.INDENTED))
    }

    /** Captures only environment changes; every case drives the same real code leaf. */
    private data class Snapshot(
        val name: String,
        val dark: Boolean = false,
        val amoled: Boolean = false,
        val scale: Float = 1f,
        val rtl: Boolean = false,
        val kind: MarkdownCodeBlockKindFfi = MarkdownCodeBlockKindFfi.FENCED,
    )

    /** Checks authored text, measured lines and accessible overflow before and after scrolling. */
    @Suppress("LongMethod") // The rendered fixture and its before/after geometry share one Compose lifetime.
    private fun capture(snapshot: Snapshot) {
        val leadingPath =
            if (snapshot.rtl) {
                "/项目_\uD83D\uDE00_ملف_very_long_filename_document.md"
            } else {
                "/very_long_filename_without_spaces_example_document.md"
            }
        val content = leadingPath + "\n" + "directory/very_long_filename_without_spaces_example_document.md"
        var codeLayout: TextLayoutResult? = null
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (snapshot.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = snapshot.dark, amoled = snapshot.amoled, fontScale = snapshot.scale) {
                    Surface {
                        Box(Modifier.width(240.dp).padding(12.dp).testTag("code-path-fixture")) {
                            MarkdownMessageBody(
                                document =
                                    MarkdownDocumentFfi(
                                        blocks =
                                            listOf(
                                                MarkdownBlockFfi.CodeBlock(
                                                    kind = snapshot.kind,
                                                    info = "",
                                                    content = content,
                                                ),
                                            ),
                                        truncated = false,
                                        blankLinesBefore = byteArrayOf(),
                                    ),
                                onSelectableTextLayoutChanged = { _, layout, _ ->
                                    if (layout != null) codeLayout = layout
                                },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val fixture = composeRule.onNodeWithTag("code-path-fixture")
        fixture.captureRoboImage("src/test/snapshots/markdown_code_paths_${snapshot.name}.png")
        composeRule.runOnIdle {
            assertNotNull("The real code leaf must report its measured layout", codeLayout)
            val layout = requireNotNull(codeLayout)
            assertSourceLayout(layout, content)
            assertTrue(
                "Code overflow must not widen its surrounding surface",
                fixture.fetchSemanticsNode().size.width <= 240,
            )
        }
        val node = composeRule.onNodeWithText(content)
        val range = node.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("Every clipped path must be reachable", range.maxValue() > 0f)
        node.performSemanticsAction(SemanticsActions.ScrollBy) { action ->
            assertTrue(action(if (range.reverseScrolling) -range.maxValue() else range.maxValue(), 0f))
        }
        composeRule.waitForIdle()
        assertEquals("The complete line ending must be reachable", range.maxValue(), range.value(), 1f)
        fixture.captureRoboImage("src/test/snapshots/markdown_code_paths_${snapshot.name}_end.png")
    }

    /** Couples the visual baseline to unchanged source bytes, authored newlines and the leading slash's position. */
    private fun assertSourceLayout(
        layout: TextLayoutResult,
        content: String,
    ) {
        assertEquals("Code display must preserve authored characters", content, layout.layoutInput.text.text)
        assertEquals("Only the explicit source newline may break these paths", 2, layout.lineCount)
        assertEquals(
            "RTL surroundings must not move the leading slash to the path's end",
            0f,
            layout.getBoundingBox(0).left,
            0.1f,
        )
    }
}
