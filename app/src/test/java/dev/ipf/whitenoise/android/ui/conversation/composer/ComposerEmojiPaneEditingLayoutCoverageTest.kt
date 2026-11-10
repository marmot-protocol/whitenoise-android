package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
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

/**
 * Switching an empty, focused composer from the keyboard to the emoji pane keeps its editing layout.
 * Opening the pane clears editor focus; the composer must not fold to the one-line resting row while
 * the pane rises and unfold again on the way back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerEmojiPaneEditingLayoutCoverageTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun emptyComposerStaysExpandedWhenTheEmojiPaneReplacesTheKeyboard() =
        assertStaysExpanded(fontScale = 1f, direction = LayoutDirection.Ltr, dark = false, name = "light")

    @Test
    fun emptyComposerStaysExpandedAtLargeFontInRtlDark() =
        assertStaysExpanded(fontScale = 2f, direction = LayoutDirection.Rtl, dark = true, name = "large_rtl_dark")

    private fun assertStaysExpanded(
        fontScale: Float,
        direction: LayoutDirection,
        dark: Boolean,
        name: String,
    ) {
        render(fontScale, direction, dark)
        val resting = pillHeight()
        composeRule.onNode(hasSetTextAction()).requestFocus()
        composeRule.waitForIdle()
        val editing = pillHeight()
        assertTrue("the focused empty composer uses its editing layout", editing > resting + 1f)

        composeRule
            .onAllNodesWithContentDescription(context.getString(R.string.open_emoji_picker))
            .onFirst()
            .performClick()
        composeRule.waitForIdle()

        // The emoji action flips to "show keyboard" only while the inline pane owns input.
        composeRule
            .onAllNodesWithContentDescription(context.getString(R.string.show_keyboard))
            .onFirst()
            .assertExists()
        assertEquals("the emoji pane keeps the editing layout", editing, pillHeight(), 1f)
        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/composer_emoji_pane_expanded_$name.png")
    }

    private fun pillHeight(): Float =
        composeRule
            .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG)
            .fetchSemanticsNode()
            .boundsInRoot.height

    private fun render(
        fontScale: Float,
        direction: LayoutDirection,
        dark: Boolean,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface {
                        Box(Modifier.width(360.dp).height(760.dp), contentAlignment = Alignment.BottomCenter) {
                            ComposerBar(
                                appState = null,
                                draftAccountRef = null,
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
        const val ROOT_TAG = "composer-emoji-pane-editing-layout"
    }
}
