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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
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
            target = Target(80, 0),
            viewportHeight = 420,
            padding = 12,
            initialIndex = 90,
            restoreTail = true,
        )
    }

    /** The keyboard viewport retains the same physical destination for the true final row. */
    @Test
    fun shortNewestMentionWithKeyboardStartsAtThePhysicalTop() {
        assertMentionTop(target = Target(80, 0), viewportHeight = 260, padding = 32, initialIndex = 90)
    }

    /** Stable row keys retain the visited reading top when a new tail message arrives. */
    @Test
    fun incomingAfterNewestMentionDoesNotPullTheReaderToTheTail() {
        assertMentionTop(
            target = Target(80, 0),
            viewportHeight = 420,
            padding = 12,
            initialIndex = 90,
            incomingAfterLanding = true,
        )
    }

    @Test
    fun shortMentionStartsAtThePhysicalTop() {
        assertMentionTop(target = Target(80), viewportHeight = 420, padding = 12)
    }

    @Test
    fun oversizedMentionStartsAtThePhysicalTop() {
        assertMentionTop(target = Target(720), viewportHeight = 420, padding = 12)
    }

    @Test
    fun keyboardReducedViewportStillShowsTheBeginning() {
        assertMentionTop(target = Target(720), viewportHeight = 260, padding = 32)
    }

    @Test
    fun expandedComposerOverlapDoesNotShiftTheReadingTop() {
        assertMentionTop(target = Target(80), viewportHeight = 420, padding = 144, overlap = 120)
    }

    @Test
    fun tallMentionWithExpandedComposerAndRtlShowsTheBeginning() {
        assertMentionTop(target = Target(720), viewportHeight = 420, padding = 144, overlap = 120, rtl = true)
    }

    @Test
    fun farUnmeasuredMentionUsesBoundedNavigationAndFreshGeometry() {
        assertMentionTop(target = Target(720, 150), viewportHeight = 420, padding = 12)
    }

    @Test
    fun endMentionAcrossMoreThanFiftyMixedRowsStillStartsAtThePhysicalTop() {
        assertMentionTop(target = Target(720), viewportHeight = 420, padding = 12, initialIndex = 90, mixedRows = true)
    }

    @Test
    fun endMentionWithKeyboardAndComposerStillStartsAtThePhysicalTop() {
        assertMentionTop(
            target = Target(80),
            viewportHeight = 260,
            padding = 144,
            overlap = 120,
            initialIndex = 90,
            mixedRows = true,
        )
    }

    @Suppress("LongMethod") // One real-list fixture shares measurement and the production command.
    private fun assertMentionTop(
        target: Target,
        viewportHeight: Int,
        padding: Int,
        overlap: Int = 0,
        rtl: Boolean = false,
        initialIndex: Int = 0,
        mixedRows: Boolean = false,
        restoreTail: Boolean = false,
        incomingAfterLanding: Boolean = false,
    ) {
        var completed = false
        val targetIndex = target.index
        var tailReturned = false
        var incomingCount by mutableIntStateOf(0)
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
                    val scope = rememberCoroutineScope()
                    val coordinator =
                        remember(listState) {
                            ConversationScrollCoordinator(LazyListConversationScrollWriter(listState))
                        }
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
                        if (restoreTail) {
                            TextButton(
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
                            ) { Text("Newest") }
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
        if (restoreTail) {
            composeRule.onNodeWithTag("mention-tail").performClick()
            composeRule.waitForIdle()
            val restingTop = composeRule.onNodeWithTag("message-0").getUnclippedBoundsInRoot().top.value
            assertEquals(listTop + viewportHeight - padding - target.height, restingTop, 1f)
            composeRule.runOnIdle { assertTrue(tailReturned) }
        }
    }

    private data class Target(
        val height: Int,
        val index: Int = 8,
    )
}
