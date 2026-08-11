package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.common.InlineErrorBanner
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
class ErrorRecoveryBannerScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadedContentRecoveryLight() = capture("error_recovery_banner_light.png", dark = false)

    @Test
    fun loadedContentRecoveryDark() = capture("error_recovery_banner_dark.png", dark = true)

    @Test
    fun conversationConfirmationLight() {
        captureConversationConfirmation("conversation_confirmation_notice_light.png", dark = false)
    }

    @Test
    fun conversationConfirmationDark() {
        captureConversationConfirmation("conversation_confirmation_notice_dark.png", dark = true)
    }

    private fun capture(
        fileName: String,
        dark: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxWidth().testTag(TAG)) {
                    Box(Modifier.padding(vertical = 12.dp)) {
                        InlineErrorBanner(
                            error =
                                ErrorPresentation(
                                    message =
                                        AppText.Plain(
                                            "Your loaded messages are still available, but they may be out of date.",
                                        ),
                                    report = "operation=CONVERSATION_REFRESH\nerror=CONNECTIVITY",
                                ),
                            onRetry = {},
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$fileName")
    }

    private fun captureConversationConfirmation(
        fileName: String,
        dark: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxWidth().testTag(CONFIRMATION_TAG)) {
                    Column {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text("Alpine group", style = MaterialTheme.typography.titleMedium)
                        }
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Text(
                                "Latest message remains visible",
                                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            ConversationTransientNotice(
                                notice =
                                    TransientNotice(
                                        id = 1L,
                                        title = AppText.Plain("Admin removed"),
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
        }
        composeRule.onNodeWithTag(CONFIRMATION_TAG).captureRoboImage("src/test/snapshots/$fileName")
    }

    private companion object {
        const val TAG = "error-recovery-banner"
        const val CONFIRMATION_TAG = "conversation-confirmation"
    }
}
