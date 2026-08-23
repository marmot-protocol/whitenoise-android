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
class TtsTimingPreferencesTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences(TtsTimingPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun verdictsPersistPerEngineAcrossInstances() {
        TtsTimingPreferences(context).setRangeVerdict("com.a", true)
        TtsTimingPreferences(context).setRangeVerdict("com.b", false)

        val reloaded = TtsTimingPreferences(context)
        assertEquals(true, reloaded.rangeVerdict("com.a"))
        assertEquals(false, reloaded.rangeVerdict("com.b"))
        assertNull(reloaded.rangeVerdict("com.never.seen"))
    }

    @Test
    fun paceIsPersistedPerEngine() {
        TtsTimingPreferences(context).setMsPerUnitAt1x("com.a", 21.25)

        val reloaded = TtsTimingPreferences(context)
        assertEquals(21.25, reloaded.msPerUnitAt1x("com.a")!!, 0.001)
        assertNull(reloaded.msPerUnitAt1x("com.b"))
    }

    @Test
    fun emptyEngineKeyIsNeverStoredOrRead() {
        val preferences = TtsTimingPreferences(context)
        preferences.setRangeVerdict("", true)
        preferences.setMsPerUnitAt1x("", 20.0)

        assertNull(preferences.rangeVerdict(""))
        assertNull(preferences.msPerUnitAt1x(""))
    }

    @Test
    fun nonPositivePaceIsRejected() {
        val preferences = TtsTimingPreferences(context)
        preferences.setMsPerUnitAt1x("com.a", 0.0)
        preferences.setMsPerUnitAt1x("com.a", -3.0)

        assertNull(preferences.msPerUnitAt1x("com.a"))
    }
}
