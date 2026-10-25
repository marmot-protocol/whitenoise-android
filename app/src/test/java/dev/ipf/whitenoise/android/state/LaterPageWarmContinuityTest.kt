package dev.ipf.whitenoise.android.state

import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.EVENT_ID
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.LOADED
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.NOTE
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.READ_BLOCK_SECONDS
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.SECOND_EVENT_ID
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.SECOND_NOTE
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.describe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
     * A far jump into a window with a card row, once the conversation is ready: the previous transcript stays while the
     * card is read, then the new window replaces it in one frame. No frame is blank, the card first composes Loaded,
     * and the landing returns with its target on screen.
     */
    @Test
    fun farJumpShowsTheJumpedToWindowCompleteWithNoBlankFrame() {
        val read = CountDownLatch(1)
        val fixture = farJumpConversation(read)
        try {
            fixture.compose()
            // A later page reads its cards only once the first presentation is ready, and before that the first
            // page's hold reads them, so the jump waits for readiness to take the path this test is about.
            fixture.awaitIdleCondition {
                fixture.controller.hasPreparedInitialPresentation && fixture.controller.timeline.size == SHOWN.size
            }
            val landing = fixture.launchLanding()
            fixture.awaitIdleCondition { fixture.cacheReads.get() >= 1 }
            assertEquals("the transcript stays during the read", SHOWN, timelineMessageIds(fixture.controller))

            read.countDown()
            fixture.awaitIdleCondition { landing.availability != null }

            fixture.assertLandedCompleteOnTheFarWindow(landing)
        } finally {
            read.countDown()
            fixture.close()
        }
    }

    /** An early landing cannot claim a row while the initial card read still holds its publication. */
    @Test
    fun earlyLandingRemainsRetryableUntilItsInitialCardPageIsPublished() {
        val read = CountDownLatch(1)
        val fixture =
            EventCardConversationFixture(
                composeRule = composeRule,
                snapshotPage = timelinePage(timelineRecord(TARGET_ID, 1uL, plaintext = "nostr:$NOTE")),
                cacheRead = { read.await(READ_BLOCK_SECONDS, TimeUnit.SECONDS) },
            )
        try {
            fixture.compose()
            fixture.awaitIdleCondition { fixture.cacheReads.get() >= 1 }
            val landing = Landing()
            navigationScope.launch {
                landing.availability = fixture.controller.loadMessageAvailability(TARGET_ID)
            }
            fixture.awaitIdleCondition { landing.availability != null }
            assertEquals(MessageAvailability.RETRYABLE, landing.availability)
            assertTrue(TARGET_ID !in timelineMessageIds(fixture.controller))

            read.countDown()
            fixture.awaitIdleCondition {
                fixture.controller.hasPreparedInitialPresentation && TARGET_ID in timelineMessageIds(fixture.controller)
            }
            landing.availability = null
            navigationScope.launch {
                landing.availability = fixture.controller.loadMessageAvailability(TARGET_ID)
            }
            fixture.awaitIdleCondition { landing.availability != null }
            assertEquals(MessageAvailability.AVAILABLE, landing.availability)
        } finally {
            read.countDown()
            fixture.close()
        }
    }

    /**
     * A far jump that commits before the conversation's first presentation is ready, here held by its profile read:
     * the jumped-to window is held unpublished for its card like a first page (#2238). The landing waits until that
     * window is shown, so it never reports a target the reader cannot see, and the window still first composes with
     * its card Loaded and replaces the transcript in one frame.
     */
    @Test
    fun farJumpBeforeTheConversationIsReadyReturnsOnceItsHeldWindowIsShown() {
        val read = CountDownLatch(1)
        val profile = CountDownLatch(1)
        val fixture = farJumpConversation(read, profileRead = { profile.await(READ_BLOCK_SECONDS, TimeUnit.SECONDS) })
        try {
            fixture.compose()
            fixture.awaitIdleCondition { fixture.controller.timeline.size == SHOWN.size }
            assertFalse("the profile read holds readiness", fixture.controller.hasPreparedInitialPresentation)
            val landing = fixture.launchLanding()
            fixture.awaitIdleCondition {
                fixture.controller.isHoldingInitialPageForEventCards && fixture.cacheReads.get() >= 1
            }
            assertEquals("the transcript stays while the window is held", SHOWN, timelineMessageIds(fixture.controller))
            assertNull("the landing returned while its window was held", landing.availability)

            read.countDown()
            profile.countDown()
            fixture.awaitIdleCondition { landing.availability != null }

            fixture.assertLandedCompleteOnTheFarWindow(landing)
        } finally {
            read.countDown()
            profile.countDown()
            fixture.close()
        }
    }

    /**
     * Once the conversation is ready, the echo of a send without a client token arrives in a live window whose card row
     * is being read. Nothing is hidden meanwhile: the send is on screen in every frame, as its bubble until the window
     * applies, then as the settled row.
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
            // Ready first, so the live window reads its card before it applies instead of falling under the first
            // page's hold, or applying unread when readiness lands between that check and its commit.
            fixture.awaitIdleCondition {
                val shown = timelineMessageIds(fixture.controller)
                fixture.controller.hasPreparedInitialPresentation && PENDING_ID in shown
            }
            val emittedAt = SystemClock.elapsedRealtime()
            fixture.timelineScript.emitWindow(timelinePage(newest, card, echo))
            fixture.awaitIdleCondition { fixture.drainLiveWindows() && fixture.cacheReads.get() >= 1 }
            // A bounded card warm can install the echo while its native cache read remains blocked.
            val rowsDuringRead = timelineMessageIds(fixture.controller)
            val readPhase =
                "${fixture.describeState()} latchCount=${read.count} rows=$rowsDuringRead " +
                    "sinceEmitMs=${SystemClock.elapsedRealtime() - emittedAt}"
            println("own-send before cache release: $readPhase")
            assertEquals(
                "the send needs exactly one representation: $readPhase",
                1,
                rowsDuringRead.count { it == PENDING_ID || it == ECHO_ID },
            )

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

    /**
     * A newer window is installed while an older page's cards are read, carrying a second previously resolved card the
     * older page did not have, and newer metadata for the older card's unchanged message. The newer window is what gets
     * committed, so its card is read too within the same budget, both cards first compose Loaded, and the older
     * message keeps the newer window's metadata: only its parsed Markdown is carried over.
     */
    @Test
    fun newerWindowInstalledDuringTheReadHasItsCardsReadToo() {
        val read = CountDownLatch(1)
        val newest = timelineRecord(NEW_A_ID, 3uL, plaintext = "plain newest")
        val olderCard = timelineRecord(FAR_CARD_ID, 1uL, plaintext = "nostr:$NOTE")
        val newerCard = timelineRecord(TARGET_ID, 2uL, plaintext = "nostr:$SECOND_NOTE")
        val fixture =
            EventCardConversationFixture(
                composeRule = composeRule,
                snapshotPage = timelinePage(newest).copy(hasMoreBefore = true),
                backwardsPage = timelinePage(olderCard, newest),
                cacheRead = { read.await(READ_BLOCK_SECONDS, TimeUnit.SECONDS) },
            )
        try {
            fixture.compose()
            fixture.awaitIdleCondition { fixture.controller.hasPreparedInitialPresentation }
            var advanced: Boolean? = null
            navigationScope.launch { advanced = fixture.controller.loadOlderTimelinePage() }
            fixture.awaitIdleCondition { fixture.cacheReads.get() >= 1 }
            fixture.timelineScript.installedWindow =
                InstalledConversationWindow(
                    page =
                        timelinePage(
                            olderCard.copy(hasReports = true, sourceEpoch = NEWER_EPOCH),
                            newerCard,
                            newest,
                        ),
                    frame = MarmotWindowTestFakes.conversationFrame("Timeline group"),
                )

            read.countDown()
            fixture.awaitIdleCondition { advanced != null && fixture.composed.size >= 2 }

            assertEquals(listOf(FAR_CARD_ID, TARGET_ID, NEW_A_ID), timelineMessageIds(fixture.controller))
            assertEquals(
                "no card composes Loading first",
                setOf("$LOADED $EVENT_ID", "$LOADED $SECOND_EVENT_ID"),
                fixture.composed.map(::describe).toSet(),
            )
            val committed = checkNotNull(fixture.controller.timelineRecords[FAR_CARD_ID])
            assertTrue("the newer window's metadata survives", committed.hasReports)
            assertEquals(NEWER_EPOCH, committed.sourceEpoch)
            assertTrue("the parsed Markdown was carried", committed.contentTokens.blocks.isNotEmpty())
        } finally {
            read.countDown()
            fixture.close()
        }
    }

    /**
     * The conversation [farJumpShowsTheJumpedToWindowCompleteWithNoBlankFrame] and its pre-readiness twin open: two
     * plain newest rows, and a jump answered with a far window whose middle row names a cached card, read after [read].
     */
    private fun farJumpConversation(
        read: CountDownLatch,
        profileRead: () -> Unit = {},
    ): EventCardConversationFixture =
        EventCardConversationFixture(
            composeRule = composeRule,
            snapshotPage =
                timelinePage(
                    timelineRecord(NEW_A_ID, 100uL, plaintext = "newer a"),
                    timelineRecord(NEW_B_ID, 101uL, plaintext = "newer b"),
                ).copy(hasMoreBefore = true),
            jumpOutcomes =
                mutableListOf(
                    ConversationJumpOutcome.Window(
                        TimelinePageOutcome.Advanced(
                            timelinePage(
                                timelineRecord(FAR_PLAIN_ID, 1uL, plaintext = "far plain"),
                                timelineRecord(FAR_CARD_ID, 2uL, plaintext = "nostr:$NOTE"),
                                timelineRecord(TARGET_ID, 3uL, plaintext = "the target"),
                            ).copy(hasMoreAfter = true),
                        ),
                    ),
                ),
            cacheRead = { read.await(READ_BLOCK_SECONDS, TimeUnit.SECONDS) },
            profileRead = profileRead,
        )

    /** Lands on [TARGET_ID] on the main thread, recording whether it was on screen when the landing returned. */
    private fun EventCardConversationFixture.launchLanding(): Landing {
        val landing = Landing()
        navigationScope.launch {
            val availability = controller.loadMessageAvailability(TARGET_ID)
            landing.targetShownOnReturn = TARGET_ID in timelineMessageIds(controller)
            landing.availability = availability
        }
        return landing
    }

    /**
     * Asserts the landing returned available with its target on screen, the far window replaced the transcript in one
     * frame, and its card only ever composed Loaded.
     */
    private fun EventCardConversationFixture.assertLandedCompleteOnTheFarWindow(landing: Landing) {
        val far = listOf(FAR_PLAIN_ID, FAR_CARD_ID, TARGET_ID)
        assertEquals(MessageAvailability.AVAILABLE, landing.availability)
        assertTrue("the landing returned before its target was shown", landing.targetShownOnReturn)
        assertEquals(far, timelineMessageIds(controller))
        assertEquals(listOf(SHOWN, far), composedOrders.dropWhile { it != SHOWN })
        assertEquals(listOf("$LOADED $EVENT_ID"), composed.map(::describe).distinct())
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
        const val NEWER_EPOCH = 7uL
    }
}
