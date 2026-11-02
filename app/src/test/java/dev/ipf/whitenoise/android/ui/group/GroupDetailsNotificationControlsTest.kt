package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.ConversationAlertPreferences
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupDetailsNotificationControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val visualErrors = ErrorCollector()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** New groups keep All; independent device switches can retain mentions-only and restore All. */
    @Test
    fun newGroupStartsWithAllAndCanChooseOnlyMentions() = verifyCategoryControls()

    @Test
    fun notificationCategoryControlsDark() = verifyCategoryControls(darkTheme = true, suffix = "_dark")

    @Test
    fun notificationCategoryControlsAmoled() {
        verifyCategoryControls(darkTheme = true, amoled = true, suffix = "_amoled")
    }

    private fun verifyCategoryControls(
        darkTheme: Boolean = false,
        amoled: Boolean = false,
        suffix: String = "",
    ) {
        listOf("whitenoise.chat_mute", "whitenoise.conversation_alerts").forEach { name ->
            context
                .getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        val fixture = notificationControlsFixture()
        val groupId = fixture.controller.group.groupIdHex
        render(fixture, darkTheme = darkTheme, amoled = amoled)
        composeRule
            .onNodeWithText(context.getString(R.string.sounds_and_notifications))
            .performScrollTo()
            .performClick()
        val messagesTag = "conversation-alert-messages_group"
        val reactionsTag = "conversation-alert-reactions_v2"
        // Capture the prepared category rows, not the transient delivery-only fallback.
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodesWithTag("open-conversation-notification-messages_group")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(messagesTag).assertIsOn()
        composeRule.onNodeWithTag("conversation-alert-mentions").assertIsOn()
        visualErrors.checkSucceeds {
            composeRule.onRoot().captureRoboImage("src/test/snapshots/group_notify_new_all$suffix.png")
        }
        composeRule.onNodeWithTag(messagesTag).performClick()
        composeRule.waitUntil(5_000) {
            ConversationAlertPreferences(context)
                .choice(ACCOUNT_REF, groupId, NotificationChannelSpec.GROUP_MESSAGES) == false &&
                alertControlReady(messagesTag, ToggleableState.Off)
        }
        composeRule.onNodeWithTag(reactionsTag).performClick()
        composeRule.waitUntil(5_000) {
            ConversationAlertPreferences(context)
                .choice(ACCOUNT_REF, groupId, NotificationChannelSpec.REACTIONS) == false &&
                alertControlReady(reactionsTag, ToggleableState.Off)
        }
        composeRule.onNodeWithTag(messagesTag).assertIsOff()
        composeRule.onNodeWithTag(reactionsTag).assertIsOff()
        composeRule.onNodeWithTag("conversation-alert-mentions").assertIsOn()
        visualErrors.checkSucceeds {
            composeRule.onRoot().captureRoboImage("src/test/snapshots/group_notify_selected_mentions$suffix.png")
        }
        val restored = ConversationAlertPreferences(context)
        assertEquals(false, restored.choice(ACCOUNT_REF, groupId, NotificationChannelSpec.GROUP_MESSAGES))
        assertEquals(false, restored.choice(ACCOUNT_REF, groupId, NotificationChannelSpec.REACTIONS))
        composeRule.onNodeWithTag(messagesTag).performClick()
        composeRule.waitUntil(5_000) {
            ConversationAlertPreferences(context)
                .choice(ACCOUNT_REF, groupId, NotificationChannelSpec.GROUP_MESSAGES) == true &&
                alertControlReady(messagesTag, ToggleableState.On)
        }
        composeRule.onNodeWithTag(messagesTag).assertIsOn()
    }

    private fun alertControlReady(
        tag: String,
        state: ToggleableState,
    ): Boolean {
        val node =
            composeRule
                .onAllNodesWithTag(tag)
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config ?: return false
        return !node.contains(SemanticsProperties.Disabled) && node[SemanticsProperties.ToggleableState] == state
    }

    private fun render(
        testController: GroupDetailsTestController,
        darkTheme: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                GroupDetailsScreen(
                    appState = testController.appState,
                    controller = testController.controller,
                    onBack = {},
                    onLeft = {},
                    onOpenSearch = null,
                    onStartGroupWithPeer = {},
                )
            }
        }
        composeRule.waitForIdle()
    }
}

private const val ACCOUNT_REF = "account-a"
