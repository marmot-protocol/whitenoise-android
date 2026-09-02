package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class AndroidTtsSpeechEngineTest {
    /** Verifies the framework listener forwards every callback with its payload intact. */
    @Test
    fun progressListenerForwardsRangesStopsCompletionAndDetailedErrors() {
        val calls = mutableListOf<String>()
        val listener =
            androidTtsProgressListener(
                onStart = { calls += "start:$it" },
                onDone = { calls += "done:$it" },
                onError = { id, code -> calls += "error:$id:$code" },
                onRangeStart = { id, start, end, frame -> calls += "range:$id:$start:$end:$frame" },
                onStop = { id, interrupted -> calls += "stop:$id:$interrupted" },
            )

        listener.onStart("u1")
        listener.onRangeStart("u1", 2, 7, 11)
        listener.onStop("u1", true)
        listener.onDone("u1")
        listener.onError("u1", TextToSpeech.ERROR_NETWORK)

        assertEquals(
            listOf("start:u1", "range:u1:2:7:11", "stop:u1:true", "done:u1", "error:u1:${TextToSpeech.ERROR_NETWORK}"),
            calls,
        )
    }

    /** Preserves completion when an engine omits optional range callbacks. */
    @Test
    fun completionWithoutARangeStillForwardsDone() {
        val calls = mutableListOf<String>()
        val listener = listener(calls)

        listener.onDone("u1")

        assertEquals(listOf("done:u1"), calls)
    }

    /** Keeps an interrupted stop distinct from successful completion. */
    @Test
    fun stopWithoutDoneDoesNotSynthesizeCompletion() {
        val calls = mutableListOf<String>()
        val listener = listener(calls)

        listener.onStop("u1", true)

        assertEquals(listOf("stop:u1:true"), calls)
    }

    /** Carries the framework frame index through the adapter callback. */
    @Test
    fun adapterForwardsRangeFrame() {
        val tts = CapturingTextToSpeech(RuntimeEnvironment.getApplication())
        val engine = AndroidTtsSpeechEngine(tts)
        var received: String? = null
        engine.setCallbacks(
            onStart = {},
            onDone = {},
            onError = { _, _ -> },
            onRangeStart = { id, start, end, frame -> received = "$id:$start:$end:$frame" },
            onStop = { _, _ -> },
        )

        tts.listener?.onRangeStart("whitenoise.tts.1.0", 4, 9, 12)

        assertEquals("whitenoise.tts.1.0:4:9:12", received)
    }

    /** Removes the framework listener when controller ownership ends. */
    @Test
    fun clearCallbacksRemovesTheUtteranceListener() {
        val tts = CapturingTextToSpeech(RuntimeEnvironment.getApplication())
        val engine = AndroidTtsSpeechEngine(tts)
        engine.setCallbacks(
            onStart = {},
            onDone = {},
            onError = { _, _ -> },
            onRangeStart = { _, _, _, _ -> },
            onStop = { _, _ -> },
        )

        engine.clearCallbacks()

        assertNull(tts.listener)
    }

    /** Confirms only media-mix speech carries a clamped Android volume bundle. */
    @Test
    fun mediaMixSpeechUsesBoundedFrameworkVolumeWhileOrdinarySpeechUsesNoBundle() {
        val tts = CapturingTextToSpeech(RuntimeEnvironment.getApplication())
        val engine = AndroidTtsSpeechEngine(tts)

        engine.speak("ordinary", "u1")
        assertNull(tts.lastParams)

        engine.speak("mixed", "u2", 3f)
        assertEquals(1f, tts.lastParams?.getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME))
        assertEquals("u2", tts.lastUtteranceId)
    }

    /** Applies the exact saved installed voice for the current engine. */
    @Test
    fun languageSelectionAppliesTheExactSavedOfflineVoice() {
        val fallback = voice("Fallback", Locale.US, 300)
        val selected = voice("Selected", Locale.UK, 200)
        val tts = VoiceSelectingTextToSpeech(fallback, setOf(fallback, selected))
        var resolution: TtsVoiceResolution? = null
        val engine =
            AndroidTtsSpeechEngine(
                textToSpeech = tts,
                enginePackage = "engine.a",
                selectedVoice = { TtsVoiceKey("engine.a", "Selected", "en-GB") },
                onVoiceResolved = { resolution = it },
            )

        assertEquals(TextToSpeech.LANG_AVAILABLE, engine.setLanguage(Locale.US))
        assertSame(selected, tts.voice)
        assertTrue(resolution?.isUsingRequestedVoice == true)
    }

    /** Falls back to an installed offline voice when the saved key disappears. */
    @Test
    fun missingSavedVoiceFallsBackDeterministicallyWithoutUsingNetworkSpeech() {
        val network = voice("Network", Locale.US, 500, networkRequired = true)
        val offline = voice("Offline", Locale.UK, 300)
        val tts = VoiceSelectingTextToSpeech(network, setOf(network, offline))
        val engine =
            AndroidTtsSpeechEngine(
                textToSpeech = tts,
                enginePackage = "engine.a",
                selectedVoice = { TtsVoiceKey("engine.a", "Gone", "en-US") },
            )

        assertEquals(TextToSpeech.LANG_AVAILABLE, engine.setLanguage(Locale.US))
        assertSame(offline, tts.voice)
    }

    /** Refuses a language whose engine exposes only network speech. */
    @Test
    fun networkOnlyLanguageIsRejectedBeforeSpeechSubmission() {
        val network = voice("Network", Locale.US, 500, networkRequired = true)
        val tts = VoiceSelectingTextToSpeech(network, setOf(network))
        val engine = AndroidTtsSpeechEngine(tts, enginePackage = "engine.a")

        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, engine.setLanguage(Locale.US))
    }

    /** Keeps an installed ISO-639-2 voice usable for the equivalent ISO-639-1 utterance locale. */
    @Test
    fun equivalentThreeLetterLanguageVoiceRemainsUsable() {
        val english = voice("English ISO3", Locale.forLanguageTag("eng"), 300)
        val tts = VoiceSelectingTextToSpeech(english, setOf(english))
        val engine = AndroidTtsSpeechEngine(tts, enginePackage = "engine.a")

        assertEquals(TextToSpeech.LANG_AVAILABLE, engine.setLanguage(Locale.US))
        assertSame(english, tts.voice)
    }

    /** Preserves an accepted framework language when an engine has no optional voice catalog. */
    @Test
    fun emptyVoiceCatalogKeepsTheFrameworkLanguageStatus() {
        val tts = EmptyCatalogTextToSpeech()
        var resolution: TtsVoiceResolution? = null
        val engine =
            AndroidTtsSpeechEngine(
                textToSpeech = tts,
                enginePackage = "engine.a",
                onVoiceResolved = { resolution = it },
            )

        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, engine.setLanguage(Locale.US))
        assertEquals(Locale.US.toLanguageTag(), resolution?.localeTag)
        assertTrue(resolution?.options?.isEmpty() == true)
        assertNull(resolution?.effectiveKey)
    }

    /** Builds a listener that records each lifecycle callback in order. */
    private fun listener(calls: MutableList<String>) =
        androidTtsProgressListener(
            onStart = { calls += "start:$it" },
            onDone = { calls += "done:$it" },
            onError = { id, code -> calls += "error:$id:$code" },
            onRangeStart = { id, start, end, frame -> calls += "range:$id:$start:$end:$frame" },
            onStop = { id, interrupted -> calls += "stop:$id:$interrupted" },
        )

    /** Creates framework voices with the exact availability needed by each case. */
    private fun voice(
        name: String,
        locale: Locale,
        quality: Int,
        networkRequired: Boolean = false,
    ) = Voice(name, locale, quality, 100, networkRequired, emptySet())

    private class VoiceSelectingTextToSpeech(
        initialVoice: Voice,
        private val availableVoices: Set<Voice>,
    ) : TextToSpeech(RuntimeEnvironment.getApplication(), {}) {
        private var activeVoice = initialVoice

        /** Models an engine that accepts the requested locale. */
        override fun setLanguage(locale: Locale?): Int = TextToSpeech.LANG_AVAILABLE

        /** Returns installed and unavailable voices exactly as configured. */
        override fun getVoices(): MutableSet<Voice> = availableVoices.toMutableSet()

        /** Exposes the voice last accepted by the fake engine. */
        override fun getVoice(): Voice = activeVoice

        /** Accepts only catalog voices so fallback failures remain observable. */
        override fun setVoice(voice: Voice?): Int {
            if (voice == null || voice !in availableVoices) return TextToSpeech.ERROR
            activeVoice = voice
            return TextToSpeech.SUCCESS
        }
    }

    /** Models a usable engine that relies on its default voice and exposes no catalog. */
    private class EmptyCatalogTextToSpeech : TextToSpeech(RuntimeEnvironment.getApplication(), {}) {
        override fun setLanguage(locale: Locale?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE

        override fun getVoices(): MutableSet<Voice> = mutableSetOf()
    }

    private class CapturingTextToSpeech(
        context: Context,
    ) : TextToSpeech(context, {}) {
        var listener: UtteranceProgressListener? = null
            private set
        var lastParams: Bundle? = null
            private set
        var lastUtteranceId: String? = null
            private set

        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?): Int {
            this.listener = listener
            return super.setOnUtteranceProgressListener(listener)
        }

        /** Captures the framework bundle and utterance identity without synthesizing audio. */
        override fun speak(
            text: CharSequence?,
            queueMode: Int,
            params: Bundle?,
            utteranceId: String?,
        ): Int {
            lastParams = params
            lastUtteranceId = utteranceId
            return TextToSpeech.SUCCESS
        }
    }
}
