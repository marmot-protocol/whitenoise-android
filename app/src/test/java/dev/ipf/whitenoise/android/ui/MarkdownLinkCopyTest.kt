package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MarkdownLinkCopyTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun longPressingAutolinkCopiesItsUrlInsteadOfOpeningParentActions() {
        val url = "https://example.com/page"

        assertEquals(
            LongPressResult(copiedUrl = url, parentLongPresses = 0),
            longPress(autolinkDocument(url), visibleText = url),
        )
    }

    @Test
    fun longPressingExplicitLinkCopiesDestinationInsteadOfLabel() {
        val destination = "https://example.com/destination"
        val document =
            paragraphDocument(
                MarkdownInlineFfi.Link(
                    dest = destination,
                    title = null,
                    children = listOf(MarkdownInlineFfi.Text("visible label")),
                ),
            )

        assertEquals(
            LongPressResult(copiedUrl = destination, parentLongPresses = 0),
            longPress(document, visibleText = "visible label"),
        )
    }

    @Test
    fun longPressingPlainTextStillOpensParentActions() {
        val document = paragraphDocument(MarkdownInlineFfi.Text("plain message"))

        assertEquals(
            LongPressResult(copiedUrl = null, parentLongPresses = 1),
            longPress(document, visibleText = "plain message"),
        )
    }

    @Test
    fun tappingExplicitLinkStillOpensConfirmationDialog() {
        val destination = "https://example.com/tap-target"
        val document =
            paragraphDocument(
                MarkdownInlineFfi.Link(
                    dest = destination,
                    title = null,
                    children = listOf(MarkdownInlineFfi.Text("tap label")),
                ),
            )

        composeRule.setContent {
            WhiteNoiseTheme {
                MarkdownMessageBody(
                    document = document,
                    onCopyLink = {},
                )
            }
        }

        composeRule.onNodeWithText("tap label").performClick()
        composeRule.onNodeWithText(destination).assertIsDisplayed()
    }

    @Test
    fun accessibilityCustomActionCopiesAutolink() {
        var copiedUrl: String? = null
        val url = "https://example.com/accessibility"

        composeRule.setContent {
            WhiteNoiseTheme {
                MarkdownMessageBody(
                    document = autolinkDocument(url),
                    onCopyLink = { copiedUrl = it },
                )
            }
        }

        val actions = composeRule.onNodeWithText(url).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(1, actions.size)
        actions.single().action()

        composeRule.runOnIdle { assertEquals(url, copiedUrl) }
    }

    private fun longPress(
        document: MarkdownDocumentFfi,
        visibleText: String,
    ): LongPressResult {
        var copiedUrl: String? = null
        var parentLongPresses = 0

        composeRule.setContent {
            val linkLayouts = remember { mutableMapOf<Any, MarkdownLinkTextLayout>() }
            val rowCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
            WhiteNoiseTheme {
                Box(
                    Modifier
                        .onGloballyPositioned { rowCoordinates[0] = it }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val longPress = awaitLongPressOrCancellation(down.id)
                                if (longPress != null) {
                                    longPress.consume()
                                    val windowPosition = rowCoordinates[0]?.localToWindow(longPress.position)
                                    val link = windowPosition?.let { markdownLinkDestinationAt(linkLayouts.values, it) }
                                    if (link != null) {
                                        copiedUrl = link
                                    } else {
                                        parentLongPresses++
                                    }
                                }
                            }
                        },
                ) {
                    MarkdownMessageBody(
                        document = document,
                        onLinkTextLayoutChanged = { key, text, layoutResult, coordinates ->
                            if (layoutResult != null && coordinates != null) {
                                linkLayouts[key] = MarkdownLinkTextLayout(text, layoutResult, coordinates)
                            } else {
                                linkLayouts.remove(key)
                            }
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithText(visibleText).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            up()
        }
        composeRule.waitForIdle()

        return LongPressResult(copiedUrl, parentLongPresses)
    }

    private fun autolinkDocument(url: String) = paragraphDocument(MarkdownInlineFfi.Autolink(url, MarkdownAutolinkKindFfi.URI))

    private fun paragraphDocument(inline: MarkdownInlineFfi) =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(inline))),
        )

    private data class LongPressResult(
        val copiedUrl: String?,
        val parentLongPresses: Int,
    )
}
