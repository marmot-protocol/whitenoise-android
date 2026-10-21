package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownCodeBlockKindFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageFullScreenBody
import dev.ipf.whitenoise.android.ui.conversation.messages.ReaderTextSelectionController
import dev.ipf.whitenoise.android.ui.conversation.messages.rememberReaderTextSelectionController
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventReaderBlock
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Protects measured source layout and its real selection/speech consumers, rather than inspecting modifier text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MarkdownCodePathLayoutCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var selection: ReaderTextSelectionController
    private var layout: TextLayoutResult? = null
    private var coordinates: LayoutCoordinates? = null
    private var speechLayout: TextLayoutResult? = null
    private var speechCoordinates: LayoutCoordinates? = null

    /** Referenced event content uses the shared code treatment rather than an independent wrapping path. */
    @Test
    fun referencedEventReaderKeepsTheCompletePathOnOneSourceLine() {
        val path = "directory/very_long_filename_without_spaces_example_document.md"
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(240.dp)) {
                    NostrEventReaderBlock(
                        block = MarkdownBlockFfi.CodeBlock(MarkdownCodeBlockKindFfi.FENCED, "", path),
                        mentionDisplayName = { null },
                        onNostrProfileTap = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val measured = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(path).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue(action(measured))
        }
        assertEquals(path, measured.single().layoutInput.text.text)
        assertEquals(1, measured.single().lineCount)
        scrollToEnd(path)
    }

    /** Reusing a reader for different source cannot carry the previous path's scroll offset into it. */
    @Test
    fun changingReaderSourceRetiresThePreviousCodeViewport() {
        val original = "/" + "directory/".repeat(40) + "old.md"
        var source by mutableStateOf(original)
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageFullScreenBody(
                    body = source,
                    markdownDocument =
                        MarkdownDocumentFfi(
                            listOf(MarkdownBlockFfi.CodeBlock(MarkdownCodeBlockKindFfi.FENCED, "", source)),
                            false,
                            byteArrayOf(),
                        ),
                    mentionDisplayName = null,
                    isGroupMember = null,
                    onNostrProfileTap = null,
                    onCopyMarkdownLink = {},
                    modifier = Modifier.width(240.dp),
                )
            }
        }
        composeRule.waitForIdle()
        scrollToEnd(original)
        composeRule.runOnIdle { source = "/new.md" }
        composeRule.waitForIdle()
        val range = horizontalRange("/new.md")
        assertEquals(0f, range.value(), 0.1f)
        assertEquals(0f, range.maxValue(), 0.1f)
        composeRule.onNodeWithText(original).assertDoesNotExist()
    }

    /** Indented native source retains Unicode characters, indentation and explicit newlines at system 200%. */
    @Test
    fun indentedUnicodePathsKeepTheirSourceLinesBesideRtlProse() {
        val content = "  directory/\uD83D\uDE00_项目_ملف.md\n/very_long_filename_without_spaces_example_document.md"
        render(MarkdownBlockFfi.CodeBlock(MarkdownCodeBlockKindFfi.INDENTED, "", content), scale = 2f)
        assertEquals(content, requireNotNull(layout).layoutInput.text.text)
        assertEquals(2, requireNotNull(layout).lineCount)
        val secondLineStart = content.indexOf('\n') + 1
        assertEquals(0f, requireNotNull(layout).getBoundingBox(secondLineStart).left, 0.1f)
        assertTrue(horizontalRange(content).maxValue() > 0f)
    }

    /** An oversized component remains one source line within a finite, scrollable viewport. */
    @Test
    fun oversizedFencedComponentStaysReachableWithoutWideningTheViewport() {
        val content = "/" + "long_filename_".repeat(700) + ".md"
        render(MarkdownBlockFfi.CodeBlock(MarkdownCodeBlockKindFfi.FENCED, "", content))
        assertEquals(content, requireNotNull(layout).layoutInput.text.text)
        assertEquals(1, requireNotNull(layout).lineCount)
        assertTrue(requireNotNull(coordinates).boundsInWindow().width <= 240f)
        scrollToEnd(content)
    }

    /** Horizontal placement changes must update both native-selection hits and spoken-word geometry. */
    @Test
    fun scrolledCodeUsesTheSameAuthoredOffsetForSelectionAndSpeech() {
        val content = "directory/" + "long_filename_".repeat(20) + "ending.md"
        render(MarkdownBlockFfi.CodeBlock(MarkdownCodeBlockKindFfi.FENCED, "", content))
        scrollToEnd(content)
        composeRule.runOnIdle {
            val measured = requireNotNull(layout)
            val position = requireNotNull(coordinates).localToWindow(measured.getBoundingBox(content.lastIndex).center)
            val hit = requireNotNull(selection.renderedHitAt(position))
            assertEquals(content, hit.renderedText)
            assertTrue(hit.renderedOffset in content.lastIndex..content.length)
            val spoken = requireNotNull(speechLayout)
            val spokenPosition =
                requireNotNull(speechCoordinates).localToWindow(spoken.getBoundingBox(content.lastIndex).center)
            assertEquals(position, spokenPosition)
            assertTrue(requireNotNull(speechCoordinates).boundsInWindow().contains(spokenPosition))
        }
    }

    /** Source treatment must not turn ordinary prose or inline code into horizontally scrolling blocks. */
    @Test
    fun proseAndInlineCodeRetainTheirExistingSoftWrapping() {
        val code = "directory/very_long_filename_without_spaces_example_document.md"
        val content = "Prose $code"
        render(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("Prose "), MarkdownInlineFfi.Code(code))))
        assertEquals(content, requireNotNull(layout).layoutInput.text.text)
        assertTrue(requireNotNull(layout).lineCount > 1)
        val semantics = composeRule.onNodeWithText(content).fetchSemanticsNode().config
        assertNull(semantics.getOrNull(SemanticsProperties.HorizontalScrollAxisRange))
    }

    /** The shared math treatment keeps the established sanitization and parser-newline contract. */
    @Test
    fun mathRetainsSanitizedTextAndExplicitLineBoundaries() {
        render(MarkdownBlockFfi.MathBlock("a\u202E + b\nc/d\n"))
        assertEquals("a + b\nc/d", requireNotNull(layout).layoutInput.text.text)
        assertEquals(2, requireNotNull(layout).lineCount)
    }

    /** Renders one native block through the production selection and speech reporting callbacks. */
    private fun render(block: MarkdownBlockFfi, scale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, scale),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                WhiteNoiseTheme {
                    val controller = rememberReaderTextSelectionController(block)
                    selection = controller
                    MarkdownMessageBody(
                        document = MarkdownDocumentFfi(listOf(block), false, byteArrayOf()),
                        modifier = Modifier.width(240.dp),
                        onSelectableTextLayoutChanged = { key, measured, placed ->
                            controller.selectableTextLayoutReporter(key, measured, placed)
                            if (measured != null && placed != null) {
                                layout = measured
                                coordinates = placed
                            }
                        },
                        ttsSentenceLayoutReporter = { _, _, measured, placed ->
                            if (measured != null && placed != null) {
                                speechLayout = measured
                                speechCoordinates = placed
                            }
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Reads the accessible scroll range exposed by the actual rendered source leaf. */
    private fun horizontalRange(content: String): ScrollAxisRange {
        val semantics = composeRule.onNodeWithText(content).fetchSemanticsNode().config
        return semantics[SemanticsProperties.HorizontalScrollAxisRange]
    }

    /** Uses the native accessibility action and requires the final source column to remain reachable. */
    private fun scrollToEnd(content: String) {
        val range = horizontalRange(content)
        assertTrue(range.maxValue() > 0f)
        composeRule.onNodeWithText(content).performSemanticsAction(SemanticsActions.ScrollBy) { action ->
            assertTrue(action(range.maxValue(), 0f))
        }
        composeRule.waitForIdle()
        assertEquals(range.maxValue(), range.value(), 1f)
    }
}
