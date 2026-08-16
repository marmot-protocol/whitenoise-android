package dev.ipf.whitenoise.android.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.core.graphemeBoundaryAtOrAfter
import dev.ipf.whitenoise.android.core.graphemeBoundaryAtOrBefore

internal data class ConversationDictationTarget(
    val accountRef: String,
    val groupIdHex: String,
    val capturedDraft: TextFieldValue,
    val capturedDraftRevision: Long,
    val mode: ConversationDictationMode,
)

internal enum class ConversationDictationMode {
    InApp,
    ProviderActivity,
}

internal data class ConversationDictationDraftSnapshot(
    val value: TextFieldValue,
    val revision: Long,
)

internal enum class ConversationDictationFailure {
    ProviderUnavailable,
    PermissionDenied,
    PermissionPermanentlyDenied,
    MicrophoneInUse,
    NoSpeech,
    Network,
    RecognizerBusy,
    TimedOut,
    Unknown,
}

internal sealed interface ConversationDictationState {
    val sessionId: Long?
    val target: ConversationDictationTarget?

    data object Idle : ConversationDictationState {
        override val sessionId: Long? = null
        override val target: ConversationDictationTarget? = null
    }

    data class DisclosureRequired(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
    ) : ConversationDictationState

    data class PermissionRequired(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
    ) : ConversationDictationState

    data class Starting(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
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
    ) : ConversationDictationState

    /**
     * The origin draft changed in a way that made the captured insertion anchor
     * ambiguous. The transcript remains process-memory-only until the user
     * explicitly inserts, copies, or discards it.
     */
    data class ReviewRequired(
        override val sessionId: Long,
        override val target: ConversationDictationTarget,
        val transcript: String,
    ) : ConversationDictationState
}

internal interface ConversationDictationRecognitionListener {
    fun onReady()

    fun onEndOfSpeech()

    fun onResult(transcript: String?)

    fun onError(error: ConversationDictationFailure)
}

internal interface ConversationDictationRecognitionSession {
    fun start()

    fun stop()

    fun cancel()

    fun destroy()
}

internal interface ConversationDictationPlatform {
    fun hasRecordAudioPermission(): Boolean

    fun recognitionAvailable(): Boolean

    fun recognitionActivityAvailable(): Boolean = true

    fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession
}

internal fun interface ConversationDictationTimeoutHandle {
    fun cancel()
}

private data class ConversationDictationKey(
    val accountRef: String,
    val groupIdHex: String,
)

/**
 * Process-level owner for composer dictation.
 *
 * The immutable target prevents a delayed recognizer callback from writing to
 * whichever conversation happens to be visible when recognition completes.
 * Raw audio is owned by the installed speech service and is never persisted by
 * White Noise; transcript text takes the normal per-conversation draft path.
 */
@Stable
@Suppress("ReturnCount", "TooManyFunctions")
internal class ConversationDictationController internal constructor(
    private val platform: ConversationDictationPlatform,
    private val readDraft: (accountRef: String, groupIdHex: String) -> ConversationDictationDraftSnapshot,
    private val writeDraft: (
        accountRef: String,
        groupIdHex: String,
        expectedRevision: Long,
        value: TextFieldValue,
    ) -> Boolean,
    private val targetAvailable: (accountRef: String, groupIdHex: String) -> Boolean = { _, _ -> true },
    private val onBeforeRecognition: () -> Unit = {},
    private val tryAcquireMicrophone: () -> Boolean = { true },
    private val releaseMicrophone: () -> Unit = {},
    private val disclosureAccepted: () -> Boolean,
    private val markDisclosureAccepted: () -> Unit,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val scheduleTimeout: (delayMillis: Long, callback: () -> Unit) -> ConversationDictationTimeoutHandle =
        ::scheduleConversationDictationTimeout,
) {
    constructor(
        context: Context,
        readDraft: (accountRef: String, groupIdHex: String) -> ConversationDictationDraftSnapshot,
        writeDraft: (
            accountRef: String,
            groupIdHex: String,
            expectedRevision: Long,
            value: TextFieldValue,
        ) -> Boolean,
        targetAvailable: (accountRef: String, groupIdHex: String) -> Boolean,
        onBeforeRecognition: () -> Unit,
        tryAcquireMicrophone: () -> Boolean,
        releaseMicrophone: () -> Unit,
    ) : this(
        platform = AndroidConversationDictationPlatform(context.applicationContext),
        readDraft = readDraft,
        writeDraft = writeDraft,
        targetAvailable = targetAvailable,
        onBeforeRecognition = onBeforeRecognition,
        tryAcquireMicrophone = tryAcquireMicrophone,
        releaseMicrophone = releaseMicrophone,
        disclosureAccepted = {
            context
                .applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(DISCLOSURE_ACCEPTED_KEY, false)
        },
        markDisclosureAccepted = {
            context
                .applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(DISCLOSURE_ACCEPTED_KEY, true)
                .apply()
        },
    )

    var state: ConversationDictationState by mutableStateOf(ConversationDictationState.Idle)
        private set

    private val completionRevisions = mutableStateMapOf<ConversationDictationKey, Int>()
    private var nextSessionId = 0L
    private var recognitionSession: ConversationDictationRecognitionSession? = null
    private var timeoutHandle: ConversationDictationTimeoutHandle? = null
    private var microphoneHeld = false

    private val _permissionRequestId = mutableLongStateOf(0L)
    val permissionRequestId: Long
        get() = _permissionRequestId.longValue

    private val _providerActivityRequestId = mutableLongStateOf(0L)
    val providerActivityRequestId: Long
        get() = _providerActivityRequestId.longValue

    val hasPendingSession: Boolean
        get() = state !is ConversationDictationState.Idle

    val blocksNewRequest: Boolean
        get() =
            state !is ConversationDictationState.Idle &&
                state !is ConversationDictationState.Failed

    val ownsMicrophone: Boolean
        get() = microphoneHeld

    fun completionRevision(
        accountRef: String,
        groupIdHex: String,
    ): Int = completionRevisions[ConversationDictationKey(accountRef, groupIdHex)] ?: 0

    fun isOwnedBy(
        accountRef: String,
        groupIdHex: String,
    ): Boolean = state.target?.let { it.accountRef == accountRef && it.groupIdHex == groupIdHex } == true

    fun requestStart(
        accountRef: String,
        groupIdHex: String,
        draft: TextFieldValue,
    ): Boolean =
        requestStart(
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            draft = draft,
            mode = ConversationDictationMode.InApp,
        )

    fun requestProviderActivityStart(
        accountRef: String,
        groupIdHex: String,
        draft: TextFieldValue,
    ): Boolean =
        requestStart(
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            draft = draft,
            mode = ConversationDictationMode.ProviderActivity,
        )

    private fun requestStart(
        accountRef: String,
        groupIdHex: String,
        draft: TextFieldValue,
        mode: ConversationDictationMode,
    ): Boolean {
        if (!targetAvailable(accountRef, groupIdHex)) return false
        // Unlike the app-owned SpeechRecognizer, an external provider Activity
        // cannot be synchronously terminated by this controller. Keep its one
        // ActivityResult owner stable until the provider returns, so a result
        // can never be misattributed to a replacement target.
        if (state is ConversationDictationState.ProviderActivityActive) return false
        if (blocksNewRequest && hasSameTarget(accountRef, groupIdHex, mode)) {
            return false
        }

        // A request for a different target/mode is an explicit replacement.
        // Tear down the previous generation before publishing the new target;
        // all callbacks from the previous generation then fail the session-id
        // ownership check and become no-ops.
        clearRecognitionSession(cancel = true)
        val sessionId = ++nextSessionId
        val capturedRevision = readDraft(accountRef, groupIdHex).revision
        val target =
            ConversationDictationTarget(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                capturedDraft = draft.copy(composition = null),
                capturedDraftRevision = capturedRevision,
                mode = mode,
            )
        if (!disclosureAccepted()) {
            state = ConversationDictationState.DisclosureRequired(sessionId, target)
            return true
        }
        startTarget(sessionId, target)
        return true
    }

    private fun hasSameTarget(
        accountRef: String,
        groupIdHex: String,
        mode: ConversationDictationMode,
    ): Boolean =
        state.target?.let { target ->
            target.accountRef == accountRef &&
                target.groupIdHex == groupIdHex &&
                target.mode == mode
        } == true

    fun acceptDisclosure() {
        val pending = state as? ConversationDictationState.DisclosureRequired ?: return
        markDisclosureAccepted()
        startTarget(pending.sessionId, pending.target)
    }

    fun onPermissionResult(
        granted: Boolean,
        permanentlyDenied: Boolean = false,
    ) {
        val pending = state as? ConversationDictationState.PermissionRequired ?: return
        if (!granted) {
            state =
                ConversationDictationState.Failed(
                    sessionId = pending.sessionId,
                    target = pending.target,
                    reason =
                        if (permanentlyDenied) {
                            ConversationDictationFailure.PermissionPermanentlyDenied
                        } else {
                            ConversationDictationFailure.PermissionDenied
                        },
                )
            return
        }
        startRecognition(pending.sessionId, pending.target)
    }

    fun stop() {
        val current = state
        if (current !is ConversationDictationState.Starting && current !is ConversationDictationState.Listening) return
        val sessionId = current.sessionId ?: return
        val target = current.target ?: return
        state = ConversationDictationState.Processing(sessionId, target)
        armTimeout(sessionId, PROCESSING_TIMEOUT_MILLIS) {
            fail(sessionId, target, ConversationDictationFailure.TimedOut, cancelSession = true)
        }
        runCatching { recognitionSession?.stop() }
            .onFailure { fail(sessionId, target, ConversationDictationFailure.Unknown) }
    }

    fun cancel() {
        clearRecognitionSession(cancel = true)
        state = ConversationDictationState.Idle
    }

    fun beginProviderActivityLaunch(requestId: Long): Boolean {
        if (requestId != providerActivityRequestId) return false
        val pending = state as? ConversationDictationState.ProviderActivityRequired ?: return false
        state = ConversationDictationState.ProviderActivityActive(pending.sessionId, pending.target)
        return true
    }

    fun onProviderActivityResult(transcript: String?) {
        val active = state as? ConversationDictationState.ProviderActivityActive ?: return
        val recognized = transcript?.trim().orEmpty()
        if (recognized.isBlank()) {
            fail(active.sessionId, active.target, ConversationDictationFailure.NoSpeech)
            return
        }
        if (!targetAvailable(active.target.accountRef, active.target.groupIdHex)) {
            cancel()
            return
        }
        deliverTranscript(active.sessionId, active.target, recognized)
    }

    fun onProviderActivityCancelled() {
        if (state is ConversationDictationState.ProviderActivityActive) cancel()
    }

    fun onProviderActivityLaunchFailed() {
        val providerState = state as? ConversationDictationState.ProviderActivityActive ?: return
        fail(
            providerState.sessionId,
            providerState.target,
            ConversationDictationFailure.ProviderUnavailable,
        )
    }

    fun dismissFailure() {
        if (state is ConversationDictationState.Failed) state = ConversationDictationState.Idle
    }

    fun retry() {
        val failed = state as? ConversationDictationState.Failed ?: return
        requestStart(
            accountRef = failed.target.accountRef,
            groupIdHex = failed.target.groupIdHex,
            draft = readDraft(failed.target.accountRef, failed.target.groupIdHex).value,
            mode = failed.target.mode,
        )
    }

    fun insertReviewAtEnd() {
        val review = state as? ConversationDictationState.ReviewRequired ?: return
        if (!targetAvailable(review.target.accountRef, review.target.groupIdHex)) {
            cancel()
            return
        }
        repeat(MAX_CONDITIONAL_WRITE_ATTEMPTS) {
            val current = readDraft(review.target.accountRef, review.target.groupIdHex)
            val merged = appendConversationDictationTranscript(current.value, review.transcript)
            if (
                writeDraft(
                    review.target.accountRef,
                    review.target.groupIdHex,
                    current.revision,
                    merged,
                )
            ) {
                complete(review.target)
                return
            }
        }
    }

    fun dismissReview() {
        if (state is ConversationDictationState.ReviewRequired) state = ConversationDictationState.Idle
    }

    fun onTargetRemoved(
        accountRef: String,
        groupIdHex: String,
    ) {
        if (isOwnedBy(accountRef, groupIdHex)) cancel()
    }

    /** Cancels only when the target account is signed out or removed, not when it becomes inactive. */
    fun onAccountUnavailable(accountRef: String) {
        if (state.target?.accountRef == accountRef) cancel()
    }

    /** Releases any provider/microphone resource without discarding terminal review text. */
    fun onAppBackgrounded() {
        when (state) {
            is ConversationDictationState.DisclosureRequired,
            is ConversationDictationState.PermissionRequired,
            is ConversationDictationState.Starting,
            is ConversationDictationState.Listening,
            is ConversationDictationState.Processing,
            -> cancel()
            // Launching the provider Activity necessarily backgrounds White
            // Noise. Its registered ActivityResult callback remains the owner
            // across that transition and across Activity recreation.
            is ConversationDictationState.ProviderActivityRequired,
            is ConversationDictationState.ProviderActivityActive,
            -> Unit
            else -> Unit
        }
    }

    private fun startTarget(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        when (target.mode) {
            ConversationDictationMode.InApp -> startOrRequestPermission(sessionId, target)
            ConversationDictationMode.ProviderActivity -> prepareProviderActivity(sessionId, target)
        }
    }

    private fun prepareProviderActivity(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        state = ConversationDictationState.ProviderActivityRequired(sessionId, target)
        if (!platform.recognitionActivityAvailable()) {
            fail(sessionId, target, ConversationDictationFailure.ProviderUnavailable)
            return
        }
        _providerActivityRequestId.longValue += 1L
    }

    private fun startOrRequestPermission(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        state = ConversationDictationState.Starting(sessionId, target)
        if (!platform.recognitionAvailable()) {
            fail(sessionId, target, ConversationDictationFailure.ProviderUnavailable)
            return
        }
        if (!platform.hasRecordAudioPermission()) {
            state = ConversationDictationState.PermissionRequired(sessionId, target)
            _permissionRequestId.longValue += 1L
            return
        }
        startRecognition(sessionId, target)
    }

    private fun startRecognition(
        sessionId: Long,
        target: ConversationDictationTarget,
    ) {
        if (state.sessionId != sessionId) return
        clearRecognitionSession(cancel = false)
        if (!tryAcquireMicrophone()) {
            fail(sessionId, target, ConversationDictationFailure.MicrophoneInUse)
            return
        }
        microphoneHeld = true
        if (runCatching(onBeforeRecognition).isFailure) {
            fail(sessionId, target, ConversationDictationFailure.Unknown)
            return
        }
        state = ConversationDictationState.Starting(sessionId, target)
        armTimeout(sessionId, STARTING_TIMEOUT_MILLIS) {
            fail(sessionId, target, ConversationDictationFailure.TimedOut, cancelSession = true)
        }
        val listener =
            object : ConversationDictationRecognitionListener {
                override fun onReady() {
                    if (!owns(sessionId) || state !is ConversationDictationState.Starting) return
                    state =
                        ConversationDictationState.Listening(
                            sessionId = sessionId,
                            target = target,
                            startedAtElapsedMillis = elapsedRealtime(),
                        )
                    armTimeout(sessionId, MAX_LISTENING_MILLIS) { stop() }
                }

                override fun onEndOfSpeech() {
                    val recognitionActive =
                        state is ConversationDictationState.Starting || state is ConversationDictationState.Listening
                    if (!owns(sessionId) || !recognitionActive) {
                        return
                    }
                    state = ConversationDictationState.Processing(sessionId, target)
                    armTimeout(sessionId, PROCESSING_TIMEOUT_MILLIS) {
                        fail(sessionId, target, ConversationDictationFailure.TimedOut, cancelSession = true)
                    }
                }

                override fun onResult(transcript: String?) {
                    if (!owns(sessionId)) return
                    val recognized = transcript?.trim().orEmpty()
                    if (recognized.isBlank()) {
                        fail(sessionId, target, ConversationDictationFailure.NoSpeech)
                        return
                    }
                    if (!targetAvailable(target.accountRef, target.groupIdHex)) {
                        cancel()
                        return
                    }
                    deliverTranscript(sessionId, target, recognized)
                }

                override fun onError(error: ConversationDictationFailure) {
                    if (!owns(sessionId)) return
                    fail(sessionId, target, error)
                }
            }
        runCatching {
            platform.createSession(listener).also { recognitionSession = it }.start()
        }.onFailure {
            fail(sessionId, target, ConversationDictationFailure.Unknown)
        }
    }

    private fun owns(sessionId: Long): Boolean =
        state.sessionId == sessionId &&
            (
                recognitionSession != null ||
                    state is ConversationDictationState.ProviderActivityRequired ||
                    state is ConversationDictationState.ProviderActivityActive
            )

    private fun fail(
        sessionId: Long,
        target: ConversationDictationTarget,
        reason: ConversationDictationFailure,
        cancelSession: Boolean = false,
    ) {
        if (state.sessionId != sessionId) return
        clearRecognitionSession(cancel = cancelSession)
        state = ConversationDictationState.Failed(sessionId, target, reason)
    }

    private fun clearRecognitionSession(cancel: Boolean) {
        timeoutHandle?.cancel()
        timeoutHandle = null
        val session = recognitionSession
        recognitionSession = null
        if (cancel) runCatching { session?.cancel() }
        runCatching { session?.destroy() }
        if (microphoneHeld) {
            microphoneHeld = false
            runCatching(releaseMicrophone)
        }
    }

    private fun deliverTranscript(
        sessionId: Long,
        target: ConversationDictationTarget,
        transcript: String,
    ) {
        repeat(MAX_CONDITIONAL_WRITE_ATTEMPTS) {
            if (!owns(sessionId) || !targetAvailable(target.accountRef, target.groupIdHex)) {
                cancel()
                return
            }
            val current = readDraft(target.accountRef, target.groupIdHex)
            when (
                val merge =
                    mergeConversationDictationTranscript(
                        captured = target.capturedDraft,
                        current = current.value,
                        transcript = transcript,
                    )
            ) {
                is ConversationDictationMerge.Applied -> {
                    val accepted =
                        runCatching {
                            writeDraft(
                                target.accountRef,
                                target.groupIdHex,
                                current.revision,
                                merge.value,
                            )
                        }.getOrDefault(false)
                    if (accepted) {
                        complete(target)
                        return
                    }
                }
                ConversationDictationMerge.NeedsReview -> {
                    clearRecognitionSession(cancel = false)
                    state = ConversationDictationState.ReviewRequired(sessionId, target, transcript)
                    return
                }
            }
        }
        clearRecognitionSession(cancel = false)
        state = ConversationDictationState.ReviewRequired(sessionId, target, transcript)
    }

    private fun complete(target: ConversationDictationTarget) {
        clearRecognitionSession(cancel = false)
        val key = ConversationDictationKey(target.accountRef, target.groupIdHex)
        completionRevisions[key] = (completionRevisions[key] ?: 0) + 1
        state = ConversationDictationState.Idle
    }

    private fun armTimeout(
        sessionId: Long,
        delayMillis: Long,
        callback: () -> Unit,
    ) {
        timeoutHandle?.cancel()
        timeoutHandle =
            scheduleTimeout(delayMillis) {
                if (state.sessionId == sessionId) callback()
            }
    }

    private companion object {
        const val PREFERENCES_NAME = "whitenoise"
        const val DISCLOSURE_ACCEPTED_KEY = "composer_dictation_external_provider_disclosed"
        const val STARTING_TIMEOUT_MILLIS = 10_000L
        const val MAX_LISTENING_MILLIS = 60_000L
        const val PROCESSING_TIMEOUT_MILLIS = 20_000L
        const val MAX_CONDITIONAL_WRITE_ATTEMPTS = 2
    }
}

internal sealed interface ConversationDictationMerge {
    data class Applied(
        val value: TextFieldValue,
    ) : ConversationDictationMerge

    data object NeedsReview : ConversationDictationMerge
}

@Suppress("ReturnCount")
internal fun mergeConversationDictationTranscript(
    captured: TextFieldValue,
    current: TextFieldValue,
    transcript: String,
): ConversationDictationMerge {
    val recognized = transcript.trim()
    if (recognized.isEmpty()) return ConversationDictationMerge.Applied(current)

    if (current.text == captured.text) {
        return ConversationDictationMerge.Applied(
            insertConversationDictationTranscript(current, captured.selection, recognized),
        )
    }

    val remappedSelection =
        remapConversationDictationSelection(captured, current)
            ?: return ConversationDictationMerge.NeedsReview
    return ConversationDictationMerge.Applied(
        insertConversationDictationTranscript(current, remappedSelection, recognized),
    )
}

internal fun appendConversationDictationTranscript(
    current: TextFieldValue,
    transcript: String,
): TextFieldValue {
    val recognized = transcript.trim()
    if (recognized.isEmpty()) return current
    val separator =
        when {
            current.text.isBlank() -> ""
            current.text.last().isWhitespace() -> ""
            else -> " "
        }
    val appended = current.text + separator + recognized
    return TextFieldValue(appended, TextRange(appended.length))
}

private fun insertConversationDictationTranscript(
    current: TextFieldValue,
    selection: TextRange,
    transcript: String,
): TextFieldValue {
    val rawStart = minOf(selection.start, selection.end).coerceIn(0, current.text.length)
    val rawEnd = maxOf(selection.start, selection.end).coerceIn(rawStart, current.text.length)
    val start = current.text.graphemeBoundaryAtOrBefore(rawStart)
    val end = current.text.graphemeBoundaryAtOrAfter(rawEnd)
    val needsLeadingSpace =
        start > 0 &&
            !current.text[start - 1].isWhitespace() &&
            !transcript.first().isWhitespace()
    val needsTrailingSpace =
        end < current.text.length &&
            Character.isLetterOrDigit(current.text.codePointAt(end)) &&
            !transcript.last().isWhitespace()
    val insertion =
        buildString {
            if (needsLeadingSpace) append(' ')
            append(transcript)
            if (needsTrailingSpace) append(' ')
        }
    val inserted = current.text.replaceRange(start, end, insertion)
    return TextFieldValue(inserted, TextRange(start + insertion.length))
}

/**
 * Maps the captured insertion range into a concurrently edited draft using
 * unchanged context on both sides. A unique best anchor is required; edits
 * that remove the selection or make its location ambiguous fail closed so the
 * transcript can be offered for explicit insertion/copying.
 */
@Suppress("ReturnCount")
private fun remapConversationDictationSelection(
    captured: TextFieldValue,
    current: TextFieldValue,
): TextRange? {
    if (captured.text.isEmpty()) return null
    val rawStart = minOf(captured.selection.start, captured.selection.end).coerceIn(0, captured.text.length)
    val rawEnd = maxOf(captured.selection.start, captured.selection.end).coerceIn(rawStart, captured.text.length)
    val start = captured.text.graphemeBoundaryAtOrBefore(rawStart)
    val end = captured.text.graphemeBoundaryAtOrAfter(rawEnd)
    val selected = captured.text.substring(start, end)
    val leftContext = captured.text.substring(0, start)
    val rightContext = captured.text.substring(end)

    val candidates =
        if (selected.isEmpty()) {
            (0..current.text.length).map { TextRange(it) }
        } else {
            current.text
                .occurrenceStarts(selected)
                .map { occurrenceStart -> TextRange(occurrenceStart, occurrenceStart + selected.length) }
        }.filter { range ->
            current.text.isGraphemeBoundary(range.start) && current.text.isGraphemeBoundary(range.end)
        }
    if (candidates.isEmpty()) return null

    val scored =
        candidates.map { candidate ->
            val currentLeft = current.text.substring(0, candidate.start)
            val currentRight = current.text.substring(candidate.end)
            candidate to
                (
                    commonSuffixLength(leftContext, currentLeft) +
                        commonPrefixLength(rightContext, currentRight)
                )
        }
    val bestScore = scored.maxOf { it.second }
    val requiredContext = minOf(MIN_ANCHOR_CONTEXT_CHARS, leftContext.length + rightContext.length).coerceAtLeast(1)
    if (selected.isEmpty() && bestScore < requiredContext) return null
    val best = scored.filter { it.second == bestScore }
    return best.singleOrNull()?.first
}

private fun String.occurrenceStarts(needle: String): List<Int> {
    if (needle.isEmpty()) return (0..length).toList()
    val starts = mutableListOf<Int>()
    var from = 0
    while (from <= length - needle.length) {
        val found = indexOf(needle, startIndex = from)
        if (found < 0) break
        starts += found
        from = found + 1
    }
    return starts
}

@Suppress("MaxLineLength")
private fun String.isGraphemeBoundary(index: Int): Boolean = index in 0..length && graphemeBoundaryAtOrBefore(index) == index

private fun commonPrefixLength(
    first: String,
    second: String,
): Int {
    val limit = minOf(first.length, second.length)
    var index = 0
    while (index < limit && first[index] == second[index]) index += 1
    return index
}

private fun commonSuffixLength(
    first: String,
    second: String,
): Int {
    val limit = minOf(first.length, second.length)
    var count = 0
    while (count < limit && first[first.length - 1 - count] == second[second.length - 1 - count]) count += 1
    return count
}

private const val MIN_ANCHOR_CONTEXT_CHARS = 3

@Suppress("MaxLineLength")
private class AndroidConversationDictationPlatform(
    private val context: Context,
) : ConversationDictationPlatform {
    override fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    override fun recognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun recognitionActivityAvailable(): Boolean = conversationDictationRecognitionActivityIntent().resolveActivity(context.packageManager) != null

    override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession =
        AndroidConversationDictationRecognitionSession(context, listener)
}

internal fun conversationDictationRecognitionActivityIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

private class AndroidConversationDictationRecognitionSession(
    private val context: Context,
    listener: ConversationDictationRecognitionListener,
) : ConversationDictationRecognitionSession {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private var destroyed = false

    init {
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = listener.onReady()

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = listener.onEndOfSpeech()

                override fun onError(error: Int) = listener.onError(error.toConversationDictationFailure())

                override fun onResults(results: Bundle?) {
                    listener.onResult(
                        results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull(),
                    )
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) = Unit
            },
        )
    }

    override fun start() {
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
        )
    }

    override fun stop() = recognizer.stopListening()

    override fun cancel() = recognizer.cancel()

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        recognizer.destroy()
    }
}

private fun Int.toConversationDictationFailure(): ConversationDictationFailure =
    when (this) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ConversationDictationFailure.PermissionDenied
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> ConversationDictationFailure.NoSpeech
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        -> ConversationDictationFailure.Network
        SpeechRecognizer.ERROR_AUDIO -> ConversationDictationFailure.MicrophoneInUse
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        -> ConversationDictationFailure.RecognizerBusy
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> ConversationDictationFailure.ProviderUnavailable
        else -> ConversationDictationFailure.Unknown
    }

private fun scheduleConversationDictationTimeout(
    delayMillis: Long,
    callback: () -> Unit,
): ConversationDictationTimeoutHandle {
    val handler = Handler(Looper.getMainLooper())
    val runnable = Runnable(callback)
    handler.postDelayed(runnable, delayMillis)
    return ConversationDictationTimeoutHandle { handler.removeCallbacks(runnable) }
}
