package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentKeepMediaFfi
import dev.ipf.marmotkit.AttachmentMigrationStateFfi
import dev.ipf.marmotkit.AttachmentStorageUsageFfi
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** MarmotKit's Keep media windows in days, used only to order them. */
private const val KEEP_ONE_YEAR_DAYS = 365
private const val KEEP_SIX_MONTHS_DAYS = 183
private const val KEEP_THIRTY_DAYS = 30

/** The account's Keep media window, in the order the picker lists it. */
internal enum class KeepMediaPeriod(
    val ffi: AttachmentKeepMediaFfi,
    val days: Int?,
) {
    Forever(AttachmentKeepMediaFfi.FOREVER, null),
    OneYear(AttachmentKeepMediaFfi.ONE_YEAR, KEEP_ONE_YEAR_DAYS),
    SixMonths(AttachmentKeepMediaFfi.SIX_MONTHS, KEEP_SIX_MONTHS_DAYS),
    ThirtyDays(AttachmentKeepMediaFfi.THIRTY_DAYS, KEEP_THIRTY_DAYS),
    ;

    /** True when switching from [current] to this window removes attachments, so the change needs a confirmation. */
    fun shortens(current: KeepMediaPeriod): Boolean = (days ?: Int.MAX_VALUE) < (current.days ?: Int.MAX_VALUE)

    companion object {
        /** Maps MarmotKit's stored setting onto the picker's choices. */
        fun from(ffi: AttachmentKeepMediaFfi): KeepMediaPeriod = entries.first { it.ffi == ffi }
    }
}

/** Ages offered by Remove older attachments, shortest first. */
internal enum class RemoveOlderAge(
    private val days: Long = 0,
    private val months: Long = 0,
    private val years: Long = 0,
) {
    OneWeek(days = 7),
    OneMonth(months = 1),
    ThreeMonths(months = 3),
    SixMonths(months = 6),
    OneYear(years = 1),
    ;

    /**
     * The start of the local day this age reaches back to, as the instant MarmotKit compares against. It is
     * captured once when the user picks an age, so the date the confirmation names is exactly the one removed.
     */
    fun cutoff(
        now: Instant,
        zone: ZoneId,
    ): Instant =
        ZonedDateTime
            .ofInstant(now, zone)
            .minusYears(years)
            .minusMonths(months)
            .minusDays(days)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
}

/** A background change to how attachments are stored that the screen reports while it runs. */
internal enum class StorageMigration { Moving, Paused, Compacting }

/**
 * One account's storage figures in bytes. MarmotKit measures attachments, the account database and the picture
 * cache, and Android adds its own temporary decrypted copies. Every field is non-negative.
 */
internal data class StorageUsage(
    val attachmentBytes: Long,
    val attachmentCount: Long,
    val chatDataBytes: Long,
    val unusedDatabaseBytes: Long,
    val pictureCacheBytes: Long,
    val temporaryBytes: Long,
    val migration: StorageMigration? = null,
    val migrationBytes: Long = 0,
) {
    /** Everything White Noise holds for this account on the device, including temporary files all profiles share. */
    val totalBytes: Long
        get() = profileBytes + temporaryBytes

    /** What this profile alone holds on the device: every figure except the temporary files all profiles share. */
    val profileBytes: Long
        get() = attachmentBytes + chatDataBytes + unusedDatabaseBytes + pictureCacheBytes

    /** What Clear pictures and temporary files can return. */
    val clearableCacheBytes: Long get() = pictureCacheBytes + temporaryBytes

    companion object {
        /** Splits MarmotKit's figures into the categories the screen shows, saturating at zero. */
        fun from(
            ffi: AttachmentStorageUsageFfi,
            temporaryBytes: Long,
        ): StorageUsage {
            val legacy = ffi.legacyBytes.toSaturatedLong()
            val database = ffi.databaseBytes.toSaturatedLong()
            val free = ffi.databaseFreeBytes.toSaturatedLong()
            return StorageUsage(
                attachmentBytes = ffi.mediaBytes.toSaturatedLong().saturatedPlus(legacy),
                attachmentCount = ffi.attachmentCount.toSaturatedLong(),
                chatDataBytes = (database - free - legacy).coerceAtLeast(0),
                unusedDatabaseBytes = free,
                pictureCacheBytes = ffi.cacheBytes.toSaturatedLong(),
                temporaryBytes = temporaryBytes.coerceAtLeast(0),
                migration = ffi.migration.toStorageMigration(),
                migrationBytes = ffi.migrationBytes.toSaturatedLong(),
            )
        }
    }
}

/** One chat's retained attachments, content shared with another chat counts in each. */
internal data class ChatStorageEntry(
    val groupIdHex: String,
    val bytes: Long,
    val attachmentCount: Long,
)

/**
 * One retained attachment, as listed largest first. [receivedAtSeconds] is zero when unknown. The peer-supplied file
 * name is deliberately not carried, since this screen shows no file names.
 */
internal data class StoredAttachment(
    val groupIdHex: String,
    val messageIdHex: String,
    val sourceMessageIdHex: String,
    val attachmentIndex: Int,
    val bytes: Long,
    val mediaType: String,
    val receivedAtSeconds: Long,
) {
    /** Stable identity within one account, used for list keys and selection. */
    val key: String get() = "$groupIdHex|$messageIdHex|$sourceMessageIdHex|$attachmentIndex"
}

/** An opaque MarmotKit page cursor, whoever holds it closes it once the next page is read or the list is dropped. */
internal class StorageCursor(
    internal val handle: AutoCloseable,
) : AutoCloseable {
    /** Releases the native cursor. */
    override fun close() = handle.close()
}

/** One page of a largest-first list, [next] is null on the last page. */
internal data class StoragePage<T>(
    val items: List<T>,
    val next: StorageCursor?,
)

/** Maps a running, paused or compacting migration, finished or unneeded ones are null. */
private fun AttachmentMigrationStateFfi.toStorageMigration(): StorageMigration? =
    when (this) {
        AttachmentMigrationStateFfi.RUNNING -> StorageMigration.Moving
        AttachmentMigrationStateFfi.PAUSED -> StorageMigration.Paused
        AttachmentMigrationStateFfi.COMPACTING -> StorageMigration.Compacting
        AttachmentMigrationStateFfi.NOT_NEEDED, AttachmentMigrationStateFfi.COMPLETE -> null
    }

/** Converts a native byte count, clamping values beyond Long.MAX_VALUE. */
internal fun ULong.toSaturatedLong(): Long = if (this > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else toLong()

/** Adds two non-negative counts, clamping at Long.MAX_VALUE. */
private fun Long.saturatedPlus(other: Long): Long = if (Long.MAX_VALUE - this < other) Long.MAX_VALUE else this + other
