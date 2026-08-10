package dev.ipf.whitenoise.android.ui.group

import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TtsAutoReadOverride

@StringRes
internal fun ttsAutoReadSettingLabelRes(
    override: TtsAutoReadOverride?,
    globalDefaultEnabled: Boolean,
): Int =
    when (override) {
        null ->
            if (globalDefaultEnabled) {
                R.string.tts_auto_read_use_default_on
            } else {
                R.string.tts_auto_read_use_default_off
            }
        TtsAutoReadOverride.ON -> R.string.tts_auto_read_override_on
        TtsAutoReadOverride.OFF -> R.string.tts_auto_read_override_off
    }
