package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.CALLER_AUDIO_PROBE_TIMEOUT_MILLIS
import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.FOREGROUND_READINESS_TIMEOUT_MILLIS
import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.PROVIDER_READINESS_TIMEOUT_MILLIS

/** Routes a captured target to either app-owned recognition or provider compatibility UI. */
internal fun ConversationDictationController.startTarget(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    conversationDictationDiagnostic("event=start_target mode=${target.mode.name}")
    when (target.mode) {
        ConversationDictationMode.InApp -> startInAppOrDivertToProvider(sessionId, target)
        ConversationDictationMode.ProviderActivity -> prepareProviderActivity(sessionId, target)
    }
}

/**
 * Chooses the one surface this gesture will show.
 *
 * An unprivileged provider can only serve an app-owned session when it transcribes
 * the audio White Noise captures. Deciding that here, from what is already known about the
 * installed provider build, keeps one gesture to one UI: the in-app controls never hand an
 * already-rejected session to the provider's own recognition Activity mid-gesture.
 */
internal fun ConversationDictationController.startInAppOrDivertToProvider(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    // A platform that cannot answer is treated as one that never needed caller audio, which is
    // the path every configuration used before this routing existed.
    val requirement =
        runCatching(platform::callerAudioRequirement)
            .getOrDefault(ConversationDictationCallerAudioRequirement.NotNeeded)
    conversationDictationDiagnostic("event=caller_audio_requirement requirement=${requirement.name}")
    if (requirement == ConversationDictationCallerAudioRequirement.Unknown) {
        probeCallerAudioSupport(sessionId, target)
    } else {
        routeByCallerAudioRequirement(sessionId, target, requirement)
    }
}

/** Sends this session to app-owned capture, or to the only surface that can still capture. */
internal fun ConversationDictationController.routeByCallerAudioRequirement(
    sessionId: Long,
    target: ConversationDictationTarget,
    requirement: ConversationDictationCallerAudioRequirement,
) {
    if (requirement.allowsInAppCapture) {
        startOrRequestPermission(sessionId, target)
        return
    }
    // The provider ignores caller-supplied audio and cannot open the microphone for an
    // app-owned session, so its own recognition UI is the only surface that can capture.
    prepareProviderActivity(
        sessionId,
        target.copy(mode = ConversationDictationMode.ProviderActivity),
    )
}

/**
 * Asks the resolved provider once, without opening the microphone, and routes this exact
 * session on the answer. [ConversationDictationState.CheckingProvider] already renders as the
 * bounded readiness phase, so the wait needs no new surface.
 */
internal fun ConversationDictationController.probeCallerAudioSupport(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    val probeId = ++nextCallerAudioProbeId
    pendingCallerAudioProbeId = probeId
    val startedAt = elapsedRealtime()
    readinessStartedAtMillis = startedAt
    state = ConversationDictationState.CheckingProvider(sessionId, target, startedAt)
    emitReadiness(ConversationDictationReadinessPhase.CheckingService)
    conversationDictationDiagnostic("event=caller_audio_probe_start")
    armSessionTimeout(sessionId, CALLER_AUDIO_PROBE_TIMEOUT_MILLIS, "caller_audio_probe") {
        resolveCallerAudioProbe(
            probeId,
            sessionId,
            target,
            ConversationDictationCallerAudioRequirement.Unknown,
        )
    }
    val handle =
        runCatching {
            platform.probeCallerAudioSupport { requirement ->
                resolveCallerAudioProbe(probeId, sessionId, target, requirement)
            }
        }.getOrElse { failure ->
            if (failure !is RuntimeException) throw failure
            // A platform that cannot even start the question has established nothing about the
            // descriptor, so this session resolves as unknown rather than failing the gesture.
            conversationDictationDiagnostic(
                "event=caller_audio_probe_start_failed type=${failure.javaClass.simpleName}",
            )
            resolveCallerAudioProbe(
                probeId,
                sessionId,
                target,
                ConversationDictationCallerAudioRequirement.Unknown,
            )
            ConversationDictationTimeoutHandle {}
        }
    // A synchronous answer has already routed this session, and that routing can own a
    // readiness handle of its own, so only a still-pending probe adopts this one.
    if (pendingCallerAudioProbeId == probeId) readinessHandle = handle else handle.cancel()
}

/**
 * Applies the first answer a probe produces and discards every later one.
 *
 * An answer that establishes nothing about caller-supplied audio keeps app-owned capture, where
 * the real failure stays visible, instead of sending the user into provider UI whose engine
 * would fail the same way.
 */
internal fun ConversationDictationController.resolveCallerAudioProbe(
    probeId: Long,
    sessionId: Long,
    target: ConversationDictationTarget,
    requirement: ConversationDictationCallerAudioRequirement,
) {
    if (pendingCallerAudioProbeId != probeId) return
    val checking = state as? ConversationDictationState.CheckingProvider
    if (checking?.sessionId != sessionId) return
    pendingCallerAudioProbeId = null
    recognitionDeadlines.clearSession()
    readinessHandle?.cancel()
    readinessHandle = null
    conversationDictationDiagnostic("event=caller_audio_probe_result requirement=${requirement.name}")
    // A divert publishes its own readiness sequence from the provider-Activity check.
    if (requirement.allowsInAppCapture) emitReadiness(ConversationDictationReadinessPhase.ServiceReady)
    routeByCallerAudioRequirement(sessionId, target, requirement)
}

/** Performs the bounded microphone-free readiness check before provider UI launch. */
internal fun ConversationDictationController.prepareProviderActivity(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    conversationDictationDiagnostic("event=provider_activity_check_start")
    val startedAt = elapsedRealtime()
    readinessStartedAtMillis = startedAt
    state = ConversationDictationState.CheckingProvider(sessionId, target, startedAt)
    emitReadiness(ConversationDictationReadinessPhase.CheckingService)
    armSessionTimeout(sessionId, PROVIDER_READINESS_TIMEOUT_MILLIS, "provider_readiness") {
        emitReadiness(ConversationDictationReadinessPhase.TimedOut)
        fail(sessionId, target, ConversationDictationFailure.TimedOut)
    }
    val handle =
        platform.checkRecognitionActivity { available ->
            conversationDictationDiagnostic("event=provider_activity_check_result available=$available")
            val checking = state as? ConversationDictationState.CheckingProvider
            if (checking?.sessionId != sessionId) return@checkRecognitionActivity
            recognitionDeadlines.clearSession()
            readinessHandle = null
            if (!available) {
                emitReadiness(ConversationDictationReadinessPhase.ProviderUnavailable)
                fail(sessionId, target, ConversationDictationFailure.ProviderUnavailable)
                return@checkRecognitionActivity
            }
            emitReadiness(ConversationDictationReadinessPhase.ServiceReady)
            state = ConversationDictationState.ProviderActivityRequired(sessionId, target)
            providerActivityRequestIdState.longValue += 1L
        }
    if (state is ConversationDictationState.CheckingProvider) {
        readinessHandle = handle
    } else {
        handle.cancel()
    }
}

/** Checks provider and White Noise's runtime grant before creating a recognizer generation. */
internal fun ConversationDictationController.startOrRequestPermission(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    state = ConversationDictationState.Starting(sessionId, target)
    val configured = platform.pinnedRecognitionConfigured()
    conversationDictationDiagnostic("event=recognition_configured configured=$configured")
    val microphoneAccess = platform.microphoneAccess()
    conversationDictationDiagnostic("event=microphone_preflight access=${microphoneAccess.name}")
    // Routing has already chosen this gesture's capture surface. A failed in-app session
    // stays here with recovery; it never opens a second recording UI mid-gesture.
    if (!configured) {
        val failure =
            when (microphoneAccess) {
                ConversationDictationMicrophoneAccess.AppOpDenied ->
                    ConversationDictationFailure.PermissionPermanentlyDenied
                ConversationDictationMicrophoneAccess.MicrophoneMuted ->
                    ConversationDictationFailure.MicrophoneMuted
                ConversationDictationMicrophoneAccess.Granted,
                ConversationDictationMicrophoneAccess.RuntimePermissionRequired,
                -> ConversationDictationFailure.ProviderUnavailable
            }
        fail(sessionId, target, failure)
        return
    }
    when (microphoneAccess) {
        ConversationDictationMicrophoneAccess.Granted -> startWhenProviderAvailable(sessionId, target)
        ConversationDictationMicrophoneAccess.RuntimePermissionRequired -> {
            state = ConversationDictationState.PermissionRequired(sessionId, target)
            permissionRequestIdState.longValue += 1L
        }
        ConversationDictationMicrophoneAccess.AppOpDenied,
        ConversationDictationMicrophoneAccess.MicrophoneMuted,
        -> requestNativeMicrophoneUnblock(sessionId, target)
    }
}

/** Re-checks the selected provider after permission is known before opening the microphone. */
internal fun ConversationDictationController.startWhenProviderAvailable(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    state = ConversationDictationState.Starting(sessionId, target)
    val available = platform.recognitionAvailable()
    conversationDictationDiagnostic("event=recognition_available available=$available")
    if (!available) {
        fail(sessionId, target, ConversationDictationFailure.ProviderUnavailable)
        return
    }
    startRecognition(sessionId, target)
}

/** Publishes pending ownership before enqueueing, without treating enqueue success as promotion. */
internal fun ConversationDictationController.ensureDurableSession(
    sessionId: Long,
    target: ConversationDictationTarget,
): Boolean {
    if (!foregroundLease.begin()) return foregroundLease.ready
    val token = requireNotNull(notificationSessionToken)
    foregroundLease.armPromotionTimeout(
        scheduleTimeout(FOREGROUND_READINESS_TIMEOUT_MILLIS) {
            if (foregroundLease.active && !foregroundLease.ready && notificationSessionToken == token) {
                failDurableSessionStart(sessionId, target, ConversationDictationFailure.TimedOut)
            }
        },
    )
    val started = runCatching { startDurableSession(token) { onDurableServiceReady(token) } }.getOrDefault(false)
    conversationDictationDiagnostic("event=foreground_service_start requested=$started")
    if (foregroundLease.active && notificationSessionToken == token) {
        if (started) {
            if (foregroundLease.acceptStart()) onDurableServiceReady(token)
        } else {
            failDurableSessionStart(sessionId, target, ConversationDictationFailure.Unknown)
        }
    }
    // The acknowledgement path owns continuation, including a synchronous test/platform callback.
    return false
}

/** A rejected retry promotion cannot discard the sealed recording it was asked to transcribe. */
internal fun ConversationDictationController.failDurableSessionStart(
    sessionId: Long,
    target: ConversationDictationTarget,
    failure: ConversationDictationFailure,
) {
    releaseDurableSessionLease()
    // A foreground recovery reattach must not replace or erase the retained failure.
    if (state is ConversationDictationState.Failed) return
    if (finishRequested && runCatching(platform::callerAudioHasPending).getOrDefault(false)) {
        failWithRetainedCallerAudio(sessionId, target, failure)
    } else {
        fail(sessionId, target, failure)
    }
}
