package dev.ipf.whitenoise.android.ui.settings

import android.speech.tts.Voice
import dev.ipf.whitenoise.android.audio.tts.TtsEngineResolver
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Distinguishes an absent optional voice catalogue from a known catalogue with unavailable voices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TextToSpeechPresentationTest {
    /** A stale saved choice must retain its Voice entry so Automatic can clear the override. */
    @Test
    fun savedVoiceWithoutCatalogKeepsTheRecoveryPicker() {
        val resolution =
            TtsEngineResolver.resolveVoiceSelection(
                enginePackage = ENGINE,
                locale = Locale.US,
                voices = emptyList(),
                requestedKey = TtsVoiceKey(ENGINE, "Removed voice", "en-US"),
            )

        assertTrue(showTtsVoicePicker(resolution))
        assertFalse(hasUnavailableTtsVoiceCatalog(resolution))
        assertFalse(showTtsVoicePicker(resolution.copy(requestedKey = null)))
    }

    /** Engines may synthesize successfully without exposing the optional Voice catalogue. */
    @Test
    fun absentCatalogDoesNotClaimSpeechIsUnavailable() {
        val resolution =
            TtsEngineResolver.resolveVoiceSelection(ENGINE, Locale.US, emptyList(), requestedKey = null)

        assertFalse(hasUnavailableTtsVoiceCatalog(resolution))
        assertFalse(showTtsVoicePicker(resolution))
    }

    /** A real catalogue containing only a network voice still explains why offline speech is unavailable. */
    @Test
    fun networkOnlyCatalogRetainsTheUnavailableWarningAndPicker() {
        val networkVoice = Voice("Cloud", Locale.US, 300, 100, true, emptySet())
        val resolution =
            TtsEngineResolver.resolveVoiceSelection(ENGINE, Locale.US, listOf(networkVoice), requestedKey = null)

        assertTrue(hasUnavailableTtsVoiceCatalog(resolution))
        assertTrue(showTtsVoicePicker(resolution))
    }

    /** A successfully applied offline voice suppresses the failure notice while keeping voice selection. */
    @Test
    fun effectiveOfflineVoiceDoesNotShowAnUnavailableWarning() {
        val offlineVoice = Voice("Offline", Locale.US, 300, 100, false, emptySet())
        val key = TtsVoiceKey(ENGINE, "Offline", "en-US")
        val resolution =
            TtsEngineResolver
                .resolveVoiceSelection(ENGINE, Locale.US, listOf(offlineVoice), requestedKey = key)
                .copy(effectiveKey = key)

        assertFalse(hasUnavailableTtsVoiceCatalog(resolution))
        assertTrue(showTtsVoicePicker(resolution))
    }

    private companion object {
        const val ENGINE = "engine.test"
    }
}
