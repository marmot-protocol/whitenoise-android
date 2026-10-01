package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.ConversationDictationDeliveryMode
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.DictationSettingsScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-mdpi")
class ConversationDictationSettingsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun automaticSendExplainsUnsentRecoveryLight() {
        capture("dictation_send_recovery_settings_light.png", dark = false, scale = 1f, rtl = false)
    }

    @Test
    fun automaticSendExplainsUnsentRecoveryLargeRtlDark() {
        capture("dictation_send_recovery_settings_large_rtl_dark.png", dark = true, scale = 2f, rtl = true)
    }

    private fun capture(
        name: String,
        dark: Boolean,
        scale: Float,
        rtl: Boolean,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("whitenoise.composer_dictation", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "missing-account",
            )
        appState.conversationDictationPreferences.setFinishAfterSilenceMillis(3_000L)
        appState.conversationDictationPreferences.setSilenceDeliveryMode(ConversationDictationDeliveryMode.SendOnFinish)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        DictationSettingsScreen(appState, onBack = {}, resolveProviderPackage = { _, _ -> null })
                    }
                }
            }
        }
        val explanation = "If the chat or draft changes before sending, the dictation stays unsent."
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(explanation, substring = true))
        composeRule.onNodeWithText(explanation, substring = true).assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name")
    }
}
