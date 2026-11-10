package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.SnippetHighlight
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Global message results (#1944) across themes, RTL with large text, and their loading, empty and error states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class GlobalSearchResultsScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Several matches, two from one chat, with highlight, attachment and filter-only rows, in light. */
    @Test fun resultsLight() = capture("results_light", ended())

    /** The same results in dark. */
    @Test fun resultsDark() = capture("results_dark", ended(), dark = true)

    /** The same results in AMOLED. */
    @Test fun resultsAmoled() = capture("results_amoled", ended(), dark = true, amoled = true)

    /** The same results right-to-left at double font scale on a narrow screen. */
    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun resultsLargeRtl() = capture("results_large_rtl", ended(), largeRtl = true)

    /** The first batch is still reading. */
    @Test fun loadingLight() = capture("loading_light", loading(), rows = emptyList())

    /** A later batch failed: loaded rows stay and the boundary offers a retry. */
    @Test fun failureDark() = capture("failure_dark", failed(), dark = true)

    /** Nothing matched the query and its two filters. */
    @Test fun emptyLight() = capture("empty_light", ended(), rows = emptyList(), filterCount = 2)

    /** Several batches showed nothing, so reading older messages waits for the user. */
    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun sparseLargeRtl() =
        capture(
            "sparse_large_rtl",
            GlobalSearchResultsState(1, batches = 3, emptyBatches = GLOBAL_SEARCH_AUTO_EMPTY_BATCHES),
            rows = emptyList(),
            filterCount = 1,
            largeRtl = true,
        )

    /** A finished generation with no older rows. */
    private fun ended() = GlobalSearchResultsState(generation = 1, batches = 1, endReached = true)

    /** A generation whose first batch is in flight. */
    private fun loading(): GlobalSearchResultsState {
        val request = GlobalSearchPageRequest(generation = 1, sequence = 0, cursor = null, seenKeys = emptySet())
        return GlobalSearchResultsState(generation = 1, inFlight = request)
    }

    /** A generation whose later batch failed after one loaded. */
    private fun failed() = GlobalSearchResultsState(generation = 1, batches = 1, failed = true)

    /** Renders the result items as the chat list hosts them and records the golden. */
    @Suppress("LongParameterList")
    private fun capture(
        name: String,
        state: GlobalSearchResultsState,
        rows: List<GlobalSearchResultPresentation> = sampleRows(),
        filterCount: Int = 0,
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    Surface(
                        modifier =
                            Modifier.width(if (largeRtl) 320.dp else 360.dp).height(680.dp).testTag("results"),
                    ) {
                        LazyColumn {
                            globalSearchResultItems(
                                GlobalSearchResultsContent(state = state, rows = rows, filterCount = filterCount),
                            )
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(FRAME_MILLIS)
        composeRule.onNodeWithTag("results").captureRoboImage("src/test/snapshots/global_search_results_$name.png")
    }

    /** Four results: two matches from one chat, an attachment match and a filter-only match. */
    private fun sampleRows(): List<GlobalSearchResultPresentation> =
        listOf(
            row("m1", "Alice", "Hiking crew", "10:42", SnippetHighlight("gm everyone, ready for the trail?", 0, 2)),
            row("m2", "Bob", "Hiking crew", "09:15", SnippetHighlight(CLIPPED_SNIPPET, 6, 8)),
            row(
                "m3",
                "You",
                "Dana",
                "Yesterday",
                SnippetHighlight("gm! photos from the summit", 0, 2),
                GlobalSearchResultAttachment(GlobalSearchResultAttachmentKind.IMAGE, "summit.jpg", 3),
            ),
            row(
                "m4",
                "Carol",
                "Book club",
                "Mon",
                SnippetHighlight("Minutes for this week", 0, 0),
                GlobalSearchResultAttachment(GlobalSearchResultAttachmentKind.FILE, "minutes.pdf", 1),
            ),
        )

    /** One named result. */
    @Suppress("LongParameterList")
    private fun row(
        id: String,
        sender: String,
        chat: String,
        time: String,
        snippet: SnippetHighlight,
        attachment: GlobalSearchResultAttachment? = null,
    ) = GlobalSearchResultPresentation(
        result =
            GlobalSearchResult(
                groupIdHex = chat.lowercase(),
                messageIdHex = id,
                senderIdHex = sender.lowercase().padEnd(64, '0'),
                timelineAt = 1uL,
                snippet = snippet,
                attachment = attachment,
            ),
        senderLabel = sender,
        chatTitle = chat,
        timeLabel = time,
    )

    private companion object {
        const val FRAME_MILLIS = 32L
        const val CLIPPED_SNIPPET = "…said gm before sunrise and then we left"
    }
}
