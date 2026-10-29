@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.WCAG_NON_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val ROOT_TAG = "footer.status.matrix"
private const val OPAQUE_ARGB_MASK = 0xFFFFFFFFL

/**
 * The bubble footer's delivery states. A settled Sent disc must read as metadata beside the
 * timestamp, while Sending and a failure stay as legible as before and keep their own glyphs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MessageInlineFooterScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light theme: every delivery state on an own bubble, another sender's bubble and media. */
    @Test
    fun footerStatusMatrixLight() {
        renderMatrix(darkTheme = false, amoled = false)
        capture("message_footer_status_matrix_light")
    }

    /** Dark theme keeps the same weights against the darker fills. */
    @Test
    fun footerStatusMatrixDark() {
        renderMatrix(darkTheme = true, amoled = false)
        capture("message_footer_status_matrix_dark")
    }

    /** AMOLED draws the footer in its directional accent, so the softened disc is checked there too. */
    @Test
    fun footerStatusMatrixAmoled() {
        renderMatrix(darkTheme = true, amoled = true)
        capture("message_footer_status_matrix_amoled")
    }

    /** The settled disc is lighter than the footer colour yet clears the non-text contrast floor. */
    @Test
    fun sentDiscRecedesAsFarAsTheContrastFloorAllows() {
        val samples = mutableListOf<Triple<String, Color, Color>>()

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                collectFooterSample("light-own", mine = true, samples)
            }
            WhiteNoiseTheme(darkTheme = true) {
                collectFooterSample("dark-own", mine = true, samples)
            }
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                collectFooterSample("amoled-own", mine = true, samples)
            }
        }

        composeRule.runOnIdle {
            assertTrue("no footer samples were collected", samples.isNotEmpty())
            samples.forEach { (name, fill, footer) ->
                val disc = sentDeliveryDiscColor(footer, fill)
                assertTrue("$name: the disc must be lighter than the footer text", disc.alpha < footer.alpha)
                val composited = disc.compositeOver(fill)
                val ratio = contrastRatio(composited.opaqueArgb(), fill.opaqueArgb())
                assertTrue("$name: disc contrast $ratio is below the non-text floor", ratio >= WCAG_NON_TEXT_CONTRAST)
            }
        }
    }

    /** On a fill with little room the disc stops receding early instead of dropping under the floor. */
    @Test
    fun sentDiscStopsRecedingBeforeItBecomesUnreadable() {
        val tightFill = Color(0xFFE9E9E9)
        val footer = Color(0xFF666666)
        val disc = sentDeliveryDiscColor(footer, tightFill)
        val ratio = contrastRatio(disc.compositeOver(tightFill).opaqueArgb(), tightFill.opaqueArgb())
        assertTrue("a tight fill must back off past the target alpha", disc.alpha > footer.alpha * 0.6f)
        assertTrue("disc contrast $ratio is below the non-text floor", ratio >= WCAG_NON_TEXT_CONTRAST)
    }

    /** The media scrim chip keeps its white-on-black footer readable at the softened alpha. */
    @Test
    fun sentDiscStaysReadableOnTheMediaScrim() {
        val disc = sentDeliveryDiscColor(Color.White, Color.Black)
        val ratio = contrastRatio(disc.compositeOver(Color.Black).opaqueArgb(), Color.Black.opaqueArgb())
        assertTrue("media chip disc contrast $ratio is below the non-text floor", ratio >= WCAG_NON_TEXT_CONTRAST)
    }

    /** Each delivery state keeps a description, so the state never rests on colour alone. */
    @Test
    fun statusKeepsNonColourSemantics() {
        renderMatrix(darkTheme = false, amoled = false)
        listOf("Sending", "Sent", "Send failed").forEach { description ->
            val nodes = composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes()
            assertTrue("no node described \"$description\"", nodes.isNotEmpty())
        }
    }

    /** A focused preview shares the same logical footer geometry as its source message. */
    @Test
    fun focusedLatinPreviewInRtlDoesNotOverlapItsFooter() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WhiteNoiseTheme(darkTheme = false) {
                    Box(Modifier.width(220.dp).testTag(ROOT_TAG)) {
                        FocusedTextMessagePreview(
                            presentation = messageBubblePresentation(deleted = false, mine = true),
                            mine = true,
                            text = "A Latin preview with several wrapped lines of ordinary text.\nHi",
                            document = null,
                            time = "12:45",
                            status = MessageStatus.Sent,
                            showStatus = true,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        capture("message_footer_focused_latin_rtl")
        val bodyNode = composeRule.onNodeWithTag("message-actions-excerpt")
        val text = bodyNode.fetchSemanticsNode().boundsInRoot
        val results = mutableListOf<TextLayoutResult>()
        bodyNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        val time = composeRule.onNodeWithText("12:45", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val status = composeRule.onNodeWithContentDescription("Sent", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val footer = Rect(minOf(time.left, status.left), minOf(time.top, status.top), maxOf(time.right, status.right), maxOf(time.bottom, status.bottom))
        val lines =
            (0 until layout.lineCount).map { line ->
                Rect(
                    text.left + layout.getLineLeft(line),
                    text.top + layout.getLineTop(line),
                    text.left + layout.getLineRight(line),
                    text.top + layout.getLineBottom(line),
                )
            }
        println("RTL focused footer: text=$text, footer=$footer, lines=$lines")
        assertTrue("preview must actually wrap", layout.lineCount > 1)
        assertTrue("footer $footer must not intersect rendered preview lines $lines", lines.none { it.overlaps(footer) })
    }

    /** The warning follows the terminal timestamp baseline, not a preceding metadata label. */
    @Test
    fun warningAlignsWithTheTerminalLineOfMultilineMetadata() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.width(180.dp).testTag(ROOT_TAG)) {
                    MessageBubbleMetadataFooter(warning = "Note", color = Color.Black) {
                        Column {
                            Text("Edited", style = MaterialTheme.typography.labelSmall)
                            Text("12:45", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val warning = composeRule.onNodeWithText("Note").fetchSemanticsNode().boundsInRoot
        val edited = composeRule.onNodeWithText("Edited").fetchSemanticsNode().boundsInRoot
        val time = composeRule.onNodeWithText("12:45").fetchSemanticsNode().boundsInRoot
        assertTrue("warning must align to time rather than edited label", kotlin.math.abs(warning.bottom - time.bottom) <= 1f)
        assertTrue("warning must follow the preceding metadata row", warning.top >= edited.bottom)
        capture("message_footer_warning_multiline_metadata")
    }

    /** Long translated copy and edited metadata wrap without covering the terminal timestamp. */
    @Test
    fun longWarningAndEditedLabelStayBoundedAtLargeRtlFonts() {
        val warning = "Die Zustellung dieser Nachricht konnte noch nicht bestätigt werden"
        val edited = "Nachträglich bearbeitet"
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 1.6f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                WhiteNoiseTheme(darkTheme = true, amoled = true) {
                    Surface(modifier = Modifier.width(180.dp).testTag(ROOT_TAG)) {
                        Box(Modifier.padding(8.dp)) {
                            MessageBubbleMetadataFooter(warning = warning, color = Color.White) {
                                MessageInlineFooter(
                                    timeText = "12:45",
                                    color = Color.White,
                                    showStatus = false,
                                    status = MessageStatus.Pending,
                                    editedLabel = edited,
                                    onEditedClick = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val root = composeRule.onNodeWithTag(ROOT_TAG).fetchSemanticsNode().boundsInRoot
        val time = composeRule.onNodeWithText("12:45", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        listOf(warning, edited).forEach { text ->
            val bounds = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$text must stay inside the footer", bounds.left >= root.left && bounds.right <= root.right)
            assertTrue("$text must not extend below the timestamp", bounds.bottom <= time.bottom + 1f)
            assertTrue(
                "$text must not intersect the timestamp",
                bounds.bottom <= time.top || bounds.right <= time.left || bounds.left >= time.right,
            )
        }
        assertTrue("RTL timestamp must remain at the trailing edge", time.left <= root.left + 9f)
        capture("message_footer_long_warning_large_rtl")
    }

    /** Captures the footer colour and its bubble fill for one theme and bubble direction. */
    @Composable
    private fun collectFooterSample(
        name: String,
        mine: Boolean,
        into: MutableList<Triple<String, Color, Color>>,
    ) {
        val fill = bubbleFill(mine)
        val footer = footerColor(mine)
        SideEffect { into += Triple(name, fill, footer) }
    }

    /** Mounts one footer row per delivery state on both bubble directions plus the media chip. */
    private fun renderMatrix(
        darkTheme: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(modifier = Modifier.width(320.dp).testTag(ROOT_TAG)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FooterStates(mine = true)
                        FooterStates(mine = false)
                        MediaFooterChip()
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** One bubble direction: the pending ring, the settled disc and the failure glyph in a row each. */
    @Composable
    private fun FooterStates(mine: Boolean) {
        val fill = bubbleFill(mine)
        val footer = footerColor(mine)
        Surface(color = fill, shape = MaterialTheme.shapes.large) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(MessageStatus.Pending, MessageStatus.Sent, MessageStatus.Failed).forEach { status ->
                    MessageInlineFooter(
                        timeText = "12:45",
                        color = footer,
                        showStatus = mine,
                        status = status,
                        editedLabel = null,
                        onEditedClick = null,
                        // Production passes the bubble's own fill, which both punches the check out of
                        // the disc and is the surface the disc's contrast is judged against.
                        statusContainerColor = fill,
                    )
                }
            }
        }
    }

    /** The overlay footer a visual-media bubble paints on its own scrim. */
    @Composable
    private fun MediaFooterChip() {
        Box(modifier = Modifier.size(width = 200.dp, height = 60.dp)) {
            MediaFooterOverlay(timeText = "12:45", showStatus = true, status = MessageStatus.Sent)
        }
    }

    /** The fill the production bubble paints behind its footer for this direction. */
    @Composable
    private fun bubbleFill(mine: Boolean): Color = messageBubbleFillColor(deleted = false, mine = mine)

    /** The footer colour production pairs with that fill. */
    @Composable
    private fun footerColor(mine: Boolean): Color = messageBubbleTimestampColor(mine = mine, deleted = false)

    /** Captures the mounted matrix as the named baseline. */
    private fun capture(name: String) {
        composeRule.onNodeWithTag(ROOT_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }
}

/** Opaque ARGB value of a colour, for the shared contrast helper. */
private fun Color.opaqueArgb(): Long = toArgb().toLong() and OPAQUE_ARGB_MASK
