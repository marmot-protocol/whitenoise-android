package dev.ipf.whitenoise.android.ui.group

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The header Members shortcut's page survives activity recreation after its one-shot request is consumed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
class GroupDetailsMembersShortcutRestoreTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun recreatedShortcutMembersPageStaysOpenAndBackLeavesDetails() {
        val state =
            conversationTimelineTestAppState(
                ScriptedConversationLiveSubscriptions(emptyList(), conversationTimelineTestGroup()).subscriptions,
            )
        val controller =
            ConversationController(
                state,
                conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
            )
        var back = 0
        val restoration = StateRestorationTester(rule)
        try {
            restoration.setContent {
                var autoOpenMembers by rememberSaveable { mutableStateOf(true) }
                WhiteNoiseTheme {
                    GroupDetailsScreen(
                        state,
                        controller,
                        onBack = { back++ },
                        onLeft = {},
                        autoOpenMembers = autoOpenMembers,
                        onAutoOpenMembersConsumed = { autoOpenMembers = false },
                    )
                }
            }
            rule.onNodeWithTag("chat_info.members_screen").assertExists()
            restoration.emulateSavedInstanceStateRestore()
            rule.onNodeWithTag("chat_info.members_screen").assertExists()
            rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(1, back)
        } finally {
            controller.onCleared()
        }
    }
}
