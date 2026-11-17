package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.DeviceLinkEndReasonFfi
import dev.ipf.marmotkit.DeviceLinkPromptFfi
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
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

    /** Only prompts that still need the user or are in progress count as open. */
    @Test
    fun onlyUnsettledPromptsAreOpen() {
        val open =
            listOf(
                DeviceLinkPromptStateFfi.Preparing,
                DeviceLinkPromptStateFfi.Comparing(emptyList()),
                DeviceLinkPromptStateFfi.CompetingRequests,
                DeviceLinkPromptStateFfi.RateLimited(1u),
                DeviceLinkPromptStateFfi.CannotLink(emptyList()),
                DeviceLinkPromptStateFfi.TooManyDevices,
                DeviceLinkPromptStateFfi.Linking,
            )
        val settled =
            listOf(
                DeviceLinkPromptStateFfi.Linked,
                DeviceLinkPromptStateFfi.AddedElsewhere("a", "b"),
                DeviceLinkPromptStateFfi.Ended(DeviceLinkEndReasonFfi.TIMED_OUT),
            )

        open.forEach { assertTrue(it.toString(), it.isOpen()) }
        settled.forEach { assertFalse(it.toString(), it.isOpen()) }
    }

    /** Open requests from every profile are listed oldest first, and settled ones are left out. */
    @Test
    fun pendingRequestsSpanProfilesOldestFirst() {
        val pending =
            pendingDeviceLinkRequests(
                mapOf(
                    "b" to linkSnapshot(prompt("b1", 30u), prompt("b2", 5u, DeviceLinkPromptStateFfi.Linked)),
                    "a" to linkSnapshot(prompt("a1", 20u), prompt("a2", 10u)),
                ),
            )

        assertEquals(
            listOf(
                PendingDeviceLinkRequest("a", "a2", 10),
                PendingDeviceLinkRequest("a", "a1", 20),
                PendingDeviceLinkRequest("b", "b1", 30),
            ),
            pending,
        )
    }

    /** The oldest request is presented, and a newer one never replaces it while MDK still lists it. */
    @Test
    fun aPresentedRequestIsNotReplacedByANewerOne() {
        val first = PendingDeviceLinkRequest("a", "p1", 10)
        val older = PendingDeviceLinkRequest("b", "p0", 1)

        val presented = DeviceLinkRequestQueue().offer(listOf(first), blocked = false)
        val afterOlderArrives = presented.offer(listOf(older, first), blocked = false)

        assertEquals(first, presented.presented)
        assertEquals(first, afterOlderArrives.presented)
    }

    /** Nothing new is presented while a protected flow is on screen, and it is presented once that ends. */
    @Test
    fun nothingNewIsPresentedWhileBlocked() {
        val request = PendingDeviceLinkRequest("a", "p1", 10)

        val blocked = DeviceLinkRequestQueue().offer(listOf(request), blocked = true)
        val unblocked = blocked.offer(listOf(request), blocked = false)

        assertNull(blocked.presented)
        assertEquals(request, unblocked.presented)
    }

    /** A request already presented stays presented when a protected flow starts. */
    @Test
    fun aPresentedRequestSurvivesBlocking() {
        val request = PendingDeviceLinkRequest("a", "p1", 10)
        val presented = DeviceLinkRequestQueue().offer(listOf(request), blocked = false)

        assertEquals(request, presented.offer(listOf(request), blocked = true).presented)
    }

    /** Closing a request hides it without answering, presents the next one, and forgets it once MDK drops it. */
    @Test
    fun hidingARequestPresentsTheNextAndIsForgottenWhenItSettles() {
        val first = PendingDeviceLinkRequest("a", "p1", 10)
        val second = PendingDeviceLinkRequest("a", "p2", 20)
        val presented = DeviceLinkRequestQueue().offer(listOf(first, second), blocked = false)

        val hidden = presented.hidePresented().offer(listOf(first, second), blocked = false)
        val settled = hidden.offer(listOf(second), blocked = false)

        assertEquals(second, hidden.presented)
        assertEquals(setOf(first), hidden.hidden)
        assertTrue(settled.hidden.isEmpty())
    }

    /** A request MDK no longer lists stops being presented. */
    @Test
    fun aRequestMdkDroppedIsNoLongerPresented() {
        val request = PendingDeviceLinkRequest("a", "p1", 10)
        val presented = DeviceLinkRequestQueue().offer(listOf(request), blocked = false)

        assertNull(presented.offer(emptyList(), blocked = false).presented)
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
        ) = LinkedDeviceFfi(
            slotIdHex = slot,
            isThisDevice = isThisDevice,
            label = label,
            client = client,
            platform = platform,
            linkedAt = linkedAt,
            linkedBySlotIdHex = null,
            status = status,
            addToAllChats = true,
            bringHistory = false,
            keepWhileInactive = false,
            lastActiveAt = lastActiveAt,
            inactive = inactive,
            removalPending = removalPending,
        )

        /** A request prompt in [state], open by default. */
        fun prompt(
            id: String,
            requestedAt: ULong,
            state: DeviceLinkPromptStateFfi = DeviceLinkPromptStateFfi.Preparing,
        ) = DeviceLinkPromptFfi(promptId = id, slotIdHex = null, requestedAt = requestedAt, state = state)

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
