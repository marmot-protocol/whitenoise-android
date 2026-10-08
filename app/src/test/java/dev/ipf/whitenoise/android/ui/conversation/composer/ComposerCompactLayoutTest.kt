package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The resting one-line row keeps its placeholder and controls on one vertical center at every font scale. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerCompactLayoutTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** At the default scale the row is exactly one 24 dp line between two 12 dp insets, as tall as its controls. */
    @Test
    fun defaultScaleRowIsAsTallAsItsControls() {
        render(fontScale = 1f, direction = LayoutDirection.Ltr)

        assertRestingRowCentered(LayoutDirection.Ltr)
        assertEquals(24f, placeholder().height, 1f)
        assertEquals(48f, pill().height, 1f)
    }

    /** A modestly enlarged font grows the row from the measured line instead of a fixed height. */
    @Test
    fun enlargedTextGrowsTheRowFromTheMeasuredLine() {
        render(fontScale = 1.3f, direction = LayoutDirection.Ltr)

        assertRestingRowCentered(LayoutDirection.Ltr)
        assertTrue("line grew with the font", placeholder().height > 24f)
    }

    /** At 200% text the row keeps symmetric whitespace and the controls stay centered beside the line. */
    @Test
    fun doubleTextKeepsTheControlsCenteredBesideTheLine() {
        render(fontScale = 2f, direction = LayoutDirection.Ltr)

        assertRestingRowCentered(LayoutDirection.Ltr)
        assertTrue("line grew with the font", placeholder().height > 36f)
    }

    /** The mirrored row at 200% text keeps the same centering with the placeholder clear of the emoji control. */
    @Test
    fun doubleTextMirrorsTheCenteredRowInRtl() {
        render(fontScale = 2f, direction = LayoutDirection.Rtl)

        assertRestingRowCentered(LayoutDirection.Rtl)
        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/composer_compact_resting_large_font_rtl.png")
    }

    /** One 12 dp inset above and below the measured line, controls and placeholder sharing its center. */
    private fun assertRestingRowCentered(direction: LayoutDirection) {
        val pill = pill()
        val placeholder = placeholder()
        val emoji = bounds(R.string.open_emoji_picker)
        val add = bounds(R.string.attach_options)
        val send = bounds(R.string.send)

        assertEquals(maxOf(48f, placeholder.height + 24f), pill.height, 1f)
        assertEquals(placeholder.top - pill.top, pill.bottom - placeholder.bottom, 1f)
        listOf(add, emoji, send).forEach { control ->
            assertEquals(placeholder.center.y, control.center.y, 1f)
            assertTrue("tap target stays 48 dp tall", control.height >= 48f)
        }
        if (direction == LayoutDirection.Ltr) {
            assertTrue("placeholder starts beside the emoji control", placeholder.left >= emoji.right - 1f)
        } else {
            assertTrue("placeholder starts beside the emoji control", placeholder.right <= emoji.left + 1f)
        }
    }

    /** The pill surface bounds in the root. */
    private fun pill(): Rect = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).fetchSemanticsNode().boundsInRoot

    /** The empty placeholder text, not the editable field hosting it. */
    private fun placeholder(): Rect =
        composeRule
            .onNode(hasText(context.getString(R.string.message)) and hasSetTextAction().not())
            .fetchSemanticsNode()
            .boundsInRoot

    /** Bounds of the action carrying the given content description. */
    private fun bounds(label: Int): Rect =
        composeRule
            .onNodeWithContentDescription(context.getString(label))
            .fetchSemanticsNode()
            .boundsInRoot

    /** Composes the empty resting composer at the given font scale and layout direction. */
    private fun render(
        fontScale: Float,
        direction: LayoutDirection,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                WhiteNoiseTheme(darkTheme = false) {
                    Surface {
                        Box(Modifier.width(360.dp).height(600.dp), contentAlignment = Alignment.BottomCenter) {
                            ComposerBar(
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { _, _ -> },
                                onPickDocument = {},
                                textState = ComposerTextState(TextFieldValue()),
                                modifier = Modifier.testTag(ROOT_TAG),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val ROOT_TAG = "composer-compact-layout"
    }
}
