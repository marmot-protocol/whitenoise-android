package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.SwipeAction
import dev.ipf.whitenoise.android.state.SwipePreferenceState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native ListItem and the real ChatListRow must share one tap/hold/range owner without fall-through. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatRowPortGestureTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val events = Events()
    private val selecting = mutableStateOf(false)
    private val rangeActive = mutableStateOf(false)
    private val visible = mutableStateOf(true)
    private val swipeSettings = mutableStateOf(SwipePreferenceState())
    private val swipes = mutableListOf<SwipeAction>()
    private val heldMenu = mutableStateOf(false)

    /** A physical tap opens exactly once, through the native ListItem callback. */
    @Test fun tapOpensOnce() {
        render()
        row().performTouchInput { click(center) }
        assertEquals(1, events.opens)
        assertEquals(0, events.actions)
        assertEquals(0, events.toggles)
    }

    /** Stationary hold opens actions at the threshold and consumes release without opening the chat. */
    @Test fun stationaryHoldOpensActionsOnceWithoutTapFallThrough() {
        render()
        row().performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        assertEquals(1, events.actions)
        assertEquals(0, events.opens)
        assertEquals(0, events.starts)
    }

    /** Range drag survives the actual row recomposing from normal mode to active selection during the gesture. */
    @Test fun verticalHoldDragKeepsItsOwnerThroughSelectionRecomposition() {
        verifyVerticalHold()
    }

    /** An enabled ancestor swipe must not cancel the child when the menu opens or range selection begins. */
    @Test fun configuredSwipePreservesHeldMenuAndRangeSelection() {
        swipeSettings.value = SwipePreferenceState(chatRight = SwipeAction.PinUnpin)
        verifyVerticalHold()
    }

    private fun verifyVerticalHold() {
        render()
        row().performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset.Zero)
        }
        composeRule.waitForIdle()
        row().performTouchInput {
            moveTo(Offset(center.x, center.y + viewConfiguration.touchSlop + 24f))
        }
        composeRule.waitForIdle()
        row().performTouchInput {
            moveTo(Offset(center.x, center.y + 64f))
            up()
        }
        assertEquals(1, events.actions)
        assertEquals(1, events.starts)
        assertTrue(events.moves >= 1)
        assertEquals(1, events.ends)
        assertEquals(0, events.opens)
        assertEquals(0, events.toggles)
        assertEquals(0, events.cancels)
    }

    /** Selection taps toggle, while holding an already-selectable row remains a no-op. */
    @Test fun selectionTapTogglesButLongHoldDoesNothing() {
        selecting.value = true
        render()
        row().assertIsSelected().performClick()
        row().performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        assertEquals(1, events.toggles)
        assertEquals(0, events.actions)
        assertEquals(0, events.opens)
    }

    /** The transition overlay's disabled state blocks both native tap and custom hold actions. */
    @Test fun disabledRowBlocksTapAndHold() {
        render(enabled = false)
        row().performTouchInput {
            click(center)
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        assertEquals(0, events.opens)
        assertEquals(0, events.actions)
        assertEquals(0, events.starts)
    }

    /** Disposal during a live range must retire screen-owned selection rather than orphaning its pointer. */
    @Test fun disposedRangeDeliversCancellationOnce() {
        render()
        row().performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset.Zero)
        }
        composeRule.waitForIdle()
        row().performTouchInput {
            moveTo(Offset(center.x, center.y + viewConfiguration.touchSlop + 24f))
        }
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        assertEquals(1, events.starts)
        assertEquals(1, events.cancels)
        assertEquals(0, events.ends)
        assertEquals(0, events.opens)
    }

    /** Both default directions preserve the existing non-swipe chat row. */
    @Test fun defaultsNeverRevealOrCommitChatActions() {
        render()
        row().performTouchInput {
            down(center)
            moveBy(Offset(110f, 0f))
        }
        composeRule.onNodeWithTag("chat.swipe.cue").assertDoesNotExist()
        row().performTouchInput {
            up()
            down(center)
            moveBy(Offset(-110f, 0f))
            up()
        }
        assertTrue(swipes.isEmpty())
    }

    /** Each physical side invokes only its selected opt-in action once. */
    @Test fun configuredChatDirectionsDispatchOnce() {
        swipeSettings.value = SwipePreferenceState(chatLeft = SwipeAction.ReadUnread, chatRight = SwipeAction.PinUnpin)
        render()
        row().performTouchInput {
            down(center)
            moveBy(Offset(-110f, 0f))
            up()
        }
        row().performTouchInput {
            down(center)
            moveBy(Offset(110f, 0f))
            up()
        }
        assertEquals(listOf(SwipeAction.ReadUnread, SwipeAction.PinUnpin), swipes)
        assertEquals(0, events.opens)
        assertEquals(0, events.actions)
    }

    /** Off, short movement, scrolling and pointer cancellation are all non-actions. */
    @Test fun disabledAndCancelledChatDirectionsNeverCommit() {
        swipeSettings.value = SwipePreferenceState(chatRight = SwipeAction.MuteUnmute)
        render()
        row().performTouchInput {
            down(center)
            moveBy(Offset(-110f, 0f))
            up()
        }
        row().performTouchInput {
            down(center)
            moveBy(Offset(30f, 0f))
            up()
        }
        row().performTouchInput {
            down(center)
            moveBy(Offset(20f, 100f))
            up()
        }
        row().performTouchInput {
            down(center)
            moveBy(Offset(110f, 0f))
            cancel()
        }
        assertTrue(swipes.isEmpty())
    }

    /** Changing settings while armed cannot reinterpret a gesture as a different command. */
    @Test fun changedBindingRevokesArmedChatGesture() {
        swipeSettings.value = SwipePreferenceState(chatRight = SwipeAction.PinUnpin)
        render()
        row().performTouchInput {
            down(center)
            moveBy(Offset(110f, 0f))
        }
        composeRule.runOnIdle { swipeSettings.value = SwipePreferenceState(chatRight = SwipeAction.MuteUnmute) }
        row().performTouchInput { up() }
        assertTrue(swipes.isEmpty())
    }

    /** Beginning selection retires the horizontal stream even when the row stays composed. */
    @Test fun selectionRevokesArmedChatGesture() {
        swipeSettings.value = SwipePreferenceState(chatRight = SwipeAction.PinUnpin)
        render()
        row().performTouchInput {
            down(center)
            moveBy(Offset(110f, 0f))
        }
        composeRule.runOnIdle { selecting.value = true }
        row().performTouchInput { up() }
        assertTrue(swipes.isEmpty())
    }

    /** An unread chat offers Mark read in dark theme. */
    @Test fun readCueDark() =
        captureCue(
            "read_dark",
            SwipeAction.ReadUnread,
            -1,
            R.string.chat_row_action_mark_read,
            dark = true,
            item = ChatRowPortFixtures.item(unread = true),
        )

    /** A read chat offers Mark unread in light theme. */
    @Test fun unreadCueLight() =
        captureCue("unread_light", SwipeAction.ReadUnread, 1, R.string.chat_row_action_mark_unread)

    /** An unmuted chat offers Mute in AMOLED. */
    @Test fun muteCueAmoled() =
        captureCue("mute_amoled", SwipeAction.MuteUnmute, 1, R.string.chat_row_action_mute, dark = true, amoled = true)

    /** A muted chat offers Unmute in dark theme. */
    @Test fun unmuteCueDark() =
        captureCue(
            "unmute_dark",
            SwipeAction.MuteUnmute,
            -1,
            R.string.chat_row_action_unmute,
            dark = true,
            item = ChatRowPortFixtures.item(muted = true),
        )

    /** An unpinned chat offers Pin with physical direction preserved at large RTL text. */
    @Test fun pinCueLargeRtl() =
        captureCue("pin_large_rtl", SwipeAction.PinUnpin, -1, R.string.chat_row_action_pin, rtl = true)

    /** A pinned chat offers Unpin in light theme. */
    @Test fun unpinCueLight() =
        captureCue(
            "unpin_light",
            SwipeAction.PinUnpin,
            1,
            R.string.chat_row_action_unpin,
            item = ChatRowPortFixtures.item(pinned = true),
        )

    /** Holds a real row's opted-in action below the release threshold for deterministic cue inspection. */
    @Suppress("LongParameterList")
    private fun captureCue(
        name: String,
        action: SwipeAction,
        direction: Int,
        expectedLabel: Int,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        item: ChatListItem = ChatRowPortFixtures.item(),
    ) {
        swipeSettings.value = SwipePreferenceState(chatLeft = action, chatRight = action)
        render(dark = dark, amoled = amoled, rtl = rtl, item = item)
        row().performTouchInput {
            down(center)
            moveBy(Offset(90f * direction, 0f))
        }
        composeRule.onNodeWithTag("chat.swipe.cue").assertContentDescriptionEquals(context.getString(expectedLabel))
        composeRule.onRoot().captureRoboImage("src/test/snapshots/chat_swipe_$name.png")
        row().performTouchInput { cancel() }
        assertTrue(swipes.isEmpty())
    }

    /** Uses real row state and callbacks, with no native IO or synthetic gesture-only Box substitute. */
    private fun render(
        enabled: Boolean = true,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        item: ChatListItem = ChatRowPortFixtures.item(),
    ) {
        val state = ChatRowPortFixtures.state(context)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                        if (visible.value) {
                            ChatSwipeActions(
                                owner = state,
                                settings = swipeSettings.value,
                                enabled = enabled && !selecting.value && !rangeActive.value && !heldMenu.value,
                                leftAllowed = chatSwipeAllowed(swipeSettings.value.chatLeft, item, null),
                                rightAllowed = chatSwipeAllowed(swipeSettings.value.chatRight, item, null),
                                hasUnread = item.effectiveHasUnread(null),
                                isMuted = item.engineMuted(),
                                isPinned = item.pinned(),
                                onCommit = { swipes.add(it) },
                            ) {
                                ChatListRow(
                                    item = item,
                                    appState = state,
                                    isMuted = item.engineMuted(),
                                    interactionsEnabled = enabled,
                                    selectionMode = selecting.value,
                                    selected = selecting.value,
                                    onOpen = { events.opens++ },
                                    onOpenProfile = { error("Named group has no DM avatar action") },
                                    onOpenActions = { events.actions++ },
                                    onActionsHeldChange = { heldMenu.value = it },
                                    onDragSelectionStart = {
                                        events.starts++
                                        selecting.value = true
                                        rangeActive.value = true
                                    },
                                    onDragSelection = {
                                        events.moves++
                                        true
                                    },
                                    onDragSelectionEnd = {
                                        events.ends++
                                        rangeActive.value = false
                                    },
                                    onDragSelectionCancel = {
                                        events.cancels++
                                        rangeActive.value = false
                                    },
                                    rangeDragActive = rangeActive.value,
                                    onToggleSelection = { events.toggles++ },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Builds a row fixture. */
    private fun row() = composeRule.onNodeWithTag("chat.row.g1")

    private class Events {
        var opens = 0
        var actions = 0
        var toggles = 0
        var starts = 0
        var moves = 0
        var ends = 0
        var cancels = 0
    }
}
