package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
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

    @Suppress("LongMethod") // One real-list fixture shares measurement and the production command.
    private fun assertMentionTop(
        target: Target,
        viewportHeight: Int,
        padding: Int,
        overlap: Int = 0,
        rtl: Boolean = false,
    ) {
        var completed = false
        val targetIndex = target.index
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    val listState = rememberLazyListState()
                    val scope = rememberCoroutineScope()
                    val coordinator =
                        remember(listState) {
                            ConversationScrollCoordinator(LazyListConversationScrollWriter(listState))
                        }
                    Box(modifier = Modifier.fillMaxWidth().height(viewportHeight.dp)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().testTag("mention-list"),
                            reverseLayout = true,
                            contentPadding = PaddingValues(bottom = padding.dp),
                        ) {
                            items((0..targetIndex + 12).toList(), key = { "message-$it" }) { index ->
                                Text(
                                    "Message $index",
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height((if (index == targetIndex) target.height else 72).dp)
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
                                                )
                                            },
                                        )
                                }
                            },
                            modifier = Modifier.testTag("mention-jump"),
                        ) {
                            Text("@")
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("mention-jump").performClick()
        composeRule.waitForIdle()
        val listTop = composeRule.onNodeWithTag("mention-list").getUnclippedBoundsInRoot().top.value
        val messageTop = composeRule.onNodeWithTag("message-$targetIndex").getUnclippedBoundsInRoot().top.value
        assertEquals(listTop, messageTop, 1f)
        composeRule.runOnIdle { assertTrue(completed) }
    }

    private data class Target(
        val height: Int,
        val index: Int = 8,
    )
}
