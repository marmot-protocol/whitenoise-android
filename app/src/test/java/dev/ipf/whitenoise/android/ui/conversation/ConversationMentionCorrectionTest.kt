package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationMentionCorrectionTest {
    @Test
    fun heightChangeDuringCorrectionRequiresAnotherFreshCorrection() =
        runTest {
            var height: Int? = null
            val writer = Writer(onSnap = { height = 900 })
            val reached =
                ConversationScrollCoordinator(writer).jumpToMentionReadingStart(
                    targetMessageId = "mention",
                    resolveTargetIndex = { 5 },
                    readLayout = { ConversationMentionJumpLayout(500, height) },
                    awaitLayout = { if (height == null) height = 800 },
                )
            assertTrue(reached)
            assertEquals(listOf(5 to 0, 5 to 300, 5 to 400), writer.writes)
        }

    @Test
    fun continuouslyChangingHeightStopsAfterThreeCorrectionsWithoutSuccess() =
        runTest {
            var height = 800
            var completions = 0
            val writer = Writer()
            val reached =
                ConversationScrollCoordinator(writer).jumpToMentionReadingStart(
                    targetMessageId = "mention",
                    resolveTargetIndex = { 5 },
                    readLayout = { ConversationMentionJumpLayout(500, height) },
                    awaitLayout = { height += 20 },
                    onCompleted = { completions++ },
                )
            assertFalse(reached)
            assertEquals(listOf(5 to 300, 5 to 320, 5 to 340, 5 to 360), writer.writes)
            assertEquals(1, completions)
        }

    @Test
    fun replacementDuringCorrectionFramePreventsFurtherWritesAndCompletion() =
        runTest {
            var measured = false
            var frames = 0
            var completions = 0
            val writer = Writer()
            val coordinator = ConversationScrollCoordinator(writer)
            val waiting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var reached = true
            val jump =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    reached =
                        coordinator.jumpToMentionReadingStart(
                            targetMessageId = "mention",
                            resolveTargetIndex = { 5 },
                            readLayout = { ConversationMentionJumpLayout(500, if (measured) 800 else null) },
                            awaitLayout = {
                                measured = true
                                if (++frames == 2) {
                                    waiting.complete(Unit)
                                    release.await()
                                }
                            },
                            onCompleted = { completions++ },
                        )
                }
            waiting.await()
            assertTrue(
                coordinator.programmaticJump("search", ConversationScrollReason.Search) {
                    scrollToItem(3, -20)
                },
            )
            release.complete(Unit)
            jump.join()
            assertFalse(reached)
            assertEquals(0, completions)
            assertEquals(listOf(5 to 0, 5 to 300, 3 to -20), writer.writes)
        }

    private class Writer(
        private val onSnap: () -> Unit = {},
    ) : ConversationScrollWriter {
        override var firstVisibleItemIndex = 0
        val writes = mutableListOf<Pair<Int, Int>>()

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            firstVisibleItemIndex = index
            writes += index to scrollOffset
            onSnap()
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            firstVisibleItemIndex = index
            writes += index to scrollOffset
        }
    }
}
