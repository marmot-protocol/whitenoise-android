package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.reconcileSuccessfulTextSend
import dev.ipf.whitenoise.android.ui.conversation.TimelineRow
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Exercises the actual bubble menu across outgoing-send reconciliation and row disposal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class PendingMessageActionMenuDeliveryTest {
    @get:Rule val composeRule = createComposeRule()

    /** Confirmation keeps the popup visible and binds Reply to the confirmed record. */
    @Test
    fun confirmationKeepsTheOpenMenuAndUsesTheConfirmedTarget() {
        val robot = MenuRobot()
        robot.render()
        robot.open()
        robot.confirm()
        robot.assertOpen()
        composeRule.onNodeWithText("Reply").performClick()
        composeRule.runOnIdle {
            assertEquals(
                CONFIRMED_ID,
                robot.surface.controller.replyingTo
                    ?.messageIdHex,
            )
        }
    }

    /** A held initiating pointer is not cancelled by the temporary-to-confirmed ID replacement. */
    @Test
    fun confirmationWhileHoldingKeepsMenuOpenAfterRelease() {
        val robot = MenuRobot()
        robot.render()
        val host = composeRule.onNodeWithTag(SWIPE_TEST_HOST_TAG)
        host.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(center)
        }
        robot.assertOpen()
        robot.confirm()
        robot.assertOpen()
        host.performTouchInput { up() }
        robot.assertOpen()
    }

    /** Ordinary status and ordering updates retain the menu's presentation owner. */
    @Test
    fun sameIdentityStatusChangesKeepTheMenuOpen() {
        val robot = MenuRobot()
        robot.render()
        robot.open()
        composeRule.runOnIdle { robot.item = robot.item.copy(status = MessageStatus.Sent) }
        robot.assertOpen()
        composeRule.runOnIdle { robot.item = robot.item.copy(timelineOrder = 99uL) }
        robot.assertOpen()
    }

    /** A dismissed menu is not resurrected by a successful send. */
    @Test
    fun dismissedMenuDoesNotReopenAtConfirmation() {
        val robot = MenuRobot()
        robot.render()
        robot.open()
        composeRule.onNodeWithTag("message-actions-preview").performClick()
        robot.assertClosed()
        robot.confirm()
        robot.assertClosed()
    }

    /** Removing a row clears its owner even if the same row is subsequently displayed again. */
    @Test
    fun removingTheConfirmedRowClosesTheMenu() {
        val robot = MenuRobot()
        robot.render()
        robot.open()
        robot.confirm()
        composeRule.runOnIdle { robot.visible = false }
        robot.assertClosed()
        composeRule.runOnIdle { robot.visible = true }
        robot.assertClosed()
    }

    /** Deletion still dismisses the popup after the identity handoff. */
    @Test
    fun deletionClosesTheConfirmedMenu() {
        val robot = MenuRobot()
        robot.render()
        robot.open()
        robot.confirm()
        composeRule.runOnIdle {
            robot.item =
                robot.item.copy(
                    projected =
                        robot.surface.item.projected!!
                            .copy(messageIdHex = CONFIRMED_ID, deleted = true),
                )
        }
        robot.assertClosed()
    }

    /** A different row cannot inherit the departing row's popup. */
    @Test
    fun replacingTheRowWithAnotherMessageDoesNotTransferTheMenu() {
        val robot = MenuRobot()
        robot.render()
        robot.open()
        composeRule.runOnIdle {
            robot.item =
                robot.item.copy(
                    id = "msg:unrelated",
                    presentationId = "msg:unrelated",
                    record = robot.item.record.copy(messageIdHex = "unrelated"),
                )
        }
        robot.assertClosed()
    }

    /** Changing conversations clears selection even when the destination reuses the same row key. */
    @Test
    fun conversationNavigationDoesNotReopenTheDeliveredMenu() = assertNavigationDismisses(changeAccount = false)

    /** Account navigation clears selection before the old account's late confirmation arrives. */
    @Test
    fun accountNavigationDoesNotReopenTheDeliveredMenu() = assertNavigationDismisses(changeAccount = true)

    /** Keeps the row identity deliberately identical to exercise route ownership rather than row removal. */
    private fun assertNavigationDismisses(changeAccount: Boolean) {
        val robot = MenuRobot()
        robot.render()
        robot.open()
        robot.confirm()
        robot.assertOpen()
        composeRule.runOnIdle {
            val group = robot.surface.controller.group
            robot.surface =
                robot.surface.copy(
                    controller =
                        ConversationController(
                            appState = robot.surface.appState,
                            initialGroup = if (changeAccount) group else group.copy(groupIdHex = "other-group"),
                            accountRefOverride = if (changeAccount) "other-account" else SWIPE_TEST_ACCOUNT_REF,
                        ),
                )
        }
        robot.assertClosed()
        robot.confirm()
        robot.assertClosed()
        robot.open()
        robot.assertOpen()
    }

    /** Uses production reconciliation and real bubble interactions without duplicating menu logic. */
    private inner class MenuRobot {
        var surface by mutableStateOf(
            swipeTestSurface(ApplicationProvider.getApplicationContext(), reacted = false, mine = true, media = false),
        )
        private val pending = surface.item.copy(status = MessageStatus.Pending, projected = null)
        var item by mutableStateOf(pending)
        var visible by mutableStateOf(true)
        private var openState = mutableStateOf<String?>(null)
        private val openId get() = openState.value

        /** The host owns selection in the same way as the conversation's lazy row. */
        fun render() {
            composeRule.setContent {
                val owner = remember(surface.controller) { mutableStateOf<String?>(null) }
                SideEffect { openState = owner }
                if (visible) {
                    key(item.presentationId) {
                        val messageId = item.presentationId
                        PendingMenuTimelineRowHost(
                            surface = surface.copy(item = item),
                            actionMenuOpen = owner.value == messageId,
                            onActionMenuOpenChange = { open ->
                                if (open) {
                                    owner.value = messageId
                                } else if (owner.value == messageId) {
                                    owner.value = null
                                }
                            },
                        )
                    }
                }
            }
        }

        /** Opens the actual menu with a long press. */
        fun open() {
            composeRule.onNodeWithTag(SWIPE_TEST_HOST_TAG).performTouchInput { longClick() }
            assertOpen()
        }

        /** Applies the successful-send bridge used before the native projection arrives. */
        fun confirm() {
            composeRule.runOnIdle {
                val optimistic = linkedMapOf(pending.id to pending)
                reconcileSuccessfulTextSend(
                    summaryMessageIds = listOf(CONFIRMED_ID),
                    acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                    optimisticKey = pending.id,
                    tempId = pending.record.messageIdHex,
                    optimisticRecord = pending.record,
                    optimisticMessages = optimistic,
                    messageById = linkedMapOf(pending.record.messageIdHex to pending.record),
                    projectedMessageIds = emptySet(),
                    timelineOrder = 1uL,
                )
                item = optimistic.values.single()
            }
        }

        /** Checks both popup visibility and ownership. */
        fun assertOpen() {
            composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).assertIsDisplayed()
            composeRule.runOnIdle { assertEquals(item.presentationId, openId) }
        }

        /** Checks disposal does not leave a stale owner that could reopen the popup. */
        fun assertClosed() {
            composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).assertDoesNotExist()
            composeRule.runOnIdle { assertNull(openId) }
        }
    }

    private companion object {
        const val CONFIRMED_ID = "confirmed-message"
    }
}

/** Renders the production timeline wrapper, including its independent disposal and account boundaries. */
@Composable
@Suppress("FunctionNaming", "LongMethod") // Mirrors the real timeline row's complete interaction contract.
private fun PendingMenuTimelineRowHost(
    surface: SwipeTestSurface,
    actionMenuOpen: Boolean,
    onActionMenuOpenChange: (Boolean) -> Unit,
) {
    WhiteNoiseTheme {
        Box(Modifier.fillMaxWidth().testTag(SWIPE_TEST_HOST_TAG)) {
            TimelineRow(
                item = surface.item,
                older = surface.item,
                newer = null,
                transcriptLocale = Locale.US,
                entryUnreadCount = 0,
                entryUnreadDividerRetired = true,
                entryFirstUnreadMessageId = null,
                onMeasured = { _, _ -> },
                appState = surface.appState,
                controller = surface.controller,
                composerTextState = ComposerTextState(TextFieldValue("")),
                highlighted = false,
                selectionMode = false,
                textSelectionMode = false,
                onTextSelectionModeChange = {},
                onTextSelectionBoundsChange = {},
                batchSelectable = true,
                selected = false,
                onToggleSelection = {},
                rangeDragActive = false,
                onDragSelectionStart = {},
                onDragSelection = { false },
                onDragSelectionEnd = {},
                onDragSelectionCancel = {},
                quickReactionEmojis = emptyList(),
                recentEmojis = emptyList(),
                onEmojiUsed = {},
                isActionMenuOpen = actionMenuOpen,
                onActionMenuOpenChange = onActionMenuOpenChange,
                onQuickReactionsSave = {},
                onReplyPreviewClick = {},
                composerGate = ComposerGate.COMPOSER,
                onBack = {},
                mentionCandidates = emptyList(),
                mentionPickerEnabled = false,
                collapseLongMessages = false,
            )
        }
    }
}
