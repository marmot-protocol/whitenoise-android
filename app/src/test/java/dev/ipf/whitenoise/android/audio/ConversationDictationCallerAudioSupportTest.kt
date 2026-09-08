package dev.ipf.whitenoise.android.audio

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationCallerAudioSupportTest {
    @Test
    fun anEmptyUtteranceThatReachedTheEngineMeansTheDescriptorWasRead() {
        listOf(null, SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT).forEach { code ->
            assertEquals(
                "code=$code",
                ConversationDictationCallerAudioRequirement.Supported,
                conversationDictationClassifyCallerAudioProbe(code),
            )
        }
    }

    @Test
    fun aRefusedSessionMeansTheProviderReachedForTheMicrophoneInstead() {
        listOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_CLIENT).forEach { code ->
            assertEquals(
                "code=$code",
                ConversationDictationCallerAudioRequirement.Unsupported,
                conversationDictationClassifyCallerAudioProbe(code),
            )
        }
    }

    @Test
    fun anEngineThatFailedForItsOwnReasonsSaysNothingAboutTheDescriptor() {
        // ERROR_SERVER is the trap: a provider that reads the descriptor but has no model
        // configured answers it, and calling that unsupported would send the user to the same
        // engine's own recognition UI, where it fails identically.
        listOf(
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        ).forEach { code ->
            assertEquals(
                "code=$code",
                ConversationDictationCallerAudioRequirement.Unknown,
                conversationDictationClassifyCallerAudioProbe(code),
            )
        }
    }

    @Test
    fun onlyAnEstablishedRefusalBlocksInAppCapture() {
        assertTrue(ConversationDictationCallerAudioRequirement.NotNeeded.allowsInAppCapture)
        assertTrue(ConversationDictationCallerAudioRequirement.Supported.allowsInAppCapture)
        assertTrue(ConversationDictationCallerAudioRequirement.Unknown.allowsInAppCapture)
        assertFalse(ConversationDictationCallerAudioRequirement.Unsupported.allowsInAppCapture)
    }

    @Test
    fun aRecordedAnswerBelongsToTheExactProviderBuildThatGaveIt() {
        val store = mutableMapOf<String, String>()
        val verdicts = verdicts(store)

        verdicts.record(PROVIDER, 19L, ConversationDictationCallerAudioRequirement.Unsupported)

        assertEquals(
            ConversationDictationCallerAudioRequirement.Unsupported,
            verdicts.recorded(PROVIDER, 19L),
        )
        // The upgrade that adds descriptor support must not inherit the old build's refusal.
        assertEquals(
            ConversationDictationCallerAudioRequirement.Unknown,
            verdicts.recorded(PROVIDER, 20L),
        )
        assertEquals(
            ConversationDictationCallerAudioRequirement.Unknown,
            verdicts.recorded("org.other.provider", 19L),
        )
    }

    @Test
    fun theCacheKeyCarriesBothTheProviderPackageAndItsVersion() {
        val store = mutableMapOf<String, String>()
        val verdicts = verdicts(store)

        verdicts.record(PROVIDER, 19L, ConversationDictationCallerAudioRequirement.Supported)

        assertEquals(setOf("caller_audio_support:$PROVIDER:19"), store.keys)
        assertEquals("supported", store.getValue("caller_audio_support:$PROVIDER:19"))
    }

    @Test
    fun anInconclusiveProbeRecordsNothingSoItIsAskedAgain() {
        val store = mutableMapOf<String, String>()
        val verdicts = verdicts(store)

        verdicts.record(PROVIDER, 19L, ConversationDictationCallerAudioRequirement.Unknown)
        verdicts.record(PROVIDER, 19L, ConversationDictationCallerAudioRequirement.NotNeeded)

        assertTrue(store.isEmpty())
        assertEquals(
            ConversationDictationCallerAudioRequirement.Unknown,
            verdicts.recorded(PROVIDER, 19L),
        )
    }

    @Test
    fun anUnrecognizedStoredValueIsTreatedAsUnestablished() {
        val store = mutableMapOf("caller_audio_support:$PROVIDER:19" to "maybe")

        assertEquals(
            ConversationDictationCallerAudioRequirement.Unknown,
            verdicts(store).recorded(PROVIDER, 19L),
        )
    }

    private fun verdicts(store: MutableMap<String, String>) =
        ConversationDictationCallerAudioVerdicts(
            read = { key -> store[key] },
            write = { key, value -> store[key] = value },
        )

    private companion object {
        const val PROVIDER = "org.offline.provider"
    }
}
