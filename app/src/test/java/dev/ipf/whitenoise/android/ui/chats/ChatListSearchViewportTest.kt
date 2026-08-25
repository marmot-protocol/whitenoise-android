package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListSearchViewportTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun delayedMessagesKeepAnUntouchedSearchAtTheTop() {
        var messageCount by mutableIntStateOf(0)
        val listStateHolder = arrayOf<LazyListState?>(null)

        composeRule.setContent {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = 6)
            listStateHolder[0] = listState
            ChatListSearchTopResetEffect(
                listState = listState,
                datasetKey = ChatListDatasetKey(false, null, "alpha"),
                searchActive = true,
            )
            SearchViewportList(listState = listState, messageCount = messageCount)
        }

        composeRule.waitForIdle()
        assertEquals(0, listStateHolder[0]!!.firstVisibleItemIndex)
        assertEquals(0, listStateHolder[0]!!.firstVisibleItemScrollOffset)

        composeRule.runOnUiThread { messageCount = 8 }
        composeRule.waitForIdle()

        assertEquals(0, listStateHolder[0]!!.firstVisibleItemIndex)
        assertEquals(0, listStateHolder[0]!!.firstVisibleItemScrollOffset)
    }

    @Test
    fun newQueryResetsTopButDelayedMessagesPreserveDeliberateAnchorAndOffset() {
        var query by mutableStateOf("alpha")
        var messageCount by mutableIntStateOf(0)
        val listStateHolder = arrayOf<LazyListState?>(null)

        composeRule.setContent {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = 8)
            listStateHolder[0] = listState
            val datasetKey = remember(query) { ChatListDatasetKey(false, null, query) }
            ChatListSearchTopResetEffect(
                listState = listState,
                datasetKey = datasetKey,
                searchActive = query.isNotBlank(),
            )
            SearchViewportList(listState = listState, messageCount = messageCount)
        }

        composeRule.waitForIdle()
        assertEquals(0, listStateHolder[0]!!.firstVisibleItemIndex)

        composeRule.runOnIdle { listStateHolder[0]!!.requestScrollToItem(9, 13) }
        composeRule.waitForIdle()
        val anchorKey =
            listStateHolder[0]!!
                .layoutInfo.visibleItemsInfo
                .first()
                .key
        val anchorOffset = listStateHolder[0]!!.firstVisibleItemScrollOffset

        composeRule.runOnUiThread { messageCount = 8 }
        composeRule.waitForIdle()

        assertEquals(
            anchorKey,
            listStateHolder[0]!!
                .layoutInfo.visibleItemsInfo
                .first()
                .key,
        )
        assertEquals(anchorOffset, listStateHolder[0]!!.firstVisibleItemScrollOffset)

        composeRule.runOnUiThread { query = "beta" }
        composeRule.waitForIdle()
        assertEquals(0, listStateHolder[0]!!.firstVisibleItemIndex)
    }
}

@androidx.compose.runtime.Composable
private fun SearchViewportList(
    listState: LazyListState,
    messageCount: Int,
) {
    LazyColumn(state = listState) {
        item(key = "groups-header") { Text("Groups", Modifier.height(40.dp)) }
        items((0 until 20).toList(), key = { "group-$it" }) {
            Text("Group $it", Modifier.height(48.dp))
        }
        if (messageCount > 0) {
            item(key = "messages-header") { Text("Messages", Modifier.height(40.dp)) }
            items((0 until messageCount).toList(), key = { "message-$it" }) {
                Text("Message $it", Modifier.height(48.dp))
            }
        }
    }
}
