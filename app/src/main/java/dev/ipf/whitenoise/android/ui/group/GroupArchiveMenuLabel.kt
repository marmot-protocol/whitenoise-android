package dev.ipf.whitenoise.android.ui.group

import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R

/**
 * Archive menu copy from the optimistic presentation: the presented state is
 * already the in-flight target, so the progress labels follow it directly.
 */
@StringRes
internal fun archiveMenuLabel(
    archiveMutationInFlight: Boolean,
    presentedArchived: Boolean,
): Int =
    when {
        archiveMutationInFlight && presentedArchived -> R.string.archiving_chat
        archiveMutationInFlight -> R.string.restoring_chat
        presentedArchived -> R.string.unarchive_chat
        else -> R.string.archive_chat
    }

/**
 * [archiveMenuLabel] derived from the tap-time request. The pending target is
 * recorded synchronously with the tap, so the progress label is correct even
 * in the dispatch gap before the mutation coroutine stages the optimistic
 * intent — and stays correct if a guard exits without ever staging one.
 */
@StringRes
internal fun archiveMenuLabelForTarget(
    pendingArchiveTarget: Boolean?,
    presentedArchived: Boolean,
): Int =
    archiveMenuLabel(
        archiveMutationInFlight = pendingArchiveTarget != null,
        presentedArchived = pendingArchiveTarget ?: presentedArchived,
    )
