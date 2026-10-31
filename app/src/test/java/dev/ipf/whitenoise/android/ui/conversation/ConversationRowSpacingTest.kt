package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.TimelineReactionEmojiFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.conversation.reactions.REACTION_PILL_TEST_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** Measures production rows rather than a Boolean spacing helper. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationRowSpacingTest : ConversationRowSpacingFixtures() {
    @get:Rule val composeRule = createComposeRule()
    private val originalTimeZone = TimeZone.getDefault()
    private val controllers = mutableListOf<ConversationController>()

    @Before fun pinTimeZone() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After fun releaseControllers() {
        controllers.forEach { it.onCleared() }
        pollController.onCleared()
        TimeZone.setDefault(originalTimeZone)
    }

    /** Run boundaries alter attribution, never the empty host padding or list interval. */
    @Test fun bubbleIntervalsStayCompactAcrossTimeSenderAndDirectChrome() {
        var rows by mutableStateOf(listOf(message(1), message(2)))
        var controller by mutableStateOf(pollController)
        composeRule.setContent { Transcript(rows, controller = controller) }
        val directController =
            ConversationController(
                appState = pollState,
                initialGroup = group(),
                initialIsDm = true,
                initialMemberSnapshot =
                    GroupMemberSnapshot(
                        listOf(
                            AppGroupMemberRecordFfi(message(1, mine = true).record.sender, "personal", true),
                            AppGroupMemberRecordFfi(message(1).record.sender, null, false),
                        ),
                    ),
            ).also { controllers += it }
        assertTrue("the direct fixture must use the DM branch", directController.isDm)
        listOf(pollController, directController).forEach { chrome ->
            listOf(179uL, 180uL, 181uL, 3600uL).forEach { delta ->
                listOf(false, true).forEach { firstMine ->
                    listOf(false, true).forEach { secondMine ->
                        composeRule.runOnIdle {
                            controller = chrome
                            rows = listOf(message(1, 1uL, firstMine), message(2, 1uL + delta, secondMine))
                        }
                        assertBubbleBoundary(rows[0], rows[1])
                    }
                }
            }
        }
    }

    /** Rename/avatar/description/membership summaries keep equal compact intervals on both sides. */
    @Test fun simpleActivityKeepsBalancedCompactIntervals() {
        var rows by mutableStateOf(listOf(message(1, 0uL), summary(), message(2)))
        composeRule.setContent { Transcript(rows) }
        listOf("group_renamed", "group_avatar_changed", "group_description_changed", "member_removed").forEach { type ->
            composeRule.runOnIdle { rows = listOf(message(1, 0uL), summary(type), message(2)) }
            val previous = hostBounds(rows[0])
            val host = hostBounds(rows[1])
            val next = hostBounds(rows[2])
            // Equal activity insets keep its summary balanced between compact bubble rows.
            val text =
                composeRule
                    .onNode(hasText(summaryFragment(type), substring = true), useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
            val touch =
                composeRule
                    .onNode(hasText(summaryFragment(type), substring = true), useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .touchBoundsInRoot
            assertTrue("summary minimum touch target", touch.height >= 48f)
            assertEquals("$type bottom padding", 8f, host.bottom.value - text.bottom.value, 0.5f)
            assertEquals("$type upper interval", 10f, text.top.value - previous.bottom.value, 0.5f)
            assertEquals("$type lower interval", 10f, next.top.value - text.bottom.value, 0.5f)
            assertEquals("$type top padding", 8f, text.top.value - host.top.value, 0.5f)
        }
    }

    /** The full-width bubble hit region wins over the preceding summary's expanded target. */
    @Test
    fun compactActivityCannotStealTheFollowingBubbleLongPress() {
        val activity = summary("group_renamed")
        val bubble = message(2, text = "Reply after the rename")
        listOf(activity, bubble).forEach(::retain)
        var openedMessage: String? = null
        composeRule.setContent {
            Transcript(listOf(activity, bubble), onMenuChange = { item, open ->
                if (open) openedMessage = item.record.messageIdHex
            })
        }
        composeRule
            .onNode(hasText("Evening readers", substring = true), useUnmergedTree = true)
            .performTouchInput { longClick() }
        composeRule.runOnIdle {
            assertEquals("the summary keeps its own action target", activity.record.messageIdHex, openedMessage)
            openedMessage = null
        }
        composeRule
            .onNodeWithTag("host:${bubble.record.messageIdHex}")
            .performTouchInput { longClick(Offset(center.x, 2f)) }
        composeRule.runOnIdle {
            assertEquals("the following row owns its top strip", bubble.record.messageIdHex, openedMessage)
        }
    }

    /** Day/unread boundaries retain the activity's readable inset and their own separators. */
    @Test fun separatorsAndConsecutiveActivitiesRetainTheirInsets() {
        val activity = summary()
        var rows by mutableStateOf(listOf(activity, message(2)))
        var unreadId by mutableStateOf<String?>(rows[1].record.messageIdHex)
        composeRule.setContent { Transcript(rows, firstUnreadId = unreadId) }
        assertActivityBottomInset(8f)
        val divider =
            composeRule.onNodeWithTag(UNREAD_MESSAGES_DIVIDER_CONTENT_TAG).getUnclippedBoundsInRoot()
        assertEquals(
            "unread divider alone owns the bubble interval",
            18f,
            bubbleBounds(rows[1]).top.value - divider.bottom.value,
            0.5f,
        )
        composeRule.runOnIdle {
            unreadId = null
            rows = listOf(activity, message(2, 86_400uL))
        }
        assertActivityBottomInset(8f)
        assertTrue(
            hostBounds(rows[1]).let { it.bottom.value - it.top.value } >
                bubbleBounds(rows[1]).let { it.bottom.value - it.top.value },
        )
        composeRule.runOnIdle {
            rows =
                listOf(
                    activity,
                    summary("group_renamed").let {
                        it.copy(id = "second-activity", record = it.record.copy(messageIdHex = "ff".repeat(32)))
                    },
                )
        }
        assertActivityBottomInset(8f)
    }

    /** Reacted activity preserves its complete pill flow and existing bottom inset. */
    @Test fun activityReactionsRemainInsideTheOccupiedBoundary() {
        val activity = summary()
        val rows = listOf(activity, message(2))
        applyPage(
            activity.projected!!.copy(
                reactions =
                    TimelineReactionSummaryFfi(
                        byEmoji = listOf(TimelineReactionEmojiFfi("👍", 1u, listOf(activity.record.sender))),
                        userReactions = emptyList(),
                    ),
            ),
        )
        composeRule.setContent { Transcript(rows) }
        val pill = composeRule.onNodeWithTag("$REACTION_PILL_TEST_TAG:0").getUnclippedBoundsInRoot()
        val activityBounds = hostBounds(activity)
        assertTrue(pill.bottom.value <= activityBounds.bottom.value)
        assertEquals(8f, activityBounds.bottom.value - pill.bottom.value, 0.5f)
        assertEquals(2f, hostBounds(rows[1]).top.value - activityBounds.bottom.value, 0.5f)
    }

    /** Reversed-list neighbor recomposition follows insertion, deletion and prepended paging. */
    @Test fun adjacencyRecomposesWithBalancedActivityPadding() {
        val activity = summary()
        val bubble = message(2, 181uL)
        var rows by mutableStateOf(listOf(activity, bubble))
        composeRule.setContent { Transcript(rows, dark = true, amoled = true, rtl = true, fontScale = 1.6f) }
        assertActivityBottomInset(8f)
        val inserted = message(3, 90uL, mine = true)
        composeRule.runOnIdle { rows = listOf(activity, inserted, bubble) }
        assertBubbleBoundary(inserted, bubble)
        composeRule.runOnIdle { rows = listOf(message(4, 0uL), activity, bubble) }
        assertActivityBottomInset(8f)
        composeRule.runOnIdle { rows = listOf(message(4, 0uL), bubble) }
        assertBubbleBoundary(rows[0], bubble)
    }

    private fun assertBubbleBoundary(
        older: TimelineMessage,
        newer: TimelineMessage,
    ) {
        val first = hostBounds(older)
        val second = hostBounds(newer)
        assertEquals("timeline interval", 2f, second.top.value - first.bottom.value, 0.5f)
        assertEquals("no extra bubble host top padding", second.top.value, bubbleBounds(newer).top.value, 0.5f)
        assertEquals(
            "complete chrome remains inside its host",
            first.bottom.value,
            bubbleBounds(older).bottom.value,
            0.5f,
        )
    }

    private fun assertActivityBottomInset(expected: Float) {
        val host = composeRule.onNodeWithTag("host:${summary().record.messageIdHex}").getUnclippedBoundsInRoot()
        val text =
            composeRule
                .onNode(hasText("group avatar", substring = true), useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        assertEquals(expected, host.bottom.value - text.bottom.value, 0.5f)
    }

    private fun hostBounds(item: TimelineMessage) =
        composeRule
            .onNodeWithTag("host:${item.record.messageIdHex}")
            .getUnclippedBoundsInRoot()

    private fun bubbleBounds(item: TimelineMessage) =
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(item.record.messageIdHex), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

    private fun summaryFragment(type: String): String =
        when (type) {
            "group_renamed" -> "Evening readers"
            "group_avatar_changed" -> "group avatar"
            "group_description_changed" -> "Group updated"
            else -> "removed"
        }
}
