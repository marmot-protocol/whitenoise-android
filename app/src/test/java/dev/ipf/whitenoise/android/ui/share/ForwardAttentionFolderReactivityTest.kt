package dev.ipf.whitenoise.android.ui.share

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.ui.conversation.messages.ForwardMessagePickerContent
import dev.ipf.whitenoise.android.ui.conversation.messages.forwardFolderChipTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** New category rules must update a mounted forward picker as its authoritative rows change. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ForwardAttentionFolderReactivityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unreadMentionFolderTracksReadPinAndDirectStateWithoutRemounting() {
        val appState = emptyAppState()
        val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        controller.applyLocalDirectChat(GROUP_A, ACCOUNT_HEX, PEER_A)
        controller.applyLocalDirectChat(GROUP_B, ACCOUNT_HEX, PEER_B)
        appState.attachChatsController(controller)
        val store = appState.chatFolderPreferences
        store.clearAllForAccount(ACCOUNT_REF)
        store.foldersFor(ACCOUNT_REF)
        val folder =
            requireNotNull(
                store.commitFolderDraft(
                    accountRef = ACCOUNT_REF,
                    folderId = null,
                    name = "Unread mentions",
                    description = "",
                    manualChatIds = emptySet(),
                    rule = ChatFolderRule(unreadMentionsOnly = true, directChatsOnly = true, pinnedOnly = true),
                ),
            )
        val mentioned = attentionRow(GROUP_B)
        controller.publishRow(attentionRow(GROUP_A))
        controller.publishRow(mentioned)
        try {
            composeRule.setContent {
                WhiteNoiseTheme {
                    Surface {
                        ForwardMessagePickerContent(
                            appState = appState,
                            messageCount = 1,
                            attachmentCount = 0,
                            originGroupIdHex = "ff".repeat(32),
                            sourceAccountRef = ACCOUNT_REF,
                            onDismiss = {},
                            onForward = { _, _ -> true },
                        )
                    }
                }
            }
            val chip = composeRule.onNodeWithTag(forwardFolderChipTestTag(folder.id))
            chip.assertExists()

            // Forward folder chips require two eligible targets; losing one must hide the chip.
            val excludedRows =
                listOf(
                    mentioned.copy(unreadMention = false, unreadMentionCount = 0uL), // ordinary unread
                    mentioned.copy(hasUnread = false, unreadCount = 0uL, unreadMention = false, unreadMentionCount = 0uL),
                    mentioned.copy(
                        hasUnread = false,
                        unreadCount = 0uL,
                        unreadMention = false,
                        unreadMentionCount = 0uL,
                        manuallyMarkedUnread = true,
                    ),
                    mentioned.copy(pinned = false),
                    mentioned.copy(conversationKind = ChatConversationKindFfi.GROUP),
                )
            excludedRows.forEach { excluded ->
                publishAndAwaitRevision(controller, excluded)
                chip.assertDoesNotExist()
                publishAndAwaitRevision(controller, mentioned)
                chip.assertExists()
            }
        } finally {
            composeRule.runOnIdle {
                store.clearAllForAccount(ACCOUNT_REF)
                appState.attachChatsController(null)
                controller.onCleared()
            }
        }
    }

    /** Wait for the production controller's debounced publication rather than racing its next frame. */
    private fun publishAndAwaitRevision(
        controller: ChatsController,
        row: ChatListRowFfi,
    ) {
        var previousRevision = 0L
        composeRule.runOnIdle {
            previousRevision = controller.forwardTargetsRevision
            controller.publishRow(row)
            val published = requireNotNull(controller.forwardTargets().first { it.id == row.groupIdHex }.projection)
            assertEquals(row.hasUnread, published.hasUnread)
            assertEquals(row.unreadMention, published.unreadMention)
            assertEquals(row.pinned, published.pinned)
            assertEquals(row.conversationKind, published.conversationKind)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { controller.forwardTargetsRevision > previousRevision }
    }

    /** Use the ordered native stream reducer with a complete authoritative read watermark. */
    private fun ChatsController.publishRow(row: ChatListRowFfi) {
        applyChatListSubscriptionUpdate(
            accountRef = ACCOUNT_REF,
            update = ChatListSubscriptionUpdateFfi.Row(row = row, trigger = ChatListUpdateTriggerFfi.SNAPSHOT_REFRESH),
        )
    }

    private fun attentionRow(groupId: String): ChatListRowFfi =
        chatRow(groupId).copy(
            hasUnread = true,
            unreadCount = 1uL,
            unreadMention = true,
            unreadMentionCount = 1uL,
            pinned = true,
            lastReadTimelineAt = 0uL,
            lastReadMessageIdHex = "00".repeat(32),
        )
}
