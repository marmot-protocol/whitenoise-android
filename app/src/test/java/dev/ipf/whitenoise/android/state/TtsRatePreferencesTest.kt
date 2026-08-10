package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsRatePreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences("whitenoise.tts_rate", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun presetRatesIncludeHighRatesAfterTwoX() {
        assertEquals(
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f),
            TtsRatePreferences.PRESET_RATES,
        )
    }

    @Test
    fun highRateOverridesPersistAcrossReload() {
        val store = TtsRatePreferences(context)

        store.setRateOverride(2.5f)
        assertEquals(2.5f, store.rateOverride.value)
        assertEquals(2.5f, TtsRatePreferences(context).rateOverride.value)

        store.setRateOverride(3.0f)
        assertEquals(3.0f, store.rateOverride.value)
        assertEquals(3.0f, TtsRatePreferences(context).rateOverride.value)
    }

    @Test
    fun customRateBoundariesNormalizeAndPersistAcrossReload() {
        val store = TtsRatePreferences(context)

        store.setRateOverride(0.1f)
        assertEquals(0.1f, TtsRatePreferences(context).rateOverride.value)

        store.setRateOverride(1.26f)
        assertEquals(1.3f, store.rateOverride.value)
        assertEquals(1.3f, TtsRatePreferences(context).rateOverride.value)

        store.setRateOverride(10.0f)
        assertEquals(10.0f, TtsRatePreferences(context).rateOverride.value)
    }

    @Test
    fun invalidRatesDoNotReplaceTheCurrentOverride() {
        val store = TtsRatePreferences(context)
        store.setRateOverride(1.5f)

        store.setRateOverride(0.09f)
        store.setRateOverride(10.01f)
        store.setRateOverride(Float.NaN)

        assertEquals(1.5f, store.rateOverride.value)
        assertEquals(1.5f, TtsRatePreferences(context).rateOverride.value)
    }

    @Test
    fun storedCustomRatesNormalizeInsteadOfSnappingToAPreset() {
        context
            .getSharedPreferences("whitenoise.tts_rate", Context.MODE_PRIVATE)
            .edit()
            .putFloat("rateOverride", 1.64f)
            .commit()

        val reloaded = TtsRatePreferences(context)
        assertEquals(1.6f, reloaded.rateOverride.value)

        reloaded.setRateOverride(null)
        assertNull(reloaded.rateOverride.value)
    }
}
