package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsRatePreferencesTest {
    @Test
    fun presetRatesIncludeHighRatesAfterTwoX() {
        assertEquals(
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f),
            TtsRatePreferences.PRESET_RATES,
        )
    }

    @Test
    fun highRateOverridesPersistAcrossReload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TtsRatePreferences(context)

        store.setRateOverride(2.5f)
        assertEquals(2.5f, store.rateOverride.value)
        assertEquals(2.5f, TtsRatePreferences(context).rateOverride.value)

        store.setRateOverride(3.0f)
        assertEquals(3.0f, store.rateOverride.value)
        assertEquals(3.0f, TtsRatePreferences(context).rateOverride.value)
    }

    @Test
    fun overridesSnapToThePresetGridOnWriteAndRead() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TtsRatePreferences(context)

        store.setRateOverride(1.1f)
        assertEquals(1.0f, store.rateOverride.value)

        // An off-grid float already on disk (migration, manual edit) snaps on
        // read too, so the settings rows always have a selected entry.
        context
            .getSharedPreferences("whitenoise.tts_rate", Context.MODE_PRIVATE)
            .edit()
            .putFloat("rateOverride", 1.6f)
            .commit()
        val reloaded = TtsRatePreferences(context)
        assertEquals(1.5f, reloaded.rateOverride.value)

        reloaded.setRateOverride(null)
        assertNull(reloaded.rateOverride.value)
    }
}
