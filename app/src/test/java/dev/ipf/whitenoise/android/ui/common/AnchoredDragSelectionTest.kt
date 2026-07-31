package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnchoredDragSelectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun anchoredRangeGrowsShrinksAndTransfersAcrossAnchor() {
        val ids = listOf("a", "b", "c", "d", "e")
        val eligible = ids.toSet()

        assertEquals(setOf("c", "d", "e"), anchoredDragSelection(ids, eligible, "c", "e"))
        assertEquals(setOf("c", "d"), anchoredDragSelection(ids, eligible, "c", "d"))
        assertEquals(setOf("a", "b", "c"), anchoredDragSelection(ids, eligible, "c", "a"))
    }

    @Test
    fun anchoredRangeSkipsIneligibleRowsWithoutBreakingTheInterval() {
        val ids = listOf("a", "system", "b", "deleted", "c")

        assertEquals(
            setOf("a", "b", "c"),
            anchoredDragSelection(ids, setOf("a", "b", "c"), "a", "c"),
        )
        assertTrue(anchoredDragSelection(ids, setOf("a", "b", "c"), "system", "c").isEmpty())
    }

    @Test
    fun endpointAndAutoScrollStayBoundedAtViewportEdges() {
        val visible =
            listOf(
                DragSelectionVisibleItem("a", 0f, 40f),
                DragSelectionVisibleItem("b", 40f, 90f),
            )
        assertEquals("a", dragSelectionEndpoint(visible, -20f))
        assertEquals("b", dragSelectionEndpoint(visible, 55f))
        assertEquals("b", dragSelectionEndpoint(visible, 120f))
        assertEquals(-12f, dragSelectionAutoScrollDelta(0f, 0f, 100f, 20f, 12f))
        assertEquals(0f, dragSelectionAutoScrollDelta(50f, 0f, 100f, 20f, 12f))
        assertEquals(12f, dragSelectionAutoScrollDelta(100f, 0f, 100f, 20f, 12f))
    }

    @Test
    fun completedHoldOpensActionButVerticalDragSuppressesIt() {
        var longPresses = 0
        var dragStarts = 0
        var dragMoves = 0
        var dragEnds = 0
        composeRule.setContent {
            Box(
                Modifier
                    .size(200.dp)
                    .testTag("gesture-target")
                    .longPressOrVerticalDrag(
                        onLongPressRelease = { longPresses++ },
                        onDragStart = { dragStarts++ },
                        onDrag = {
                            dragMoves++
                            true
                        },
                        onDragEnd = { dragEnds++ },
                        onGestureCancel = {},
                    ),
            )
        }

        composeRule.onNodeWithTag("gesture-target").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(1, longPresses)

        composeRule.onNodeWithTag("gesture-target").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(center.x, center.y + viewConfiguration.touchSlop + 24f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(1, longPresses)
        assertEquals(1, dragStarts)
        assertTrue(dragMoves >= 1)
        assertEquals(1, dragEnds)
    }

    @Test
    fun verticalDriftWithinTheAnchorStillResolvesAsAStationaryAction() {
        var longPresses = 0
        var cancels = 0
        composeRule.setContent {
            Box(
                Modifier
                    .size(200.dp)
                    .testTag("anchor-only-target")
                    .longPressOrVerticalDrag(
                        onLongPressRelease = { longPresses++ },
                        onDragStart = {},
                        onDrag = { false },
                        onDragEnd = {},
                        onGestureCancel = { cancels++ },
                    ),
            )
        }

        composeRule.onNodeWithTag("anchor-only-target").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(center.x, center.y + viewConfiguration.touchSlop + 24f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(1, longPresses)
        assertEquals(1, cancels)
    }

    @Test
    fun rangeGestureSurvivesAutoScrollPastItsOriginUntilRelease() {
        var dragEnds = 0
        var cancels = 0
        val ids = (0 until 30).toList()
        composeRule.setContent {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.size(width = 240.dp, height = 180.dp).testTag("range-list"),
            ) {
                items(ids, key = { it }) { id ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .testTag("range-row-$id")
                            .longPressOrVerticalDrag(
                                onLongPressRelease = {},
                                onDragStart = {},
                                onDrag = {
                                    // Model the screen-owned edge auto-scroll loop:
                                    // move the viewport far enough that the gesture's
                                    // originating row would normally be disposed.
                                    listState.requestScrollToItem(20)
                                    true
                                },
                                onDragEnd = { dragEnds++ },
                                onGestureCancel = { cancels++ },
                            ),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("range-row-0").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(center.x, center.y + viewConfiguration.touchSlop + 24f))
        }
        composeRule.waitForIdle()

        // The viewport moved, but the lazy item remains composed because the
        // active pointer pinned it. Its coroutine can therefore observe release.
        composeRule.onNodeWithTag("range-row-0").assertExists()
        composeRule.onNodeWithTag("range-list").performTouchInput { up() }
        composeRule.waitForIdle()

        assertEquals(1, dragEnds)
        assertEquals(0, cancels)
        composeRule.onNodeWithTag("range-row-0").assertDoesNotExist()
    }

    @Test
    fun navigationDisposalTerminatesAnActiveRangeWithoutPhantomState() {
        val showList = mutableStateOf(true)
        var dragActive = false
        var dragEnds = 0
        var cancels = 0
        composeRule.setContent {
            Box(Modifier.size(width = 240.dp, height = 180.dp).testTag("range-screen")) {
                if (showList.value) {
                    LazyColumn(Modifier.matchParentSize()) {
                        items((0 until 10).toList(), key = { it }) { id ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(60.dp)
                                    .testTag("cancel-row-$id")
                                    .longPressOrVerticalDrag(
                                        onLongPressRelease = {},
                                        onDragStart = { dragActive = true },
                                        onDrag = { true },
                                        onDragEnd = {
                                            dragActive = false
                                            dragEnds++
                                        },
                                        onGestureCancel = {
                                            dragActive = false
                                            cancels++
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("cancel-row-0").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(center.x, center.y + viewConfiguration.touchSlop + 24f))
        }
        composeRule.runOnIdle {
            assertEquals(0, dragEnds)
            assertEquals(0, cancels)
            assertTrue(dragActive)
            showList.value = false
        }
        composeRule.waitForIdle()

        assertEquals(1, dragEnds + cancels)
        assertEquals(false, dragActive)
        // Finish the test input stream after the detached row has retired its
        // own state; no callback should be delivered a second time.
        composeRule.onNodeWithTag("range-screen").performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals(1, dragEnds + cancels)
    }
}
