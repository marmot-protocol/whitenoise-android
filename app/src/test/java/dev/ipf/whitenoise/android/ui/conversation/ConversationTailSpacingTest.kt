package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production-policy coverage for the conversation's one-owner tail spacing contract (#415). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationTailSpacingTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Resolves the real final message index after every supported leading structural-row combination. */
    @Test
    fun tailIndexTargetsTheRealFinalRowAcrossHeaderVariants() {
        assertNull(conversationTimelineTailListIndex(timelineSize = 0, leadingStructuralRowCount = 0))
        assertEquals(1, conversationTimelineTailListIndex(timelineSize = 1, leadingStructuralRowCount = 0))
        assertEquals(5, conversationTimelineTailListIndex(timelineSize = 4, leadingStructuralRowCount = 1))
        assertEquals(
            6,
            conversationTimelineTailListIndex(
                timelineSize = 4,
                leadingStructuralRowCount =
                    conversationTimelineLeadingStructuralRowCount(
                        hasOlderHeader = true,
                        hasInlineTopError = true,
                    ),
            ),
        )
    }

    /** Physically aligns an oversized seeded final row before committing its sentinel-free reveal. */
    @Test
    fun oversizedSeededTailReachesItsPhysicalEndWithoutASentinel() {
        val tailIndex =
            requireNotNull(
                conversationTimelineTailListIndex(
                    timelineSize = 1,
                    leadingStructuralRowCount = 2,
                ),
            )
        val listState = LazyListState(firstVisibleItemIndex = tailIndex)
        val writer = CountingLazyListScrollWriter(listState)
        var tailAlignmentCommitted = false
        var tailAlignmentExhausted = false

        composeRule.setContent {
            OversizedSeededTailHarness(
                listState = listState,
                writer = writer,
                tailIndex = tailIndex,
                onCommitted = { tailAlignmentCommitted = true },
                onExhausted = { tailAlignmentExhausted = true },
            )
        }
        composeRule.waitForIdle()

        assertEquals("no synthetic tail item may be reintroduced", 4, listState.layoutInfo.totalItemsCount)
        assertFalse("the seeded final row must reach its physical end", listState.canScrollForward)
        assertTrue(listState.firstVisibleItemScrollOffset > 0)
        assertEquals(1, writer.snapCount)
        assertFalse("the available tail writer must not exhaust", tailAlignmentExhausted)
        assertTrue("the seeded tail alignment callback must commit", tailAlignmentCommitted)
        assertTrue(
            "the corrected oversized row must be eligible for paint, TalkBack, and useful-frame telemetry",
            conversationTranscriptVisibilityCommitted(
                initialTimelineAnchored = true,
                anchorTailImmediately = true,
                seededTailAlignmentCommitted = tailAlignmentCommitted,
                viewportMeasured = listState.layoutInfo.viewportSize.height > 0,
                canScrollForward = listState.canScrollForward,
            ),
        )
    }

    /** Mounts the real structural rows and bounded seeded-tail owner for the oversized-row regression. */
    @Composable
    private fun OversizedSeededTailHarness(
        listState: LazyListState,
        writer: ConversationScrollWriter,
        tailIndex: Int,
        onCommitted: () -> Unit,
        onExhausted: () -> Unit,
    ) {
        val coordinator = remember(listState) { ConversationScrollCoordinator(writer = writer) }
        val reanchorGate = remember(listState) { ConversationPostInitialReanchorGate() }
        SeededConversationAnchorBaselineEffect(
            enabled = true,
            retryGeneration = 0L,
            listState = listState,
            scrollCoordinator = coordinator,
            currentTailIndex = { tailIndex },
            postInitialReanchorGate = reanchorGate,
            timelineStructure =
                ConversationTimelineStructure(
                    rowKeys = listOf("tail" to "message-tail"),
                    olderHeaderCount = 1,
                    inlineTopErrorCount = 1,
                ),
            onTailAlignmentCommitted = onCommitted,
            onTailAlignmentExhausted = onExhausted,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.size(width = 320.dp, height = 100.dp),
            verticalArrangement = CONVERSATION_TIMELINE_VERTICAL_ARRANGEMENT,
            contentPadding = conversationTimelineContentPadding(0.dp),
        ) {
            item(key = "top-spacer") { Spacer(Modifier.height(4.dp)) }
            item(key = "top-error") { Spacer(Modifier.fillMaxWidth().height(44.dp)) }
            item(key = "older-header") { Spacer(Modifier.fillMaxWidth().height(40.dp)) }
            item(key = "tail") { Spacer(Modifier.fillMaxWidth().height(400.dp)) }
        }
    }

    /** Includes the top error and older-page header when judging distance from an oversized tail. */
    @Test
    fun oversizedTailNearBottomCountsTopErrorAndOlderHeaderRows() {
        val tailIndex =
            requireNotNull(
                conversationTimelineTailListIndex(
                    timelineSize = 1,
                    leadingStructuralRowCount = 2,
                ),
            )
        val listState = LazyListState()
        composeRule.setContent {
            LazyColumn(
                state = listState,
                modifier = Modifier.size(width = 320.dp, height = 100.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                item { Spacer(Modifier.height(44.dp)) }
                item { Spacer(Modifier.height(40.dp)) }
                item { Spacer(Modifier.fillMaxWidth().height(400.dp).testTag(TAIL_ROW_TAG)) }
            }
        }
        composeRule.waitForIdle()

        scrollTo(listState, tailIndex, 280)
        assertTrue(
            isNearBottom(
                listState = listState,
                timelineSize = 1,
                hasOlderHeader = true,
                hasInlineTopError = true,
            ),
        )

        scrollTo(listState, tailIndex, 250)
        assertFalse(
            isNearBottom(
                listState = listState,
                timelineSize = 1,
                hasOlderHeader = true,
                hasInlineTopError = true,
            ),
        )
    }

    /** Ignores bottom-geometry changes when there is no real message row to anchor. */
    @Test
    fun emptyTimelineIgnoresImeAndSnackbarInsetTransitions() {
        val fixture = TailSpacingFixture(timelineSize = 0)
        showFixture(fixture)

        composeRule.runOnUiThread {
            fixture.bottomChromeHeight.value = 180.dp
            fixture.snackbarContentInset.value = 56.dp
        }
        composeRule.waitForIdle()

        assertEquals(0, fixture.writer.snapCount)
        assertFalse(fixture.listState.canScrollForward)
        composeRule.onNodeWithTag(TAIL_ROW_TAG).assertDoesNotExist()
    }

    /** Bottom-aligns a short transcript with exactly one aesthetic composer interval. */
    @Test
    fun shortTimelineBottomAlignsWithOneComposerInterval() {
        val fixture = TailSpacingFixture(timelineSize = 1)
        showFixture(fixture)

        assertTailGap(CONVERSATION_TIMELINE_TAIL_GAP)
        assertFalse(fixture.listState.canScrollForward)
        assertEquals(0, fixture.writer.snapCount)
    }

    /** Keeps an overflowing tail attached while the IME changes usable viewport height. */
    @Test
    fun longOverflowingTimelineFollowsTailAcrossImeResize() {
        val fixture = TailSpacingFixture(timelineSize = 24)
        showFixture(fixture)
        scrollToTail(fixture)
        assertTailGap(CONVERSATION_TIMELINE_TAIL_GAP)

        composeRule.runOnUiThread { fixture.bottomChromeHeight.value = 180.dp }
        composeRule.waitForIdle()

        assertTailGap(CONVERSATION_TIMELINE_TAIL_GAP)
        assertFalse(fixture.listState.canScrollForward)
        assertTrue(fixture.writer.snapCount > 0)
    }

    /** Applies temporary snackbar clearance and restores the one-gap resting state. */
    @Test
    fun snackbarInsetTemporarilyClearsTheTailThenRestoresTheSingleGap() {
        val fixture = TailSpacingFixture(timelineSize = 24)
        showFixture(fixture)
        scrollToTail(fixture)

        composeRule.runOnUiThread { fixture.snackbarContentInset.value = 64.dp }
        composeRule.waitForIdle()
        assertTailGap(CONVERSATION_TIMELINE_TAIL_GAP + 64.dp)

        composeRule.runOnUiThread { fixture.snackbarContentInset.value = 0.dp }
        composeRule.waitForIdle()
        assertTailGap(CONVERSATION_TIMELINE_TAIL_GAP)
        assertFalse(fixture.listState.canScrollForward)
        assertTrue(fixture.writer.snapCount >= 2)
    }

    /** Retains a pending snackbar transition until foreground-restore ownership is released. */
    @Test
    fun snackbarInsetDuringForegroundRestoreReanchorsAfterRestoreOwnershipEnds() {
        val fixture = TailSpacingFixture(timelineSize = 24)
        showFixture(fixture)
        scrollToTail(fixture)

        composeRule.runOnUiThread {
            fixture.foregroundRestoreInProgress.value = true
            fixture.snackbarContentInset.value = 64.dp
        }
        composeRule.waitForIdle()

        assertEquals(0, fixture.writer.snapCount)
        assertTrue(fixture.listState.canScrollForward)

        composeRule.runOnUiThread { fixture.foregroundRestoreInProgress.value = false }
        composeRule.waitForIdle()

        assertTailGap(CONVERSATION_TIMELINE_TAIL_GAP + 64.dp)
        assertFalse(fixture.listState.canScrollForward)
        assertEquals(1, fixture.writer.snapCount)
    }

    /** Defers a frozen route transition and applies its bottom correction exactly once on release. */
    @Test
    fun insetTransitionDuringRouteFreezeReanchorsExactlyOnceAfterRelease() {
        val fixture = TailSpacingFixture(timelineSize = 24)
        showFixture(fixture)
        scrollToTail(fixture)

        composeRule.runOnUiThread {
            fixture.routePresentationFrozen.value = true
            fixture.bottomChromeHeight.value = 180.dp
        }
        composeRule.waitForIdle()

        assertEquals(0, fixture.writer.snapCount)
        assertTrue(fixture.listState.canScrollForward)

        composeRule.runOnUiThread { fixture.routePresentationFrozen.value = false }
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) { fixture.writer.snapCount == 1 }
        composeRule.waitForIdle()

        assertTailGap(CONVERSATION_TIMELINE_TAIL_GAP)
        assertFalse(fixture.listState.canScrollForward)
        assertEquals(1, fixture.writer.snapCount)
    }

    /** Waits for a transient programmatic tail command before applying the latest IME correction. */
    @Test
    fun imeTransitionWaitsForTransientTailCommandThenReanchors() {
        val fixture = TailSpacingFixture(timelineSize = 24)
        showFixture(fixture)
        scrollToTail(fixture)
        val commandGate = CompletableDeferred<Unit>()

        holdTransientCommand(fixture, commandGate)
        composeRule.runOnUiThread { fixture.bottomChromeHeight.value = 180.dp }
        composeRule.waitForIdle()

        assertEquals("the active command still owns the writer", 0, fixture.writer.snapCount)
        assertTrue(fixture.listState.canScrollForward)

        composeRule.runOnUiThread { commandGate.complete(Unit) }
        composeRule.waitUntil(timeoutMillis = 5_000) { fixture.writer.snapCount == 1 }

        assertTailGap(CONVERSATION_TIMELINE_TAIL_GAP)
        assertFalse(fixture.listState.canScrollForward)
        assertTrue(fixture.coordinator.isFollowingTail)
    }

    /** Cancels the frame-suspended inset writer once and exits when a drag claims durable history. */
    @Test
    fun gestureDuringInsetFrameEndsTheTailRetryWithoutAnotherWrite() {
        val fixture = TailSpacingFixture(timelineSize = 24)
        showFixture(fixture)
        scrollToTail(fixture)

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnUiThread { fixture.bottomChromeHeight.value = 180.dp }
            repeat(3) {
                if (fixture.coordinator.mode is ConversationScrollMode.ProgrammaticJump) return@repeat
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            }
            assertTrue(fixture.coordinator.mode is ConversationScrollMode.ProgrammaticJump)
            assertEquals("the inset writer must still be awaiting its layout frame", 0, fixture.writer.snapCount)

            composeRule.runOnUiThread {
                fixture.coordinator.onUserGestureStarted(
                    ConversationScrollAnchor(
                        listIndex = 10,
                        pixelOffset = 7,
                        itemId = "message-9",
                        messageId = "message-9",
                    ),
                )
            }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }

        assertEquals(0, fixture.writer.snapCount)
        assertEquals(ConversationScrollMode.ReadingHistory("message-9", 7), fixture.coordinator.mode)
        assertFalse(fixture.coordinator.isFollowingTail)
    }

    /** Preserves a history reader's pixel anchor when a transient command settles back to history. */
    @Test
    fun insetTransitionDuringTransientHistoryCommandPreservesReaderAnchor() {
        val fixture =
            TailSpacingFixture(
                timelineSize = 30,
                initialMode = ConversationScrollMode.ReadingHistory("message-5", 7),
            )
        showFixture(fixture)
        scrollTo(fixture.listState, index = 5, offset = 7)
        val indexBefore = fixture.listState.firstVisibleItemIndex
        val offsetBefore = fixture.listState.firstVisibleItemScrollOffset
        val commandGate = CompletableDeferred<Unit>()

        holdTransientCommand(fixture, commandGate)
        composeRule.runOnUiThread { fixture.bottomChromeHeight.value = 180.dp }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { commandGate.complete(Unit) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            fixture.coordinator.mode is ConversationScrollMode.ReadingHistory
        }

        assertEquals(indexBefore, fixture.listState.firstVisibleItemIndex)
        assertEquals(offsetBefore, fixture.listState.firstVisibleItemScrollOffset)
        assertEquals(0, fixture.writer.snapCount)
        assertFalse(fixture.coordinator.isFollowingTail)
    }

    /** Leaves a stable history anchor untouched across one coordinated IME and snackbar transition. */
    @Test
    fun coordinatedImeAndSnackbarChangeDoesNotMoveAHistoryReader() {
        val fixture =
            TailSpacingFixture(
                timelineSize = 30,
                initialMode = ConversationScrollMode.ReadingHistory("message-5", 7),
            )
        showFixture(fixture)
        scrollTo(fixture.listState, index = 5, offset = 7)
        val indexBefore = fixture.listState.firstVisibleItemIndex
        val offsetBefore = fixture.listState.firstVisibleItemScrollOffset

        composeRule.runOnUiThread {
            fixture.bottomChromeHeight.value = 180.dp
            fixture.snackbarContentInset.value = 64.dp
        }
        composeRule.waitForIdle()

        assertEquals(indexBefore, fixture.listState.firstVisibleItemIndex)
        assertEquals(offsetBefore, fixture.listState.firstVisibleItemScrollOffset)
        assertEquals(0, fixture.writer.snapCount)
        assertTrue(fixture.listState.canScrollForward)
    }

    /** Mounts the production-shaped tail-spacing fixture and waits for its initial layout. */
    private fun showFixture(fixture: TailSpacingFixture) {
        composeRule.setContent { TailSpacingHarness(fixture) }
        composeRule.waitForIdle()
    }

    /** Holds one coordinator command open so inset behavior can be observed under transient ownership. */
    private fun holdTransientCommand(
        fixture: TailSpacingFixture,
        commandGate: CompletableDeferred<Unit>,
    ) {
        composeRule.runOnUiThread {
            fixture.coroutineScope.launch {
                fixture.coordinator.programmaticJump(
                    targetMessageId = null,
                    reason = ConversationScrollReason.Search,
                    resultingMode = fixture.initialMode,
                ) {
                    commandGate.await()
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            fixture.coordinator.mode is ConversationScrollMode.ProgrammaticJump
        }
    }

    /** Reproduces the Scaffold, bottom chrome, inset observer, and real-message LazyColumn contract. */
    @Composable
    private fun TailSpacingHarness(fixture: TailSpacingFixture) {
        val measuredBottomChromeHeightPx = remember { mutableStateOf<Int?>(null) }
        val coordinator = fixture.coordinator
        fixture.coroutineScope = rememberCoroutineScope()
        Box(Modifier.size(width = 320.dp, height = 500.dp)) {
            Scaffold(
                bottomBar = {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(fixture.bottomChromeHeight.value)
                            .onSizeChanged { measuredBottomChromeHeightPx.value = it.height }
                            .testTag(BOTTOM_CHROME_TAG),
                    )
                },
            ) { scaffoldPadding ->
                val density = LocalDensity.current
                ConversationTailInsetReanchorEffect(
                    scrollCoordinator = coordinator,
                    bottomChromeHeightPx = measuredBottomChromeHeightPx.value,
                    snackbarContentInsetPx =
                        with(density) { fixture.snackbarContentInset.value.roundToPx() },
                    bottomInputRevision = 0L,
                    hasTimeline = fixture.timelineSize > 0,
                    initialTimelineAnchored = true,
                    routePresentationFrozen = fixture.routePresentationFrozen.value,
                    foregroundRestoreInProgress = fixture.foregroundRestoreInProgress.value,
                    currentTailIndex = {
                        requireNotNull(
                            conversationTimelineTailListIndex(
                                timelineSize = fixture.timelineSize,
                                leadingStructuralRowCount = 0,
                            ),
                        )
                    },
                )
                Box(Modifier.fillMaxSize().padding(scaffoldPadding)) {
                    LazyColumn(
                        state = fixture.listState,
                        modifier = Modifier.fillMaxSize().testTag(TRANSCRIPT_TAG),
                        verticalArrangement = CONVERSATION_TIMELINE_VERTICAL_ARRANGEMENT,
                        contentPadding =
                            conversationTimelineContentPadding(fixture.snackbarContentInset.value),
                    ) {
                        tailSpacingItems(fixture.timelineSize)
                    }
                }
            }
        }
    }

    /** Adds the permanent top spacer and message rows used by the tail-spacing harness. */
    private fun LazyListScope.tailSpacingItems(timelineSize: Int) {
        item(key = "top-spacer") { Spacer(Modifier.height(4.dp)) }
        items((0 until timelineSize).toList()) { index ->
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .then(
                        if (index == timelineSize - 1) {
                            Modifier.testTag(TAIL_ROW_TAG)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }

    /** Requires the measured gap between the transcript viewport and final message to equal [expected]. */
    private fun assertTailGap(expected: Dp) {
        val transcriptBottom =
            composeRule
                .onNodeWithTag(TRANSCRIPT_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val tailBottom =
            composeRule
                .onNodeWithTag(TAIL_ROW_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        assertEquals(
            with(composeRule.density) { expected.toPx() },
            transcriptBottom - tailBottom,
            1f,
        )
    }

    /** Moves a fixture to its real final message rather than a synthetic sentinel. */
    private fun scrollToTail(fixture: TailSpacingFixture) {
        scrollTo(
            fixture.listState,
            requireNotNull(
                conversationTimelineTailListIndex(
                    timelineSize = fixture.timelineSize,
                    leadingStructuralRowCount = 0,
                ),
            ),
        )
    }

    /** Performs a deterministic immediate lazy-list scroll on the Compose UI thread. */
    private fun scrollTo(
        state: LazyListState,
        index: Int,
        offset: Int = 0,
    ) {
        composeRule.runOnUiThread {
            runBlocking { state.scrollToItem(index, offset) }
        }
        composeRule.waitForIdle()
    }

    private class TailSpacingFixture(
        val timelineSize: Int,
        val initialMode: ConversationScrollMode = ConversationScrollMode.FollowingTail,
    ) {
        val listState = LazyListState()
        val writer = CountingLazyListScrollWriter(listState)
        val coordinator = ConversationScrollCoordinator(writer = writer, initialMode = initialMode)
        val bottomChromeHeight: MutableState<Dp> = mutableStateOf(60.dp)
        val snackbarContentInset: MutableState<Dp> = mutableStateOf(0.dp)
        val foregroundRestoreInProgress: MutableState<Boolean> = mutableStateOf(false)
        val routePresentationFrozen: MutableState<Boolean> = mutableStateOf(false)
        lateinit var coroutineScope: CoroutineScope
    }

    private class CountingLazyListScrollWriter(
        private val listState: LazyListState,
    ) : ConversationScrollWriter {
        private val delegate = LazyListConversationScrollWriter(listState)

        var snapCount = 0
            private set

        override val firstVisibleItemIndex: Int
            get() = listState.firstVisibleItemIndex

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            snapCount++
            delegate.scrollToItem(index, scrollOffset)
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            delegate.animateScrollToItem(index, scrollOffset)
        }

        override suspend fun scrollToTail(index: Int) {
            snapCount++
            delegate.scrollToTail(index)
        }

        override suspend fun animateScrollToTail(index: Int) {
            delegate.animateScrollToTail(index)
        }
    }

    private companion object {
        const val TRANSCRIPT_TAG = "conversation-tail-spacing-transcript"
        const val TAIL_ROW_TAG = "conversation-tail-spacing-last-row"
        const val BOTTOM_CHROME_TAG = "conversation-tail-spacing-bottom-chrome"
    }
}
