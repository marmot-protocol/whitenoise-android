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

    /** Resolves the newest message row, which trails only the bottom-edge failure row. */
    @Test
    fun tailIndexTargetsTheRealFinalRowAcrossHeaderVariants() {
        assertNull(conversationTimelineTailListIndex(timelineSize = 0, trailingRowCount = 0))
        assertEquals(0, conversationTimelineTailListIndex(timelineSize = 1, trailingRowCount = 0))
        assertEquals(0, conversationTimelineTailListIndex(timelineSize = 4, trailingRowCount = 0))
        // Rows above the timeline live at the reversed list's far end, so they
        // can no longer displace the newest row.
        assertEquals(
            1,
            conversationTimelineTailListIndex(
                timelineSize = 4,
                trailingRowCount = conversationTimelineTrailingRowCount(hasBottomError = true),
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
                    trailingRowCount = 0,
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
        // Reversed: the newest edge is exhausted when the list cannot scroll
        // backward, and reaching it needs no offset into an oversized row.
        assertFalse("the seeded final row must reach its physical end", listState.canScrollBackward)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
        // The reversed transcript seeds directly at its origin, so the newest
        // row is already aligned and no correcting write is owed.
        assertEquals(0, writer.snapCount)
        assertFalse("the available tail writer must not exhaust", tailAlignmentExhausted)
        assertTrue("the seeded tail alignment callback must commit", tailAlignmentCommitted)
        assertTrue(
            "the corrected oversized row must be eligible for paint, TalkBack, and useful-frame telemetry",
            conversationTranscriptVisibilityCommitted(
                initialTimelineAnchored = true,
                anchorTailImmediately = true,
                seededTailAlignmentCommitted = tailAlignmentCommitted,
                viewportMeasured = listState.layoutInfo.viewportSize.height > 0,
                canScrollTowardNewest = listState.canScrollBackward,
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
            reverseLayout = true,
            verticalArrangement = CONVERSATION_TIMELINE_VERTICAL_ARRANGEMENT,
            contentPadding = conversationTimelineContentPadding(0.dp),
        ) {
            // Reversed emission: the newest row is laid out against the composer.
            item(key = "tail") { Spacer(Modifier.fillMaxWidth().height(400.dp)) }
            item(key = "older-header") { Spacer(Modifier.fillMaxWidth().height(40.dp)) }
            item(key = "top-error") { Spacer(Modifier.fillMaxWidth().height(44.dp)) }
            item(key = "top-spacer") { Spacer(Modifier.height(4.dp)) }
        }
    }

    /** Keeps the oversized newest row near-bottom only inside its small no-flicker zone. */
    @Test
    fun oversizedTailNearBottomUsesTheNoFlickerZone() {
        val tailIndex =
            requireNotNull(
                conversationTimelineTailListIndex(
                    timelineSize = 1,
                    trailingRowCount = 0,
                ),
            )
        val listState = LazyListState()
        composeRule.setContent {
            LazyColumn(
                state = listState,
                modifier = Modifier.size(width = 320.dp, height = 100.dp),
                reverseLayout = true,
            ) {
                // Reversed emission: the oversized newest row sits at the origin
                // and the structural rows stack above it.
                item { Spacer(Modifier.fillMaxWidth().height(400.dp).testTag(TAIL_ROW_TAG)) }
                item { Spacer(Modifier.height(40.dp)) }
                item { Spacer(Modifier.height(44.dp)) }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
        composeRule.waitForIdle()

        // Resting against the composer, the newest row is trivially near bottom.
        scrollTo(listState, tailIndex, 0)
        assertTrue(isNearBottom(listState = listState, timelineSize = 1))

        // Scrolled a long way up inside the same oversized row, it is not.
        scrollTo(listState, tailIndex, 280)
        assertFalse(isNearBottom(listState = listState, timelineSize = 1))
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
        assertFalse(fixture.listState.canScrollBackward)
        composeRule.onNodeWithTag(TAIL_ROW_TAG).assertDoesNotExist()
    }

    /** Bottom-aligns a short transcript with exactly one aesthetic composer interval. */
    @Test
    fun shortTimelineBottomAlignsWithOneComposerInterval() {
        val fixture = TailSpacingFixture(timelineSize = 1)
        showFixture(fixture)

        assertTailGap(CONVERSATION_TIMELINE_TAIL_GAP)
        assertFalse(fixture.listState.canScrollBackward)
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
        assertFalse(fixture.listState.canScrollBackward)
        // Bottom-anchored layout absorbs the keyboard, so the follower stays
        // pinned without any scroll being issued.
        assertEquals(0, fixture.writer.snapCount)
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
        assertFalse(fixture.listState.canScrollBackward)
        assertEquals(0, fixture.writer.snapCount)
    }

    /** Keeps a history reader's exact row and offset across an inset change, with no write. */
    @Test
    fun insetTransitionDoesNotMoveAHistoryReader() {
        val fixture = TailSpacingFixture(timelineSize = 24)
        showFixture(fixture)
        scrollTo(fixture.listState, index = 5, offset = 7)
        val indexBefore = fixture.listState.firstVisibleItemIndex
        val offsetBefore = fixture.listState.firstVisibleItemScrollOffset
        val writesBefore = fixture.writer.snapCount

        composeRule.runOnUiThread { fixture.bottomChromeHeight.value = 180.dp }
        composeRule.waitForIdle()

        assertEquals(indexBefore, fixture.listState.firstVisibleItemIndex)
        assertEquals(offsetBefore, fixture.listState.firstVisibleItemScrollOffset)
        assertEquals("a reader owes no compensating write", writesBefore, fixture.writer.snapCount)
    }

    /** Keeps a tail follower pinned across a coordinated keyboard and snackbar change, with no write. */
    @Test
    fun coordinatedInsetChangeKeepsTheTailPinnedWithoutAWrite() {
        val fixture = TailSpacingFixture(timelineSize = 24)
        showFixture(fixture)
        scrollToTail(fixture)
        val writesBefore = fixture.writer.snapCount

        composeRule.runOnUiThread {
            fixture.bottomChromeHeight.value = 180.dp
            fixture.snackbarContentInset.value = 64.dp
        }
        composeRule.waitForIdle()

        // Bottom-anchored layout absorbs both insets, so the resting interval
        // below the newest row is unchanged and no scroll was issued.
        assertTailGap(CONVERSATION_TIMELINE_TAIL_GAP + 64.dp)
        assertFalse(fixture.listState.canScrollBackward)
        assertEquals("a pinned tail owes no compensating write", writesBefore, fixture.writer.snapCount)
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
                Box(Modifier.fillMaxSize().padding(scaffoldPadding)) {
                    LazyColumn(
                        state = fixture.listState,
                        modifier = Modifier.fillMaxSize().testTag(TRANSCRIPT_TAG),
                        reverseLayout = true,
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

    /**
     * Adds message rows then the permanent top spacer, in reversed emission
     * order, so the newest row is laid out against the composer.
     */
    private fun LazyListScope.tailSpacingItems(timelineSize: Int) {
        items((0 until timelineSize).toList().asReversed()) { index ->
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
        item(key = "top-spacer") { Spacer(Modifier.height(4.dp)) }
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
                    trailingRowCount = 0,
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
