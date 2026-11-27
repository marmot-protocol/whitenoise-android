package dev.ipf.whitenoise.android.ui.settings

import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class AccountKeysAccessibilityAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rawExportReportsTheScreenLockRequirementToAccessibilityServices() {
        val ui = ProfileKeysUiState().apply { openRawExport() }
        assertPoliteTextChange(ui, R.string.keep_your_private_key_safe, R.string.export_nsec)
    }

    @Test
    fun encryptedExportReportsTheScreenLockRequirementToAccessibilityServices() {
        val ui =
            ProfileKeysUiState().apply {
                openEncryptedExport()
                password.setTextAndPlaceCursorAtEnd(TEST_PASSPHRASE)
                confirmation.setTextAndPlaceCursorAtEnd(TEST_PASSPHRASE)
            }
        assertPoliteTextChange(ui, R.string.encrypted_private_key, R.string.export)
    }

    @Suppress("DEPRECATION") // Pre-33 accessibility events still need recycling.
    private fun assertPoliteTextChange(
        ui: ProfileKeysUiState,
        @StringRes titleRes: Int,
        @StringRes actionRes: Int,
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val title = composeRule.activity.getString(titleRes)
        val action = composeRule.activity.getString(actionRes)
        val notice = composeRule.activity.getString(R.string.toast_app_lock_screen_lock_required)
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileKeysDialogs(
                    ui = ui,
                    canExport = true,
                    onExport = { _, _ -> ui.exportScreenLockRequired = true },
                    onCopyBackup = {},
                    onExportBackup = {},
                    cancelRawAuthentication = {},
                )
            }
        }

        val titleNode = composeRule.onNodeWithText(title).assertIsDisplayed().fetchSemanticsNode()
        val root = checkNotNull(titleNode.root)
        try {
            composeRule.runOnIdle {
                // Compose ignores UiAutomation when looking for an enabled accessibility service.
                root.forceAccessibilityForTesting(true)
                root.updateSemanticsForTest()
            }
            automation.waitForIdle(ACCESSIBILITY_QUIET_MILLIS, ACCESSIBILITY_TIMEOUT_MILLIS)
            val event =
                automation.executeAndWaitForEvent(
                    { composeRule.onNodeWithText(action).performClick() },
                    { it.isPoliteTextChange("$title\n$notice") },
                    ACCESSIBILITY_TIMEOUT_MILLIS,
                )
            event.recycle()
        } finally {
            composeRule.runOnUiThread { root.forceAccessibilityForTesting(false) }
        }
    }

    private fun AccessibilityEvent.isPoliteTextChange(expected: String): Boolean {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT == 0
        ) {
            return false
        }
        val node = source ?: return false
        return node.liveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE && node.text?.toString() == expected
    }

    private companion object {
        const val ACCESSIBILITY_QUIET_MILLIS = 500L
        const val ACCESSIBILITY_TIMEOUT_MILLIS = 10_000L
        const val TEST_PASSPHRASE = "simulated backup passphrase"
    }
}
