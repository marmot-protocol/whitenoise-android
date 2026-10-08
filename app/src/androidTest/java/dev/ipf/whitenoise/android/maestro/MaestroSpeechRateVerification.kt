package dev.ipf.whitenoise.android.maestro

import android.content.Context
import dev.ipf.whitenoise.android.state.TtsRatePreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Verify the actual Android preference owner and a fresh reader after the visible rate mutation. */
internal suspend fun verifyMaestroSpeechRate(
    context: Context,
    state: WhiteNoiseAppState,
    postcondition: String?,
): Boolean {
    if (postcondition?.startsWith("speech-rate-") != true) return false
    val expected =
        when (postcondition) {
            "speech-rate-custom" -> 2.8f
            "speech-rate-minimum" -> TtsRatePreferences.MIN_RATE
            "speech-rate-maximum" -> TtsRatePreferences.MAX_RATE
            "speech-rate-preset" -> 0.5f
            "speech-rate-system" -> null
            else -> error("Unknown speech-rate postcondition")
        }
    withTimeout(15_000L) {
        while (true) {
            val matches =
                withContext(Dispatchers.Main.immediate) {
                    state.ttsRatePreferences.rateOverride.value == expected &&
                        TtsRatePreferences(context).rateOverride.value == expected
                }
            if (matches) return@withTimeout
            delay(100L)
        }
    }
    return true
}
