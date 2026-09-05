package dev.ipf.whitenoise.android.ui.conversation.share

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val USER_SHARE_CARD_TAG = "user-share-card"
private const val USER_SHARE_CARD_SNAPSHOT = "src/test/snapshots/user_share_card_bare_reference_light.png"

/** Visual regression for the unambiguous bare-reference user-share card. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class UserMessageBubbleScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bareReferenceShareUsesTheShortenedNpubHeader() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.padding(16.dp)) {
                    UserMessageBubble(
                        user = SharedUser(npub = TEST_NPUB, name = null),
                        onOpen = {},
                        modifier = Modifier.testTag(USER_SHARE_CARD_TAG),
                    )
                }
            }
        }

        composeRule.onNodeWithTag(USER_SHARE_CARD_TAG).captureRoboImage(USER_SHARE_CARD_SNAPSHOT)
    }
}

private const val TEST_NPUB =
    "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
