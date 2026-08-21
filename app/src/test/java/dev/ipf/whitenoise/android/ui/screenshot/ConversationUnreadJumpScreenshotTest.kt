package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.ReplyNavigation
import dev.ipf.whitenoise.android.ui.conversation.ConversationJumpToNewestButton
import dev.ipf.whitenoise.android.ui.conversation.ConversationJumpToNewestOutcome
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollAnchor
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollCoordinator
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollMode
import dev.ipf.whitenoise.android.ui.conversation.LazyListConversationScrollWriter
import dev.ipf.whitenoise.android.ui.conversation.isConversationItemTopAligned
import dev.ipf.whitenoise.android.ui.conversation.jumpToUnreadOrNewest
import dev.ipf.whitenoise.android.ui.conversation.notificationFocusOwnsInitialConversationAnchor
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationNearBottom
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual and interaction contract for the two-stage unread jump (#1994). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationUnreadJumpScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exactNotificationTargetIsCenteredOnTheFirstReadableFrame() {
        assertTrue(
            notificationFocusOwnsInitialConversationAnchor(
                notificationOpenRequestId = 9L,
                focusMessageId = NOTIFICATION_TARGET_ID,
                focusMessageRequestId = 4L,
                fallbackRequestId = Long.MIN_VALUE,
                ttsFocusSessionId = null,
            ),
        )
        val messages = (1..12).map { "Conversation message $it" }

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxWidth().height(420.dp).testTag(NOTIFICATION_ROOT_TAG)) {
                    val listState = rememberLazyListState()
                    var initialAnchorCommitted by remember { mutableStateOf(false) }

                    LaunchedEffect(listState) {
                        listState.scrollToItem(
                            index = NOTIFICATION_TARGET_INDEX,
                            scrollOffset = ReplyNavigation.centeredScrollOffset(420, 72),
                        )
                        initialAnchorCommitted = true
                    }

                    LazyColumn(
                        state = listState,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = if (initialAnchorCommitted) 1f else 0f },
                    ) {
                        itemsIndexed(messages, key = { index, _ -> "notification-message-$index" }) { index, label ->
                            val isTarget = index == NOTIFICATION_TARGET_INDEX
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(72.dp)
                                        .background(
                                            if (isTarget) {
                                                MaterialTheme.colorScheme.secondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                        ).then(
                                            if (isTarget) Modifier.testTag(NOTIFICATION_TARGET_TAG) else Modifier,
                                        ).padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(label, style = MaterialTheme.typography.titleMedium)
                                if (isTarget) {
                                    Text(
                                        "Opened from the tapped notification",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(NOTIFICATION_TARGET_TAG).assertIsDisplayed()
        val rootBounds = composeRule.onNodeWithTag(NOTIFICATION_ROOT_TAG).fetchSemanticsNode().boundsInRoot
        val targetBounds = composeRule.onNodeWithTag(NOTIFICATION_TARGET_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(rootBounds.center.y, targetBounds.center.y, 1f)
        composeRule
            .onNodeWithTag(NOTIFICATION_ROOT_TAG)
            .captureRoboImage("src/test/snapshots/conversation_notification_target_first_frame_light.png")
    }

    @Test
    @Suppress("LongMethod") // One vertical harness verifies both taps against the same real list state.
    fun oversizedVisibleUnreadTopAlignsBeforeSecondTapReachesTail() {
        lateinit var coordinator: ConversationScrollCoordinator
        val messages = (1..10).map { "Message $it" }
        val unreadTimelineIndex = 4
        val unreadListIndex = unreadTimelineIndex + 1
        val tailListIndex = messages.size

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxWidth().height(420.dp).testTag(ROOT_TAG)) {
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 2)
                    val scope = rememberCoroutineScope()
                    var pendingUnreadId: String? by remember { mutableStateOf(UNREAD_ID) }
                    coordinator =
                        remember(listState) {
                            ConversationScrollCoordinator(
                                writer = LazyListConversationScrollWriter(listState),
                                initialMode = ConversationScrollMode.ReadingHistory("message-1", 0),
                            )
                        }
                    val nearBottom =
                        rememberConversationNearBottom(
                            listState = listState,
                            renderedTimelineSize = messages.size,
                            hasOlderHeader = false,
                        )

                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().testTag(LIST_TAG),
                            contentPadding = PaddingValues(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "older-loading") {
                                Text("Earlier messages", modifier = Modifier.padding(12.dp))
                            }
                            itemsIndexed(messages, key = { index, _ -> "message-$index" }) { index, label ->
                                val isUnreadTarget = index == unreadTimelineIndex
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(if (isUnreadTarget) 520.dp else 72.dp)
                                            .background(
                                                if (isUnreadTarget) {
                                                    MaterialTheme.colorScheme.secondaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                },
                                            ).then(
                                                if (isUnreadTarget) Modifier.testTag(UNREAD_TAG) else Modifier,
                                            ).padding(16.dp),
                                ) {
                                    Text(label, style = MaterialTheme.typography.titleMedium)
                                    if (isUnreadTarget) {
                                        Text(
                                            "First unread message — taller than the viewport",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                            item(key = "bottom-spacer") {
                                Text("Newest message", modifier = Modifier.padding(12.dp).testTag(TAIL_TAG))
                            }
                        }

                        if (!nearBottom) {
                            ConversationJumpToNewestButton(
                                unreadIncomingCount = 6,
                                onClick = {
                                    scope.launch {
                                        val targetId = pendingUnreadId
                                        when (
                                            coordinator.jumpToUnreadOrNewest(
                                                pendingUnreadMessageId = targetId,
                                                resolveUnreadIndex = {
                                                    if (targetId == UNREAD_ID) unreadListIndex else null
                                                },
                                                isUnreadTopAligned = {
                                                    isConversationItemTopAligned(listState, unreadListIndex)
                                                },
                                                resolveTailIndex = { tailListIndex },
                                            )
                                        ) {
                                            ConversationJumpToNewestOutcome.UnreadStart -> {
                                                coordinator.settleReadingAt(
                                                    ConversationScrollAnchor(
                                                        listIndex = unreadListIndex,
                                                        pixelOffset = 0,
                                                        itemId = "msg:$UNREAD_ID",
                                                        messageId = UNREAD_ID,
                                                    ),
                                                )
                                                pendingUnreadId = null
                                            }
                                            ConversationJumpToNewestOutcome.Tail -> pendingUnreadId = null
                                            ConversationJumpToNewestOutcome.Cancelled -> Unit
                                        }
                                    }
                                },
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(16.dp)
                                        .testTag(BUTTON_TAG),
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag(UNREAD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        val listTop =
            composeRule
                .onNodeWithTag(LIST_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.top
        val unreadTop =
            composeRule
                .onNodeWithTag(UNREAD_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertEquals(listTop, unreadTop, 1f)
        composeRule.runOnIdle {
            assertEquals(ConversationScrollMode.ReadingHistory(UNREAD_ID, 0), coordinator.mode)
        }
        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/conversation_unread_jump_first_stage_dark.png")

        composeRule.onNodeWithTag(BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TAIL_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(BUTTON_TAG).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(ConversationScrollMode.FollowingTail, coordinator.mode)
        }
    }

    private companion object {
        const val ROOT_TAG = "conversation-unread-jump-root"
        const val LIST_TAG = "conversation-unread-jump-list"
        const val BUTTON_TAG = "conversation-unread-jump-button"
        const val UNREAD_TAG = "conversation-unread-jump-target"
        const val TAIL_TAG = "conversation-unread-jump-tail"
        const val UNREAD_ID = "message-5"
        const val NOTIFICATION_ROOT_TAG = "conversation-notification-target-root"
        const val NOTIFICATION_TARGET_TAG = "conversation-notification-target"
        const val NOTIFICATION_TARGET_ID = "notification-message-7"
        const val NOTIFICATION_TARGET_INDEX = 6
    }
}
