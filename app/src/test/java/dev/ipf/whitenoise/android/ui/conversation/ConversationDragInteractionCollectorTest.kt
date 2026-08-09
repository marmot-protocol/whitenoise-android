package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationDragInteractionCollectorTest {
    @Test
    fun newerStartCancelsThePreviousPendingSettle() =
        runTest {
            val interactions = MutableSharedFlow<Interaction>(extraBufferCapacity = 3)
            val firstStart = DragInteraction.Start()
            val secondStart = DragInteraction.Start()
            val neverSettles = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val collector =
                launch {
                    interactions.collectConversationDragInteractions(
                        onStarted = { events += "start" },
                        awaitScrollSettled = { neverSettles.await() },
                        onSettled = { events += "settled" },
                    )
                }
            runCurrent()

            interactions.emit(firstStart)
            interactions.emit(DragInteraction.Stop(firstStart))
            runCurrent()
            interactions.emit(secondStart)
            runCurrent()

            assertEquals(listOf("start", "start"), events)
            collector.cancel()
        }

    @Test
    fun ordinaryStopSettlesAfterFlingMotionEnds() =
        runTest {
            val interactions = MutableSharedFlow<Interaction>(extraBufferCapacity = 2)
            val start = DragInteraction.Start()
            val allowSettle = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val collector =
                launch {
                    interactions.collectConversationDragInteractions(
                        onStarted = { events += "start" },
                        awaitScrollSettled = { allowSettle.await() },
                        onSettled = { events += "settled" },
                    )
                }
            runCurrent()

            interactions.emit(start)
            interactions.emit(DragInteraction.Stop(start))
            runCurrent()
            assertEquals(listOf("start"), events)

            allowSettle.complete(Unit)
            runCurrent()
            assertEquals(listOf("start", "settled"), events)
            collector.cancel()
        }

    @Test
    fun unrelatedInteractionDoesNotCancelAPendingDragSettle() =
        runTest {
            val interactions = MutableSharedFlow<Interaction>(extraBufferCapacity = 3)
            val start = DragInteraction.Start()
            val allowSettle = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val collector =
                launch {
                    interactions.collectConversationDragInteractions(
                        onStarted = { events += "start" },
                        awaitScrollSettled = { allowSettle.await() },
                        onSettled = { events += "settled" },
                    )
                }
            runCurrent()

            interactions.emit(start)
            interactions.emit(DragInteraction.Stop(start))
            interactions.emit(object : Interaction {})
            runCurrent()
            allowSettle.complete(Unit)
            runCurrent()

            assertEquals(listOf("start", "settled"), events)
            collector.cancel()
        }
}
