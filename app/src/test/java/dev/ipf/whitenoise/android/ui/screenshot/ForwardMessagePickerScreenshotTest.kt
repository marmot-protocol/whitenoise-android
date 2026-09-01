package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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
        renderPicker(
            fontScale = 1f,
            layoutDirection = LayoutDirection.Ltr,
            snapshotPath = "src/test/snapshots/forward_message_picker_dark.png",
        )
    }

    @Test
    fun multiMessageMediaPickerLargeFont() {
        renderPicker(
            fontScale = 1.6f,
            layoutDirection = LayoutDirection.Ltr,
            snapshotPath = "src/test/snapshots/forward_message_picker_large_font.png",
        )
    }

    @Test
    fun multiMessageMediaPickerRtl() {
        renderPicker(
            fontScale = 1f,
            layoutDirection = LayoutDirection.Rtl,
            snapshotPath = "src/test/snapshots/forward_message_picker_rtl.png",
        )
    }

    private fun renderPicker(
        fontScale: Float,
        layoutDirection: LayoutDirection,
        snapshotPath: String,
    ) {
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
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = true) {
                    Surface {
                        ForwardMessagePickerContent(
                            appState = appState,
                            messageCount = 11,
                            attachmentCount = 11,
                            originGroupIdHex = "ff".repeat(32),
                            sourceAccountRef = ACCOUNT_REF,
                            onDismiss = {},
                            onForward = { _, _ -> true },
                        )
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(FORWARD_CHAT_PICKER_SCREEN_TEST_TAG)
            .captureRoboImage(snapshotPath)
    }

    private fun hexId(byte: Int): String = byte.toString(16).padStart(2, '0').repeat(32)
}
