package dev.ipf.whitenoise.android.audio

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Selecting a capable service must not replace continuous capture with its microphone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationCallerAudioRoutingCoverageTest {
    private val integrated =
        ConversationDictationCallerAudioProvider(
            ComponentName(OFFLINE_SPEECH_TO_TEXT_PACKAGE, "dev.notune.transcribe.VoiceRecognitionService"),
            61L,
        )
    private val other =
        ConversationDictationCallerAudioProvider(ComponentName("org.example.speech", "org.example.speech.Service"), 1L)

    @Test
    fun selectedIntegratedProviderKeepsContinuousAudioWithoutAProbe() {
        listOf(false, true).forEach { canRecord ->
            assertEquals(
                ConversationDictationCallerAudioRequirement.Supported,
                requirement(integrated, canRecord),
            )
        }
    }

    @Test
    fun selectedProviderWithAnExactBuildVerdictAlsoKeepsContinuousAudio() {
        assertEquals(
            ConversationDictationCallerAudioRequirement.Supported,
            requirement(other, canRecord = true, recorded = ConversationDictationCallerAudioRequirement.Supported),
        )
        assertEquals(
            ConversationDictationCallerAudioRequirement.NotNeeded,
            requirement(other.copy(versionCode = 2L), true),
        )
    }

    @Test
    fun selectedUnknownAndUnsupportedProvidersKeepTheirOwnMicrophone() {
        listOf(
            ConversationDictationCallerAudioRequirement.Unknown,
            ConversationDictationCallerAudioRequirement.Unsupported,
        ).forEach {
            assertEquals(ConversationDictationCallerAudioRequirement.NotNeeded, requirement(other, true, it))
        }
        // The service contract is stronger evidence than an old failed descriptor probe.
        assertEquals(
            ConversationDictationCallerAudioRequirement.Supported,
            requirement(integrated, true, ConversationDictationCallerAudioRequirement.Unsupported),
        )
    }

    @Test
    fun compatibilityContractDoesNotApplyToOlderOrDifferentServices() {
        listOf(
            integrated.copy(versionCode = 60L),
            integrated.copy(component = ComponentName(OFFLINE_SPEECH_TO_TEXT_PACKAGE, "org.example.OtherService")),
            other,
        ).forEach {
            assertEquals(ConversationDictationCallerAudioRequirement.NotNeeded, requirement(it, true))
            assertEquals(ConversationDictationCallerAudioRequirement.Unknown, requirement(it, false))
        }
    }

    @Test
    fun oldAndroidRetainsNativeCaptureAndCannotSupplyAudio() {
        assertEquals(
            ConversationDictationCallerAudioRequirement.NotNeeded,
            requirement(integrated, true, supported = false),
        )
        assertEquals(
            ConversationDictationCallerAudioRequirement.Unsupported,
            requirement(integrated, false, supported = false),
        )
        assertEquals(ConversationDictationCallerAudioRequirement.Unknown, requirement(null, true))
    }

    @Test
    fun decodingDoesNotResumeMusicOrLoseTheNextCapturedPhrase() {
        ConversationDictationControllerTest().continuousCallerAudioKeepsSpeechCapturedDuringProviderDecoding()
    }

    @Test
    fun captureRoutingUsesEstablishedSupportEvenWhenTheProviderCanRecord() {
        assertTrue(conversationDictationUsesCallerAudio(true, true, requirement(integrated, true)))
        assertTrue(
            conversationDictationUsesCallerAudio(
                true,
                true,
                requirement(other, true, ConversationDictationCallerAudioRequirement.Supported),
            ),
        )
        assertFalse(conversationDictationUsesCallerAudio(true, true, requirement(other, true)))
        assertFalse(
            conversationDictationUsesCallerAudio(true, true, requirement(integrated.copy(versionCode = 60L), true)),
        )
        assertFalse(conversationDictationUsesCallerAudio(true, false, requirement(integrated, true, supported = false)))
        assertTrue(conversationDictationUsesCallerAudio(false, true, requirement(other, false)))
    }

    private fun requirement(
        provider: ConversationDictationCallerAudioProvider?,
        canRecord: Boolean,
        recorded: ConversationDictationCallerAudioRequirement = ConversationDictationCallerAudioRequirement.Unknown,
        supported: Boolean = true,
    ) = conversationDictationKnownCallerAudioRequirement(provider, supported, canRecord, recorded)
}
