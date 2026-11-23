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
    Video(R.string.storage_attachment_video, R.drawable.ic_videocam),
    Audio(R.string.storage_attachment_audio, R.drawable.ic_voice_note),
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
        is StorageOutcome.Removed -> removedText(context, outcome.count, outcome.freedBytes)
        StorageOutcome.NothingRemoved -> context.getString(R.string.storage_nothing_removed)
        StorageOutcome.KeepMediaSaved -> context.getString(R.string.storage_keep_media_saved)
        is StorageOutcome.CacheCleared ->
            outcome.freedBytes
                ?.let { context.getString(R.string.storage_cache_cleared, formatStorageBytes(context, it)) }
                ?: context.getString(R.string.storage_caches_cleared)
        StorageOutcome.Failed -> context.getString(R.string.storage_action_failed)
    }

/** "Removed N attachments", with the space freed when it could be measured. */
private fun removedText(
    context: Context,
    count: Long,
    freedBytes: Long?,
): String {
    val quantity = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return if (freedBytes == null) {
        context.resources.getQuantityString(R.plurals.storage_removed, quantity, count)
    } else {
        val freed = formatStorageBytes(context, freedBytes)
        context.resources.getQuantityString(R.plurals.storage_removed_freed, quantity, count, freed)
    }
}

/** Longest file name a row keeps, long enough for any real name and short enough to bound layout work. */
private const val STORAGE_FILE_NAME_MAX_CHARS = 120

/**
 * The sender's file name made safe to show: only the last path segment, without control, bidirectional override or
 * other invisible characters, trimmed and bounded. Null when nothing readable is left, so the row falls back to the
 * attachment's kind. The name comes from another device and is never trusted for layout or direction.
 */
internal fun storageDisplayName(raw: String): String? {
    val cleaned = storageNameCodePoints(raw.substringAfterLast('/').substringAfterLast('\\'))
    // A name of only dots, marks or spaces would draw as nothing, so it reads as no name at all.
    val readable = cleaned.codePoints().anyMatch { it.isVisibleNameCharacter() } && !cleaned.all { it == '.' }
    return if (readable) boundedStorageName(cleaned) else null
}

/**
 * Keeps the code points of [segment] that draw: controls become spaces, format, unassigned and default-ignorable
 * points go, and runs of spaces collapse. Whole code points are filtered, so a supplementary format character such as
 * a Unicode tag cannot slip through as a surrogate pair.
 */
private fun storageNameCodePoints(segment: String): String =
    buildString {
        segment.codePoints().forEach { point ->
            when {
                Character.isISOControl(point) -> append(' ')
                Character.getType(point) in StorageNameDroppedTypes || point.isDefaultIgnorable() -> Unit
                else -> appendCodePoint(point)
            }
        }
    }.replace(Regex("\\s+"), " ").trim()

/** Keeps both ends of an over-long name, so it still shows how it starts and its extension. */
private fun boundedStorageName(name: String): String {
    if (name.length <= STORAGE_FILE_NAME_MAX_CHARS) return name
    val half = STORAGE_FILE_NAME_MAX_CHARS / 2
    val head = name.take(half).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
    val tail = name.takeLast(half).let { if (it.first().isLowSurrogate()) it.drop(1) else it }
    return "$head…$tail"
}

/** Character types a file name never shows: invisible formatting, unassigned and lone surrogate code points. */
private val StorageNameDroppedTypes =
    setOf(Character.FORMAT.toInt(), Character.UNASSIGNED.toInt(), Character.SURROGATE.toInt())

/** Default-ignorable code points outside the format category that draw as nothing: fillers and variation selectors. */
@Suppress("MagicNumber") // Unicode code points, named by the property they share.
private val StorageNameIgnorables =
    listOf(
        0x034F..0x034F,
        0x115F..0x1160,
        0x17B4..0x17B5,
        0x180B..0x180F,
        0x3164..0x3164,
        0xFE00..0xFE0F,
        0xFFA0..0xFFA0,
        0xE0100..0xE01EF,
    )

private fun Int.isDefaultIgnorable(): Boolean = StorageNameIgnorables.any { this in it }

/** Marks that only decorate their neighbour, so a name made of them alone draws nothing. */
private val StorageNameMarkTypes =
    setOf(
        Character.NON_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
    )

/** A character that draws on its own: not a space, a blank Braille cell or a mark. */
private fun Int.isVisibleNameCharacter(): Boolean {
    val mark = Character.getType(this) in StorageNameMarkTypes
    return !mark && !Character.isWhitespace(this) && this != BRAILLE_PATTERN_BLANK
}

/** A symbol that draws as an empty cell, so it never counts as a readable character on its own. */
private const val BRAILLE_PATTERN_BLANK = 0x2800
