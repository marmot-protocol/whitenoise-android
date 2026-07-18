package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleSelectionGutter
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleSelectionRow
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
class MessageBubbleSelectionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectedRowLight() {
        renderSelectionRow(darkTheme = false, amoled = false)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/message_bubble_selection_selected_light.png")
    }

    @Test
    fun selectedRowDark() {
        renderSelectionRow(darkTheme = true, amoled = false)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/message_bubble_selection_selected_dark.png")
    }

    @Test
    fun selectedRowAmoled() {
        renderSelectionRow(darkTheme = true, amoled = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/message_bubble_selection_selected_amoled.png")
    }

    private fun renderSelectionRow(
        darkTheme: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(color = if (amoled) Color.Black else MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.width(360.dp).padding(16.dp).testTag(TAG),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SelectionRowFixture(mine = false, selected = true)
                        SelectionRowFixture(mine = true, selected = true)
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "message-bubble-selection"
    }
}

@androidx.compose.runtime.Composable
private fun SelectionRowFixture(
    mine: Boolean,
    selected: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .messageBubbleSelectionRow(selectionMode = true, selected = selected),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MessageBubbleSelectionGutter(
            batchSelectable = true,
            selected = selected,
        )
        if (mine) Spacer(Modifier.weight(1f))
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(
                text = if (mine) "Outgoing selected message" else "Incoming selected message",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
