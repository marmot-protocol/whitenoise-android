package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_PILL_SURFACE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_RESIZE_GESTURE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pixel baselines for short and wrapping message edits. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ComposerEditScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composerShortEditLight() {
        render(darkTheme = false, width = 360, fontScale = 1f, rtl = false, editText = "Short edit")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val editor = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val send =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.send))
                .fetchSemanticsNode()
                .boundsInRoot
        val emoji =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.open_emoji_picker))
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(
            "Edit text should stay above its separate control row",
            editor.bottom <= send.top && editor.bottom <= emoji.top,
        )
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_short_edit_light.png")
    }

    @Test
    fun composerWrappingEditLargeRtl() {
        render(
            darkTheme = true,
            width = 300,
            fontScale = 1.6f,
            rtl = true,
            editText = "A longer edit that wraps across several lines while the controls remain available.",
        )
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_wrapping_edit_large_rtl.png")
    }

    @Test
    fun editingAccessoryIsInsideTheSurfaceAndStillCancels() {
        var cancelled = 0
        render(
            darkTheme = true,
            width = 320,
            fontScale = 2f,
            rtl = true,
            editText = "Message being edited",
            onCancelEdit = { cancelled += 1 },
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val surface = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).fetchSemanticsNode().boundsInRoot
        val cancel =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.cancel_edit))
                .fetchSemanticsNode()
                .boundsInRoot
        val editor = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val border =
            composeRule
                .onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(cancel.left >= surface.left && cancel.right <= surface.right)
        assertTrue(cancel.top >= border.bottom)
        // Only four dp of the field's empty leading overlaps the Cancel target.
        assertTrue(cancel.bottom <= editor.top + with(composeRule.density) { 4.dp.toPx() })
        assertTrue(editor.bottom <= surface.bottom)
        composeRule.onNodeWithContentDescription(context.getString(R.string.cancel_edit)).performClick()
        assertEquals(1, cancelled)
    }

    private fun render(
        darkTheme: Boolean,
        width: Int,
        fontScale: Float,
        rtl: Boolean,
        editText: String,
        onCancelEdit: () -> Unit = {},
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.width(width.dp).testTag(TAG)) {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onCancelEdit = onCancelEdit,
                            onSend = { _, _ -> },
                            initialDraft = TextFieldValue(""),
                            editingMessageId = "edited-message",
                            editingInitialText = editText,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "composer-edit-screenshot"
    }
}
