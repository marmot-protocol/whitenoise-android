package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real [LazyListState] regressions for the diagnostic write boundary. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
internal class LazyListConversationScrollWriterEvidenceTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A visible snap-to-tail reports its measured physical-end request exactly once. */
    @Test
    fun visibleSnapTailReportsTheMeasuredOffset() {
        val fixture = mountList()
        val targetSize = fixture.visibleSize(VISIBLE_TARGET_INDEX)

        fixture.runWriterCommand { writer.scrollToTail(VISIBLE_TARGET_INDEX) }

        assertEquals(
            listOf(scrollEvidence(animated = false, VISIBLE_TARGET_INDEX, targetSize)),
            fixture.evidence.writes,
        )
    }

    /** An unmeasured snap-to-tail reports both materialization and measured correction writes. */
    @Test
    fun unmeasuredSnapTailReportsInitialAndCorrectionWrites() {
        val fixture = mountList()
        fixture.assertNotVisible(UNMEASURED_TARGET_INDEX)

        fixture.runWriterCommand { writer.scrollToTail(UNMEASURED_TARGET_INDEX) }

        assertEquals(
            listOf(
                scrollEvidence(animated = false, UNMEASURED_TARGET_INDEX, 0),
                scrollEvidence(animated = false, UNMEASURED_TARGET_INDEX, ROW_HEIGHT_PX),
            ),
            fixture.evidence.writes,
        )
    }

    /** A visible animated tail command reports its measured physical-end request exactly once. */
    @Test
    fun visibleAnimatedTailReportsTheMeasuredOffset() {
        val fixture = mountList()
        val targetSize = fixture.visibleSize(VISIBLE_TARGET_INDEX)

        fixture.runWriterCommand { writer.animateScrollToTail(VISIBLE_TARGET_INDEX) }

        assertEquals(
            listOf(scrollEvidence(animated = true, VISIBLE_TARGET_INDEX, targetSize)),
            fixture.evidence.writes,
        )
    }

    /** An unmeasured animated tail reports both materialization and measured correction writes. */
    @Test
    fun unmeasuredAnimatedTailReportsInitialAndCorrectionWrites() {
        val fixture = mountList()
        fixture.assertNotVisible(UNMEASURED_TARGET_INDEX)

        fixture.runWriterCommand { writer.animateScrollToTail(UNMEASURED_TARGET_INDEX) }

        assertEquals(
            listOf(
                scrollEvidence(animated = true, UNMEASURED_TARGET_INDEX, 0),
                scrollEvidence(animated = true, UNMEASURED_TARGET_INDEX, ROW_HEIGHT_PX),
            ),
            fixture.evidence.writes,
        )
    }

    /** Mounts an oversized-row list so visible and not-yet-measured branches are deterministic. */
    private fun mountList(): WriterFixture {
        val evidence = RecordingEvidence()
        lateinit var listState: LazyListState
        lateinit var writer: LazyListConversationScrollWriter
        lateinit var scope: CoroutineScope
        composeRule.setContent {
            listState = rememberLazyListState()
            writer = remember(listState, evidence) { LazyListConversationScrollWriter(listState, evidence) }
            scope = rememberCoroutineScope()
            LazyColumn(
                state = listState,
                modifier = Modifier.height(VIEWPORT_HEIGHT_DP.dp),
            ) {
                items(ITEM_COUNT, key = { it }) { index ->
                    Text(
                        text = "Message $index",
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(ROW_HEIGHT_DP.dp),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        return WriterFixture(listState, writer, scope, evidence)
    }

    /** Describes the exact boundary event expected from one list-state mutation. */
    private fun scrollEvidence(
        animated: Boolean,
        index: Int,
        offsetPx: Int,
    ) = ConversationScrollWriteEvidence(animated = animated, index = index, offsetPx = offsetPx)

    /** Owns one mounted list and executes a writer command on its composition coroutine. */
    private inner class WriterFixture(
        private val listState: LazyListState,
        val writer: LazyListConversationScrollWriter,
        private val scope: CoroutineScope,
        val evidence: RecordingEvidence,
    ) {
        /** Returns the real measured size of an item that must already be visible. */
        fun visibleSize(index: Int): Int =
            composeRule
                .runOnIdle {
                    requireNotNull(listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }).size
                }.also { size -> assertTrue(size > 0) }

        /** Proves [index] starts outside the composed viewport before materialization. */
        fun assertNotVisible(index: Int) {
            composeRule.runOnIdle {
                assertFalse(listState.layoutInfo.visibleItemsInfo.any { it.index == index })
            }
        }

        /** Executes [operation] to completion without blocking Compose's main dispatcher. */
        fun runWriterCommand(operation: suspend WriterFixture.() -> Unit) {
            val completed = AtomicBoolean(false)
            val failure = AtomicReference<Throwable?>(null)
            composeRule.runOnIdle {
                scope.launch {
                    try {
                        operation()
                    } catch (throwable: Throwable) {
                        failure.set(throwable)
                    } finally {
                        completed.set(true)
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = COMMAND_TIMEOUT_MILLIS) { completed.get() }
            assertNull(failure.get()?.stackTraceToString(), failure.get())
            composeRule.waitForIdle()
        }
    }

    /** Records only writer commands; viewport evidence is irrelevant to this boundary test. */
    private class RecordingEvidence : ConversationScrollEvidenceSink {
        val writes = CopyOnWriteArrayList<ConversationScrollWriteEvidence>()

        /** Ignores viewport callbacks so these regressions isolate writer-boundary evidence. */
        override fun onViewport(snapshot: ConversationViewportEvidence) = Unit

        /** Records every requested list mutation in execution order. */
        override fun onWrite(write: ConversationScrollWriteEvidence) {
            writes += write
        }
    }

    private companion object {
        const val ITEM_COUNT = 20
        const val VISIBLE_TARGET_INDEX = 0
        const val UNMEASURED_TARGET_INDEX = 12
        const val VIEWPORT_HEIGHT_DP = 96
        const val ROW_HEIGHT_DP = 128
        const val ROW_HEIGHT_PX = ROW_HEIGHT_DP
        const val COMMAND_TIMEOUT_MILLIS = 10_000L
    }
}
