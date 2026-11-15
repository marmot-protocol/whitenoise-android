package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.PushStorageRecovery
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Hold-to-explain on the notification, privacy and storage rows. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class PrivacyStorageExplanationsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var changes = 0
    private var retries = 0

    @Test
    fun previewSwitchHoldExplainsAndQuickClickToggles() {
        render {
            NotificationPreviewControl(
                state = NotificationPreviewControlState(enabled = false),
                onChange = { changes++ },
                onRetry = { retries++ },
            )
        }
        val title = context.getString(R.string.notification_show_previews)
        composeRule.onNode(hasText(title)).performClick()
        assertEquals(1, changes)
        changes = 0
        composeRule.onNode(hasText(title)).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertSheet(R.string.explanation_privacy_notif_previews)
        assertEquals(0, changes)
    }

    @Test
    fun previewRetryHoldExplainsAndQuickClickRetries() {
        render {
            NotificationPreviewControl(
                state = NotificationPreviewControlState(enabled = false, failed = true),
                onChange = { changes++ },
                onRetry = { retries++ },
            )
        }
        val title = context.getString(R.string.retry)
        composeRule.onNode(hasText(title)).performClick()
        assertEquals(1, retries)
        retries = 0
        composeRule.onNode(hasText(title)).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertSheet(R.string.explanation_privacy_notif_preview_retry)
        assertEquals(0, retries)
        assertEquals(0, changes)
    }

    @Test
    fun pushStorageRecoveryHoldExplainsAndQuickClickRetries() {
        render {
            PushStorageRecoveryControl(
                state = PushStorageRecovery.LegacyImportPending,
                busy = false,
                onRetry = { retries++ },
            )
        }
        val title = context.getString(R.string.retry)
        composeRule.onNode(hasText(title)).performClick()
        assertEquals(1, retries)
        retries = 0
        composeRule.onNode(hasText(title)).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertSheet(R.string.explanation_privacy_notif_push_retry)
        assertEquals(0, retries)
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent {
            WhiteNoiseTheme { ExplanationHost { Surface { content() } } }
        }
    }

    private fun assertSheet(body: Int) {
        composeRule.onNodeWithTag("explanation.sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("explanation.body").assertTextEquals(context.getString(body))
    }
}
