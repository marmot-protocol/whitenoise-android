package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.AndroidNotificationSettingsTarget
import dev.ipf.whitenoise.android.notifications.ConversationNotificationCategorySetting
import dev.ipf.whitenoise.android.notifications.ConversationNotificationScope
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Holding the alert switch in a category row explains the alert; the chevron explains the settings action. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationNotificationCategoryExplanationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val channel = NotificationChannelSpec.MENTIONS
    private var opened = 0
    private var changed = 0

    @Test
    fun holdingAlertSwitchShowsAlertExplanation() {
        render()
        composeRule.onNodeWithTag("conversation-alert-${channel.id}").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertSheetBody(R.string.explanation_privacy_conversation_alert)
        assertEquals(0, opened + changed)
    }

    @Test
    fun holdingChevronShowsCategoryExplanation() {
        render()
        composeRule
            .onNodeWithTag("conversation-category-chevron-${channel.id}", useUnmergedTree = true)
            .performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertSheetBody(R.string.explanation_privacy_conversation_category)
        assertEquals(0, opened + changed)
    }

    private fun assertSheetBody(body: Int) {
        composeRule.onNodeWithTag("explanation.sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("explanation.body").assertTextEquals(context.getString(body))
    }

    private fun render() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ExplanationHost {
                    Surface {
                        ConversationNotificationCategoriesList(
                            settings =
                                listOf(
                                    ConversationNotificationCategorySetting(
                                        channel = channel,
                                        scope = ConversationNotificationScope.USE_GLOBAL_DEFAULT,
                                        canChangeScope = true,
                                        settingsTarget = AndroidNotificationSettingsTarget.Global(channel),
                                    ),
                                ),
                            onOpen = { opened++ },
                            alertEnabled = mapOf(channel to true),
                            onAlertChange = { _, _ -> changed++ },
                        )
                    }
                }
            }
        }
    }
}
