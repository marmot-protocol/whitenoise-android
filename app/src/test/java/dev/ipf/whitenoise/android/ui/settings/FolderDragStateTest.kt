package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Measured variable-height slots keep the visible insertion separate from the saved order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FolderDragStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Siblings leave the correct gap, while release commits once and retains the dragged frame. */
    @Test
    fun variableHeightInsertionSettlesWithoutReturningToTheOriginalSlot() {
        val ids = mutableStateOf(listOf("a", "b", "c"))
        val moves = mutableListOf<Pair<String, Int>>()
        lateinit var drag: FolderDragState
        renderFolders(ids) { drag = it }
        val a = composeRule.onNodeWithTag("a").fetchSemanticsNode().boundsInRoot
        val b = composeRule.onNodeWithTag("b").fetchSemanticsNode().boundsInRoot
        val c = composeRule.onNodeWithTag("c").fetchSemanticsNode().boundsInRoot
        composeRule.mainClock.autoAdvance = false
        publishChange {
            drag.start("a")
            drag.drag(c.center.y - a.center.y)
        }
        composeRule.mainClock.advanceTimeBy(160)
        composeRule.waitForIdle()
        assertEquals(listOf("a", "b", "c"), ids.value)
        val shifted = composeRule.onNodeWithTag("b").fetchSemanticsNode().boundsInRoot
        assertTrue(shifted.top < b.top)
        val beforeDrop = composeRule.onNodeWithTag("a").fetchSemanticsNode().boundsInRoot
        publishChange {
            drag.drop(ids.value) { id, delta ->
                moves.add(id to delta)
                ids.value = ids.value.toMutableList().apply { add(indexOf(id) + delta, removeAt(indexOf(id))) }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val afterDrop = composeRule.onNodeWithTag("a").fetchSemanticsNode().boundsInRoot
        assertEquals(beforeDrop.top, afterDrop.top, 1f)
        composeRule.mainClock.advanceTimeBy(240)
        composeRule.waitForIdle()
        assertEquals(listOf("a" to 2), moves)
        assertEquals(listOf("b", "c", "a"), ids.value)
        val settled = composeRule.onNodeWithTag("a").fetchSemanticsNode().boundsInRoot
        assertTrue(settled.top > composeRule.onNodeWithTag("c").fetchSemanticsNode().boundsInRoot.top)
    }

    /** A rejected synchronous save settles back to the real order rather than retaining a fictional insertion. */
    @Test
    fun rejectedSaveRetainsOrderAndReturnsToTheOriginalSlot() {
        val ids = mutableStateOf(listOf("a", "b", "c"))
        lateinit var drag: FolderDragState
        var moves = 0
        renderFolders(ids) { drag = it }
        val initial = composeRule.onNodeWithTag("a").fetchSemanticsNode().boundsInRoot
        val target = composeRule.onNodeWithTag("c").fetchSemanticsNode().boundsInRoot
        composeRule.mainClock.autoAdvance = false
        publishChange {
            drag.start("a")
            drag.drag(target.center.y - initial.center.y)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        publishChange { drag.drop(ids.value) { _, _ -> moves++ } }
        composeRule.mainClock.advanceTimeBy(240)
        composeRule.waitForIdle()
        publishChange {
            assertEquals(1, moves)
            assertEquals(listOf("a", "b", "c"), ids.value)
            assertNull(drag.folderId)
        }
        assertEquals(initial.top, composeRule.onNodeWithTag("a").fetchSemanticsNode().boundsInRoot.top, 0.5f)
    }

    /** Cancel and a newer authoritative order both retire the preview without a store write. */
    @Test
    fun cancellationAndExternalReplacementCannotCommitTheStaleDrag() {
        val ids = mutableStateOf(listOf("a", "b", "c"))
        lateinit var drag: FolderDragState
        var moves = 0
        renderFolders(ids) { drag = it }
        composeRule.mainClock.autoAdvance = false
        publishChange {
            drag.start("a")
            drag.drag(60f)
            drag.cancel()
            drag.drop(ids.value) { _, _ -> moves++ }
            assertEquals(listOf("a", "b", "c"), ids.value)
            drag.start("b")
            drag.drag(40f)
            ids.value = listOf("c", "a", "b")
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        publishChange {
            assertNull(drag.folderId)
            drag.drop(ids.value) { _, _ -> moves++ }
            assertEquals(0, moves)
            assertEquals(listOf("c", "a", "b"), ids.value)
        }
    }

    /** Publishes direct fixture writes before the caller advances its paused composition clock. */
    private fun publishChange(block: () -> Unit) {
        composeRule.runOnUiThread {
            block()
            Snapshot.sendApplyNotifications()
        }
    }

    /** Uses the production drag state and row modifier against measured, keyed, unequal-height slots. */
    private fun renderFolders(
        ids: State<List<String>>,
        report: (FolderDragState) -> Unit,
    ) {
        composeRule.setContent {
            val list = rememberLazyListState()
            val drag = rememberFolderDrag(list, ids.value)
            report(drag)
            LazyColumn(
                Modifier.size(240.dp, 300.dp),
                state = list,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ids.value, key = { it }) { id ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(if (id == "b") 80.dp else 48.dp)
                            .then(folderDragRowModifier(id, drag))
                            .testTag(id),
                    )
                }
            }
        }
    }
}
