package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.translation.ComposerTranslationBar
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
@Config(sdk = [36], qualifiers = "en-w400dp-h800dp-mdpi")
class ComposerTranslationBarScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private var chosen = 0
    private var retried = 0

    /** The language chip opens the picker; the translated line is shown as text, not as an editor. */
    @Test
    fun languageChipOpensThePicker() {
        render()
        composeRule.onNodeWithText(TRANSLATED).assertIsDisplayed()
        composeRule.onNodeWithTag("composer.translation.language").performClick()
        assertEquals(1, chosen)
        assertEquals(0, retried)
    }

    /** A failed request retries from the line itself and offers engine recovery. */
    @Test
    fun failureRetriesAndOffersTheEngine() {
        render(Appearance(outgoing = null, failure = TranslationFailure.MISSING_ENGINE))
        composeRule.onNodeWithTag("composer.translation.text").performClick()
        assertEquals(1, retried)
        composeRule.onNodeWithTag("translation.engine").assertIsDisplayed()
    }

    @Test fun light() = capture("composer_translation_light.png")

    @Test fun dark() = capture("composer_translation_dark.png", Appearance(dark = true))

    @Test fun amoled() = capture("composer_translation_amoled.png", Appearance(dark = true, amoled = true))

    @Test fun largeRtl() = capture("composer_translation_large_rtl.png", Appearance(scale = 2f, rtl = true))

    @Test fun pending() = capture("composer_translation_pending.png", Appearance(outgoing = null, pending = true))

    @Test fun empty() = capture("composer_translation_empty.png", Appearance(outgoing = null))

    @Test fun missingEngine() =
        capture(
            "composer_translation_missing_engine.png",
            Appearance(outgoing = null, failure = TranslationFailure.MISSING_ENGINE),
        )

    @Test fun detectionFailed() =
        capture(
            "composer_translation_detection_failed.png",
            Appearance(outgoing = null, failure = TranslationFailure.DETECTION),
        )

    private data class Appearance(
        val dark: Boolean = false,
        val amoled: Boolean = false,
        val scale: Float = 1f,
        val rtl: Boolean = false,
        val outgoing: String? = TRANSLATED,
        val pending: Boolean = false,
        val failure: TranslationFailure? = null,
    )

    /** Renders [appearance] and records it as the baseline [name]. */
    private fun capture(
        name: String,
        appearance: Appearance = Appearance(),
    ) {
        render(appearance)
        composeRule.onNodeWithTag("composer.translation.fixture").captureRoboImage("src/test/snapshots/$name")
    }

    /** Shows the translation bar fixture in [appearance]. */
    private fun render(appearance: Appearance = Appearance()) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (appearance.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(
                    darkTheme = appearance.dark,
                    amoled = appearance.amoled,
                    fontScale = appearance.scale,
                ) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Box(Modifier.width(360.dp).testTag("composer.translation.fixture")) {
                            ComposerTranslationBar(
                                targetLabel = "German",
                                outgoing = appearance.outgoing,
                                pending = appearance.pending,
                                failure = appearance.failure,
                                onChooseLanguage = { chosen += 1 },
                                onRetry = { retried += 1 },
                                onOpenEngine = { true },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val TRANSLATED = "Ich bin in zehn Minuten da, bring bitte den Schlüssel mit."
    }
}
