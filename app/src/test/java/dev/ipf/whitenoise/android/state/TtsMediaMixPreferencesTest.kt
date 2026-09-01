package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsMediaMixPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Prevents one opt-in persistence case from influencing another. */
    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences(TtsMediaMixPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    /** Establishes opt-in default behavior and safe volume bounds. */
    @Test
    fun modeIsOptInAndPresetValuesStayInsideTheFrameworkRange() {
        val store = TtsMediaMixPreferences(context)

        assertFalse(store.state.value.enabled)
        assertTrue(TtsMediaMixVolume.entries.all { it.frameworkVolume in 0f..1f })
    }

    /** Verifies both local media-mix settings survive recreation. */
    @Test
    fun enablementAndVolumePersistTogether() {
        val store = TtsMediaMixPreferences(context)
        store.setEnabled(true)
        store.setVolume(TtsMediaMixVolume.LOUD)

        val reloaded = TtsMediaMixPreferences(context).state.value
        assertTrue(reloaded.enabled)
        assertEquals(TtsMediaMixVolume.LOUD, reloaded.volume)
    }

    /** Treats malformed restored types as the safe disabled defaults. */
    @Test
    fun malformedRestoredValuesCannotEnableMixingOrEscapePresetBounds() {
        context
            .getSharedPreferences(TtsMediaMixPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("enabled", "true")
            .putInt("volume", 99)
            .commit()

        val restored = TtsMediaMixPreferences(context).state.value

        assertFalse(restored.enabled)
        assertEquals(TtsMediaMixVolume.MEDIUM, restored.volume)
    }
}
