package dev.ipf.whitenoise.android.ui.group

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TtsAutoReadOverride
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsAutoReadPresentationTest {
    @Test
    fun settingLabelResReflectsOverrideAndInheritedGlobal() {
        assertEquals(
            R.string.tts_auto_read_use_default_off,
            ttsAutoReadSettingLabelRes(null, globalDefaultEnabled = false),
        )
        assertEquals(
            R.string.tts_auto_read_use_default_on,
            ttsAutoReadSettingLabelRes(null, globalDefaultEnabled = true),
        )
        assertEquals(
            R.string.tts_auto_read_override_on,
            ttsAutoReadSettingLabelRes(TtsAutoReadOverride.ON, globalDefaultEnabled = false),
        )
        assertEquals(
            R.string.tts_auto_read_override_off,
            ttsAutoReadSettingLabelRes(TtsAutoReadOverride.OFF, globalDefaultEnabled = true),
        )
    }
}
