package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.GLOBAL_TRANSIENT_NOTICE_TAG
import dev.ipf.whitenoise.android.ui.ShellTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.TRANSIENT_NOTICE_DURATION_MILLIS
import dev.ipf.whitenoise.android.ui.TransientNoticeTimeoutEffect
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationTransientNoticeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var timelineState: LazyListState

    @Test
    fun otherConversationDoesNotRenderScopedNoticeOrMoveHeader() {
        val visibleGroup = mutableStateOf(GROUP_B)
        val notice = mutableStateOf<TransientNotice?>(null)
        renderHarness(visibleGroup, notice)
        val headerBefore = headerBounds()

        composeRule.runOnUiThread {
            notice.value = scopedNotice(GROUP_A)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(NOTICE_TEXT).assertDoesNotExist()
        assertEquals(headerBefore, headerBounds())
    }

    @Test
    fun originatingConversationRendersNoticeBelowStableHeader() {
        val notice = mutableStateOf<TransientNotice?>(null)
        renderHarness(mutableStateOf(GROUP_A), notice)
        val headerBefore = headerBounds()
        val initialIndex = timelineState.firstVisibleItemIndex
        val initialOffset = timelineState.firstVisibleItemScrollOffset

        composeRule.runOnUiThread {
            notice.value = scopedNotice(GROUP_A)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(NOTICE_TEXT).assertIsDisplayed()
        val noticeBounds =
            composeRule
                .onNodeWithTag(CONVERSATION_TRANSIENT_NOTICE_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(headerBefore, headerBounds())
        assertEquals(headerBefore.bottom, noticeBounds.top, POSITION_TOLERANCE)
        val daySeparatorBounds =
            composeRule
                .onNodeWithTag(DAY_SEPARATOR_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(noticeBounds.bottom <= daySeparatorBounds.top)
        assertEquals(initialIndex, timelineState.firstVisibleItemIndex)
        assertEquals(initialOffset, timelineState.firstVisibleItemScrollOffset)

        composeRule.runOnUiThread {
            notice.value = null
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(NOTICE_TEXT).assertDoesNotExist()
        assertEquals(initialIndex, timelineState.firstVisibleItemIndex)
        assertEquals(initialOffset, timelineState.firstVisibleItemScrollOffset)
    }

    @Test
    fun deliveredNoticeClearsAfterTransientWindow() {
        composeRule.mainClock.autoAdvance = false
        val notice = mutableStateOf<TransientNotice?>(scopedNotice(GROUP_A))
        composeRule.setContent {
            TransientNoticeTimeoutEffect(notice.value) { delivered ->
                if (notice.value === delivered) notice.value = null
            }
            WhiteNoiseTheme {
                Box(Modifier.fillMaxSize()) {
                    ConversationTransientNotice(
                        notice = notice.value,
                        accountRef = ACCOUNT,
                        groupIdHex = GROUP_A,
                    )
                }
            }
        }
        composeRule.onNodeWithText(NOTICE_TEXT).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(TRANSIENT_NOTICE_DURATION_MILLIS + 100L)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(NOTICE_TEXT).assertDoesNotExist()
    }

    @Test
    fun globalNoticeReservesSpaceBelowContentWithoutMovingHeader() {
        val notice = mutableStateOf<TransientNotice?>(null)
        composeRule.setContent {
            WhiteNoiseTheme {
                ShellTransientNoticeLayout(notice = notice.value) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag(CONTENT_TAG),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag(HEADER_TAG),
                        )
                    }
                }
            }
        }
        val headerBefore = headerBounds()

        composeRule.runOnUiThread {
            notice.value = TransientNotice(id = 3L, title = AppText.Plain("Global confirmation"))
        }
        composeRule.waitForIdle()

        val noticeBounds =
            composeRule
                .onNodeWithTag(GLOBAL_TRANSIENT_NOTICE_TAG)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val headerAfter = headerBounds()
        val contentBounds = composeRule.onNodeWithTag(CONTENT_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(headerBefore, headerAfter)
        assertTrue(contentBounds.bottom <= noticeBounds.top)
    }

    private fun renderHarness(
        visibleGroupId: State<String>,
        notice: State<TransientNotice?>,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag(HEADER_TAG),
                    )
                    ConversationTransientNoticeLayout(
                        notice = notice.value,
                        accountRef = ACCOUNT,
                        groupIdHex = visibleGroupId.value,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        timelineState =
                            rememberLazyListState(
                                initialFirstVisibleItemIndex = 1,
                                initialFirstVisibleItemScrollOffset = 8,
                            )
                        LazyColumn(
                            state = timelineState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            item {
                                Text(
                                    "Earlier message",
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                )
                            }
                            item {
                                Text(
                                    "Visible message before separator",
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                )
                            }
                            item {
                                Box(Modifier.fillMaxWidth().testTag(DAY_SEPARATOR_TAG)) {
                                    DaySeparator("Today")
                                }
                            }
                            items(12) { index ->
                                Text(
                                    "Visible message $index",
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun headerBounds() = composeRule.onNodeWithTag(HEADER_TAG).fetchSemanticsNode().boundsInRoot

    private fun scopedNotice(groupIdHex: String) =
        TransientNotice(
            id = 1L,
            title = AppText.Plain(NOTICE_TEXT),
            conversation = ConversationNoticeDestination(ACCOUNT, groupIdHex),
        )

    private companion object {
        const val ACCOUNT = "account-a"
        const val GROUP_A = "group-a"
        const val GROUP_B = "group-b"
        const val HEADER_TAG = "conversation-header"
        const val CONTENT_TAG = "shell-content"
        const val DAY_SEPARATOR_TAG = "conversation-day-separator"
        const val NOTICE_TEXT = "Admin removed"
        const val POSITION_TOLERANCE = 0.5f
    }
}
