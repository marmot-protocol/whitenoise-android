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
