package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.PresentationTextFfi
import java.util.Locale

/**
 * The names one locally committed rename installed and replaced.
 *
 * [superseded] accumulates every name this group has been renamed away from since the engine last
 * agreed, so a burst of renames still recognises a snapshot carrying any of the older values.
 */
private data class LocalGroupNameCommit(
    val committed: String,
    val superseded: Set<String>,
)

/**
 * Retains a just-committed group name until the engine's own projection agrees with it (#2696).
 *
 * A rename is durable the moment `updateGroupProfile` returns, but the chat-list subscription
 * settles separately, so a snapshot that was already in flight can still carry the pre-rename
 * name and undo the row the user just saw update. Records that are plainly stale — the exact name
 * the rename replaced — are answered with the committed name instead; a snapshot carrying the
 * committed name retires the record, and any *other* name is a genuinely newer authoritative
 * commit and wins outright. That keeps this a short-lived presentation patch rather than a second
 * source of truth: nothing is retained across a process restart, where MDK's durable state is
 * already correct.
 *
 * Instances belong to one account's controller, so a rename never reaches another account's rows.
 */
internal class LocalGroupNameAuthority {
    private val commitsByGroup = HashMap<String, LocalGroupNameCommit>()

    /**
     * Records a successful local rename from [previousName] to [committedName].
     *
     * A commit that does not actually change the name is ignored, so the ordinary local
     * projection path (avatar, archive, membership) never installs a retention record.
     */
    fun record(
        groupIdHex: String,
        previousName: String,
        committedName: String,
    ) {
        val key = key(groupIdHex) ?: return
        if (previousName == committedName) return
        val existing = commitsByGroup[key]
        val carried = existing?.superseded.orEmpty() + setOfNotNull(existing?.committed)
        commitsByGroup[key] = LocalGroupNameCommit(committedName, carried + previousName)
    }

    /** The name [groupIdHex] should display given an authoritative [incomingName]. */
    fun reconcile(
        groupIdHex: String,
        incomingName: String,
    ): String {
        retainedFor(groupIdHex, incomingName)?.let { return it }
        forget(groupIdHex)
        return incomingName
    }

    /**
     * The retained name to show instead of a superseded [incomingName], else null.
     *
     * Unlike [reconcile] this never retires a record, so the row and its prepared presentation can
     * be patched from one decision in either order.
     */
    fun retainedFor(
        groupIdHex: String,
        incomingName: String,
    ): String? {
        val commit = key(groupIdHex)?.let(commitsByGroup::get) ?: return null
        return commit.committed.takeIf { incomingName in commit.superseded }
    }

    /** Drops any retention record for [groupIdHex], e.g. once its row leaves the list. */
    fun forget(groupIdHex: String) {
        key(groupIdHex)?.let(commitsByGroup::remove)
    }

    private fun key(groupIdHex: String): String? =
        groupIdHex
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)
}

/**
 * [row] with the newest locally committed name applied; the same instance when nothing changed.
 *
 * The prepared row title moves with the name: it is what the chat list displays, searches, and
 * sorts by for a named group, so leaving it behind would show the rename only in the header.
 */
internal fun LocalGroupNameAuthority.reconciled(row: ChatListRowFfi): ChatListRowFfi {
    val name = reconcile(row.groupIdHex, row.groupName)
    return if (name == row.groupName) row else row.copy(groupName = name, title = name)
}

/** [presentation] retitled to a retained local rename, decided from its own [row]'s name. */
internal fun LocalGroupNameAuthority.reconciledPresentation(
    row: ChatListRowFfi,
    presentation: ConversationPresentationFfi,
): ConversationPresentationFfi {
    val retained = retainedFor(row.groupIdHex, row.groupName) ?: return presentation
    return presentation.withLocalGroupTitle(retained)
}

/** [presentation] carrying [name] as its literal title. */
internal fun ConversationPresentationFfi.withLocalGroupTitle(name: String): ConversationPresentationFfi = copy(title = PresentationTextFfi.Literal(name))

/** [record] with the newest locally committed name applied; the same instance when nothing changed. */
internal fun LocalGroupNameAuthority.reconciled(record: AppGroupRecordFfi): AppGroupRecordFfi {
    val name = reconcile(record.groupIdHex, record.name)
    return if (name == record.name) record else record.copy(name = name)
}
