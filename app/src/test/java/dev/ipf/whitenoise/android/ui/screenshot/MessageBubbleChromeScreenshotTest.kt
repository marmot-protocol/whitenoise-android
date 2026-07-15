package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageInlineFooter
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleBorder
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleTimestampColor
import dev.ipf.whitenoise.android.ui.conversation.replies.ReplyPreviewCard
import dev.ipf.whitenoise.android.ui.settings.FontSizePreviewBubble
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baseline for the message-bubble chrome that renders without a live
 * conversation controller: the bubble facsimile used by the font-size
 * preview, the inline time/status footer, and the reply quote card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageBubbleChromeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bubbleChromeLight() {
        render(darkTheme = false)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/message_bubble_chrome_light.png")
    }

    @Test
    fun bubbleChromeDark() {
        render(darkTheme = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/message_bubble_chrome_dark.png")
    }

    @Test
    fun bubbleChromeAmoledDirectionAccents() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Surface(color = Color.Black) {
                    Column(
                        modifier = Modifier.width(360.dp).padding(16.dp).testTag(TAG),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        DirectionalBubble(text = "Incoming message", time = "12:34", mine = false)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            DirectionalBubble(text = "Outgoing message", time = "12:35", mine = true)
                        }
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_bubble_chrome_amoled.png")
    }

    private fun render(darkTheme: Boolean) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface {
                    Column(modifier = Modifier.width(360.dp).padding(8.dp).testTag(TAG)) {
                        FontSizePreviewBubble(text = "Incoming message bubble", mine = false)
                        FontSizePreviewBubble(text = "Outgoing message bubble", mine = true)
                        MessageInlineFooter(
                            timeText = "12:34",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            showStatus = true,
                            status = MessageStatus.Sent,
                            editedLabel = "edited",
                            onEditedClick = null,
                        )
                        MessageInlineFooter(
                            timeText = "12:35",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            showStatus = true,
                            status = MessageStatus.Failed,
                            editedLabel = null,
                            onEditedClick = null,
                        )
                        ReplyPreviewCard(
                            senderTitle = "Alex",
                            isOwn = false,
                            body = "Original quoted message",
                            mediaKind = ReplyMediaKind.None,
                            onClick = null,
                            onDismiss = null,
                        )
                        ReplyPreviewCard(
                            senderTitle = "You",
                            isOwn = true,
                            body = "Photo reply quote",
                            mediaKind = ReplyMediaKind.Photo,
                            onClick = null,
                            onDismiss = {},
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "bubble-chrome"
    }
}

@Composable
private fun DirectionalBubble(
    text: String,
    time: String,
    mine: Boolean,
) {
    Surface(
        color = Color.Black,
        contentColor = Color.White,
        shape = RoundedCornerShape(18.dp),
        border = messageBubbleBorder(highlighted = false, mine = mine),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(text)
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = messageBubbleTimestampColor(invalidated = false, mine = mine, deleted = false),
            )
        }
    }
}
