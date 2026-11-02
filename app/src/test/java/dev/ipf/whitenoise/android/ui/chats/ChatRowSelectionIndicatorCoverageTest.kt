package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.InvitationBadge
import dev.ipf.whitenoise.android.ui.common.UnreadCountBadge
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.CheckCircle
import dev.ipf.whitenoise.android.ui.icons.filled.RadioButtonUnchecked
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
    private val invitedText by lazy { context.getString(R.string.invitation_pending) }
    private val mentionDescription by lazy { context.getString(R.string.chat_list_mention_badge) }
    private val selectDescription by lazy { context.getString(R.string.select) }
    private val selectedDescription by lazy { context.getString(R.string.selected) }

    @Test
    fun selectionIndicatorUsesOutlinedAndFilledIcons() {
        assertSame(Icons.Default.RadioButtonUnchecked, chatRowSelectionIcon(selected = false))
        assertSame(Icons.Default.CheckCircle, chatRowSelectionIcon(selected = true))
    }

    /** Selection belongs to the row; its decorative indicator must not duplicate timestamp or badge announcements. */
    @Test
    fun selectedAndUnselectedRowsKeepMetadataVisibleWithoutDuplicateAnnouncements() {
        val selectionMode = mutableStateOf(true)
        val selected = mutableStateOf(false)
        render(selectionMode, selected, unreadCount = 3uL)

        composeRule.onNode(hasClickAction()).assertIsNotSelected()
        assertSelectionMetadataVisible(unreadCount = 3uL)

        selected.value = true
        composeRule.waitForIdle()

        composeRule.onNode(hasClickAction()).assertIsSelected()
        assertSelectionMetadataVisible(unreadCount = 3uL)

        selected.value = false
        composeRule.waitForIdle()

        composeRule.onNode(hasClickAction()).assertIsNotSelected()
        assertSelectionMetadataVisible(unreadCount = 3uL)
    }

    /** Exiting selection retains current unread and mention metadata instead of replacing the row content. */
    @Test
    fun leavingSelectionModeKeepsUnreadTimestampAndBadge() {
        val selectionMode = mutableStateOf(true)
        val selected = mutableStateOf(false)
        render(selectionMode, selected, unreadCount = 3uL, unreadMention = true)

        assertSelectionMetadataVisible(unreadCount = 3uL, mentions = 1)

        selectionMode.value = false
        composeRule.waitForIdle()

        composeRule.onNodeWithText(nowText).assertExists()
        composeRule.onNodeWithText("3").assertExists()
        composeRule.onNodeWithContentDescription(mentionDescription).assertExists()
    }

    /** Selection mode preserves invited timestamp and badge. */
    @Test
    fun selectionModePreservesInvitedTimestampAndBadge() {
        val selectionMode = mutableStateOf(false)
        val selected = mutableStateOf(false)
        render(selectionMode, selected, pendingConfirmation = true)

        composeRule.onNodeWithText(nowText).assertExists()
        composeRule.onNodeWithContentDescription(invitedText).assertExists()

        selectionMode.value = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText(nowText).assertExists()
        composeRule.onNodeWithContentDescription(invitedText).assertExists()
        composeRule.onNode(hasClickAction()).assertIsNotSelected()
    }

    /** Read rows retain their timestamp while gaining the same selection owner as unread rows. */
    @Test
    fun selectionModePreservesTimestampForReadRows() {
        val selectionMode = mutableStateOf(false)
        val selected = mutableStateOf(false)
        render(selectionMode, selected)

        composeRule.onNodeWithText(nowText).assertExists()

        selectionMode.value = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText(nowText).assertExists()
        composeRule.onNode(hasClickAction()).assertIsNotSelected()
    }

    /** Verifies visible metadata with no extra Select/Selected announcement on the decorative icon. */
    private fun assertSelectionMetadataVisible(
        unreadCount: ULong,
        mentions: Int = 0,
    ) {
        composeRule.onNodeWithText(nowText).assertExists()
        composeRule.onNodeWithText(unreadCount.toString()).assertExists()
        composeRule.onAllNodesWithContentDescription(mentionDescription).assertCountEquals(mentions)
        composeRule.onAllNodesWithContentDescription(selectDescription).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(selectedDescription).assertCountEquals(0)
    }

    /** Composes the surface under test with the given fixture. */
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
                // ChatRowLayout now owns the native click/toggle semantics; no duplicate wrapper action.
                Box {
                    ChatRowLayout(
                        title = "Conversation",
                        selectionMode = selectionMode.value,
                        selected = selected.value,
                        timestampAt = timestampAt,
                        rowHasUnread = unreadCount > 0uL,
                        leadingContent = {
                            Box(Modifier.size(44.dp))
                        },
                        supportingContent = {
                            Text("Preview", maxLines = 1)
                        },
                        supportingMetadata =
                            when {
                                pendingConfirmation -> {
                                    {
                                        InvitationBadge()
                                    }
                                }
                                unreadCount > 0uL -> {
                                    {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (unreadMention) MentionBadge()
                                            UnreadCountBadge(unreadCount)
                                        }
                                    }
                                }
                                else -> null
                            },
                    )
                }
            }
        }
    }
}
