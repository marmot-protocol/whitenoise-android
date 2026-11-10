package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real lazy measurement and ranking exercise the transition from names to completed body results. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class GlobalSearchStagedViewportTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun remountedSameDatasetRetainsDeepMessageAnchorUntilBodyRowsReturn() {
        var loading by mutableStateOf(true)
        val dataset = ChatListDatasetKey(false, null, "name")
        val savedOwner = ChatListSearchViewportState(dataset)
        val listState = LazyListState(firstVisibleItemIndex = 40, firstVisibleItemScrollOffset = 9)
        composeRule.setContent {
            val preserve =
                rememberSearchBodyAnchorRestoration(
                    datasetKey = dataset,
                    sameDataset = savedOwner.appliedDatasetKey == dataset,
                    firstVisibleIndex = listState.firstVisibleItemIndex,
                    firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                    loading = loading,
                )
            ChatListSearchTopResetEffect(listState, dataset, searchActive = true, viewportState = savedOwner)
            if (blockLocalSearchRows(loading, hasLocalRows = true, preservePendingAnchor = preserve)) {
                Text("Restoring message results")
            } else {
                LazyColumn(state = listState, modifier = Modifier.height(200.dp)) {
                    items((0 until 3).toList(), key = { "name-$it" }) { Text("Name $it", Modifier.height(48.dp)) }
                    items((0 until 60).toList(), key = { "body-$it" }) { Text("Body $it", Modifier.height(48.dp)) }
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(40, listState.firstVisibleItemIndex)
        composeRule.runOnIdle { loading = false }
        composeRule.waitForIdle()
        assertEquals(
            "body-37",
            listState.layoutInfo.visibleItemsInfo
                .first()
                .key,
        )
        assertEquals(9, listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun restoredAnchorWaitsForItsFirstLoadButDoesNotHideNamesOnRetry() {
        val restoredDataset = ChatListDatasetKey(false, null, "name")
        var dataset by mutableStateOf(restoredDataset)
        var loading by mutableStateOf(false)
        var mounted by mutableStateOf(true)
        composeRule.setContent {
            if (mounted) {
                val preserve =
                    rememberSearchBodyAnchorRestoration(
                        datasetKey = dataset,
                        sameDataset = dataset == restoredDataset,
                        firstVisibleIndex = 40,
                        firstVisibleOffset = 9,
                        loading = loading,
                    )
                Text(if (preserve) "Restoration pending" else "Restoration finished")
                if (!blockLocalSearchRows(loading, hasLocalRows = true, preservePendingAnchor = preserve)) {
                    Text("Local name result")
                }
            }
        }
        composeRule.onNodeWithText("Restoration pending").assertIsDisplayed()
        composeRule.runOnIdle { loading = true }
        composeRule.onNodeWithText("Restoration pending").assertIsDisplayed()
        composeRule.onNodeWithText("Local name result").assertDoesNotExist()
        composeRule.runOnIdle { loading = false }
        composeRule.onNodeWithText("Restoration finished").assertIsDisplayed()
        composeRule.onNodeWithText("Local name result").assertIsDisplayed()

        composeRule.runOnIdle { loading = true }
        composeRule.onNodeWithText("Restoration finished").assertIsDisplayed()
        composeRule.onNodeWithText("Local name result").assertIsDisplayed()
        composeRule.runOnIdle { dataset = ChatListDatasetKey(false, null, "another") }
        composeRule.onNodeWithText("Restoration finished").assertIsDisplayed()
        composeRule.onNodeWithText("Local name result").assertIsDisplayed()

        composeRule.runOnIdle { mounted = false }
        composeRule.runOnIdle {
            dataset = restoredDataset
            mounted = true
        }
        composeRule.onNodeWithText("Restoration pending").assertIsDisplayed()
        composeRule.onNodeWithText("Local name result").assertDoesNotExist()
    }
}
