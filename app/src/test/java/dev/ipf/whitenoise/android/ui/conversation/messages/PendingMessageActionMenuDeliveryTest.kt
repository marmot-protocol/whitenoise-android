package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.reconcileSuccessfulTextSend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    /** Uses production reconciliation and real bubble interactions without duplicating menu logic. */
    private inner class MenuRobot {
        val surface =
            swipeTestSurface(ApplicationProvider.getApplicationContext(), reacted = false, mine = true, media = false)
        private val pending = surface.item.copy(status = MessageStatus.Pending, projected = null)
        var item by mutableStateOf(pending)
        var visible by mutableStateOf(true)
        private var openId by mutableStateOf<String?>(null)

        /** The host owns selection in the same way as the conversation's lazy row. */
        fun render() {
            composeRule.setContent {
                if (visible) {
                    key(item.presentationId) {
                        val messageId = item.presentationId
                        SwipeTestBubbleHost(
                            surface = surface.copy(item = item),
                            actionMenuOpen = openId == messageId,
                            onActionMenuOpenChange = { open ->
                                if (open) {
                                    openId = messageId
                                } else if (openId == messageId) {
                                    openId = null
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
