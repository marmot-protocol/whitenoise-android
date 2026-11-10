package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.SnippetHighlight
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One global message result and its list boundary: content, highlight, touch target, TalkBack and paging. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GlobalSearchResultRowTest {
    @get:Rule val composeRule = createComposeRule()

    /** The row names the sender, chat, time, snippet and attachment, and tapping it opens that message. */
    @Test
    fun rowShowsItsContextAndOpensTheMessage() {
        var opened: GlobalSearchResult? = null
        val image = GlobalSearchResultAttachment(GlobalSearchResultAttachmentKind.IMAGE, "beach.jpg", 1)
        val row = presentation(attachment = image)
        composeRule.setContent {
            WhiteNoiseTheme { GlobalSearchResultRow(row, onOpen = { opened = row.result }) }
        }

        composeRule.onNodeWithText("Alice in Hiking crew", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("10:42", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Image · beach.jpg", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(globalSearchResultTag(row.result)).performClick()
        composeRule.runOnIdle { assertEquals(row.result, opened) }
    }

    /** The row is one button of at least the minimum touch height, labeled for TalkBack as Go to Message. */
    @Test
    fun rowIsOneLabeledButtonWithAMinimumTouchTarget() {
        val row = presentation()
        composeRule.setContent { WhiteNoiseTheme { GlobalSearchResultRow(row, onOpen = {}) } }

        val node = composeRule.onNodeWithTag(globalSearchResultTag(row.result))
        node.assertHeightIsAtLeast(48.dp)
        val config = node.fetchSemanticsNode().config
        assertEquals("Go to Message", config.getOrNull(SemanticsActions.OnClick)?.label)
        assertEquals(androidx.compose.ui.semantics.Role.Button, config.getOrNull(SemanticsProperties.Role))
        val spoken = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ")
        assertTrue(spoken, "Alice in Hiking crew" in spoken && "gm everyone" in spoken)
    }

    /** A returned-to row reports itself selected. */
    @Test
    fun returnedRowIsMarkedSelected() {
        val row = presentation()
        composeRule.setContent { WhiteNoiseTheme { GlobalSearchResultRow(row, onOpen = {}, selected = true) } }

        val config = composeRule.onNodeWithTag(globalSearchResultTag(row.result)).fetchSemanticsNode().config
        assertEquals(true, config.getOrNull(SemanticsProperties.Selected))
    }

    /** Only the matched range is emphasized; a filter-only snippet has no emphasis at all. */
    @Test
    fun highlightCoversOnlyTheMatch() {
        val highlighted = highlightedSnippet(SnippetHighlight("say gm now", 4, 6), Color.Red)
        assertEquals("say gm now", highlighted.text)
        val span = highlighted.spanStyles.single()
        assertEquals(4 to 6, span.start to span.end)
        assertEquals(FontWeight.Bold, span.item.fontWeight)

        assertTrue(highlightedSnippet(SnippetHighlight("plain", 0, 0), Color.Red).spanStyles.all { it.start == it.end })
    }

    /** A failed batch keeps its rows and offers a retry at the boundary that calls back. */
    @Test
    fun failedBatchKeepsRowsAndRetries() {
        var retried = false
        val content =
            GlobalSearchResultsContent(
                state = GlobalSearchResultsState(generation = 1, batches = 1, failed = true),
                rows = listOf(presentation()),
                actions = GlobalSearchResultsActions(onRetry = { retried = true }),
            )
        render(content)

        composeRule.onNodeWithText("gm everyone", substring = true, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Couldn’t search message content.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { assertTrue(retried) }
    }

    /** No match shows the empty state and counts the active filters without naming them. */
    @Test
    fun emptyResultsCountTheActiveFilters() {
        val content =
            GlobalSearchResultsContent(
                state = GlobalSearchResultsState(generation = 1, batches = 1, endReached = true),
                rows = emptyList(),
                filterCount = 2,
            )
        render(content)

        composeRule.onNodeWithText("No Results").assertIsDisplayed()
        composeRule.onNodeWithText("2 filters active").assertIsDisplayed()
    }

    /** The end of results is announced once MDK reports no older rows. */
    @Test
    fun endOfResultsIsShown() {
        val content =
            GlobalSearchResultsContent(
                state = GlobalSearchResultsState(generation = 1, batches = 1, endReached = true),
                rows = listOf(presentation()),
            )
        render(content)

        composeRule.onNodeWithText("End of results").assertIsDisplayed()
    }

    /** Reaching the boundary asks for the next batch on its own. */
    @Test
    fun boundaryAsksForTheNextBatchOnItsOwn() {
        var loads = 0
        val actions = GlobalSearchResultsActions(onLoadMore = { loads++ })
        val auto = GlobalSearchResultsState(generation = 1, batches = 1)
        render(GlobalSearchResultsContent(state = auto, rows = listOf(presentation()), actions = actions))
        composeRule.runOnIdle { assertEquals(1, loads) }
    }

    /** After several empty batches the boundary offers to search older messages instead of reading on. */
    @Test
    fun sparseResultsOfferToSearchOlderMessages() {
        var loads = 0
        val empties = GLOBAL_SEARCH_AUTO_EMPTY_BATCHES
        val sparse = GlobalSearchResultsState(generation = 1, batches = empties, emptyBatches = empties)
        render(
            GlobalSearchResultsContent(
                state = sparse,
                rows = emptyList(),
                actions = GlobalSearchResultsActions(onLoadMore = { loads++ }),
            ),
        )
        composeRule.runOnIdle { assertEquals(0, loads) }
        composeRule.onNodeWithText("Search older messages").performClick()
        composeRule.runOnIdle { assertEquals(1, loads) }
    }

    /** A real file name containing a slash is shown; a missing name falls back to the type alone. */
    @Test
    fun attachmentNamesAreNeverMistakenForTypes() {
        val file = GlobalSearchResultAttachment(GlobalSearchResultAttachmentKind.FILE, "Q3/report.pdf", 1)
        val image = GlobalSearchResultAttachment(GlobalSearchResultAttachmentKind.IMAGE, "", 1)
        val named = presentation(attachment = file)
        val unnamed = presentation(attachment = image)
        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    GlobalSearchResultRow(named, onOpen = {})
                    GlobalSearchResultRow(unnamed.copy(result = unnamed.result.copy(messageIdHex = "dd")), onOpen = {})
                }
            }
        }

        composeRule.onNodeWithText("File · Q3/report.pdf", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Image", useUnmergedTree = true).assertIsDisplayed()
    }

    /** At large text the title moves above the time so neither is squeezed beside the other. */
    @Test
    fun largeTextStacksTheTitleAboveTheTime() {
        val row = presentation()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                WhiteNoiseTheme { GlobalSearchResultRow(row, onOpen = {}) }
            }
        }

        val title = composeRule.onNodeWithText("Alice in Hiking crew", useUnmergedTree = true).getBoundsInRoot()
        val time = composeRule.onNodeWithText("10:42", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(time.top >= title.bottom)
    }

    /** Only a query's first read is announced; later automatic batches stay quiet, and the empty state is heard. */
    @Test
    fun loadingIsAnnouncedOncePerQuery() {
        val request = GlobalSearchPageRequest(1, 0, null, emptySet())
        var state by mutableStateOf(GlobalSearchResultsState(generation = 1, inFlight = request))
        composeRule.setContent {
            WhiteNoiseTheme { LazyColumn { globalSearchResultItems(GlobalSearchResultsContent(state, emptyList())) } }
        }
        assertEquals(LiveRegionMode.Polite, liveRegion("Searching message content…"))

        composeRule.runOnIdle {
            state = GlobalSearchResultsState(generation = 1, batches = 1, inFlight = request.copy(sequence = 1))
        }
        assertNull(liveRegion("Searching message content…"))

        composeRule.runOnIdle { state = GlobalSearchResultsState(generation = 1, batches = 2, endReached = true) }
        val empty = composeRule.onNodeWithText("No Results", useUnmergedTree = true).fetchSemanticsNode()
        val announced = generateSequence(empty) { it.parent }.any { it.config.contains(SemanticsProperties.LiveRegion) }
        assertTrue(announced)
    }

    /** The live-region mode on the node showing [text], or null when it has none. */
    private fun liveRegion(text: String): LiveRegionMode? =
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.LiveRegion)

    /** Renders the result items in a list the way the chat list hosts them. */
    private fun render(content: GlobalSearchResultsContent) {
        composeRule.setContent {
            WhiteNoiseTheme { LazyColumn { globalSearchResultItems(content) } }
        }
        composeRule.waitForIdle()
    }

    /** A named result with a fixed time label. */
    private fun presentation(attachment: GlobalSearchResultAttachment? = null) =
        GlobalSearchResultPresentation(
            result =
                GlobalSearchResult(
                    groupIdHex = "aa".repeat(32),
                    messageIdHex = "bb".repeat(32),
                    senderIdHex = "cc".repeat(32),
                    timelineAt = 1uL,
                    snippet = SnippetHighlight("gm everyone", 0, 2),
                    attachment = attachment,
                ),
            senderLabel = "Alice",
            chatTitle = "Hiking crew",
            timeLabel = "10:42",
        )
}
