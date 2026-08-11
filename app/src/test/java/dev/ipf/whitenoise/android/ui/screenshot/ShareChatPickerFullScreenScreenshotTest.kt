package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.ui.share.SHARE_CHAT_PICKER_SCREEN_TEST_TAG
import dev.ipf.whitenoise.android.ui.share.ShareChatPickerFullScreenContent
import dev.ipf.whitenoise.android.ui.share.appStateWithDirectChats
import dev.ipf.whitenoise.android.ui.share.profile
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Baseline for the dedicated inbound system-share recipient destination. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ShareChatPickerFullScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun populatedPickerDark() {
        val chats =
            (0 until 10).map { index ->
                hexId(0x20 + index) to hexId(0x40 + index)
            }
        val profiles =
            chats
                .mapIndexed { index, (_, peerId) ->
                    peerId to profile(displayName = "Person ${index + 1}")
                }.toMap(mutableMapOf())
        val appState = appStateWithDirectChats(*chats.toTypedArray(), profiles = profiles)

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface {
                    ShareChatPickerFullScreenContent(
                        appState = appState,
                        requestId = "screenshot-request",
                        payload =
                            SharePayload(
                                text = "Planning notes for tomorrow",
                                streamUris = emptyList(),
                                intentMimeType = "text/plain",
                            ),
                        onDismiss = {},
                        onStage = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(SHARE_CHAT_PICKER_SCREEN_TEST_TAG)
            .captureRoboImage("src/test/snapshots/share_chat_picker_full_screen_dark.png")
    }

    private fun hexId(byte: Int): String = byte.toString(16).padStart(2, '0').repeat(32)
}
