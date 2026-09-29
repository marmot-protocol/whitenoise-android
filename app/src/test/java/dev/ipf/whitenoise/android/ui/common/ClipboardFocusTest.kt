package dev.ipf.whitenoise.android.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ClipboardFocusTest {
    @get:Rule val composeRule = createComposeRule()
    private val clipboard = ApplicationProvider.getApplicationContext<Context>()
        .getSystemService(ClipboardManager::class.java)

    @After fun clearClipboard() = clipboard.clearPrimaryClip()

    @Test fun focusReturnRefreshesPasteAffordanceWithoutClipCallback() {
        clipboard.clearPrimaryClip()
        val focused = mutableStateOf(false)
        val windowInfo = object : WindowInfo {
            override val isWindowFocused: Boolean get() = focused.value
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalWindowInfo provides windowInfo) {
                if (rememberClipboardCanOfferPaste(clipboard)) Text("Paste available")
            }
        }
        composeRule.onNodeWithText("Paste available").assertDoesNotExist()

        // Simulate an OS clipboard change while backgrounded for which no listener is delivered.
        // The Robolectric shadow's backing clip is set directly, bypassing its listener dispatch.
        val shadow = Shadows.shadowOf(clipboard)
        val clipField = shadow.javaClass.getDeclaredField("clip")
        clipField.isAccessible = true
        clipField.set(shadow, ClipData.newPlainText("recipient", "npub1"))
        composeRule.onNodeWithText("Paste available").assertDoesNotExist()

        composeRule.runOnIdle { focused.value = true }
        composeRule.onNodeWithText("Paste available").assertExists()
    }
}
