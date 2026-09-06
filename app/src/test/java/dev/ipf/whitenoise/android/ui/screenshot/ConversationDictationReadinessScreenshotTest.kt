package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationFloatingControl
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession as RecognitionSession

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-mdpi")
class ConversationDictationReadinessScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Captures the bounded provider check before Android resolves an Activity. */
    @Test
    fun largeFontRtlCheckingServiceReadiness() {
        val fixture = fixture()
        capture(
            fixture = fixture,
            snapshotName = "composer_dictation_checking_service_large_font_rtl.png",
        )
    }

    /** Captures the transition from provider readiness to queued Activity launch. */
    @Test
    fun largeFontRtlOpeningServiceReadiness() {
        val fixture = fixture()
        fixture.platform.completeReadinessCheck(available = true)
        capture(
            fixture = fixture,
            snapshotName = "composer_dictation_opening_service_large_font_rtl.png",
        )
    }

    /** Captures the stable owner state after the provider Activity opens. */
    @Test
    fun largeFontRtlServiceOpenReadiness() {
        val fixture = fixture()
        fixture.platform.completeReadinessCheck(available = true)
        fixture.controller.beginProviderActivityLaunch(fixture.controller.providerActivityRequestId)
        capture(
            fixture = fixture,
            snapshotName = "composer_dictation_service_open_large_font_rtl.png",
        )
    }

    /** Builds a provider-Activity fixture whose readiness callback is controlled by the test. */
    private fun fixture(): Fixture {
        val draft = TextFieldValue("Keep")
        val platform = DeferredActivityPlatform()
        val controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, 0L) },
                writeDraft = { _, _, _, _ -> true },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
            )
        controller.requestProviderActivityStart("account", "group", draft)
        return Fixture(controller, platform)
    }

    /** Renders one readiness phase at large font in RTL and records its baseline. */
    private fun capture(
        fixture: Fixture,
        snapshotName: String,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                WhiteNoiseTheme {
                    Box(Modifier.width(268.dp)) {
                        ConversationDictationFloatingControl(
                            state = fixture.controller.state,
                            controller = fixture.controller,
                        )
                    }
                }
            }
        }

        composeRule.onRoot().captureRoboImage("src/test/snapshots/$snapshotName")
    }

    private data class Fixture(
        val controller: ConversationDictationController,
        val platform: DeferredActivityPlatform,
    )

    private class DeferredActivityPlatform : ConversationDictationPlatform {
        private lateinit var readinessCallback: (Boolean) -> Unit

        override fun hasRecordAudioPermission() = true

        override fun recognitionAvailable() = true

        override fun checkRecognitionActivity(callback: (Boolean) -> Unit): ConversationDictationTimeoutHandle {
            readinessCallback = callback
            return ConversationDictationTimeoutHandle {}
        }

        /** Completes the deferred provider check with the requested availability. */
        fun completeReadinessCheck(available: Boolean) = readinessCallback(available)

        override fun createSession(listener: ConversationDictationRecognitionListener): RecognitionSession =
            object : RecognitionSession {
                override fun start() = Unit

                override fun stop() = Unit

                override fun cancel() = Unit

                override fun destroy() = Unit
            }
    }
}
