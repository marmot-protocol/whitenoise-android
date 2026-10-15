package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.translation.MessageTranslationForm
import dev.ipf.whitenoise.android.translation.OFFLINE_TRANSLATOR_PACKAGE
import dev.ipf.whitenoise.android.translation.TranslationFailure
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    /** Chosen source and target reach the primary action, and healthy requests hide engine management. */
    @Test
    fun chosenLanguagesReachThePrimaryAction() {
        render()
        composeRule
            .onNodeWithText(
                "Offline Translator can write message text and translations to Android system logs. " +
                    "White Noise cannot guarantee the security of text handled by another app.",
            ).assertIsDisplayed()
        choose("Source language", "English")
        choose("Target language", "Spanish")
        composeRule.onNodeWithTag("translation.confirm").performClick()
        assertEquals(listOf("en" to "es"), requests)
        composeRule.onNodeWithTag("translation.engine").assertDoesNotExist()
    }

    /** Slovak is selectable as both source and target and reaches the action as the engine's `sk` code. */
    @Test
    fun slovakIsSelectableAsSourceAndTarget() {
        render()
        choose("Source language", "Slovak")
        choose("Target language", "English")
        composeRule.onNodeWithTag("translation.confirm").performClick()
        choose("Source language", "Detect language")
        choose("Target language", "Slovak")
        composeRule.onNodeWithTag("translation.confirm").performClick()
        assertEquals(listOf("sk" to "en", "" to "sk"), requests)
    }

    /** Sources include into-English-only languages that the target list leaves out. */
    @Test
    fun sourceOnlyLanguagesAppearOnlyInTheSourceList() {
        render()
        composeRule.onNodeWithText("Source language").performClick()
        composeRule.onNodeWithText("Belarusian").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Target language").performClick()
        composeRule.onNodeWithText("Belarusian").assertDoesNotExist()
        composeRule.onNodeWithText("Chinese (Traditional)").performScrollTo().performClick()
        composeRule.onNodeWithTag("translation.confirm").performClick()
        assertEquals(listOf("" to "zh_hant"), requests)
    }

    /** Closing the sheet or cancelling a choice sends nothing. */
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

    /** An installed engine opens for its language packs without submitting the message. */
    @Test
    fun recoveryOpensTheEngineWithoutSubmitting() {
        render(Appearance(failure = TranslationFailure.SOURCE_MODEL, engineInstalled = true))
        composeRule.onNodeWithText("Install from GitHub").assertDoesNotExist()
        composeRule.onNodeWithText("Open engine").performClick()
        assertEquals(1, engineOpened)
        assertEquals(emptyList<Pair<String, String>>(), requests)
        composeRule.onNodeWithText(OPEN_FAILED).assertDoesNotExist()
    }

    /** A missing engine names the reviewed GitHub route, explains why Zapstore is not offered, and submits nothing. */
    @Test
    fun missingEngineNamesTheCompatibleInstallRoute() {
        render(Appearance(failure = TranslationFailure.MISSING_ENGINE))
        composeRule.onNodeWithText("Install from GitHub").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Zapstore currently offers Offline Translator 0.8.2, which White Noise cannot use, " +
                    "so this opens version 0.8.5 from the publisher on GitHub.",
            ).assertIsDisplayed()
        composeRule.onNodeWithTag("translation.engine").performClick()
        assertEquals(1, engineOpened)
        assertEquals(emptyList<Pair<String, String>>(), requests)
    }

    /** With no app able to open the route, the sheet says so and keeps its recovery controls. */
    @Test
    fun failedEngineHandOffStaysVisible() {
        render(Appearance(failure = TranslationFailure.MISSING_ENGINE, engineOpens = false))
        composeRule.onNodeWithTag("translation.engine").performClick()
        composeRule.onNodeWithText(OPEN_FAILED).assertIsDisplayed()
        composeRule.onNodeWithTag("translation.engine").assertIsDisplayed()
        assertEquals(emptyList<Pair<String, String>>(), requests)
    }

    /** An engine too old for the chosen language gets update guidance rather than a generic failure. */
    @Test
    fun olderEngineCatalogAsksForAnEngineUpdate() {
        render(Appearance(failure = TranslationFailure.ENGINE_UPDATE_REQUIRED))
        composeRule
            .onNodeWithText("This language needs a newer Offline Translator. Update the engine, then retry.")
            .assertIsDisplayed()
    }

    /** An unusable target keeps the primary action disabled. */
    @Test
    fun unavailableRequestKeepsThePrimaryActionDisabled() {
        render(Appearance(enabled = false))
        composeRule.onNodeWithTag("translation.confirm").assertIsNotEnabled()
    }

    /** At double font size in RTL the primary action stays visible below the scrolling warning. */
    @Test
    fun largeTextKeepsThePrimaryActionVisibleBelowTheScrollingWarning() {
        render(Appearance(scale = 2f, rtl = true))
        composeRule.onNodeWithTag("translation.confirm").assertIsDisplayed().performClick()
        assertEquals(listOf("" to "de"), requests)
    }

    @Test fun light() = capture("translation_form_light.png")

    @Test fun dark() = capture("translation_form_dark.png", Appearance(dark = true))

    @Test fun amoled() = capture("translation_form_amoled.png", Appearance(dark = true, amoled = true))

    @Test fun largeRtl() = capture("translation_form_large_rtl.png", Appearance(scale = 2f, rtl = true))

    /** With the engine installed, recovery shows the failure and the unchanged Open engine control. */
    @Test fun recovery() =
        capture(
            "translation_form_recovery.png",
            Appearance(failure = TranslationFailure.SOURCE_MODEL, engineInstalled = true),
        )

    /** Without an engine, recovery names the reviewed install route and why Zapstore is not offered. */
    @Test fun missingEngine() =
        capture(
            "translation_form_missing_engine.png",
            Appearance(failure = TranslationFailure.MISSING_ENGINE),
        )

    /** Slovak selected as the source, rendered with its catalog label. */
    @Test fun slovak() = capture("translation_form_slovak_light.png", Appearance(from = "sk", target = "en"))

    /** Long German labels for Traditional Chinese and Norwegian Bokmål at double size still fit their rows. */
    @Config(qualifiers = "de-w400dp-h1000dp-mdpi")
    @Test
    fun longLocalizedLabels() =
        capture(
            "translation_form_long_labels_de_large.png",
            Appearance(scale = 2f, from = "zh_hant", target = "nb"),
        )

    private data class Appearance(
        val dark: Boolean = false,
        val amoled: Boolean = false,
        val scale: Float = 1f,
        val rtl: Boolean = false,
        val failure: TranslationFailure? = null,
        val enabled: Boolean = true,
        val from: String = "",
        val target: String = "de",
        val engineOpens: Boolean = true,
        val engineInstalled: Boolean = false,
    )

    /** Opens a language row and picks [label], scrolling the catalog-length list as needed. */
    private fun choose(
        row: String,
        label: String,
    ) {
        composeRule.onNodeWithText(row).performClick()
        composeRule.onNodeWithText(label).performScrollTo().performClick()
    }

    private fun capture(
        name: String,
        appearance: Appearance = Appearance(),
    ) {
        render(appearance)
        composeRule.onNodeWithTag("translation.form.fixture").captureRoboImage("src/test/snapshots/$name")
    }

    /** Renders the form at a fixed width with the appearance's languages and engine hand-off outcome. */
    private fun render(appearance: Appearance = Appearance()) {
        if (appearance.engineInstalled) installEngineLauncher()
        composeRule.setContent {
            var from by remember { mutableStateOf(appearance.from) }
            var target by remember { mutableStateOf(appearance.target) }
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
                                onOpenEngine = {
                                    engineOpened += 1
                                    appearance.engineOpens
                                },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Makes an Offline Translator launcher visible, as an installed engine of any version would be. */
    private fun installEngineLauncher() {
        val packageManager = ApplicationProvider.getApplicationContext<Context>().packageManager
        val application = ApplicationInfo().apply { packageName = OFFLINE_TRANSLATOR_PACKAGE }
        shadowOf(packageManager).installPackage(
            PackageInfo().apply {
                packageName = OFFLINE_TRANSLATOR_PACKAGE
                applicationInfo = application
            },
        )
        shadowOf(packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(OFFLINE_TRANSLATOR_PACKAGE),
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = OFFLINE_TRANSLATOR_PACKAGE
                        name = "$OFFLINE_TRANSLATOR_PACKAGE.MainActivity"
                        exported = true
                        applicationInfo = application
                    }
            },
        )
    }

    private companion object {
        const val OPEN_FAILED =
            "No app could open Offline Translator or its download page. Install a web browser, then retry."
    }
}
