package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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

/** Physical bounds, not screenshots alone, prove the production command's reversed-list destination. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationMentionNavigationUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** One short newer row still leaves insufficient native runway for a near-tail mention. */
    @Test
    fun shortSecondNewestMentionUsesNaturalTailSpacing() {
        assertMentionTop(
            MentionFixture(
                Target(80, 1),
                viewportHeight = 420,
                padding = 12,
                initialIndex = 90,
                dark = true,
                captureName = "conversation_mention_near_tail_dark",
            ),
        )
    }

    /** Keyboard and composer reduction retain the measured newer-row runway in the native list. */
    @Test
    fun shortSecondNewestMentionClearsComposerWithoutExtraSpacing() {
        assertMentionTop(
            MentionFixture(Target(80, 1), viewportHeight = 260, padding = 64, overlap = 32, initialIndex = 90),
        )
    }

    /** A fresh near-tail command must remeasure newer content after an incoming row and header shift. */
    @Test
    fun secondNewestMentionWindowChangeUsesCurrentTailExtent() {
        assertMentionTop(
            MentionFixture(
                Target(80, 1),
                viewportHeight = 420,
                padding = 12,
                initialIndex = 90,
                suspendedAction = SuspendedMentionAction.Window,
            ),
        )
    }

    /** A genuine end mention must not invent scroll room below it. */
    @Test
    fun shortNewestMentionWithNoNewerRowsUsesNormalTailSpacing() {
        assertMentionTop(
            MentionFixture(
                target = Target(80, 0),
                viewportHeight = 420,
                padding = 12,
                initialIndex = 90,
                restoreTail = true,
                captureName = "conversation_mention_newest_light",
            ),
        )
    }

    /** A gesture at the natural tail keeps reading ownership without creating a return affordance. */
    @Test
    fun gestureAtNewestMentionRetainsReadingIntentWithoutAnUnneededReturnControl() {
        assertMentionTop(
            MentionFixture(
                target = Target(80, 0),
                viewportHeight = 420,
                padding = 12,
                initialIndex = 90,
                restoreTail = true,
                gestureAfterLanding = true,
            ),
        )
    }

    /** The real leave snapshot supplies the native padding before saved-position restoration. */
    @Test
    fun newestMentionNaturalLandingSurvivesRouteRecreation() {
        assertMentionTop(
            MentionFixture(
                target = Target(80, 0),
                viewportHeight = 420,
                padding = 12,
                initialIndex = 90,
                restoreTail = true,
                reopenAfterLanding = true,
            ),
        )
    }

    /** The keyboard viewport retains the same physical destination for the true final row. */
    @Test
    fun shortNewestMentionWithKeyboardUsesNormalTailSpacing() {
        assertMentionTop(
            MentionFixture(
                target = Target(80, 0),
                viewportHeight = 260,
                padding = 32,
                initialIndex = 90,
                rtl = true,
                fontScale = 2f,
                captureName = "conversation_mention_newest_large_rtl",
            ),
        )
    }

    /** Stable row keys retain the visited reading top when a new tail message arrives. */
    @Test
    fun incomingAfterNewestMentionDoesNotPullTheReaderToTheTail() {
        assertMentionTop(
            MentionFixture(
                target = Target(80, 0),
                viewportHeight = 420,
                padding = 12,
                initialIndex = 90,
                incomingAfterLanding = true,
            ),
        )
    }

    @Test
    fun shortMentionStartsAtThePhysicalTop() {
        assertMentionTop(MentionFixture(target = Target(80), viewportHeight = 420, padding = 12))
    }

    @Test
    fun oversizedMentionStartsAtThePhysicalTop() {
        assertMentionTop(
            MentionFixture(
                target = Target(720),
                viewportHeight = 420,
                padding = 12,
                captureName = "conversation_mention_tall",
            ),
        )
    }

    @Test
    fun keyboardReducedViewportStillShowsTheBeginning() {
        assertMentionTop(MentionFixture(target = Target(720), viewportHeight = 260, padding = 32))
    }

    @Test
    fun expandedComposerOverlapDoesNotShiftTheReadingTop() {
        assertMentionTop(MentionFixture(target = Target(80), viewportHeight = 420, padding = 144, overlap = 120))
    }

    @Test
    fun tallMentionWithExpandedComposerAndRtlShowsTheBeginning() {
        assertMentionTop(
            MentionFixture(
                target = Target(720),
                viewportHeight = 420,
                padding = 144,
                overlap = 120,
                rtl = true,
            ),
        )
    }

    @Test
    fun farUnmeasuredMentionUsesBoundedNavigationAndFreshGeometry() {
        assertMentionTop(MentionFixture(target = Target(720, 150), viewportHeight = 420, padding = 12))
    }

    @Test
    fun endMentionAcrossMoreThanFiftyMixedRowsStillStartsAtThePhysicalTop() {
        assertMentionTop(
            MentionFixture(
                target = Target(720),
                viewportHeight = 420,
                padding = 12,
                initialIndex = 90,
                mixedRows = true,
            ),
        )
    }

    @Test
    fun endMentionWithKeyboardAndComposerStillStartsAtThePhysicalTop() {
        assertMentionTop(
            MentionFixture(
                target = Target(80),
                viewportHeight = 260,
                padding = 144,
                overlap = 120,
                initialIndex = 90,
                mixedRows = true,
            ),
        )
    }

    /** A media-like target height changes after native reading room has suspended the real list command. */
    @Test
    fun targetMeasurementChangeDuringSuspensionUsesFreshPhysicalBounds() {
        assertMentionTop(suspendedFixture(SuspendedMentionAction.Height))
    }

    /** New tail rows and a structural header shift the target index while layout is suspended. */
    @Test
    fun windowAndHeaderChangeDuringSuspensionUsesTheCurrentTargetKey() {
        assertMentionTop(suspendedFixture(SuspendedMentionAction.Window))
    }

    /** Repeated @ owns one completion; an obsolete suspended command cannot mark the target visited. */
    @Test
    fun repeatedTapSupersedesTheSuspendedMentionVisit() {
        assertMentionTop(suspendedFixture(SuspendedMentionAction.RepeatedTap))
    }

    /** A real drag supersedes the waiting command before any stale landing/read completion can occur. */
    @Test
    fun dragDuringSuspendedMentionPreventsOldLandingAndCompletion() {
        assertMentionTop(suspendedFixture(SuspendedMentionAction.Drag))
    }

    /** More than fifty mixed rows separate the original reader from the true final mention. */
    private fun suspendedFixture(action: SuspendedMentionAction) =
        MentionFixture(
            target = Target(80, 0),
            viewportHeight = 420,
            padding = 12,
            initialIndex = 90,
            mixedRows = true,
            suspendedAction = action,
        )

    /** Runs the production mention command on a real reversed list and validates its readable natural landing. */
    @Suppress("LongMethod") // One real-list fixture shares measurement and the production command.
    private fun assertMentionTop(fixture: MentionFixture) {
        val target = fixture.target
        val viewportHeight = fixture.viewportHeight
        val padding = fixture.padding
        val overlap = fixture.overlap
        val initialIndex = fixture.initialIndex
        val mixedRows = fixture.mixedRows
        val restoreTail = fixture.restoreTail
        val reopenAfterLanding = fixture.reopenAfterLanding
        val state = MentionHarnessState(target.height)
        val targetIndex = target.index
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = fixture.dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalLayoutDirection provides fixture.layoutDirection,
                    LocalDensity provides Density(density.density, fixture.fontScale),
                ) {
                    val listState =
                        key(state.routeGeneration) {
                            rememberLazyListState(
                                initialFirstVisibleItemIndex =
                                    state.savedSnapshot?.firstVisibleItemIndex ?: initialIndex,
                                initialFirstVisibleItemScrollOffset =
                                    state.savedSnapshot?.firstVisibleItemScrollOffset ?: 0,
                            )
                        }
                    val scope = rememberCoroutineScope()
                    val coordinator =
                        remember(listState) {
                            ConversationScrollCoordinator(
                                writer = LazyListConversationScrollWriter(listState),
                                initialMode =
                                    state.savedSnapshot?.let {
                                        ConversationScrollMode.ReadingHistory(
                                            it.anchorMessageIdHex,
                                            it.firstVisibleItemScrollOffset,
                                        )
                                    } ?: ConversationScrollMode.FollowingTail,
                                initialMentionReadingRowHeightPx =
                                    state.savedSnapshot?.mentionReadingRowHeightPx
                                        ?: if (reopenAfterLanding && state.routeGeneration == 0) {
                                            target.height
                                        } else {
                                            null
                                        },
                            )
                        }
                    state.activeCoordinator = coordinator

                    fun anchor(): ConversationScrollAnchor {
                        val index = listState.firstVisibleItemIndex
                        val id = "message-${index - state.incomingCount - state.headerCount}"
                        return ConversationScrollAnchor(index, listState.firstVisibleItemScrollOffset, id, id)
                    }
                    LaunchedEffect(listState, coordinator) {
                        listState.interactionSource.interactions.collectConversationDragInteractions(
                            onStarted = { coordinator.onUserGestureStarted(anchor()) },
                            awaitScrollSettled = {
                                snapshotFlow { listState.isScrollInProgress }.filter { !it }.first()
                            },
                            onSettled = {
                                coordinator.onUserGestureSettled(
                                    anchor(),
                                    isNearBottom(
                                        listState,
                                        maxOf(targetIndex, initialIndex) + 13 + state.incomingCount,
                                        mentionReadingRowHeightPx = coordinator.mentionReadingRowHeightPx,
                                    ),
                                )
                            },
                        )
                    }
                    LaunchedEffect(listState, coordinator) {
                        val restore = state.savedSnapshot ?: return@LaunchedEffect
                        coordinator.commitInitialAnchor(
                            targetMessageId = restore.anchorMessageIdHex,
                            reason = ConversationScrollReason.SavedRestore,
                            resultingMode =
                                ConversationScrollMode.ReadingHistory(
                                    restore.anchorMessageIdHex,
                                    restore.firstVisibleItemScrollOffset,
                                ),
                            targetIndex = restore.firstVisibleItemIndex,
                            pixelOffset = restore.firstVisibleItemScrollOffset,
                            captureLayout = {
                                ConversationInitialAnchorLayout(
                                    viewportHeight,
                                    listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull {
                                            it.index == restore.firstVisibleItemIndex
                                        }?.size,
                                )
                            },
                        )
                    }
                    val nearBottom =
                        rememberConversationNearBottom(
                            listState,
                            maxOf(targetIndex, initialIndex) + 13 + state.incomingCount,
                            mentionReadingRowHeightPx = { coordinator.mentionReadingRowHeightPx },
                        )
                    // The production padding type reads the reserve in measurement, as the screen does.
                    val contentPadding =
                        remember(coordinator) {
                            ConversationTimelineContentPadding(padding.dp) {
                                conversationMentionReadingReservePx(
                                    viewportHeight - overlap,
                                    padding - overlap,
                                    coordinator.mentionReadingRowHeightPx,
                                ).dp
                            }
                        }
                    Box(modifier = Modifier.fillMaxWidth().height(viewportHeight.dp)) {
                        LazyColumn(
                            state = listState,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                                    .testTag("mention-list"),
                            reverseLayout = true,
                            contentPadding = contentPadding,
                        ) {
                            if (state.headerCount > 0) {
                                item(key = "structural-header") { Text("Local header", Modifier.height(32.dp)) }
                            }
                            items(
                                (-state.incomingCount..maxOf(targetIndex, initialIndex) + 12).toList(),
                                key = { "message-$it" },
                            ) { index ->
                                val height = mentionFixtureRowHeight(index, targetIndex, mixedRows, state)
                                Text(
                                    "Message $index",
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(height.dp)
                                            .testTag("message-$index"),
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    state.completed =
                                        coordinator.jumpToMentionReadingStart(
                                            targetMessageId = "message-$targetIndex",
                                            resolveTargetIndex = {
                                                targetIndex + state.incomingCount + state.headerCount
                                            },
                                            readLayout = { index ->
                                                mentionJumpLayout(listState, overlap, index, state)
                                            },
                                            awaitLayout = {
                                                withFrameNanos { }
                                                state.awaitSuspendedLayout(fixture.suspendedAction)
                                            },
                                            onCompleted = {
                                                state.completionCount++
                                                coordinator.settleReadingAt(anchor())
                                            },
                                        )
                                }
                            },
                            modifier = Modifier.testTag("mention-jump"),
                        ) {
                            Text("@")
                        }
                        if (restoreTail && !nearBottom) {
                            ConversationJumpToNewestButton(
                                unreadIncomingCount = 0,
                                onClick = {
                                    scope.launch {
                                        state.tailReturned =
                                            coordinator.programmaticJump(
                                                targetMessageId = null,
                                                reason = ConversationScrollReason.JumpToNewest,
                                                resultingMode = ConversationScrollMode.FollowingTail,
                                            ) { scrollToTail(0) }
                                    }
                                },
                                modifier = Modifier.offset(y = 48.dp).testTag("mention-tail"),
                            )
                        }
                        if (reopenAfterLanding) {
                            TextButton(
                                onClick = {
                                    state.savedSnapshot =
                                        conversationScrollSnapshotOnLeave(
                                            listState.firstVisibleItemIndex,
                                            listState.firstVisibleItemScrollOffset,
                                            coordinator.isFollowingTail,
                                            "message-$targetIndex",
                                            "message-$targetIndex",
                                            coordinator.mentionReadingRowHeightPx,
                                        )
                                    state.routeGeneration++
                                },
                                modifier = Modifier.offset(y = 96.dp).testTag("mention-reopen"),
                            ) { Text("Reopen") }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("mention-jump").performClick()
        applySuspendedMentionAction(fixture, state)
        composeRule.waitForIdle()
        if (fixture.suspendedAction == SuspendedMentionAction.Drag) {
            assertFalse(state.completed)
            assertEquals(0, state.completionCount)
            composeRule.onNodeWithTag("message-$targetIndex").assertDoesNotExist()
            return
        }
        val listTop =
            composeRule
                .onNodeWithTag("mention-list")
                .getUnclippedBoundsInRoot()
                .top.value
        val messageTop =
            composeRule
                .onNodeWithTag("message-$targetIndex")
                .getUnclippedBoundsInRoot()
                .top.value
        if (state.atNaturalTail) {
            val messageBottom =
                composeRule
                    .onNodeWithTag("message-$targetIndex")
                    .getUnclippedBoundsInRoot()
                    .bottom.value
            assertTrue("the mention is fully visible below the header", messageTop >= listTop - 1f)
            assertTrue("the mention clears composer overlap", messageBottom <= listTop + viewportHeight - overlap + 1f)
            val newestBottom =
                composeRule
                    .onNodeWithTag("message-${-state.incomingCount}")
                    .getUnclippedBoundsInRoot()
                    .bottom.value
            assertEquals(
                "only normal padding and structural rows below the newest message",
                listTop + viewportHeight - padding - state.headerCount * 32,
                newestBottom,
                1f,
            )
        } else {
            assertEquals(listTop, messageTop, 1f)
        }
        composeRule.runOnIdle {
            assertTrue(state.completed)
            assertNull(
                "mention navigation must not retain synthetic padding",
                state.activeCoordinator?.mentionReadingRowHeightPx,
            )
            assertEquals(1, state.completionCount)
            assertTrue(state.activeCoordinator?.mode is ConversationScrollMode.ReadingHistory)
        }
        if (fixture.suspendedAction == SuspendedMentionAction.Window) {
            assertEquals("initial newest classification", targetIndex == 0, state.newestEvaluations.first())
            assertFalse("the new message--1 supersedes it as newest", state.newestEvaluations.last())
        }
        fixture.captureName?.let {
            composeRule.onNodeWithTag("mention-list").captureRoboImage("src/test/snapshots/$it.png")
        }
        assertMentionFollowUps(fixture, state, messageTop)
    }

    /** Resolves mixed-height rows independently of the real-list interaction harness. */
    private fun mentionFixtureRowHeight(
        index: Int,
        targetIndex: Int,
        mixedRows: Boolean,
        state: MentionHarnessState,
    ): Int =
        when {
            index == targetIndex -> state.targetHeight
            mixedRows && index % 9 == 0 -> 480
            else -> 72
        }

    /** Records newest classification and captures the current measured reading viewport. */
    private fun mentionJumpLayout(
        listState: LazyListState,
        overlap: Int,
        index: Int,
        state: MentionHarnessState,
    ): ConversationMentionJumpLayout {
        val layoutInfo = listState.layoutInfo
        val layout = conversationReadingLayoutInfo(layoutInfo, overlap)
        state.atNaturalTail = !listState.canScrollBackward
        val isNewest = index == state.headerCount
        state.newestEvaluations.add(isNewest)
        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
        return ConversationMentionJumpLayout(
            viewportEndOffsetPx = layout.viewportEndOffset,
            itemHeightPx = item?.size,
            estimatedItemHeightPx = state.targetHeight,
            isNewest = isNewest,
            itemOffsetPx = item?.offset,
            tailContentHeightPx = layoutInfo.mentionTailContentHeightPx(index),
            viewportStartOffsetPx = layout.viewportStartOffset,
            isAtNaturalTail = state.atNaturalTail,
        )
    }

    /** Mutates the actual list while the command awaits a controlled layout continuation. */
    private fun applySuspendedMentionAction(
        fixture: MentionFixture,
        state: MentionHarnessState,
    ) {
        val action = fixture.suspendedAction ?: return
        composeRule.waitUntil { state.awaitingLayout }
        when (action) {
            SuspendedMentionAction.Height -> composeRule.runOnIdle { state.targetHeight = 720 }
            SuspendedMentionAction.Window ->
                composeRule.runOnIdle {
                    state.incomingCount++
                    state.headerCount++
                }
            SuspendedMentionAction.RepeatedTap -> composeRule.onNodeWithTag("mention-jump").performClick()
            SuspendedMentionAction.Drag -> composeRule.onNodeWithTag("mention-list").performTouchInput { swipeUp() }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { state.layoutRelease.complete(Unit) }
    }

    /** Exercises reading-intent lifetime through incoming rows, gestures, route recreation and explicit return. */
    private fun assertMentionFollowUps(
        fixture: MentionFixture,
        state: MentionHarnessState,
        landedTop: Float,
    ) {
        val target = fixture.target
        val targetIndex = target.index
        if (fixture.incomingAfterLanding) {
            composeRule.runOnIdle { state.incomingCount++ }
            composeRule.waitForIdle()
            val afterIncoming =
                composeRule
                    .onNodeWithTag("message-$targetIndex")
                    .getUnclippedBoundsInRoot()
                    .top.value
            assertEquals(landedTop, afterIncoming, 1f)
        }
        if (fixture.gestureAfterLanding) {
            composeRule.onNodeWithTag("mention-list").performTouchInput { swipeUp() }
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertTrue(state.activeCoordinator?.mode is ConversationScrollMode.ReadingHistory) }
        }
        if (fixture.reopenAfterLanding) {
            composeRule.onNodeWithTag("mention-reopen").performClick()
            composeRule.waitForIdle()
            val reopenedTop =
                composeRule
                    .onNodeWithTag("message-$targetIndex")
                    .getUnclippedBoundsInRoot()
                    .top.value
            assertEquals(landedTop, reopenedTop, 1f)
            composeRule.runOnIdle { assertNull(state.savedSnapshot?.mentionReadingRowHeightPx) }
        }
        if (fixture.restoreTail) {
            // A fully visible newest mention already rests at the real tail; no return control is needed.
            composeRule.onNodeWithTag("mention-tail").assertDoesNotExist()
        }
    }

    /** Layout and navigation variants share the same production list/command boundary. */
    private data class MentionFixture(
        val target: Target,
        val viewportHeight: Int,
        val padding: Int,
        val overlap: Int = 0,
        val rtl: Boolean = false,
        val dark: Boolean = false,
        val fontScale: Float = 1f,
        val captureName: String? = null,
        val initialIndex: Int = 0,
        val mixedRows: Boolean = false,
        val restoreTail: Boolean = false,
        val incomingAfterLanding: Boolean = false,
        val gestureAfterLanding: Boolean = false,
        val reopenAfterLanding: Boolean = false,
        val suspendedAction: SuspendedMentionAction? = null,
    ) {
        val layoutDirection: LayoutDirection
            get() = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    }

    /** Mutable controls belong to this one test's composition and never escape to the app. */
    private class MentionHarnessState(
        initialHeight: Int,
    ) {
        /** Holds exactly one layout continuation when a mutation or cancellation case requests it. */
        suspend fun awaitSuspendedLayout(action: SuspendedMentionAction?) {
            if (action != null && !suspensionUsed) {
                suspensionUsed = true
                awaitingLayout = true
                layoutRelease.await()
            }
        }

        var targetHeight by mutableIntStateOf(initialHeight)
        var headerCount by mutableIntStateOf(0)
        var awaitingLayout = false
        var suspensionUsed = false
        var completionCount = 0
        val newestEvaluations = mutableListOf<Boolean>()
        val layoutRelease = CompletableDeferred<Unit>()
        var atNaturalTail = false
        var completed = false
        var tailReturned = false
        var incomingCount by mutableIntStateOf(0)
        var routeGeneration by mutableIntStateOf(0)
        var savedSnapshot: ConversationScrollSnapshot? = null
        var activeCoordinator: ConversationScrollCoordinator? = null
    }

    private enum class SuspendedMentionAction { Height, Window, RepeatedTap, Drag }

    private data class Target(
        val height: Int,
        val index: Int = 8,
    )
}
