package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baselines for the two compact-viewport composer contracts: a
 * landscape-with-IME-sized remainder keeps the editor and every primary action
 * viable, and a manually shrunk long draft shows the position-tracking
 * overflow affordance on the editor's trailing edge.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w780dp-h360dp-land-mdpi")
class ComposerCompactHeightScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun compactHeightComposerLight() {
        renderCompact(darkTheme = false)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_compact_height_light.png")
    }

    @Test
    fun compactHeightComposerDark() {
        renderCompact(darkTheme = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_compact_height_dark.png")
    }

    @Test
    fun compactHeightComposerAmoledRtl() {
        renderCompact(darkTheme = true, amoled = true, rtl = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_compact_height_amoled_rtl.png")
    }

    @Test
    fun shrunkOverflowAffordanceLight() {
        renderShrunkOverflow(darkTheme = false)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_shrunk_overflow_light.png")
    }

    @Test
    fun shrunkOverflowAffordanceDark() {
        renderShrunkOverflow(darkTheme = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_shrunk_overflow_dark.png")
    }

    /** A landscape-with-IME remainder: the composer viewport is the compact viable allowance. */
    private fun renderCompact(
        darkTheme: Boolean,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        val draft = (1..8).joinToString("\n") { "Compact landscape line $it" }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Surface(modifier = Modifier.width(720.dp).height(150.dp)) {
                        Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxSize()) {
                            ComposerBar(
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { _, _ -> },
                                onPickFromGallery = {},
                                onPickDocument = {},
                                initialDraft = TextFieldValue(draft, TextRange(draft.length)),
                                modifier = Modifier.testTag(TAG),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** A long draft manually shortened with the resize handle, so the affordance must appear. */
    private fun renderShrunkOverflow(darkTheme: Boolean) {
        val draft = (1..40).joinToString("\n") { "Draft line $it" }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.width(360.dp).height(340.dp)) {
                    Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxSize()) {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            onPickFromGallery = {},
                            onPickDocument = {},
                            initialDraft = TextFieldValue(draft, TextRange(draft.length)),
                            modifier = Modifier.testTag(TAG),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithContentDescription(app.getString(R.string.composer_resize))
            .performTouchInput {
                swipe(center, Offset(center.x, center.y + 120f), durationMillis = 320)
            }
        composeRule.waitForIdle()
    }

    private companion object {
        const val TAG = "compact-composer"
    }
}
