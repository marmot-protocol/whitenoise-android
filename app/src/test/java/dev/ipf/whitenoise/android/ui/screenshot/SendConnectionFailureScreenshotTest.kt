package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.conversation.ConversationTransientNotice
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
class SendConnectionFailureScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun connectionFailureDark() {
        render(darkTheme = true)
        capture("send_connection_failure_dark")
    }

    @Test
    fun connectionFailureLight() {
        render(darkTheme = false)
        capture("send_connection_failure_light")
    }

    @Test
    fun connectionFailureRtl() {
        render(darkTheme = false, layoutDirection = LayoutDirection.Rtl)
        capture("send_connection_failure_rtl")
    }

    @Test
    fun connectionFailureLargeFont() {
        render(darkTheme = true, fontScale = 2f)
        capture("send_connection_failure_large_font")
    }

    private fun render(
        darkTheme: Boolean,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides layoutDirection,
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    FailureConversation(height = if (fontScale > 1f) 360.dp else 220.dp)
                }
            }
        }
    }

    @Composable
    private fun FailureConversation(height: Dp) {
        Surface(modifier = Modifier.fillMaxWidth().testTag(TAG)) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(height)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    "Message remains available to retry",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ConversationTransientNotice(
                    notice =
                        TransientNotice(
                            id = 1L,
                            title = AppText.Resource(R.string.toast_send_connection_failed),
                            conversation = ConversationNoticeDestination("account-a", "group-a"),
                        ),
                    accountRef = "account-a",
                    groupIdHex = "group-a",
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }

    private fun capture(name: String) = composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$name.png")

    private companion object {
        const val TAG = "send-connection-failure"
    }
}
