package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Covers hold-drag selection through the production reversed conversation list. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
internal class ConversationReverseDragSelectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** An upward drag keeps the anchor and every crossed message selected after release. */
    @Test
    @Suppress("LongMethod") // The real screen requires its controller, timeline, and composition owners together.
    fun draggingFromLowerMessageToHigherMessageSelectsTheProductionRange() {
        val group = conversationTimelineTestGroup()
        val members = conversationTimelineMemberSnapshot()
        val records =
            (1..4).map { index ->
                timelineRecord(
                    messageId = index.toString().repeat(64),
                    timelineAt = index.toULong(),
                    plaintext = "message-$index",
                )
            }
        val subscription = ScriptedConversationTimelineSubscription(timelinePage(*records.toTypedArray()))
        val scripted = ScriptedConversationLiveSubscriptions(listOf(subscription), group)
        val appState = conversationTimelineTestAppState(scripted.subscriptions)
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group,
                initialMemberSnapshot = members,
                startOnConstruction = false,
            )
        val surfaceState = ConversationSurfaceState()
        try {
            composeRule.runOnUiThread {
                runBlocking {
                    controller.applyTimelinePage(
                        page = timelinePage(*records.toTypedArray()),
                        replaceWindow = true,
                        updatePagination = true,
                    )
                }
                controller.markAuthoritativeTimelinePublishedForTest()
            }
            val chat =
                ChatListItem(
                    group = group,
                    latest = null,
                    otherMemberAccount = null,
                    memberCount = 1,
                    memberSnapshot = members,
                )
            composeRule.setContent {
                WhiteNoiseTheme {
                    Box(Modifier.fillMaxSize().testTag("reverse-drag-conversation-host")) {
                        ConversationScreen(
                            appState = appState,
                            chat = chat,
                            controller = controller,
                            onBack = {},
                            surfaceState = surfaceState,
                        )
                    }
                }
            }
            composeRule.waitForIdle()

            val lowerId = records[3].messageIdHex
            val higherId = records[1].messageIdHex
            val middleId = records[2].messageIdHex
            val lower =
                composeRule
                    .onNodeWithTag(messageBubbleRowTestTag(lowerId))
                    .fetchSemanticsNode()
                    .boundsInRoot.center
            val higher =
                composeRule
                    .onNodeWithTag(messageBubbleRowTestTag(higherId))
                    .fetchSemanticsNode()
                    .boundsInRoot.center
            val root = composeRule.onNodeWithTag("reverse-drag-conversation-host")
            root.performTouchInput {
                down(lower)
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
                move()
            }
            root.performTouchInput {
                moveTo(higher)
                up()
            }
            composeRule.runOnIdle {
                assertEquals(setOf(lowerId, middleId, higherId), surfaceState.selectedMessages.keys.toSet())
            }
        } finally {
            controller.onCleared()
        }
    }
}
