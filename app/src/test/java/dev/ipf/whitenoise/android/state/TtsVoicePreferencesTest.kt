package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsVoicePreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Starts every persistence case from an empty engine-to-voice map. */
    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences(TtsVoicePreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    /** Demonstrates A/B engine isolation and restoration after process reload. */
    @Test
    fun voicesPersistIndependentlyPerEngineAndRestoreWhenReturning() {
        val store = TtsVoicePreferences(context)
        val first = TtsVoiceKey("engine.a", "Voice:A", "en-US")
        val second = TtsVoiceKey("engine.b", "Voice B", "fr-FR")

        store.setSelectedVoice("engine.a", first)
        store.setSelectedVoice("engine.b", second)

        val reloaded = TtsVoicePreferences(context)
        assertEquals(first, reloaded.selectedVoice("engine.a"))
        assertEquals(second, reloaded.selectedVoice("engine.b"))
    }

    /** Rejects cross-engine keys and clears only the requested engine. */
    @Test
    fun clearingOneEnginePreservesTheOtherAndMismatchedKeysAreRejected() {
        val store = TtsVoicePreferences(context)
        val first = TtsVoiceKey("engine.a", "Voice A", "en-US")
        val second = TtsVoiceKey("engine.b", "Voice B", "en-GB")
        store.setSelectedVoice("engine.a", first)
        store.setSelectedVoice("engine.b", second)

        store.setSelectedVoice("engine.a", second)
        assertEquals(first, store.selectedVoice("engine.a"))

        store.setSelectedVoice("engine.a", null)
        assertNull(TtsVoicePreferences(context).selectedVoice("engine.a"))
        assertEquals(second, TtsVoicePreferences(context).selectedVoice("engine.b"))
    }

    /** Ignores malformed restored values instead of fabricating a fuzzy key. */
    @Test
    fun malformedRestoredVoiceIsUnavailable() {
        context
            .getSharedPreferences(TtsVoicePreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("voice.engine.a", "999:short")
            .putInt("voice.engine.b", 4)
            .commit()

        val restored = TtsVoicePreferences(context)

        assertNull(restored.selectedVoice("engine.a"))
        assertNull(restored.selectedVoice("engine.b"))
    }
}
