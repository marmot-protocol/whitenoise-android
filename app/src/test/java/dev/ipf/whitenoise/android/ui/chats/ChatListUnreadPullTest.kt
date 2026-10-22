package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatListUnreadPullTest {
    @get:Rule val rule = createComposeRule()

    @Test fun deliberateTopPullActivatesOnceOnRelease() {
        var activations = 0
        rule.setContent {
            Box(
                Modifier
                    .size(300.dp)
                    .testTag("list")
                    .chatListUnreadPull(true, { true }, 72f) { activations++ },
            )
        }
        rule.onNodeWithTag("list").performTouchInput {
            down(Offset(100f, 20f))
            moveTo(Offset(100f, 140f))
            assertEquals(0, activations)
            up()
        }
        assertEquals(1, activations)
    }

    @Test fun pullBeginningAwayFromTopCannotArmWhenTopIsReached() {
        var atTop = false
        val sampledTopStates = mutableListOf<Boolean>()
        var activations = 0
        rule.setContent {
            Box(
                Modifier
                    .size(300.dp)
                    .testTag("list")
                    .chatListUnreadPull(true, {
                        sampledTopStates += atTop
                        atTop
                    }, 72f) { activations++ },
            )
        }
        // performTouchInput queues its events until the block returns. Flush the initial down
        // before changing the viewport, so the recognizer really observes a gesture below the top.
        rule.onNodeWithTag("list").performTouchInput {
            down(Offset(100f, 20f))
            moveTo(Offset(100f, 50f))
        }
        rule.runOnIdle {
            assertEquals(listOf(false), sampledTopStates)
            atTop = true
        }
        rule.onNodeWithTag("list").performTouchInput {
            moveTo(Offset(100f, 180f))
            up()
        }
        rule.runOnIdle {
            assertEquals(0, activations)
            assertEquals(listOf(false), sampledTopStates)
        }
        // A fresh gesture may use the new viewport, but the preceding held gesture could not rearm.
        rule.onNodeWithTag("list").performTouchInput {
            down(Offset(100f, 20f))
            moveTo(Offset(100f, 180f))
            up()
        }
        rule.runOnIdle {
            assertEquals(listOf(false, true), sampledTopStates)
            assertEquals(1, activations)
        }
    }

    @Test fun topPullWorksWithRealScrollableList() {
        var activations = 0
        rule.setContent {
            val state = rememberLazyListState()
            LazyColumn(
                Modifier
                    .size(300.dp)
                    .testTag("list")
                    .chatListUnreadPull(true, {
                        !state.isScrollInProgress &&
                            state.firstVisibleItemIndex == 0 &&
                            state.firstVisibleItemScrollOffset == 0
                    }, 72f) { activations++ },
                state = state,
            ) {
                items(100) { Text("Chat $it", Modifier.size(48.dp)) }
            }
        }
        rule.onNodeWithTag("list").performTouchInput {
            down(Offset(100f, 20f))
            moveTo(Offset(100f, 80f))
            moveTo(Offset(100f, 160f))
            up()
        }
        assertEquals(1, activations)
    }

    @Test fun heldPullCannotActivateAfterFolderScopeChangesAndProgressClears() {
        val folder = mutableStateOf("first")
        var activations = 0
        var progress = 0f
        rule.setContent {
            LazyColumn(
                Modifier
                    .size(300.dp)
                    .testTag("list")
                    .chatListUnreadPull(true, { true }, 72f, { progress = it }, scopeKey = folder.value) {
                        activations++
                    },
            ) {
                items(100) { Text("Chat $it", Modifier.size(48.dp)) }
            }
        }
        rule.onNodeWithTag("list").performTouchInput {
            down(Offset(100f, 20f))
            moveTo(Offset(100f, 140f))
        }
        rule.runOnIdle {
            assertEquals(1f, progress)
            folder.value = "second"
        }
        rule.waitForIdle()
        rule.onNodeWithTag("list").performTouchInput { up() }
        rule.runOnIdle {
            assertEquals(0, activations)
            assertEquals(0f, progress)
        }
    }

    @Test fun untouchedLongPressCannotTurnIntoUnreadPullOnRangeMovement() {
        var activations = 0
        var progress = 0f
        rule.setContent {
            LazyColumn(
                Modifier
                    .size(300.dp)
                    .testTag("list")
                    .chatListUnreadPull(true, { true }, 72f, { progress = it }) { activations++ },
            ) {
                items(100) { Text("Chat $it", Modifier.size(48.dp)) }
            }
        }
        rule.onNodeWithTag("list").performTouchInput {
            down(Offset(100f, 20f))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(100f, 20f))
            moveTo(Offset(100f, 180f))
            up()
        }
        rule.runOnIdle {
            assertEquals(0, activations)
            assertEquals(0f, progress)
        }
    }

    @Test fun disabledPullPreservesInput() {
        var activations = 0
        rule.setContent {
            Box(
                Modifier
                    .size(300.dp)
                    .testTag("list")
                    .chatListUnreadPull(false, { true }, 72f) { activations++ },
            )
        }
        rule.onNodeWithTag("list").performTouchInput {
            down(Offset(100f, 20f))
            moveTo(Offset(100f, 180f))
            up()
        }
        assertEquals(0, activations)
    }
}
