package dev.ipf.whitenoise.android.state

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsWarningPreferencesTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences(TtsWarningPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun acknowledgementPersistsPerEnginePackage() {
        val prefs = TtsWarningPreferences(context)

        assertFalse(prefs.hasAcknowledged("com.google.android.tts"))

        prefs.acknowledge("com.google.android.tts")

        assertTrue(TtsWarningPreferences(context).hasAcknowledged("com.google.android.tts"))
    }

    @Test
    fun switchingEnginePackageRearmsWarning() {
        val prefs = TtsWarningPreferences(context)

        prefs.acknowledge("com.google.android.tts")

        assertTrue(prefs.hasAcknowledged("com.google.android.tts"))
        assertFalse(prefs.hasAcknowledged("com.github.olga_yakovleva.rhvoice.android"))
    }
}
