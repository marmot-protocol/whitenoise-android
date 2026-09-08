package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineGroupRoster
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.notificationChatListRow
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme

/** Owns one direct production conversation surface and its lifecycle-bound controller. */
internal data class DirectNotificationConversationFixture(
    val appState: WhiteNoiseAppState,
    val controller: ConversationController,
    val chat: ChatListItem,
)

/** Mounts real request-routed conversation content without involving the outer shell. */
internal class DirectNotificationConversationHarness(
    private val composeRule: ComposeContentTestRule,
) {
    /** Builds a real controller/screen fixture around a caller-supplied subscription boundary. */
    fun create(liveSubscriptions: ConversationLiveSubscriptions): DirectNotificationConversationFixture {
        val group = conversationTimelineTestGroup()
        val row = notificationChatListRow()
        val appState = conversationTimelineTestAppState(liveSubscriptions)
        val memberSnapshot = conversationTimelineMemberSnapshot()
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group,
                initialMemberSnapshot = memberSnapshot,
                initialChatListRow = row,
                groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                startOnConstruction = true,
            )
        val chat =
            ChatListItem(
                group = group,
                latest = null,
                otherMemberAccount = null,
                memberCount = 1,
                memberSnapshot = memberSnapshot,
                projection = row,
            )
        return DirectNotificationConversationFixture(appState, controller, chat)
    }

    /** Mounts the real screen while allowing one retained controller's request generation to advance. */
    fun mount(
        fixture: DirectNotificationConversationFixture,
        mounted: MutableState<Boolean>,
        notificationOpenRequestId: () -> Long,
        onTimelineVisibilityChanged: (Long, Boolean) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            if (mounted.value) {
                val presentedRequestId = notificationOpenRequestId()
                WhiteNoiseTheme {
                    ConversationScreen(
                        appState = fixture.appState,
                        chat = fixture.chat,
                        controller = fixture.controller,
                        onBack = {},
                        notificationOpenRequestId = presentedRequestId,
                        onNotificationTimelineVisibilityChanged = { visible ->
                            onTimelineVisibilityChanged(presentedRequestId, visible)
                        },
                    )
                }
            }
        }
    }

    /** Disposes the production composition before clearing its controller-owned jobs. */
    fun dispose(
        fixture: DirectNotificationConversationFixture,
        mounted: MutableState<Boolean>,
    ) {
        try {
            composeRule.runOnIdle { mounted.value = false }
            composeRule.waitForIdle()
        } finally {
            fixture.controller.onCleared()
        }
    }
}
