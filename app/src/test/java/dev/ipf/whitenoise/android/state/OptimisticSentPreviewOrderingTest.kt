package dev.ipf.whitenoise.android.state

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en")
class OptimisticSentPreviewReturnFrameTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstComposedListFrameAfterConversationDisposalUsesOptimisticFinalIndex() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 20uL), row("chat-b", "Zulu", 10uL))
        val firstFrameOrder = mutableListOf<String>()
        var conversationVisible by mutableStateOf(true)

        composeRule.setContent {
            val listVisible = !conversationVisible
            LaunchedEffect(controller, listVisible) {
                controller.setChatListVisible(listVisible)
            }
            if (listVisible) {
                val visibleIds = controller.items.map { it.id }
                Column(
                    Modifier.onGloballyPositioned {
                        if (firstFrameOrder.isEmpty()) firstFrameOrder += visibleIds
                    },
                ) {
                    visibleIds.forEach { BasicText(it) }
                }
            }
        }

        composeRule.runOnIdle {
            controller.setChatListVisible(false)
            controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "pending B", 20uL))
            // MainShell flushes the hidden controller before disposing the
            // conversation route and exposing the list composition.
            controller.setChatListVisible(true)
            conversationVisible = false
        }
        composeRule.waitForIdle()

        assertEquals(listOf("chat-b", "chat-a"), firstFrameOrder)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class OptimisticSentPreviewOrderingTest {
    @Test
    fun firstReturnFramePublishesOptimisticPreviewAtFinalSameSecondIndex() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 20uL), row("chat-b", "Zulu", 10uL))
        assertEquals(listOf("chat-a", "chat-b"), controller.items.map { it.id })

        controller.setChatListVisible(false)
        assertTrue(controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "pending B", 20uL)))
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-b", "chat-a"), controller.items.map { it.id })
        val optimistic = controller.items.first().projection
        assertEquals("pending B", optimistic?.lastMessage?.plaintext)
        assertEquals(ChatListMessageDeliveryStateFfi.PENDING, optimistic?.lastMessage?.deliveryState)
        assertEquals(20uL, optimistic?.activitySortAt)
        assertEquals(20uL, controller.items.first().latestAt)
    }

    @Test
    fun authoritativeEchoWhileSendIsPendingStillRollsBackOnFailure() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 20uL), row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "pending B", 20uL))
        controller.applyChatListRow(
            row("chat-b", "Zulu", 20uL).copy(
                lastMessage =
                    preview(
                        "confirmed-b",
                        "pending B",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
        )
        controller.rollbackOptimisticSentPreview("chat-b", "temp-b")
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-a", "chat-b"), controller.items.map { it.id })
        assertEquals(
            "message-chat-b",
            controller.items
                .last()
                .projection
                ?.lastMessage
                ?.messageIdHex,
        )
    }

    @Test
    fun deletingAnInterveningNewerMessageRestoresThePendingPreview() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 20uL), row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "last chat-b", 10uL))
        applySubscriptionChatListRow(
            controller,
            row("chat-b", "Zulu", 30uL).copy(
                lastMessage =
                    preview(
                        "incoming-b",
                        "newer B",
                        30uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
            ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
        )
        applySubscriptionChatListRow(
            controller,
            row("chat-b", "Zulu", 10uL).copy(activitySortAt = 30uL, updatedAt = 30uL),
            ChatListUpdateTriggerFfi.LAST_MESSAGE_DELETED,
        )
        controller.setChatListVisible(true)

        val chatB = controller.items.single { it.id == "chat-b" }
        val visiblePreview = chatB.projection?.lastMessage
        assertEquals("temp-b", visiblePreview?.messageIdHex)
        assertEquals("last chat-b", visiblePreview?.plaintext)
        assertEquals(ChatListMessageDeliveryStateFfi.PENDING, visiblePreview?.deliveryState)
    }

    @Test
    fun authoritativeEchoWhileSendIsPendingReconcilesAfterSuccess() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 20uL), row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "pending B", 20uL))
        controller.applyChatListRow(
            row("chat-b", "Zulu", 20uL).copy(
                lastMessage =
                    preview(
                        "confirmed-b",
                        "pending B",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
        )
        controller.commitOptimisticSentPreview("chat-b", "temp-b", "confirmed-b")
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-b", "chat-a"), controller.items.map { it.id })
        assertEquals(
            "confirmed-b",
            controller.items
                .first()
                .projection
                ?.lastMessage
                ?.messageIdHex,
        )
        assertEquals(
            ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
            controller.items
                .first()
                .projection
                ?.lastMessage
                ?.deliveryState,
        )
    }

    @Test
    fun lateEchoCannotMoveTheSameChatBehindANewerAuthoritativeMessage() {
        val controller = controllerWithRows(row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "pending B", 20uL))
        controller.applyChatListRow(
            row("chat-b", "Zulu", 20uL).copy(
                lastMessage =
                    preview(
                        "z-newer-b",
                        "newer B",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
        )
        controller.applyChatListRow(
            row("chat-b", "Zulu", 20uL).copy(
                lastMessage =
                    preview(
                        "zz-echo-b",
                        "pending B",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
        )
        controller.setChatListVisible(true)

        assertEquals(
            "z-newer-b",
            controller.items
                .single()
                .projection
                ?.lastMessage
                ?.messageIdHex,
        )
        assertEquals(
            "newer B",
            controller.items
                .single()
                .projection
                ?.lastMessage
                ?.plaintext,
        )
    }

    @Test
    fun laterIncomingSameSecondActivitySupersedesTheOptimisticTieBreak() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 20uL), row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "pending B", 20uL))
        controller.setChatListVisible(true)
        assertEquals(listOf("chat-b", "chat-a"), controller.items.map { it.id })

        controller.setChatListVisible(false)
        controller.applyChatListRow(
            row("chat-a", "Alpha", 20uL).copy(
                lastMessage =
                    preview(
                        "z-incoming-a",
                        "incoming A",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
        )
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-a", "chat-b"), controller.items.map { it.id })
        assertEquals(
            "z-incoming-a",
            controller.items
                .first()
                .projection
                ?.lastMessage
                ?.messageIdHex,
        )
    }

    @Test
    fun earlierEchoCannotReplaceANewerRapidPreviewOrJumpAhead() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 10uL), row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-1", "pending 1", 20uL))
        controller.applyOptimisticSentPreview("chat-b", preview("temp-2", "pending 2", 20uL))
        controller.applyChatListRow(
            row("chat-a", "Alpha", 20uL).copy(
                lastMessage =
                    preview(
                        "z-incoming-a",
                        "incoming A",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
        )
        controller.applyChatListRow(
            row("chat-b", "Zulu", 20uL).copy(
                lastMessage =
                    preview(
                        "z-authoritative-1",
                        "pending 1",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
        )
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-a", "chat-b"), controller.items.map { it.id })
        assertEquals(
            "temp-2",
            controller.items
                .last()
                .projection
                ?.lastMessage
                ?.messageIdHex,
        )
    }

    @Test
    fun identicalRapidSendEchoIsReassignedWhenItPrecedesCommitCallbacks() {
        val controller = controllerWithRows(row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-1", "same text", 20uL))
        controller.applyOptimisticSentPreview("chat-b", preview("temp-2", "same text", 20uL))
        controller.applyChatListRow(
            row("chat-b", "Zulu", 20uL).copy(
                lastMessage =
                    preview(
                        "confirmed-2",
                        "same text",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
        )
        controller.commitOptimisticSentPreview("chat-b", "temp-1", "confirmed-1")
        controller.commitOptimisticSentPreview("chat-b", "temp-2", "confirmed-2")
        controller.applyChatListRow(
            row("chat-b", "Zulu", 20uL).copy(
                lastMessage =
                    preview(
                        "confirmed-1",
                        "same text",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
        )
        controller.setChatListVisible(true)

        assertEquals(
            "confirmed-2",
            controller.items
                .single()
                .projection
                ?.lastMessage
                ?.messageIdHex,
        )
    }

    @Test
    fun replacementSnapshotGivesANewSameSecondRowFreshOrder() {
        val controller = controllerWithRows(row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "pending B", 20uL))
        replaceRows(
            controller,
            listOf(
                row("chat-b", "Zulu", 20uL).copy(
                    lastMessage =
                        preview(
                            "z-authoritative-b",
                            "pending B",
                            20uL,
                            ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                        ),
                ),
                row("chat-c", "Omega", 20uL),
            ),
        )
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-c", "chat-b"), controller.items.map { it.id })
    }

    @Test
    fun replacementSnapshotCannotReuseStaleSequenceForChangedActivity() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 10uL), row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "pending B", 20uL))
        replaceRows(
            controller,
            listOf(
                row("chat-a", "Alpha", 20uL).copy(
                    lastMessage =
                        preview(
                            "z-incoming-a",
                            "incoming A",
                            20uL,
                            ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                        ),
                ),
                row("chat-b", "Zulu", 20uL).copy(
                    lastMessage =
                        preview(
                            "z-authoritative-b",
                            "pending B",
                            20uL,
                            ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                        ),
                ),
            ),
        )
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-a", "chat-b"), controller.items.map { it.id })
    }

    @Test
    fun authoritativeEchoDoesNotJumpAheadOfLaterSameSecondActivity() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 10uL), row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "pending B", 20uL))
        controller.applyChatListRow(
            row("chat-a", "Alpha", 20uL).copy(
                lastMessage =
                    preview(
                        "z-incoming-a",
                        "incoming A",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
        )
        controller.setChatListVisible(true)
        assertEquals(listOf("chat-a", "chat-b"), controller.items.map { it.id })

        controller.setChatListVisible(false)
        controller.applyChatListRow(
            row("chat-b", "Zulu", 20uL).copy(
                lastMessage =
                    preview(
                        "z-authoritative-b",
                        "pending B",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
        )
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-a", "chat-b"), controller.items.map { it.id })
    }

    @Test
    fun sameSecondAuthoritativeEchoDoesNotMoveTheOptimisticRowAgain() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 20uL), row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "pending B", 20uL))
        controller.setChatListVisible(true)
        assertEquals(listOf("chat-b", "chat-a"), controller.items.map { it.id })

        controller.setChatListVisible(false)
        val authoritativeRow =
            row("chat-b", "Zulu", 20uL).copy(
                lastMessage =
                    preview(
                        "z-authoritative-b",
                        "pending B",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            )
        controller.applyChatListRow(authoritativeRow)
        controller.commitOptimisticSentPreview("chat-b", "temp-b", "z-authoritative-b")
        controller.applyChatListRow(authoritativeRow.copy(activitySortAt = 10uL, updatedAt = 10uL))
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-b", "chat-a"), controller.items.map { it.id })
        val projection = controller.items.first().projection
        assertEquals("z-authoritative-b", projection?.lastMessage?.messageIdHex)
        assertEquals(20uL, projection?.activitySortAt)
    }

    @Test
    fun staleLowerActivitySortAtIsNotRestoredByLateRollback() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 20uL), row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "pending B", 20uL))
        controller.applyChatListRow(row("chat-b", "Zulu", 10uL).copy(activitySortAt = 30uL, updatedAt = 30uL))
        controller.applyChatListRow(row("chat-b", "Zulu", 10uL))
        controller.rollbackOptimisticSentPreview("chat-b", "temp-b")
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-b", "chat-a"), controller.items.map { it.id })
        assertEquals(
            "message-chat-b",
            controller.items
                .first()
                .projection
                ?.lastMessage
                ?.messageIdHex,
        )
        assertEquals(
            30uL,
            controller.items
                .first()
                .projection
                ?.activitySortAt,
        )
    }

    @Test
    fun retiredFirstSuccessStillOwnsItsLateEcho() {
        val controller = controllerWithRows(row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-1", "pending 1", 20uL))
        controller.applyOptimisticSentPreview("chat-b", preview("temp-2", "pending 2", 20uL))
        controller.commitOptimisticSentPreview("chat-b", "temp-1", "confirmed-1")
        controller.commitOptimisticSentPreview("chat-b", "temp-2", "confirmed-2")
        replaceRows(
            controller,
            listOf(
                row("chat-b", "Zulu", 20uL).copy(
                    lastMessage =
                        preview(
                            "confirmed-2",
                            "pending 2",
                            20uL,
                            ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                        ),
                ),
            ),
        )
        controller.applyChatListRow(
            row("chat-b", "Zulu", 20uL).copy(
                lastMessage =
                    preview(
                        "confirmed-1",
                        "pending 1",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
        )
        controller.setChatListVisible(true)

        assertEquals(
            "confirmed-2",
            controller.items
                .single()
                .projection
                ?.lastMessage
                ?.messageIdHex,
        )
    }

    @Test
    fun outOfOrderSuccessCallbacksKeepOnlyTheNewestCommittedPreview() {
        val controller = controllerWithRows(row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-1", "pending 1", 20uL))
        controller.applyOptimisticSentPreview("chat-b", preview("temp-2", "pending 2", 20uL))
        controller.commitOptimisticSentPreview("chat-b", "temp-2", "confirmed-2")
        controller.commitOptimisticSentPreview("chat-b", "temp-1", "confirmed-1")
        controller.setChatListVisible(true)

        assertEquals(
            "confirmed-2",
            controller.items
                .single()
                .projection
                ?.lastMessage
                ?.messageIdHex,
        )
    }

    @Test
    fun coalescedNewestEchoRetiresSupersededCommittedPreviews() {
        val controller = controllerWithRows(row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-1", "pending 1", 20uL))
        controller.applyOptimisticSentPreview("chat-b", preview("temp-2", "pending 2", 20uL))
        controller.commitOptimisticSentPreview("chat-b", "temp-1", "confirmed-1")
        controller.commitOptimisticSentPreview("chat-b", "temp-2", "confirmed-2")
        replaceRows(
            controller,
            listOf(
                row("chat-b", "Zulu", 20uL).copy(
                    lastMessage =
                        preview(
                            "confirmed-2",
                            "pending 2",
                            20uL,
                            ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                        ),
                ),
            ),
        )

        controller.setChatListVisible(true)
        assertEquals(
            "confirmed-2",
            controller.items
                .single()
                .projection
                ?.lastMessage
                ?.messageIdHex,
        )
    }

    @Test
    fun secondRapidFailureFallsBackToTheCommittedFirstPreview() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 20uL), row("chat-b", "Zulu", 20uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-1", "pending 1", 20uL))
        controller.applyOptimisticSentPreview("chat-b", preview("temp-2", "pending 2", 20uL))
        controller.commitOptimisticSentPreview("chat-b", "temp-1", "confirmed-1")
        controller.rollbackOptimisticSentPreview("chat-b", "temp-2")
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-b", "chat-a"), controller.items.map { it.id })
        val committed =
            controller.items
                .first()
                .projection
                ?.lastMessage
        assertEquals("confirmed-1", committed?.messageIdHex)
        assertEquals(ChatListMessageDeliveryStateFfi.DELIVERED, committed?.deliveryState)
    }

    @Test
    fun twoRapidSameSecondFailuresDoNotResurrectTheFirstPreview() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 20uL), row("chat-b", "Zulu", 20uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-1", "pending 1", 20uL))
        controller.applyOptimisticSentPreview("chat-b", preview("temp-2", "pending 2", 20uL))
        controller.rollbackOptimisticSentPreview("chat-b", "temp-1")
        controller.rollbackOptimisticSentPreview("chat-b", "temp-2")
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-a", "chat-b"), controller.items.map { it.id })
        assertEquals(
            "message-chat-b",
            controller.items
                .last()
                .projection
                ?.lastMessage
                ?.messageIdHex,
        )
    }

    @Test
    fun failedSendRollsPreviewAndSameSecondOrderBackTogether() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 20uL), row("chat-b", "Zulu", 20uL))
        assertEquals(listOf("chat-a", "chat-b"), controller.items.map { it.id })

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "pending B", 20uL))
        controller.setChatListVisible(true)
        assertEquals(listOf("chat-b", "chat-a"), controller.items.map { it.id })

        controller.setChatListVisible(false)
        controller.rollbackOptimisticSentPreview("chat-b", "temp-b")
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-a", "chat-b"), controller.items.map { it.id })
        assertEquals(
            "message-chat-b",
            controller.items
                .last()
                .projection
                ?.lastMessage
                ?.messageIdHex,
        )
    }

    @Test
    fun failedRowRemovalRestoresThePreSendPreviewAndOrder() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 20uL), row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "pending B", 20uL))
        removeAndRestoreChatRow(controller, "chat-b")
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-b", "chat-a"), controller.items.map { it.id })
        assertEquals(
            "temp-b",
            controller.items
                .first()
                .projection
                ?.lastMessage
                ?.messageIdHex,
        )

        controller.setChatListVisible(false)
        controller.rollbackOptimisticSentPreview("chat-b", "temp-b")
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-a", "chat-b"), controller.items.map { it.id })
        assertEquals(
            "message-chat-b",
            controller.items
                .last()
                .projection
                ?.lastMessage
                ?.messageIdHex,
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class OptimisticSentPreviewAuthoritativeFoldTest {
    @Test
    fun backwardSameSecondSubscriptionRowUpdatesContentWithoutLoweringOrder() {
        val controller = controllerWithRows(row("chat-a", "Alpha", 20uL), row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "sent B", 20uL))
        controller.commitOptimisticSentPreview("chat-b", "temp-b", "ff-sent-b")
        applySubscriptionChatListRow(
            controller,
            row("chat-b", "Zulu", 20uL).copy(
                lastMessage =
                    preview(
                        "ff-sent-b",
                        "sent B",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
            ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
        )
        applySubscriptionChatListRow(
            controller,
            row("chat-b", "Renamed", 20uL).copy(
                lastMessage =
                    preview(
                        "0a-reply-b",
                        "same-second reply",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
                muted = true,
            ),
            ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
        )
        controller.setChatListVisible(true)

        assertEquals(listOf("chat-b", "chat-a"), controller.items.map { it.id })
        val projection = controller.items.first().projection
        assertEquals("0a-reply-b", projection?.lastMessage?.messageIdHex)
        assertEquals("same-second reply", projection?.lastMessage?.plaintext)
        assertEquals("Renamed", projection?.groupName)
        assertTrue(projection?.muted == true)
    }

    @Test
    fun lateConfirmedEchoCannotReplaceBackwardAcceptedAuthoritativeContent() {
        val controller = controllerWithRows(row("chat-b", "Zulu", 10uL))

        controller.setChatListVisible(false)
        controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "sent B", 20uL))
        controller.commitOptimisticSentPreview("chat-b", "temp-b", "ff-sent-b")
        val sentRow =
            row("chat-b", "Zulu", 20uL).copy(
                lastMessage =
                    preview(
                        "ff-sent-b",
                        "sent B",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            )
        applySubscriptionChatListRow(controller, sentRow, ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE)
        applySubscriptionChatListRow(
            controller,
            row("chat-b", "Zulu", 20uL).copy(
                lastMessage =
                    preview(
                        "0a-reply-b",
                        "same-second reply",
                        20uL,
                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
            ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
        )
        applySubscriptionChatListRow(controller, sentRow, ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE)
        controller.setChatListVisible(true)

        val projection = controller.items.single().projection
        assertEquals("0a-reply-b", projection?.lastMessage?.messageIdHex)
        assertEquals("same-second reply", projection?.lastMessage?.plaintext)
    }

    @Test
    fun clockSkewedOptimisticSendUpdatesPreviewWithoutLoweringRecency() {
        val controller = controllerWithRows(row("chat-b", "Beta", 30uL))

        controller.setChatListVisible(false)
        val applied = controller.applyOptimisticSentPreview("chat-b", preview("temp-b", "local pending", 20uL))
        controller.setChatListVisible(true)

        assertTrue(applied)
        val current = controller.items.single().projection
        assertEquals("temp-b", current?.lastMessage?.messageIdHex)
        assertEquals("local pending", current?.lastMessage?.plaintext)
        assertEquals(30uL, current?.activitySortAt)
    }
}

private fun controllerWithRows(vararg rows: ChatListRowFfi): ChatsController {
    val controller = ChatsController(appState())
    ChatsController::class.java
        .getDeclaredField("accountRef")
        .apply { isAccessible = true }
        .set(controller, ACCOUNT_REF)
    controller.setChatListVisible(false)
    rows.reversed().forEach(controller::applyChatListRow)
    controller.setChatListVisible(true)
    return controller
}

private fun replaceRows(
    controller: ChatsController,
    rows: List<ChatListRowFfi>,
) {
    ChatsController::class.java
        .getDeclaredMethod("replaceChatRows", List::class.java)
        .apply { isAccessible = true }
        .invoke(controller, rows)
}

private fun applySubscriptionChatListRow(
    controller: ChatsController,
    row: ChatListRowFfi,
    trigger: ChatListUpdateTriggerFfi,
) {
    ChatsController::class.java
        .getDeclaredMethod("foldChatRow", ChatListRowFfi::class.java, ChatListUpdateTriggerFfi::class.java)
        .apply { isAccessible = true }
        .invoke(controller, row, trigger)
}

private fun removeAndRestoreChatRow(
    controller: ChatsController,
    groupIdHex: String,
) {
    val snapshotMethod =
        ChatsController::class.java
            .getDeclaredMethod("snapshotChatRowForRemoval", String::class.java)
            .apply { isAccessible = true }
    val snapshot = requireNotNull(snapshotMethod.invoke(controller, groupIdHex))
    ChatsController::class.java
        .getDeclaredMethod("removeChatRow", String::class.java)
        .apply { isAccessible = true }
        .invoke(controller, groupIdHex)
    ChatsController::class.java
        .getDeclaredMethod("restoreRemovedChatRow", snapshot.javaClass)
        .apply { isAccessible = true }
        .invoke(controller, snapshot)
}

private fun appState(): WhiteNoiseAppState =
    WhiteNoiseAppState(
        context = ApplicationProvider.getApplicationContext(),
        draftStore = DraftStore(OptimisticPreviewDraftPersistence()),
        accountIdHexResolver = { null },
        accounts =
            listOf(
                AccountSummaryFfi(
                    label = ACCOUNT_REF,
                    accountIdHex = ACCOUNT_ID,
                    localSigning = true,
                    externalSigning = false,
                    signedOut = false,
                    running = true,
                ),
            ),
        activeAccountRef = ACCOUNT_REF,
    )

private fun row(
    groupId: String,
    title: String,
    activityAt: ULong,
): ChatListRowFfi =
    ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = title,
        groupName = title,
        avatarUrl = null,
        avatar = null,
        lastMessage =
            preview(
                "message-$groupId",
                "last $groupId",
                activityAt,
                ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
            ),
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = 0uL,
        activitySortAt = activityAt,
        updatedAt = activityAt,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        manuallyMarkedUnread = false,
        conversationKind = ChatConversationKindFfi.UNKNOWN,
        muted = false,
        mutedUntilMs = null,
        pinned = false,
        pinnedPosition = null,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
    )

private fun preview(
    messageId: String,
    plaintext: String,
    timelineAt: ULong,
    deliveryState: ChatListMessageDeliveryStateFfi = ChatListMessageDeliveryStateFfi.PENDING,
): ChatListMessagePreviewFfi =
    ChatListMessagePreviewFfi(
        messageIdHex = messageId,
        sender = ACCOUNT_ID,
        senderDisplayName = null,
        plaintext = plaintext,
        contentTokens =
            MarkdownDocumentFfi(
                truncated = false,
                blocks = emptyList(),
                blankLinesBefore = ByteArray(0),
            ),
        kind = 9uL,
        timelineAt = timelineAt,
        deleted = false,
        attachmentKind = null,
        attachmentCount = 0u,
        deliveryState = deliveryState,
    )

private const val ACCOUNT_REF = "alice"
private const val ACCOUNT_ID = "alice-id"

private class OptimisticPreviewDraftPersistence : DraftPersistence {
    private val values = mutableMapOf<String, String>()

    override fun read(): Map<String, String> = values.toMap()

    override fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
