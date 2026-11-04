package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.notifications.NotificationScenario
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.awaitConversationCondition
import dev.ipf.whitenoise.android.state.awaitOpenedTimelineSubscriptionsClosed
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.navigation.DirectNotificationAppearance
import dev.ipf.whitenoise.android.ui.navigation.DirectNotificationConversationFixture
import dev.ipf.whitenoise.android.ui.navigation.DirectNotificationConversationHarness
import dev.ipf.whitenoise.android.ui.navigation.NotificationRouteTimelinePresentationFixture
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #3558 gate: a live incoming append is classified, its production row is measured in the reading viewport, an
 * oversized row lands at its beginning, and that landing becomes a retained reading intent that later appends do not
 * pull back to the newest edge. Short rows, own sends and history readers keep the established tail behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class IncomingLongMessageReadingStartCoverageTest : NotificationRouteTimelinePresentationFixture() {
    /** An incoming message several screens tall starts at its first line, and a later arrival keeps it there. */
    @Test
    fun oversizedIncomingAppendStartsAtItsBeginningAndKeepsReadingIntent() {
        verifyAppend(AppendCase(bodyLines = TALL_BODY_LINES, expectReadingStart = true))
    }

    /** Large text and right-to-left layout reflow the bubble, and the landing still follows its beginning. */
    @Test
    fun largeTextRtlOversizedIncomingAppendStartsAtItsBeginning() {
        val appearance = DirectNotificationAppearance(rtl = true, fontScale = LARGE_FONT_SCALE)
        verifyAppend(AppendCase(bodyLines = TALL_BODY_LINES / 2, expectReadingStart = true, appearance = appearance))
    }

    /**
     * A burst whose first tall message is pushed off screen by a second tall one still starts at the first message,
     * the first newly arrived line the reader has not seen.
     */
    @Test
    fun burstOfTallIncomingMessagesStartsAtTheFirstOne() {
        verifyAppend(AppendCase(bodyLines = TALL_BODY_LINES, expectReadingStart = true, burstLines = TALL_BODY_LINES))
    }

    /**
     * A burst of short incoming messages that together overflow the viewport keeps following the tail. Besides the
     * offset-zero tail write, its append only snaps to the burst's first row to measure it and straight back.
     */
    @Test
    fun burstOfShortIncomingMessagesKeepsFollowingTheTail() {
        verifyAppend(AppendCase(bodyLines = 1, expectReadingStart = false, shortBurstCount = SHORT_BURST_COUNT))
    }

    /**
     * A tall message that arrives together with enough short ones to fill the screen is never laid out at the tail,
     * and nothing earlier in the burst saw it, yet the append still starts at its beginning.
     */
    @Test
    fun tallMessageArrivingWithAScreenOfShortOnesStartsAtItsBeginning() {
        verifyAppend(
            AppendCase(bodyLines = TALL_BODY_LINES, expectReadingStart = true, shortBurstCount = SHORT_BURST_COUNT),
        )
    }

    /** An incoming message that fits keeps the newest edge and the tail-following intent. */
    @Test
    fun shortIncomingAppendKeepsFollowingTheTail() {
        verifyAppend(AppendCase(bodyLines = 1, expectReadingStart = false))
    }

    /** Rows just below, equal to and just above the measured space are told apart by rendered height alone. */
    @Test
    fun measuredHeightAtTheReadingViewportBoundaryDecidesTheLanding() =
        runTest {
            for ((height, revealed) in listOf(599 to false, 600 to false, 601 to true)) {
                val list = IncomingAppendTestList(viewportEndPx = 600, heightsPx = listOf(height, 80, 80))
                list.settle(index = 1)
                val coordinator = ConversationScrollCoordinator(list)
                val outcome =
                    coordinator.followAppendOrRevealIncomingStart(
                        candidates = listOf(candidate("incoming")),
                        probeFor = { list.probe { 0 } },
                        resolveTailIndex = { 0 },
                        awaitFrame = list::frame,
                    )
                val expectedWrites = if (revealed) listOf(0 to 0, 0 to height - 600) else listOf(0 to 0)
                assertEquals("height $height", expectedWrites, list.writes)
                val landed = outcome is ConversationIncomingAppendOutcome.RevealedReadingStart
                assertEquals("height $height", revealed, landed)
                assertEquals("height $height", !revealed, coordinator.isFollowingTail)
            }
        }

    /**
     * A burst decides over every incoming row it admitted, so its first oversized row keeps the landing even after a
     * later arrival restarted the follow, and an own send or a changed scroll intent retires the burst.
     */
    @Test
    fun burstKeepsItsFirstRowsUntilIntentChangesOrTheReaderSends() {
        val coordinator = ConversationScrollCoordinator(IncomingAppendTestList(600, listOf(80)))
        val queue = ConversationIncomingAppendLandingQueue()
        queue.admit(listOf(row("first")), coordinator.intentToken)
        assertEquals(
            listOf("first", "second"),
            queue.admit(listOf(row("second")), coordinator.intentToken).map { it.messageId },
        )
        assertEquals(emptyList<String>(), queue.admit(listOf(row("own", mine = true)), coordinator.intentToken))
        queue.admit(listOf(row("third")), coordinator.intentToken)
        coordinator.onUserGestureStarted(ConversationScrollAnchor(3, 0, "msg:older", "older"))
        assertEquals(listOf("fourth"), queue.admit(listOf(row("fourth")), coordinator.intentToken).map { it.messageId })
        queue.retire()
        assertEquals(listOf("fifth"), queue.admit(listOf(row("fifth")), coordinator.intentToken).map { it.messageId })
    }

    /**
     * A row under the newest message (a pending membership row) takes space from the arrival: a message that would fit
     * the viewport alone but not the space above that row lands at its beginning, and one that fits stays at the tail.
     */
    @Test
    fun aTrailingRowBelowTheNewestMessageCountsAgainstItsSpace() =
        runTest {
            for ((height, revealed) in listOf(560 to false, 561 to true, 580 to true)) {
                // Newest first: a 40 px trailing row at the tail, the incoming message, then earlier history.
                val list = IncomingAppendTestList(viewportEndPx = 600, heightsPx = listOf(40, height, 80))
                list.settle(index = 2)
                val coordinator = ConversationScrollCoordinator(list)
                val outcome =
                    coordinator.followAppendOrRevealIncomingStart(
                        candidates = listOf(candidate("incoming")),
                        probeFor = { list.probe { 1 } },
                        resolveTailIndex = { 0 },
                        awaitFrame = list::frame,
                    )
                val landed = outcome is ConversationIncomingAppendOutcome.RevealedReadingStart
                assertEquals("height $height", revealed, landed)
                assertEquals("height $height", 0 to 0, list.writes.first())
                if (revealed) assertEquals("height $height", 1 to height - 600, list.writes.last())
            }
        }

    /**
     * A second arrival that restarts the follow while the first one's landing is still measuring joins the same burst:
     * the interrupted command leaves the scroll intent alone, so the later decision still starts at the first row.
     */
    @Test
    fun aLandingInterruptedByASecondArrivalStillStartsAtTheFirstRow() =
        runTest {
            val list = IncomingAppendTestList(viewportEndPx = 600, heightsPx = listOf(900, 80))
            list.settle(index = 1)
            val coordinator = ConversationScrollCoordinator(list)
            val queue = ConversationIncomingAppendLandingQueue()
            val first = queue.admit(listOf(row("first")), coordinator.intentToken)
            val interrupted =
                launch {
                    coordinator.followAppendOrRevealIncomingStart(
                        candidates = first,
                        probeFor = { list.probe { 0 } },
                        resolveTailIndex = { 0 },
                        awaitFrame = { awaitCancellation() },
                    )
                }
            testScheduler.runCurrent()
            interrupted.cancelAndJoin()
            // The second arrival is shorter and now sits at the tail, below the first message.
            list.heightsPx.add(0, 80)
            list.writes.clear()
            val burst = queue.admit(listOf(row("second")), coordinator.intentToken)
            assertEquals(listOf("first", "second"), burst.map { it.messageId })
            val outcome =
                coordinator.followAppendOrRevealIncomingStart(
                    candidates = burst,
                    probeFor = { candidate -> list.probe { if (candidate.messageId == "first") 1 else 0 } },
                    resolveTailIndex = { 0 },
                    awaitFrame = list::frame,
                )
            val revealed = outcome as ConversationIncomingAppendOutcome.RevealedReadingStart
            assertEquals("first", revealed.anchor.messageId)
        }

    /**
     * A landing that measured an oversized row and was then cancelled by short arrivals that push that row out of the
     * layout still starts at it: the burst keeps what the cancelled decision saw, and an own send clears it.
     */
    @Test
    fun anOversizedRowSeenBeforeACancelledLandingKeepsTheLanding() =
        runTest {
            val list = IncomingAppendTestList(viewportEndPx = 600, heightsPx = listOf(900, 80))
            list.settle(index = 1)
            val coordinator = ConversationScrollCoordinator(list)
            val queue = ConversationIncomingAppendLandingQueue()
            val first = queue.admit(listOf(row("first")), coordinator.intentToken)
            var frames = 0
            val interrupted =
                launch {
                    coordinator.followAppendOrRevealIncomingStart(
                        candidates = first,
                        probeFor = { list.probe { 0 } },
                        resolveTailIndex = { 0 },
                        oversizedSeen = queue.oversizedSeen,
                        onOversized = queue::markOversized,
                        // Cancelled while placing, after the tail layout measured the oversized row.
                        awaitFrame = { if (++frames > 2) awaitCancellation() else list.frame() },
                    )
                }
            testScheduler.runCurrent()
            interrupted.cancelAndJoin()
            assertTrue("the cancelled decision measured the oversized row", queue.oversizedSeen)
            // Two short arrivals fill the viewport, so the first row is no longer laid out at the tail.
            list.heightsPx.addAll(0, listOf(300, 300))
            val ids = listOf("first", "second", "third")
            val burst = queue.admit(listOf(row("second"), row("third")), coordinator.intentToken)
            assertEquals(ids, burst.map { it.messageId })
            val outcome =
                coordinator.followAppendOrRevealIncomingStart(
                    candidates = burst,
                    probeFor = { candidate -> list.probe { ids.lastIndex - ids.indexOf(candidate.messageId) } },
                    resolveTailIndex = { 0 },
                    oversizedSeen = queue.oversizedSeen,
                    onOversized = queue::markOversized,
                    awaitFrame = list::frame,
                )
            assertEquals("first", (outcome as ConversationIncomingAppendOutcome.RevealedReadingStart).anchor.messageId)
            queue.admit(listOf(row("own", mine = true)), coordinator.intentToken)
            assertFalse("an own send clears what the burst saw", queue.oversizedSeen)
        }

    /**
     * The screen's append path classifies rows, lands through the coordinator and hands the landing to its owner, and
     * every restart that is not an append, including an empty or unanchored timeline, retires any burst it interrupted.
     */
    @Test
    fun productionAppendPathRoutesThroughTheIncomingLanding() {
        val source = productionSource("ConversationScreen.kt").withoutWhitespace()
        val append =
            source
                .substringAfter("LaunchedEffect(controller,latestTimelineItemId,initialTimelineAnchored)")
                .substringBefore("ConversationViewportStructureEffect(")
        assertTrue(append.contains("conversationAppendedRows(renderedTimeline,previousId)"))
        assertTrue(append.contains("controller.isMessageMine(row.record)"))
        assertTrue(append.contains("incomingAppendLanding.admit("))
        assertTrue(append.contains("scrollCoordinator.followAppendOrRevealIncomingStart("))
        assertTrue(append.contains("viewportRestorationOwner.adoptIncomingReadingStart(outcome)"))
        assertFalse("an append must not bypass the landing decision", append.contains("followTailIfAllowed("))
        assertEquals(
            "a decided append, an empty or unanchored restart and a non-append restart all retire the pending burst",
            3,
            append.split("incomingAppendLanding.retire()").size - 1,
        )
        assertTrue(source.contains("remember(controller){ConversationIncomingAppendLandingQueue()}"))
        // The physical-tail write keeps its #2627 offset-zero contract, and the landing is a separate policy.
        val writer = productionSource("ConversationScrollCoordinator.kt").withoutWhitespace()
        assertTrue(writer.contains("overridesuspendfunscrollToTail(index:Int){scrollToItem(index,0)}"))
    }

    /** One mounted append: the body size it arrives with, what it must land on, and the appearance it renders in. */
    private data class AppendCase(
        val bodyLines: Int,
        val expectReadingStart: Boolean,
        val appearance: DirectNotificationAppearance = DirectNotificationAppearance(),
        /** When set, a second incoming message of this many lines arrives in the same publication. */
        val burstLines: Int? = null,
        /** How many one-line incoming messages follow the first in the same publication. */
        val shortBurstCount: Int = 0,
    )

    /** Mounts the real screen at the newest edge, appends a peer message live, then a later short one. */
    private fun verifyAppend(case: AppendCase) {
        val harness = DirectNotificationConversationHarness(composeRule)
        val timeline = ScriptedConversationTimelineSubscription(timelinePage(*history().toTypedArray()))
        val scripted = ScriptedConversationLiveSubscriptions(listOf(timeline), conversationTimelineTestGroup())
        val row =
            NotificationScenario.chatListRow(
                groupIdHex = ConversationTimelineTestIds.GROUP_ID,
                unreadCount = 0,
                firstUnreadMessageIdHex = null,
            )
        val fixture = harness.create(scripted.subscriptions, row)
        val mounted = mutableStateOf(true)
        val evidence = ViewportEvidence()
        try {
            awaitConversationCondition {
                fixture.controller.hasPublishedAuthoritativeTimeline && !fixture.controller.isLoading
            }
            mount(fixture, mounted, evidence, case.appearance)
            awaitCondition(failureMessage = { "the transcript never revealed" }) { transcriptRevealed() }
            awaitCondition(failureMessage = { "the reader never followed the tail" }) {
                evidence.latest()?.mode == ConversationScrollMode.FollowingTail
            }
            val appended = history() + appendedRows(case)
            val writesBeforeAppend = evidence.writeCount()
            timeline.emitWindow(timelinePage(*appended.toTypedArray()))
            awaitLanding(fixture, case, evidence, newestId = appended.last().messageIdHex)
            if (case.shortBurstCount > 0 && !case.expectReadingStart) {
                assertTheTailWithOneMeasuringSnap(evidence.writesSince(writesBeforeAppend))
            }
            if (case.expectReadingStart) {
                assertIncomingAtReadingTop()
                val withLater = appended + timelineRecord(LATER_ID, HISTORY_COUNT + 3uL, plaintext = "later")
                timeline.emitWindow(timelinePage(*withLater.toTypedArray()))
                awaitCondition(
                    failureMessage = { "the later append never published" },
                ) { fixture.controller.timeline.any { it.record.messageIdHex == LATER_ID } }
                composeRule.waitForIdle()
                // The later arrival neither pulls the reader to the end nor restarts the completed landing.
                assertIncomingAtReadingTop()
                assertReadingIncoming(evidence.latest())
            }
        } finally {
            try {
                harness.dispose(fixture, mounted)
            } finally {
                awaitOpenedTimelineSubscriptionsClosed(scripted)
            }
        }
    }

    /** The incoming rows [case] appends in one publication, oldest first. */
    private fun appendedRows(case: AppendCase): List<TimelineMessageRecordFfi> {
        val body = (1..case.bodyLines).joinToString("\n") { "incoming line $it" }
        val burst =
            case.burstLines?.let { lines ->
                val burstBody = (1..lines).joinToString("\n") { "burst line $it" }
                timelineRecord(BURST_ID, HISTORY_COUNT + 2uL, plaintext = burstBody)
            }
        val shortBurst =
            (0 until case.shortBurstCount).map { index ->
                val order = HISTORY_COUNT + 4uL + index.toULong()
                timelineRecord(shortBurstId(index), order, plaintext = "short $index")
            }
        return listOf(timelineRecord(INCOMING_ID, HISTORY_COUNT + 1uL, plaintext = body)) +
            listOfNotNull(burst) + shortBurst
    }

    /** Waits for the append's settled intent, then checks the tail or reading geometry it left behind. */
    private fun awaitLanding(
        fixture: DirectNotificationConversationFixture,
        case: AppendCase,
        evidence: ViewportEvidence,
        newestId: String,
    ) {
        awaitCondition(failureMessage = { "the append never published" }) {
            fixture.controller.timeline.any { it.record.messageIdHex == INCOMING_ID }
        }
        if (case.expectReadingStart) {
            awaitCondition(failureMessage = { "the oversized row never became the reading anchor" }) {
                (evidence.latest()?.mode as? ConversationScrollMode.ReadingHistory)?.anchorMessageId == INCOMING_ID
            }
            composeRule.waitForIdle()
            val viewport = requireNotNull(evidence.latest())
            assertReadingIncoming(viewport)
            assertTrue("the preceding message stays reachable above", viewport.canScrollForward)
            assertTrue("the newest end stays reachable below", viewport.canScrollBackward)
        } else {
            composeRule.waitForIdle()
            val viewport = requireNotNull(evidence.latest())
            assertEquals(ConversationScrollMode.FollowingTail, viewport.mode)
            assertFalse("a fitting row keeps the newest edge", viewport.canScrollBackward)
            val bubble = bubbleBounds(newestId)
            assertTrue("the whole newest row is below the reading top", bubble.first >= transcriptTop())
        }
    }

    /** Mounts the production screen with the opt-in viewport evidence sink and the case's appearance. */
    private fun mount(
        fixture: DirectNotificationConversationFixture,
        mounted: MutableState<Boolean>,
        evidence: ViewportEvidence,
        appearance: DirectNotificationAppearance,
    ) {
        composeRule.setContent {
            if (mounted.value) {
                CompositionLocalProvider(
                    LocalConversationScrollEvidenceSink provides evidence,
                    LocalLayoutDirection provides if (appearance.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    WhiteNoiseTheme(fontScale = appearance.fontScale) {
                        ConversationScreen(
                            appState = fixture.appState,
                            chat = fixture.chat,
                            controller = fixture.controller,
                            onBack = {},
                        )
                    }
                }
            }
        }
    }

    /** A short-only burst writes the tail, snaps once to measure its first row and returns to the same tail. */
    private fun assertTheTailWithOneMeasuringSnap(writes: List<ConversationScrollWriteEvidence>) {
        assertEquals("a short burst must make the tail, probe and return writes: $writes", 3, writes.size)
        val tail = writes.first()
        assertEquals(ConversationScrollWriteEvidence(animated = false, index = tail.index, offsetPx = 0), tail)
        val probe = writes[1]
        assertEquals(ConversationScrollWriteEvidence(animated = false, index = probe.index, offsetPx = 0), probe)
        assertEquals(tail, writes.last())
    }

    /** The settled mode is a history reading anchored on the incoming message. */
    private fun assertReadingIncoming(viewport: ConversationViewportEvidence?) {
        val mode = viewport?.mode
        assertTrue("expected a retained reading intent, was $mode", mode is ConversationScrollMode.ReadingHistory)
        assertEquals(INCOMING_ID, (mode as ConversationScrollMode.ReadingHistory).anchorMessageId)
    }

    /** The incoming bubble's first line sits at the transcript's reading top, not above it. */
    private fun assertIncomingAtReadingTop() {
        assertEquals(
            "the incoming bubble must start at the transcript's reading top",
            transcriptTop(),
            bubbleBounds(INCOMING_ID).first,
            1f,
        )
    }

    /** True once the screen has committed its initial position and revealed the transcript. */
    private fun transcriptRevealed(): Boolean =
        composeRule
            .onAllNodesWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
            .fetchSemanticsNodes()
            .isNotEmpty()

    /** The top of the clear reading viewport, below the header. */
    private fun transcriptTop(): Float =
        composeRule
            .onNodeWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
            .getUnclippedBoundsInRoot()
            .top.value

    /** The unclipped top and bottom of one bubble row, so a row taller than the viewport is still measured. */
    private fun bubbleBounds(messageId: String): Pair<Float, Float> =
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(messageId), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .let { it.top.value to it.bottom.value }

    /** Short peer rows already read, so the screen opens at the newest edge. */
    private fun history(): List<TimelineMessageRecordFfi> =
        (0 until HISTORY_COUNT.toInt()).map { index ->
            timelineRecord(index.toString(16).padStart(2, '0').repeat(32), (index + 1).toULong(), "body $index")
        }

    /** Reads a production source file from either the module or the repository root. */
    private fun productionSource(name: String): String =
        sequenceOf(File("src/main"), File("app/src/main"))
            .map { File(it, "java/dev/ipf/whitenoise/android/ui/conversation/$name") }
            .first(File::isFile)
            .readText()

    /** Strips every whitespace character so a source check survives reformatting. */
    private fun String.withoutWhitespace(): String = filterNot(Char::isWhitespace)

    /** Builds one appended row with its presentation identity. */
    private fun row(
        messageId: String,
        mine: Boolean = false,
    ) = ConversationAppendedRow(messageId, "msg:$messageId", mine)

    /** Builds one incoming landing candidate with its presentation identity. */
    private fun candidate(messageId: String) = ConversationIncomingAppendCandidate(messageId, "msg:$messageId")

    /** Records every measured production viewport in test memory only. */
    private class ViewportEvidence : ConversationScrollEvidenceSink {
        private val samples = CopyOnWriteArrayList<ConversationViewportEvidence>()
        private val writes = CopyOnWriteArrayList<ConversationScrollWriteEvidence>()

        /** Captures one measured viewport. */
        override fun onViewport(snapshot: ConversationViewportEvidence) {
            samples += snapshot
        }

        /** Records each list write in order. */
        override fun onWrite(write: ConversationScrollWriteEvidence) {
            writes += write
        }

        /** How many writes have happened so far. */
        fun writeCount(): Int = writes.size

        /** The writes after the first [count]. */
        fun writesSince(count: Int): List<ConversationScrollWriteEvidence> = writes.drop(count)

        /** The newest measured viewport, or null before the first one. */
        fun latest(): ConversationViewportEvidence? = samples.lastOrNull()
    }

    private companion object {
        const val HISTORY_COUNT = 12uL
        const val TALL_BODY_LINES = 60
        const val LARGE_FONT_SCALE = 1.6f
        val INCOMING_ID = "e1".repeat(32)
        val LATER_ID = "e2".repeat(32)
        val BURST_ID = "e3".repeat(32)
        const val SHORT_BURST_COUNT = 24

        /** A distinct message id for the short burst's row at [index]. */
        fun shortBurstId(index: Int): String = (0xa0 + index).toString(16).repeat(32)
    }
}
