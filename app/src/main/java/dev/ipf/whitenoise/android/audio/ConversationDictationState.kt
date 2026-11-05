package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.input.TextFieldValue

internal data class ConversationDictationTarget(
    val accountRef: String,
    val groupIdHex: String,
    val capturedDraft: TextFieldValue,
    val capturedDraftRevision: Long,
    val mode: ConversationDictationMode,
    val replyToMessageIdHex: String? = null,
    val finishAfterSilenceMillis: Long? = null,
    val silenceDeliveryMode: ConversationDictationDeliveryMode = ConversationDictationDeliveryMode.PasteIntoDraft,
    val pauseOtherAudio: Boolean = true,
) {
    /** Compares the stable account and group identifiers without changing the captured target. */
    fun matchesConversation(
        accountRef: String,
        groupIdHex: String,
    ): Boolean =
        this.accountRef.equals(accountRef, ignoreCase = true) &&
            this.groupIdHex.equals(groupIdHex, ignoreCase = true)
}

internal enum class ConversationDictationMode {
    InApp,
    ProviderActivity,
}

internal enum class ConversationDictationDeliveryMode {
    PasteIntoDraft,
    SendOnFinish,
}

/** Separates an authoritative removal from a probe that could not establish membership. */
internal enum class ConversationDictationTargetValidation {
    Available,
    DefinitelyRemoved,
    Indeterminate,
}

internal data class ConversationDictationDraftSnapshot(
    val value: TextFieldValue,
    val revision: Long,
)

/** Immutable auto-send request that the app must compare with the origin draft again at commit time. */
internal data class ConversationDictationSendRequest(
    val accountRef: String,
    val groupIdHex: String,
    val expectedDraftRevision: Long,
    val expectedDraftText: String,
    val payload: String,
    val replyToMessageIdHex: String? = null,
    /** Called under the origin commit lock immediately before dispatch; false cancels an uncommitted send. */
    val beginDispatch: () -> Boolean = { true },
    /** Releases a successful claim when a fresh ownership check rejects the request before transport. */
    val onDispatchRejectedBeforeTransport: () -> Unit = {},
    /** Called after the send is visibly pending or natively accepted, when dictation UI can release ownership. */
    val onPendingShown: () -> Unit = {},
)

internal enum class ConversationDictationFailure {
    ProviderUnavailable,
    ProviderAccessRejected,
    PermissionDenied,
    PermissionPermanentlyDenied,
    MicrophoneMuted,
    MicrophoneInUse,
    AudioFocusUnavailable,
    Interrupted,
    NoSpeech,
    NoMatch,
    Network,
    ProviderDisconnected,
    RecognizerBusy,
    AudioBufferFull,
    TimedOut,
    DeliveryUnknown,
    SendBlocked,
    Unknown,
    DraftWriteDeferred,
    DraftWriteRefused,
}

/** The enabled external-media policy cannot be honored, so capture must not begin. */
internal class ConversationDictationAudioFocusDenied : RuntimeException()

/** PII-free provider readiness phases emitted for local diagnostics and tests. */
internal enum class ConversationDictationReadinessPhase {
    CheckingService,
    ServiceReady,
    ProviderUnavailable,
    TimedOut,
    Cancelled,
    LaunchingProvider,
}

internal data class ConversationDictationReadinessEvent(
    val phase: ConversationDictationReadinessPhase,
    val elapsedMillis: Long,
)

internal sealed interface ConversationDictationState {
    val sessionId: Long?
    val target: ConversationDictationTarget?

    data object Idle : ConversationDictationState {
        override val sessionId: Long? = null
        override val target: ConversationDictationTarget? = null
    }

    /** Choosing or dismissing a provider never captures audio or changes the captured draft. */
    data class ProviderSelectionRequired(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
    ) : ConversationDictationState

    data class DisclosureRequired(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
        val usesOfflineSpeechToText: Boolean = false,
    ) : ConversationDictationState

    data class PermissionRequired(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
    ) : ConversationDictationState

    data class Starting(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
    ) : ConversationDictationState

    /** A bounded, microphone-free availability check for the provider Activity. */
    data class CheckingProvider(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
        val startedAtElapsedMillis: Long,
    ) : ConversationDictationState

    data class Listening(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
        val startedAtElapsedMillis: Long,
    ) : ConversationDictationState

    data class Processing(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
    ) : ConversationDictationState

    /** The provider-owned recognition Activity is queued for launch. */
    data class ProviderActivityRequired(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
    ) : ConversationDictationState

    /** The provider-owned recognition Activity has been launched. */
    data class ProviderActivityActive(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
    ) : ConversationDictationState

    data class Failed(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
        val reason: ConversationDictationFailure,
        val retainedTranscript: String? = null,
        val cause: ConversationDictationFailure? = null,
        val recognitionIncomplete: Boolean = false,
        val draftRecovered: Boolean = false,
        val draftSuperseded: Boolean = false,
    ) : ConversationDictationState
}

/** Saved or submitted recovery leaves the ordinary composer in control, including during audio Retry. */
internal fun ConversationDictationState.requiresCompletionControls(recoveryHandedToComposer: Boolean = false): Boolean =
    this !is ConversationDictationState.Idle && !recoveryHandedToComposer
