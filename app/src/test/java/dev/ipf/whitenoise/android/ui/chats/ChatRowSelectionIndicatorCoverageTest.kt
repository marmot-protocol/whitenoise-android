package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatRowSelectionIndicatorCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val nowText by lazy { context.getString(R.string.relative_time_now) }
    private val invitedText by lazy { context.getString(R.string.invited) }
    private val mentionDescription by lazy { context.getString(R.string.chat_list_mention_badge) }
    private val selectDescription by lazy { context.getString(R.string.select) }
    private val selectedDescription by lazy { context.getString(R.string.selected) }

    @Test
    fun selectionIndicatorUsesOutlinedAndFilledIcons() {
        assertSame(Icons.Default.RadioButtonUnchecked, chatRowSelectionIcon(selected = false))
        assertSame(Icons.Default.CheckCircle, chatRowSelectionIcon(selected = true))
    }

    @Test
    fun selectedAndUnselectedRowsKeepMetadataReplacedWithoutDuplicateAnnouncements() {
        val selectionMode = mutableStateOf(true)
        val selected = mutableStateOf(false)
        render(selectionMode, selected, unreadCount = 3uL)

        composeRule.onNode(hasClickAction()).assertIsNotSelected()
        assertSelectionMetadataHidden(unreadCount = 3uL)

        selected.value = true
        composeRule.waitForIdle()

        composeRule.onNode(hasClickAction()).assertIsSelected()
        assertSelectionMetadataHidden(unreadCount = 3uL)

        selected.value = false
        composeRule.waitForIdle()

        composeRule.onNode(hasClickAction()).assertIsNotSelected()
        assertSelectionMetadataHidden(unreadCount = 3uL)
    }

    @Test
    fun leavingSelectionModeRestoresUnreadTimestampAndBadge() {
        val selectionMode = mutableStateOf(true)
        val selected = mutableStateOf(false)
        render(selectionMode, selected, unreadCount = 3uL, unreadMention = true)

        assertSelectionMetadataHidden(unreadCount = 3uL)

        selectionMode.value = false
        composeRule.waitForIdle()

        composeRule.onNodeWithText(nowText).assertExists()
        composeRule.onNodeWithText("3").assertExists()
        composeRule.onNodeWithContentDescription(mentionDescription).assertExists()
    }

    @Test
    fun selectionModeReplacesInvitedTimestampAndBadge() {
        val selectionMode = mutableStateOf(false)
        val selected = mutableStateOf(false)
        render(selectionMode, selected, pendingConfirmation = true)

        composeRule.onNodeWithText(nowText).assertExists()
        composeRule.onNodeWithText(invitedText).assertExists()

        selectionMode.value = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText(nowText).assertDoesNotExist()
        composeRule.onNodeWithText(invitedText).assertDoesNotExist()
        composeRule.onNode(hasClickAction()).assertIsNotSelected()
    }

    @Test
    fun selectionModeReplacesTimestampForReadRows() {
        val selectionMode = mutableStateOf(false)
        val selected = mutableStateOf(false)
        render(selectionMode, selected)

        composeRule.onNodeWithText(nowText).assertExists()

        selectionMode.value = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText(nowText).assertDoesNotExist()
        composeRule.onNode(hasClickAction()).assertIsNotSelected()
    }

    private fun assertSelectionMetadataHidden(unreadCount: ULong) {
        composeRule.onNodeWithText(nowText).assertDoesNotExist()
        composeRule.onNodeWithText(unreadCount.toString()).assertDoesNotExist()
        composeRule.onAllNodesWithContentDescription(mentionDescription).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(selectDescription).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(selectedDescription).assertCountEquals(0)
    }

    private fun render(
        selectionMode: MutableState<Boolean>,
        selected: MutableState<Boolean>,
        unreadCount: ULong = 0uL,
        pendingConfirmation: Boolean = false,
        unreadMention: Boolean = false,
    ) {
        val timestampAt = (System.currentTimeMillis() / 1_000L).toULong()
        composeRule.setContent {
            MaterialTheme {
                Box(
                    Modifier.chatListSelectionRow(
                        selected = selected.value,
                        onClick = {},
                    ),
                ) {
                    ChatRowTrailingContent(
                        selectionMode = selectionMode.value,
                        selected = selected.value,
                        timestampAt = timestampAt,
                        pendingConfirmation = pendingConfirmation,
                        rowHasUnread = unreadCount > 0uL,
                        rowUnreadCount = unreadCount,
                        unreadMention = unreadMention,
                    )
                }
            }
        }
    }
}
