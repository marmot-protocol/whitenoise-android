package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.audio.ConversationDictationParkedAudio
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.ConversationDictationTargetValidation
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession as RecognitionSession

/** Controlled recognition supplies events to the real controller; no microphone or network is opened. */
internal class MaestroDictationDraftController {
    var draft by mutableStateOf(TextFieldValue("Draft", TextRange(5)))
        private set
    var revision = 41L
        private set
    private var elapsedMillis = 0L
    private lateinit var listener: ConversationDictationRecognitionListener
    private var recognitionCreated = false
    var pendingAudio = false
        private set
    var keptAudio = false
        private set
    private val timers = mutableListOf<Scheduled>()
    private val sessions = mutableListOf<RecognitionLifetime>()
    private var parkedDiscards = 0
    private val dispatchAttempts = mutableListOf<String>()
    private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var restoredDispatches = 0
    private val ordinarySends = mutableListOf<String>()
    var delayedAcceptance: (() -> Unit)? = null
    var initialWriter: ((TextFieldValue, Boolean) -> Unit)? = null
    var visibleGroup by mutableStateOf(GROUP)
    var previousDictate: (() -> Unit)? = null
    private var availabilityReads = 0
    private var recovered = false
    val controller =
        ConversationDictationController(
            platform = ControlledPlatform(),
            targetAvailable = { _, _ ->
                availabilityReads += 1
                true
            },
            readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, revision) },
            writeDraft = { _, _, expected, value ->
                if (expected != revision) {
                    null
                } else {
                    edit(value)
                    revision
                }
            },
            disclosureAccepted = { true },
            markDisclosureAccepted = {},
            elapsedRealtime = { elapsedMillis },
            scheduleTimeout = { delay, callback ->
                check(delay >= 0)
                val timer = Scheduled(elapsedMillis + delay, callback)
                timers += timer
                ConversationDictationTimeoutHandle { timer.active = false }
            },
            targetValidator = { account, group ->
                check(account == ACCOUNT && group in setOf(GROUP, OTHER))
                ConversationDictationTargetValidation.Available
            },
            targetValidationScope = dispatchScope,
            sendTranscriptIfOriginUnchanged = { request ->
                check(request.accountRef == ACCOUNT && request.groupIdHex == GROUP)
                check(request.payload == RECOVERED && draft.text == "Draft")
                val before = revision
                check(request.beginDispatch()) { "Production send claim was not acquired" }
                check(draft.text.isEmpty() && revision > before) { "Production claim did not clear the draft" }
                request.onDispatchRejectedBeforeTransport()
                check(draft.text == "Draft" && revision > before + 1) { "Rejected claim lost its draft" }
                dispatchAttempts += request.payload
                restoredDispatches += 1
                false
            },
        )

    private inner class ControlledPlatform : ConversationDictationPlatform {
        override fun hasRecordAudioPermission(): Boolean = true

        override fun recognitionAvailable(): Boolean = true

        override fun callerAudioHasPending(): Boolean = pendingAudio

        override fun parkCallerAudio(): ConversationDictationParkedAudio? {
            if (!pendingAudio) return null
            pendingAudio = false
            keptAudio = true
            return object : ConversationDictationParkedAudio {
                override fun restore(): Boolean {
                    if (pendingAudio || !keptAudio) return false
                    keptAudio = false
                    pendingAudio = true
                    return true
                }

                override fun discard() {
                    check(keptAudio)
                    keptAudio = false
                    parkedDiscards += 1
                }
            }
        }

        override fun discardCallerAudio(onClosed: () -> Unit): Boolean {
            val retained = pendingAudio && recognitionCreated
            if (retained) {
                pendingAudio = false
                onClosed()
            }
            return retained
        }

        override fun finishCallerAudioCapture(onClosed: () -> Unit): Boolean {
            onClosed()
            return true
        }

        override fun createSession(listener: ConversationDictationRecognitionListener): RecognitionSession {
            this@MaestroDictationDraftController.listener = listener
            recognitionCreated = true
            val life = RecognitionLifetime(listener, pendingAudio)
            sessions += life
            return object : RecognitionSession {
                override fun start() = listener.onReady()

                override fun stop() = Unit

                override fun cancel() {
                    life.cancelled = true
                }

                override fun destroy() {
                    life.destroyed = true
                }

                override fun usesCallerAudioCapture(): Boolean = life.callerAudio

                override fun acknowledgeCallerAudio(): Boolean {
                    life.acknowledged = true
                    return true
                }

                override fun callerAudioFullyFed(): Boolean = true

                override fun callerAudioIsFinalChunk(): Boolean = true

                override fun callerAudioContainsSpeech(): Boolean = true
            }
        }
    }

    fun edit(
        value: TextFieldValue,
        textChanged: Boolean = true,
    ) {
        draft = value
        if (textChanged) revision += 1
    }

    fun recover(retained: Boolean) {
        check(!recovered)
        pendingAudio = retained
        check(controller.requestStart(ACCOUNT, GROUP, draft))
        if (retained) controller.paste() else controller.send()
        listener.onResult("recognized")
        if (retained) {
            restart()
            repeat(3) {
                listener.onError(ConversationDictationFailure.NoMatch)
                if (controller.state is ConversationDictationState.Starting) restart()
            }
        }
        check(draft.text == RECOVERED)
        check(controller.state is ConversationDictationState.Idle)
        check(controller.completionRevision(ACCOUNT, GROUP) > 0)
        check(keptAudio == retained)
        checkDispatchAttempts(retained)
        rejectOldWriter(RECOVERED)
        recovered = true
    }

    fun rejectOldWriter(expected: String) {
        val before = revision
        checkNotNull(initialWriter).invoke(TextFieldValue("obsolete writer"), true)
        check(draft.text == expected && revision == before)
    }

    fun verifySend(
        text: String,
        expected: String,
        retained: Boolean,
    ) {
        check(recovered && text == expected && draft.text == expected)
        check(controller.state is ConversationDictationState.Idle)
        check(keptAudio == retained && !pendingAudio)
        checkDispatchAttempts(retained)
        check(ordinarySends.isEmpty()) { "Ordinary send dispatched more than once" }
        ordinarySends += text
    }

    fun verifyFreshTake() {
        // A new generation waits for the production teardown delay on this virtual clock.
        if (controller.state is ConversationDictationState.Starting) restart()
        check(recovered && controller.state is ConversationDictationState.Listening)
        check(controller.state.target?.groupIdHex == GROUP && controller.state.target?.accountRef == ACCOUNT)
        check(!keptAudio && !pendingAudio && parkedDiscards == 1)
        val before = revision
        check(sessions.size > 1)
        sessions.dropLast(1).forEach { old ->
            old.listener.onResult("obsolete recognition")
            old.listener.onError(ConversationDictationFailure.NoMatch)
        }
        check(draft.text == RECOVERED && revision == before)
        check(controller.state is ConversationDictationState.Listening)
        checkDispatchAttempts(true)
        controller.cancel()
        check(controller.state is ConversationDictationState.Idle && draft.text == RECOVERED)
    }

    fun verifyPreviousGesture() {
        check(visibleGroup == OTHER && keptAudio && controller.state is ConversationDictationState.Idle)
        val reads = availabilityReads
        checkNotNull(previousDictate).invoke()
        check(availabilityReads == reads && keptAudio && controller.state is ConversationDictationState.Idle)
    }

    fun verifyOtherOrigin() {
        if (controller.state is ConversationDictationState.Starting) restart()
        check(controller.state is ConversationDictationState.Listening)
        check(controller.state.target?.accountRef == ACCOUNT && controller.state.target?.groupIdHex == OTHER)
        check(keptAudio && parkedDiscards == 0 && !pendingAudio)
        val before = revision
        sessions.dropLast(1).forEach { old -> old.listener.onResult("wrong-origin recognition") }
        check(draft.text == RECOVERED && revision == before && keptAudio)
    }

    private fun checkDispatchAttempts(retained: Boolean) {
        val expected = if (retained) emptyList() else listOf(RECOVERED)
        check(dispatchAttempts == expected && restoredDispatches == expected.size)
    }

    private fun restart() {
        elapsedMillis += 500L
        var dispatched = 0
        while (true) {
            val next = timers.filter { it.active && it.at <= elapsedMillis }.minByOrNull { it.at } ?: break
            check(dispatched++ < 64) { "Unbounded virtual timer callbacks" }
            next.active = false
            next.callback()
        }
    }

    fun close() {
        controller.onAccountUnavailable(ACCOUNT)
        controller.cancel()
        dispatchScope.cancel()
        check(!keptAudio && !pendingAudio)
        check(sessions.all { it.destroyed }) { "Recognition generation was not closed" }
        check(timers.none { it.active }) { "Controller disposal retained a scheduled callback" }
        val before = draft
        val version = revision
        sessions.forEach { old ->
            old.listener.onResult("disposed callback")
            old.listener.onError(ConversationDictationFailure.NoMatch)
        }
        check(draft == before && revision == version && controller.state is ConversationDictationState.Idle)
    }

    companion object {
        const val ACCOUNT = "maestro-dictation-account"
        const val GROUP = "maestro-dictation-group"
        const val OTHER = "maestro-other-dictation-group"
        const val RECOVERED = "Draft recognized"
        const val EDITED = "Edited recovery"
    }

    private class Scheduled(
        val at: Long,
        val callback: () -> Unit,
    ) {
        var active = true
    }

    private class RecognitionLifetime(
        val listener: ConversationDictationRecognitionListener,
        val callerAudio: Boolean,
    ) {
        var cancelled = false
        var destroyed = false
        var acknowledged = false
    }
}
