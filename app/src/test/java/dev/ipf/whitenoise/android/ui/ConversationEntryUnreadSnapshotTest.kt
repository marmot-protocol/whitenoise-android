package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.conversation.ConversationEntryUnreadSnapshot
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationEntryUnreadSnapshot
import dev.ipf.whitenoise.android.ui.conversation.shouldShowConversationEntryUnreadDivider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationEntryUnreadSnapshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fullyReadConversationHidesEntryUnreadDivider() {
        assertEquals(
            false,
            shouldShowConversationEntryUnreadDivider(
                entryUnreadCount = 20,
                liveUnreadCount = 0,
                dividerRetired = false,
                messageId = "first-unread",
                firstUnreadMessageId = "first-unread",
            ),
        )
    }

    @Test
    fun retiredEntryDividerDoesNotReappearForNewMessages() {
        assertEquals(
            false,
            shouldShowConversationEntryUnreadDivider(
                entryUnreadCount = 20,
                liveUnreadCount = 1,
                dividerRetired = true,
                messageId = "first-unread",
                firstUnreadMessageId = "first-unread",
            ),
        )
    }

    @Test
    fun controllerSwitchRecomputesUnreadSnapshotWhenBothTimelinesAreNonEmpty() {
        val controllerIdentity = mutableStateOf<Any>(Any())
        val timeline = mutableStateOf(listOf(received("a1"), received("a2")))
        val readAnchor = mutableStateOf<String?>("a2")
        val snapshot = arrayOfNulls<ConversationEntryUnreadSnapshot>(1)

        composeRule.setContent {
            snapshot[0] =
                rememberConversationEntryUnreadSnapshot(
                    controllerIdentity = controllerIdentity.value,
                    projectionUnread = 2,
                    timeline = timeline.value,
                    readAnchorMessageId = readAnchor.value,
                )
        }
        composeRule.waitForIdle()
        assertEquals(ConversationEntryUnreadSnapshot(count = 0, firstUnreadMessageId = null), snapshot[0])

        composeRule.runOnUiThread {
            controllerIdentity.value = Any()
            timeline.value = listOf(received("b1"), received("b2"))
            readAnchor.value = null
        }
        composeRule.waitForIdle()

        assertEquals(ConversationEntryUnreadSnapshot(count = 2, firstUnreadMessageId = "b1"), snapshot[0])
    }

    private fun received(id: String): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = "received",
                    groupIdHex = "group",
                    sender = "bob",
                    plaintext = "text-$id",
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = 9uL,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = 1uL,
                    receivedAt = 1uL,
                ),
            status = MessageStatus.Received,
        )
}
