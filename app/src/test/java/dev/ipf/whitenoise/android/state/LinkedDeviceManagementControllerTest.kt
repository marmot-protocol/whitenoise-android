package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device edits go to MDK as the user chose them, and MDK's refusals are reported as what they are. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkedDeviceManagementControllerTest {
    /** A scripted runtime that records every call and can fail or delay edits. */
    private class Native : LinkedDeviceManagementNative {
        val calls = mutableListOf<String>()
        var closed: suspend () -> UInt = { 2u }
        var leftover: suspend () -> UInt = { 1u }
        var overdue: suspend () -> Boolean = { true }
        var edit: suspend () -> Unit = {}

        /** Records the rename and runs the scripted edit. */
        override suspend fun renameLinkedDevice(
            accountRef: String,
            slotIdHex: String,
            label: String,
        ) {
            calls += "rename:$accountRef:$slotIdHex:$label"
            edit()
        }

        /** Records the patch and runs the scripted edit. */
        override suspend fun updateLinkedDeviceSettings(
            accountRef: String,
            slotIdHex: String,
            patch: LinkedDeviceSettingsPatch,
        ) {
            calls += "settings:$accountRef:$slotIdHex:$patch"
            edit()
        }

        /** Records the removal and runs the scripted edit. */
        override suspend fun removeLinkedDevice(
            accountRef: String,
            slotIdHex: String,
        ) {
            calls += "remove:$accountRef:$slotIdHex"
            edit()
        }

        /** Answers with the scripted leftover count. */
        override suspend fun removedDeviceLeftoverChats(accountRef: String): UInt = leftover()

        /** Answers with the scripted closed count. */
        override suspend fun chatsClosedToLinkedDevices(accountRef: String): UInt = closed()

        /** Answers with the scripted overdue flag. */
        override suspend fun keyPackageRefreshOverdue(accountRef: String): Boolean = overdue()

        /** Records the reset, runs the scripted edit and answers with an empty snapshot. */
        override suspend fun resetDeviceGroupForRepair(accountRef: String): DeviceLinkSnapshotFfi {
            calls += "repair:$accountRef"
            edit()
            return DeviceLinkSnapshotFfi(null, emptyList(), emptyList(), false)
        }
    }

    /** A refresh publishes both counts and the overdue flag. */
    @Test
    fun aRefreshPublishesCountsAndTheOverdueFlag() =
        runTest {
            val controller = LinkedDeviceManagementController(ACCOUNT, Native())

            controller.refresh()

            assertEquals(LinkedDeviceChatCounts(closedToLinkedDevices = 2, holdingRemovedDevice = 1), controller.counts)
            assertTrue(controller.keyPackageRefreshOverdue)
        }

    /** A count that could not be read keeps its last value and is never reported as zero. */
    @Test
    fun aFailedCountReadKeepsTheLastValue() =
        runTest {
            val native = Native()
            val controller = LinkedDeviceManagementController(ACCOUNT, native)
            controller.refresh()
            native.closed = { throw MarmotKitException.TransportClosed() }
            native.leftover = { 4u }
            native.overdue = { throw MarmotKitException.TransportClosed() }

            controller.refresh()

            assertEquals(LinkedDeviceChatCounts(closedToLinkedDevices = 2, holdingRemovedDevice = 4), controller.counts)
            assertTrue(controller.keyPackageRefreshOverdue)
        }

    /** Before any successful read the counts are unknown, not zero. */
    @Test
    fun countsAreUnknownUntilRead() =
        runTest {
            val native = Native()
            native.closed = { throw MarmotKitException.TransportClosed() }
            native.leftover = { throw MarmotKitException.TransportClosed() }
            val controller = LinkedDeviceManagementController(ACCOUNT, native)

            controller.refresh()

            assertEquals(LinkedDeviceChatCounts(), controller.counts)
        }

    /** Each edit reaches its own native call with the device and values it was given. */
    @Test
    fun eachEditReachesItsNativeCall() =
        runTest {
            val native = Native()
            val controller = LinkedDeviceManagementController(ACCOUNT, native)
            val patch = LinkedDeviceSettingsPatch(keepWhileInactive = true)

            controller.rename("slot", "Desk")
            controller.updateSettings("slot", patch)
            controller.remove("slot")
            val snapshot = controller.resetForRepair()

            assertEquals(
                listOf(
                    "rename:$ACCOUNT:slot:Desk",
                    "settings:$ACCOUNT:slot:$patch",
                    "remove:$ACCOUNT:slot",
                    "repair:$ACCOUNT",
                ),
                native.calls,
            )
            assertEquals(DeviceLinkSnapshotFfi(null, emptyList(), emptyList(), false), snapshot)
            assertNull(controller.failure)
        }

    /** A patch names only the setting the user changed, leaving the others for MDK to keep. */
    @Test
    fun aPatchNamesOnlyTheChangedSetting() {
        val patch = LinkedDeviceSettingsPatch(bringHistory = false)

        assertNull(patch.addToAllChats)
        assertEquals(false, patch.bringHistory)
        assertNull(patch.keepWhileInactive)
    }

    /** A busy device group is reported as busy so the user can retry, not as a generic failure. */
    @Test
    fun aBusyDeviceGroupIsReportedAsBusy() =
        runTest {
            val native = Native()
            native.edit = { throw MarmotKitException.Runtime("device group refused: device_group_busy") }
            val controller = LinkedDeviceManagementController(ACCOUNT, native)

            controller.updateSettings("slot", LinkedDeviceSettingsPatch(addToAllChats = false))

            assertEquals(LinkedDeviceActionFailure.Busy(LinkedDeviceAction.Settings), controller.failure)
            controller.failureShown()
            assertNull(controller.failure)
        }

    /** Removing a device whose link is still pending is reported as that. */
    @Test
    fun aPendingLinkBlocksRemovalWithItsOwnReason() =
        runTest {
            val native = Native()
            native.edit = { throw MarmotKitException.Runtime("device group refused: device_link_pending") }
            val controller = LinkedDeviceManagementController(ACCOUNT, native)

            controller.remove("slot")

            assertEquals(LinkedDeviceActionFailure.LinkPending(LinkedDeviceAction.Remove), controller.failure)
        }

    /** Any other refusal or failure keeps its cause for the copyable report. */
    @Test
    fun otherFailuresKeepTheirCause() =
        runTest {
            val native = Native()
            val error = MarmotKitException.Runtime("device group refused: unknown_device")
            native.edit = { throw error }
            val controller = LinkedDeviceManagementController(ACCOUNT, native)

            controller.rename("slot", "x")

            assertEquals(LinkedDeviceActionFailure.Other(LinkedDeviceAction.Rename, error), controller.failure)
        }

    /** Only MDK's device-group refusal details are read as a refusal reason. */
    @Test
    fun onlyDeviceGroupRefusalsCarryAReason() {
        val refused = MarmotKitException.Runtime("device group refused: label_too_long")

        assertEquals("label_too_long", deviceGroupRefusal(refused))
        assertNull(deviceGroupRefusal(MarmotKitException.Runtime("device_group_busy")))
        assertNull(deviceGroupRefusal(IllegalStateException("device group refused: device_group_busy")))
    }

    /** A failed repair returns no snapshot, so the caller does not move on to linking. */
    @Test
    fun aFailedRepairReturnsNoSnapshot() =
        runTest {
            val native = Native()
            native.edit = { throw MarmotKitException.TransportClosed() }
            val controller = LinkedDeviceManagementController(ACCOUNT, native)

            assertNull(controller.resetForRepair())
            assertTrue(controller.failure is LinkedDeviceActionFailure.Other)
        }

    /** A second tap while an edit runs sends nothing. */
    @Test
    fun anEditStartedWhileAnotherRunsIsDropped() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val native = Native()
            native.edit = { gate.await() }
            val controller = LinkedDeviceManagementController(ACCOUNT, native)
            val first = launch { controller.remove("slot") }
            runCurrent()
            assertEquals(LinkedDeviceAction.Remove, controller.running)

            controller.remove("slot")
            controller.rename("slot", "x")
            gate.complete(Unit)
            first.join()

            assertEquals(listOf("remove:$ACCOUNT:slot"), native.calls)
            assertNull(controller.running)
        }

    /** After retirement nothing is sent and nothing is published. */
    @Test
    fun aRetiredControllerSendsAndPublishesNothing() =
        runTest {
            val native = Native()
            val controller = LinkedDeviceManagementController(ACCOUNT, native)
            controller.retire()

            controller.rename("slot", "x")
            controller.refresh()

            assertTrue(native.calls.isEmpty())
            assertEquals(LinkedDeviceChatCounts(), controller.counts)
            assertFalse(controller.keyPackageRefreshOverdue)
        }

    /** A failure that arrives after the account changed is dropped. */
    @Test
    fun aFailureAfterTheOwnerChangedIsDropped() =
        runTest {
            var owned = true
            val native = Native()
            native.edit = {
                owned = false
                throw MarmotKitException.TransportClosed()
            }
            val controller = LinkedDeviceManagementController(ACCOUNT, native, owns = { owned })

            controller.remove("slot")

            assertNull(controller.failure)
        }

    /** Only this account's roster and overdue events trigger a re-read. */
    @Test
    fun onlyThisAccountsEventsReread() =
        runTest {
            val native = Native()
            var reads = 0
            native.closed = {
                reads++
                0u
            }
            val controller = LinkedDeviceManagementController(ACCOUNT, native)

            controller.onEvent(MarmotEventFfi.LinkedDevicesChanged("id", ACCOUNT))
            controller.onEvent(MarmotEventFfi.KeyPackageRefreshOverdueChanged("id", ACCOUNT))
            controller.onEvent(MarmotEventFfi.LinkedDevicesChanged("id", "other"))
            controller.onEvent(MarmotEventFfi.DeviceLinkChanged("id", ACCOUNT))

            assertEquals(2, reads)
        }

    private companion object {
        const val ACCOUNT = "account"
    }
}
