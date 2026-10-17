package dev.ipf.whitenoise.android.state

import android.content.Context
import android.util.Log
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONObject

/**
 * How wide a WorkManager network constraint is, for the constraints automatic work uses: [NetworkType.CONNECTED]
 * admits any network, [NetworkType.NOT_ROAMING] drops roaming, and [NetworkType.UNMETERED] admits only unmetered
 * ones. A smaller rank is wider, except that [NetworkType.NOT_ROAMING] and [NetworkType.UNMETERED] are not
 * ordered — each admits a network the other refuses, a metered home network or an unmetered roaming one.
 */
internal fun NetworkType.automaticWidthRank(): Int =
    when (this) {
        NetworkType.CONNECTED -> 0
        NetworkType.NOT_ROAMING -> 1
        NetworkType.UNMETERED -> 2
        else -> 0
    }

/**
 * Whether [requested] admits a network this constraint refuses, so work waiting on this one should take it.
 * [NetworkType.UNMETERED] admits unmetered roaming networks that [NetworkType.NOT_ROAMING] refuses, so it counts
 * here too, just as [NetworkType.NOT_ROAMING] does over [NetworkType.UNMETERED] by rank.
 */
internal fun NetworkType.isNarrowerThan(requested: NetworkType): Boolean =
    requested.automaticWidthRank() < automaticWidthRank() ||
        (this == NetworkType.NOT_ROAMING && requested == NetworkType.UNMETERED)

/**
 * Automatic downloads queued on a network narrower than [NetworkType.CONNECTED], so a policy that later allows
 * a wider network can widen them without a new receipt (#3308).
 *
 * WorkManager cannot rebuild a waiting spec by itself: [WorkInfo] exposes no input data, and the unique names
 * and tags are digests so they carry no attachment identity. Widening needs the full request, so this keeps one
 * record per narrowed spec, keyed by its unique work name. Requests are attachment identifiers, so the records
 * live in their own Keystore-sealed file rather than in tags, names or plain preferences. Only narrowed automatic
 * work is recorded, a record is dropped once its work finishes, and a sweep prunes any whose work no longer waits,
 * so the file stays as small as the narrowed backlog. One process-wide instance serialises every read-modify-write.
 */
internal class AutomaticAttachmentNetworkWaits(
    private val records: AttachmentInstallerHandoffRecordStore,
) {
    /** One recorded wait: the request, its policy row and the network it was last queued on. */
    data class Wait(
        val request: AttachmentTransferRequest,
        val type: MediaAutoDownloadType,
        val networkType: NetworkType,
    )

    /** Records [request] as automatic work of [type] waiting on [networkType], or forgets it on any network. */
    fun record(
        request: AttachmentTransferRequest,
        type: MediaAutoDownloadType,
        networkType: NetworkType,
    ) {
        if (networkType == NetworkType.CONNECTED) return forget(request)
        update { it + (attachmentDownloadWorkName(request) to encode(Wait(request, type, networkType))) }
    }

    /** Drops [request]'s record, once its work finished or no longer waits on a narrowed network. */
    fun forget(request: AttachmentTransferRequest) {
        val name = attachmentDownloadWorkName(request)
        update { if (name in it) it - name else it }
    }

    /** The recorded waits for [accountRef]. */
    fun waiting(accountRef: String): List<Wait> {
        val all = synchronized(this) { records.readAll() }
        return all.values.mapNotNull(::decode).filter { it.request.accountRef == accountRef }
    }

    /**
     * Widens every live automatic spec of [accountRef] whose recorded type [networkTypeFor] now allows on a wider
     * network, in place by id so its attempt count and backoff stay. A waiting spec takes the wider network at
     * once, and a running one for its next run, so an interruption after the policy widened still waits on the
     * wider network. Retired, explicit and finished work is left alone. A record moves to the new network only once
     * every live spec is at least that wide, so a failed update keeps it for the next refresh, and a record whose
     * work has finished is pruned. Blocks on WorkManager, so callers run it off the main thread. Returns how many
     * specs were widened.
     */
    fun widen(
        manager: WorkManager,
        accountRef: String,
        networkTypeFor: (MediaAutoDownloadType) -> NetworkType,
    ): Int {
        var widened = 0
        waiting(accountRef).forEach { wait ->
            val infos = manager.getWorkInfosForUniqueWork(attachmentDownloadWorkName(wait.request)).get()
            val target = networkTypeFor(wait.type)
            val live = infos.filter { it.isLiveAutomatic(accountRef) }
            var allWide = true
            live.filter { it.constraints.requiredNetworkType.isNarrowerThan(target) }.forEach { info ->
                val work =
                    AttachmentDownloadWorker.buildRequest(
                        wait.request,
                        AttachmentDownloadPriority.Automatic,
                        info.id,
                        target,
                    )
                runCatching { manager.updateWork(work).get() }
                    .onSuccess { result ->
                        if (result == WorkManager.UpdateResult.NOT_APPLIED) allWide = false else widened += 1
                    }.onFailure {
                        allWide = false
                        Log.w(TAG, "attachment_network_widening_failed")
                    }
            }
            when {
                infos.none { !it.state.isFinished } -> forget(wait.request)
                allWide && wait.networkType.isNarrowerThan(target) -> record(wait.request, wait.type, target)
            }
        }
        return widened
    }

    /** Applies [change] to the sealed record under the process-wide lock. */
    private fun update(change: (Map<String, String>) -> Map<String, String>) {
        synchronized(this) {
            val current = records.readAll()
            val next = change(current)
            if (next != current && !records.replaceAllDurably(next)) Log.w(TAG, "attachment_network_wait_unsaved")
        }
    }

    companion object {
        private const val TAG = "DMAttachmentWorker"
        private const val SECURE_FILE = "whitenoise.attachment_network_waits.keystore"
        private const val KEY_ALIAS = "whitenoise.attachment_network_waits.aes_gcm.v1"

        @Volatile
        private var instance: AutomaticAttachmentNetworkWaits? = null

        /** The process-wide registry, sealed with its own Keystore key. */
        fun get(context: Context): AutomaticAttachmentNetworkWaits =
            instance ?: synchronized(this) {
                instance ?: AutomaticAttachmentNetworkWaits(
                    EncryptedAttachmentInstallerHandoffRecordStore.create(context, SECURE_FILE, KEY_ALIAS),
                ).also { instance = it }
            }

        /** Installs [registry] in place of the sealed one, or restores it with null, for tests. */
        internal fun installForTest(registry: AutomaticAttachmentNetworkWaits?) {
            synchronized(this) { instance = registry }
        }

        /** Encodes one wait as a small JSON object inside the sealed record. */
        private fun encode(wait: Wait): String =
            JSONObject()
                .put("account", wait.request.accountRef)
                .put("group", wait.request.groupIdHex)
                .put("message", wait.request.messageIdHex)
                .put("index", wait.request.attachmentIndex)
                .put("source", wait.request.sourceMessageIdHex ?: JSONObject.NULL)
                .put("type", wait.type.preferenceKey)
                .put("network", wait.networkType.name)
                .toString()

        /** Decodes one recorded wait, dropping any that is malformed. */
        private fun decode(value: String): Wait? =
            runCatching {
                val json = JSONObject(value)
                Wait(
                    request =
                        AttachmentTransferRequest(
                            accountRef = json.getString("account"),
                            groupIdHex = json.getString("group"),
                            messageIdHex = json.getString("message"),
                            attachmentIndex = json.getInt("index"),
                            sourceMessageIdHex = json.optString("source").takeUnless { json.isNull("source") },
                        ),
                    type = checkNotNull(MediaAutoDownloadType.fromKey(json.getString("type"))),
                    networkType = NetworkType.valueOf(json.getString("network")),
                )
            }.getOrNull()

        /**
         * Whether this is live automatic work of [accountRef], waiting or running, not a retired or explicit spec. A
         * running spec is included because Android can still interrupt it and run it again under its constraint.
         */
        private fun WorkInfo.isLiveAutomatic(accountRef: String): Boolean =
            (state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING) &&
                attachmentAutomaticAccountTag(accountRef) in tags &&
                AttachmentDownloadWorker.RETIRED_TAG !in tags
    }
}
