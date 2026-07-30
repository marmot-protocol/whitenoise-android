package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatRowUnreadBadgeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val markedUnreadDescription by lazy { context.getString(R.string.chat_row_marked_unread) }

    private fun unreadMessagesDescription(count: Int): String =
        context.resources.getQuantityString(
            R.plurals.unread_messages_count,
            count,
            count,
        )

    @Test
    fun manualUnreadWithZeroCount_showsDotWithoutNumericText() {
        render(rowHasUnread = true, rowUnreadCount = 0uL)

        composeRule.onNodeWithText("0").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(markedUnreadDescription).assertExists()
    }

    @Test
    fun positiveUnreadCount_showsNumericBadgeAndTalkBack() {
        render(rowHasUnread = true, rowUnreadCount = 3uL)

        composeRule.onNodeWithText("3").assertExists()
        composeRule.onNodeWithContentDescription(unreadMessagesDescription(3)).assertExists()
        composeRule.onNodeWithContentDescription(markedUnreadDescription).assertDoesNotExist()
    }

    @Test
    fun unreadCountAboveNinetyNine_capsDisplayAndKeepsFullCountTalkBack() {
        render(rowHasUnread = true, rowUnreadCount = 128uL)

        composeRule.onNodeWithText("99+").assertExists()
        composeRule.onNodeWithContentDescription(unreadMessagesDescription(128)).assertExists()
    }

    private fun render(
        rowHasUnread: Boolean,
        rowUnreadCount: ULong,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    Row {
                        ChatRowSupportingMetadata(
                            pendingConfirmation = false,
                            rowHasUnread = rowHasUnread,
                            rowUnreadCount = rowUnreadCount,
                            unreadMention = false,
                            actionColors = null,
                            pinned = false,
                        )
                    }
                }
            }
        }
    }
}
