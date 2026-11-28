package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.DeviceLinkPromptFfi
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import dev.ipf.marmotkit.LinkedDeviceFfi
import dev.ipf.marmotkit.LinkedDeviceStatusFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controller mirrors MDK's linked-device state for one account and never publishes a retired result. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceLinkControllerTest {
    /** A scripted runtime: each read runs the current behavior and counts itself. */
    private class Native(
        var devices: suspend () -> List<LinkedDeviceFfi> = { emptyList() },
        var snapshot: suspend () -> DeviceLinkSnapshotFfi = { linkSnapshot() },
    ) : DeviceLinkNative {
        var rosterReads = 0
        var linkReads = 0
        val accounts = mutableListOf<String>()
        val commands = mutableListOf<String>()
        var commandResult: suspend () -> DeviceLinkSnapshotFfi = { linkSnapshot() }

        /** Records the command and answers with the scripted result. */
        override suspend fun startDeviceLink(accountRef: String): DeviceLinkSnapshotFfi {
            commands += "start:$accountRef"
            return commandResult()
        }

        /** Records the command and answers with the scripted result. */
        override suspend fun confirmDeviceLinkMatch(accountRef: String): DeviceLinkSnapshotFfi {
            commands += "confirm:$accountRef"
            return commandResult()
        }

        /** Records the command and answers with the scripted result. */
        override suspend fun cancelDeviceLink(accountRef: String): DeviceLinkSnapshotFfi {
            commands += "cancel:$accountRef"
            return commandResult()
        }

        /** Records the command with its prompt and choices and answers with the scripted result. */
        override suspend fun linkDevice(
            accountRef: String,
            promptId: String,
            choices: DeviceLinkChoices,
        ): DeviceLinkSnapshotFfi {
            commands += "link:$accountRef:$promptId:$choices"
            return commandResult()
        }

        /** Records the command with its prompt and answers with the scripted result. */
        override suspend fun declineDeviceLink(
            accountRef: String,
            promptId: String,
        ): DeviceLinkSnapshotFfi {
            commands += "decline:$accountRef:$promptId"
            return commandResult()
        }

        /** Records the dismissal with its move and device and answers with the scripted result. */
        override suspend fun dismissDeviceGroupMoveSibling(
            accountRef: String,
            moveId: String,
            slotIdHex: String,
        ): DeviceLinkSnapshotFfi {
            commands += "dismiss:$accountRef:$moveId:$slotIdHex"
            return commandResult()
        }

        /** Counts the read and answers with the scripted snapshot. */
        override suspend fun deviceLinkSnapshot(accountRef: String): DeviceLinkSnapshotFfi {
            linkReads++
            accounts += accountRef
            return snapshot()
        }

        /** Counts the read and answers with the scripted roster. */
        override suspend fun linkedDevices(accountRef: String): List<LinkedDeviceFfi> {
            rosterReads++
            accounts += accountRef
            return devices()
        }
    }

    /** The first successful read publishes the grouped roster for the controller's own account. */
    @Test
    fun aSuccessfulReadPublishesTheRoster() =
        runTest {
            val native = Native(devices = { listOf(device("a", isThisDevice = true), device("b")) })
            val controller = DeviceLinkController(ACCOUNT, native)

            controller.refreshRoster()

            assertEquals(
                "a",
                controller.roster.roster
                    ?.thisDevice
                    ?.slotIdHex,
            )
            assertEquals(
                listOf("b"),
                controller.roster.roster
                    ?.others
                    ?.map { it.slotIdHex },
            )
            assertFalse(controller.roster.readFailed)
            assertFalse(controller.roster.refreshing)
            assertEquals(listOf(ACCOUNT), native.accounts)
        }

    /** A failed read keeps the rows on screen and marks the failure, so it never reads as an empty list. */
    @Test
    fun aFailedReadKeepsTheRowsAndMarksTheFailure() =
        runTest {
            val native = Native(devices = { listOf(device("a", isThisDevice = true), device("b")) })
            val controller = DeviceLinkController(ACCOUNT, native)
            controller.refreshRoster()
            native.devices = { throw MarmotKitException.TransportClosed() }

            controller.refreshRoster()

            assertTrue(controller.roster.readFailed)
            assertEquals(
                listOf("b"),
                controller.roster.roster
                    ?.others
                    ?.map { it.slotIdHex },
            )
        }

    /** A failure before any successful read leaves no roster rather than an empty one. */
    @Test
    fun aFirstFailedReadLeavesNoRoster() =
        runTest {
            val native = Native(devices = { throw MarmotKitException.TransportClosed() })
            val controller = DeviceLinkController(ACCOUNT, native)

            controller.refreshRoster()

            assertNull(controller.roster.roster)
            assertTrue(controller.roster.readFailed)
        }

    /** A retry that succeeds clears the failure mark. */
    @Test
    fun aSuccessfulRetryClearsTheFailure() =
        runTest {
            val native = Native(devices = { throw MarmotKitException.TransportClosed() })
            val controller = DeviceLinkController(ACCOUNT, native)
            controller.refreshRoster()
            native.devices = { listOf(device("a", isThisDevice = true)) }

            controller.refreshRoster()

            assertFalse(controller.roster.readFailed)
            assertEquals(
                "a",
                controller.roster.roster
                    ?.thisDevice
                    ?.slotIdHex,
            )
        }

    /** A read that returns after the controller retired is dropped. */
    @Test
    fun aReadFinishingAfterRetirementIsDropped() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val native =
                Native(
                    devices = {
                        gate.await()
                        listOf(device("late", isThisDevice = true))
                    },
                )
            val controller = DeviceLinkController(ACCOUNT, native)
            val read = launch { controller.refreshRoster() }
            runCurrent()

            controller.retire()
            gate.complete(Unit)
            read.join()

            assertNull(controller.roster.roster)
            assertFalse(controller.current)
        }

    /** A read that returns after the account or runtime changed is dropped, and later reads are not started. */
    @Test
    fun aReadFinishingAfterTheOwnerChangedIsDropped() =
        runTest {
            var owned = true
            val gate = CompletableDeferred<Unit>()
            val native =
                Native(
                    snapshot = {
                        gate.await()
                        linkSnapshot(stalled = true)
                    },
                )
            val controller = DeviceLinkController(ACCOUNT, native, owns = { owned })
            val read = launch { controller.refreshLink() }
            runCurrent()

            owned = false
            gate.complete(Unit)
            read.join()
            controller.refreshLink()
            controller.refreshRoster()

            assertNull(controller.link)
            assertEquals(1, native.linkReads)
            assertEquals(0, native.rosterReads)
        }

    /** A failed snapshot read keeps the snapshot already shown and marks the failure. */
    @Test
    fun aFailedSnapshotReadKeepsTheShownSnapshot() =
        runTest {
            val shown = linkSnapshot(stalled = true)
            val native = Native(snapshot = { shown })
            val controller = DeviceLinkController(ACCOUNT, native)
            controller.refreshLink()
            native.snapshot = { throw MarmotKitException.TransportClosed() }

            controller.refreshLink()

            assertEquals(shown, controller.link)
            assertTrue(controller.linkReadFailed)
        }

    @Test
    fun readOnlyDevicesRefreshesRosterWithoutDrivingPairing() =
        runTest {
            val native = Native()
            val controller = DeviceLinkController(ACCOUNT, native, canLink = false)
            val events = Channel<MarmotEventFfi?>(Channel.UNLIMITED)
            val observing = launch { controller.observe { events.receive() } }
            runCurrent()
            events.send(MarmotEventFfi.DeviceLinkChanged("id", ACCOUNT))
            runCurrent()
            events.send(null)
            observing.join()
            controller.refreshLink()
            controller.start()

            assertTrue(native.commands.isEmpty())
            assertEquals(1, native.rosterReads)
            assertEquals(0, native.linkReads)
            assertNull(controller.link)
            assertFalse(controller.linkReadFailed)
        }

    /** Observing reads both once, then only what this account's events make stale. */
    @Test
    fun observingRereadsOnlyWhatThisAccountsEventsMakeStale() =
        runTest {
            val native = Native()
            val controller = DeviceLinkController(ACCOUNT, native)
            val events = Channel<MarmotEventFfi?>(Channel.UNLIMITED)
            val observing = launch { controller.observe { events.receive() } }
            runCurrent()
            assertEquals(1 to 1, native.rosterReads to native.linkReads)

            events.send(MarmotEventFfi.LinkedDevicesChanged("id", ACCOUNT))
            runCurrent()
            assertEquals(2 to 1, native.rosterReads to native.linkReads)

            events.send(MarmotEventFfi.DeviceLinkChanged("id", ACCOUNT))
            runCurrent()
            assertEquals(2 to 2, native.rosterReads to native.linkReads)

            events.send(MarmotEventFfi.LinkedDevicesChanged("other", "other-account"))
            events.send(MarmotEventFfi.DeviceLinkChanged("other", "other-account"))
            events.send(MarmotEventFfi.KeyPackageRefreshOverdueChanged("id", ACCOUNT))
            runCurrent()
            assertEquals(2 to 2, native.rosterReads to native.linkReads)

            events.send(null)
            observing.join()
        }

    /** Change events arriving while a read is running collapse into one further read. */
    @Test
    fun aBurstOfEventsDuringAReadCostsOneMoreRead() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val native = Native()
            native.devices = {
                if (native.rosterReads == 1) gate.await()
                emptyList()
            }
            val controller = DeviceLinkController(ACCOUNT, native)
            val events = Channel<MarmotEventFfi?>(Channel.UNLIMITED)
            val observing = launch { controller.observe { events.receive() } }
            runCurrent()

            repeat(5) { events.send(MarmotEventFfi.LinkedDevicesChanged("id", ACCOUNT)) }
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(2, native.rosterReads)
            events.send(null)
            observing.join()
        }

    /** A stream that fails ends observation without throwing, and the first reads still finish. */
    @Test
    fun aFailingStreamEndsObservationAfterTheFirstReads() =
        runTest {
            val native = Native(devices = { listOf(device("a", isThisDevice = true)) })
            val controller = DeviceLinkController(ACCOUNT, native)

            val observing = async { controller.observe { throw MarmotKitException.TransportClosed() } }
            observing.await()

            assertEquals(
                "a",
                controller.roster.roster
                    ?.thisDevice
                    ?.slotIdHex,
            )
            assertEquals(1, native.linkReads)
        }

    /** This device leads, other devices follow newest link first, and pending removals are listed apart. */
    @Test
    fun theRosterGroupsThisDeviceOthersAndPendingRemovals() {
        val roster =
            linkedDeviceRoster(
                listOf(
                    device("old", linkedAt = 10u),
                    device("removing", linkedAt = 30u, removalPending = true),
                    device("self", isThisDevice = true, linkedAt = 5u),
                    device("new", linkedAt = 20u),
                ),
            )

        assertEquals("self", roster.thisDevice?.slotIdHex)
        assertEquals(listOf("new", "old"), roster.others.map { it.slotIdHex })
        assertEquals(listOf("removing"), roster.removing.map { it.slotIdHex })
        assertTrue(roster.hasOtherDevices)
        assertFalse(roster.isEmpty)
    }

    /** A row names the device that linked it: its label, blank when it has none, and nothing when MDK names none. */
    @Test
    fun aRowNamesTheDeviceThatLinkedIt() {
        val roster =
            linkedDeviceRoster(
                listOf(
                    device("self", isThisDevice = true, label = "Phone"),
                    device("named", linkedBy = "self"),
                    device("unnamed", linkedBy = "named"),
                    device("gone", linkedBy = "missing"),
                ),
            )

        assertNull(roster.thisDevice?.linkedByLabel)
        assertEquals("Phone", roster.device("named")?.linkedByLabel)
        assertEquals("", roster.device("unnamed")?.linkedByLabel)
        assertNull(roster.device("gone")?.linkedByLabel)
    }

    /** MDK's fields reach the row unchanged, including an absent heartbeat. */
    @Test
    fun aRowCarriesMdksFieldsUnchanged() {
        val row =
            linkedDeviceRoster(
                listOf(
                    device(
                        "slot",
                        label = "Desk",
                        client = "White Noise",
                        platform = "Android",
                        linkedAt = 7u,
                        status = LinkedDeviceStatusFfi.INACTIVE,
                        lastActiveAt = null,
                        inactive = true,
                    ),
                ),
            ).others.single()

        assertEquals(
            LinkedDeviceRow(
                slotIdHex = "slot",
                isThisDevice = false,
                label = "Desk",
                client = "White Noise",
                platform = "Android",
                linkedAtSeconds = 7,
                status = LinkedDeviceStatusFfi.INACTIVE,
                lastActiveAtSeconds = null,
                inactive = true,
                removalPending = false,
            ),
            row,
        )
    }

    /** An empty native roster is an empty roster, distinct from no roster at all. */
    @Test
    fun anEmptyNativeRosterIsEmpty() {
        val roster = linkedDeviceRoster(emptyList())

        assertTrue(roster.isEmpty)
        assertFalse(roster.hasOtherDevices)
    }

    /** A command publishes the snapshot MDK returns and clears the running mark. */
    @Test
    fun aCommandPublishesTheReturnedSnapshot() =
        runTest {
            val native = Native()
            val returned = linkSnapshot(stalled = true)
            native.commandResult = { returned }
            val controller = DeviceLinkController(ACCOUNT, native)

            controller.start()

            assertEquals(returned, controller.link)
            assertNull(controller.running)
            assertEquals(listOf("start:$ACCOUNT"), native.commands)
        }

    /** Each command reaches its own native call with the account, prompt and choices it was given. */
    @Test
    fun eachCommandReachesItsNativeCall() =
        runTest {
            val native = Native()
            val controller = DeviceLinkController(ACCOUNT, native)
            val choices = DeviceLinkChoices(label = "Desk", addToAllChats = false, bringHistory = false)

            controller.start()
            controller.confirmMatch()
            controller.cancel()
            controller.link("p1", choices)
            controller.decline("p2")
            controller.dismissMoveSibling("m1", "slot")

            assertEquals(
                listOf(
                    "start:$ACCOUNT",
                    "confirm:$ACCOUNT",
                    "cancel:$ACCOUNT",
                    "link:$ACCOUNT:p1:$choices",
                    "decline:$ACCOUNT:p2",
                    "dismiss:$ACCOUNT:m1:slot",
                ),
                native.commands,
            )
        }

    /** A read waits for a running command, so it can never publish between the command's call and its result. */
    @Test
    fun aLinkReadWaitsForARunningCommand() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val native = Native(snapshot = { linkSnapshot(stalled = true).also { order += "read" } })
            native.commandResult = {
                gate.await()
                linkSnapshot().also { order += "command" }
            }
            val controller = DeviceLinkController(ACCOUNT, native)
            val command = launch { controller.confirmMatch() }
            runCurrent()
            val read = launch { controller.refreshLink() }
            runCurrent()

            gate.complete(Unit)
            command.join()
            read.join()

            assertEquals(listOf("command", "read"), order)
            assertEquals(true, controller.link?.deviceGroupStalled)
        }

    /** A second tap while a command runs submits nothing. */
    @Test
    fun aCommandStartedWhileAnotherRunsIsDropped() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val native = Native()
            native.commandResult = {
                gate.await()
                linkSnapshot()
            }
            val controller = DeviceLinkController(ACCOUNT, native)
            val first = launch { controller.confirmMatch() }
            runCurrent()
            assertEquals(DeviceLinkCommand.ConfirmMatch, controller.running)

            controller.confirmMatch()
            controller.cancel()
            gate.complete(Unit)
            first.join()

            assertEquals(listOf("confirm:$ACCOUNT"), native.commands)
            assertNull(controller.running)
        }

    /** A failed command keeps the shown snapshot and reports the failure once. */
    @Test
    fun aFailedCommandIsReportedOnceAndKeepsTheSnapshot() =
        runTest {
            val native = Native()
            val controller = DeviceLinkController(ACCOUNT, native)
            controller.refreshLink()
            val shown = controller.link
            val error = MarmotKitException.TransportClosed()
            native.commandResult = { throw error }

            controller.link("p1", DeviceLinkChoices())

            assertEquals(DeviceLinkCommandFailure(DeviceLinkCommand.Link, error), controller.failure)
            assertEquals(shown, controller.link)
            assertNull(controller.running)
            controller.failureShown()
            assertNull(controller.failure)
        }

    /** A command result that arrives after retirement is dropped, and later commands are not sent. */
    @Test
    fun aCommandFinishingAfterRetirementIsDropped() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val native = Native()
            native.commandResult = {
                gate.await()
                linkSnapshot(stalled = true)
            }
            val controller = DeviceLinkController(ACCOUNT, native)
            val command = launch { controller.start() }
            runCurrent()

            controller.retire()
            gate.complete(Unit)
            command.join()
            controller.cancel()

            assertNull(controller.link)
            assertNull(controller.failure)
            assertEquals(listOf("start:$ACCOUNT"), native.commands)
        }

    /** A device name is accepted up to MDK's 64-byte limit, counted in UTF-8 bytes rather than characters. */
    @Test
    fun aDeviceNameIsLimitedInUtf8Bytes() {
        assertTrue(deviceLabelFits(""))
        assertTrue(deviceLabelFits("a".repeat(64)))
        assertFalse(deviceLabelFits("a".repeat(65)))
        assertTrue(deviceLabelFits("é".repeat(32)))
        assertFalse(deviceLabelFits("é".repeat(33)))
    }

    private companion object {
        const val ACCOUNT = "account"

        /** A roster entry with only the fields a test cares about set away from their defaults. */
        @Suppress("LongParameterList") // Mirrors the native record so each test names what it varies.
        fun device(
            slot: String,
            isThisDevice: Boolean = false,
            label: String = "",
            client: String = "",
            platform: String = "",
            linkedAt: ULong = 0u,
            status: LinkedDeviceStatusFfi = LinkedDeviceStatusFfi.ACTIVE,
            lastActiveAt: ULong? = null,
            inactive: Boolean = false,
            removalPending: Boolean = false,
            linkedBy: String? = null,
        ) = LinkedDeviceFfi(
            slotIdHex = slot,
            isThisDevice = isThisDevice,
            label = label,
            client = client,
            platform = platform,
            linkedAt = linkedAt,
            linkedBySlotIdHex = linkedBy,
            status = status,
            addToAllChats = true,
            bringHistory = false,
            keepWhileInactive = false,
            lastActiveAt = lastActiveAt,
            inactive = inactive,
            removalPending = removalPending,
        )

        /** A snapshot holding [prompts] and nothing else. */
        fun linkSnapshot(
            vararg prompts: DeviceLinkPromptFfi,
            stalled: Boolean = false,
        ) = DeviceLinkSnapshotFfi(
            newDevice = null,
            prompts = prompts.toList(),
            moves = emptyList(),
            deviceGroupStalled = stalled,
        )
    }
}
