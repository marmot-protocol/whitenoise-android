package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises quick transport against a real scrollable transcript and its production viewport lock. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w411dp-h891dp-420dpi")
class TtsQuickTransportViewportLockTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Staggered contact freezes the recognition viewport through commit, finger lift, and release. */
    @Test
    fun staggeredContactKeepsRecognitionViewportStableThroughRelease() {
        lateinit var listState: LazyListState
        var swipes = 0
        composeRule.setContent {
            listState =
                rememberLazyListState(
                    initialFirstVisibleItemIndex = 18,
                    initialFirstVisibleItemScrollOffset = 20,
                )
            Transcript(listState = listState, onSwipe = { swipes++ })
        }
        composeRule.waitForIdle()
        val initial = viewport(listState)

        composeRule.onNodeWithTag(LIST).performTouchInput {
            down(0, Offset(centerX - SPREAD, FIRST_DOWN_Y))
            moveTo(0, Offset(centerX - SPREAD, RECOGNITION_Y))
        }
        composeRule.waitForIdle()
        val beforeSecond = viewport(listState)
        assertNotEquals("the witness drag must move before recognition", initial, beforeSecond)

        composeRule.onNodeWithTag(LIST).performTouchInput {
            down(1, Offset(centerX + SPREAD, RECOGNITION_Y))
        }
        composeRule.waitForIdle()
        val recognition = viewport(listState)
        assertEquals(beforeSecond, recognition)

        listOf(8f, 16f, COMMITTING_TRAVEL).forEach { travel ->
            composeRule.onNodeWithTag(LIST).performTouchInput {
                moveTo(0, Offset(centerX - SPREAD, RECOGNITION_Y + travel))
                moveTo(1, Offset(centerX + SPREAD, RECOGNITION_Y + travel))
            }
            composeRule.waitForIdle()
            assertEquals("the viewport moved during two-finger travel $travel", recognition, viewport(listState))
        }
        composeRule.runOnIdle { assertEquals(1, swipes) }

        composeRule.onNodeWithTag(LIST).performTouchInput {
            up(0)
            moveTo(1, Offset(centerX + SPREAD, RECOGNITION_Y + COMMITTING_TRAVEL + 8f))
        }
        composeRule.waitForIdle()
        assertEquals("lifting one committed pointer handed motion to the list", recognition, viewport(listState))

        composeRule.onNodeWithTag(LIST).performTouchInput { up(1) }
        composeRule.waitForIdle()
        assertEquals("release introduced a snap or residual fling", recognition, viewport(listState))
        assertFalse("the transcript must be settled after release", listState.isScrollInProgress)
        composeRule.runOnIdle { assertEquals(1, swipes) }
    }

    /** Dropping and re-adding the second finger keeps one anchor and commits exactly once. */
    @Test
    fun precommitFingerDropAndReaddKeepsViewportStable() {
        lateinit var listState: LazyListState
        var swipes = 0
        composeRule.setContent {
            listState =
                rememberLazyListState(
                    initialFirstVisibleItemIndex = 10,
                    initialFirstVisibleItemScrollOffset = 30,
                )
            Transcript(listState = listState, onSwipe = { swipes++ })
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(LIST).performTouchInput {
            down(0, Offset(centerX - SPREAD, RECOGNITION_Y))
            down(1, Offset(centerX + SPREAD, RECOGNITION_Y))
        }
        composeRule.waitForIdle()
        val recognition = viewport(listState)

        composeRule.onNodeWithTag(LIST).performTouchInput {
            moveTo(0, Offset(centerX - SPREAD, RECOGNITION_Y + 8f))
            moveTo(1, Offset(centerX + SPREAD, RECOGNITION_Y + 8f))
            up(1)
            moveTo(0, Offset(centerX - SPREAD, RECOGNITION_Y + 18f))
            down(1, Offset(centerX + SPREAD, RECOGNITION_Y + 18f))
        }
        composeRule.waitForIdle()
        assertEquals(recognition, viewport(listState))

        composeRule.onNodeWithTag(LIST).performTouchInput {
            moveTo(0, Offset(centerX - SPREAD, RECOGNITION_Y + 18f + COMMITTING_TRAVEL))
            moveTo(1, Offset(centerX + SPREAD, RECOGNITION_Y + 18f + COMMITTING_TRAVEL))
            up(0)
            up(1)
        }
        composeRule.waitForIdle()

        assertEquals(recognition, viewport(listState))
        assertFalse(listState.isScrollInProgress)
        composeRule.runOnIdle { assertEquals(1, swipes) }
    }

    /** The first transcript item remains fixed through immediate two-finger recognition and release. */
    @Test
    fun topBoundaryRemainsStable() {
        assertBoundaryRemainsStable(initialIndex = 0, initialOffset = 0)
    }

    /** A viewport clamped against the transcript tail remains fixed through recognition and release. */
    @Test
    fun bottomBoundaryRemainsStable() {
        assertBoundaryRemainsStable(initialIndex = 59, initialOffset = 40)
    }

    /** Drives one complete quick-transport gesture at the requested transcript boundary. */
    private fun assertBoundaryRemainsStable(
        initialIndex: Int,
        initialOffset: Int,
    ) {
        lateinit var listState: LazyListState
        var swipes = 0
        composeRule.setContent {
            listState =
                rememberLazyListState(
                    initialFirstVisibleItemIndex = initialIndex,
                    initialFirstVisibleItemScrollOffset = initialOffset,
                )
            Transcript(listState = listState, onSwipe = { swipes++ })
        }
        composeRule.waitForIdle()
        val recognition = viewport(listState)

        composeRule.onNodeWithTag(LIST).performTouchInput {
            down(0, Offset(centerX - SPREAD, RECOGNITION_Y))
            down(1, Offset(centerX + SPREAD, RECOGNITION_Y))
            moveTo(0, Offset(centerX - SPREAD, RECOGNITION_Y + COMMITTING_TRAVEL))
            moveTo(1, Offset(centerX + SPREAD, RECOGNITION_Y + COMMITTING_TRAVEL))
            up(0)
            up(1)
        }
        composeRule.waitForIdle()

        assertEquals(recognition, viewport(listState))
        assertFalse(listState.isScrollInProgress)
        composeRule.runOnIdle { assertEquals(1, swipes) }
    }

    /** Builds the same parent-child arbitration used by the conversation transcript. */
    @Composable
    private fun Transcript(
        listState: LazyListState,
        onSwipe: () -> Unit,
    ) {
        val viewportLock = rememberTtsQuickTransportViewportLock(listState)
        LazyColumn(state = listState, modifier = Modifier.testTag(LIST)) {
            items((0 until 60).toList()) { index ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .twoFingerSwipeDown(
                            enabled = true,
                            viewportLock = viewportLock,
                            onSwipe = onSwipe,
                        ),
                ) { Text("message $index") }
            }
        }
    }

    /** Reads both coordinates atomically on the Compose thread. */
    private fun viewport(listState: LazyListState): ViewportSnapshot {
        lateinit var snapshot: ViewportSnapshot
        composeRule.runOnIdle {
            snapshot = ViewportSnapshot(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
        return snapshot
    }

    private data class ViewportSnapshot(
        val index: Int,
        val offset: Int,
    )

    private companion object {
        const val LIST = "quick-transport-transcript"
        const val FIRST_DOWN_Y = 320f
        const val RECOGNITION_Y = 272f
        const val COMMITTING_TRAVEL = 120f
        const val SPREAD = 30f
        const val centerX = 160f
    }
}
