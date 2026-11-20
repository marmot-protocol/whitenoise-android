package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import dev.ipf.whitenoise.android.state.TtsWarningPreferences
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
class TtsTrustWarningTest {
    private lateinit var preferences: TtsWarningPreferences

    @Before
    fun setUp() {
        RuntimeEnvironment
            .getApplication()
            .getSharedPreferences(TtsWarningPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        preferences = TtsWarningPreferences(RuntimeEnvironment.getApplication())
    }

    @Test
    fun requiresWarningForUnknownUnacknowledgedEngine() {
        assertTrue(
            requiresTtsTrustWarning(
                enginePackage = "com.google.android.tts",
                trust = EngineTrust.Unknown,
                preferences = preferences,
            ),
        )
    }

    @Test
    fun localEngineNeverRequiresWarning() {
        assertFalse(
            requiresTtsTrustWarning(
                enginePackage = "app.grapheneos.speechservices",
                trust = EngineTrust.Local,
                preferences = preferences,
            ),
        )
    }

    @Test
    fun acknowledgedUnknownEngineDoesNotRequireWarning() {
        preferences.acknowledge("com.google.android.tts")

        assertFalse(
            requiresTtsTrustWarning(
                enginePackage = "com.google.android.tts",
                trust = EngineTrust.Unknown,
                preferences = preferences,
            ),
        )
    }
}
