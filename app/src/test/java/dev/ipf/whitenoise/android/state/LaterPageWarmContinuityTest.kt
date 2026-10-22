package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.EVENT_ID
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.LOADED
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.NOTE
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.READ_BLOCK_SECONDS
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.describe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What the reader sees while a later page's event cards are read before it is applied (#2238): a far jump keeps the
 * previous transcript until the jumped-to window shows complete, and the reader's own send never disappears.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class LaterPageWarmContinuityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val navigationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Cancels any navigation still in flight when a test ends. */
    @After
    fun cancelNavigation() {
        navigationScope.cancel()
    }

    /**
     * A far jump into a window with a card row: the previous transcript stays while the card is read, then the new
     * window replaces it in one frame. No frame is blank, the card first composes Loaded, and the landing returns with
     * its target on screen.
     */
    @Test
    fun farJumpShowsTheJumpedToWindowCompleteWithNoBlankFrame() {
        val read = CountDownLatch(1)
        val farWindow =
            timelinePage(
                timelineRecord(FAR_PLAIN_ID, 1uL, plaintext = "far plain"),
                timelineRecord(FAR_CARD_ID, 2uL, plaintext = "nostr:$NOTE"),
                timelineRecord(TARGET_ID, 3uL, plaintext = "the target"),
            ).copy(hasMoreAfter = true)
        val fixture =
            EventCardConversationFixture(
                composeRule = composeRule,
                snapshotPage =
                    timelinePage(
                        timelineRecord(NEW_A_ID, 100uL, plaintext = "newer a"),
                        timelineRecord(NEW_B_ID, 101uL, plaintext = "newer b"),
                    ).copy(hasMoreBefore = true),
                jumpOutcomes = mutableListOf(ConversationJumpOutcome.Window(TimelinePageOutcome.Advanced(farWindow))),
                cacheRead = { read.await(READ_BLOCK_SECONDS, TimeUnit.SECONDS) },
            )
        try {
            fixture.compose()
            fixture.awaitIdleCondition { fixture.controller.timeline.size == SHOWN.size }
            val landing = Landing()
            navigationScope.launch {
                val availability = fixture.controller.loadMessageAvailability(TARGET_ID)
                landing.targetShownOnReturn = TARGET_ID in timelineMessageIds(fixture.controller)
                landing.availability = availability
            }
            fixture.awaitIdleCondition { fixture.cacheReads.get() >= 1 }
            assertEquals("the transcript stays during the read", SHOWN, timelineMessageIds(fixture.controller))

            read.countDown()
            fixture.awaitIdleCondition { landing.availability != null }

            val far = listOf(FAR_PLAIN_ID, FAR_CARD_ID, TARGET_ID)
            assertEquals(MessageAvailability.AVAILABLE, landing.availability)
            assertTrue("the landing returned before its target was shown", landing.targetShownOnReturn)
            assertEquals(far, timelineMessageIds(fixture.controller))
            assertEquals(listOf(SHOWN, far), fixture.composedOrders.dropWhile { it != SHOWN })
            assertEquals(listOf("$LOADED $EVENT_ID"), fixture.composed.map(::describe).distinct())
        } finally {
            read.countDown()
            fixture.close()
        }
    }

    /**
     * The echo of a send without a client token arrives in a live window whose card row is being read. Nothing is
     * hidden meanwhile: the send is on screen in every frame, as its bubble until the window applies, then as the
     * settled row.
     */
    @Test
    fun ownSendStaysVisibleWhileAWindowCarryingItsEchoIsWarming() {
        val read = CountDownLatch(1)
        val newest = timelineRecord(NEW_A_ID, 1uL, plaintext = "plain newest")
        val card = timelineRecord(FAR_CARD_ID, 2uL, plaintext = "nostr:$NOTE")
        val echo = ownText(ECHO_ID, 3uL)
        val fixture =
            EventCardConversationFixture(
                composeRule = composeRule,
                snapshotPage = timelinePage(newest),
                seedOptimistic = { appState ->
                    val optimistic =
                        appState.optimisticMessages(
                            ConversationTimelineTestIds.ACCOUNT_REF,
                            ConversationTimelineTestIds.GROUP_ID,
                        )
                    optimistic["msg:$PENDING_ID"] = pendingBubble(echo)
                },
                cacheRead = { read.await(READ_BLOCK_SECONDS, TimeUnit.SECONDS) },
            )
        try {
            fixture.compose()
            fixture.awaitIdleCondition { PENDING_ID in timelineMessageIds(fixture.controller) }
            fixture.timelineScript.emitWindow(timelinePage(newest, card, echo))
            fixture.awaitIdleCondition { fixture.drainLiveWindows() && fixture.cacheReads.get() >= 1 }
            assertTrue(PENDING_ID in timelineMessageIds(fixture.controller))

            read.countDown()
            fixture.awaitIdleCondition { FAR_CARD_ID in timelineMessageIds(fixture.controller) }

            val framesFromTheSend = fixture.composedOrders.dropWhile { PENDING_ID !in it }
            assertTrue("the send was composed", framesFromTheSend.isNotEmpty())
            framesFromTheSend.forEach { order ->
                assertTrue("the send vanished in $order", PENDING_ID in order || ECHO_ID in order)
            }
            assertEquals(listOf(NEW_A_ID, FAR_CARD_ID, ECHO_ID), timelineMessageIds(fixture.controller))
        } finally {
            read.countDown()
            fixture.close()
        }
    }

    /** What a landing saw when it returned, filled in on the main thread. */
    private class Landing {
        @Volatile var availability: MessageAvailability? = null

        @Volatile var targetShownOnReturn = false
    }

    /** The reader's own text row, as MDK echoes it without a client token. */
    private fun ownText(
        messageId: String,
        timelineAt: ULong,
    ): TimelineMessageRecordFfi =
        timelineRecord(messageId, timelineAt, plaintext = "my own words").copy(
            direction = "sent",
            sender = ConversationTimelineTestIds.ACCOUNT_ID,
            clientToken = null,
        )

    /** The pending bubble the composer showed for [echo] before the engine answered, matching it only by shape. */
    private fun pendingBubble(echo: TimelineMessageRecordFfi): TimelineMessage =
        TimelineMessage(
            id = "msg:$PENDING_ID",
            record = TimelineProjector.toAppMessageRecord(echo).copy(messageIdHex = PENDING_ID),
            status = MessageStatus.Pending,
            timelineOrder = 1_000uL,
        )

    /**
     * Moves the main looper's clock just past the live pipeline's batch drain, so a delivered window is taken up, and
     * returns true so it can lead a wait's condition. The step is far below the card budget.
     */
    private fun EventCardConversationFixture.drainLiveWindows(): Boolean {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LIVE_DRAIN_STEP_MILLIS))
        return controller.timeline.isNotEmpty()
    }

    private companion object {
        val NEW_A_ID = "d1".repeat(32)
        val NEW_B_ID = "d2".repeat(32)
        val FAR_PLAIN_ID = "c1".repeat(32)
        val FAR_CARD_ID = "c2".repeat(32)
        val TARGET_ID = "c3".repeat(32)
        val ECHO_ID = "e1".repeat(32)
        val PENDING_ID = "f1".repeat(32)
        val SHOWN = listOf(NEW_A_ID, NEW_B_ID)
        const val LIVE_DRAIN_STEP_MILLIS = 8L
    }
}
