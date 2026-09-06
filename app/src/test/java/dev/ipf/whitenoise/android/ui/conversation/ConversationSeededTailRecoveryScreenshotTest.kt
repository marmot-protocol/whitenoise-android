package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WCAG_AA_NORMAL_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compose and visual coverage for bounded seeded-tail recovery. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class ConversationSeededTailRecoveryScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var renderedRecoverySurface = Color.Unspecified

    /** Retries the real exhausted effect and reveals only after a physical-tail write commits. */
    @Test
    fun retryReleasesTheMeasuredTranscriptOnlyAfterTailAlignment() {
        val fixture = showRecoveringFixture()

        composeRule.onNodeWithTag(CONVERSATION_SEEDED_TAIL_RECOVERY_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.couldnt_load_conversation)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.error_loaded_content_kept)).assertIsDisplayed()
        assertCopyTextContrast()
        val transcriptBounds = composeRule.onNodeWithTag(TRANSCRIPT_TEST_TAG).getUnclippedBoundsInRoot()
        assertTrue(transcriptBounds.bottom - transcriptBounds.top > 0.dp)
        assertFalse(fixture.committed.value)
        assertEquals(0, fixture.writer.tailWriteCount)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/conversation_seeded_tail_recovery_light.png")

        fixture.commandGate.complete(Unit)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            fixture.coordinator.mode is ConversationScrollMode.FollowingTail
        }
        composeRule.onNodeWithTag(CONVERSATION_SEEDED_TAIL_RECOVERY_TEST_TAG).assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        advanceSeededAlignmentEpoch()
        composeRule.waitUntil(timeoutMillis = 5_000) { fixture.committed.value }

        composeRule.onNodeWithTag(CONVERSATION_SEEDED_TAIL_RECOVERY_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(TRANSCRIPT_ROW_TEST_TAG).assertIsDisplayed()
        assertFalse(fixture.listState.canScrollForward)
        assertTrue(fixture.listState.firstVisibleItemScrollOffset > 0)
        assertEquals(1, fixture.writer.tailWriteCount)
    }

    /** Reveals at newer history intent without retrying or writing the seeded tail. */
    @Test
    fun newerHistoryIntentDismissesRecoveryWithoutATailWrite() {
        val fixture = showRecoveringFixture()

        composeRule.runOnUiThread {
            fixture.coordinator.settleReadingAt(
                ConversationScrollAnchor(
                    listIndex = 0,
                    pixelOffset = 0,
                    itemId = "history-row",
                    messageId = "history-message",
                ),
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { fixture.committed.value }

        composeRule.onNodeWithTag(CONVERSATION_SEEDED_TAIL_RECOVERY_TEST_TAG).assertDoesNotExist()
        assertTrue(fixture.coordinator.mode is ConversationScrollMode.ReadingHistory)
        assertTrue(fixture.listState.canScrollForward)
        assertEquals(0, fixture.writer.tailWriteCount)
    }

    /** Routes awaited authority through the sole bounded owner without restarting on later structure. */
    @Test
    fun awaitedAuthorityAndLaterStructureShareOneSeededAlignmentOwner() {
        val fixture = showHeldFixture(awaitingAuthoritative = true)

        composeRule.onNodeWithTag(CONVERSATION_SEEDED_TAIL_RECOVERY_TEST_TAG).assertDoesNotExist()
        assertEquals(0, fixture.writer.tailWriteCount)

        composeRule.runOnUiThread { fixture.authoritativePublished.value = true }
        advanceSeededAlignmentEpoch()
        composeRule.waitUntil(timeoutMillis = 5_000) { fixture.recoveryVisible.value }
        assertEquals(0, fixture.writer.tailWriteCount)

        composeRule.runOnUiThread { fixture.timelineRows.value = 2 }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CONVERSATION_SEEDED_TAIL_RECOVERY_TEST_TAG).assertIsDisplayed()
        assertEquals(0, fixture.writer.tailWriteCount)

        composeRule.runOnUiThread {
            fixture.coordinator.settleReadingAt(
                ConversationScrollAnchor(
                    listIndex = 0,
                    pixelOffset = 0,
                    itemId = "history-row",
                    messageId = "history-message",
                ),
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { fixture.committed.value }
        assertEquals(0, fixture.writer.tailWriteCount)
    }

    /** Mounts a measured oversized list under a held competing command until recovery is visible. */
    private fun showRecoveringFixture(): SeededRecoveryFixture {
        val fixture = showHeldFixture()
        advanceSeededAlignmentEpoch()
        composeRule.waitUntil(timeoutMillis = 5_000) { fixture.recoveryVisible.value }
        return fixture
    }

    /** Advances the initial frame plus the complete bounded alignment budget under virtual time. */
    private fun advanceSeededAlignmentEpoch() {
        repeat(SEEDED_TAIL_ALIGNMENT_MAX_ATTEMPTS + 2) {
            composeRule.mainClock.advanceTimeByFrame()
        }
        composeRule.waitForIdle()
    }

    /** Starts a competing command before making seeded alignment eligible. */
    private fun showHeldFixture(awaitingAuthoritative: Boolean = false): SeededRecoveryFixture {
        val fixture = SeededRecoveryFixture(awaitingAuthoritative)
        composeRule.setContent { SeededRecoveryHarness(fixture) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            fixture.coroutineScope.launch {
                fixture.coordinator.programmaticJump(
                    targetMessageId = null,
                    reason = ConversationScrollReason.Search,
                    resultingMode = ConversationScrollMode.FollowingTail,
                ) {
                    fixture.commandGate.await()
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            fixture.coordinator.mode is ConversationScrollMode.ProgrammaticJump
        }
        composeRule.runOnUiThread { fixture.enabled.value = true }
        return fixture
    }

    /** Hosts the production effect, recovery component, coordinator, and physical LazyColumn. */
    @Composable
    @Suppress("FunctionNaming")
    private fun SeededRecoveryHarness(fixture: SeededRecoveryFixture) {
        fixture.coroutineScope = rememberCoroutineScope()
        val timelineRows = fixture.timelineRows.value
        val alignmentEnabled =
            fixture.enabled.value &&
                (!fixture.awaitingAuthoritative || fixture.authoritativePublished.value)
        WhiteNoiseTheme {
            renderedRecoverySurface = MaterialTheme.colorScheme.background
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                SeededRecoveryTranscript(
                    listState = fixture.listState,
                    timelineRows = timelineRows,
                    visible = fixture.committed.value,
                )
                SeededRecoveryAlignmentOwner(
                    fixture = fixture,
                    alignmentEnabled = alignmentEnabled,
                    timelineRows = timelineRows,
                )
            }
        }
    }

    /** Keeps the real oversized LazyColumn measured while withholding paint and accessibility. */
    @Composable
    @Suppress("FunctionNaming")
    private fun SeededRecoveryTranscript(
        listState: LazyListState,
        timelineRows: Int,
        visible: Boolean,
    ) {
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag(TRANSCRIPT_TEST_TAG)
                    .drawWithContent {
                        if (visible) drawContent()
                    }.semantics {
                        if (!visible) hideFromAccessibility()
                    },
        ) {
            item(key = "top-spacer") { Spacer(Modifier.height(4.dp)) }
            items(timelineRows, key = { "oversized-row-$it" }) { row ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(720.dp)
                        .testTag(
                            if (row == timelineRows - 1) {
                                TRANSCRIPT_ROW_TEST_TAG
                            } else {
                                "$TRANSCRIPT_ROW_TEST_TAG-$row"
                            },
                        ),
                )
            }
        }
    }

    /** Requires the rendered Copy label to meet normal-text AA against this recovery surface. */
    private fun assertCopyTextContrast() {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(context.getString(R.string.copy), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        val foreground =
            layouts
                .single()
                .layoutInput.style.color
        val contrast = contrastRatio(foreground.argbLong(), renderedRecoverySurface.argbLong())
        assertTrue(
            "Rendered Copy contrast $contrast must be at least $WCAG_AA_NORMAL_TEXT_CONTRAST",
            contrast >= WCAG_AA_NORMAL_TEXT_CONTRAST,
        )
    }

    /** Converts an opaque rendered Compose color to the unsigned ARGB contrast contract. */
    private fun Color.argbLong(): Long {
        check(this != Color.Unspecified) { "Rendered recovery contrast colors must be specified" }
        return toArgb().toUInt().toLong()
    }

    /** Wires the production seeded effect and its retryable recovery presentation. */
    @Composable
    @Suppress("FunctionNaming")
    private fun SeededRecoveryAlignmentOwner(
        fixture: SeededRecoveryFixture,
        alignmentEnabled: Boolean,
        timelineRows: Int,
    ) {
        SeededConversationAnchorBaselineEffect(
            enabled = alignmentEnabled,
            retryGeneration = fixture.retryGeneration,
            listState = fixture.listState,
            scrollCoordinator = fixture.coordinator,
            currentTailIndex = { timelineRows },
            postInitialReanchorGate = fixture.postInitialReanchorGate,
            timelineStructure =
                ConversationTimelineStructure(
                    rowKeys =
                        List(timelineRows) { row ->
                            "oversized-row-$row" to "tail-message-$row"
                        },
                    olderHeaderCount = 0,
                ),
            onTailAlignmentCommitted = {
                fixture.recoveryVisible.value = false
                fixture.committed.value = true
            },
            onTailAlignmentExhausted = { fixture.recoveryVisible.value = true },
        )
        ConversationSeededTailAlignmentRecovery(
            visible = fixture.recoveryVisible.value,
            onRetry = {
                fixture.recoveryVisible.value = false
                fixture.retryGeneration++
            },
        )
    }

    /** Owns observable inputs and the real list writer used by the recovery composition. */
    private class SeededRecoveryFixture(
        val awaitingAuthoritative: Boolean,
    ) {
        val enabled = mutableStateOf(false)
        val authoritativePublished = mutableStateOf(!awaitingAuthoritative)
        val timelineRows = mutableStateOf(1)
        val recoveryVisible = mutableStateOf(false)
        val committed = mutableStateOf(false)
        val listState = LazyListState()
        val writer = CountingTailWriter(listState)
        val coordinator = ConversationScrollCoordinator(writer)
        val postInitialReanchorGate = ConversationPostInitialReanchorGate()
        val commandGate = CompletableDeferred<Unit>()
        var retryGeneration by mutableLongStateOf(0L)
        lateinit var coroutineScope: CoroutineScope
    }

    /** Counts physical-tail writes while delegating all list mutation to the production writer. */
    private class CountingTailWriter(
        private val listState: LazyListState,
    ) : ConversationScrollWriter {
        private val delegate = LazyListConversationScrollWriter(listState)

        var tailWriteCount = 0
            private set

        override val firstVisibleItemIndex: Int
            get() = listState.firstVisibleItemIndex

        /** Delegates ordinary snap writes without counting them as tail corrections. */
        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) = delegate.scrollToItem(index, scrollOffset)

        /** Delegates ordinary animated writes without counting them as tail corrections. */
        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) = delegate.animateScrollToItem(index, scrollOffset)

        /** Records and delegates a physical-end correction. */
        override suspend fun scrollToTail(index: Int) {
            tailWriteCount++
            delegate.scrollToTail(index)
        }

        /** Delegates animated physical-tail writes used outside this non-animated recovery path. */
        override suspend fun animateScrollToTail(index: Int) = delegate.animateScrollToTail(index)
    }

    private companion object {
        const val TRANSCRIPT_TEST_TAG = "conversation.seeded_tail_recovery.transcript"
        const val TRANSCRIPT_ROW_TEST_TAG = "conversation.seeded_tail_recovery.row"
    }
}
