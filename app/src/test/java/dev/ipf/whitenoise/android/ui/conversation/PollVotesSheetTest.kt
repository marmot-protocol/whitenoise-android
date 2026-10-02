package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.BlockListSnapshotFfi
import dev.ipf.marmotkit.BlockedUserFfi
import dev.ipf.marmotkit.PollOptionResultFfi
import dev.ipf.marmotkit.PollVoteFfi
import dev.ipf.marmotkit.PollVotePageFfi
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** The View votes sheet reads MDK's per-voter pages through the real controller and native boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class PollVotesSheetTest : PollMessageTestFixtures() {
    @get:Rule val composeRule = createComposeRule()
    private val voterA = "0a".repeat(32)
    private val voterB = "0b".repeat(32)
    private val voterC = "0c".repeat(32)

    /** Releases the controller so no screen job outlives the test. */
    @After fun clearController() {
        pollController.onCleared()
    }

    /** A poll with three participants, so the card offers View votes. */
    private fun votedPoll(): TimelineMessage {
        val source = pollMessage()
        val projected = checkNotNull(source.projected)
        val poll = checkNotNull(projected.poll)
        return source.copy(
            projected =
                projected.copy(
                    poll =
                        poll.copy(
                            participants = 3uL,
                            options =
                                listOf(
                                    PollOptionResultFfi("a", "Soup", 2uL),
                                    PollOptionResultFfi("b", "Salad", 1uL),
                                ),
                        ),
                ),
        )
    }

    /** Mounts the real poll content for [item] in AMOLED, as a received poll in a group would render. */
    private fun render(item: TimelineMessage) {
        retain(item)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                PollMessageContent(item, pollController, pollState, canVote = true)
            }
        }
    }

    /** Opens the sheet from the card's View votes action. */
    private fun openSheet() {
        composeRule.onNodeWithTag(POLL_VIEW_VOTES_TAG).performClick()
    }

    /** Waits for the off-main native read to settle onto the main thread. */
    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.countTagged(tag) > 0
        }
    }

    /** Counts the nodes carrying [tag] without failing when none exist. */
    private fun ComposeContentTestRule.countTagged(tag: String): Int = onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /** Two pages append in order, send MDK's cursor, and the voters' choices cover the card's tally. */
    @Test
    fun multiPageResultsUseTheNativeCursor() {
        val first = listOf(PollVoteFfi(voterA, listOf("a"), 10uL), PollVoteFfi(voterB, listOf("a"), 11uL))
        val second = listOf(PollVoteFfi(voterC, listOf("b"), 12uL))
        pollVotesResponder = { args ->
            if (args[3] == null) PollVotePageFfi(first, true) else PollVotePageFfi(second, false)
        }
        render(votedPoll())

        openSheet()
        awaitTag("poll-voter-$voterB")
        composeRule.onNodeWithTag("poll-voter-$voterA").assertExists()
        composeRule.onNodeWithTag(POLL_VOTES_LOAD_MORE_TAG).performClick()
        awaitTag("poll-voter-$voterC")

        val reads = recordedCalls().filter { it.first == "pollVotes" }
        assertEquals(2, reads.size)
        assertEquals(listOf("personal", pollController.group.groupIdHex), reads[0].second.take(2))
        assertEquals(listOf(null, null), reads[0].second.subList(3, 5))
        assertEquals(listOf(11uL, voterB), reads[1].second.subList(3, 5))
        composeRule.onNodeWithTag("poll-voter-$voterA").assertTextContains("Soup", substring = true)
    }

    /** An unanswered poll reads as empty rather than failed. */
    @Test
    fun emptyPageShowsTheEmptyState() {
        render(votedPoll())

        openSheet()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.countTagged(POLL_VOTES_SHEET_TAG) > 0
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithText("No votes yet.").assertExists() }.isSuccess
        }
    }

    /** A native failure shows the error and Retry reads the first page again. */
    @Test
    fun failureShowsRetryThatRecovers() {
        val attempts = AtomicInteger()
        pollVotesResponder = {
            if (attempts.getAndIncrement() == 0) error("native failure")
            PollVotePageFfi(listOf(PollVoteFfi(voterA, listOf("a"), 10uL)), false)
        }
        render(votedPoll())

        openSheet()
        awaitTag(POLL_VOTES_RETRY_TAG)
        composeRule.onNodeWithText("Couldn't load votes.").assertExists()
        composeRule.onNodeWithTag(POLL_VOTES_RETRY_TAG).performClick()

        awaitTag("poll-voter-$voterA")
        assertEquals(2, recordedCalls().count { it.first == "pollVotes" })
    }

    /** A blocked voter stays in the native results and is marked from the account's block list. */
    @Test
    fun blockedVoterIsListedAndMarked() {
        pollVotesResponder = {
            PollVotePageFfi(
                listOf(PollVoteFfi(voterA, listOf("a"), 10uL), PollVoteFfi(voterB, listOf("b"), 11uL)),
                false,
            )
        }
        pollState.runtimeMirrors.blocks.install(
            BlockListSnapshotFfi(1uL, listOf(BlockedUserFfi(voterB, isPrivate = false, createdAtMs = 1L))),
        )
        render(votedPoll())

        openSheet()
        awaitTag("poll-voter-$voterB")

        composeRule.onNodeWithTag("poll-voter-$voterB").assertTextContains("Blocked", substring = true)
        val unblockedText =
            composeRule
                .onNodeWithTag("poll-voter-$voterA")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString { it.text }
        assertFalse(unblockedText.contains("Blocked"))
        assertTrue(recordedCalls().any { it.first == "pollVotes" })
    }

    /** A poll without participants offers no View votes action. */
    @Test
    fun pollWithoutParticipantsHasNoViewVotesAction() {
        render(pollMessage())

        assertEquals(0, composeRule.countTagged(POLL_VIEW_VOTES_TAG))
    }

    /** A voter who re-voted between pages and reappears on page two renders once and does not crash the list. */
    @Test
    fun repeatedVoterAcrossPagesRendersOnceWithoutCrashing() {
        val first = listOf(PollVoteFfi(voterA, listOf("a"), 10uL), PollVoteFfi(voterB, listOf("a"), 11uL))
        val second = listOf(PollVoteFfi(voterC, listOf("b"), 12uL), PollVoteFfi(voterA, listOf("b"), 99uL))
        pollVotesResponder = { args ->
            if (args[3] == null) PollVotePageFfi(first, true) else PollVotePageFfi(second, false)
        }
        render(votedPoll())

        openSheet()
        awaitTag("poll-voter-$voterB")
        composeRule.onNodeWithTag(POLL_VOTES_LOAD_MORE_TAG).performClick()
        awaitTag("poll-voter-$voterC")

        composeRule.onNodeWithTag("poll-voter-$voterA").assertTextContains("Salad", substring = true)
        assertEquals(1, composeRule.countTagged("poll-voter-$voterA"))
    }
}
