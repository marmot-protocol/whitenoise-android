package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.MentionBadge
import dev.ipf.whitenoise.android.ui.UnreadCountBadge
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baseline for the chat-row badges (the row itself needs live app
 * state, so its pure leaves are pinned instead).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListBadgesScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chatListBadgesLight() {
        render(darkTheme = false)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/chat_list_badges_light.png")
    }

    @Test
    fun chatListBadgesDark() {
        render(darkTheme = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/chat_list_badges_dark.png")
    }

    private fun render(darkTheme: Boolean) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface {
                    Row(
                        modifier = Modifier.padding(8.dp).testTag(TAG),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MentionBadge()
                        UnreadCountBadge(unreadCount = 3u)
                        UnreadCountBadge(unreadCount = 128u)
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "chat-list-badges"
    }
}
