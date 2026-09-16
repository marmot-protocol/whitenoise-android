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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
