package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatListUnreadViewportTest {
    @get:Rule val rule = createComposeRule()

    /** A cleared old filter has no authority over another account/runtime/folder/search/archive viewport. */
    @Test fun replacingTheDatasetInvalidatesTheUnreadExitReset() {
        val dataset = mutableStateOf(ChatListDatasetKey(false, null, "", "account", 0))
        val unread = mutableStateOf(true)
        val searchOpen = mutableStateOf(false)
        var resets = 0
        var viewport: LazyListState? = null
        rule.setContent {
            val state = rememberLazyListState(initialFirstVisibleItemIndex = 10).also { viewport = it }
            ChatListUnreadTopResetEffect(state, dataset.value, unread.value, searchOpen.value) { resets++ }
            LazyColumn(Modifier.testTag("viewport"), state = state) {
                items(40) { Text("Row $it", Modifier.height(64.dp)) }
            }
        }
        val original = dataset.value
        val replacements =
            listOf(
                original.copy(accountRef = "another"),
                original.copy(runtimeGeneration = 1),
                original.copy(folderId = "folder"),
                original.copy(query = "query"),
                original.copy(showArchived = true),
            )
        replacements.forEach { next ->
            rule.runOnIdle { unread.value = true }
            rule.runOnIdle {
                dataset.value = next
                unread.value = false
            }
            rule.waitForIdle()
            assertEquals(0, resets)
            assertEquals(10, checkNotNull(viewport).firstVisibleItemIndex)
        }
        rule.runOnIdle { unread.value = true }
        rule.runOnIdle {
            searchOpen.value = true
            unread.value = false
        }
        rule.waitForIdle()
        assertEquals(0, resets)
        assertEquals(10, checkNotNull(viewport).firstVisibleItemIndex)
        rule.runOnIdle {
            searchOpen.value = false
            unread.value = true
        }
        rule.runOnIdle { unread.value = false }
        rule.waitForIdle()
        assertEquals(1, resets)
        assertEquals(0, checkNotNull(viewport).firstVisibleItemIndex)
        rule.onNodeWithTag("viewport").assertExists()
    }
}
