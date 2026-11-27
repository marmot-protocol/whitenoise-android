package dev.ipf.whitenoise.android.state

import android.util.AtomicFile
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupEventKindFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
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

private const val INITIAL_VOICE_PURGE_RETRY_MS = 1_000L
private const val MAX_VOICE_PURGE_RETRY_MS = 60_000L

/** Persists failed evictions beside the app's draft storage so they survive a restart or a read-only draft dir. */
internal class VoiceDraftEvictionJournal(
    private val directory: File,
) {
    /** Commits the exact account and chat under a hashed filename before the retry can be lost. */
    @Suppress("TooGenericExceptionCaught") // Release AtomicFile's temporary output on any failed write.
    fun stage(key: VoiceNoteKey): Boolean =
        runCatching {
            check(directory.isDirectory || directory.mkdirs())
            val data =
                JSONObject()
                    .put("account", key.accountRef)
                    .put("group", key.groupIdHex)
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
    fun pending(): Set<VoiceNoteKey> =
        directory
            .list()
            .orEmpty()
            .map { it.removeSuffix(".bak") }
            .filter { it.endsWith(".json") }
            .distinct()
            .mapNotNull(::read)
            .toSet()

    /** Drops a completed request only if no committed version remains on disk. */
    fun finish(key: VoiceNoteKey): Boolean =
        runCatching {
            val file = fileFor(key)
            file.delete()
            !file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()
        }.getOrDefault(false)

    /** Rejects missing or damaged records and any contents that disagree with their hashed identity. */
    private fun read(name: String): VoiceNoteKey? =
        runCatching {
            val value = JSONObject(String(AtomicFile(File(directory, name)).readFully(), Charsets.UTF_8))
            val key = VoiceNoteKey(value.getString("account"), value.getString("group"))
            key.takeIf { fileFor(it).baseFile.name == name }
        }.getOrNull()

    /** Uses the same private chat identity as voice draft paths. */
    private fun fileFor(key: VoiceNoteKey): AtomicFile {
        val identity = voiceReviewRecoveryOwner(key.accountRef, key.groupIdHex)
        return AtomicFile(File(directory, "$identity.json"))
    }
}

/** Retries only still-evicted targets without waiting for new group events or a profile switch. */
internal class VoiceDraftEvictionCleanup(
    private val journal: VoiceDraftEvictionJournal,
    private val scope: CoroutineScope,
    private val isEvicted: suspend (VoiceNoteKey) -> Boolean?,
    private val purge: (VoiceNoteKey) -> Boolean,
) {
    /** The same request must own the key when its asynchronous membership read comes back. */
    private class Request(
        var persisted: Boolean,
    )

    private val pending = journal.pending().associateWithTo(mutableMapOf()) { Request(persisted = true) }
    private var worker: Job? = null

    /** Records eviction before touching the audio and retains the request if the journal is unwritable. */
    fun stage(key: VoiceNoteKey) {
        val persisted = pending[key]?.persisted == true || journal.stage(key)
        pending[key] = Request(persisted)
    }

    /** Returns whether an authoritative projection can retry this exact chat's purge. */
    fun contains(key: VoiceNoteKey): Boolean = key in pending

    /** Removes this request after a purge or after the native runtime confirms the chat is active again. */
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
        if (pending.isEmpty() || worker?.isActive == true) return
        worker = scope.launch { retryPending() }
    }

    /** Backs off repeated failures while the runtime or the filesystem is temporarily unavailable. */
    private suspend fun retryPending() {
        var pauseMillis = INITIAL_VOICE_PURGE_RETRY_MS
        while (pending.isNotEmpty()) {
            delay(pauseMillis)
            pending.keys.toList().forEach { retry(it) }
            pauseMillis = (pauseMillis * 2).coerceAtMost(MAX_VOICE_PURGE_RETRY_MS)
        }
    }

    /** Rechecks membership and request ownership after suspension before touching private audio. */
    private suspend fun retry(key: VoiceNoteKey) {
        val request = pending[key] ?: return
        if (!request.persisted) request.persisted = journal.stage(key)
        val evicted = isEvicted(key) ?: return
        if (pending[key] === request && (!evicted || purge(key))) complete(key)
    }
}

/** Restores the app-owned voice eviction backlog independently of the notification or chat streams. */
internal fun WhiteNoiseAppState.createVoiceDraftEvictionCleanup(): VoiceDraftEvictionCleanup =
    VoiceDraftEvictionCleanup(
        journal = VoiceDraftEvictionJournal(appContext.noBackupFilesDir.resolve("voice-draft-evictions")),
        scope = mutationsScope,
        isEvicted = { key -> marmotOrNull()?.let { readCaptureEviction(key, it, allowMissing = true) } },
        purge = { key -> releaseCaptureTarget(key.accountRef, key.groupIdHex) },
    )

/** Commits or journals private cleanup for the exact chat that native membership marked as evicted. */
internal fun WhiteNoiseAppState.releaseEvictedCaptureTarget(
    account: String,
    groupIdHex: String,
) {
    val key = VoiceNoteKey(account, groupIdHex)
    releaseCaptureTarget(account, groupIdHex) { voiceDraftEvictions.stage(key) }
    voiceDraftEvictions.resume()
}

/** Releases capture on an authoritative eviction and retries a purge while the group still reports removal. */
internal fun WhiteNoiseAppState.releaseEvictedCapture(
    account: String?,
    previous: AppGroupRecordFfi?,
    current: AppGroupRecordFfi,
) {
    if (account == null || !current.selfMembership.isGroupEviction()) return
    if (previous?.selfMembership?.isGroupEviction() != true ||
        voiceDraftEvictions.contains(VoiceNoteKey(account, current.groupIdHex))
    ) {
        releaseEvictedCaptureTarget(account, current.groupIdHex)
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
    if (readCaptureEviction(key, runtime) == true) releaseEvictedCaptureTarget(account, group)
}

/** Only a recorded eviction can use confirmed absence of the targeted native row to authorize a purge. */
private suspend fun WhiteNoiseAppState.readCaptureEviction(
    key: VoiceNoteKey,
    runtime: MarmotInterface,
    allowMissing: Boolean = false,
): Boolean? {
    if (marmotOrNull() !== runtime) return null
    val (account, group) = key
    val epoch = runtimeGeneration
    val eviction =
        runCatchingCancellable {
            withContext(Dispatchers.IO) { runtime.chatListRow(account, group)?.selfMembership }
        }.fold(
            onSuccess = { membership -> membership?.isGroupEviction() ?: allowMissing },
            onFailure = { failure ->
                val removedAccount =
                    failure is MarmotKitException.UnknownAccount &&
                        accountListLoaded &&
                        accounts.none { it.label == account }
                true.takeIf { allowMissing && removedAccount }
            },
        )
    // Native membership belongs to this account and chat even if another profile was selected during readback.
    return eviction?.takeIf { epoch == runtimeGeneration && marmotOrNull() === runtime }
}
