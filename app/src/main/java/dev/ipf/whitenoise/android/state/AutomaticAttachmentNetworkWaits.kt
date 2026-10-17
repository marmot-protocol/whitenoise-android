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
    /**
     * One recorded wait: the request, its policy row, the network it was last queued on, and when it was first
     * registered, so a record whose work never appeared can be pruned once [REGISTRATION_GRACE_MS] has passed.
     */
    data class Wait(
        val request: AttachmentTransferRequest,
        val type: MediaAutoDownloadType,
        val networkType: NetworkType,
        val registeredAtMs: Long = 0L,
    )

    /** Records [request] as automatic work of [type] waiting on [networkType], or forgets it on any network. */
    fun record(
        request: AttachmentTransferRequest,
        type: MediaAutoDownloadType,
        networkType: NetworkType,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (networkType == NetworkType.CONNECTED) return forget(request)
        val name = attachmentDownloadWorkName(request)
        update { current ->
            val since = current[name]?.let(::decode)?.registeredAtMs ?: nowMs
            current + (name to encode(Wait(request, type, networkType, since)))
        }
    }

    /**
     * Registers [request] before its automatic work is enqueued on the narrowed [networkType], so a policy that
     * widens while the enqueue is still landing, or a process that dies before it lands, always leaves a record for
     * a later sweep to reconcile. An existing record is kept, since its spec's network is settled after the
     * enqueue. Returns whether this call created the record, so a failed enqueue can withdraw it.
     */
    fun register(
        request: AttachmentTransferRequest,
        type: MediaAutoDownloadType,
        networkType: NetworkType,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (networkType == NetworkType.CONNECTED) return false
        val name = attachmentDownloadWorkName(request)
        var created = false
        update { current ->
            if (name in current) {
                current
            } else {
                created = true
                current + (name to encode(Wait(request, type, networkType, nowMs)))
            }
        }
        return created
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
     * network, through [settle], the rule a duplicate receipt follows too. Blocks on WorkManager, so callers run it
     * off the main thread. Returns how many specs were widened.
     */
    fun widen(
        manager: WorkManager,
        accountRef: String,
        networkTypeFor: (MediaAutoDownloadType) -> NetworkType,
    ): Int = widenAll(manager) { account, type -> networkTypeFor(type).takeIf { account == accountRef } }

    /**
     * Widens every recorded wait whose account and type [networkTypeFor] answers, reading the sealed records once
     * for the whole sweep rather than once per account. A null answer skips that wait. Blocks on WorkManager, so
     * callers run it off the main thread. Returns how many specs were widened.
     */
    fun widenAll(
        manager: WorkManager,
        networkTypeFor: (String, MediaAutoDownloadType) -> NetworkType?,
    ): Int {
        val all = synchronized(this) { records.readAll() }.values.mapNotNull(::decode)
        return all.sumOf { wait ->
            val target = networkTypeFor(wait.request.accountRef, wait.type) ?: return@sumOf 0
            settle(manager, wait.request, wait.type, target, wait.networkType, registeredAtMs = wait.registeredAtMs)
        }
    }

    /**
     * Records the outcome of a receipt for [request] once its KEEP enqueue has settled: the spec it kept, waiting or
     * running, takes [networkType] when that is wider, through [settle]. A [type] of null only widens, since such a
     * caller cannot name the policy row a record needs. Blocks on WorkManager, so it runs off WorkManager's threads.
     */
    fun settleReceipt(
        manager: WorkManager,
        request: AttachmentTransferRequest,
        type: MediaAutoDownloadType?,
        networkType: NetworkType,
    ) {
        val recorded = synchronized(this) { records.readAll()[attachmentDownloadWorkName(request)] }?.let(::decode)
        val settledType = type ?: recorded?.type
        // Reconcile against the policy as it is now, not as it was when the receipt was read: a policy that widened
        // while this enqueue was landing must not leave the spec on the older, narrower network.
        val current = settledType?.let { policyNetworkType?.invoke(request.accountRef, it) }
        settle(
            manager,
            request,
            settledType,
            current ?: networkType,
            recorded?.networkType,
            recordable = type != null,
            registeredAtMs = recorded?.registeredAtMs,
        )
    }

    /**
     * The one widening rule for a recorded wait or a receipt. Every live automatic spec of [request] narrower than
     * [target] is updated in place by id, keeping its attempt count and backoff: a waiting spec takes the wider
     * network at once and a running one for its next run, so an interruption still waits on the wider network.
     * Retired, explicit and finished work is left alone. The record then follows the live specs: an existing one
     * moves to [target] only once every one of them is at least that wide, so a failed or unapplied update keeps the
     * [recorded] network for the next refresh; a new one records the narrowest network a live spec still waits on;
     * and a record is pruned only when no unfinished work remains. Returns how many specs were widened.
     */
    @Suppress("LongParameterList") // Each input is one fact of the same settle decision.
    private fun settle(
        manager: WorkManager,
        request: AttachmentTransferRequest,
        type: MediaAutoDownloadType?,
        target: NetworkType,
        recorded: NetworkType?,
        recordable: Boolean = true,
        registeredAtMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Int =
        synchronized(constraintLock) {
            // Under the one constraint lock, the live policy wins over the target the caller read earlier, so an
            // older sweep or receipt that finishes after a newer one can never write back an outdated network.
            val live = type?.let { policyNetworkType?.invoke(request.accountRef, it) } ?: target
            val stored = synchronized(this) { records.readAll()[attachmentDownloadWorkName(request)] }?.let(::decode)
            settleLocked(
                manager,
                request,
                type,
                live,
                stored?.networkType ?: recorded,
                recordable,
                stored?.registeredAtMs ?: registeredAtMs,
                nowMs,
            )
        }

    /**
     * Narrows the automatic work [workId] of [request] for a run its policy deferred, to the network its live policy
     * allows [type] on now, or [captured] when that is unknown. It shares the constraint lock with every widening,
     * so a settings change that lands while the worker is narrowing is applied after it, not overwritten by it, and
     * the wait is recorded so a later sweep can widen it again.
     */
    fun narrow(
        manager: WorkManager,
        request: AttachmentTransferRequest,
        type: MediaAutoDownloadType?,
        workId: java.util.UUID,
        captured: NetworkType,
    ) {
        synchronized(constraintLock) {
            val target = type?.let { policyNetworkType?.invoke(request.accountRef, it) } ?: captured
            manager.updateWork(AttachmentDownloadWorker.buildRequest(request, AttachmentDownloadPriority.Automatic, workId, target)).get()
            if (type != null) record(request, type, target)
        }
    }

    /** [settle]'s decision, run while the constraint lock is held. */
    @Suppress("LongParameterList") // The same facts as [settle], resolved under its lock.
    private fun settleLocked(
        manager: WorkManager,
        request: AttachmentTransferRequest,
        type: MediaAutoDownloadType?,
        target: NetworkType,
        recorded: NetworkType?,
        recordable: Boolean,
        registeredAtMs: Long?,
        nowMs: Long,
    ): Int {
        val infos = manager.getWorkInfosForUniqueWork(attachmentDownloadWorkName(request)).get()
        val live = infos.filter { it.isLiveAutomatic(request.accountRef) }
        // The network each live spec waits on once this pass is done: the target where the update applied.
        val effective =
            live.map { info ->
                val current = info.constraints.requiredNetworkType
                if (current.isNarrowerThan(target) && widenInPlace(manager, request, info, target)) target else current
            }
        val widened = live.indices.count { effective[it] != live[it].constraints.requiredNetworkType }
        val allWide = effective.none { it.isNarrowerThan(target) }
        val waitsOn = effective.firstOrNull { it != NetworkType.CONNECTED }
        when {
            // Registration precedes the enqueue, so a record whose work has not appeared yet is kept for a grace
            // period, and pruned only once that work finished or never landed.
            infos.isEmpty() && registeredAtMs != null && nowMs - registeredAtMs < REGISTRATION_GRACE_MS -> Unit
            infos.none { !it.state.isFinished } -> forget(request)
            type == null || !recordable && recorded == null -> Unit
            recorded == null -> if (waitsOn != null) record(request, type, waitsOn)
            allWide && recorded.isNarrowerThan(target) -> record(request, type, target)
        }
        return widened
    }

    /** Updates one live spec of [request] to [target] by id, reporting whether WorkManager applied it. */
    private fun widenInPlace(
        manager: WorkManager,
        request: AttachmentTransferRequest,
        info: WorkInfo,
        target: NetworkType,
    ): Boolean {
        val work = AttachmentDownloadWorker.buildRequest(request, AttachmentDownloadPriority.Automatic, info.id, target)
        return runCatching { manager.updateWork(work).get() != WorkManager.UpdateResult.NOT_APPLIED }
            .onFailure { Log.w(TAG, "attachment_network_widening_failed") }
            .getOrDefault(false)
    }

    /** Serialises every constraint write, widening or narrowing, so overlapping updates apply in order. */
    private val constraintLock = Any()

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

        /** How long a registered wait may exist before its work appears, far longer than any enqueue takes. */
        internal const val REGISTRATION_GRACE_MS = 10 * 60 * 1000L

        /**
         * The network the account's live policy allows a type on now, installed by the app state, so a receipt
         * settles against the current policy rather than the one read before its enqueue. Null before it is set.
         */
        @Volatile
        internal var policyNetworkType: ((String, MediaAutoDownloadType) -> NetworkType?)? = null

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
                .put("since", wait.registeredAtMs)
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
                    registeredAtMs = json.optLong("since", 0L),
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
