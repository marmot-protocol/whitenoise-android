package dev.ipf.whitenoise.android.ui.chats

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatListUnreadPullSurfaceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun resistanceNeverOutrunsFingerAtAnyDensityAndRemainsBounded() {
        listOf(72f, 216f).forEach { threshold ->
            listOf(0.01f, 0.1f, 0.5f, 1f, 2f, 100f).forEach { ratio ->
                val displacement = unreadPullDisplacement(ratio, threshold)
                assertTrue(displacement > 0f)
                assertTrue(displacement < ratio * threshold)
                assertTrue(displacement < threshold * 1.5f)
            }
        }
    }

    @Test fun actualRowsFollowBothDownwardTogglesWithOneHapticEach() {
        val unread = mutableStateOf(false)
        var haptics = 0
        var toggles = 0
        rule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalHapticFeedback provides
                        object : HapticFeedback {
                            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                                haptics++
                            }
                        },
                ) {
                    Box(Modifier.size(300.dp, 500.dp)) {
                        ChatListUnreadPullSurface(unread.value, true, true, { true }, {
                            unread.value = it
                            toggles++
                        }) {
                            LazyColumn {
                                items(20) { Text("Chat $it", Modifier.size(48.dp).testTag("row.$it")) }
                            }
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
        repeat(2) { direction ->
            val before =
                rule
                    .onNodeWithTag("row.0")
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            rule.onNodeWithTag("chats.unread_surface").performTouchInput {
                down(Offset(width / 2f, height * 0.3f))
                moveBy(Offset(0f, 40f))
            }
            val moved =
                rule
                    .onNodeWithTag("row.0")
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            assertTrue(moved > before)
            assertTrue(moved - before < 40f)
            assertEquals(direction, toggles)
            rule.onNodeWithTag("chats.unread_surface").performTouchInput {
                moveBy(Offset(0f, height * 0.4f))
                up()
            }
            rule.waitForIdle()
            assertEquals(direction + 1, toggles)
            assertEquals(direction + 1, haptics)
            assertEquals(direction == 0, unread.value)
        }
    }

    /** The pinned divider rides the single body offset with its rows through a held pull, release and toggles (#3842). */
    @Test fun pinnedDividerMovesWithRowsOnEveryPullFrame() {
        val unread = mutableStateOf(false)
        rule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.size(300.dp, 500.dp)) {
                    ChatListUnreadPullSurface(unread.value, true, true, { true }, { unread.value = it }) {
                        LazyColumn {
                            items(2, key = { it }) { Text("Chat $it", Modifier.size(48.dp).testTag("row.$it")) }
                            item(key = CHAT_LIST_PINNED_BOUNDARY_KEY) {
                                ChatListPinnedBoundary(chatListPinnedBoundaryMotion(rowCount = 6))
                            }
                            items(4, key = { it + 2 }) { Text("Chat ${it + 2}", Modifier.size(48.dp).testTag("row.${it + 2}")) }
                        }
                    }
                }
            }
        }
        fun top(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        val above = top(CHAT_LIST_PINNED_BOUNDARY_TAG) - top("row.1")
        val below = top("row.2") - top(CHAT_LIST_PINNED_BOUNDARY_TAG)
        fun assertTogether(phase: String) {
            assertEquals(phase, above, top(CHAT_LIST_PINNED_BOUNDARY_TAG) - top("row.1"), 0.5f)
            assertEquals(phase, below, top("row.2") - top(CHAT_LIST_PINNED_BOUNDARY_TAG), 0.5f)
        }
        val rest = top("row.0")
        // Below the threshold: the held pull and its spring return move rows and divider together.
        rule.onNodeWithTag("chats.unread_surface").performTouchInput {
            down(Offset(width / 2f, height * 0.3f))
            moveBy(Offset(0f, 40f))
        }
        assertTrue(top("row.0") > rest)
        assertTogether("held pull")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("chats.unread_surface").performTouchInput { up() }
        repeat(40) { frame ->
            rule.mainClock.advanceTimeByFrame()
            rule.runOnIdle { }
            assertTogether("return frame $frame")
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(rest, top("row.0"), 0.5f)
        assertEquals(false, unread.value)
        // Past the threshold in both directions: every settling frame keeps the divider attached.
        repeat(2) { direction ->
            rule.onNodeWithTag("chats.unread_surface").performTouchInput {
                down(Offset(width / 2f, height * 0.3f))
                moveBy(Offset(0f, height * 0.6f))
            }
            rule.mainClock.autoAdvance = false
            rule.onNodeWithTag("chats.unread_surface").performTouchInput { up() }
            repeat(40) { frame ->
                rule.mainClock.advanceTimeByFrame()
                rule.runOnIdle { }
                assertTogether("toggle $direction frame $frame")
            }
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
            assertEquals(direction == 0, unread.value)
            assertTogether("toggle $direction settled")
        }
    }

    /** While the unread snapshot loads, a scrolled unread list scrolls instead of clearing the filter (#3622). */
    @Test fun scrolledUnreadListPassesDownwardDragToTheList() {
        var toggles = 0
        lateinit var listState: LazyListState
        rule.setContent {
            WhiteNoiseTheme {
                listState = rememberLazyListState(initialFirstVisibleItemIndex = 10)
                Box(Modifier.size(300.dp, 500.dp)) {
                    ChatListUnreadPullSurface(true, true, true, {
                        chatListUnreadPullArmed(
                            unreadOnly = true,
                            listEmpty = false,
                            hasEarlierChats = false,
                            scrollInProgress = listState.isScrollInProgress,
                            firstVisibleItemIndex = listState.firstVisibleItemIndex,
                            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                        )
                    }, { toggles++ }) {
                        LazyColumn(Modifier.testTag("chats.list"), state = listState) {
                            items(30) { Text("Chat $it", Modifier.size(48.dp).testTag("row.$it")) }
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("chats.list").performTouchInput { swipe(center, Offset(center.x, height - 1f)) }
        rule.waitForIdle()
        assertEquals(0, toggles)
        assertTrue(listState.firstVisibleItemIndex < 10)
    }

    @Test fun accessibleActionAndWholePillClearAnEmptyBodyEvenWithGestureDisabled() {
        val unread = mutableStateOf(false)
        var changes = 0
        rule.setContent {
            WhiteNoiseTheme {
                ChatListUnreadPullSurface(unread.value, true, false, { true }, {
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    unread.value = it
                    changes++
                }) { Text("No chats") }
            }
        }
        rule.onNodeWithTag("chats.unread_surface").performUnreadFilterAction(rule)
        rule.onNodeWithTag("chats.unread_filter").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
        assertEquals(2, changes)
        assertEquals(false, unread.value)
    }
}
