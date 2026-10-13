package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.translation.MessageTranslationForm
import dev.ipf.whitenoise.android.translation.TranslationFailure
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w400dp-h1000dp-mdpi")
class MessageTranslationFormScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val requests = mutableListOf<Pair<String, String>>()
    private var dismissed = 0
    private var engineOpened = 0

    @Test
    fun chosenLanguagesReachThePrimaryAction() {
        render()
        composeRule
            .onNodeWithText(
                "Offline Translator can write message text and translations to Android system logs. " +
                    "White Noise cannot guarantee the security of text handled by another app.",
            ).assertIsDisplayed()
        composeRule.onNodeWithText("Source language").performClick()
        composeRule.onNodeWithText("English").performClick()
        composeRule.onNodeWithText("Target language").performClick()
        composeRule.onNodeWithText("Spanish").performClick()
        composeRule.onNodeWithTag("translation.confirm").performClick()
        assertEquals(listOf("en" to "es"), requests)
        composeRule.onNodeWithText("Open engine").assertDoesNotExist()
    }

    @Test
    fun closeAndChoiceCancellationDoNotSubmit() {
        render()
        composeRule.onNodeWithText("Source language").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Detect language").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, dismissed)
        assertEquals(emptyList<Pair<String, String>>(), requests)
    }

    @Test
    fun recoveryOpensTheEngineWithoutSubmitting() {
        render(Appearance(failure = TranslationFailure.SOURCE_MODEL))
        composeRule.onNodeWithText("Open engine").performClick()
        assertEquals(1, engineOpened)
        assertEquals(emptyList<Pair<String, String>>(), requests)
    }

    @Test
    fun unavailableRequestKeepsThePrimaryActionDisabled() {
        render(Appearance(enabled = false))
        composeRule.onNodeWithTag("translation.confirm").assertIsNotEnabled()
    }

    @Test fun light() = capture("translation_form_light.png")

    @Test fun dark() = capture("translation_form_dark.png", Appearance(dark = true))

    @Test fun amoled() = capture("translation_form_amoled.png", Appearance(dark = true, amoled = true))

    @Test fun largeRtl() = capture("translation_form_large_rtl.png", Appearance(scale = 2f, rtl = true))

    @Test fun recovery() =
        capture(
            "translation_form_recovery.png",
            Appearance(failure = TranslationFailure.SOURCE_MODEL),
        )

    private data class Appearance(
        val dark: Boolean = false,
        val amoled: Boolean = false,
        val scale: Float = 1f,
        val rtl: Boolean = false,
        val failure: TranslationFailure? = null,
        val enabled: Boolean = true,
    )

    private fun capture(
        name: String,
        appearance: Appearance = Appearance(),
    ) {
        render(appearance)
        composeRule.onNodeWithTag("translation.form.fixture").captureRoboImage("src/test/snapshots/$name")
    }

    private fun render(appearance: Appearance = Appearance()) {
        composeRule.setContent {
            var from by remember { mutableStateOf("") }
            var target by remember { mutableStateOf("de") }
            CompositionLocalProvider(
                LocalLayoutDirection provides if (appearance.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(
                    darkTheme = appearance.dark,
                    amoled = appearance.amoled,
                    fontScale = appearance.scale,
                ) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.width(360.dp).heightIn(max = 650.dp).testTag("translation.form.fixture")) {
                            MessageTranslationForm(
                                from = from,
                                target = target,
                                failure = appearance.failure,
                                enabled = appearance.enabled,
                                onFromChange = { from = it },
                                onTargetChange = { target = it },
                                onTranslate = { requests.add(from to target) },
                                onDismiss = { dismissed += 1 },
                                onOpenEngine = { engineOpened += 1 },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
