package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.marmotkit.ConversationMessageReferencesFfi
import dev.ipf.marmotkit.ConversationReactionFfi
import dev.ipf.marmotkit.ConversationReactionsFfi
import dev.ipf.marmotkit.ConversationWindowRevisionFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.state.TimelinePageOutcome.Advanced
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Someone else's reaction reaches an open conversation as a window replacement whose rows are unchanged:
 * MarmotKit carries reactions only in the per-message references sidecar, never in the timeline record.
 * The chip must still follow that replacement live, for an add and for a removal, without a reopen (#2990).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationLiveReactionReferenceTest {
    private val messageId = ConversationTimelineTestIds.MESSAGE_A
    private val page = timelinePage(timelineRecord(messageId = messageId, timelineAt = 1uL))

    /** A reaction arriving on an unchanged row shows its chip on the live replacement and leaves on the next. */
    @Test
    fun incomingReactionOnUnchangedRowUpdatesTheChipWithoutReopening() =
        runBlocking {
            val handle = InstalledWindowHandle(page, frame(sequence = 1uL, reactions = null))
            val controller = controller(handle)
            try {
                awaitConversationCondition { timelineMessageIds(controller) == listOf(messageId) }
                assertNull(controller.reactions[messageId])

                handle.replace(frame(sequence = 2uL, reactions = thumbsUp()))
                awaitLiveWindowApplied(handle, calls = 2) { controller.reactions[messageId] != null }
                assertEquals(listOf(ReactionTally("👍", 1, mine = false)), controller.reactions[messageId])

                handle.replace(frame(sequence = 3uL, reactions = null))
                awaitLiveWindowApplied(handle, calls = 3) { controller.reactions[messageId] == null }
            } finally {
                controller.onCleared()
                awaitConversationCondition { handle.closed }
            }
        }

    /** Activity rows accept the same live sidecar and rehydrate its counts on conversation reentry. */
    @Test
    fun activityReactionSurvivesLiveUpdateAndReentry() =
        runBlocking {
            val activityPage = page.copy(messages = page.messages.map { it.copy(kind = 1210uL, direction = "system") })
            val first = InstalledWindowHandle(activityPage, frame(sequence = 1uL, reactions = null))
            val firstController = controller(first)
            try {
                awaitConversationCondition { timelineMessageIds(firstController) == listOf(messageId) }
                first.replace(frame(sequence = 2uL, reactions = thumbsUp()))
                awaitLiveWindowApplied(first, calls = 2) { firstController.reactions[messageId] != null }
                assertEquals(listOf(messageId), timelineMessageIds(firstController))
                assertEquals(listOf(ReactionTally("👍", 1, mine = false)), firstController.reactions[messageId])
            } finally {
                firstController.onCleared()
                awaitConversationCondition { first.closed }
            }
            val restored = InstalledWindowHandle(activityPage, frame(sequence = 3uL, reactions = thumbsUp()))
            val restoredController = controller(restored)
            try {
                awaitConversationCondition { restoredController.reactions[messageId] != null }
                assertEquals(listOf(messageId), timelineMessageIds(restoredController))
                assertEquals(listOf(ReactionTally("👍", 1, mine = false)), restoredController.reactions[messageId])
                restored.replace(frame(sequence = 4uL, reactions = null))
                awaitLiveWindowApplied(restored, calls = 2) { restoredController.reactions[messageId] == null }
            } finally {
                restoredController.onCleared()
                awaitConversationCondition { restored.closed }
            }
        }

    /** Only messages whose sidecar reactions differ from the installed frame are reported. */
    @Test
    fun reactionReferenceChangesNameOnlyTheMovedMessages() {
        val state = ConversationWindowState()
        val first = frame(sequence = 1uL, reactions = thumbsUp())
        assertEquals(setOf(messageId), state.reactionReferenceChanges(first))
        state.install(first)
        assertEquals(emptySet<String>(), state.reactionReferenceChanges(frame(sequence = 2uL, reactions = thumbsUp())))
        assertEquals(setOf(messageId), state.reactionReferenceChanges(frame(sequence = 3uL, reactions = null)))
    }

    /**
     * A row the window no longer retains (slid outside MDK's bounded window) is not reported, so its
     * retained tally is left as it was rather than cleared; it refreshes once the window covers it again.
     */
    @Test
    fun rowsOutsideTheWindowKeepTheirRetainedTally() {
        val state = ConversationWindowState()
        state.install(frame(sequence = 1uL, reactions = thumbsUp()))
        val slidPast = frame(sequence = 2uL, reactions = null).copy(references = emptyMap())
        assertEquals(emptySet<String>(), state.reactionReferenceChanges(slidPast))
    }

    /**
     * Waits for the pump to take the replacement and for [condition] to hold. The pump's batch drain is a
     * short main-looper timeout, so the paused looper is nudged forward while waiting.
     */
    private fun awaitLiveWindowApplied(
        handle: InstalledWindowHandle,
        calls: Int,
        condition: () -> Boolean,
    ) {
        awaitConversationCondition { handle.nextWindowCalls >= calls }
        awaitConversationCondition {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            condition()
        }
    }

    /** A started controller whose only timeline seam is [handle]. */
    private fun controller(handle: ConversationTimelineSubscriptionHandle): ConversationController {
        val group = conversationTimelineTestGroup()
        val subscriptions =
            ConversationLiveSubscriptions(
                openTimeline = { _, _, _ -> handle },
                openGroupState = { _, _ -> ScriptedConversationGroupStateSubscription(group) },
            )
        return ConversationController(
            appState = conversationTimelineTestAppState(subscriptions),
            initialGroup = conversationTimelineTestGroup(),
            initialMemberSnapshot = conversationTimelineMemberSnapshot(),
            groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
            startOnConstruction = true,
        )
    }

    /** A sidecar at [sequence] whose only message carries [reactions], or no reactions at all. */
    private fun frame(
        sequence: ULong,
        reactions: ConversationReactionsFfi?,
    ): ConversationWindowFrame =
        MarmotWindowTestFakes.conversationFrame("Timeline group").copy(
            revision = ConversationWindowRevisionFfi("test", sequence),
            references =
                mapOf(
                    messageId to
                        ConversationMessageReferencesFfi(
                            messageIdHex = messageId,
                            sender = null,
                            replyAuthor = null,
                            mentions = emptyList(),
                            mentionsTruncated = false,
                            replyMentions = emptyList(),
                            replyMentionsTruncated = false,
                            system = null,
                            reactions = reactions ?: ConversationReactionsFfi(0uL, 0uL, emptyList(), 0uL),
                        ),
                ),
        )

    /** One thumbs-up from another member, the shape MDK's bounded reaction references take. */
    private fun thumbsUp(): ConversationReactionsFfi =
        ConversationReactionsFfi(
            totalCount = 1uL,
            totalKinds = 1uL,
            items =
                listOf(
                    ConversationReactionFfi(
                        emoji = "👍",
                        count = 1uL,
                        reactors = listOf(ConversationTimelineTestIds.SENDER_ID),
                        viewerReacted = false,
                        reactionMessageIdHex = null,
                    ),
                ),
            omittedKinds = 0uL,
        )

    /**
     * A window seam whose rows never change: every replacement re-delivers the same page under a new
     * sidecar, exactly what MDK streams when someone reacts to a message already on screen.
     */
    private class InstalledWindowHandle(
        private val page: TimelinePageFfi,
        frame: ConversationWindowFrame,
    ) : ConversationTimelineSubscriptionHandle {
        private val replacements = Channel<TimelinePageFfi>(Channel.UNLIMITED)

        @Volatile
        private var installed = InstalledConversationWindow(page, frame)

        @Volatile
        var closed = false
            private set

        /** Installs [frame] as the newest replacement and streams it to the controller's pump. */
        fun replace(frame: ConversationWindowFrame) {
            installed = InstalledConversationWindow(page, frame)
            check(replacements.trySend(page).isSuccess) { "replacement stream is closed" }
        }

        /** The installed page doubles as the opening snapshot. */
        override fun snapshot(): TimelinePageFfi = page

        /** How many times the pump asked for a replacement; the n-th call follows the (n-1)-th delivery. */
        @Volatile
        var nextWindowCalls = 0
            private set

        /** Delivers the next replacement, or ends the stream once the seam is closed. */
        override suspend fun nextWindow(): TimelinePageFfi? {
            nextWindowCalls += 1
            return replacements.receiveCatching().getOrNull()
        }

        /** Paging leaves the installed window where it is. */
        override suspend fun paginateBackwards(count: UInt): TimelinePageOutcome = Advanced(page)

        /** Paging leaves the installed window where it is. */
        override suspend fun paginateForwards(count: UInt): TimelinePageOutcome = Advanced(page)

        /** The newest replacement installed by [replace]. */
        override fun latestInstalledWindow(): InstalledConversationWindow = installed

        /** Ends the stream so the pump finishes. */
        override fun close() {
            closed = true
            replacements.close()
        }
    }
}
