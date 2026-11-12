package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.PresentationTextFfi
import java.util.Locale

/**
 * The values one locally committed edit of a group field installed and replaced.
 *
 * [superseded] accumulates every value the field has been changed away from since the engine last
 * agreed, so a burst of edits still recognises a snapshot carrying any of the older values. It
 * never contains [committed] itself. [rowAgreed] and [recordAgreed] track which streams have
 * delivered a held clear.
 */
private data class LocalGroupFieldCommit(
    val committed: String,
    val superseded: Set<String>,
    val rowAgreed: Boolean = false,
    val recordAgreed: Boolean = false,
)

/**
 * Retains a just-committed group field, the name (#2696) or the description (#3704), until the
 * engine's own projection agrees with it. One instance tracks one field.
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
 *
 * [holdClearAfterAgreement] keeps a cleared value fenced until both the reconciled row stream and
 * the group-record stream have delivered it. Only a value that a second, independently settling
 * stream can still restore needs it.
 */
internal class LocalGroupFieldAuthority(
    private val holdClearAfterAgreement: Boolean = false,
) {
    private val commitsByGroup = HashMap<String, LocalGroupFieldCommit>()

    /**
     * Records a successful local edit of the field from [previous] to [committed].
     *
     * A commit that does not actually change the value is ignored, so the ordinary local
     * projection path (avatar, archive, membership) never installs a retention record.
     */
    fun record(
        groupIdHex: String,
        previous: String,
        committed: String,
    ) {
        val key = key(groupIdHex) ?: return
        if (previous == committed) return
        val existing = commitsByGroup[key]
        val carried = existing?.superseded.orEmpty() + setOfNotNull(existing?.committed)
        // Changing back to an earlier value must not leave that value marked superseded, or the
        // snapshot that agrees with the new commit would be read as stale and never retire it (#3703).
        commitsByGroup[key] = LocalGroupFieldCommit(committed, carried + previous - committed)
    }

    /** The value [groupIdHex] should display given an authoritative [incoming] value. */
    fun reconcile(
        groupIdHex: String,
        incoming: String,
    ): String {
        val retained = retainedFor(groupIdHex, incoming)
        if (retained == null) agree(groupIdHex, incoming, fromRecord = false, retireOtherwise = true)
        return retained ?: incoming
    }

    /**
     * The value a group record should carry given its authoritative [incoming] value.
     *
     * The record stream answers a superseded value like [reconcile], but only a value other than
     * the committed one ends the fence there; plain agreement is left to the chat-list row.
     */
    fun reconcileRecord(
        groupIdHex: String,
        incoming: String,
    ): String {
        val retained = retainedFor(groupIdHex, incoming)
        if (retained == null) agree(groupIdHex, incoming, fromRecord = true, retireOtherwise = false)
        return retained ?: incoming
    }

    /**
     * Settles the commit for a non-superseded [incoming] value. A held clear retires once both
     * streams delivered it; any other value retires on a row, or on a record that disagrees.
     */
    private fun agree(
        groupIdHex: String,
        incoming: String,
        fromRecord: Boolean,
        retireOtherwise: Boolean,
    ) {
        val key = key(groupIdHex) ?: return
        val commit = commitsByGroup[key] ?: return
        val agrees = incoming == commit.committed
        when {
            agrees && holdClearAfterAgreement && commit.committed.isBlank() -> {
                val next =
                    if (fromRecord) commit.copy(recordAgreed = true) else commit.copy(rowAgreed = true)
                if (next.rowAgreed && next.recordAgreed) commitsByGroup.remove(key) else commitsByGroup[key] = next
            }
            !agrees || retireOtherwise -> commitsByGroup.remove(key)
        }
    }

    /**
     * The retained value to show instead of a superseded [incoming], else null.
     *
     * Unlike [reconcile] this never retires a record, so the row and its prepared presentation can
     * be patched from one decision in either order.
     */
    fun retainedFor(
        groupIdHex: String,
        incoming: String,
    ): String? {
        val commit = key(groupIdHex)?.let(commitsByGroup::get) ?: return null
        return commit.committed.takeIf { incoming in commit.superseded }
    }

    /** Drops any retention record for [groupIdHex], e.g. once its row leaves the list. */
    fun forget(groupIdHex: String) {
        key(groupIdHex)?.let(commitsByGroup::remove)
    }

    /** The case-folded map key for [groupIdHex], or null when there is no usable id. */
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
internal fun LocalGroupFieldAuthority.reconciled(row: ChatListRowFfi): ChatListRowFfi {
    val name = reconcile(row.groupIdHex, row.groupName)
    return if (name == row.groupName) row else row.copy(groupName = name, title = name)
}

/** [presentation] retitled to a retained local rename, decided from its own [row]'s name. */
internal fun LocalGroupFieldAuthority.reconciledPresentation(
    row: ChatListRowFfi,
    presentation: ConversationPresentationFfi,
): ConversationPresentationFfi {
    val retained = retainedFor(row.groupIdHex, row.groupName) ?: return presentation
    return presentation.withLocalGroupTitle(retained)
}

/** [presentation] carrying [name] as its literal title, or the unnamed-group title once cleared. */
internal fun ConversationPresentationFfi.withLocalGroupTitle(name: String): ConversationPresentationFfi {
    val title =
        if (name.isBlank()) {
            PresentationTextFfi.UnnamedGroup(memberCount = null)
        } else {
            PresentationTextFfi.Literal(name)
        }
    return copy(title = title)
}

/** [record] with the newest locally committed name applied; the same instance when nothing changed. */
internal fun LocalGroupFieldAuthority.reconciled(record: AppGroupRecordFfi): AppGroupRecordFfi {
    val name = reconcile(record.groupIdHex, record.name)
    return if (name == record.name) record else record.copy(name = name)
}
