package dev.ipf.whitenoise.android.state

import android.util.AtomicFile
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupEventKindFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.audio.VoiceDraftStore
import dev.ipf.whitenoise.android.audio.VoiceNoteKey
import dev.ipf.whitenoise.android.ui.conversation.composer.voiceReviewRecoveryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

private const val INITIAL_VOICE_PURGE_RETRY_MS = 1_000L
private const val MAX_VOICE_PURGE_RETRY_MS = 60_000L

/** Persists voice retirement and eviction readbacks independently of the chat's own draft directory. */
internal class VoiceDraftEvictionJournal(
    private val directory: File,
) {
    /** Commits the exact account and chat under a hashed filename before the retry can be lost. */
    @Suppress("TooGenericExceptionCaught") // Release AtomicFile's temporary output on any failed write.
    fun stage(
        key: VoiceNoteKey,
        confirmed: Boolean,
    ): Boolean =
        runCatching {
            check(directory.isDirectory || directory.mkdirs())
            val data =
                JSONObject()
                    .put("account", key.accountRef)
                    .put("group", key.groupIdHex)
                    .put("confirmed", confirmed)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            val file = fileFor(key)
            val output = file.startWrite()
            try {
                output.write(data)
                output.fd.sync()
            } catch (failure: Throwable) {
                file.failWrite(output)
                throw failure
            }
            file.finishWrite(output)
            file.readFully().contentEquals(data)
        }.getOrDefault(false)

    /** Reads committed records, including records left with an AtomicFile backup. */
    fun pending(): Map<VoiceNoteKey, Boolean> =
        directory
            .list()
            .orEmpty()
            .map { it.removeSuffix(".bak") }
            .filter { it.endsWith(".json") }
            .distinct()
            .mapNotNull(::read)
            .toMap()

    /** Drops a completed request only if no committed version remains on disk. */
    fun finish(key: VoiceNoteKey): Boolean =
        runCatching {
            val file = fileFor(key)
            val backup = File(file.baseFile.path + ".bak")
            file.delete()
            Files.notExists(file.baseFile.toPath()) && Files.notExists(backup.toPath())
        }.getOrDefault(false)

    /** Rejects missing or damaged records and any contents that disagree with their hashed identity. */
    private fun read(name: String): Pair<VoiceNoteKey, Boolean>? =
        runCatching {
            val record = AtomicFile(File(directory, name))
            val value = JSONObject(String(record.readFully(), Charsets.UTF_8))
            val key = VoiceNoteKey(value.getString("account"), value.getString("group"))
            check(fileFor(key).baseFile.name == name)
            key to value.optBoolean("confirmed")
        }.getOrNull()

    /** Uses the same private chat identity as voice draft paths. */
    private fun fileFor(key: VoiceNoteKey): AtomicFile {
        val identity = voiceReviewRecoveryOwner(key.accountRef, key.groupIdHex)
        return AtomicFile(File(directory, "$identity.json"))
    }
}

/** Keeps removed chats' old directories out of recovery and retries their deletion across process restarts. */
internal class VoiceDraftEvictionCleanup(
    private val journal: VoiceDraftEvictionJournal,
    draftRoot: File,
    private val scope: CoroutineScope,
    private val isEvicted: suspend (VoiceNoteKey) -> Boolean?,
    private val releaseEvictedTarget: (VoiceNoteKey) -> Unit,
) {
    /** The same request must own the key when its asynchronous membership read comes back. */
    private class Request(
        val confirmed: Boolean,
        var persisted: Boolean,
    )

    private val pending = journal.pending().mapValuesTo(mutableMapOf()) { (_, confirmed) -> Request(confirmed, true) }
    private var worker: Job? = null
    val draftStore = VoiceDraftStore(draftRoot, ::blocksDraft)

    /** Fences the active path for confirmed removal and reports whether the request is on disk. */
    fun stage(
        key: VoiceNoteKey,
        confirmed: Boolean,
    ): Boolean {
        val previous = pending[key]
        if (previous?.confirmed == true && !confirmed) return previous.persisted
        val persisted =
            (previous != null && previous.confirmed == confirmed && previous.persisted) ||
                journal.stage(key, confirmed)
        pending[key] = Request(confirmed, persisted)
        return persisted
    }

    /** Includes an outstanding membership read as well as an unisolated, confirmed eviction. */
    fun contains(key: VoiceNoteKey): Boolean = key in pending

    /** Blocks draft recovery and recording until the confirmed removal's directory has been isolated. */
    private fun blocksDraft(chatName: String): Boolean {
        val confirmed = pending.asSequence().filter { (_, request) -> request.confirmed }
        return confirmed.any { (key, _) -> draftStore.chatName(key) == chatName }
    }

    /** Reports whether the old directory is out of the active path and can be swept separately. */
    fun isolate(key: VoiceNoteKey): Boolean {
        val isolated = draftStore.quarantine(key)
        if (isolated) complete(key)
        return isolated
    }

    /** Drops the fence once the live path is clean, or clears a readback that found active membership. */
    fun complete(key: VoiceNoteKey) {
        if (key in pending && journal.finish(key)) pending.remove(key)
    }

    /** Removes cleanup records after account-wide draft storage has been deleted. */
    fun completeAccount(account: String) {
        pending.keys.filter { it.accountRef == account }.forEach(::complete)
        resume()
    }

    /** Starts one process-owned worker for the restored backlog or the first newly failed eviction. */
    fun resume() {
        if (worker?.isActive == true) return
        worker = scope.launch { retryPending() }
    }

    /** Backs off repeated failures while the runtime or the filesystem is temporarily unavailable. */
    private suspend fun retryPending() {
        var pauseMillis = INITIAL_VOICE_PURGE_RETRY_MS
        do {
            delay(pauseMillis)
            pending.keys.toList().forEach { retry(it) }
            val purged = draftStore.purgeQuarantined()
            pauseMillis = (pauseMillis * 2).coerceAtMost(MAX_VOICE_PURGE_RETRY_MS)
        } while (pending.isNotEmpty() || !purged)
    }

    /** Rechecks membership and request ownership after suspension before touching private audio. */
    private suspend fun retry(key: VoiceNoteKey) {
        val request = pending[key] ?: return
        if (!request.persisted) request.persisted = journal.stage(key, request.confirmed)
        if (request.confirmed) {
            isolate(key)
        } else {
            val evicted = isEvicted(key) ?: return
            if (pending[key] === request) {
                if (evicted) releaseEvictedTarget(key) else complete(key)
            }
        }
    }
}

/** Restores the app-owned voice eviction backlog independently of the notification or chat streams. */
internal fun WhiteNoiseAppState.createVoiceDraftEvictionCleanup(): VoiceDraftEvictionCleanup =
    VoiceDraftEvictionCleanup(
        journal = VoiceDraftEvictionJournal(appContext.noBackupFilesDir.resolve("voice-draft-evictions")),
        draftRoot = appContext.noBackupFilesDir.resolve("voice-drafts"),
        scope = mutationsScope,
        isEvicted = { key -> marmotOrNull()?.let { readCaptureEviction(key, it) } },
        releaseEvictedTarget = { key -> releaseCaptureTarget(key.accountRef, key.groupIdHex) },
    )

/** Releases capture when a group projection first reports an authoritative eviction. */
internal fun WhiteNoiseAppState.releaseEvictedCapture(
    account: String?,
    previous: AppGroupRecordFfi?,
    current: AppGroupRecordFfi,
) {
    if (account == null || !current.selfMembership.isGroupEviction()) return
    if (previous?.selfMembership?.isGroupEviction() != true) {
        releaseCaptureTarget(account, current.groupIdHex)
    }
}

/** Schedules readback for a group update that may end capture while its conversation or chat stream is closed. */
internal fun WhiteNoiseAppState.checkCaptureEvictionOnRuntimeEvent(
    event: MarmotEventFfi,
    runtime: MarmotInterface,
    scope: CoroutineScope,
) {
    val key =
        when (event) {
            is MarmotEventFfi.GroupStateUpdated -> VoiceNoteKey(event.accountLabel, event.groupIdHex)
            is MarmotEventFfi.GroupEvent ->
                if (event.event is GroupEventKindFfi.LocalGroupCopyTerminated) {
                    VoiceNoteKey(event.accountLabel, event.groupIdHex)
                } else {
                    null
                }
            else -> null
        } ?: return
    scope.launch(Dispatchers.Main.immediate) { reconcileCaptureEviction(key, runtime) }
}

/** On the capture owner's dispatcher, confirms native membership before revoking capture or purging its draft. */
internal suspend fun WhiteNoiseAppState.reconcileCaptureEviction(
    key: VoiceNoteKey,
    runtime: MarmotInterface,
) {
    val (account, group) = key
    if (!voiceNotes.hasCaptureOrDraft(key) &&
        !conversationDictation.isOwnedBy(account, group) &&
        !voiceDraftEvictions.contains(key)
    ) {
        return
    }
    val evicted =
        readCaptureEviction(key, runtime) {
            voiceDraftEvictions.stage(key, confirmed = false)
            voiceDraftEvictions.resume()
        }
    if (evicted == true) releaseCaptureTarget(account, group)
}

/** Authorizes removal only for the current capture and defers failed or superseded eviction reads. */
private suspend fun WhiteNoiseAppState.readCaptureEviction(
    key: VoiceNoteKey,
    runtime: MarmotInterface,
    onUnresolved: () -> Unit = {},
): Boolean? {
    if (marmotOrNull() !== runtime) return null
    val (account, group) = key
    val epoch = runtimeGeneration
    val captureIsCurrent = voiceNotes.captureTargetIsCurrent(key)
    val eviction =
        runCatchingCancellable {
            withContext(Dispatchers.IO) {
                runtime.chatListRow(account, group)?.selfMembership?.isGroupEviction() == true
            }
        }.getOrNull()
    // Selection may change, but this read cannot retire a newer recording or draft for the same chat.
    val runtimeIsCurrent = epoch == runtimeGeneration && marmotOrNull() === runtime
    val sameCapture = captureIsCurrent()
    val needsReadback = eviction == null || (eviction && !sameCapture)
    if (runtimeIsCurrent && needsReadback) onUnresolved()
    return eviction?.takeIf { runtimeIsCurrent && sameCapture }
}
