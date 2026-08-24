package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pixel regression for the A-to-B folder overlap frame fixed by issue #1728. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListFolderTransitionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun folderTransitionLight() {
        captureFolderTransition(darkTheme = false, amoled = false, themeName = "light")
    }

    @Test
    fun folderTransitionDark() {
        captureFolderTransition(darkTheme = true, amoled = false, themeName = "dark")
    }

    @Test
    fun folderTransitionAmoled() {
        captureFolderTransition(darkTheme = true, amoled = true, themeName = "amoled")
    }

    @Test
    fun sameHeadPlacementCrossingDark() {
        var itemIds by mutableStateOf(listOf("A", "B", "C"))
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = false) {
                Surface(
                    modifier =
                        Modifier
                            .width(SCREENSHOT_WIDTH)
                            .height(SCREENSHOT_HEIGHT)
                            .testTag(SCREENSHOT_TAG),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    FolderTransitionHarness(itemIds)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { itemIds = listOf("A", "C", "B") }
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeBy(CHAT_LIST_ROW_PLACEMENT_MILLIS / 2L)
        composeRule.runOnIdle { }

        composeRule
            .onNodeWithTag(SCREENSHOT_TAG)
            .captureRoboImage("src/test/snapshots/chat_list_same_head_crossing_dark.png")
    }

    private fun captureFolderTransition(
        darkTheme: Boolean,
        amoled: Boolean,
        themeName: String,
    ) {
        var itemIds by mutableStateOf(listOf("A", "B", "C"))

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(
                    modifier =
                        Modifier
                            .width(SCREENSHOT_WIDTH)
                            .height(SCREENSHOT_HEIGHT)
                            .testTag(SCREENSHOT_TAG),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    FolderTransitionHarness(itemIds)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(SCREENSHOT_TAG)
            .captureRoboImage("src/test/snapshots/chat_list_folder_a_$themeName.png")

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            itemIds = listOf("C", "D", "A")
        }
        composeRule.runOnIdle { }

        val rowHeightPx = composeRule.density.run { ROW_HEIGHT.toPx() }
        var capturedEnteringFrame = false
        var sawOverlap = false
        for (frame in 0 until 30) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            val aTop = rowTop("A")
            val cTop = rowTop("C")
            val overlapTop = maxOf(aTop, cTop)
            val overlapBottom = minOf(aTop + rowHeightPx, cTop + rowHeightPx)
            if (!capturedEnteringFrame && overlapBottom - overlapTop > 4f) {
                composeRule
                    .onNodeWithTag(SCREENSHOT_TAG)
                    .captureRoboImage("src/test/snapshots/chat_list_folder_b_entering_$themeName.png")
                capturedEnteringFrame = true
            }
            if (overlapBottom - overlapTop > rowHeightPx / 2f) {
                sawOverlap = true
                break
            }
        }
        assertTrue("folder transition never reached the entering-row fade frame", capturedEnteringFrame)
        assertTrue("shared rows never reached the folder-transition overlap frame", sawOverlap)

        composeRule
            .onNodeWithTag(SCREENSHOT_TAG)
            .captureRoboImage("src/test/snapshots/chat_list_folder_b_overlap_$themeName.png")
    }

    private fun rowTop(id: String): Float =
        composeRule
            .onNodeWithTag(rowTag(id))
            .fetchSemanticsNode()
            .boundsInRoot
            .top
}

@Composable
private fun FolderTransitionHarness(itemIds: List<String>) {
    LazyColumn(
        modifier =
            Modifier
                .width(SCREENSHOT_WIDTH)
                .height(SCREENSHOT_HEIGHT)
                .clipToBounds(),
    ) {
        itemsIndexed(itemIds, key = { _, id -> id }) { targetIndex, id ->
            Box(modifier = chatListRowMotion(targetIndex)) {
                val containerColor =
                    when (id) {
                        "A" -> MaterialTheme.colorScheme.secondaryContainer
                        "B" -> MaterialTheme.colorScheme.tertiaryContainer
                        "C" -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                val contentColor =
                    when (id) {
                        "A" -> MaterialTheme.colorScheme.onSecondaryContainer
                        "B" -> MaterialTheme.colorScheme.onTertiaryContainer
                        "C" -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onErrorContainer
                    }
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(ROW_HEIGHT)
                            .background(containerColor)
                            .padding(horizontal = 16.dp)
                            .testTag(rowTag(id)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(text = "Chat $id", color = contentColor)
                }
            }
        }
    }
}

private fun rowTag(id: String): String = "chat-list-folder-transition-row-$id"

private val SCREENSHOT_WIDTH = 240.dp
private val SCREENSHOT_HEIGHT = 144.dp
private val ROW_HEIGHT = 48.dp
private const val SCREENSHOT_TAG = "chat-list-folder-transition"
