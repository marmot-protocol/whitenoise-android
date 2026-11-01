package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import kotlinx.coroutines.withTimeoutOrNull

/** Identity-scoped presentation intent; an older completion must not retire a replacement. */
internal class OptimisticArchiveIntent(
    val bindEpoch: Long,
    val archived: Boolean,
    val ownerIsCurrent: () -> Boolean = { true },
    val completionIsCurrent: () -> Boolean = ownerIsCurrent,
)

internal class ResolvedArchiveRows(
    val rows: Collection<ChatListRowFfi>,
    val isCurrent: () -> Boolean,
    val completionIsCurrent: () -> Boolean = isCurrent,
)

/**
 * Owns transient archive overlays and batch completion for one chat-list controller.
 *
 * The controller supplies its existing bind fence, native commit adapter and row projection.
 * This owner never stores authoritative rows, opens subscriptions or retries native mutations.
 * Callers use the controller's presentation context, just as they do for its other row updates.
 */
internal class ChatListArchiveActions(
    private val rowForGroup: (String) -> ChatListRowFfi?,
    private val isCurrent: (Long) -> Boolean,
    private val onChanged: () -> Unit,
    private val updateGroup: suspend (String, String, Boolean, OptimisticArchiveIntent) -> Boolean,
    private val onFailure: (Throwable) -> Unit,
    private val onSuccess: (Boolean) -> Unit,
) {
    private val intentsByGroup = mutableMapOf<String, OptimisticArchiveIntent>()

    /** Moves the whole eligible selection before I/O and settles each intent independently. */
    suspend fun setArchived(
        account: String,
        epoch: Long,
        groupIds: Collection<String>,
        archived: Boolean,
        notify: Boolean,
    ): Int = commit(account, begin(groupIds, archived, epoch), archived, notify)

    /** Uses resolved off-window rows while preserving distinct admission and committed-completion fences. */
    @Suppress("LongParameterList") // Preserve archive arguments plus optional per-target progress observers.
    suspend fun setArchivedRows(
        account: String,
        epoch: Long,
        resolved: ResolvedArchiveRows,
        archived: Boolean,
        notify: Boolean,
        onAttempted: (String) -> Unit = {},
        onSettled: (String, Boolean) -> Unit = { _, _ -> },
        stepTimeoutMillis: Long? = null,
        onTimedOut: (String) -> Unit = {},
        notifyFailures: Boolean = true,
    ): Int {
        val intents = beginRows(resolved.rows, archived, epoch, resolved.isCurrent, resolved.completionIsCurrent)
        return commit(
            account,
            intents,
            archived,
            notify,
            onAttempted,
            onSettled,
            stepTimeoutMillis,
            onTimedOut,
            notifyFailures,
        )
    }

    /**
     * Settles each overlay by identity after its native attempt; cancellation also retires unstarted overlays.
     * Errors and success feedback remain scoped to a current presentation, independently of committed work.
     */
    @Suppress("LongParameterList") // The same archive owner carries optional attempt and settlement observers.
    private suspend fun commit(
        account: String,
        intents: List<Pair<String, OptimisticArchiveIntent>>,
        archived: Boolean,
        notify: Boolean,
        onAttempted: (String) -> Unit = {},
        onSettled: (String, Boolean) -> Unit = { _, _ -> },
        stepTimeoutMillis: Long? = null,
        onTimedOut: (String) -> Unit = {},
        notifyFailures: Boolean = true,
    ): Int {
        if (intents.isEmpty()) return 0
        var succeeded = 0
        try {
            intents.forEach { (groupIdHex, intent) ->
                val applied =
                    runCatchingCancellable {
                        if (ownsIntent(groupIdHex, intent)) {
                            onAttempted(groupIdHex)
                            val updated =
                                withArchiveDeadline(stepTimeoutMillis) {
                                    updateGroup(account, groupIdHex, archived, intent)
                                }
                            if (updated == null) onTimedOut(groupIdHex)
                            updated == true
                        } else {
                            false
                        }
                    }.onFailure { error ->
                        if (notifyFailures && isCurrent(intent.bindEpoch) && intent.ownerIsCurrent()) onFailure(error)
                    }.getOrDefault(false)
                if (applied) succeeded += 1
                finish(groupIdHex, intent)
                onSettled(groupIdHex, applied)
            }
        } finally {
            // Cancellation retires unstarted overlays without rolling back native work.
            intents.forEach { (groupIdHex, intent) -> finish(groupIdHex, intent) }
        }
        val shouldNotify = notify && succeeded > 0
        if (shouldNotify && intents.any { isCurrent(it.second.bindEpoch) && it.second.ownerIsCurrent() }) {
            onSuccess(archived)
        }
        return succeeded
    }

    /** Records only changed, retained rows; conversation actions share this same overlay owner. */
    fun begin(
        groupIds: Collection<String>,
        archived: Boolean,
        epoch: Long,
    ): List<Pair<String, OptimisticArchiveIntent>> {
        val rows = groupIds.map(String::trim).filter(String::isNotEmpty).mapNotNull(rowForGroup)
        return beginRows(rows, archived, epoch, { true }, { true })
    }

    /** Deduplicates resolved rows and publishes only changed archive intents before any native call starts. */
    private fun beginRows(
        rows: Collection<ChatListRowFfi>,
        archived: Boolean,
        epoch: Long,
        ownerIsCurrent: () -> Boolean,
        completionIsCurrent: () -> Boolean,
    ): List<Pair<String, OptimisticArchiveIntent>> {
        if (!ownerIsCurrent()) return emptyList()
        val intents =
            rows.distinctBy { it.groupIdHex.lowercase() }.mapNotNull { row ->
                val key = row.groupIdHex.lowercase()
                val currentArchived = intentsByGroup[key]?.archived ?: row.archived
                if (currentArchived == archived) return@mapNotNull null
                val intent = OptimisticArchiveIntent(epoch, archived, ownerIsCurrent, completionIsCurrent)
                intentsByGroup[key] = intent
                row.groupIdHex to intent
            }
        if (intents.isNotEmpty()) onChanged()
        return intents
    }

    /** Reveals the latest native row only if this completion still owns the current overlay. */
    fun finish(
        groupIdHex: String,
        intent: OptimisticArchiveIntent,
    ) {
        val rowKey = groupIdHex.lowercase()
        if (intentsByGroup[rowKey] === intent) {
            intentsByGroup.remove(rowKey)
            onChanged()
        }
    }

    /** Rechecks unstarted batch admission inside the native adapter's account/group lock. */
    fun ownsIntent(
        groupIdHex: String,
        intent: OptimisticArchiveIntent,
    ): Boolean {
        val ownsBinding = isCurrent(intent.bindEpoch) && intent.ownerIsCurrent()
        return ownsBinding && intentsByGroup[groupIdHex.lowercase()] === intent
    }

    /** Settles committed work only for the same binding and exact overlay identity. */
    fun ownsCompletion(
        groupIdHex: String,
        intent: OptimisticArchiveIntent,
    ): Boolean =
        isCurrent(intent.bindEpoch) &&
            intent.completionIsCurrent() &&
            intentsByGroup[groupIdHex.lowercase()] === intent

    /** Applies the temporary archive flag without changing the authoritative row. */
    fun projectRow(row: ChatListRowFfi): ChatListRowFfi =
        intentsByGroup[row.groupIdHex.lowercase()]?.let {
            row.copy(archived = it.archived)
        } ?: row

    /** Keeps the projected group flag consistent with its row while an action is pending. */
    fun projectGroup(
        groupIdHex: String,
        group: AppGroupRecordFfi?,
    ): AppGroupRecordFfi? = intentsByGroup[groupIdHex.lowercase()]?.let { group?.copy(archived = it.archived) } ?: group

    /** Drops presentation intents when the controller resets its account binding or is cleared. */
    fun clear() {
        intentsByGroup.clear()
    }
}

/** Bounds one native attempt; a timeout stays uncertain for explicit read-only recovery. */
private suspend fun withArchiveDeadline(
    timeoutMillis: Long?,
    update: suspend () -> Boolean,
): Boolean? {
    if (timeoutMillis == null) return update()
    return withTimeoutOrNull(timeoutMillis) { update() }
}
