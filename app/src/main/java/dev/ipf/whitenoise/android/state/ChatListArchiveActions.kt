package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi

/** Identity-scoped presentation intent; an older completion must not retire a replacement. */
internal class OptimisticArchiveIntent(
    val bindEpoch: Long,
    val archived: Boolean,
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
    ): Int {
        val intents = begin(groupIds, archived, epoch)
        if (intents.isEmpty()) return 0
        var succeeded = 0
        try {
            intents.forEach { (groupIdHex, intent) ->
                val applied =
                    runCatchingCancellable {
                        updateGroup(account, groupIdHex, archived, intent)
                    }.onFailure { error ->
                        if (isCurrent(epoch)) onFailure(error)
                    }.getOrDefault(false)
                if (applied) succeeded += 1
                finish(groupIdHex, intent)
            }
        } finally {
            // Cancellation retires unstarted overlays without rolling back native work.
            intents.forEach { (groupIdHex, intent) -> finish(groupIdHex, intent) }
        }
        if (notify && succeeded > 0 && isCurrent(epoch)) onSuccess(archived)
        return succeeded
    }

    /** Records only changed, retained rows; conversation actions share this same overlay owner. */
    fun begin(
        groupIds: Collection<String>,
        archived: Boolean,
        epoch: Long,
    ): List<Pair<String, OptimisticArchiveIntent>> {
        val intents =
            groupIds
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinctBy { it.lowercase() }
                .mapNotNull { groupIdHex ->
                    val rowKey = groupIdHex.lowercase()
                    val currentRow = rowForGroup(groupIdHex) ?: return@mapNotNull null
                    val currentArchived = intentsByGroup[rowKey]?.archived ?: currentRow.archived
                    if (currentArchived == archived) return@mapNotNull null
                    val intent = OptimisticArchiveIntent(bindEpoch = epoch, archived = archived)
                    intentsByGroup[rowKey] = intent
                    groupIdHex to intent
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
        if (ownsIntent(groupIdHex, intent)) {
            intentsByGroup.remove(rowKey)
            onChanged()
        }
    }

    /** Rechecks unstarted batch admission inside the native adapter's account/group lock. */
    fun ownsIntent(
        groupIdHex: String,
        intent: OptimisticArchiveIntent,
    ): Boolean = isCurrent(intent.bindEpoch) && intentsByGroup[groupIdHex.lowercase()] === intent

    /** Applies the temporary archive flag without changing the authoritative row. */
    fun projectRow(row: ChatListRowFfi): ChatListRowFfi = intentsByGroup[row.groupIdHex.lowercase()]?.let { row.copy(archived = it.archived) } ?: row

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
