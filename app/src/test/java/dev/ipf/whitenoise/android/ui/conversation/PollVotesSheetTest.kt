package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.PollOptionResultFfi
import dev.ipf.marmotkit.PollVoteFfi
import dev.ipf.marmotkit.PollVotePageFfi
import dev.ipf.marmotkit.RuntimeProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineRemoveReasonFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
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
        closeProjectionStream()
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
                ConversationHost { PollMessageContent(item, pollController, pollState, canVote = true) }
            }
        }
    }

    /** Provides the conversation-level votes host the way the screen does, around the poll [row]. */
    @Composable
    private fun ConversationHost(row: @Composable () -> Unit) {
        val host = remember { PollVotesHostState() }
        CompositionLocalProvider(LocalPollVotesHost provides host) {
            row()
            PollVotesHost(host, pollController, pollState)
        }
    }

    /** Builds a projection event for the fixture's account and group that reprojects [item]'s poll. */
    private fun touchingEvent(item: TimelineMessage) =
        MarmotEventFfi.ProjectionUpdated(
            RuntimeProjectionUpdateFfi(
                "ff".repeat(32),
                "personal",
                TimelineProjectionUpdateFfi(
                    pollController.group.groupIdHex,
                    emptyList(),
                    listOf(
                        TimelineMessageChangeFfi.Remove(item.record.messageIdHex, TimelineRemoveReasonFfi.INVALIDATED),
                    ),
                    null,
                    ChatListUpdateTriggerFfi.NEW_GROUP,
                ),
            ),
        )

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

    /** A projection event for the open poll re-reads the first page, and an unrelated one does not. */
    @Test
    fun touchingProjectionEventRefreshesTheOpenSheet() {
        val item = votedPoll()
        pollVotesResponder = { PollVotePageFfi(listOf(PollVoteFfi(voterA, listOf("a"), 10uL)), false) }
        render(item)
        openSheet()
        awaitTag("poll-voter-$voterA")
        val unrelated = item.copy(record = item.record.copy(messageIdHex = "dd".repeat(32)))

        projectionEvents.put(touchingEvent(unrelated))
        composeRule.waitUntil(timeoutMillis = 5_000) { projectionEvents.isEmpty() }
        composeRule.waitForIdle()
        assertEquals(1, recordedCalls().count { it.first == "pollVotes" })
        projectionEvents.put(touchingEvent(item))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            recordedCalls().count { it.first == "pollVotes" } >= 2
        }

        assertEquals(2, recordedCalls().count { it.first == "pollVotes" })
        composeRule.onNodeWithTag("poll-voter-$voterA").assertExists()
    }

    /** The sheet is hosted above the poll row, so disposing the row does not close it or lose its votes. */
    @Test
    fun sheetSurvivesTheRowLeavingTheComposition() {
        val item = votedPoll()
        pollVotesResponder = { singleVoterPage() }
        retain(item)
        var rowShown by mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                ConversationHost {
                    if (rowShown) PollMessageContent(item, pollController, pollState, canVote = true)
                }
            }
        }
        openSheet()
        awaitTag("poll-voter-$voterA")

        rowShown = false
        composeRule.waitForIdle()

        assertEquals(0, composeRule.countTagged(POLL_VIEW_VOTES_TAG))
        composeRule.onNodeWithTag(POLL_VOTES_SHEET_TAG).assertExists()
        composeRule.onNodeWithTag("poll-voter-$voterA").assertExists()
    }

    /** One voter's native page, for tests that only need the sheet populated. */
    private fun singleVoterPage() = PollVotePageFfi(listOf(PollVoteFfi(voterA, listOf("a"), 10uL)), false)

    /** A deleted upsert for the open poll closes the sheet, even with the row off screen. */
    @Test
    fun deletedPollUpsertClosesTheSheet() {
        val item = votedPoll()
        pollVotesResponder = { singleVoterPage() }
        render(item)
        openSheet()
        awaitTag("poll-voter-$voterA")

        projectionEvents.put(upsertEvent(item, deleted = true))

        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.countTagged(POLL_VOTES_SHEET_TAG) == 0 }
    }

    /** A pruned removal of the open poll closes the sheet when its row is not composed. */
    @Test
    fun prunedPollRemovalClosesTheSheetWithTheRowOffScreen() {
        val item = votedPoll()
        pollVotesResponder = { singleVoterPage() }
        retain(item)
        var rowShown by mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                ConversationHost {
                    if (rowShown) PollMessageContent(item, pollController, pollState, canVote = true)
                }
            }
        }
        openSheet()
        awaitTag("poll-voter-$voterA")
        rowShown = false
        composeRule.waitForIdle()

        projectionEvents.put(removeEvent(item, TimelineRemoveReasonFfi.PRUNED))

        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.countTagged(POLL_VOTES_SHEET_TAG) == 0 }
    }

    /** Builds a projection event upserting [item]'s poll record as deleted or live. */
    private fun upsertEvent(
        item: TimelineMessage,
        deleted: Boolean,
    ) = projectionEventOf(
        TimelineMessageChangeFfi.Upsert(
            TimelineUpdateTriggerFfi.NEW_MESSAGE,
            checkNotNull(item.projected).copy(deleted = deleted),
        ),
    )

    /** Builds a projection event removing [item]'s poll with [reason]. */
    private fun removeEvent(
        item: TimelineMessage,
        reason: TimelineRemoveReasonFfi,
    ) = projectionEventOf(TimelineMessageChangeFfi.Remove(item.record.messageIdHex, reason))

    /** Wraps one change in a projection update for the fixture's account and group. */
    private fun projectionEventOf(change: TimelineMessageChangeFfi) =
        MarmotEventFfi.ProjectionUpdated(
            RuntimeProjectionUpdateFfi(
                "ff".repeat(32),
                "personal",
                TimelineProjectionUpdateFfi(
                    pollController.group.groupIdHex,
                    emptyList(),
                    listOf(change),
                    null,
                    ChatListUpdateTriggerFfi.NEW_GROUP,
                ),
            ),
        )

    /** A votedPoll whose engine deadline is [seconds] after the fixture clock. */
    private fun expiringPoll(seconds: Long): TimelineMessage {
        val item = votedPoll()
        val deadline = (pollClockMillis / 1_000L + seconds).toULong()
        // Received rows defer send-time expiry until read, so the deadline is exercised on an own poll.
        return item.copy(record = item.record.copy(direction = "sent", retentionExpiresAt = deadline))
    }

    /** Mounts the poll row, opens its sheet with the compose clock paused, and returns once voters show. */
    private fun openWithPausedClock(item: TimelineMessage): MutableState<Boolean> {
        val rowShown = mutableStateOf(true)
        retain(item)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                ConversationHost {
                    if (rowShown.value) PollMessageContent(item, pollController, pollState, canVote = true)
                }
            }
        }
        openSheet()
        awaitTag("poll-voter-$voterA")
        composeRule.mainClock.autoAdvance = false
        return rowShown
    }

    /** A disappearing poll that passes its deadline closes the sheet even though no engine event arrives. */
    @Test
    fun expiredPollClosesTheSheet() {
        pollVotesResponder = { singleVoterPage() }
        val rowShown = openWithPausedClock(expiringPoll(60))
        rowShown.value = false

        pollClockMillis += 120_000L
        composeRule.mainClock.advanceTimeBy(61_000L)

        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.countTagged(POLL_VOTES_SHEET_TAG) == 0 }
    }

    /** A live row trimmed out of the bounded window is unknown, not expired, so the sheet stays open. */
    @Test
    fun trimmedLiveRowDoesNotCloseTheSheet() {
        pollVotesResponder = { singleVoterPage() }
        val item = expiringPoll(600)
        val rowShown = openWithPausedClock(item)
        rowShown.value = false
        pollController.timelineItemsById.remove(item.record.messageIdHex)

        pollClockMillis += 120_000L
        composeRule.mainClock.advanceTimeBy(700_000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(POLL_VOTES_SHEET_TAG).assertExists()
    }

    /** Recomposing a visible poll row as deleted, with no projection event, closes its open sheet. */
    @Test
    fun visibleRowDeletedClosesTheSheetWithoutAnEvent() {
        pollVotesResponder = { singleVoterPage() }
        val item = votedPoll()
        retain(item)
        val shown = mutableStateOf(item)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                ConversationHost { RealPollMessage(shown.value, false) {} }
            }
        }
        openSheet()
        awaitTag("poll-voter-$voterA")

        shown.value = item.copy(projected = checkNotNull(item.projected).copy(deleted = true))

        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.countTagged(POLL_VOTES_SHEET_TAG) == 0 }
    }
}
