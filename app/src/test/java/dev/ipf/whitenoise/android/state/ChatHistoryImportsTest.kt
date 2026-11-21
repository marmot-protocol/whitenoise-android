package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.BackupHistorySourceFfi
import dev.ipf.marmotkit.ChatHistoryImportFfi
import dev.ipf.marmotkit.ChatHistoryImportStateFfi
import dev.ipf.marmotkit.HistoryImportProvenanceFfi
import dev.ipf.marmotkit.HistoryImportSourceFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** History transfer is shown as MDK reports it, and imported rows are labelled by where MDK says they came from. */
class ChatHistoryImportsTest {
    /** A read publishes each chat's state under its group id, whatever its letter case. */
    @Test
    fun aReadPublishesEachChatsState() =
        runTest {
            val imports = ChatHistoryImports(ACCOUNT, readImports = { listOf(ChatHistoryImportFfi("ABCD", RECEIVING)) })

            imports.refresh()

            assertEquals(RECEIVING, imports.state("abcd"))
            assertNull(imports.state("other"))
        }

    /** A failed read keeps what is shown, so a transfer in progress does not vanish. */
    @Test
    fun aFailedReadKeepsTheShownState() =
        runTest {
            var fail = false
            val imports =
                ChatHistoryImports(
                    ACCOUNT,
                    readImports = {
                        if (fail) throw MarmotKitException.TransportClosed()
                        listOf(ChatHistoryImportFfi("group", RECEIVING))
                    },
                )
            imports.refresh()
            fail = true

            imports.refresh()

            assertEquals(RECEIVING, imports.state("group"))
        }

    /** A chat MDK no longer lists has no state. */
    @Test
    fun aChatMdkStoppedListingHasNoState() =
        runTest {
            var listed = listOf(ChatHistoryImportFfi("group", RECEIVING))
            val imports = ChatHistoryImports(ACCOUNT, readImports = { listed })
            imports.refresh()
            listed = emptyList()

            imports.refresh()

            assertNull(imports.state("group"))
        }

    /** A read that finishes after the runtime changed is dropped. */
    @Test
    fun aReadAfterTheOwnerChangedIsDropped() =
        runTest {
            var owned = true
            val imports =
                ChatHistoryImports(
                    ACCOUNT,
                    readImports = {
                        owned = false
                        listOf(ChatHistoryImportFfi("group", RECEIVING))
                    },
                    owns = { owned },
                )

            imports.refresh()

            assertTrue(imports.byGroup.isEmpty())
        }

    /** Only this account's history-import event triggers a re-read. */
    @Test
    fun onlyThisAccountsEventRereads() =
        runTest {
            var reads = 0
            val imports =
                ChatHistoryImports(
                    ACCOUNT,
                    readImports = {
                        reads++
                        emptyList()
                    },
                )
            val events =
                mutableListOf<MarmotEventFfi?>(
                    MarmotEventFfi.ChatHistoryImportsChanged("id", ACCOUNT),
                    MarmotEventFfi.ChatHistoryImportsChanged("id", "other"),
                    MarmotEventFfi.LinkedDevicesChanged("id", ACCOUNT),
                    null,
                )

            imports.observe { events.removeFirst() }

            assertEquals(2, reads)
        }

    /** Receiving and retrying are transfers under way; an import that settled, or none, is not. */
    @Test
    fun onlyAnUnsettledImportIsATransfer() {
        assertEquals(ChatHistoryTransfer.Receiving, chatHistoryTransfer(RECEIVING))
        assertEquals(ChatHistoryTransfer.Retrying, chatHistoryTransfer(ChatHistoryImportStateFfi.Receiving(true)))
        assertNull(chatHistoryTransfer(imported("slot", "Tablet")))
        assertNull(chatHistoryTransfer(null))
    }

    /** A live row has no source. */
    @Test
    fun aLiveRowHasNoSource() {
        assertNull(importedHistorySource(row("live"), null))
        assertNull(importedHistorySource(null, null))
    }

    /** A row imported from a device is labelled with the name the row carries. */
    @Test
    fun aDeviceRowUsesItsOwnLabel() {
        assertEquals(ImportedHistorySource.Device("Tablet"), importedHistorySource(deviceRow("slot", "Tablet"), null))
    }

    /** A rename of the source device shows through the chat's current import state without re-reading rows. */
    @Test
    fun aRenamedSourceDeviceUsesItsCurrentName() {
        val source = importedHistorySource(deviceRow("slot", "Tablet"), imported("slot", "Desk tablet"))

        assertEquals(ImportedHistorySource.Device("Desk tablet"), source)
    }

    /** The current import state names a row's device only when it is the same device. */
    @Test
    fun anotherDevicesImportStateDoesNotRenameTheRow() {
        val source = importedHistorySource(deviceRow("slot", "Tablet"), imported("other-slot", "Laptop"))

        assertEquals(ImportedHistorySource.Device("Tablet"), source)
    }

    /** A device MDK has no name for is another device, not a blank label. */
    @Test
    fun anUnnamedSourceIsAnotherDevice() {
        assertEquals(ImportedHistorySource.AnotherDevice, importedHistorySource(deviceRow("slot", ""), null))
        assertEquals(
            ImportedHistorySource.AnotherDevice,
            importedHistorySource(deviceRow("slot", "Tablet"), imported("slot", "")),
        )
    }

    /** A row restored from a backup says so and is never shown as an unnamed device. */
    @Test
    fun aBackupRowIsLabelledAsABackup() {
        val backup = BackupHistorySourceFfi("key", "backup", "head", 1u)
        val record = row("restored").copy(historyImportProvenance = HistoryImportProvenanceFfi.Backup(backup))

        assertEquals(ImportedHistorySource.Backup, importedHistorySource(record, null))
    }

    /** The label is drawn where imported rows give way to a live row, and nowhere else. */
    @Test
    fun theBoundaryIsWhereImportedRowsEnd() {
        val imported = deviceRow("slot", "Tablet")
        val live = row("live")

        assertEquals(ImportedHistorySource.Device("Tablet"), importedHistoryBoundary(imported, live, null))
        assertNull(importedHistoryBoundary(imported, deviceRow("slot", "Tablet"), null))
        assertNull(importedHistoryBoundary(live, row("live-2"), null))
        assertNull(importedHistoryBoundary(live, imported, null))
        assertNull(importedHistoryBoundary(null, imported, null))
    }

    /** Imported rows from two sources are separated by the first source's label. */
    @Test
    fun aChangeOfSourceIsAlsoABoundary() {
        val first = deviceRow("slot-a", "Tablet")
        val second = deviceRow("slot-b", "Laptop")

        assertEquals(ImportedHistorySource.Device("Tablet"), importedHistoryBoundary(first, second, null))
    }

    private companion object {
        const val ACCOUNT = "account"
        val RECEIVING = ChatHistoryImportStateFfi.Receiving(false)

        /** A settled import from the device in [slot] with its current [label]. */
        fun imported(
            slot: String,
            label: String,
        ) = ChatHistoryImportStateFfi.Imported(HistoryImportSourceFfi(slot, label), 1u)

        /** A live row. */
        fun row(id: String): TimelineMessageRecordFfi = timelineRecord(messageId = id, timelineAt = 1uL)

        /** A row imported from the device in [slot], carrying [label]. */
        fun deviceRow(
            slot: String,
            label: String,
        ) = row("imported-$slot").copy(
            importedFrom = HistoryImportSourceFfi(slot, label),
            historyImportProvenance = HistoryImportProvenanceFfi.Device(slot),
        )
    }
}
