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

/** Device-backed regression coverage for transcript scroll and quick-transport arbitration. */
class TtsQuickTransportViewportAndroidTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A staggered first-pointer drag anchors at recognition and has no post-release velocity. */
    @Test
    fun staggeredContactHasNoTwitchOrReleaseFling() {
        lateinit var listState: LazyListState
        var swipes = 0
        composeRule.setContent {
            listState =
                rememberLazyListState(
                    initialFirstVisibleItemIndex = 20,
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
        val recognition = viewport(listState)
        assertNotEquals("the first pointer must exercise real transcript scroll", initial, recognition)

        composeRule.onNodeWithTag(LIST).performTouchInput {
            down(1, Offset(centerX + SPREAD, RECOGNITION_Y))
        }
        composeRule.waitForIdle()
        assertEquals(recognition, viewport(listState))

        listOf(8f, 18f, COMMITTING_TRAVEL).forEach { travel ->
            composeRule.onNodeWithTag(LIST).performTouchInput {
                moveTo(0, Offset(centerX - SPREAD, RECOGNITION_Y + travel))
                moveTo(1, Offset(centerX + SPREAD, RECOGNITION_Y + travel))
            }
            composeRule.waitForIdle()
            assertEquals("transcript moved at travel=$travel", recognition, viewport(listState))
        }
        composeRule.runOnIdle { assertEquals(1, swipes) }

        composeRule.onNodeWithTag(LIST).performTouchInput {
            up(0)
            moveTo(1, Offset(centerX + SPREAD, RECOGNITION_Y + COMMITTING_TRAVEL + 12f))
            up(1)
        }
        composeRule.waitForIdle()

        assertEquals("release snapped or flung the transcript", recognition, viewport(listState))
        assertFalse("release left transcript scrolling", listState.isScrollInProgress)
        composeRule.runOnIdle { assertEquals(1, swipes) }
    }

    /** Cancellation restores the anchor and releases ownership for the next ordinary one-finger scroll. */
    @Test
    fun cancellationReleasesViewportForNextGesture() {
        lateinit var listState: LazyListState
        var swipes = 0
        composeRule.setContent {
            listState =
                rememberLazyListState(
                    initialFirstVisibleItemIndex = 12,
                    initialFirstVisibleItemScrollOffset = 12,
                )
            Transcript(listState = listState, onSwipe = { swipes++ })
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(LIST).performTouchInput {
            down(0, Offset(centerX - SPREAD, RECOGNITION_Y))
            down(1, Offset(centerX + SPREAD, RECOGNITION_Y))
            moveTo(0, Offset(centerX - SPREAD, RECOGNITION_Y + 12f))
            moveTo(1, Offset(centerX + SPREAD, RECOGNITION_Y + 12f))
        }
        composeRule.waitForIdle()
        val recognition = viewport(listState)

        composeRule.onNodeWithTag(LIST).performTouchInput { cancel() }
        composeRule.waitForIdle()
        assertEquals("cancellation changed the recognition anchor", recognition, viewport(listState))
        assertFalse("cancellation left the lock active", listState.isScrollInProgress)
        composeRule.runOnIdle { assertEquals(0, swipes) }

        composeRule.onNodeWithTag(LIST).performTouchInput {
            down(0, Offset(centerX, FIRST_DOWN_Y))
            moveTo(0, Offset(centerX, RECOGNITION_Y - 80f))
            up(0)
        }
        composeRule.waitForIdle()
        assertNotEquals("the next one-finger gesture must scroll normally", recognition, viewport(listState))
    }

    /** Renders a production-shaped lazy transcript with the real detector and conversation-owned lock. */
    @Composable
    private fun Transcript(
        listState: LazyListState,
        onSwipe: () -> Unit,
    ) {
        val viewportLock = rememberTtsQuickTransportViewportLock(listState)
        LazyColumn(state = listState, modifier = Modifier.testTag(LIST)) {
            items((0 until 70).toList()) { index ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .twoFingerSwipeDown(
                            enabled = true,
                            viewportLock = viewportLock,
                            onSwipe = onSwipe,
                        ),
                ) { Text("conversation message $index") }
            }
        }
    }

    /** Reads a coherent viewport tuple on the instrumentation Compose thread. */
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
        const val LIST = "device-quick-transport-transcript"
        const val FIRST_DOWN_Y = 360f
        const val RECOGNITION_Y = 300f
        const val COMMITTING_TRAVEL = 120f
        const val SPREAD = 36f
        const val centerX = 180f
    }
}
