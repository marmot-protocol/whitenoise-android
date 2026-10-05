package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.AndroidNotificationSettingsTarget
import dev.ipf.whitenoise.android.notifications.ConversationNotificationCategorySetting
import dev.ipf.whitenoise.android.notifications.ConversationNotificationScope
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.ui.group.ConversationNotificationCategoriesList
import dev.ipf.whitenoise.android.ui.group.ConversationSoundAppearanceSheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual bottom-sheet window and long settings content, without platform preparation timing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationSoundAppearanceSheetScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val visualErrors = ErrorCollector()

    @Test
    fun groupSoundSheetLight() {
        render(isDm = false)
        capture("conversation_sound_sheet_group_light")
    }

    @Test
    fun directSoundSheetDark() {
        render(isDm = true, dark = true)
        capture("conversation_sound_sheet_dm_dark")
    }

    @Test
    fun soundSheetLargeRtl() {
        render(isDm = false, rtl = true, fontScale = 2f)
        capture("conversation_sound_sheet_large_rtl")
    }

    @Test
    fun lastCategoryRemainsReachableAndCloseDismissesTheSheet() {
        var opened: NotificationChannelSpec? = null
        render(isDm = false, fontScale = 2f, onOpen = { opened = it.channel })
        composeRule
            .onNodeWithTag("open-conversation-notification-${NotificationChannelSpec.AGENT_ACTIVITY.id}")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(NotificationChannelSpec.AGENT_ACTIVITY, opened) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        composeRule.onNodeWithTag("sheet.surface").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val surface = composeRule.onNodeWithTag("sheet.surface")
        val handle = composeRule.onNodeWithTag("sheet.dragHandle", useUnmergedTree = true)
        visualErrors.checkSucceeds { surface.captureRoboImage("src/test/snapshots/$name.png") }
        handle.assertIsDisplayed()
    }

    private fun render(
        isDm: Boolean,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        onOpen: (ConversationNotificationCategorySetting) -> Unit = {},
    ) {
        val visible = mutableStateOf(true)
        val settings = settings(isDm)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) {
                    Surface(Modifier.fillMaxSize()) {}
                    if (visible.value) {
                        ConversationSoundAppearanceSheet(onDismiss = { visible.value = false }) {
                            ConversationNotificationCategoriesList(
                                settings,
                                onOpen = onOpen,
                                onScopeChange = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
    }

    private fun settings(isDm: Boolean): List<ConversationNotificationCategorySetting> {
        val primary = if (isDm) NotificationChannelSpec.DIRECT_MESSAGES else NotificationChannelSpec.GROUP_MESSAGES
        return listOf(
            primary,
            NotificationChannelSpec.MENTIONS,
            NotificationChannelSpec.REACTIONS,
            NotificationChannelSpec.INVITES,
            NotificationChannelSpec.AGENT_ACTIVITY,
        ).map { channel ->
            val custom = channel == NotificationChannelSpec.GROUP_MESSAGES
            ConversationNotificationCategorySetting(
                channel = channel,
                scope =
                    if (custom) {
                        ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT
                    } else {
                        ConversationNotificationScope.USE_GLOBAL_DEFAULT
                    },
                canChangeScope = channel != NotificationChannelSpec.GROUP_MESSAGES,
                settingsTarget =
                    if (custom) {
                        AndroidNotificationSettingsTarget.Conversation("sheet-group", "sheet-group")
                    } else {
                        AndroidNotificationSettingsTarget.Global(channel)
                    },
            )
        }
    }
}
