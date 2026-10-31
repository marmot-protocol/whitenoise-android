package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mechanism behind #3529. ChatsScreen keeps its LazyListState while the New Message flow
 * replaces the list, so a chat inserted above the old head lands above the viewport on return
 * unless the return snap moves the list back to index 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListDetachedHeadAnchorTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A row inserted while the list is off screen stays hidden above the retained keyed anchor. */
    @Test
    fun aRowInsertedWhileTheListIsDetachedReturnsAboveTheViewport() {
        val harness = detachInsertAndReturn(snapOnReturn = false)

        assertEquals(1, harness.listState.firstVisibleItemIndex)
        assertEquals("old-head", harness.firstVisibleKey())
    }

    /** The return snap brings the inserted row into view as the first visible item. */
    @Test
    fun theReturnSnapShowsTheInsertedRowAtTheTop() {
        val harness = detachInsertAndReturn(snapOnReturn = true)

        assertEquals(0, harness.listState.firstVisibleItemIndex)
        assertEquals("new-group", harness.firstVisibleKey())
    }

    /** Shows the list, removes it, inserts a row above the old head, optionally snaps, and shows it again. */
    private fun detachInsertAndReturn(snapOnReturn: Boolean): Harness {
        val harness = Harness()
        composeRule.setContent {
            if (harness.listShown) {
                LazyColumn(state = harness.listState) {
                    items(harness.rows, key = { it }) { row ->
                        Text(row, Modifier.fillMaxWidth().height(72.dp))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals("old-head", harness.firstVisibleKey())

        harness.listShown = false
        composeRule.waitForIdle()
        harness.rows = listOf("new-group") + harness.rows
        if (snapOnReturn) harness.listState.requestScrollToItem(0)
        harness.listShown = true
        composeRule.waitForIdle()
        return harness
    }

    /** One list whose state outlives its LazyColumn, as ChatsScreen's does behind New Message. */
    private class Harness {
        val listState = LazyListState()
        var listShown by mutableStateOf(true)
        var rows by mutableStateOf(listOf("old-head") + List(20) { "chat-$it" })

        fun firstVisibleKey(): Any? =
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull()
                ?.key
    }
}
