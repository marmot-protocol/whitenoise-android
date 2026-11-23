package dev.ipf.whitenoise.android.state

import androidx.annotation.StringRes
import androidx.compose.runtime.snapshotFlow
import dev.ipf.marmotkit.GroupChangeNotDeliveredFfi
import dev.ipf.marmotkit.GroupEventKindFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.OversizedArtifactFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.flow.first

/** MDK's stable refusal tag for a change whose commit or Welcome fits none of its relays. */
internal const val QUEUED_INTENT_REFUSED_TOO_LARGE = "event_too_large"

/**
 * A group change of the user's that MDK reports was not made, and what to tell them: [headline] names the change and
 * [reason] says why, or [reason] alone says both. [changeIdHex] is MDK's ID for the change, the queued send's intent or
 * the given-up commit, so a repeated delivery is shown once. MDK keeps a record of a given-up commit until it is
 * acknowledged. [commitIdHex] names that record, and [queuedIntentIdHex] names a queued send whose record, if any,
 * carries that intent.
 */
internal data class GroupChangeNotice(
    val accountRef: String,
    val groupIdHex: String,
    val changeIdHex: String,
    val headline: AppText?,
    val reason: AppText,
    val commitIdHex: String? = null,
    val queuedIntentIdHex: String? = null,
)

/**
 * The notice [event] calls for, or null when it reports nothing the user must hear about. MDK retries none of these
 * changes. A queued send the settled group refused, a commit MDK gave up after its call returned, and a commit another
 * change replaced (other than one MDK sent again) each mean the change was not made.
 */
internal fun groupChangeNotice(event: MarmotEventFfi): GroupChangeNotice? =
    when (event) {
        is MarmotEventFfi.GroupEvent -> {
            when (val kind = event.event) {
                is GroupEventKindFfi.QueuedIntentRefused ->
                    refusedChangeNotice(
                        RefusedChange(
                            accountRef = event.accountLabel,
                            groupIdHex = event.groupIdHex,
                            intentKind = kind.intentKind,
                            reason = kind.reason,
                            artifact = kind.artifact,
                        ),
                        changeIdHex = kind.intentIdHex,
                    )?.copy(queuedIntentIdHex = kind.intentIdHex)
                is GroupEventKindFfi.GroupChangeNotDelivered ->
                    refusedChangeNotice(
                        RefusedChange(event.accountLabel, event.groupIdHex, kind.intentKind, kind.reason),
                        changeIdHex = kind.commitIdHex,
                    )?.copy(commitIdHex = kind.commitIdHex)
                else -> null
            }
        }
        is MarmotEventFfi.GroupChangeSuperseded -> supersededChangeNotice(event)
        else -> null
    }

/**
 * The notice for MDK's retained [record] of a change of [accountRef]'s it gave up, or null for MDK's own upkeep. A
 * queued send's record is keyed by its intent, as its live refusal was, so one change is never shown twice.
 */
internal fun retainedGroupChangeNotice(
    accountRef: String,
    record: GroupChangeNotDeliveredFfi,
): GroupChangeNotice? =
    refusedChangeNotice(
        RefusedChange(accountRef, record.groupIdHex, record.intentKind, record.reason),
        changeIdHex = record.queuedIntentIdHex ?: record.commitIdHex,
    )?.copy(commitIdHex = record.commitIdHex)

/** One refused or undelivered change as MDK names it: the change's kind, MDK's reason tag and any oversized part. */
private data class RefusedChange(
    val accountRef: String,
    val groupIdHex: String,
    val intentKind: String,
    val reason: String,
    val artifact: OversizedArtifactFfi? = null,
)

/**
 * The notice for [change], or null for a change MDK makes for its own upkeep, which the user never asked for, whatever
 * the reason. A size refusal keeps its own sentence whatever else the change was.
 */
private fun refusedChangeNotice(
    change: RefusedChange,
    changeIdHex: String,
): GroupChangeNotice? {
    val (headline, reason) =
        when {
            change.intentKind in UPKEEP_INTENT_KINDS -> return null
            change.reason == QUEUED_INTENT_REFUSED_TOO_LARGE -> null to sizeRefusalReason(change.artifact)
            else ->
                AppText.Resource(changeHeadline(change.intentKind)) to AppText.Resource(refusalReason(change.reason))
        }
    return GroupChangeNotice(change.accountRef, change.groupIdHex, changeIdHex, headline, reason)
}

/**
 * The notice for a change another change replaced, or null when MDK sent it again and nothing was lost. It names its
 * commit as a record too: if MDK also gave that commit up, the one notice shown for it clears the record.
 */
private fun supersededChangeNotice(event: MarmotEventFfi.GroupChangeSuperseded): GroupChangeNotice? {
    val headline = AppText.Resource(changeHeadline(event.kind))
    val (title, reason) =
        when (event.outcome) {
            SUPERSEDED_REISSUED -> return null
            SUPERSEDED_ALREADY_SATISFIED -> null to R.string.group_change_already_done
            SUPERSEDED_NOT_MEMBER -> headline to R.string.group_change_reason_not_member
            else -> headline to R.string.group_change_reason_superseded
        }
    return GroupChangeNotice(
        accountRef = event.accountLabel,
        groupIdHex = event.groupIdHex,
        changeIdHex = event.commitIdHex,
        headline = title,
        reason = AppText.Resource(reason),
        commitIdHex = event.commitIdHex,
    )
}

/** The sentence for a size refusal: a refused Welcome reuses the invitation copy, anything else the change copy. */
private fun sizeRefusalReason(artifact: OversizedArtifactFfi?): AppText =
    AppText.Resource(
        if (artifact == OversizedArtifactFfi.WELCOME) {
            R.string.error_invite_welcome_too_large
        } else {
            R.string.error_group_change_too_large
        },
    )

/** What did not happen, by MDK's name for the change: its send intent, or the kind a superseded commit reports. */
@StringRes
private fun changeHeadline(kind: String): Int =
    when (kind) {
        "invite" -> R.string.group_change_invite_not_sent
        "remove_members" -> R.string.group_change_member_not_removed
        "update_group_data", "update_app_components", "group_profile", "app_components" ->
            R.string.group_change_details_not_updated
        "leave", "step_down_and_leave" -> R.string.group_change_leave_failed
        "disband", "enable_disbanding" -> R.string.group_change_end_failed
        "app_message" -> R.string.group_change_message_not_sent
        else -> R.string.group_change_not_made
    }

/** Why MDK did not make the change, by its stable reason tag; any other tag means the settled group refused it. */
@StringRes
private fun refusalReason(reason: String): Int =
    when (reason) {
        "not_group_admin" -> R.string.group_change_reason_not_admin
        "not_a_member" -> R.string.group_change_reason_not_member
        "not_delivered" -> R.string.group_change_reason_not_delivered
        "refused" -> R.string.group_change_reason_refused
        else -> R.string.group_change_reason_group_changed
    }

/**
 * Shows [notice] until the user has seen it, and returns whether they have, so only a seen change is acknowledged.
 * Another account's notice shows nothing and returns false, because its group is not on screen. A change already seen
 * in this process returns true without showing again, so an acknowledgement that failed is retried. One another call
 * is still showing returns false, because that call acknowledges it. Must run on the main thread.
 */
internal suspend fun WhiteNoiseAppState.showGroupChangeNotice(notice: GroupChangeNotice): Boolean {
    val showing = "${notice.accountRef}|${notice.changeIdHex.lowercase()}"
    return when {
        activeAccountRef != notice.accountRef || showing in showingGroupChangeNotices -> false
        shownGroupChangeNotices.contains(notice.accountRef, notice.changeIdHex) -> true
        else -> {
            showingGroupChangeNotices += showing
            try {
                showUntilSeen(notice, showing).also { seen ->
                    if (seen) shownGroupChangeNotices.firstShowing(notice.accountRef, notice.changeIdHex)
                }
            } finally {
                showingGroupChangeNotices -= showing
            }
        }
    }
}

/**
 * Presents [notice], titled with the chat's name when the chat list knows it and tagged with its [showing] key, and
 * waits until its own snackbar has run to the end, which [noteToastSeen] records. The toast has one slot, so when the
 * notice leaves the slot without that record, another toast replaced it, and the notice is presented again once the
 * slot is free. Returns false once its account is no longer active before it is seen.
 */
private suspend fun WhiteNoiseAppState.showUntilSeen(
    notice: GroupChangeNotice,
    showing: String,
): Boolean {
    val (title, detail) = groupChangeToast(chatTitle(notice.accountRef, notice.groupIdHex), notice)
    while (activeAccountRef == notice.accountRef) {
        seenGroupChangeToasts -= showing
        // Tagged with the change, so another change's notice with the same wording still replaces this one.
        presentText(ToastMessage(title, detail, groupChangeKey = showing))
        val shown = toast
        snapshotFlow { toast }.first { it !== shown }
        if (seenGroupChangeToasts.remove(showing)) return true
        snapshotFlow { toast }.first { it == null }
    }
    return false
}

/**
 * Records that [toast]'s snackbar ran to the end, so the group-change notice it carries counts as seen. A snackbar a
 * newer toast cancels never gets here. Must run on the main thread, before the toast is cleared.
 */
internal fun WhiteNoiseAppState.noteToastSeen(toast: ToastMessage) {
    toast.groupChangeKey?.let { seenGroupChangeToasts += it }
}

/**
 * The toast title and detail for [notice]: the chat's name over what happened when the chat list knows the chat,
 * otherwise what happened alone, whose wording names no particular chat.
 */
internal fun groupChangeToast(
    chat: String?,
    notice: GroupChangeNotice,
): Pair<AppText, AppText?> {
    val headline = notice.headline
    return when {
        chat != null && headline != null -> {
            val detail = AppText.Resource(R.string.group_change_notice_detail, listOf(headline, notice.reason))
            AppText.Plain(chat) to detail
        }
        chat != null -> AppText.Plain(chat) to notice.reason
        headline != null -> headline to notice.reason
        else -> notice.reason to null
    }
}

/**
 * The most recent notices already shown, by account and MDK ID, which MDK documents as the key for ignoring a repeated
 * delivery. Bounded so a long session cannot grow it without limit; an ID old enough to be evicted is no longer
 * redelivered in practice. Not thread-safe: use it from the main thread.
 */
internal class ShownQueuedRefusals(
    private val capacity: Int = SHOWN_QUEUED_REFUSALS_CAPACITY,
) {
    private val shown = LinkedHashSet<String>()

    /** Records this notice and returns whether it had not been shown yet. */
    fun firstShowing(
        accountRef: String,
        intentIdHex: String,
    ): Boolean {
        if (!shown.add(key(accountRef, intentIdHex))) return false
        if (shown.size > capacity) shown.remove(shown.first())
        return true
    }

    /** Whether this notice was shown already, without recording it. */
    fun contains(
        accountRef: String,
        intentIdHex: String,
    ): Boolean = key(accountRef, intentIdHex) in shown

    /** The record's key: IDs compare without case. */
    private fun key(
        accountRef: String,
        intentIdHex: String,
    ) = "$accountRef|${intentIdHex.lowercase()}"
}

/** Changes MDK sends for its own upkeep of a group or the account's devices, which the user never asked for. */
private val UPKEEP_INTENT_KINDS =
    setOf(
        "recover_members",
        "add_own_account_devices",
        "remove_own_account_devices",
        "self_update",
        "replace_device_entry",
        "replace_account_entry",
        "link_device",
        "unlink_device",
        "import_device_group_rejections",
        "mark_device_group_moved",
        "record_removed_slot",
    )

private const val SUPERSEDED_REISSUED = "reissued"
private const val SUPERSEDED_ALREADY_SATISFIED = "already_satisfied"
private const val SUPERSEDED_NOT_MEMBER = "not_member"
private const val SHOWN_QUEUED_REFUSALS_CAPACITY = 64
