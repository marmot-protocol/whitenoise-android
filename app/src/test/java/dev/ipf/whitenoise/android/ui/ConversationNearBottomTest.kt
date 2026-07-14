package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.conversation.isNearBottom
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationNearBottom
import dev.ipf.whitenoise.android.ui.conversation.rememberImeOpenReanchorNearBottom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the conversation near-bottom derived state (issue #1253) and the
 * IME-open bottom-chase gate (issue #1375).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationNearBottomTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Composable
    private fun TimelineHarness(
        listState: LazyListState,
        timelineSize: Int,
        modifier: Modifier = Modifier.height(400.dp),
    ) {
        LazyColumn(modifier = modifier, state = listState) {
            item { Spacer(Modifier.height(1.dp)) }
            item { Spacer(Modifier.height(1.dp)) }
            items((0 until timelineSize).toList()) {
                Box(Modifier.fillMaxWidth().height(50.dp))
            }
            item { Spacer(Modifier.height(1.dp)) }
        }
    }

    @Composable
    private fun ImeOpenChaseHarness(
        listState: LazyListState,
        timelineSize: Int,
        imeIsOpen: Boolean,
        initialTimelineAnchored: Boolean,
        liveNearBottom: Boolean,
        chaseCount: IntArray,
    ) {
        val gateNearBottom =
            rememberImeOpenReanchorNearBottom(
                chatId = "chat-under-test",
                imeIsOpen = imeIsOpen,
                nearBottom = liveNearBottom,
            )

        // Mirrors ConversationScreen's keys and guards; one snap is enough to
        // prove whether the production 24-frame chase started.
        LaunchedEffect(imeIsOpen, initialTimelineAnchored) {
            if (!imeIsOpen || !initialTimelineAnchored || !gateNearBottom) return@LaunchedEffect
            chaseCount[0]++
            val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            listState.scrollToItem(last)
        }

        TimelineHarness(listState = listState, timelineSize = timelineSize)
    }

    @Test
    fun nearBottomTracksTimelineHydrationWhenScrolledUp() {
        val listState = LazyListState()
        val timelineSize = mutableStateOf(0)
        val nearBottomHolder = arrayOf<Boolean?>(null)

        composeRule.setContent {
            nearBottomHolder[0] =
                rememberConversationNearBottom(
                    listState = listState,
                    renderedTimelineSize = timelineSize.value,
                    hasOlderHeader = true,
                )
            TimelineHarness(
                listState = listState,
                timelineSize = 50,
                modifier = Modifier.height(100.dp),
            )
        }

        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            timelineSize.value = 50
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            runBlocking {
                listState.scrollToItem(20)
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            assertFalse(
                "Jump FAB should show when scrolled up after timeline hydration",
                nearBottomHolder[0]!!,
            )
        }
    }

    @Test
    fun imeOpenGateRetainsHistoryStateThroughTransientLayoutRead() {
        val imeOpen = mutableStateOf(false)
        val nearBottom = mutableStateOf(false)
        val gateHolder = arrayOf<Boolean?>(null)

        composeRule.setContent {
            gateHolder[0] =
                rememberImeOpenReanchorNearBottom(
                    chatId = "chat-under-test",
                    imeIsOpen = imeOpen.value,
                    nearBottom = nearBottom.value,
                )
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            // IME resize can transiently make live near-bottom true on the same
            // snapshot edge that reports the keyboard open.
            nearBottom.value = true
            imeOpen.value = true
        }
        composeRule.waitForIdle()

        assertFalse(gateHolder[0]!!)
    }

    @Test
    fun imeOpenChasePreservesHistoryScrollPosition() {
        val timelineSize = 50
        val listState = LazyListState()
        val imeOpen = mutableStateOf(false)
        val liveNearBottom = mutableStateOf(false)
        val chaseCount = intArrayOf(0)

        composeRule.setContent {
            ImeOpenChaseHarness(
                listState = listState,
                timelineSize = timelineSize,
                imeIsOpen = imeOpen.value,
                initialTimelineAnchored = true,
                liveNearBottom = liveNearBottom.value,
                chaseCount = chaseCount,
            )
        }

        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            runBlocking {
                listState.scrollToItem(20)
            }
        }
        composeRule.waitForIdle()

        val indexBeforeIme = listState.firstVisibleItemIndex
        assertFalse(isNearBottom(listState, timelineSize, hasOlderHeader = true))

        composeRule.runOnUiThread {
            // Explicitly model the live layout race without violating the
            // production invariant: all 50 timeline rows are still rendered.
            liveNearBottom.value = true
            imeOpen.value = true
        }
        composeRule.waitForIdle()

        assertEquals(0, chaseCount[0])
        assertEquals(indexBeforeIme, listState.firstVisibleItemIndex)
    }

    @Test
    fun imeOpenGateRetainsPreImeBottomState() {
        val imeOpen = mutableStateOf(false)
        val nearBottom = mutableStateOf(true)
        val gateHolder = arrayOf<Boolean?>(null)

        composeRule.setContent {
            gateHolder[0] =
                rememberImeOpenReanchorNearBottom(
                    chatId = "chat-under-test",
                    imeIsOpen = imeOpen.value,
                    nearBottom = nearBottom.value,
                )
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            nearBottom.value = false
            imeOpen.value = true
        }
        composeRule.waitForIdle()

        assertTrue(gateHolder[0]!!)
    }

    @Test
    fun imeOpenGateResetsForChatOpenedWithKeyboardUp() {
        val chatId = mutableStateOf("first-chat")
        val imeOpen = mutableStateOf(false)
        val nearBottom = mutableStateOf(false)
        val gateHolder = arrayOf<Boolean?>(null)

        composeRule.setContent {
            gateHolder[0] =
                rememberImeOpenReanchorNearBottom(
                    chatId = chatId.value,
                    imeIsOpen = imeOpen.value,
                    nearBottom = nearBottom.value,
                )
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            nearBottom.value = true
            imeOpen.value = true
        }
        composeRule.waitForIdle()
        assertFalse(gateHolder[0]!!)

        composeRule.runOnUiThread {
            chatId.value = "second-chat"
        }
        composeRule.waitForIdle()

        assertTrue(
            "A new chat first seen with IME open must follow live near-bottom",
            gateHolder[0]!!,
        )
    }
}
