package dev.ipf.whitenoise.android.ui.conversation.composer

import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * In-app dictation never opens the provider's own recognition screen, so an unusable provider
 * has to be recoverable from White Noise's own controls. These map each failure to the single
 * action that can clear it.
 */
class ConversationDictationRecoveryTest {
    @Test
    fun unusableProviderOffersItsOwnSetupInsteadOfRetry() {
        listOf(
            ConversationDictationFailure.ProviderAccessRejected,
            ConversationDictationFailure.ProviderUnavailable,
        ).forEach { reason ->
            assertEquals(
                ConversationDictationRecovery.SpeechProviderSetup,
                dictationFailureRecovery(reason),
            )
        }
    }

    @Test
    fun permanentMicrophoneDenialStillOffersAppSettings() {
        assertEquals(
            ConversationDictationRecovery.AppSettings,
            dictationFailureRecovery(ConversationDictationFailure.PermissionPermanentlyDenied),
        )
    }

    @Test
    fun transientFailuresStillOfferRetry() {
        listOf(
            ConversationDictationFailure.NoSpeech,
            ConversationDictationFailure.Network,
            ConversationDictationFailure.RecognizerBusy,
            ConversationDictationFailure.TimedOut,
            ConversationDictationFailure.MicrophoneInUse,
            ConversationDictationFailure.MicrophoneMuted,
            ConversationDictationFailure.PermissionDenied,
            ConversationDictationFailure.ProviderDisconnected,
            ConversationDictationFailure.Unknown,
        ).forEach { reason ->
            assertEquals(
                ConversationDictationRecovery.Retry,
                dictationFailureRecovery(reason),
            )
        }
    }
}
