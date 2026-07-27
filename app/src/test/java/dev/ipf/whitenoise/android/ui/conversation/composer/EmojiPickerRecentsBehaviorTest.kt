package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
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
        runBlocking { owner.hydrateFromDiskIfEmpty() }
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

    @Test
    fun searchPickUpdatesDisplayedRecentsImmediately() {
        val owner =
            RecentEmojiRecentsOwner(
                scope = scope,
                loadFromDisk = { listOf("👍", "😂") },
                saveToDisk = {},
            )
        runBlocking { owner.hydrateFromDiskIfEmpty() }
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
        composeRule
            .onNodeWithContentDescription(string(R.string.emoji_search_hint))
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performTextInput("happy")
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

    private fun waitForBrowseGrid() {
        repeat(100) {
            composeRule.waitForIdle()
            runCatching {
                composeRule.onNodeWithText(string(R.string.emoji_category_smileys)).assertIsDisplayed()
                composeRule.onNodeWithText("😀").assertIsDisplayed()
            }.onSuccess { return }
            Thread.sleep(20)
        }
        error("Emoji browse grid did not load")
    }
}
