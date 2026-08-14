package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
    fun connectionFailureKeepsTheConversationVisible() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxWidth().testTag(TAG)) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(220.dp)
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
        }

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/send_connection_failure_dark.png")
    }

    private companion object {
        const val TAG = "send-connection-failure"
    }
}
