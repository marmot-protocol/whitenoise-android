package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.notifications.AndroidNotificationSettingsTarget
import dev.ipf.whitenoise.android.notifications.ConversationNotificationCategorySetting
import dev.ipf.whitenoise.android.notifications.ConversationNotificationScope
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.ui.group.ConversationNotificationCategoriesList
import dev.ipf.whitenoise.android.ui.settings.GlobalNotificationCategories
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class NotificationScopeSettingsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun globalDefaultsLight() {
        renderGlobal(darkTheme = false)
        composeRule.onNodeWithTag(GLOBAL_TAG).captureRoboImage(
            "src/test/snapshots/notification_global_defaults_light.png",
        )
    }

    @Test
    fun globalDefaultsDark() {
        renderGlobal(darkTheme = true)
        composeRule.onNodeWithTag(GLOBAL_TAG).captureRoboImage(
            "src/test/snapshots/notification_global_defaults_dark.png",
        )
    }

    @Test
    fun conversationScopesLight() {
        renderConversation(darkTheme = false)
        composeRule.onNodeWithTag(CONVERSATION_TAG).captureRoboImage(
            "src/test/snapshots/notification_conversation_scopes_light.png",
        )
    }

    @Test
    fun conversationScopesDark() {
        renderConversation(darkTheme = true)
        composeRule.onNodeWithTag(CONVERSATION_TAG).captureRoboImage(
            "src/test/snapshots/notification_conversation_scopes_dark.png",
        )
    }

    @Test
    fun rowsExposeExactScopeAndIndependentOpenAndToggleActions() {
        var opened: NotificationChannelSpec? = null
        var toggled: Pair<NotificationChannelSpec, Boolean>? = null
        renderConversation(
            darkTheme = false,
            onOpen = { opened = it.channel },
            onScopeChange = { setting, custom -> toggled = setting.channel to custom },
        )

        composeRule.onNodeWithContentDescription("Custom Mentions for this chat").assertIsOn()
        composeRule.onNodeWithContentDescription("Custom Reactions for this chat").assertIsOff()

        composeRule.onNodeWithTag("open-conversation-notification-reactions_v2").performClick()
        composeRule.runOnIdle {
            assertEquals(NotificationChannelSpec.REACTIONS, opened)
            assertFalse(toggled != null)
            opened = null
        }

        composeRule.onNodeWithContentDescription("Custom Reactions for this chat").performClick()
        composeRule.runOnIdle {
            assertEquals(NotificationChannelSpec.REACTIONS to true, toggled)
            assertTrue(opened == null)
        }
    }

    @Test
    fun everyGlobalRowOpensItsSelectedStableChannel() {
        var opened: NotificationChannelSpec? = null
        renderGlobal(darkTheme = false, onOpen = { opened = it })

        NotificationChannelSpec.entries.forEach { expected ->
            opened = null
            composeRule.onNodeWithTag("global-notification-category-${expected.id}").performClick()
            composeRule.runOnIdle { assertEquals(expected, opened) }
        }
    }

    @Test
    fun everyConversationRowOpensItsSelectedSettingsTarget() {
        var opened: ConversationNotificationCategorySetting? = null
        val expectedSettings = conversationSettings()
        renderConversation(darkTheme = false, onOpen = { opened = it })

        expectedSettings.forEach { expected ->
            opened = null
            composeRule.onNodeWithTag("open-conversation-notification-${expected.channel.id}").performClick()
            composeRule.runOnIdle { assertEquals(expected, opened) }
        }
    }

    private fun renderGlobal(
        darkTheme: Boolean,
        onOpen: (NotificationChannelSpec) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.width(360.dp).testTag(GLOBAL_TAG)) {
                    GlobalNotificationCategories(onOpenChannel = onOpen)
                }
            }
        }
    }

    private fun renderConversation(
        darkTheme: Boolean,
        onOpen: (ConversationNotificationCategorySetting) -> Unit = {},
        onScopeChange: (ConversationNotificationCategorySetting, Boolean) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.width(360.dp).testTag(CONVERSATION_TAG)) {
                    ConversationNotificationCategoriesList(
                        settings = conversationSettings(),
                        onOpen = onOpen,
                        onScopeChange = onScopeChange,
                    )
                }
            }
        }
    }

    private fun conversationSettings(): List<ConversationNotificationCategorySetting> =
        listOf(
            setting(NotificationChannelSpec.GROUP_MESSAGES, custom = true, canChange = false),
            setting(NotificationChannelSpec.MENTIONS, custom = true),
            setting(NotificationChannelSpec.REACTIONS, custom = false),
            setting(NotificationChannelSpec.INVITES, custom = false),
            setting(NotificationChannelSpec.AGENT_ACTIVITY, custom = true),
        )

    private fun setting(
        channel: NotificationChannelSpec,
        custom: Boolean,
        canChange: Boolean = true,
    ): ConversationNotificationCategorySetting {
        val scope =
            if (custom) {
                ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT
            } else {
                ConversationNotificationScope.USE_GLOBAL_DEFAULT
            }
        return ConversationNotificationCategorySetting(
            channel = channel,
            scope = scope,
            canChangeScope = canChange,
            settingsTarget =
                if (custom) {
                    AndroidNotificationSettingsTarget.Conversation(
                        channelId = "${channel.id}:conv:conversation-test",
                        shortcutId = "conversation-test",
                    )
                } else {
                    AndroidNotificationSettingsTarget.Global(channel)
                },
        )
    }

    private companion object {
        const val GLOBAL_TAG = "notification-global-defaults"
        const val CONVERSATION_TAG = "notification-conversation-scopes"
    }
}
