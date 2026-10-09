package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.FakeSessionFocus
import dev.ipf.whitenoise.android.audio.tts.TtsController
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TtsAutoReadContinuationTest {
    @Test
    fun completeBatchesAreAppendedOnceInNativeOrderWithNoConversationScreen() =
        runTest {
            val host = Host(page("m1"))
            host.start(this)
            runCurrent()
            host.windows.send(page("m1", "m2", "m3"))
            runCurrent()
            host.windows.send(page("m1", "m2", "m3"))
            runCurrent()

            assertEquals(listOf("m1", "m2", "m3"), host.controller.queuedMessageIds())
            assertEquals(3, host.engine.spoken.size)
            host.controller.stop()
            runCurrent()
            assertEquals(1, host.closes)
        }

    @Test
    fun pauseRetainsEveryArrivalWithoutSpeakingAndResumeUsesTheSameQueue() =
        runTest {
            val host = Host(page("m1"))
            host.start(this)
            runCurrent()
            host.controller.pause()
            host.windows.send(page("m1", "m2", "m3"))
            runCurrent()

            assertTrue(host.controller.state.value is TtsState.Paused)
            assertEquals(listOf("m1", "m2", "m3"), host.controller.queuedMessageIds())
            assertEquals(1, host.engine.spoken.size)
            host.controller.resume()
            assertEquals(4, host.engine.spoken.size)
            assertEquals(1, host.opens)
            host.controller.stop()
            runCurrent()
        }

    @Test
    fun aMissingTailUsesNativeJumpAndForwardPaginationRatherThanSkippingToTheNewestRow() =
        runTest {
            val host = Host(page("m4"))
            host.jump = page("m1", "m2").copy(hasMoreAfter = true)
            host.forward = page("m2", "m3", "m4")
            host.start(this)
            runCurrent()

            assertEquals(listOf("m1", "m2", "m3", "m4"), host.controller.queuedMessageIds())
            assertEquals(listOf("m1"), host.jumps)
            assertEquals(1, host.pages)
            host.controller.stop()
            runCurrent()
        }

    @Test
    fun anUnrecoverableGapPausesAndOnlyAnExplicitResumeReopensTheFeed() =
        runTest {
            val host = Host(page("m3"))
            host.start(this)
            runCurrent()
            assertTrue(host.controller.state.value is TtsState.Paused)
            assertEquals(listOf("m1"), host.controller.queuedMessageIds())
            assertEquals(1, host.opens)
            assertEquals(1, host.closes)
            runCurrent()
            assertEquals(1, host.opens)

            host.initial = page("m1", "m2", "m3")
            host.controller.resume()
            runCurrent()
            assertEquals(2, host.opens)
            assertEquals(listOf("m1", "m2", "m3"), host.controller.queuedMessageIds())
            host.controller.stop()
            runCurrent()
        }

    @Test
    fun aNativeDeletionRevokesCapturedSpeechWhileTheScreenIsAbsent() =
        runTest {
            val host = Host(page("m1"))
            host.start(this)
            runCurrent()
            val initial = page("m1")
            host.windows.send(initial.copy(messages = initial.messages.map { it.copy(deleted = true) }))
            runCurrent()

            assertTrue(host.controller.state.value is TtsState.Idle)
            assertTrue(host.controller.queuedMessageIds().isEmpty())
            assertEquals(1, host.closes)
        }

    @Test
    fun aProjectionThatReturnsAfterAccountReplacementCannotAppendToTheNewOwner() =
        runTest {
            val host = Host(page("m1"))
            host.start(this)
            runCurrent()
            host.beforeProject = {
                host.owned = false
                host.controller.speak(listOf(entry("other")), Locale.US)
            }
            host.windows.send(page("m1", "m2"))
            runCurrent()

            assertEquals(listOf("other"), host.controller.queuedMessageIds())
            assertEquals(1, host.closes)
            host.controller.stop()
        }

    private class Host(
        var initial: TimelinePageFfi,
    ) : TtsAutoReadContinuationHost {
        val engine = FakeSessionEngine()
        override val controller = TtsController(FakeSessionFocus(), maxChunkLength = 4_000)
        val windows = Channel<TimelinePageFfi>(Channel.UNLIMITED)
        var owned = true
        var opens = 0
        var closes = 0
        var pages = 0
        val jumps = mutableListOf<String>()
        var jump: TimelinePageFfi? = null
        var forward: TimelinePageFfi? = null
        var beforeProject: (() -> Unit)? = null

        init {
            controller.attachEngine(engine)
            check(controller.speak(listOf(entry("m1")), Locale.US))
        }

        fun start(scope: TestScope) {
            val dispatcher = StandardTestDispatcher(scope.testScheduler)
            TtsAutoReadContinuation(this, scope.backgroundScope, dispatcher).start("account", "group", Locale.US)
        }

        override fun owns(
            account: String,
            group: String,
            session: Long,
        ): Boolean = owned

        override fun allowsAppend(): Boolean = true

        override suspend fun project(record: TimelineMessageRecordFfi): TtsSpeakableEntry {
            beforeProject?.invoke()
            return entry(record.messageIdHex)
        }

        override suspend fun open(
            account: String,
            group: String,
        ): ConversationTimelineSubscriptionHandle {
            opens++
            return object : ConversationTimelineSubscriptionHandle {
                override fun snapshot(): TimelinePageFfi = initial

                override suspend fun nextWindow(): TimelinePageFfi? = windows.receiveCatching().getOrNull()

                override suspend fun paginateBackwards(count: UInt): TimelinePageOutcome = unchanged()

                override suspend fun paginateForwards(count: UInt): TimelinePageOutcome {
                    pages++
                    return forward?.let { TimelinePageOutcome.Advanced(it) } ?: unchanged()
                }

                override suspend fun jumpToMessage(messageIdHex: String): ConversationJumpOutcome {
                    jumps += messageIdHex
                    return jump?.let { ConversationJumpOutcome.Window(TimelinePageOutcome.Advanced(it)) }
                        ?: ConversationJumpOutcome.Missing
                }

                override fun close() {
                    closes++
                }

                private fun unchanged() =
                    TimelinePageOutcome.Unchanged(
                        ConversationWindowUnchangedReason.TERMINAL,
                        initial,
                    )
            }
        }
    }

    companion object {
        private fun entry(id: String) = TtsSpeakableEntry("alice", "Alice", "Text $id.", messageIdHex = id)

        private fun page(vararg ids: String) =
            TimelinePageFfi(
                ids.mapIndexed { index, id -> timelineRecord(id, index.toULong(), "Text $id.") },
                false,
                false,
            )
    }
}
