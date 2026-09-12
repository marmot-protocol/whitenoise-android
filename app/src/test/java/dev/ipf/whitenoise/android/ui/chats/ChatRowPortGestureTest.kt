package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Native ListItem and the real ChatListRow must share one tap/hold/range owner without fall-through. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatRowPortGestureTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val events = Events()
    private val selecting = mutableStateOf(false)
    private val rangeActive = mutableStateOf(false)
    private val visible = mutableStateOf(true)

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
        render()
        row().performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
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
            moveTo(Offset(center.x, center.y + viewConfiguration.touchSlop + 24f))
        }
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        assertEquals(1, events.starts)
        assertEquals(1, events.cancels)
        assertEquals(0, events.ends)
        assertEquals(0, events.opens)
    }

    /** Uses real row state and callbacks, with no native IO or synthetic gesture-only Box substitute. */
    private fun render(enabled: Boolean = true) {
        val state = ChatRowPortFixtures.state(context)
        val item = ChatRowPortFixtures.item()
        composeRule.setContent {
            WhiteNoiseTheme {
                Column(Modifier.fillMaxWidth()) {
                    if (visible.value) {
                        ChatListRow(
                            item = item,
                            appState = state,
                            isMuted = false,
                            interactionsEnabled = enabled,
                            selectionMode = selecting.value,
                            selected = selecting.value,
                            onOpen = { events.opens++ },
                            onOpenProfile = { error("Named group has no DM avatar action") },
                            onOpenActions = { events.actions++ },
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
