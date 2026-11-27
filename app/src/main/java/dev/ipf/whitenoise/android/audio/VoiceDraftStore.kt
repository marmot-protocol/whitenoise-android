package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.ui.conversation.composer.voiceReviewRecoveryOwner
import org.json.JSONObject
import java.io.File

/** One chat's voice-note scope; its directory name is hashed, so no account or chat id is stored in clear. */
internal data class VoiceNoteKey(
    val accountRef: String,
    val groupIdHex: String,
)

/** A finished, unsent take waiting in review for its chat. */
internal data class VoiceDraft(
    val file: File,
    val durationMs: Long,
    /** Why capture stopped on its own, until the chat has explained it once; null for an ordinary Stop. */
    val stopReason: VoiceRecordingStopReason? = null,
)

/** What the store found for a chat that has no live recording in this process. */
internal sealed interface VoiceDraftRecovery {
    /** Nothing was waiting. */
    data object None : VoiceDraftRecovery

    /** A finished take is waiting in review. */
    data class Ready(
        val draft: VoiceDraft,
    ) : VoiceDraftRecovery

    /** The process ended mid-recording; the unfinished file had no index and was deleted. */
    data object Lost : VoiceDraftRecovery
}

/**
 * Keeps each chat's unsent voice take in private no-backup storage until it is sent or discarded, with no expiry,
 * like a text draft. Recording writes `voice-*.m4a` into the chat's directory; a finished take is renamed to
 * `take-*.m4a` beside `take.json`, so the recorder's 24-hour temp sweep never matches a draft.
 */
@Suppress("TooManyFunctions") // Keep transactions and recovery in one storage owner.
internal class VoiceDraftStore(
    private val root: File,
) {
    /** The chat's private directory, named from hashes of the account and of the account and chat. */
    fun directory(key: VoiceNoteKey): File = File(accountDirectory(key.accountRef), chatName(key))

    private fun accountDirectory(accountRef: String): File = File(root, voiceReviewRecoveryOwner(accountRef, ""))

    /** Where the recorder writes this chat's live take. */
    fun recordingDirectory(key: VoiceNoteKey): File = directory(key).apply { mkdirs() }

    /** Adopts a finalized recorder file as the chat's draft, replacing any older draft. */
    @Suppress("ReturnCount") // Failed writes stop before the next transaction step.
    fun commit(
        key: VoiceNoteKey,
        file: File,
        durationMs: Long,
        stopReason: VoiceRecordingStopReason?,
    ): VoiceDraft? {
        val dir = recordingDirectory(key)
        if (!finishConsumption(dir)) return null
        val target = File(dir, "$TAKE_PREFIX${System.nanoTime()}.$TAKE_EXTENSION")
        val draft = VoiceDraft(target, durationMs, stopReason)
        val pending = File(dir, PENDING_METADATA)
        // Journal the target before moving the audio. Recovery can finish publishing it
        // once the target exists, while an interrupted move leaves the previous draft valid.
        if (!writeMetadata(pending, draft)) return null
        if (!file.renameTo(target)) {
            pending.delete()
            return null
        }
        publishPending(dir, draft)
        return draft
    }

    /** Records why capture stopped on its own, or clears it once the chat has explained it. */
    fun withStopReason(
        key: VoiceNoteKey,
        draft: VoiceDraft,
        reason: VoiceRecordingStopReason?,
    ): VoiceDraft {
        val updated = draft.copy(stopReason = reason)
        if (draft.file.isFile) {
            val dir = directory(key)
            val index = if (readMetadata(dir, PENDING_METADATA)?.file == draft.file) PENDING_METADATA else METADATA
            writeMetadata(File(dir, index), updated)
        }
        return updated
    }

    /** Reads the chat's draft. An unfinished recorder file means the process died mid-take: it is deleted as lost. */
    fun recover(key: VoiceNoteKey): VoiceDraftRecovery {
        val dir = directory(key)
        val unfinished = dir.listFiles { file -> file.name.startsWith(RECORDING_PREFIX) }.orEmpty()
        unfinished.forEach { it.delete() }
        if (consumptionMarkers(dir).any(File::isFile)) {
            finishConsumption(dir)
            return VoiceDraftRecovery.None
        }
        val pending = readMetadata(dir, PENDING_METADATA)
        if (pending != null) publishPending(dir, pending) else File(dir, PENDING_METADATA).delete()
        val draft = pending ?: readMetadata(dir)
        return when {
            draft != null -> VoiceDraftRecovery.Ready(draft)
            unfinished.isNotEmpty() -> VoiceDraftRecovery.Lost
            else -> VoiceDraftRecovery.None
        }
    }

    /** Deletes exactly [file] when it is this chat's draft, so a stale owner can never delete a newer take. */
    fun delete(
        key: VoiceNoteKey,
        file: File,
    ): Boolean = consume(key, file, accepted = false)

    /** Retires a queued take, using its audio as the marker if creating the discard marker fails. */
    fun consumeAccepted(
        key: VoiceNoteKey,
        file: File,
    ): Boolean = consume(key, file, accepted = true)

    /** Commits consumption of the selected take before letting cleanup touch either draft index. */
    @Suppress("ReturnCount") // Reject stale drafts and failed consumption before cleanup.
    private fun consume(
        key: VoiceNoteKey,
        file: File,
        accepted: Boolean,
    ): Boolean {
        val dir = directory(key)
        val pending = readMetadata(dir, PENDING_METADATA)?.file
        val current = pending ?: readMetadata(dir)?.file
        if (current != file) return false
        val marker = File(dir, CONSUMED)
        val marked = runCatching { marker.createNewFile() || marker.isFile }.getOrDefault(false)
        if (!marked && !(accepted && runCatching { file.renameTo(File(dir, ACCEPTED)) }.getOrDefault(false))) {
            return false
        }
        finishConsumption(dir)
        return true
    }

    /** The hashed directory name of [key], as listed by [persistedDrafts]. */
    fun chatName(key: VoiceNoteKey): String = voiceReviewRecoveryOwner(key.accountRef, key.groupIdHex)

    /** Chat directories holding a finished draft, read once so chat rows can mark them without per-row disk reads. */
    fun persistedDrafts(): Set<String> =
        root
            .listFiles()
            .orEmpty()
            .flatMap { account ->
                account.listFiles().orEmpty().filter { dir ->
                    consumptionMarkers(dir).none(File::isFile) &&
                        (File(dir, METADATA).isFile || readMetadata(dir, PENDING_METADATA) != null)
                }
            }.mapTo(HashSet()) { it.name }

    /** Removes the chat's whole directory, for a chat that left the account. */
    fun purge(key: VoiceNoteKey): Boolean = directory(key).deleteRecursively()

    /** Removes every draft of a signed-out or wiped account. */
    fun purgeAccount(accountRef: String) {
        accountDirectory(accountRef).deleteRecursively()
    }

    /** Leaves the consumption markers in place until both indexes and any listed audio have been removed. */
    private fun finishConsumption(dir: File): Boolean {
        val markers = consumptionMarkers(dir)
        if (markers.none(File::isFile)) return markers.none(File::exists)
        val indexes = listOf(File(dir, METADATA), File(dir, PENDING_METADATA))
        val files = indexes + draftFiles(dir)
        files.forEach { it.delete() }
        val filesRemoved = files.none(File::exists)
        if (filesRemoved) markers.forEach { it.delete() }
        return filesRemoved && markers.none(File::exists)
    }

    /** Both markers make this directory's current and pending draft indexes ineligible for recovery. */
    private fun consumptionMarkers(dir: File): List<File> = listOf(File(dir, CONSUMED), File(dir, ACCEPTED))

    private fun draftFiles(dir: File): List<File> =
        dir
            .listFiles { file -> file.name.startsWith(TAKE_PREFIX) && file.name.endsWith(".$TAKE_EXTENSION") }
            .orEmpty()
            .toList()

    private fun publishPending(
        dir: File,
        draft: VoiceDraft,
    ) {
        if (File(dir, PENDING_METADATA).renameTo(File(dir, METADATA))) {
            draftFiles(dir).filter { it != draft.file }.forEach { it.delete() }
        }
    }

    private fun writeMetadata(
        index: File,
        draft: VoiceDraft,
    ): Boolean {
        val json =
            JSONObject()
                .put("v", 1)
                .put("state", "review")
                .put("file", draft.file.name)
                .put("durationMs", draft.durationMs)
                .put("stopReason", draft.stopReason?.name ?: JSONObject.NULL)
        val temp = File(index.parentFile, "${index.name}.tmp")
        val saved =
            runCatching {
                temp.writeText(json.toString())
                temp.renameTo(index)
            }.getOrDefault(false)
        if (!saved) temp.delete()
        return saved
    }

    private fun readMetadata(
        dir: File,
        index: String = METADATA,
    ): VoiceDraft? =
        runCatching {
            val json = JSONObject(File(dir, index).readText())
            val file = File(dir, json.getString("file"))
            val reason = json.optString("stopReason").takeIf { it.isNotEmpty() && it != "null" }
            VoiceDraft(
                file = file,
                durationMs = json.getLong("durationMs"),
                stopReason = reason?.let { name -> VoiceRecordingStopReason.entries.firstOrNull { it.name == name } },
            ).takeIf { file.isFile && it.durationMs > 0L }
        }.getOrNull()

    companion object {
        private const val CONSUMED = "take.consumed"
        private const val ACCEPTED = "take.accepted"
        private const val METADATA = "take.json"
        private const val PENDING_METADATA = "take.pending.json"
        private const val TAKE_PREFIX = "take-"
        private const val RECORDING_PREFIX = "voice-"
        private val TAKE_EXTENSION = VoiceRecorder.FILE_EXTENSION
    }
}
