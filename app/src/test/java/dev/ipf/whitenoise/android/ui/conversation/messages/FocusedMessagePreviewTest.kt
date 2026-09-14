package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class FocusedMessagePreviewTest {
    @get:Rule val composeRule = createComposeRule()

    /** Excerpt preserves native styles without any interactive links. */
    @Test fun excerptPreservesNativeStylesWithoutAnyInteractiveLinks() {
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("Bold "))),
                                MarkdownInlineFfi.Link(
                                    dest = "https://example.com",
                                    title = null,
                                    children = listOf(MarkdownInlineFfi.Text("link")),
                                    classification = MarkdownLinkDestinationKindFfi.WEB,
                                ),
                            ),
                        ),
                    ),
            )
        val excerpt = focusedMessagePreviewText("raw", document)
        assertEquals("Bold link", excerpt.text)
        assertTrue(excerpt.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(excerpt.spanStyles.any { it.item.textDecoration == TextDecoration.Underline })
        assertTrue(excerpt.getLinkAnnotations(0, excerpt.length).isEmpty())
    }

    /** Visual canvas shrinks before layout and preserves aspect when constrained. */
    @Test fun visualCanvasShrinksBeforeLayoutAndPreservesAspectWhenConstrained() {
        assertEquals(IntSize(225, 150), focusedVisualCanvasSize(IntSize(300, 200), 400, 600))
        assertEquals(IntSize(200, 100), focusedVisualCanvasSize(IntSize(400, 200), 200, 600))
        assertEquals(IntSize(60, 120), focusedVisualCanvasSize(IntSize(180, 360), 400, 120))
        assertEquals(IntSize.Zero, focusedVisualCanvasSize(IntSize.Zero, 400, 600))
    }

    /** Media only description retains author attachment time and delivery. */
    @Test fun mediaOnlyDescriptionRetainsAuthorAttachmentTimeAndDelivery() {
        assertEquals(
            "Alice, Media attachment, 12:34, Sent",
            focusedMessagePreviewDescription("Alice", "", listOf("Media attachment"), "12:34", "Sent", null),
        )
    }

    /** Text preview ellipsizes at five lines without changing source. */
    @Test fun textPreviewEllipsizesAtFiveLinesWithoutChangingSource() {
        val source = "This full original message remains available to native copy and forward. ".repeat(30)
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(260.dp)) {
                    FocusedTextMessagePreview(
                        presentation = messageBubblePresentation(deleted = false, mine = true),
                        mine = true,
                        text = source,
                        document = null,
                        time = "12:34",
                        status = MessageStatus.Sent,
                        showStatus = true,
                    )
                }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithTag("message-actions-excerpt")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertEquals(5, layout.lineCount)
        assertTrue(layout.isLineEllipsized(4))
        assertEquals(source, layout.layoutInput.text.text)
    }

    /** Focused stack centers on its source and clamps to the visible ime frame. */
    @Test fun focusedStackCentersOnItsSourceAndClampsToTheVisibleImeFrame() {
        val provider = FocusedMessageActionsPositionProvider(IntRect(20, 200, 220, 260), null)
        val regular =
            provider.calculatePosition(
                IntRect.Zero,
                IntSize(360, 780),
                LayoutDirection.Ltr,
                IntSize(360, 300),
            )
        val ime = provider.calculatePosition(IntRect.Zero, IntSize(360, 320), LayoutDirection.Rtl, IntSize(360, 300))
        assertEquals(80, regular.y)
        assertEquals(20, ime.y)
        assertEquals(0, regular.x)
        assertEquals(0, ime.x)
    }
}
