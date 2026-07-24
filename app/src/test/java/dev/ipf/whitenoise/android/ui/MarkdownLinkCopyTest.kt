package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.messages.consumePointerInputUntilReleased
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleLongPressPositionInWindow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                    classification = MarkdownLinkDestinationKindFfi.WEB,
                ),
            )

        assertEquals(
            LongPressResult(copiedUrl = destination, parentLongPresses = 0),
            longPress(document, visibleText = "visible label"),
        )
    }

    @Test
    fun partiallyClippedRowStillCopiesThePressedLink() {
        val url = "https://example.com/clipped"

        assertEquals(
            LongPressResult(copiedUrl = url, parentLongPresses = 0),
            longPress(autolinkDocument(url), visibleText = url, partiallyClipped = true),
        )
    }

    @Test
    fun longPressingPlainTextStillOpensParentActions() {
        val document = paragraphDocument(MarkdownInlineFfi.Text("plain message"))

        val result = longPress(document, visibleText = "plain message")
        assertEquals(LongPressResult(copiedUrl = null, parentLongPresses = 1), result)
        composeRule.onNodeWithText(string(R.string.link_confirm_title)).assertDoesNotExist()
    }

    @Test
    fun longPressingLinkCopiesWithoutOpeningAndSubsequentTapStillOpensLink() {
        val destination = "https://example.com/long-press-copy-only"
        // Host-shaped label that mismatches the destination, so the tap after
        // the long-press still routes through the confirmation dialog (#1477
        // narrowed the dialog to exactly this spoofable shape).
        val label = "other.example"
        val document =
            paragraphDocument(
                MarkdownInlineFfi.Link(
                    dest = destination,
                    title = null,
                    children = listOf(MarkdownInlineFfi.Text(label)),
                    classification = MarkdownLinkDestinationKindFfi.WEB,
                ),
            )

        val result = longPress(document, visibleText = label)
        assertEquals(LongPressResult(copiedUrl = destination, parentLongPresses = 0), result)
        composeRule.onNodeWithText(string(R.string.link_confirm_title)).assertDoesNotExist()

        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithText(destination).assertIsDisplayed()
    }

    @Test
    fun tappingSpoofableLabeledLinkStillOpensConfirmationDialog() {
        val destination = "https://example.com/tap-target"
        val document =
            paragraphDocument(
                MarkdownInlineFfi.Link(
                    dest = destination,
                    title = null,
                    children = listOf(MarkdownInlineFfi.Text("your-bank.example")),
                    classification = MarkdownLinkDestinationKindFfi.WEB,
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

        composeRule.onNodeWithText("your-bank.example").performClick()
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
        partiallyClipped: Boolean = false,
    ): LongPressResult {
        var copiedUrl: String? = null
        var copyInvocations = 0
        var parentLongPresses = 0
        var clippingVerified = false

        composeRule.setContent {
            val linkLayouts = remember { mutableMapOf<Any, MarkdownLinkTextLayout>() }
            val rowCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
            val clippingModifier =
                if (partiallyClipped) {
                    Modifier.size(width = 240.dp, height = 72.dp).clipToBounds()
                } else {
                    Modifier
                }
            val rowModifier =
                if (partiallyClipped) {
                    Modifier.offset(y = (-24).dp).size(width = 240.dp, height = 96.dp)
                } else {
                    Modifier
                }
            WhiteNoiseTheme {
                Box(clippingModifier) {
                    Box(
                        rowModifier
                            .onGloballyPositioned {
                                rowCoordinates[0] = it
                                if (partiallyClipped) {
                                    clippingVerified =
                                        it.boundsInWindow().top >
                                        it.localToWindow(Offset.Zero).y
                                }
                            }.pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val longPress = awaitLongPressOrCancellation(down.id)
                                    if (longPress != null) {
                                        longPress.consume()
                                        val windowPosition =
                                            rowCoordinates[0]?.let {
                                                messageBubbleLongPressPositionInWindow(it, longPress.position)
                                            }
                                        val link = windowPosition?.let { markdownLinkDestinationAt(linkLayouts.values, it) }
                                        if (link != null) {
                                            copiedUrl = link
                                            copyInvocations++
                                            consumePointerInputUntilReleased(down.id)
                                        } else {
                                            parentLongPresses++
                                        }
                                    }
                                }
                            },
                    ) {
                        Column {
                            if (partiallyClipped) Spacer(Modifier.height(40.dp))
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
            }
        }

        composeRule.onNodeWithText(visibleText).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            up()
        }
        composeRule.waitForIdle()
        if (partiallyClipped) {
            assertTrue("test row must have a clipped top edge", clippingVerified)
        }
        if (copiedUrl != null) {
            assertEquals("link copy must fire exactly once", 1, copyInvocations)
        }

        return LongPressResult(copiedUrl, parentLongPresses)
    }

    private fun string(resId: Int): String {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return context.getString(resId)
    }

    private fun autolinkDocument(url: String) =
        paragraphDocument(
            MarkdownInlineFfi.Autolink(
                url,
                MarkdownAutolinkKindFfi.URI,
                MarkdownLinkDestinationKindFfi.WEB,
            ),
        )

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
