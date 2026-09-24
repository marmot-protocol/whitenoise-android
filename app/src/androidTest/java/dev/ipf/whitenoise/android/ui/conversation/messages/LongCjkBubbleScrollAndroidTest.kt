package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Platform layout regression for the long CJK message boundary in issue #2647. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
class LongCjkBubbleScrollAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun longCjkBubbleSurvivesRepeatedNormalAndFastBoundaryScrolls() {
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState()
            ScrollFixture(listState)
        }

        val list = composeRule.onNodeWithTag(LIST)
        list.performScrollToIndex(LONG_ROW)
        composeRule.onNodeWithText("Read more").assertExists()
        val viewport = list.fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle {
            val longRow = listState.layoutInfo.visibleItemsInfo.first { it.key == "message-$LONG_ROW" }
            assertTrue("the CJK fixture must exceed one viewport", longRow.size.toFloat() > viewport.height)
        }
        repeat(4) {
            list.performScrollToIndex(LONG_ROW - 1)
            list.performTouchInput { swipeUp(durationMillis = 80) }
            composeRule.waitForIdle()
            list.performTouchInput { swipeDown(durationMillis = 80) }
            composeRule.waitForIdle()
            list.performScrollToIndex(LONG_ROW + 1)
            list.performTouchInput { swipeDown(durationMillis = 250) }
            composeRule.waitForIdle()
        }

        list.performScrollToIndex(LONG_ROW)
        composeRule.onNodeWithTag("message-$LONG_ROW").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(ROW_COUNT, listState.layoutInfo.totalItemsCount)
            assertEquals("the final jump lost its message anchor", LONG_ROW, listState.firstVisibleItemIndex)
        }
    }

    @Composable
    private fun ScrollFixture(listState: LazyListState) {
        WhiteNoiseTheme {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.width(320.dp).height(560.dp).testTag(LIST),
            ) {
                items((0 until ROW_COUNT).toList(), key = { "message-$it" }) { index ->
                    val footer: @Composable () -> Unit = {
                        MessageInlineFooter(
                            timeText = "12:45",
                            color = MaterialTheme.colorScheme.onSurface,
                            showStatus = false,
                            status = MessageStatus.Received,
                            editedLabel = null,
                            onEditedClick = null,
                        )
                    }
                    if (index == LONG_ROW) {
                        BubbleCollapsibleFooterLayout(
                            maxBodyHeight = 960.dp,
                            readMore = { Text("Read more") },
                            footer = footer,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Text(
                                text = longCjkMessage,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.testTag("message-$index"),
                            )
                        }
                    } else {
                        BubbleFooterLayout(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            footer = footer,
                        ) {
                            Text(
                                text = "相邻消息 $index",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.testTag("message-$index"),
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val LIST = "long-cjk-conversation"
        const val ROW_COUNT = 80
        const val LONG_ROW = 40
        // Synthetic text preserves script, wrapping and length without copying a private chat.
        val longCjkMessage =
            "这是一段用于测试聊天记录快速滚动的长消息。请确认文字换行、时间标记和消息边界都保持稳定。".repeat(70)
    }
}
