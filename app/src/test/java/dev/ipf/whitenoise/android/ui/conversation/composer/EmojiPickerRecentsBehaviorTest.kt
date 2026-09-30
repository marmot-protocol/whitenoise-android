package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.RecentEmojiRecentsOwner
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class EmojiPickerRecentsBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun string(resId: Int): String = context.getString(resId)

    @Test
    fun browsePickUpdatesDisplayedRecentsImmediately() {
        val owner =
            RecentEmojiRecentsOwner(
                scope = scope,
                loadFromDisk = { listOf("👍", "😂", "🎉") },
                saveToDisk = {},
            )
        runBlocking { owner.hydrateFromDisk() }
        composeRule.setContent {
            WhiteNoiseTheme {
                ComposerEmojiPickerPane(
                    height = ComposerEmojiPickerFallbackHeight,
                    alpha = 1f,
                    recentEmojis = owner.recents,
                    onEmojiUsed = owner::onEmojiUsed,
                    onEmojiPicked = {},
                    onBackspace = {},
                    onSearchActiveChange = {},
                    modifier = Modifier.width(360.dp),
                )
            }
        }
        waitForBrowseGrid()
        composeRule.onNodeWithText("😀").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("😀", "👍", "😂", "🎉"), owner.recents)
        composeRule.onNodeWithText(string(R.string.emoji_category_recent)).assertIsDisplayed()
    }

    /** Search pick updates displayed recents immediately. */
    @Test
    fun searchPickUpdatesDisplayedRecentsImmediately() {
        val owner =
            RecentEmojiRecentsOwner(
                scope = scope,
                loadFromDisk = { listOf("👍", "😂") },
                saveToDisk = {},
            )
        runBlocking { owner.hydrateFromDisk() }
        composeRule.setContent {
            WhiteNoiseTheme {
                ComposerEmojiPickerPane(
                    height = ComposerEmojiPickerFallbackHeight,
                    alpha = 1f,
                    recentEmojis = owner.recents,
                    onEmojiUsed = owner::onEmojiUsed,
                    onEmojiPicked = {},
                    onBackspace = {},
                    onSearchActiveChange = {},
                    modifier = Modifier.width(360.dp),
                )
            }
        }
        waitForBrowseGrid()
        composeRule.onNodeWithTag(EMOJI_PICKER_SEARCH_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EMOJI_PICKER_SEARCH_TEST_TAG).performTextInput("happy")
        composeRule.waitForIdle()
        var found = false
        repeat(100) {
            if (found) return@repeat
            composeRule.waitForIdle()
            runCatching {
                composeRule.onNodeWithText("😀").assertIsDisplayed()
                found = true
            }
            if (!found) Thread.sleep(20)
        }
        composeRule.onNodeWithText("😀").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("😀", "👍", "😂"), owner.recents)
    }

    @Test
    fun configureQuickReactionPurposeDoesNotRecordUsage() {
        var usedCount = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                EmojiPickerSheet(
                    onDismissRequest = {},
                    purpose = EmojiPickerPurpose.CONFIGURE_QUICK_REACTION,
                    recentEmojis = listOf("👍", "😂"),
                    onEmojiUsed = { usedCount++ },
                    onEmojiPicked = {},
                )
            }
        }
        waitForBrowseGrid()
        composeRule.onNodeWithText(string(R.string.emoji_category_recent)).assertIsDisplayed()
        composeRule.onNodeWithText("😀").performClick()
        composeRule.waitForIdle()

        assertEquals(0, usedCount)
    }

    @Test
    fun builtInRecentsRenderArtworkAndDeliverLiteralShortcodes() {
        val picked = mutableListOf<String>()
        val used = mutableListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme {
                EmojiPickerContent(
                    onEmojiPicked = picked::add,
                    onEmojiUsed = used::add,
                    recentEmojis = listOf(":marmot:", ":wn:", "👍"),
                    modifier = Modifier.width(360.dp).height(400.dp).testTag("builtin.picker"),
                )
            }
        }
        waitForBrowseGrid()
        composeRule.onNodeWithContentDescription(":marmot:").performClick()
        composeRule.onNodeWithContentDescription(":wn:").performClick()
        assertEquals(listOf(":marmot:", ":wn:"), picked)
        assertEquals(picked, used)
        composeRule
            .onNodeWithTag("builtin.picker")
            .captureRoboImage("src/test/snapshots/emoji_picker_builtin_recents.png")
    }

    @Test
    fun builtInSearchConfiguresLiteralQuickReactionWithoutRecordingUsage() {
        var picked = ""
        val used = mutableListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme {
                EmojiPickerContent(
                    onEmojiPicked = { picked = it },
                    onEmojiUsed = used::add,
                    purpose = EmojiPickerPurpose.CONFIGURE_QUICK_REACTION,
                    modifier = Modifier.width(360.dp).height(400.dp).testTag("builtin.search"),
                )
            }
        }
        waitForBrowseGrid()
        composeRule.onNodeWithTag(EMOJI_PICKER_SEARCH_TEST_TAG).performTextInput(":marmot:")
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription(":marmot:").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(":marmot:").performClick()
        assertEquals(":marmot:", picked)
        assertEquals(emptyList<String>(), used)
        composeRule
            .onNodeWithTag("builtin.search")
            .captureRoboImage("src/test/snapshots/emoji_picker_builtin_search.png")
    }

    @Test
    fun groupImagePickerExcludesArtworkFromRecentsAndSearch() {
        composeRule.setContent {
            WhiteNoiseTheme {
                EmojiPickerContent(
                    onEmojiPicked = {},
                    purpose = EmojiPickerPurpose.GROUP_IMAGE,
                    recentEmojis = listOf(":marmot:", ":wn:", "👍"),
                    modifier = Modifier.width(360.dp).height(400.dp),
                )
            }
        }
        waitForBrowseGrid()
        composeRule.onNodeWithText("👍").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(":marmot:").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(":wn:").assertCountEquals(0)
        composeRule.onNodeWithTag(EMOJI_PICKER_SEARCH_TEST_TAG).performTextInput(":marmot:")
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(string(R.string.emoji_search_no_results)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(string(R.string.emoji_search_no_results)).assertIsDisplayed()
    }

    /** Waits until the browse grid is composed. */
    private fun waitForBrowseGrid() {
        repeat(100) {
            composeRule.waitForIdle()
            runCatching {
                composeRule.onNodeWithText(string(R.string.emoji_category_smileys_people)).assertIsDisplayed()
                composeRule.onNodeWithText("😀").assertIsDisplayed()
            }.onSuccess { return }
            Thread.sleep(20)
        }
        error("Emoji browse grid did not load")
    }
}
