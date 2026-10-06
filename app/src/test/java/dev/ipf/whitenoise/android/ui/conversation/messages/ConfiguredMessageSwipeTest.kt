package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.SwipeAction
import dev.ipf.whitenoise.android.state.SwipeBinding
import dev.ipf.whitenoise.android.ui.conversation.composer.EMOJI_PICKER_TEST_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises real controller-backed bubbles through the production pointer stream and command handlers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w400dp-h800dp-mdpi")
class ConfiguredMessageSwipeTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var surface: SwipeTestSurface
    private val readOnly = mutableStateOf(false)
    private val selecting = mutableStateOf(false)

    @Before fun reset() {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test fun defaultRightRepliesExactlyToTheOriginalMessage() {
        render()
        drag(100f)
        assertEquals(SWIPE_TEST_MESSAGE_ID, surface.controller.replyingTo?.messageIdHex)
    }

    /** Local send rows expose the same Reply action before native acknowledgement and after send failure. */
    @Test fun pendingOptimisticMessageStillReplies() {
        render(optimistic = dev.ipf.whitenoise.android.state.MessageStatus.Pending)
        drag(100f)
        assertEquals(SWIPE_TEST_MESSAGE_ID, surface.controller.replyingTo?.messageIdHex)
    }

    @Test fun failedOptimisticMessageStillReplies() {
        render(optimistic = dev.ipf.whitenoise.android.state.MessageStatus.Failed)
        drag(100f)
        assertEquals(SWIPE_TEST_MESSAGE_ID, surface.controller.replyingTo?.messageIdHex)
    }

    @Test fun removedOptimisticMessageCannotCommitAHeldSwipe() {
        render(optimistic = dev.ipf.whitenoise.android.state.MessageStatus.Pending)
        row().performTouchInput {
            down(center)
            moveBy(Offset(100f, 0f))
        }
        rule.runOnIdle {
            surface.appState.optimisticMessages(SWIPE_TEST_ACCOUNT_REF, SWIPE_TEST_GROUP_ID).clear()
        }
        row().performTouchInput { up() }
        assertNull(surface.controller.replyingTo)
    }

    @Test fun defaultLeftIsInactive() {
        render()
        drag(-100f)
        assertNull(surface.controller.replyingTo)
        row().assertExists()
    }

    @Test fun bothDisabledKeepTheBubbleAtRest() {
        render(SwipeAction.Off, SwipeAction.Off)
        row().performTouchInput {
            down(center)
            moveBy(Offset(100f, 0f))
        }
        rule.onNodeWithTag(messageReplySwipeGlyphTestTag(SWIPE_TEST_MESSAGE_ID), true).assertDoesNotExist()
        row().performTouchInput { up() }
        assertNull(surface.controller.replyingTo)
    }

    @Test fun leftReplyAndRightForwardOpenTheirExistingActions() {
        render(SwipeAction.Reply, SwipeAction.Forward)
        drag(-100f)
        assertEquals(SWIPE_TEST_MESSAGE_ID, surface.controller.replyingTo?.messageIdHex)
        rule.runOnIdle { surface.controller.replyingTo = null }
        drag(100f)
        rule.onNodeWithTag(FORWARD_CHAT_PICKER_SCREEN_TEST_TAG).assertExists()
        assertNull(surface.controller.replyingTo)
    }

    @Test fun explicitPhysicalRightStillRepliesInRtl() {
        render(SwipeAction.Off, SwipeAction.Reply, rtl = true)
        drag(100f)
        assertEquals(SWIPE_TEST_MESSAGE_ID, surface.controller.replyingTo?.messageIdHex)
    }

    /** React opens the existing chooser without choosing or transmitting an emoji. */
    @Test fun reactOpensTheExistingPickerWithoutReplying() {
        render(SwipeAction.React, SwipeAction.Off)
        drag(-100f)
        rule.onNodeWithTag(EMOJI_PICKER_TEST_TAG).assertExists()
        assertNull(surface.controller.replyingTo)
    }

    /** Both physical bindings may intentionally select the same command independently. */
    @Test fun bothDirectionsCanReplyToTheSameMessage() {
        render(SwipeAction.Reply, SwipeAction.Reply)
        drag(-100f)
        assertEquals(SWIPE_TEST_MESSAGE_ID, surface.controller.replyingTo?.messageIdHex)
        rule.runOnIdle { surface.controller.replyingTo = null }
        drag(100f)
        assertEquals(SWIPE_TEST_MESSAGE_ID, surface.controller.replyingTo?.messageIdHex)
    }

    @Test fun rtlDefaultRepliesLeft() {
        render(rtl = true)
        drag(-100f)
        assertEquals(SWIPE_TEST_MESSAGE_ID, surface.controller.replyingTo?.messageIdHex)
    }

    @Test fun shortVerticalCancelledAndReversedDragsNeverReply() {
        render(SwipeAction.Reply, SwipeAction.Reply)
        drag(30f)
        row().performTouchInput {
            down(center)
            moveBy(Offset(25f, 110f))
            up()
        }
        row().performTouchInput {
            down(center)
            moveBy(Offset(100f, 0f))
            cancel()
        }
        row().performTouchInput {
            down(center)
            moveBy(Offset(100f, 0f))
            moveBy(Offset(-140f, 0f))
            up()
        }
        assertNull(surface.controller.replyingTo)
    }

    @Test fun accountChangeRevokesHeldDragBeforeCommit() {
        render()
        row().performTouchInput {
            down(center)
            moveBy(Offset(100f, 0f))
        }
        rule.runOnIdle {
            surface.appState.javaClass
                .getDeclaredMethod("setActiveAccountRef", String::class.java)
                .apply { isAccessible = true }
                .invoke(surface.appState, "other")
        }
        row().performTouchInput { up() }
        assertNull(surface.controller.replyingTo)
    }

    @Test fun settingChangeRevokesHeldDrag() {
        render()
        row().performTouchInput {
            down(center)
            moveBy(Offset(100f, 0f))
        }
        rule.runOnIdle { surface.appState.swipePreferences.set(SwipeBinding.MessageRight, SwipeAction.Off) }
        row().performTouchInput { up() }
        assertNull(surface.controller.replyingTo)
    }

    @Test fun removedTargetCannotCommitAnAlreadyArmedDrag() {
        render()
        row().performTouchInput {
            down(center)
            moveBy(Offset(100f, 0f))
        }
        rule.runOnIdle { surface.controller.removeProjectedRecord(SWIPE_TEST_MESSAGE_ID) }
        row().performTouchInput { up() }
        assertNull(surface.controller.replyingTo)
    }

    /** Read-only rows reject all action recognition, including non-reply choices. */
    @Test fun readOnlyRowsDoNotRevealTheReactionPicker() {
        readOnly.value = true
        render(SwipeAction.React, SwipeAction.Forward)
        drag(-100f)
        drag(100f)
        rule.onNodeWithTag(EMOJI_PICKER_TEST_TAG).assertDoesNotExist()
        rule.onNodeWithTag(FORWARD_CHAT_PICKER_SCREEN_TEST_TAG).assertDoesNotExist()
        assertNull(surface.controller.replyingTo)
    }

    /** Selection entering during an armed gesture revokes the old stream. */
    @Test fun selectionChangeRevokesHeldMessageDrag() {
        render()
        row().performTouchInput {
            down(center)
            moveBy(Offset(100f, 0f))
        }
        rule.runOnIdle { selecting.value = true }
        row().performTouchInput { up() }
        assertNull(surface.controller.replyingTo)
    }

    private fun render(
        left: SwipeAction = SwipeAction.Default,
        right: SwipeAction = SwipeAction.Default,
        rtl: Boolean = false,
        optimistic: dev.ipf.whitenoise.android.state.MessageStatus? = null,
    ) {
        surface = swipeTestSurface(context, reacted = true, mine = optimistic != null, media = false)
        if (optimistic != null) {
            val item = surface.item.copy(status = optimistic, projected = null)
            surface.controller.removeProjectedRecord(SWIPE_TEST_MESSAGE_ID)
            surface.appState.optimisticMessages(SWIPE_TEST_ACCOUNT_REF, SWIPE_TEST_GROUP_ID)[item.id] = item
            surface = surface.copy(item = item)
        }
        surface.appState.swipePreferences.set(SwipeBinding.MessageLeft, left)
        surface.appState.swipePreferences.set(SwipeBinding.MessageRight, right)
        rule.setContent {
            SwipeTestBubbleHost(surface, rtl = rtl, readOnly = readOnly.value, selecting = selecting.value)
        }
        rule.waitForIdle()
    }

    private fun row() = rule.onNodeWithTag(SWIPE_TEST_HOST_TAG)

    private fun drag(distance: Float) {
        row().performTouchInput {
            down(center)
            moveBy(Offset(distance, 0f))
            up()
        }
    }
}
