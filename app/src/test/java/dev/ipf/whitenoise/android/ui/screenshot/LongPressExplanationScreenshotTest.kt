package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Holding a composer control explains it, and the + menu ends with Customize composer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class LongPressExplanationScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composerExplanationLight() = captureExplanation(darkTheme = false, amoled = false, name = "light")

    @Test
    fun composerExplanationDark() = captureExplanation(darkTheme = true, amoled = false, name = "dark")

    @Test
    fun composerExplanationAmoled() = captureExplanation(darkTheme = true, amoled = true, name = "amoled")

    @Test
    fun composerExplanationLargeRtl() {
        captureExplanation(darkTheme = false, amoled = false, name = "large_rtl", fontScale = 1.6f, rtl = true)
    }

    @Test
    fun plusMenuCustomizeRowLight() = capturePlusMenu(darkTheme = false, amoled = false, name = "light")

    @Test
    fun plusMenuCustomizeRowAmoled() = capturePlusMenu(darkTheme = true, amoled = true, name = "amoled")

    private fun captureExplanation(
        darkTheme: Boolean,
        amoled: Boolean,
        name: String,
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) {
        render(darkTheme, amoled, fontScale, rtl)
        composeRule.onNodeWithTag("composer.shortcut.camera").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("explanation.sheet").assertIsDisplayed()
        captureScreenRoboImage("src/test/snapshots/long_press_explanation_composer_$name.png")
    }

    private fun capturePlusMenu(
        darkTheme: Boolean,
        amoled: Boolean,
        name: String,
    ) {
        render(darkTheme, amoled)
        composeRule.onNodeWithTag("composer.shortcut.more").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("conversation.attachment.menu.customize").assertIsDisplayed()
        captureScreenRoboImage("src/test/snapshots/long_press_explanation_plus_menu_$name.png")
    }

    private fun render(
        darkTheme: Boolean,
        amoled: Boolean,
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) {
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString(
                "composer_toolbar_v1",
                ComposerToolbarSettings(
                    listOf(ComposerShortcut.More, ComposerShortcut.Emoji, ComposerShortcut.Camera),
                ).encode(),
            ).commit()
        val appState = emptyAppState()
        composeRule.setContent {
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = fontScale) {
                    ExplanationHost {
                        Surface {
                            Box(Modifier.width(360.dp).height(780.dp), contentAlignment = Alignment.BottomCenter) {
                                ComposerBar(
                                    appState = appState,
                                    onCustomizeToolbar = {},
                                    draftAccountRef = null,
                                    replyingTo = null,
                                    messageTextCopy = MessageTextCopy.Default,
                                    onCancelReply = {},
                                    onSend = { _, _ -> },
                                    textState = ComposerTextState(TextFieldValue()),
                                    onCaptureFromCamera = {},
                                    onPickFromGallery = {},
                                    onPickDocument = {},
                                    onShareLocation = {},
                                    onCreatePoll = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
