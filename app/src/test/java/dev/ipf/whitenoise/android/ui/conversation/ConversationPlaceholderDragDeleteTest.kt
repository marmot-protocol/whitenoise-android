package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.hiddenMessageIdsInGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Covers a hold-drag across projection-deleted placeholders and a live row, then Delete for me, on the real screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
internal class ConversationPlaceholderDragDeleteTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * No native runtime is bound, so a protocol delete attempt would fail and leave its row selected and unhidden:
     * three hidden rows and a closed selection prove the placeholders and the live row all took the local-only path.
     */
    @Test
    @Suppress("LongMethod") // The real screen requires its controller, timeline, and composition owners together.
    fun draggingAcrossPlaceholderLiveAndPlaceholderHidesAllThreeLocally() {
        val group = conversationTimelineTestGroup()
        val members = conversationTimelineMemberSnapshot()
        val records =
            listOf(
                timelineRecord(messageId = "1".repeat(64), timelineAt = 1uL).copy(deleted = true),
                timelineRecord(messageId = "2".repeat(64), timelineAt = 2uL, plaintext = "live-row"),
                timelineRecord(messageId = "3".repeat(64), timelineAt = 3uL).copy(deleted = true),
            )
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
                    Box(Modifier.fillMaxSize().testTag(HOST_TAG)) {
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

            val lower =
                composeRule
                    .onNodeWithTag(messageBubbleRowTestTag(records[2].messageIdHex))
                    .fetchSemanticsNode()
                    .boundsInRoot.center
            val higher =
                composeRule
                    .onNodeWithTag(messageBubbleRowTestTag(records[0].messageIdHex))
                    .fetchSemanticsNode()
                    .boundsInRoot.center
            val host = composeRule.onNodeWithTag(HOST_TAG)
            host.performTouchInput {
                down(lower)
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
                move()
            }
            host.performTouchInput {
                moveTo(higher)
                up()
            }
            composeRule.runOnIdle {
                assertEquals(records.map { it.messageIdHex }.toSet(), surfaceState.selectedMessages.keys.toSet())
            }

            val deleteLabel = string(R.string.message_selection_action_delete)
            composeRule
                .onAllNodes(hasContentDescription(deleteLabel) or hasText(deleteLabel))
                .onFirst()
                .performClick()
            composeRule.onNodeWithText(string(R.string.delete_for_everyone)).assertDoesNotExist()
            composeRule.onNodeWithText(string(R.string.delete_for_me)).performClick()
            // Each hide retracts its notification cards on Dispatchers.Default and resumes on the test dispatcher,
            // which only runs while the rule idles; waitUntil alone sleeps without draining it.
            val deadline = System.nanoTime() + ASYNC_TIMEOUT_MILLIS * 1_000_000
            while (System.nanoTime() < deadline && !surfaceState.selectedMessages.isEmpty()) {
                composeRule.waitForIdle()
                Thread.sleep(POLL_MILLIS)
            }
            composeRule.waitUntil(timeoutMillis = ASYNC_TIMEOUT_MILLIS) { surfaceState.selectedMessages.isEmpty() }
            composeRule.runOnIdle {
                assertEquals(
                    records.map { it.messageIdHex }.toSet(),
                    appState
                        .hiddenMessageIdsInGroup(
                            ConversationTimelineTestIds.ACCOUNT_REF,
                            ConversationTimelineTestIds.GROUP_ID,
                        ).toSet(),
                )
                assertTrue(surfaceState.selectedMessages.isEmpty())
            }
        } finally {
            controller.onCleared()
        }
    }

    private fun string(resource: Int): String {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        return application.getString(resource)
    }

    private companion object {
        const val HOST_TAG = "placeholder-drag-delete-host"
        const val ASYNC_TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 20L
    }
}
