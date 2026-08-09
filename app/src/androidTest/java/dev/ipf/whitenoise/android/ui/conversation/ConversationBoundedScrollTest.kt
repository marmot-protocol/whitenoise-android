package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class ConversationBoundedScrollTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun farJumpDoesNotComposeTheInterveningHistoryAndSettlesAtTheTarget() {
        val composedIndices = Collections.synchronizedSet(mutableSetOf<Int>())
        val jumpFinished = AtomicBoolean(false)
        val jumpCompleted = AtomicBoolean(false)
        lateinit var coordinator: ConversationScrollCoordinator
        lateinit var listState: LazyListState
        lateinit var scope: CoroutineScope

        composeRule.setContent {
            listState = rememberLazyListState()
            coordinator =
                remember(listState) {
                    ConversationScrollCoordinator(LazyListConversationScrollWriter(listState))
                }
            scope = rememberCoroutineScope()
            LazyColumn(
                state = listState,
                modifier = Modifier.height(240.dp),
            ) {
                items((0 until ITEM_COUNT).toList(), key = { it }) { index ->
                    SideEffect { composedIndices += index }
                    Text(
                        text = "Message $index",
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .testTag("row-$index"),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val initiallyComposed = synchronized(composedIndices) { composedIndices.toSet() }

        composeRule.runOnIdle {
            scope.launch {
                jumpCompleted.set(
                    coordinator.programmaticJump(
                        targetMessageId = "message-$TARGET_INDEX",
                        reason = ConversationScrollReason.Search,
                    ) {
                        animateScrollToItem(TARGET_INDEX)
                    },
                )
                jumpFinished.set(true)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { jumpFinished.get() }
        composeRule.waitForIdle()

        assertBoundedJumpResult(
            listState = listState,
            jumpCompleted = jumpCompleted,
            initiallyComposed = initiallyComposed,
            composedIndices = composedIndices,
        )
    }

    private fun assertBoundedJumpResult(
        listState: LazyListState,
        jumpCompleted: AtomicBoolean,
        initiallyComposed: Set<Int>,
        composedIndices: Set<Int>,
    ) {
        composeRule.onNodeWithTag("row-$TARGET_INDEX").assertIsDisplayed()
        composeRule.runOnIdle {
            val newlyComposed = synchronized(composedIndices) { composedIndices.toSet() - initiallyComposed }
            val boundedTargetWindow = (TARGET_INDEX - 20)..(TARGET_INDEX + 20)
            assertTrue("the coordinator command was cancelled", jumpCompleted.get())
            assertEquals(TARGET_INDEX, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
            assertFalse("no rows were composed for the jump", newlyComposed.isEmpty())
            assertTrue(
                "bounded jump composed rows outside $boundedTargetWindow: $newlyComposed",
                newlyComposed.all { it in boundedTargetWindow },
            )
            assertTrue("bounded jump composed ${newlyComposed.size} new rows", newlyComposed.size < 40)
        }
    }

    private companion object {
        const val ITEM_COUNT = 2_000
        const val TARGET_INDEX = 1_900
    }
}
