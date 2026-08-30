package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageQuickActions
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
class NewMessageQuickActionsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun light() = capture("new_message_quick_actions_light.png", dark = false, width = 360, fontScale = 1f)

    @Test
    fun dark() = capture("new_message_quick_actions_dark.png", dark = true, width = 360, fontScale = 1f)

    @Test
    fun largeFont() = capture("new_message_quick_actions_large.png", dark = false, width = 360, fontScale = 2f)

    @Test
    fun narrowWidth() = capture("new_message_quick_actions_narrow.png", dark = false, width = 240, fontScale = 1f)

    private fun capture(
        fileName: String,
        dark: Boolean,
        width: Int,
        fontScale: Float,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface {
                        Column(Modifier.width(width.dp).testTag(TAG)) {
                            NewMessageQuickActions(
                                query = "",
                                showMyQrLabel = context.getString(R.string.show_my_qr_code),
                                showMyQrEnabled = true,
                                onNewGroup = {},
                                onScanQr = {},
                                onShowMyQr = {},
                                onInviteFriends = {},
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$fileName")
    }

    private companion object {
        const val TAG = "new-message-quick-actions"
    }
}
