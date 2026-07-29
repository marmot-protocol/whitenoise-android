package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.OutgoingMessageIndicator
import dev.ipf.whitenoise.android.ui.chats.ChatRowPreviewLine
import dev.ipf.whitenoise.android.ui.chats.ChatRowTrailingContent
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
class ChatRowDeliveryIndicatorScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deliveryIndicatorVariantsKeepPreviewAndTrailingContentReadable() {
        val timestampAt = (System.currentTimeMillis() / 1_000L).toULong()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                DeliveryIndicatorGallery(timestampAt)
            }
        }

        composeRule
            .onNodeWithTag(GALLERY_TAG)
            .captureRoboImage("src/test/snapshots/chat_row_delivery_indicators_light.png")
    }
}

@Composable
private fun DeliveryIndicatorGallery(timestampAt: ULong) {
    Surface {
        Column(Modifier.width(360.dp).testTag(GALLERY_TAG)) {
            PREVIEW_SCENARIOS.forEach { scenario ->
                PreviewScenario(scenario, timestampAt)
            }
        }
    }
}

@Composable
private fun PreviewScenario(
    scenario: PreviewScenarioModel,
    timestampAt: ULong,
) {
    ListItem(
        leadingContent = {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(scenario.title.take(1))
                }
            }
        },
        headlineContent = { Text(scenario.title) },
        supportingContent = {
            if (scenario.bodySearch) {
                Text(scenario.preview, maxLines = 1)
            } else {
                ChatRowPreviewLine(
                    preview = AnnotatedString(scenario.preview),
                    fontStyle = FontStyle.Normal,
                    deliveryIndicator = scenario.indicator,
                )
            }
        },
        trailingContent = {
            ChatRowTrailingContent(
                selectionMode = scenario.selectionMode,
                selected = scenario.selected,
                timestampAt = timestampAt,
                pendingConfirmation = false,
                rowHasUnread = scenario.unreadCount > 0uL,
                rowUnreadCount = scenario.unreadCount,
                unreadMention = scenario.unreadMention,
            )
        },
    )
}

private data class PreviewScenarioModel(
    val title: String,
    val preview: String,
    val indicator: OutgoingMessageIndicator?,
    val unreadCount: ULong = 0uL,
    val unreadMention: Boolean = false,
    val selectionMode: Boolean = false,
    val selected: Boolean = false,
    val bodySearch: Boolean = false,
)

private const val GALLERY_TAG = "chat-row-delivery-gallery"

private val PREVIEW_SCENARIOS =
    listOf(
        PreviewScenarioModel("Delivered / short", "Short message", OutgoingMessageIndicator.Sent),
        PreviewScenarioModel(
            "Delivered / long",
            "A long outgoing preview that must ellipsize before the stable trailing delivery status slot",
            OutgoingMessageIndicator.Sent,
        ),
        PreviewScenarioModel("Pending", "Sending this message", OutgoingMessageIndicator.Sending),
        PreviewScenarioModel("Failed", "Could not send", OutgoingMessageIndicator.Failed),
        PreviewScenarioModel("Incoming", "Incoming preview", null),
        PreviewScenarioModel(
            title = "Unread mention",
            preview = "You were mentioned here",
            indicator = OutgoingMessageIndicator.Sent,
            unreadCount = 3uL,
            unreadMention = true,
        ),
        PreviewScenarioModel(
            title = "Selection mode",
            preview = "Status and selection stay separate",
            indicator = OutgoingMessageIndicator.Sent,
            selectionMode = true,
            selected = true,
        ),
        PreviewScenarioModel(
            title = "Body search result",
            preview = "Matched body snippet",
            indicator = null,
            bodySearch = true,
        ),
    )
