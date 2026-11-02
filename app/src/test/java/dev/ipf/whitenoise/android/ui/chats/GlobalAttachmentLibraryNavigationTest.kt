package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import dev.ipf.whitenoise.android.core.GlobalAttachmentItem
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/** Opening, View in chat, keyboard access and the return to a deep position in the library (#2000). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class GlobalAttachmentLibraryNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    private val opened = mutableListOf<GlobalAttachmentItem>()
    private val viewed = mutableListOf<GlobalAttachmentItem>()

    /**
     * Leaving for a chat from deep in the library and coming back keeps the scroll position while the session
     * rereads, then lands on the opened item, selected and focused.
     */
    @Test
    fun returnFromAChatLandsOnTheOpenedItem() {
        val grid = LazyGridState()
        val owner = GlobalSearchSelectionOwner(mutableStateOf(GlobalSearchSelectedResult()))
        var screen by mutableStateOf(Screen.LIBRARY)
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.LIBRARY -> Browser(ITEMS, loading = false, grid, owner)
                        Screen.CHAT -> Text("chat")
                        Screen.RESUMING -> Browser(emptyList(), loading = true, grid, owner)
                    }
                }
            }
        }
        val target = ITEMS[70]
        composeRule.onNodeWithTag(GLOBAL_LIBRARY_RESULTS_TAG).performScrollToKey(globalLibraryItemTag(target))
        composeRule.onNodeWithTag(globalLibraryItemTag(target)).performClick()
        composeRule.waitForIdle()
        val position = grid.firstVisibleItemIndex
        assertTrue("the test opens an item past the first batch", position > 30)

        screen = Screen.CHAT
        composeRule.waitForIdle()
        screen = Screen.RESUMING
        composeRule.waitForIdle()
        assertEquals("rereading does not move the grid", position, grid.firstVisibleItemIndex)

        owner.onConversationReturned()
        screen = Screen.LIBRARY
        composeRule.waitForIdle()

        // Focus scrolls the opened item fully into view, so the grid may settle up to one row (3 tiles) higher.
        assertTrue("the return lands within a row of where it left", position - grid.firstVisibleItemIndex in 0..3)
        composeRule
            .onNodeWithTag(globalLibraryItemTag(target))
            .assertIsDisplayed()
            .assertIsSelected()
            .assertIsFocused()
        assertEquals(listOf(target), opened)
    }

    /** View in chat names the exact item, and that item is the one the return lands on. */
    @Test
    fun viewInChatNamesTheExactItem() {
        val owner = GlobalSearchSelectionOwner(mutableStateOf(GlobalSearchSelectedResult()))
        composeRule.setContent { WhiteNoiseTheme { Browser(ITEMS.take(6), loading = false, LazyGridState(), owner) } }
        val target = ITEMS[4]

        composeRule.onNodeWithTag(globalLibraryItemTag(target)).performTouchInput { longClick() }

        assertEquals(listOf(target), viewed)
        assertTrue(owner.selected.matches(target.groupIdHex, target.messageIdHex, target.attachmentIndex))
    }

    /** A keyboard or switch user can focus a library item and open it with Enter. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun keyboardFocusReachesItemsAndEnterOpensThem() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Browser(ITEMS.take(6), loading = false, LazyGridState(), null)
            }
        }

        val focused = ITEMS[2]
        val node = composeRule.onNodeWithTag(globalLibraryItemTag(focused))
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        node.assertIsFocused()
        node.performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()

        assertEquals(listOf(focused), opened)
    }

    /** The browser at a fixed day in UTC, recording opens and View in chat. */
    @androidx.compose.runtime.Composable
    private fun Browser(
        items: List<GlobalAttachmentItem>,
        loading: Boolean,
        grid: LazyGridState,
        owner: GlobalSearchSelectionOwner?,
    ) {
        GlobalAttachmentBrowser(
            items = items,
            kinds = setOf(GlobalSearchContentKind.IMAGES_VIDEO),
            loading = loading,
            bottomPadding =
                androidx.compose.ui.unit
                    .Dp(0f),
            actions =
                GlobalAttachmentCardActions(
                    senderLabel = { "Bea" },
                    onOpen = { opened += it },
                    onViewInChat = { viewed += it },
                    timeLabel = { "12:00" },
                ),
            zoneId = ZoneOffset.UTC,
            today = TODAY,
            gridState = grid,
            selectionOwner = owner,
        )
    }

    /** What the search route shows while the test moves between the library and a chat. */
    private enum class Screen { LIBRARY, CHAT, RESUMING }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 12)

        /** Ninety photos from two chats, newest first, a minute apart on the fixed day. */
        val ITEMS =
            (0 until 90).map { index ->
                val chat = if (index % 2 == 0) "family" else "design"
                GlobalAttachmentItem(
                    groupIdHex = chat,
                    chatTitle = chat,
                    messageIdHex = "m$index",
                    attachmentIndex = 0,
                    mediaType = "image/jpeg",
                    label = "photo-$index.jpg",
                    timelineAt =
                        TODAY
                            .atTime(12, 0)
                            .minusMinutes(index.toLong())
                            .toEpochSecond(ZoneOffset.UTC)
                            .toULong(),
                    sender = "bea",
                )
            }
    }
}
