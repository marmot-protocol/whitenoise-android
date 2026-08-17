package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.messages.FORWARD_CHAT_PICKER_SCREEN_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.ForwardMessagePickerContent
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_HEX
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_REF
import dev.ipf.whitenoise.android.ui.share.appStateWithDirectChats
import dev.ipf.whitenoise.android.ui.share.profile
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ForwardMessagePickerScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun multiMessageMediaPickerDark() {
        val chats = (0 until 8).map { index -> hexId(0x20 + index) to hexId(0x40 + index) }
        val profiles =
            chats
                .mapIndexed { index, (_, peerId) -> peerId to profile("Person ${index + 1}") }
                .toMap(mutableMapOf())
        val appState =
            appStateWithDirectChats(
                *chats.toTypedArray(),
                profiles = profiles,
                accounts = listOf(testAccount(ACCOUNT_REF, ACCOUNT_HEX)),
            )

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface {
                    ForwardMessagePickerContent(
                        appState = appState,
                        messageCount = 11,
                        attachmentCount = 11,
                        originGroupIdHex = "ff".repeat(32),
                        onDismiss = {},
                        onForward = { true },
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(FORWARD_CHAT_PICKER_SCREEN_TEST_TAG)
            .captureRoboImage("src/test/snapshots/forward_message_picker_dark.png")
    }

    private fun hexId(byte: Int): String = byte.toString(16).padStart(2, '0').repeat(32)
}
