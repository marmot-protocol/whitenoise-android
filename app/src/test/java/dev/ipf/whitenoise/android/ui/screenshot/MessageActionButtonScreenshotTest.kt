package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageActionButton
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression guard for issue #1385. Message-action rows must use neutral
 * on-surface coloring for ordinary actions and error coloring for destructive
 * delete actions — not the brand primary/cyan tint Material3 applies to
 * TextButton by default.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageActionButtonScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun messageActionButtonsLight() {
        render(darkTheme = false, amoled = false)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/message_action_buttons_light.png")
    }

    @Test
    fun messageActionButtonsDark() {
        render(darkTheme = true, amoled = false)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/message_action_buttons_dark.png")
    }

    @Test
    fun messageActionButtonsAmoled() {
        render(darkTheme = true, amoled = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/message_action_buttons_amoled.png")
    }

    private fun render(
        darkTheme: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(
                    modifier = Modifier.width(328.dp).testTag(TAG),
                    shape = RoundedCornerShape(12.dp),
                    border = amoledSurfaceBorderStroke(),
                ) {
                    Column {
                        MessageActionButton(
                            label = "Reply",
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.Reply,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onClick = {},
                        )
                        MessageActionButton(
                            label = "Copy text",
                            icon = {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onClick = {},
                        )
                        MessageActionButton(
                            label = "Delete for me",
                            icon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onClick = {},
                            isDestructive = true,
                        )
                        MessageActionButton(
                            label = "Delete for everyone",
                            icon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onClick = {},
                            isDestructive = true,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "message-action-buttons"
    }
}
