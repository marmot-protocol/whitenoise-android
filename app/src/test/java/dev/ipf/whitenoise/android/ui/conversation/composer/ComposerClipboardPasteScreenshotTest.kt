package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Keyboard-free Paste occupies the original trailing action slot before expanding to Send. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerClipboardPasteScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Light idle and multiline states. */
    @Test fun light() = capture("composer_clipboard_light")

    /** Dark idle and multiline states. */
    @Test fun dark() = capture("composer_clipboard_dark", dark = true)

    /** RTL large text keeps both actions reachable. */
    @Test
    @Config(sdk = [36], qualifiers = "ar-ldrtl-w360dp-h780dp-mdpi")
    fun rtlLarge() = capture("composer_clipboard_rtl_large", dark = true, rtl = true, large = true)

    /** Uses a synthetic clipboard payload and captures before and after the explicit tap. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        rtl: Boolean = false,
        large: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("test", "first line\nsecond line\nthird line"),
        )
        val recorder =
            VoiceRecordingController(
                context = context,
                outputDirectory = context.cacheDir,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                onPermissionRequest = { true },
                onRecordingComplete = { _, _ -> },
                onError = {},
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, fontScale = if (large) 2f else 1f) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            voiceRecordingController = recorder,
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/${name}_paste.png")
        composeRule.onNodeWithContentDescription("Paste").performClick()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/${name}_send.png")
    }
}
