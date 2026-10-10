package dev.ipf.whitenoise.android.ui.conversation.media

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class FileBackedPicksTest {
    @get:Rule
    val temporary = TemporaryFolder()

    /** The per-file ceiling stays at the 512 MiB ciphertext every in-memory receiver accepts, minus one tag. */
    @Test
    fun perFileCeilingIsTheInMemoryReceiveLimitMinusOneTag() {
        assertEquals(512L * 1024L * 1024L, FILE_BACKED_ATTACHMENT_MAX_BYTES + FILE_BACKED_TAG_BYTES)
    }

    /** A pick within the ceiling becomes an exact private snapshot whose length is the copied byte count. */
    @Test
    fun stagedPickIsAnExactPrivateSnapshot() {
        val budget = budget(perFileBytes = 1_000, batchCiphertextBytes = 10_000)
        val bytes = ByteArray(700) { (it % 251).toByte() }

        val pick = budget.stage(declaredSize = bytes.size.toLong()) { ByteArrayInputStream(bytes) }

        val source = checkNotNull(pick.source)
        assertNull(pick.failure)
        assertEquals(700L, source.byteCount)
        assertArrayEquals(bytes, source.file.readBytes())
        source.close()
        assertFalse(source.file.exists())
    }

    /** A declared size above the ceiling is refused before the provider stream is opened. */
    @Test
    fun declaredOversizeIsRefusedWithoutOpeningTheProvider() {
        val budget = budget(perFileBytes = 100, batchCiphertextBytes = 10_000)
        var opened = false

        val pick = budget.stage(declaredSize = 101) { ByteArrayInputStream(ByteArray(1)).also { opened = true } }

        assertEquals(FileBackedPickFailure.TOO_LARGE, pick.failure)
        assertFalse(opened)
        assertEquals(0, stagedFiles(budget).size)
    }

    /** A provider that under-declares its size still stops at the ceiling and leaves no partial snapshot. */
    @Test
    fun undeclaredOversizeStopsAtTheCeilingAndLeavesNoFile() {
        val budget = budget(perFileBytes = 100, batchCiphertextBytes = 10_000)

        val pick = budget.stage(declaredSize = -1) { ByteArrayInputStream(ByteArray(101)) }

        assertEquals(FileBackedPickFailure.TOO_LARGE, pick.failure)
        assertEquals(0, stagedFiles(budget).size)
    }

    /** Every accepted item spends its bytes plus one AEAD tag of the message's ciphertext bound. */
    @Test
    fun chargesSpendTheMessageBoundIncludingOneTagPerItem() {
        val budget = budget(perFileBytes = 1_000, batchCiphertextBytes = 1_100)
        assertEquals(1_000L, budget.nextPickMaxBytes())

        budget.charge(500)

        assertEquals(1_100L - 516L - FILE_BACKED_TAG_BYTES, budget.nextPickMaxBytes())
        budget.charge(budget.nextPickMaxBytes())
        assertEquals(0L, budget.nextPickMaxBytes())
        val spent = budget.stage(declaredSize = 1) { ByteArrayInputStream(ByteArray(1)) }
        assertEquals(FileBackedPickFailure.TOO_LARGE, spent.failure)
    }

    /** A known size that would not leave room for the native copies is refused before any copy starts. */
    @Test
    fun declaredSizeWithoutRoomForTheNativeCopiesReportsStorage() {
        val budget = budget(perFileBytes = 1_000, batchCiphertextBytes = 10_000, usableBytes = 64L * 1024L * 1024L)
        var opened = false

        val pick = budget.stage(declaredSize = 10) { ByteArrayInputStream(ByteArray(10)).also { opened = true } }

        assertEquals(FileBackedPickFailure.STORAGE, pick.failure)
        assertFalse(opened)
    }

    /** An unknown size is rechecked after the copy; a snapshot that leaves no room for MDK's copies is deleted. */
    @Test
    fun unknownSizeWithoutRoomAfterCopyDeletesTheSnapshot() {
        val budget = budget(perFileBytes = 1_000, batchCiphertextBytes = 10_000, usableBytes = 64L * 1024L * 1024L + 15)

        val pick = budget.stage(declaredSize = -1) { ByteArrayInputStream(ByteArray(10)) }

        assertEquals(FileBackedPickFailure.STORAGE, pick.failure)
        assertEquals(0, stagedFiles(budget).size)
    }

    /** Empty and unreadable providers keep their own failure kinds. */
    @Test
    fun emptyAndUnreadableProvidersKeepTheirFailureKinds() {
        val budget = budget(perFileBytes = 1_000, batchCiphertextBytes = 10_000)

        assertEquals(FileBackedPickFailure.EMPTY, budget.stage(-1) { ByteArrayInputStream(ByteArray(0)) }.failure)
        assertEquals(FileBackedPickFailure.UNREADABLE, budget.stage(-1) { null }.failure)
    }

    /** Only snapshots no queued send adopted are deleted; adopted ones stay for their retained upload. */
    @Test
    fun releaseDeletesOnlyUnadoptedSnapshots() =
        runTest {
            val budget = budget(perFileBytes = 1_000, batchCiphertextBytes = 10_000)
            val adopted = checkNotNull(budget.stage(3) { ByteArrayInputStream(ByteArray(3)) }.source)
            val dropped = checkNotNull(budget.stage(3) { ByteArrayInputStream(ByteArray(3)) }.source)

            releaseUnadoptedStagedSources(listOf(adopted, dropped), setOf(adopted))

            assertTrue(adopted.file.exists())
            assertFalse(dropped.file.exists())
            adopted.close()
        }

    /** Builds a budget staging into a private test directory with generous default free space. */
    private fun budget(
        perFileBytes: Long,
        batchCiphertextBytes: Long,
        usableBytes: Long = Long.MAX_VALUE / 4,
    ): FileBackedPickBudget =
        FileBackedPickBudget(
            directory = File(temporary.root, "upload_sources"),
            limits = FileBackedSendLimits(perFileBytes, batchCiphertextBytes),
            usableBytes = { usableBytes },
        )

    /** Lists the snapshots currently left in a budget's staging directory. */
    private fun stagedFiles(budget: FileBackedPickBudget): List<File> =
        budget.directory
            .listFiles()
            ?.toList()
            .orEmpty()

    /** Every snapshot a budget keeps is reported as it is created; one refused after the copy is not. */
    @Test
    fun keptSnapshotsAreReportedAndRefusedOnesAreNot() {
        val reported = mutableListOf<StagedUploadSource>()
        val budget =
            FileBackedPickBudget(
                directory = File(temporary.root, "upload_sources"),
                limits = FileBackedSendLimits(perFileBytes = 1_000, batchCiphertextBytes = 10_000),
                usableBytes = { Long.MAX_VALUE / 4 },
                onStaged = reported::add,
            )
        val tight =
            FileBackedPickBudget(
                directory = File(temporary.root, "tight"),
                limits = FileBackedSendLimits(perFileBytes = 1_000, batchCiphertextBytes = 10_000),
                usableBytes = { 64L * 1024L * 1024L + 15 },
                onStaged = reported::add,
            )

        val kept = checkNotNull(budget.stage(3) { ByteArrayInputStream(ByteArray(3)) }.source)
        assertEquals(FileBackedPickFailure.STORAGE, tight.stage(-1) { ByteArrayInputStream(ByteArray(10)) }.failure)

        assertEquals(listOf(kept), reported)
        kept.close()
    }
}
