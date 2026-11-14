package dev.ipf.whitenoise.android.ui.screenshot

import android.os.Looper
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.translation.ComposerTranslationState
import dev.ipf.whitenoise.android.translation.TranslationEngine
import dev.ipf.whitenoise.android.translation.TranslationEngineIdentity
import dev.ipf.whitenoise.android.translation.TranslationLanguageChoices
import dev.ipf.whitenoise.android.translation.TranslationLanguagePickerSheet
import dev.ipf.whitenoise.android.translation.TranslationTrust
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pixel baselines for the composer's translation Send slot and the shared language picker sheet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ComposerTranslationSendScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Stops the held translation request. */
    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test fun pendingLight() = composer("composer_translation_send_pending_light.png", ready = false)

    @Test fun pendingDark() = composer("composer_translation_send_pending_dark.png", ready = false, dark = true)

    @Test
    fun pendingAmoled() {
        composer("composer_translation_send_pending_amoled.png", ready = false, dark = true, amoled = true)
    }

    @Test
    fun pendingAmoledLargeRtl() =
        composer(
            "composer_translation_send_pending_amoled_large_rtl.png",
            ready = false,
            dark = true,
            amoled = true,
            rtl = true,
        )

    @Test fun readyLight() = composer("composer_translation_send_ready_light.png", ready = true)

    @Test fun readyDark() = composer("composer_translation_send_ready_dark.png", ready = true, dark = true)

    @Test
    fun readyAmoled() = composer("composer_translation_send_ready_amoled.png", ready = true, dark = true, amoled = true)

    @Test fun pickerLight() = picker("translation_language_picker_light.png")

    @Test fun pickerDark() = picker("translation_language_picker_dark.png", dark = true)

    @Test fun pickerAmoled() = picker("translation_language_picker_amoled.png", dark = true, amoled = true)

    @Test
    fun pickerSearchAmoled() {
        picker("translation_language_picker_search_amoled.png", dark = true, amoled = true, query = "ger")
    }

    @Test
    fun pickerAmoledLargeRtl() {
        picker("translation_language_picker_amoled_large_rtl.png", dark = true, amoled = true, rtl = true)
    }

    /** Renders the composer in translate mode, either waiting on the engine or with the translation ready. */
    private fun composer(
        name: String,
        ready: Boolean,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        val gate = CompletableDeferred<Unit>()
        if (ready) gate.complete(Unit)
        val translation =
            ComposerTranslationState(
                scope = scope,
                engine = { engine(gate) },
                ownerCurrent = { true },
                initialTarget = "de",
                settleMillis = 0L,
            ).also { it.toggle() }
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            Appearance(dark, amoled, rtl) {
                Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, _ -> },
                        initialDraft = TextFieldValue("See you in ten minutes"),
                        composerTranslation = translation,
                    )
                }
            }
        }
        repeat(FRAMES) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.mainClock.advanceTimeByFrame()
        }
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$name")
    }

    /** Renders the shared picker sheet, optionally with [query] typed into its search field. */
    private fun picker(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        query: String = "",
        rtl: Boolean = false,
    ) {
        val names =
            linkedMapOf(
                "ar" to "Arabic",
                "zh" to "Chinese",
                "nl" to "Dutch",
                "en" to "English",
                "fr" to "French",
                "de" to "German",
                "it" to "Italian",
                "pt" to "Portuguese",
                "es" to "Spanish",
            )
        composeRule.setContent {
            Appearance(dark, amoled, rtl) {
                TranslationLanguagePickerSheet(
                    title = "Target language",
                    choices = TranslationLanguageChoices(names.keys.toList()) { names.getValue(it) },
                    selected = "de",
                    onDismiss = {},
                    onSelect = {},
                )
            }
        }
        if (query.isNotEmpty()) composeRule.onNodeWithTag("translation.language.search").performTextInput(query)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sheet.surface").captureRoboImage("src/test/snapshots/$name")
    }

    /** Applies the theme, and for [rtl] a right-to-left layout at twice the font size. */
    @Composable
    @Suppress("FunctionNaming")
    private fun Appearance(
        dark: Boolean,
        amoled: Boolean,
        rtl: Boolean,
        content: @Composable () -> Unit,
    ) {
        WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                content = content,
            )
        }
    }

    /** A local engine whose result waits on [gate]. */
    private fun engine(gate: CompletableDeferred<Unit>) =
        object : TranslationEngine {
            override val identity = TranslationEngineIdentity("local", "verified", 1)
            override val trust = TranslationTrust.LOCAL

            /** Waits on the gate, then returns the target-prefixed text. */
            override suspend fun translate(
                text: String,
                source: String,
                target: String,
            ): String {
                gate.await()
                return "$target:$text"
            }
        }

    private companion object {
        const val TAG = "composer-translation-send"
        const val FRAMES = 30
    }
}
