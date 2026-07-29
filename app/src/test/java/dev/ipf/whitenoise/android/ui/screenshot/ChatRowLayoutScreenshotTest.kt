package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.chats.ChatRowLayout
import dev.ipf.whitenoise.android.ui.chats.ChatRowSupportingMetadata
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
class ChatRowLayoutScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chatRowStatesLight() {
        render(darkTheme = false, amoled = false)
        capture("chat_row_layout_states_light.png")
    }

    @Test
    fun chatRowStatesDark() {
        render(darkTheme = true, amoled = false)
        capture("chat_row_layout_states_dark.png")
    }

    @Test
    fun chatRowStatesAmoled() {
        render(darkTheme = true, amoled = true)
        capture("chat_row_layout_states_amoled.png")
    }

    private fun render(
        darkTheme: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag(TAG),
                    ) {
                        ChatRowFixture(
                            title = "Ordinary conversation with a long title",
                            preview = "Preview reaches the trailing edge beneath the timestamp",
                        )
                        ChatRowFixture(
                            title = "Unread conversation",
                            preview = "Unread preview reserves only the badge width",
                            unread = true,
                        )
                        ChatRowFixture(
                            title = "Invited group",
                            preview = "Invitation",
                            invited = true,
                        )
                        ChatRowFixture(
                            title = "Search result",
                            preview = "Matched message snippet from the conversation",
                        )
                        ChatRowFixture(
                            title = "Draft conversation",
                            preview = "Draft: unfinished message",
                            draft = true,
                        )
                        ChatRowFixture(
                            title = "Selected conversation",
                            preview = "Normal metadata is replaced",
                            selectionMode = true,
                            selected = true,
                        )
                        ChatRowFixture(
                            title = "Unselected conversation",
                            preview = "Normal metadata is replaced",
                            selectionMode = true,
                        )
                    }
                }
            }
        }
    }

    private fun capture(fileName: String) {
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/$fileName")
    }

    private companion object {
        const val TAG = "chat-row-layout-states"
    }
}

@androidx.compose.runtime.Composable
private fun ChatRowFixture(
    title: String,
    preview: String,
    unread: Boolean = false,
    invited: Boolean = false,
    draft: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    val timestampAt = remember { (System.currentTimeMillis() / 1_000L).toULong() }
    Box {
        ChatRowLayout(
            title = title,
            timestampAt = timestampAt,
            rowHasUnread = unread,
            selectionMode = selectionMode,
            selected = selected,
            leadingContent = {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                )
            },
            supportingContent = {
                Text(
                    text = preview,
                    maxLines = 1,
                    fontStyle = if (draft) FontStyle.Italic else FontStyle.Normal,
                )
            },
            supportingMetadata =
                if (invited || unread) {
                    {
                        ChatRowSupportingMetadata(
                            pendingConfirmation = invited,
                            rowHasUnread = unread,
                            rowUnreadCount = 3uL,
                            unreadMention = unread,
                            actionColors = null,
                            pinned = false,
                        )
                    }
                } else {
                    null
                },
            modifier = Modifier.fillMaxWidth(),
        )
        if (selected) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            )
        }
    }
}
