package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Physical bounds, not screenshots alone, prove the production command's reversed-list destination. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationMentionNavigationUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A genuine end mention has no newer rows supplying scroll room below it. */
    @Test
    fun shortNewestMentionWithNoNewerRowsStartsAtThePhysicalTop() {
        assertMentionTop(
            MentionFixture(
                target = Target(80, 0),
                viewportHeight = 420,
                padding = 12,
                initialIndex = 90,
                restoreTail = true,
            ),
        )
    }

    /** A gesture at the padded native origin keeps the production return-to-tail affordance. */
    @Test
    fun gestureAtNewestMentionRetainsReadingIntentAndTheReturnControl() {
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
    fun newestMentionReadingTopSurvivesRouteRecreation() {
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
    fun shortNewestMentionWithKeyboardStartsAtThePhysicalTop() {
        assertMentionTop(MentionFixture(target = Target(80, 0), viewportHeight = 260, padding = 32, initialIndex = 90))
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
        assertMentionTop(MentionFixture(target = Target(720), viewportHeight = 420, padding = 12))
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
        assertMentionTop(MentionFixture(target = Target(720), viewportHeight = 420, padding = 144, overlap = 120, rtl = true))
    }

    @Test
    fun farUnmeasuredMentionUsesBoundedNavigationAndFreshGeometry() {
        assertMentionTop(MentionFixture(target = Target(720, 150), viewportHeight = 420, padding = 12))
    }

    @Test
    fun endMentionAcrossMoreThanFiftyMixedRowsStillStartsAtThePhysicalTop() {
        assertMentionTop(MentionFixture(target = Target(720), viewportHeight = 420, padding = 12, initialIndex = 90, mixedRows = true))
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

    @Suppress("LongMethod") // One real-list fixture shares measurement and the production command.
    private fun assertMentionTop(fixture: MentionFixture) {
        val target = fixture.target
        val viewportHeight = fixture.viewportHeight
        val padding = fixture.padding
        val overlap = fixture.overlap
        val rtl = fixture.rtl
        val initialIndex = fixture.initialIndex
        val mixedRows = fixture.mixedRows
        val restoreTail = fixture.restoreTail
        val incomingAfterLanding = fixture.incomingAfterLanding
        val gestureAfterLanding = fixture.gestureAfterLanding
        val reopenAfterLanding = fixture.reopenAfterLanding
        var completed = false
        val targetIndex = target.index
        var tailReturned = false
        var incomingCount by mutableIntStateOf(0)
        var routeGeneration by mutableIntStateOf(0)
        var savedSnapshot: ConversationScrollSnapshot? = null
        var activeCoordinator: ConversationScrollCoordinator? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    val listState =
                        key(routeGeneration) {
                            rememberLazyListState(
                                initialFirstVisibleItemIndex = savedSnapshot?.firstVisibleItemIndex ?: initialIndex,
                                initialFirstVisibleItemScrollOffset = savedSnapshot?.firstVisibleItemScrollOffset ?: 0,
                            )
                        }
                    val scope = rememberCoroutineScope()
                    val coordinator =
                        remember(listState) {
                            ConversationScrollCoordinator(
                                writer = LazyListConversationScrollWriter(listState),
                                initialMode =
                                    savedSnapshot?.let {
                                        ConversationScrollMode.ReadingHistory(it.anchorMessageIdHex, it.firstVisibleItemScrollOffset)
                                    } ?: ConversationScrollMode.FollowingTail,
                                initialMentionReadingRowHeightPx = savedSnapshot?.mentionReadingRowHeightPx,
                            )
                        }
                    activeCoordinator = coordinator
                    fun anchor(): ConversationScrollAnchor {
                        val index = listState.firstVisibleItemIndex
                        val id = "message-${index - incomingCount}"
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
                                        maxOf(targetIndex, initialIndex) + 13 + incomingCount,
                                        mentionReadingRowHeightPx = coordinator.mentionReadingRowHeightPx,
                                    ),
                                )
                            },
                        )
                    }
                    LaunchedEffect(listState, coordinator) {
                        val restore = savedSnapshot ?: return@LaunchedEffect
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
                                    listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                        it.index == restore.firstVisibleItemIndex
                                    }?.size,
                                )
                            },
                        )
                    }
                    val nearBottom =
                        rememberConversationNearBottom(
                            listState,
                            maxOf(targetIndex, initialIndex) + 13 + incomingCount,
                            mentionReadingRowHeightPx = coordinator.mentionReadingRowHeightPx,
                        )
                    val readingReserve =
                        conversationMentionReadingReservePx(
                            viewportHeight - overlap,
                            padding - overlap,
                            coordinator.mentionReadingRowHeightPx,
                        )
                    Box(modifier = Modifier.fillMaxWidth().height(viewportHeight.dp)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().testTag("mention-list"),
                            reverseLayout = true,
                            contentPadding = PaddingValues(bottom = (padding + readingReserve).dp),
                        ) {
                            items(
                                (-incomingCount..maxOf(targetIndex, initialIndex) + 12).toList(),
                                key = { "message-$it" },
                            ) { index ->
                                val height =
                                    when {
                                        index == targetIndex -> target.height
                                        mixedRows && index % 9 == 0 -> 480
                                        else -> 72
                                    }
                                Text(
                                    "Message $index",
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
                                    completed =
                                        coordinator.jumpToMentionReadingStart(
                                            targetMessageId = "message-$targetIndex",
                                            resolveTargetIndex = { targetIndex },
                                            readLayout = { index ->
                                                val layout =
                                                    conversationReadingLayoutInfo(listState.layoutInfo, overlap)
                                                ConversationMentionJumpLayout(
                                                    viewportEndOffsetPx = layout.viewportEndOffset,
                                                    itemHeightPx =
                                                        layout.visibleItemsInfo.firstOrNull { it.index == index }?.size,
                                                    estimatedItemHeightPx = target.height,
                                                    isNewest = index == 0,
                                                    itemOffsetPx =
                                                        layout.visibleItemsInfo.firstOrNull { it.index == index }?.offset,
                                                )
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
                                        tailReturned = coordinator.programmaticJump(
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
                                    savedSnapshot = conversationScrollSnapshotOnLeave(
                                        listState.firstVisibleItemIndex,
                                        listState.firstVisibleItemScrollOffset,
                                        coordinator.isFollowingTail,
                                        "message-$targetIndex",
                                        "message-$targetIndex",
                                        coordinator.mentionReadingRowHeightPx,
                                    )
                                    routeGeneration++
                                },
                                modifier = Modifier.offset(y = 96.dp).testTag("mention-reopen"),
                            ) { Text("Reopen") }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("mention-jump").performClick()
        composeRule.waitForIdle()
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
        assertEquals(listTop, messageTop, 1f)
        composeRule.runOnIdle { assertTrue(completed) }
        if (incomingAfterLanding) {
            composeRule.runOnIdle { incomingCount++ }
            composeRule.waitForIdle()
            val afterIncoming = composeRule.onNodeWithTag("message-$targetIndex").getUnclippedBoundsInRoot().top.value
            assertEquals(listTop, afterIncoming, 1f)
        }
        if (gestureAfterLanding) {
            composeRule.onNodeWithTag("mention-list").performTouchInput { swipeUp() }
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertTrue(activeCoordinator?.mode is ConversationScrollMode.ReadingHistory) }
        }
        if (reopenAfterLanding) {
            composeRule.onNodeWithTag("mention-reopen").performClick()
            composeRule.waitForIdle()
            val reopenedTop = composeRule.onNodeWithTag("message-$targetIndex").getUnclippedBoundsInRoot().top.value
            assertEquals(listTop, reopenedTop, 1f)
            composeRule.runOnIdle { assertEquals(target.height, savedSnapshot?.mentionReadingRowHeightPx) }
        }
        if (restoreTail) {
            composeRule.onNodeWithTag("mention-tail").assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            val restingTop = composeRule.onNodeWithTag("message-0").getUnclippedBoundsInRoot().top.value
            assertEquals(listTop + viewportHeight - padding - target.height, restingTop, 1f)
            composeRule.runOnIdle { assertTrue(tailReturned) }
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
        val initialIndex: Int = 0,
        val mixedRows: Boolean = false,
        val restoreTail: Boolean = false,
        val incomingAfterLanding: Boolean = false,
        val gestureAfterLanding: Boolean = false,
        val reopenAfterLanding: Boolean = false,
    )

    private data class Target(
        val height: Int,
        val index: Int = 8,
    )
}
