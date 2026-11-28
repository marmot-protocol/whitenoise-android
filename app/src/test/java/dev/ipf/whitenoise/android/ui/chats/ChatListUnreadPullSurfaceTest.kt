package dev.ipf.whitenoise.android.ui.chats

import android.os.Looper
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
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
                            PinnedRows(unread.value)
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
        repeat(2) { direction ->
            val before = top("row.0")
            val boundaryAtRest = boundaryBelowFirstRow()
            rule.onNodeWithTag("chats.unread_surface").performTouchInput {
                down(Offset(width / 2f, height * 0.3f))
                moveBy(Offset(0f, 40f))
            }
            val moved = top("row.0")
            assertTrue(moved > before)
            assertTrue(moved - before < 40f)
            assertEquals(direction, toggles)
            val boundaryFrom = boundaryBelowFirstRow()
            assertEquals("the pull must not double-translate the boundary", boundaryAtRest, boundaryFrom, 0.5f)
            val samples = releasePullAndSampleBoundary(boundaryFrom)
            assertEquals(direction + 1, toggles)
            assertEquals(direction + 1, haptics)
            assertEquals(direction == 0, unread.value)
            assertBoundaryTravelled(samples)
            assertSettledPinnedSlot(unread.value)
        }
    }

    /** Completes the pull past the threshold and samples the boundary every frame until the rows settle. */
    private fun releasePullAndSampleBoundary(boundaryFrom: Float): List<Float> {
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("chats.unread_surface").performTouchInput {
            moveBy(Offset(0f, height * 0.4f))
            up()
        }
        val samples = mutableListOf(boundaryFrom)
        repeat(BOUNDARY_FRAMES) {
            rule.mainClock.advanceTimeByFrame()
            rule.runOnIdle { }
            samples += boundaryBelowFirstRow()
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        return samples + boundaryBelowFirstRow()
    }

    private fun assertSettledPinnedSlot(unreadOnly: Boolean) {
        if (unreadOnly) {
            assertBoundaryBetween("row.2", "row.4")
            assertTrue(top("row.0") >= bounds("chats.unread_filter").bottom)
        } else {
            assertBoundaryBetween("row.3", "row.4")
            rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
            assertEquals(top("chats.unread_surface"), top("row.0"), 0.5f)
        }
    }

    @Test fun belowThresholdPullReturnsRowsAndBoundaryTogether() {
        var toggles = 0
        rule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.size(300.dp, 500.dp)) {
                    ChatListUnreadPullSurface(false, true, true, { true }, { toggles++ }) {
                        PinnedRows(unreadOnly = false)
                    }
                }
            }
        }
        val rowStart = top("row.0")
        val boundaryStart = boundaryBelowFirstRow()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("chats.unread_surface").performTouchInput {
            down(Offset(width / 2f, height * 0.3f))
            moveBy(Offset(0f, 40f))
            up()
        }
        repeat(BOUNDARY_FRAMES) { frame ->
            rule.mainClock.advanceTimeByFrame()
            rule.runOnIdle { }
            assertEquals("boundary left its row at frame $frame", boundaryStart, boundaryBelowFirstRow(), 0.5f)
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(0, toggles)
        assertEquals(rowStart, top("row.0"), 0.5f)
        assertBoundaryBetween("row.3", "row.4")
    }

    /** Production keyed rows and boundary: ids 0..3 are pinned, even ids are unread. */
    @Composable
    private fun PinnedRows(unreadOnly: Boolean) {
        val visible = (0 until 20).filter { !unreadOnly || it % 2 == 0 }
        val boundary = pinnedBoundaryIndex(visible.map { it < 4 }, showArchived = false)
        LazyColumn {
            visible.forEachIndexed { targetIndex, id ->
                if (targetIndex == boundary) chatListPinnedBoundaryItem(CHAT_LIST_ROW_PLACEMENT_MILLIS)
                item(key = id) {
                    Text("Chat $id", chatListRowMotion(targetIndex).size(48.dp).testTag("row.$id"))
                }
            }
        }
    }

    private fun bounds(tag: String): Rect =
        rule
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun top(tag: String): Float = bounds(tag).top

    private fun boundaryBelowFirstRow(): Float = top(CHAT_LIST_PINNED_BOUNDARY_TAG) - top("row.0")

    private fun assertBoundaryBetween(
        above: String,
        below: String,
    ) {
        val line = bounds(CHAT_LIST_PINNED_BOUNDARY_TAG)
        assertTrue(line.top >= bounds(above).bottom - 0.5f)
        assertTrue(line.bottom <= top(below) + 0.5f)
    }

    /** The boundary glides with the rows: monotonic, no overshoot, no single-frame jump. */
    private fun assertBoundaryTravelled(samples: List<Float>) {
        val from = samples.first()
        val to = samples.last()
        val direction = Math.signum(to - from)
        assertTrue("boundary must change slot", direction != 0f)
        samples.zipWithNext().forEachIndexed { frame, (previous, next) ->
            assertTrue("boundary reversed at frame $frame", (next - previous) * direction >= -0.5f)
            assertTrue("boundary overshot at frame $frame", (to - next) * direction >= -0.5f)
            assertTrue(
                "boundary jumped at frame $frame: $previous -> $next",
                Math.abs(next - previous) < Math.abs(to - from) / 2f,
            )
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

    @Test fun scrolledEmptyStateReturnsToTheTopBeforeTheUnreadPullCanToggle() {
        var toggles = 0
        lateinit var scroll: ScrollState
        rule.setContent {
            WhiteNoiseTheme {
                val density = Density(LocalDensity.current.density, fontScale = 2f)
                CompositionLocalProvider(LocalDensity provides density) {
                    scroll = rememberScrollState()
                    val listState = rememberLazyListState()
                    Box(Modifier.size(300.dp, 200.dp)) {
                        ChatListUnreadPullSurface(
                            unreadOnly = true,
                            actionsAvailable = true,
                            gestureEnabled = true,
                            atTop = {
                                listState.unreadPullArmed(
                                    unreadOnly = true,
                                    listEmpty = true,
                                    hasEarlierChats = false,
                                    emptyPageScroll = scroll,
                                )
                            },
                            onUnreadOnlyChange = { toggles++ },
                        ) {
                            EmptyChats(onCreate = {}, onFirstRunHelp = {}, scrollState = scroll)
                        }
                    }
                }
            }
        }
        val surface = rule.onNodeWithTag("chats.unread_surface")
        rule.runOnIdle {
            scroll.dispatchRawDelta(24f)
            assertTrue(scroll.value > 0)
        }
        repeat(2) { pull ->
            surface.performTouchInput { swipe(center, Offset(center.x, height - 1f)) }
            rule.runOnIdle {
                assertEquals(0, scroll.value)
                assertEquals(pull, toggles)
            }
        }
    }

    private companion object {
        const val BOUNDARY_FRAMES = 30
    }
}
