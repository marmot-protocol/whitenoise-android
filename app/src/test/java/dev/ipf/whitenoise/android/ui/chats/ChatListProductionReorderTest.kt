package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production text/avatar/unread row layout participates in the actual keyed motion and viewport owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatListProductionReorderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val original = listOf("A", "B", "C", "D", "E", "F")

    /** Real preview/metadata measurement must not reverse a middle promotion's path. */
    @Test
    fun realMiddleRowAndDisplacedRowsMoveMonotonically() {
        var ids by mutableStateOf(original)
        val state = mountRows { ids }
        composeRule.waitForIdle()
        val tops = original.associateWith(::top).toMutableMap()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { ids = listOf("E", "A", "B", "C", "D", "F") }
        repeat(60) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            val promotedTop = top("E")
            assertTrue("promoted real row reversed at frame $frame", promotedTop <= tops.getValue("E") + 0.5f)
            tops["E"] = promotedTop
            listOf("A", "B", "C", "D").forEach { id ->
                val rowTop = top(id)
                assertTrue("displaced $id reversed at frame $frame", rowTop + 0.5f >= tops.getValue(id))
                tops[id] = rowTop
            }
        }
        assertEquals(0, state().firstVisibleItemIndex)
        assertEquals(0, state().firstVisibleItemScrollOffset)
        assertEquals(0f, top("E"), 0.5f)
    }

    /** A second promotion supersedes a running correction without losing the newest head. */
    @Test
    fun burstPromotionsSettleAtTheNewestRealRowWithoutAnotherSnap() {
        var ids by mutableStateOf(original)
        val state = mountRows { ids }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { ids = listOf("E", "A", "B", "C", "D", "F") }
        repeat(3) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
        }
        val firstFTop = top("F")
        composeRule.runOnUiThread { ids = listOf("F", "E", "A", "B", "C", "D") }
        var previousFTop = firstFTop
        repeat(60) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            val current = top("F")
            assertTrue("newest head reversed at frame $frame", current <= previousFTop + 0.5f)
            previousFTop = current
        }
        assertEquals(0, state().firstVisibleItemIndex)
        assertEquals(0, state().firstVisibleItemScrollOffset)
        assertEquals(0f, top("F"), 0.5f)
        val settled = original.associateWith(::top)
        repeat(5) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.runOnIdle { }
        original.forEach { id -> assertEquals("late correction moved $id", settled.getValue(id), top(id), 0.5f) }
    }

    /** Mounts real ChatRow under the production motion/gate harness, using only local projections. */
    private fun mountRows(ids: () -> List<String>): () -> LazyListState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appState = ChatRowPortFixtures.state(context)
        val rows = original.associateWith(::realChatListMotionRow)
        var listState: LazyListState? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.fillMaxSize()) {
                    val currentListState = rememberLazyListState()
                    listState = currentListState
                    ChatListHeadReorderMotionHarness(
                        itemIds = ids(),
                        listState = currentListState,
                        rowHeight = 48.dp,
                        rowContent = { id, modifier, enabled ->
                            // Measure the whole lazy item, outside Material's internal content padding.
                            Box(modifier) {
                                ChatRow(
                                    item = rows.getValue(id),
                                    appState = appState,
                                    onClick = {},
                                    onOpenProfile = {},
                                    interactionsEnabled = enabled,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                    )
                }
            }
        }
        return { checkNotNull(listState) }
    }

    /** Reads current physical bounds rather than a target index or a synthetic animation value. */
    private fun top(id: String): Float {
        val node = composeRule.onNodeWithTag(chatListHeadReorderRowTag(id), useUnmergedTree = true).fetchSemanticsNode()
        return node
            .layoutInfo
            .coordinates
            .boundsInRoot()
            .top
    }
}

/** Unique local identities and actual preview/unread states exercise normal row measurement. */
internal fun realChatListMotionRow(id: String): ChatListItem {
    val base = ChatRowPortFixtures.item(unread = id == "E", preview = "Preview $id: ${ChatRowPortFixtures.PREVIEW}")
    val projection = checkNotNull(base.projection)
    return base.copy(
        group = base.group.copy(groupIdHex = id, name = "Conversation $id"),
        projection =
            projection.copy(
                groupIdHex = id,
                title = "Conversation $id",
                groupName = "Conversation $id",
                lastMessage = projection.lastMessage?.copy(timelineAt = 0uL),
            ),
    )
}
