package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TtsMediaMixVolume
import java.text.NumberFormat
import java.util.Locale

/**
 * Matches the voice-note speed pill's rendering so both read as one system.
 * Non-integer rates format with the active locale's decimal separator
 * (0,75× in de/fr), integers stay bare (1×).
 */
internal fun ttsRateLabel(
    rate: Float,
    locale: Locale,
): String {
    val whole = rate.toInt()
    val number =
        if (rate == whole.toFloat()) {
            whole.toString()
        } else {
            NumberFormat.getNumberInstance(locale).format(rate.toDouble())
        }
    return "$number×"
}

/** String label for one bounded speech-over-media volume preset. */
@StringRes
internal fun ttsMediaMixVolumeLabel(volume: TtsMediaMixVolume): Int =
    when (volume) {
        TtsMediaMixVolume.QUIET -> R.string.tts_media_mix_volume_quiet
        TtsMediaMixVolume.MEDIUM -> R.string.tts_media_mix_volume_medium
        TtsMediaMixVolume.LOUD -> R.string.tts_media_mix_volume_loud
    }

/** TalkBack-visible explanation for one mix volume preset. */
@StringRes
internal fun ttsMediaMixVolumeDescription(volume: TtsMediaMixVolume): Int =
    when (volume) {
        TtsMediaMixVolume.QUIET -> R.string.tts_media_mix_volume_quiet_description
        TtsMediaMixVolume.MEDIUM -> R.string.tts_media_mix_volume_medium_description
        TtsMediaMixVolume.LOUD -> R.string.tts_media_mix_volume_loud_description
    }
