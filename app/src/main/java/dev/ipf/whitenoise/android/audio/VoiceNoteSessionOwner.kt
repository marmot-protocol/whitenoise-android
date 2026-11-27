package dev.ipf.whitenoise.android.audio

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.util.UUID

/** Callbacks a chat's recorder reports to its process-level owner. */
internal class VoiceRecorderHooks(
    val outputDirectory: File,
    val onPermissionRequest: () -> Boolean,
    val onRecordingStarted: () -> Unit,
    val onRecordingComplete: (File, Long) -> Unit,
    val onReleasedRecordingComplete: (File, Long) -> Unit,
    val onError: (Throwable) -> Unit,
    val onAutomaticStop: (VoiceRecordingStopReason) -> Unit,
)

/** What a chat's voice note is doing, for surfaces outside the chat. */
internal enum class VoiceNoteActivity { None, Recording, Paused, Draft }

/**
 * Process-level owner of voice-note capture and unsent drafts, keyed by account and chat. A locked take keeps its
 * recorder when its chat closes; a finished take waits as that chat's draft until it is sent or discarded. Screens
 * bind to present a chat's recorder and draft and never own either.
 */
@Stable
@Suppress("TooManyFunctions") // Chat, account and foreground events share this recorder owner.
internal class VoiceNoteSessionOwner(
    private val store: VoiceDraftStore,
    private val newController: (VoiceNoteKey, VoiceRecorderHooks) -> VoiceRecordingController,
    private val presentError: (Throwable) -> Unit,
    private val presentElsewhere: () -> Unit,
    private val hasMicrophonePermission: () -> Boolean = { false },
    private val startForeground: (String) -> Boolean = { false },
) {
    private val controllers = mutableStateMapOf<VoiceNoteKey, VoiceRecordingController>()
    private val drafts = mutableStateMapOf<VoiceNoteKey, VoiceDraft>()
    private val lost = mutableStateListOf<VoiceNoteKey>()
    private val recovered = mutableSetOf<VoiceNoteKey>()
    private val permissionRequests = mutableMapOf<VoiceNoteKey, () -> Boolean>()
    private val bindingVersions = mutableStateMapOf<VoiceNoteKey, Int>()
    private var persisted: Set<String>? = null
    private var foregroundRequest: Pair<VoiceNoteKey, String>? = null
    private val sendOnArrival = mutableMapOf<VoiceNoteKey, File>()
    private val chatNames = mutableMapOf<VoiceNoteKey, String>()

    /**
     * Presents [key]'s recorder to a visible chat: the live one if this chat is recording, otherwise a fresh idle one.
     * The first bind in a process restores the chat's draft from disk.
     */
    fun bind(
        key: VoiceNoteKey,
        onPermissionRequest: () -> Boolean,
    ): VoiceRecordingController {
        permissionRequests[key] = onPermissionRequest
        if (recovered.add(key) && key !in controllers) recover(key)
        return controllers.getOrPut(key) { newController(key, hooks(key)) }
    }

    /** Changes when chat teardown revokes the recorder that an open conversation may still be presenting. */
    fun bindingVersion(key: VoiceNoteKey): Int = bindingVersions[key] ?: 0

    /**
     * The chat closed. A locked or paused take keeps recording, a hold in progress is kept as a draft, and an idle
     * recorder is released. A recorder still closing a take stays until it delivers the draft; it holds nothing after.
     */
    fun unbind(
        key: VoiceNoteKey,
        controller: VoiceRecordingController,
    ) {
        if (controllers[key] !== controller) return
        permissionRequests.remove(key)
        sendOnArrival.remove(key)
        when {
            controller.isRecording && !controller.locked -> controller.interrupt()
            controller.isRecording || controller.isFinalizing -> Unit
            else -> {
                controllers.remove(key)
                controller.release()
            }
        }
    }

    /** The chat's finished, unsent take, if one is waiting. */
    fun draft(key: VoiceNoteKey): VoiceDraft? = drafts[key]

    fun takeImmediateSend(
        key: VoiceNoteKey,
        file: File,
    ): Boolean = sendOnArrival.remove(key, file)

    /** True once per lost take, so the chat can explain that a recording ended with the app. */
    fun takeLost(key: VoiceNoteKey): Boolean = lost.remove(key)

    /** Whether a recording in a different chat holds the microphone. */
    fun recordingElsewhere(key: VoiceNoteKey): Boolean =
        controllers.any { (other, controller) -> other != key && (controller.isRecording || controller.isFinalizing) }

    /** A locked or paused recording stays controllable as navigation moves away. */
    fun persistentRecorder(visible: VoiceNoteKey?): VoiceRecordingController? =
        controllers.entries
            .firstOrNull { (key, recorder) ->
                key != visible && recorder.isRecording && (recorder.locked || recorder.isPaused)
            }?.value

    /** For chat rows and notices: recording, paused, a waiting draft, or nothing. */
    fun activity(key: VoiceNoteKey): VoiceNoteActivity {
        val controller = controllers[key]
        return when {
            controller?.isRecording == true && controller.isPaused -> VoiceNoteActivity.Paused
            controller?.isRecording == true -> VoiceNoteActivity.Recording
            draft(key) != null || persistedDraft(key) -> VoiceNoteActivity.Draft
            else -> VoiceNoteActivity.None
        }
    }

    /** Whether eviction readback could close capture or clear a saved take for the exact chat. */
    fun hasCaptureOrDraft(key: VoiceNoteKey): Boolean {
        val recorder = controllers[key]
        return recorder?.isFinalizing == true || activity(key) != VoiceNoteActivity.None
    }

    /** Captures the current recorder, start request and saved take so a delayed eviction cannot retire a newer one. */
    fun captureTargetIsCurrent(key: VoiceNoteKey): () -> Boolean {
        val recorder = controllers[key]
        val generation = recorder?.captureGeneration
        val file = drafts[key]?.file
        return {
            controllers[key] === recorder &&
                recorder?.captureGeneration == generation &&
                drafts[key]?.file == file
        }
    }

    /** Commits Discard for exactly [file] before clearing its presentation. */
    fun consume(
        key: VoiceNoteKey,
        file: File,
    ): Boolean {
        if (!store.delete(key, file)) return false
        if (drafts[key]?.file == file) drafts.remove(key)
        return true
    }

    /** The queue can take ownership only after the exact draft can no longer be recovered as unsent. */
    fun consumeForQueue(
        key: VoiceNoteKey,
        file: File,
    ): Boolean {
        if (drafts[key]?.file != file || !store.consumeAccepted(key, file)) return false
        drafts.remove(key)
        return true
    }

    /** The chat explained an automatic stop; it is not shown again. */
    fun acknowledgeStop(key: VoiceNoteKey) {
        val draft = drafts[key] ?: return
        drafts[key] = store.withStopReason(key, draft, null)
    }

    /** Stops the account's capture while keeping its saved takes recoverable. */
    fun stopAccountCapture(accountRef: String) {
        controllers.keys.filter { it.accountRef == accountRef }.forEach(::forget)
    }

    /** Deletes only this account's saved takes after it has been removed. */
    fun purgeAccountDrafts(accountRef: String): Boolean {
        drafts.keys.removeAll { it.accountRef == accountRef }
        lost.removeAll { it.accountRef == accountRef }
        persisted = null
        return store.purgeAccount(accountRef)
    }

    /** Revokes capture immediately and lets confirmed eviction journal its work before deleting files. */
    fun onTargetRemoved(
        accountRef: String,
        groupIdHex: String,
        beforePurge: () -> Unit = {},
    ): Boolean {
        val key = VoiceNoteKey(accountRef, groupIdHex)
        if (key in permissionRequests) bindingVersions[key] = bindingVersion(key) + 1
        forget(key)
        drafts.remove(key)
        lost.remove(key)
        recovered.add(key)
        persisted = null
        beforePurge()
        return store.purge(key)
    }

    fun foregroundRecorder(token: String): VoiceRecordingController? {
        val (key, requestedToken) = foregroundRequest ?: return null
        return controllers[key].takeIf { token == requestedToken }
    }

    private fun requestForeground(key: VoiceNoteKey) {
        val token = UUID.randomUUID().toString()
        foregroundRequest = key to token
        if (!startForeground(token)) foregroundRequest = null
    }

    /** Android silences a background microphone, so a take without foreground protection becomes a draft now. */
    fun onAppBackgrounded() {
        controllers.forEach { (key, recorder) ->
            if (recorder.isRecording && foregroundRequest?.first != key) recorder.interrupt()
        }
    }

    /** A draft left by an earlier process for a chat not opened since; the chat restores it when it binds. */
    private fun persistedDraft(key: VoiceNoteKey): Boolean {
        if (key in recovered) return false
        val names = persisted ?: store.persistedDrafts().also { persisted = it }
        return chatNames.getOrPut(key) { store.chatName(key) } in names
    }

    private fun forget(key: VoiceNoteKey) {
        permissionRequests.remove(key)
        sendOnArrival.remove(key)
        if (foregroundRequest?.first == key) foregroundRequest = null
        controllers.remove(key)?.release()
    }

    private fun recover(key: VoiceNoteKey) {
        when (val recovery = store.recover(key)) {
            is VoiceDraftRecovery.Ready -> drafts[key] = recovery.draft
            VoiceDraftRecovery.Lost -> lost += key
            VoiceDraftRecovery.None -> Unit
        }
    }

    private fun hooks(key: VoiceNoteKey) =
        VoiceRecorderHooks(
            outputDirectory = store.directory(key),
            onPermissionRequest = { prepareRecording(key) },
            onRecordingStarted = { requestForeground(key) },
            onRecordingComplete = { file, durationMs -> complete(key, file, durationMs) },
            onReleasedRecordingComplete = { file, durationMs ->
                complete(key, file, durationMs, sendImmediately = true)
            },
            onError = { error -> if (recordingElsewhere(key)) presentElsewhere() else presentError(error) },
            onAutomaticStop = { reason ->
                drafts[key]?.let { draft -> drafts[key] = store.withStopReason(key, draft, reason) }
            },
        )

    /** Honors the visible chat's send gate before requesting permission or opening a new recording file. */
    private fun prepareRecording(key: VoiceNoteKey): Boolean =
        store.canRecord(key) &&
            (permissionRequests[key]?.invoke() ?: hasMicrophonePermission()) &&
            store.recordingDirectory(key).isDirectory

    private fun complete(
        key: VoiceNoteKey,
        file: File,
        durationMs: Long,
        sendImmediately: Boolean = false,
    ) {
        val draft = store.commit(key, file, durationMs, stopReason = null)
        if (draft == null) {
            file.delete()
            presentError(VoiceRecordingSaveException())
        } else {
            if (sendImmediately && key in permissionRequests) sendOnArrival[key] = draft.file
            drafts[key] = draft
        }
    }
}
