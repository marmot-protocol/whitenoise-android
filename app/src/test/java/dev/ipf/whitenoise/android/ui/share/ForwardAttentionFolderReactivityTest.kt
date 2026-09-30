package dev.ipf.whitenoise.android.ui.share

import android.os.Looper
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.ui.conversation.messages.FORWARD_CHAT_PICKER_ACCOUNT_ROW_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.ForwardMessagePickerContent
import dev.ipf.whitenoise.android.ui.conversation.messages.forwardFolderChipTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

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

    @Test
    fun accountSwitchUsesTheDestinationUnreadMentionsForTheSameChatIds() {
        val otherAccountRef = "bob"
        val otherAccountHex = "d0".repeat(32)
        val appState =
            emptyAppState(
                accounts = listOf(testAccount(ACCOUNT_REF, ACCOUNT_HEX), testAccount(otherAccountRef, otherAccountHex)),
            )
        val activeController = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        listOf(GROUP_A to PEER_A, GROUP_B to PEER_B).forEach { (groupId, peerId) ->
            activeController.applyLocalDirectChat(groupId, ACCOUNT_HEX, peerId)
            activeController.publishRow(attentionRow(groupId))
        }
        appState.attachChatsController(activeController)
        val store = appState.chatFolderPreferences
        listOf(ACCOUNT_REF, otherAccountRef).forEach {
            store.clearAllForAccount(it)
            store.foldersFor(it)
        }
        val folders =
            listOf(ACCOUNT_REF, otherAccountRef).associateWith { owner ->
                requireNotNull(
                    store.commitFolderDraft(
                        accountRef = owner,
                        folderId = null,
                        name = "$owner mentions",
                        description = "",
                        manualChatIds = emptySet(),
                        rule = ChatFolderRule(unreadMentionsOnly = true),
                    ),
                )
            }
        var destinationController: ChatsController? = null
        var selectedOwner: String? = null
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
                            onPickerStateChanged = { owner, _, _ -> selectedOwner = owner },
                            controllerFactory = { state ->
                                ChatsController(state, otherAccountRef) { _, _ -> emptyList() }
                                    .also { destinationController = it }
                            },
                            controllerBinder = { controller, owner ->
                                listOf(GROUP_A to PEER_A, GROUP_B to PEER_B).forEach { (groupId, peerId) ->
                                    controller.applyLocalDirectChat(groupId, otherAccountHex, peerId)
                                    controller.publishRow(
                                        attentionRow(groupId).copy(unreadMention = false, unreadMentionCount = 0uL),
                                        owner,
                                    )
                                }
                            },
                        )
                    }
                }
            }
            val activeChip = composeRule.onNodeWithTag(forwardFolderChipTestTag(folders.getValue(ACCOUNT_REF).id))
            val destinationChip = composeRule.onNodeWithTag(forwardFolderChipTestTag(folders.getValue(otherAccountRef).id))
            activeChip.assertExists()
            composeRule.onNodeWithTag(FORWARD_CHAT_PICKER_ACCOUNT_ROW_TEST_TAG).performClick()
            composeRule.onNodeWithText(otherAccountRef).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) { selectedOwner == otherAccountRef && destinationController != null }
            activeChip.assertDoesNotExist()
            destinationChip.assertDoesNotExist()

            val destination = requireNotNull(destinationController)
            publishAndAwaitRevision(destination, attentionRow(GROUP_A), otherAccountRef)
            destinationChip.assertDoesNotExist()
            publishAndAwaitRevision(destination, attentionRow(GROUP_B), otherAccountRef)
            destinationChip.assertExists()
            activeChip.assertDoesNotExist()

            composeRule.onNodeWithTag(FORWARD_CHAT_PICKER_ACCOUNT_ROW_TEST_TAG).performClick()
            composeRule.onNodeWithText(ACCOUNT_REF).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) { selectedOwner == ACCOUNT_REF }
            activeChip.assertExists()
            destinationChip.assertDoesNotExist()
        } finally {
            composeRule.runOnIdle {
                listOf(ACCOUNT_REF, otherAccountRef).forEach(store::clearAllForAccount)
                appState.attachChatsController(null)
                activeController.onCleared()
                destinationController?.onCleared()
            }
        }
    }

    /** Wait for the production controller's debounced publication rather than racing its next frame. */
    private fun publishAndAwaitRevision(
        controller: ChatsController,
        row: ChatListRowFfi,
        accountRef: String = ACCOUNT_REF,
    ) {
        var previousRevision = 0L
        composeRule.runOnIdle {
            previousRevision = controller.forwardTargetsRevision
            controller.publishRow(row, accountRef)
            val published = requireNotNull(controller.forwardTargets().first { it.id == row.groupIdHex }.projection)
            assertEquals(row.hasUnread, published.hasUnread)
            assertEquals(row.unreadMention, published.unreadMention)
            assertEquals(row.pinned, published.pinned)
            assertEquals(row.conversationKind, published.conversationKind)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            // Compose's frame clock does not advance the controller's Android Handler delay.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            controller.forwardTargetsRevision > previousRevision
        }
    }

    /** Use the ordered native stream reducer with a complete authoritative read watermark. */
    private fun ChatsController.publishRow(
        row: ChatListRowFfi,
        ownerAccountRef: String = ACCOUNT_REF,
    ) {
        applyChatListSubscriptionUpdate(
            accountRef = ownerAccountRef,
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
