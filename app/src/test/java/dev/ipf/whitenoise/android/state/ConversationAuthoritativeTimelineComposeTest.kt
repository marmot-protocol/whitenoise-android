package dev.ipf.whitenoise.android.state

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose-level proof that the controller's authoritative order reaches visible rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationAuthoritativeTimelineComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun membershipRowIsDisplayedAboveTheMessageItAuthorizes() =
        runBlocking {
            val subscription =
                ScriptedConversationTimelineSubscription(
                    timelinePage(membershipRecord(), appRecord()),
                )
            val scripted =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = listOf(subscription),
                    group = conversationTimelineTestGroup(),
                )
            val controller =
                ConversationController(
                    appState = conversationTimelineTestAppState(scripted.subscriptions),
                    initialGroup = conversationTimelineTestGroup(),
                    initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                    groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                    startOnConstruction = true,
                )
            try {
                awaitConversationCondition { controller.timeline.size == 2 }
                val group = conversationTimelineTestGroup()
                val chat =
                    ChatListItem(
                        group = group,
                        latest = null,
                        otherMemberAccount = null,
                        memberCount = 1,
                        memberSnapshot = conversationTimelineMemberSnapshot(),
                    )

                composeRule.setContent {
                    WhiteNoiseTheme {
                        ConversationScreen(
                            appState = controller.appState,
                            chat = chat,
                            controller = controller,
                            onBack = {},
                        )
                    }
                }
                composeRule.waitForIdle()

                val systemTop =
                    composeRule
                        .onNodeWithText("You added", substring = true)
                        .fetchSemanticsNode()
                        .boundsInRoot.top
                val appTop =
                    composeRule
                        .onNodeWithText("body-$APP_MESSAGE_ID")
                        .fetchSemanticsNode()
                        .boundsInRoot.top
                assertTrue("membership row must render above the authorized app message", systemTop < appTop)
            } finally {
                controller.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(scripted)
            }
        }

    private fun membershipRecord() =
        timelineRecord(
            messageId = SYSTEM_MESSAGE_ID,
            timelineAt = 200uL,
            plaintext = "member added",
        ).copy(
            sourceMessageIdHex = null,
            direction = "system",
            kind = 1210uL,
            sourceEpoch = SOURCE_EPOCH,
            groupSystem =
                GroupSystemEventFfi(
                    systemType = "member_added",
                    text = "member added",
                    actorAccountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                    subjectAccountIdHex = ConversationTimelineTestIds.SENDER_ID,
                    name = null,
                    oldName = null,
                    oldRetentionSeconds = null,
                    newRetentionSeconds = null,
                ),
        )

    private fun appRecord() =
        timelineRecord(
            messageId = APP_MESSAGE_ID,
            timelineAt = 100uL,
        ).copy(sourceEpoch = SOURCE_EPOCH)

    private companion object {
        const val SOURCE_EPOCH = 7uL
        val SYSTEM_MESSAGE_ID = "ff".repeat(32)
        val APP_MESSAGE_ID = "00".repeat(32)
    }
}
