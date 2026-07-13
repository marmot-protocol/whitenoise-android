package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatRowSelectionClickTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectionRowTapTogglesButLongPressIsNoOp() {
        var clicks = 0
        composeRule.setContent {
            Box(
                Modifier
                    .testTag("selection-row")
                    .chatListSelectionRowClickable { clicks++ },
            )
        }

        composeRule.onNodeWithTag("selection-row").performClick()
        assertEquals(1, clicks)

        composeRule.onNodeWithTag("selection-row").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            up()
        }
        assertEquals(1, clicks)
    }
}
