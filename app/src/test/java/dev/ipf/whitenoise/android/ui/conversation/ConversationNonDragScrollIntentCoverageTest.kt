package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scroll run that nothing in the app started is the reader's own movement (#3603): screen-reader scroll
 * actions, focus moving past the visible rows, keyboard paging and mouse wheels never emit a drag
 * interaction, yet they must leave tail following and re-arm older paging exactly like a touch drag.
 * Runs the app owns, and a touch drag that reports itself, must never be counted twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationNonDragScrollIntentCoverageTest {
    private class Harness {
        val scrolling = MutableStateFlow(false)
        var claimed = false
        var touchDrag = false
        val events = mutableListOf<String>()
    }

    private fun kotlinx.coroutines.test.TestScope.collect(
        harness: Harness,
        awaitFrame: suspend () -> Unit = {},
    ) = launch {
        harness.scrolling.collectUnclaimedScrollRuns(
            claimedByApp = { harness.claimed },
            touchDragActive = { harness.touchDrag },
            onStarted = { harness.events += "start" },
            onSettled = { harness.events += "settled" },
            awaitFrame = awaitFrame,
        )
    }

    @Test
    fun unclaimedRunStartsAndSettlesReaderIntentOnce() =
        runTest {
            val harness = Harness()
            val collector = collect(harness)
            runCurrent()

            harness.scrolling.value = true
            runCurrent()
            assertEquals(listOf("start"), harness.events)

            harness.scrolling.value = false
            runCurrent()
            assertEquals(listOf("start", "settled"), harness.events)
            collector.cancel()
        }

    @Test
    fun appOwnedRunsLeaveReaderIntentUnchanged() =
        runTest {
            val harness = Harness()
            val collector = collect(harness)
            runCurrent()

            // A coordinator command, the read-aloud hold or drag-selection edge scrolling.
            harness.claimed = true
            harness.scrolling.value = true
            runCurrent()
            harness.claimed = false
            harness.scrolling.value = false
            runCurrent()

            assertTrue(harness.events.isEmpty())
            collector.cancel()
        }

    @Test
    fun touchDragReportedOneFrameLateIsNotCountedAgain() =
        runTest {
            val harness = Harness()
            val frame = CompletableDeferred<Unit>()
            val collector = collect(harness, awaitFrame = { frame.await() })
            runCurrent()

            harness.scrolling.value = true
            runCurrent()
            // The drag collector sees DragInteraction.Start after the scroll became visible.
            harness.touchDrag = true
            frame.complete(Unit)
            runCurrent()
            harness.scrolling.value = false
            runCurrent()

            assertTrue(harness.events.isEmpty())
            collector.cancel()
        }

    @Test
    fun dragThatTakesOverAReaderRunSettlesItAlone() =
        runTest {
            val harness = Harness()
            val collector = collect(harness)
            runCurrent()

            harness.scrolling.value = true
            runCurrent()
            harness.touchDrag = true
            harness.scrolling.value = false
            runCurrent()

            assertEquals(listOf("start"), harness.events)
            collector.cancel()
        }

    @Test
    fun coordinatorCommandsAndOnlyThoseOwnListMovement() {
        assertTrue(ConversationScrollMode.Restoring("m", 0).ownsListMovement)
        assertTrue(
            ConversationScrollMode.ProgrammaticJump(null, ConversationScrollReason.NewMessage).ownsListMovement,
        )
        assertFalse(ConversationScrollMode.FollowingTail.ownsListMovement)
        assertFalse(ConversationScrollMode.ReadingHistory("m", 0).ownsListMovement)
    }

    @Test
    fun readerScrollRunStopsReactionLayoutFromChasingTheTail() =
        runTest {
            val writes = mutableListOf<Int>()
            val coordinator = ConversationScrollCoordinator(RecordingWriter(writes))
            val anchor =
                ConversationScrollAnchor(listIndex = 4, pixelOffset = 12, itemId = "older", messageId = "older")
            val scrolling = MutableStateFlow(false)
            val collector =
                launch {
                    scrolling.collectUnclaimedScrollRuns(
                        claimedByApp = { coordinator.mode.ownsListMovement },
                        touchDragActive = { false },
                        onStarted = { coordinator.onUserGestureStarted(anchor) },
                        onSettled = { coordinator.onUserGestureSettled(anchor, nearBottom = false) },
                        awaitFrame = {},
                    )
                }
            runCurrent()

            // SemanticsActions.ScrollBy runs an animated scroll without any drag interaction.
            scrolling.value = true
            runCurrent()
            scrolling.value = false
            runCurrent()

            assertFalse(coordinator.isFollowingTail)
            val chased =
                coordinator.settleTailAfterLayoutChange(
                    resolveTailIndex = { 0 },
                    captureLayout = { error("a history reader is never re-measured toward the tail") },
                    awaitFrame = {},
                )
            assertFalse(chased)
            assertTrue(writes.isEmpty())
            collector.cancel()
        }

    private class RecordingWriter(
        private val writes: MutableList<Int>,
    ) : ConversationScrollWriter {
        override val firstVisibleItemIndex = 0

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += index
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += index
        }
    }

    @Test
    fun conversationScreenRoutesBothScrollSourcesThroughOneReaderPath() {
        val screen = appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt")
        val effects = screen.functionBody("readerScrollIntentEffects")
        val started = screen.functionBody("onReaderScrollStarted")

        assertTrue(effects.contains("collectConversationDragInteractions("))
        assertTrue(effects.contains("collectUnclaimedScrollRuns("))
        assertEquals(2, Regex("""onReaderScrollStarted\(\)""").findAll(effects).count())
        assertEquals(2, Regex("""onReaderScrollSettled\(\)""").findAll(effects).count())
        // Every scroll owner outside the drag collector is excluded explicitly.
        assertTrue(effects.contains("scrollCoordinator.mode.ownsListMovement"))
        assertTrue(effects.contains("ttsQuickTransportViewportLock.holdsViewport"))
        assertTrue(effects.contains("dragAnchorTimelineId != null"))
        // A blocked automatic older page re-arms for screen-reader scrolling as it does for a drag.
        assertTrue(started.contains("scrollCoordinator.onUserGestureStarted("))
        assertTrue(started.contains("controller.onOlderPagingGestureStarted()"))
    }

    @Test
    fun readAloudHoldReportsItsViewportClaim() {
        val lock =
            appModuleSource(
                "src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/TtsQuickTransportViewportLock.kt",
            )
        assertTrue(lock.contains("val holdsViewport: Boolean"))
        assertTrue(lock.contains("get() = anchor != null"))
    }
}
