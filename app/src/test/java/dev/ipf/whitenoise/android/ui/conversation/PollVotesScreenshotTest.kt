package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import dev.ipf.marmotkit.PollOptionResultFfi
import dev.ipf.marmotkit.PollProjectionFfi
import dev.ipf.marmotkit.PollTypeFfi
import dev.ipf.whitenoise.android.state.PollVoteRow
import dev.ipf.whitenoise.android.state.PollVotesPhase
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Per-voter results across themes, RTL, large text and every paging state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class PollVotesScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private val rows =
        listOf(
            PollVoteRow("0a".repeat(32), "Amina", null, listOf("Soup"), blocked = false),
            PollVoteRow("0b".repeat(32), "Bilal", null, listOf("Soup", "Salad"), blocked = true),
            PollVoteRow("0c".repeat(32), "Chidi", null, listOf("Salad"), blocked = false),
        )

    /** Renders [PollVotesContent] on the sheet surface and captures it to [path]. */
    private fun capture(
        path: String,
        darkTheme: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(1f, fontScale),
                ) {
                    Surface(Modifier.width(360.dp).testTag("poll-votes")) { content() }
                }
            }
        }
        composeRule.onNodeWithTag("poll-votes").captureRoboImage("src/test/snapshots/$path.png")
    }

    /** Renders [PollVotesContent] with the given paging state and no-op actions. */
    @Composable
    private fun Votes(
        phase: PollVotesPhase = PollVotesPhase.READY,
        hasMore: Boolean = false,
        voters: List<PollVoteRow> = rows,
    ) = PollVotesContent(voters, phase, hasMore, onRetry = {}, onLoadMore = {})

    /** A loaded page lists each voter's choices and marks the blocked voter. */
    @Test fun votesLight() = capture("poll_votes_light") { Votes(hasMore = true) }

    /** AMOLED keeps the blocked marker and the not-anonymous note readable. */
    @Test fun votesAmoled() = capture("poll_votes_amoled", darkTheme = true, amoled = true) { Votes() }

    /** RTL at 200% text keeps names, choices and the blocked marker reachable. */
    @Test fun votesDarkRtlLarge() =
        capture(
            "poll_votes_dark_rtl_large",
            darkTheme = true,
            rtl = true,
            fontScale = 2f,
        ) { Votes() }

    /** The first page shows progress while MDK is read. */
    @Test fun votesLoading() =
        capture("poll_votes_loading") {
            Votes(PollVotesPhase.LOADING, voters = emptyList())
        }

    /** An unanswered poll says so. */
    @Test fun votesEmpty() =
        capture("poll_votes_empty_amoled", darkTheme = true, amoled = true) {
            Votes(voters = emptyList())
        }

    /** A failed first page offers Retry rather than an empty list. */
    @Test fun votesFirstPageError() =
        capture("poll_votes_error_light") {
            Votes(PollVotesPhase.FAILED, voters = emptyList())
        }

    /** A later page loading keeps the loaded voters visible. */
    @Test fun votesLoadingMore() =
        capture("poll_votes_loading_more_dark", darkTheme = true) {
            Votes(PollVotesPhase.LOADING_MORE, hasMore = true)
        }

    /** A failed later page keeps the loaded voters and offers Retry. */
    @Test fun votesLoadMoreError() =
        capture("poll_votes_load_more_error_light") {
            Votes(PollVotesPhase.MORE_FAILED, hasMore = true)
        }

    /** The card offers View votes beside the participant count once someone has voted. */
    @Test fun cardWithViewVotesLight() =
        capture("poll_card_view_votes_light") {
            PollCard(
                poll =
                    PollProjectionFfi(
                        question = "Lunch?",
                        options =
                            listOf(PollOptionResultFfi("a", "Soup", 2uL), PollOptionResultFfi("b", "Salad", 1uL)),
                        pollType = PollTypeFfi.SINGLE_CHOICE,
                        participants = 3uL,
                        localSelection = listOf("a"),
                        creator = "0a".repeat(32),
                        endsAt = null,
                        open = true,
                    ),
                canVote = true,
                onVote = {},
                onViewVotes = {},
            )
        }
}
