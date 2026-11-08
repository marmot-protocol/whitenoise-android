package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GlobalAttachmentPresentation
import dev.ipf.whitenoise.android.core.globalAttachmentIsVideo
import dev.ipf.whitenoise.android.core.globalAttachmentPresentation
import dev.ipf.whitenoise.android.state.KeepMediaPeriod
import dev.ipf.whitenoise.android.state.RemoveOlderAge
import dev.ipf.whitenoise.android.state.StorageOutcome
import dev.ipf.whitenoise.android.state.StoredAttachment

/**
 * A byte count in the device locale's short form, such as "1.2 GB", isolated so its number and unit keep their
 * order inside text of either direction.
 */
internal fun formatStorageBytes(
    context: Context,
    bytes: Long,
): String = "\u2068${Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0))}\u2069"

/** [formatStorageBytes] for composition. */
@Composable
internal fun storageBytes(bytes: Long): String = formatStorageBytes(LocalContext.current, bytes)

/** The picker label for a Keep media window, written so it also reads inside a sentence. */
internal val KeepMediaPeriod.labelRes: Int
    @StringRes
    get() =
        when (this) {
            KeepMediaPeriod.Forever -> R.string.storage_keep_forever
            KeepMediaPeriod.OneYear -> R.string.storage_keep_one_year
            KeepMediaPeriod.SixMonths -> R.string.storage_keep_six_months
            KeepMediaPeriod.ThirtyDays -> R.string.storage_keep_thirty_days
        }

/** The menu label for one Remove older attachments age. */
internal val RemoveOlderAge.labelRes: Int
    @StringRes
    get() =
        when (this) {
            RemoveOlderAge.OneWeek -> R.string.storage_older_one_week
            RemoveOlderAge.OneMonth -> R.string.storage_older_one_month
            RemoveOlderAge.ThreeMonths -> R.string.storage_older_three_months
            RemoveOlderAge.SixMonths -> R.string.storage_older_six_months
            RemoveOlderAge.OneYear -> R.string.storage_older_one_year
        }

/** How an attachment is named and drawn when the list has no preview of it. */
internal enum class StoredAttachmentKind(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    Photo(R.string.storage_attachment_photo, R.drawable.ic_image),
    Video(R.string.storage_attachment_video, R.drawable.ic_play_arrow),
    Audio(R.string.storage_attachment_audio, R.drawable.ic_mic),
    File(R.string.storage_attachment_file, R.drawable.ic_description),
}

/** Classifies an attachment by its declared media type, an unparsed type is a file. */
internal val StoredAttachment.kind: StoredAttachmentKind
    get() =
        when (globalAttachmentPresentation(mediaType)) {
            GlobalAttachmentPresentation.VISUAL ->
                if (globalAttachmentIsVideo(mediaType)) StoredAttachmentKind.Video else StoredAttachmentKind.Photo
            GlobalAttachmentPresentation.AUDIO -> StoredAttachmentKind.Audio
            GlobalAttachmentPresentation.FILE -> StoredAttachmentKind.File
        }

/** The announcement for a finished action, or null when there is nothing to say. */
internal fun storageOutcomeText(
    context: Context,
    outcome: StorageOutcome,
): String =
    when (outcome) {
        is StorageOutcome.Removed ->
            context.resources.getQuantityString(
                R.plurals.storage_removed_freed,
                outcome.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                outcome.count,
                formatStorageBytes(context, outcome.freedBytes),
            )
        StorageOutcome.NothingRemoved -> context.getString(R.string.storage_nothing_removed)
        StorageOutcome.KeepMediaSaved -> context.getString(R.string.storage_keep_media_saved)
        is StorageOutcome.CacheCleared ->
            context.getString(R.string.storage_cache_cleared, formatStorageBytes(context, outcome.freedBytes))
        StorageOutcome.Failed -> context.getString(R.string.storage_action_failed)
    }
