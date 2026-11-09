package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentMigrationStateFfi
import dev.ipf.marmotkit.AttachmentStorageUsageFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Storage figures, Keep media ordering and Remove older cutoffs. */
class ManageStorageModelsTest {
    /** Legacy bodies count as attachments, and chat data excludes both free pages and legacy bodies. */
    @Test fun usageSplitsTheDatabaseHonestly() {
        val native = ffi(media = 700, legacy = 100, database = 500, free = 50, cache = 30)
        val usage = StorageUsage.from(native, temporaryBytes = 20)
        assertEquals(800, usage.attachmentBytes)
        assertEquals(350, usage.chatDataBytes)
        assertEquals(50, usage.unusedDatabaseBytes)
        assertEquals(30, usage.pictureCacheBytes)
        assertEquals(1_250, usage.totalBytes)
        assertNull(usage.migration)
    }

    /** Inconsistent native figures saturate at zero instead of going negative. */
    @Test fun usageNeverGoesNegative() {
        val native = ffi(media = 0, legacy = 900, database = 100, free = 50, cache = 0)
        val usage = StorageUsage.from(native, temporaryBytes = -5)
        assertEquals(0, usage.chatDataBytes)
        assertEquals(0, usage.temporaryBytes)
    }

    /** Running, paused and compacting migrations are reported, finished ones are not. */
    @Test fun migrationStatesMap() {
        fun migration(state: AttachmentMigrationStateFfi) = StorageUsage.from(ffi(migration = state), 0).migration
        assertEquals(StorageMigration.Moving, migration(AttachmentMigrationStateFfi.RUNNING))
        assertEquals(StorageMigration.Paused, migration(AttachmentMigrationStateFfi.PAUSED))
        assertEquals(StorageMigration.Compacting, migration(AttachmentMigrationStateFfi.COMPACTING))
        assertNull(migration(AttachmentMigrationStateFfi.COMPLETE))
    }

    /** Only a strictly shorter window asks for confirmation. */
    @Test fun onlyShorterKeepWindowsShorten() {
        assertTrue(KeepMediaPeriod.ThirtyDays.shortens(KeepMediaPeriod.Forever))
        assertTrue(KeepMediaPeriod.SixMonths.shortens(KeepMediaPeriod.OneYear))
        assertFalse(KeepMediaPeriod.Forever.shortens(KeepMediaPeriod.ThirtyDays))
        assertFalse(KeepMediaPeriod.OneYear.shortens(KeepMediaPeriod.OneYear))
    }

    /** A cutoff is the start of the local day the age reaches back to. */
    @Test fun cutoffsStartAtLocalMidnight() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = Instant.parse("2026-11-08T15:30:00Z")
        assertEquals(Instant.parse("2026-10-31T23:00:00Z"), RemoveOlderAge.OneWeek.cutoff(now, zone))
        assertEquals(Instant.parse("2026-08-07T22:00:00Z"), RemoveOlderAge.ThreeMonths.cutoff(now, zone))
        assertEquals(Instant.parse("2025-11-07T23:00:00Z"), RemoveOlderAge.OneYear.cutoff(now, zone))
    }

    /** Native counts beyond Long.MAX_VALUE clamp instead of wrapping negative. */
    @Test fun saturatedConversionClamps() {
        assertEquals(Long.MAX_VALUE, ULong.MAX_VALUE.toSaturatedLong())
        assertEquals(5L, 5uL.toSaturatedLong())
    }

    /** Clearing caches announces freed bytes from the change in removable usage, and none when a reading is missing. */
    @Test fun describeMeasuresFreedBytes() {
        val before = StorageUsage(1_000, 4, 200, 0, 50, 10)
        val after = before.copy(pictureCacheBytes = 0, temporaryBytes = 0)
        assertEquals(StorageOutcome.CacheCleared(60), describe(StorageAction.ClearCaches, 0, before, after))
        assertEquals(StorageOutcome.CacheCleared(null), describe(StorageAction.ClearCaches, 0, null, after))
        assertEquals(StorageOutcome.Removed(2, null), describe(StorageAction.RemoveChat("a"), 2, before, null))
    }

    /** A removal announces only the attachment space it returned, not temporary copies dropped alongside it. */
    @Test fun removalFreedBytesIgnoreTemporaryCopies() {
        val before = StorageUsage(1_000, 4, 200, 0, 50, 300)
        val after = before.copy(attachmentBytes = 600, temporaryBytes = 0)
        assertEquals(StorageOutcome.Removed(2, 400), describe(StorageAction.RemoveChat("a"), 2, before, after))
    }

    @Suppress("LongParameterList")
    /** Native usage figures with zero defaults. */
    private fun ffi(
        media: Long = 0,
        legacy: Long = 0,
        database: Long = 0,
        free: Long = 0,
        cache: Long = 0,
        migration: AttachmentMigrationStateFfi = AttachmentMigrationStateFfi.NOT_NEEDED,
    ) =
        AttachmentStorageUsageFfi(
            mediaBytes = media.toULong(),
            legacyBytes = legacy.toULong(),
            attachmentCount = 0u,
            databaseBytes = database.toULong(),
            databaseFreeBytes = free.toULong(),
            migration = migration,
            migrationBytes = 0u,
            cacheBytes = cache.toULong(),
        )
}
