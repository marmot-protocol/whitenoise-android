package dev.ipf.whitenoise.android.state

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsEnginePreferencesTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences(TtsEnginePreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun selectedEnginePersistsAcrossInstances() {
        val preferences = TtsEnginePreferences(context)

        assertNull(preferences.selectedEngine())

        preferences.setSelectedEngine("  app.grapheneos.speechservices  ")

        assertEquals(
            "app.grapheneos.speechservices",
            TtsEnginePreferences(context).selectedEngine(),
        )
    }

    @Test
    fun nullSelectionRestoresSystemDefault() {
        val preferences = TtsEnginePreferences(context)
        preferences.setSelectedEngine("com.google.android.tts")

        preferences.setSelectedEngine(null)

        assertNull(TtsEnginePreferences(context).selectedEngine())
    }
}
