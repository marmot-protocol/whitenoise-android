package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R

/**
 * The open message menu's action labelled [label], opening More actions first when the action waits
 * behind it (#4077).
 */
internal fun ComposeTestRule.messageAction(
    label: String,
    substring: Boolean = false,
): SemanticsNodeInteraction {
    waitForIdle()
    if (onAllNodesWithText(label, substring = substring).fetchSemanticsNodes().isEmpty()) {
        val more = ApplicationProvider.getApplicationContext<Context>().getString(R.string.more_message_actions)
        onNodeWithText(more, substring = false).performScrollTo().performClick()
        waitForIdle()
    }
    return onNodeWithText(label, substring = substring)
}
