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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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

    /** The end-only mention path crosses 50-plus mixed rows without composing the intervening history. */
    @Test
    fun unreadEndMentionFromFarHistoryKeepsCompositionBounded() {
        val composed = Collections.synchronizedSet(mutableSetOf<Int>())
        val finished = AtomicBoolean(false)
        val reached = AtomicBoolean(false)
        lateinit var coordinator: ConversationScrollCoordinator
        lateinit var listState: LazyListState
        lateinit var scope: CoroutineScope
        val target = 8
        composeRule.setContent {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 90)
            coordinator =
                remember(listState) { ConversationScrollCoordinator(LazyListConversationScrollWriter(listState)) }
            scope = rememberCoroutineScope()
            LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.height(240.dp)) {
                items((0 until ITEM_COUNT).toList(), key = { it }) { index ->
                    SideEffect { composed += index }
                    val height = if (index % 9 == 0) 320 else 40
                    Text("Message $index", Modifier.fillMaxWidth().height(height.dp).testTag("mention-row-$index"))
                }
            }
        }
        composeRule.waitForIdle()
        val initial = synchronized(composed) { composed.toSet() }
        composeRule.runOnIdle {
            scope.launch {
                reached.set(
                    coordinator.jumpToMentionReadingStart(
                        targetMessageId = "message-$target",
                        resolveTargetIndex = { target },
                        readLayout = { index ->
                            val layout = listState.layoutInfo
                            ConversationMentionJumpLayout(
                                layout.viewportEndOffset,
                                layout.visibleItemsInfo.firstOrNull { it.index == index }?.size,
                            )
                        },
                    ),
                )
                finished.set(true)
            }
        }
        composeRule.waitUntil(5_000) { finished.get() }
        composeRule.onNodeWithTag("mention-row-$target").assertIsDisplayed()
        composeRule.runOnIdle {
            val newRows = synchronized(composed) { composed.toSet() - initial }
            assertTrue(reached.get())
            assertTrue("only the bounded target region is composed: $newRows", newRows.all { it in 0..30 })
            assertTrue(newRows.size < 40)
        }
    }

    /**
     * A notification for an older retained message lands on its beginning in one hidden write, composing
     * only the target region instead of the history between the tail and that message.
     */
    @Test
    @Suppress("LongMethod") // One real-list setup shares its composition tracking with the bounded-region assertions.
    fun notificationLandingOnAnOlderTargetKeepsCompositionBoundedAndStartsAtTheTop() {
        val composed = Collections.synchronizedSet(mutableSetOf<Int>())
        val finished = AtomicBoolean(false)
        val placed = AtomicBoolean(false)
        lateinit var coordinator: ConversationScrollCoordinator
        lateinit var listState: LazyListState
        lateinit var scope: CoroutineScope
        composeRule.setContent {
            listState = rememberLazyListState()
            coordinator =
                remember(listState) { ConversationScrollCoordinator(LazyListConversationScrollWriter(listState)) }
            scope = rememberCoroutineScope()
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.height(240.dp).testTag("landing-list"),
            ) {
                items((0 until ITEM_COUNT).toList(), key = { it }) { index ->
                    SideEffect { composed += index }
                    val height = if (index == TARGET_INDEX) 480 else 40
                    Text("Message $index", Modifier.fillMaxWidth().height(height.dp).testTag("landing-row-$index"))
                }
            }
        }
        composeRule.waitForIdle()
        val initial = synchronized(composed) { composed.toSet() }
        composeRule.runOnIdle {
            scope.launch {
                val placement =
                    coordinator.commitInitialReadingStartAnchor(
                        targetMessageId = "message-$TARGET_INDEX",
                        resultingMode = ConversationScrollMode.ReadingHistory("message-$TARGET_INDEX", 0),
                        probe =
                            ConversationReadingStartProbe(
                                resolveTargetIndex = { TARGET_INDEX },
                                readLayout = { index ->
                                    val layout = listState.layoutInfo
                                    ConversationMentionJumpLayout(
                                        layout.viewportEndOffset,
                                        layout.visibleItemsInfo.firstOrNull { it.index == index }?.size,
                                    )
                                },
                                traceSections = false,
                            ),
                    )
                placed.set(placement != null)
                finished.set(true)
            }
        }
        composeRule.waitUntil(5_000) { finished.get() }
        composeRule.waitForIdle()
        val listTop =
            composeRule
                .onNodeWithTag("landing-list")
                .getUnclippedBoundsInRoot()
                .top.value
        val rowTop =
            composeRule
                .onNodeWithTag("landing-row-$TARGET_INDEX")
                .getUnclippedBoundsInRoot()
                .top.value
        assertEquals(listTop, rowTop, 1f)
        composeRule.runOnIdle {
            val newRows = synchronized(composed) { composed.toSet() - initial }
            assertTrue(placed.get())
            val boundedTargetWindow = (TARGET_INDEX - 20)..(TARGET_INDEX + 20)
            assertTrue(
                "only the bounded target region is composed: $newRows",
                newRows.all { it in boundedTargetWindow },
            )
            assertTrue(newRows.size < 40)
        }
    }

    /** Accepted sends from far history compose only the destination viewport, never intervening rows. */
    @Test
    fun acceptedSendFromFarHistorySnapsWithoutComposingInterveningRows() {
        val composed = Collections.synchronizedSet(mutableSetOf<Int>())
        val finished = AtomicBoolean(false)
        lateinit var coordinator: ConversationScrollCoordinator
        lateinit var listState: LazyListState
        lateinit var scope: CoroutineScope
        composeRule.setContent {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = TARGET_INDEX)
            coordinator =
                remember(listState) {
                    ConversationScrollCoordinator(
                        LazyListConversationScrollWriter(listState),
                        ConversationScrollMode.ReadingHistory("message-$TARGET_INDEX", 0),
                    )
                }
            scope = rememberCoroutineScope()
            LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.height(240.dp)) {
                items((0 until ITEM_COUNT).toList(), key = { it }) { index ->
                    SideEffect { composed += index }
                    Text("Message $index", Modifier.fillMaxWidth().height(40.dp).testTag("send-row-$index"))
                }
            }
        }
        composeRule.waitForIdle()
        val initial = synchronized(composed) { composed.toSet() }
        composeRule.runOnIdle {
            scope.launch {
                assertTrue(coordinator.revealSentAtLiveTail(resolveTailIndex = { 0 }))
                finished.set(true)
            }
        }
        composeRule.waitUntil(5_000) { finished.get() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("send-row-0").assertIsDisplayed()
        composeRule.runOnIdle {
            val newRows = synchronized(composed) { composed.toSet() - initial }
            assertEquals(0, listState.firstVisibleItemIndex)
            assertFalse(listState.canScrollBackward)
            assertTrue(coordinator.isFollowingTail)
            assertTrue(newRows.isNotEmpty())
            assertTrue("only the newest viewport is composed: $newRows", newRows.all { it in 0..20 })
            assertTrue(newRows.size < 40)
        }
    }

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
