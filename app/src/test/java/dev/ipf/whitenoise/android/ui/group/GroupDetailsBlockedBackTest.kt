package dev.ipf.whitenoise.android.ui.group

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** An owner-blocked route owns Back without mounting protected controls or falling through to its parent. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
class GroupDetailsBlockedBackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun rejectedInitialOwnerStillClosesDetailsExactlyOnce() {
        val state =
            conversationTimelineTestAppState(
                ScriptedConversationLiveSubscriptions(emptyList(), conversationTimelineTestGroup()).subscriptions,
            )
        val controller =
            ConversationController(
                state,
                conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                accountRefOverride = "notification-account",
            )
        var back = 0
        var parent = 0
        assertFalse(controller.isCurrentGroupMutationOwner(state, state.runtimeGeneration))
        try {
            rule.setContent {
                WhiteNoiseTheme {
                    BackHandler { parent++ }
                    GroupDetailsScreen(state, controller, onBack = { back++ }, onLeft = {})
                }
            }
            rule.onNodeWithTag("chat_info.reset").assertDoesNotExist()
            rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(1, back)
            assertEquals(0, parent)
        } finally {
            controller.onCleared()
        }
    }
}
