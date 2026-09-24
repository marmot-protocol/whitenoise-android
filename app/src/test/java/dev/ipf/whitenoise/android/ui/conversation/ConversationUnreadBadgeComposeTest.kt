package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mounted jump badge across successive window replacements (#2726): the number it shows must
 * come from the one owner, hold while history pages replace the window, never flash 0 or a
 * page-local count on the way, and carry the `99+` boundary and the first frame the same way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationUnreadBadgeComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The screen's inputs, as Compose state so a test can replace the window like a page would. */
    private class Inputs {
        var timeline by mutableStateOf<List<TimelineMessage>>(emptyList())
        var readAnchor by mutableStateOf<String?>(null)
        var projection by mutableStateOf<Int?>(null)
        var windowReachesTail by mutableStateOf(true)
    }

    /** Mounts the real badge owner feeding the real button. */
    private fun mount(inputs: Inputs) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                val ui =
                    rememberConversationUnreadBadgeCount(
                        identity = "chat",
                        anchored = true,
                        timeline = inputs.timeline,
                        readAnchorMessageId = inputs.readAnchor,
                        projectionUnread = inputs.projection,
                        windowReachesTail = inputs.windowReachesTail,
                    )
                ConversationJumpToNewestButton(unreadIncomingCount = ui.count, onClick = {})
            }
        }
    }

    /**
     * Steps [frames] frames one at a time. On every frame the badge must read [expected] or still
     * [previous] (null = no badge) — the reconciling effect lands one frame after the input changed,
     * and the frame in between must show the old number, never 0 — and by the end it must read [expected].
     */
    private fun assertBadgeThroughFrames(
        expected: String?,
        previous: String? = expected,
        frames: Int = 3,
    ) {
        repeat(frames) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithText("0").assertDoesNotExist()
            // A frame may still show the previous number. When either side is "no badge", an absent
            // badge is one of the two legitimate states, so only the final frame is pinned to [expected].
            if (expected != null && previous != null) {
                val shown = listOf(expected, previous).distinct()
                assertTrue(
                    "badge must read one of $shown on every frame",
                    shown.any { composeRule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() },
                )
            }
        }
        if (expected != null) composeRule.onNodeWithText(expected).assertExists()
    }

    /** Three backward pages, the second evicting the anchor itself, leave the number where it was on every frame. */
    @Test
    fun badgeHoldsAcrossBackwardPagesAndNeverShowsZero() {
        val inputs =
            Inputs().apply {
                timeline = rows(0, 200)
                readAnchor = "r194"
                projection = 5
            }
        mount(inputs)
        composeRule.onNodeWithText("5").assertExists()
        composeRule.mainClock.autoAdvance = false

        inputs.timeline = rows(0, 150)
        inputs.windowReachesTail = false
        assertBadgeThroughFrames("5")
        inputs.timeline = rows(0, 100)
        assertBadgeThroughFrames("5")
        inputs.timeline = rows(0, 50)
        assertBadgeThroughFrames("5")
        inputs.timeline = rows(0, 200)
        inputs.windowReachesTail = true
        assertBadgeThroughFrames("5")
    }

    /** With nothing unread the button carries no badge through every page, and the first arrival shows as 1. */
    @Test
    fun noUnreadShowsNoBadgeThroughPagesUntilAnArrival() {
        val inputs =
            Inputs().apply {
                timeline = rows(0, 200)
                readAnchor = "r199"
                projection = 0
            }
        mount(inputs)
        composeRule.onNodeWithText("0").assertDoesNotExist()
        composeRule.mainClock.autoAdvance = false

        inputs.timeline = rows(0, 150)
        inputs.windowReachesTail = false
        assertBadgeThroughFrames(null)
        inputs.timeline = rows(0, 100)
        assertBadgeThroughFrames(null)
        composeRule.onNodeWithText("50").assertDoesNotExist()
        composeRule.onNodeWithText("100").assertDoesNotExist()

        inputs.projection = 1
        assertBadgeThroughFrames("1", previous = null)
    }

    /**
     * The `99+` cap holds across a page, steps down as rows are read inside a partial window, and
     * recounts at the tail.
     */
    @Test
    fun ninetyNinePlusHoldsAcrossPagesAndStepsDownAsRowsAreRead() {
        val inputs =
            Inputs().apply {
                timeline = rows(0, 200)
                readAnchor = "r79"
                projection = 120
            }
        mount(inputs)
        composeRule.onNodeWithText("99+").assertExists()
        composeRule.mainClock.autoAdvance = false

        inputs.timeline = rows(0, 150)
        inputs.windowReachesTail = false
        assertBadgeThroughFrames("99+")
        inputs.readAnchor = "r109"
        assertBadgeThroughFrames("90", previous = "99+")
        inputs.timeline = rows(0, 200)
        inputs.windowReachesTail = true
        assertBadgeThroughFrames("90")
    }

    /** A mid-history open shows the projection, not the page, and keeps it across forward pages. */
    @Test
    fun midHistoryOpenShowsTheProjectionAcrossForwardPages() {
        val inputs =
            Inputs().apply {
                timeline = rows(0, 50)
                readAnchor = "r10"
                projection = 300
                windowReachesTail = false
            }
        mount(inputs)
        composeRule.onNodeWithText("99+").assertExists()
        composeRule.mainClock.autoAdvance = false

        inputs.timeline = rows(0, 100)
        assertBadgeThroughFrames("99+")
        composeRule.onNodeWithText("39").assertDoesNotExist()
        composeRule.onNodeWithText("89").assertDoesNotExist()
        inputs.timeline = rows(0, 150)
        assertBadgeThroughFrames("99+")
    }

    /** Mounted already anchored, the very first frame carries the number rather than gaining it a frame later. */
    @Test
    fun firstFrameCarriesTheCountWhenAlreadyAnchored() {
        val inputs =
            Inputs().apply {
                timeline = rows(0, 200)
                readAnchor = "r194"
                projection = 5
            }
        composeRule.mainClock.autoAdvance = false
        mount(inputs)

        composeRule.onNodeWithText("5").assertExists()
        composeRule.onNodeWithText("0").assertDoesNotExist()
    }

    /** Received rows `r<from>`..`r<until - 1>`. */
    private fun rows(
        from: Int,
        until: Int,
    ): List<TimelineMessage> = (from until until).map { received("r$it") }

    /** One received row with the fields the badge reads. */
    private fun received(id: String): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = "received",
                    groupIdHex = "group",
                    sender = "peer",
                    plaintext = "text-$id",
                    contentTokens =
                        MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
                    kind = 9uL,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = 1uL,
                    receivedAt = 1uL,
                ),
            status = MessageStatus.Received,
        )
}
