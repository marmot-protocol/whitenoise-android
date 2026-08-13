package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.ShellTransientNoticeLayout
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
class MediaSaveConfirmationScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mediaSaveConfirmationGlobal() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                ShellTransientNoticeLayout(
                    notice = TransientNotice(id = 1L, title = AppText.Plain("Media saved")),
                    modifier = Modifier.testTag(GLOBAL_TAG),
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize()) {
                            Text(
                                "Media library",
                                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag(GLOBAL_TAG).captureRoboImage("src/test/snapshots/media_save_confirmation_global.png")
    }

    @Test
    fun mediaSaveConfirmationConversation() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxWidth().testTag(CONVERSATION_TAG)) {
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
                                        title = AppText.Plain("Media saved"),
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

        composeRule
            .onNodeWithTag(CONVERSATION_TAG)
            .captureRoboImage("src/test/snapshots/media_save_confirmation_conversation.png")
    }

    private companion object {
        const val GLOBAL_TAG = "media-save-global-confirmation"
        const val CONVERSATION_TAG = "media-save-conversation-confirmation"
    }
}
