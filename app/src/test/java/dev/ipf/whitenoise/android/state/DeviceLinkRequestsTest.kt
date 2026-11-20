package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.DeviceLinkEndReasonFfi
import dev.ipf.marmotkit.DeviceLinkPromptFfi
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Link requests are read across profiles, presented one at a time and never answered by the shell itself. */
class DeviceLinkRequestsTest {
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
        val settled = listOf(DeviceLinkPromptStateFfi.Linked, DeviceLinkPromptStateFfi.AddedElsewhere("a", "b"), ENDED)

        open.forEach { assertTrue(it.toString(), it.isOpen()) }
        settled.forEach { assertFalse(it.toString(), it.isOpen()) }
    }

    /** Requests from every profile are listed oldest first, each marked open or settled. */
    @Test
    fun requestsSpanProfilesOldestFirst() {
        val requests =
            deviceLinkRequests(
                mapOf(
                    "b" to snapshot(prompt("b1", 30u), prompt("b2", 5u, DeviceLinkPromptStateFfi.Linked)),
                    "a" to snapshot(prompt("a1", 20u), prompt("a2", 10u)),
                ),
            )

        assertEquals(
            listOf(
                DeviceLinkRequest("b", "b2", 5, open = false),
                DeviceLinkRequest("a", "a2", 10),
                DeviceLinkRequest("a", "a1", 20),
                DeviceLinkRequest("b", "b1", 30),
            ),
            requests,
        )
    }

    /** The oldest open request is presented, and a newer one never replaces it while MDK still lists it. */
    @Test
    fun aPresentedRequestIsNotReplacedByAnotherOne() {
        val first = DeviceLinkRequest("a", "p1", 10)
        val older = DeviceLinkRequest("b", "p0", 1)

        val presented = DeviceLinkRequestQueue().offer(listOf(first), blocked = false)
        val afterOlderArrives = presented.offer(listOf(older, first), blocked = false)

        assertEquals(first, presented.presented)
        assertEquals(first, afterOlderArrives.presented)
    }

    /** A settled request never starts being presented. */
    @Test
    fun aSettledRequestIsNotPresented() {
        val settled = DeviceLinkRequest("a", "p1", 10, open = false)

        assertNull(DeviceLinkRequestQueue().offer(listOf(settled), blocked = false).presented)
    }

    /** A presented request stays through to its outcome, so the user sees how it ended. */
    @Test
    fun aPresentedRequestStaysThroughToItsOutcome() {
        val request = DeviceLinkRequest("a", "p1", 10)
        val presented = DeviceLinkRequestQueue().offer(listOf(request), blocked = false)

        val settled = presented.offer(listOf(request.copy(open = false)), blocked = false)

        assertEquals(request.copy(open = false), settled.presented)
    }

    /** Nothing new is presented while a protected flow is on screen, and it is presented once that ends. */
    @Test
    fun nothingNewIsPresentedWhileBlocked() {
        val request = DeviceLinkRequest("a", "p1", 10)

        val blocked = DeviceLinkRequestQueue().offer(listOf(request), blocked = true)
        val unblocked = blocked.offer(listOf(request), blocked = false)

        assertNull(blocked.presented)
        assertEquals(request, unblocked.presented)
    }

    /** A request already presented stays presented when a protected flow starts. */
    @Test
    fun aPresentedRequestSurvivesBlocking() {
        val request = DeviceLinkRequest("a", "p1", 10)
        val presented = DeviceLinkRequestQueue().offer(listOf(request), blocked = false)

        assertEquals(request, presented.offer(listOf(request), blocked = true).presented)
    }

    /** Closing a request hides it without answering, presents the next one, and forgets it once MDK drops it. */
    @Test
    fun hidingARequestPresentsTheNextAndIsForgottenWhenMdkDropsIt() {
        val first = DeviceLinkRequest("a", "p1", 10)
        val second = DeviceLinkRequest("a", "p2", 20)
        val presented = DeviceLinkRequestQueue().offer(listOf(first, second), blocked = false)

        val hidden = presented.hidePresented().offer(listOf(first, second), blocked = false)
        val dropped = hidden.offer(listOf(second), blocked = false)

        assertEquals(second, hidden.presented)
        assertEquals(setOf("a" to "p1"), hidden.hidden)
        assertTrue(dropped.hidden.isEmpty())
    }

    /** A request MDK no longer lists stops being presented. */
    @Test
    fun aRequestMdkDroppedIsNoLongerPresented() {
        val request = DeviceLinkRequest("a", "p1", 10)
        val presented = DeviceLinkRequestQueue().offer(listOf(request), blocked = false)

        assertNull(presented.offer(emptyList(), blocked = false).presented)
    }

    /** The watcher reads every signed-in profile and presents the oldest open request, whichever profile owns it. */
    @Test
    fun theWatcherReadsEveryProfile() =
        runTest {
            val reads = mutableListOf<String>()
            val watcher =
                DeviceLinkRequestWatcher(
                    readSnapshot = { account ->
                        reads += account
                        if (account == "b") snapshot(prompt("b1", 5u)) else snapshot(prompt("a1", 10u))
                    },
                )

            watcher.refresh(listOf("a", "b"), blocked = false)

            assertEquals(listOf("a", "b"), reads)
            assertEquals(DeviceLinkRequest("b", "b1", 5), watcher.queue.presented)
            assertEquals("b1", watcher.presentedPrompt?.promptId)
        }

    /** A profile whose read fails keeps its last snapshot, so a comparison on screen is not dismissed. */
    @Test
    fun aFailedReadKeepsTheLastSnapshot() =
        runTest {
            var fail = false
            val watcher =
                DeviceLinkRequestWatcher(
                    readSnapshot = {
                        if (fail) throw MarmotKitException.TransportClosed()
                        snapshot(prompt("p1", 10u))
                    },
                )
            watcher.refresh(listOf("a"), blocked = false)
            fail = true

            watcher.refresh(listOf("a"), blocked = false)

            assertEquals(DeviceLinkRequest("a", "p1", 10), watcher.queue.presented)
        }

    /** A profile that signed out is dropped with its requests. */
    @Test
    fun aSignedOutProfileIsDropped() =
        runTest {
            val watcher = DeviceLinkRequestWatcher(readSnapshot = { snapshot(prompt("p1", 10u)) })
            watcher.refresh(listOf("a"), blocked = false)

            watcher.refresh(emptyList(), blocked = false)

            assertNull(watcher.queue.presented)
            assertTrue(watcher.snapshots.isEmpty())
        }

    /** Only a link change for a watched profile triggers a re-read, and only of that profile. */
    @Test
    fun onlyAWatchedProfilesLinkChangeRereads() =
        runTest {
            val reads = mutableListOf<String>()
            val watcher =
                DeviceLinkRequestWatcher(
                    readSnapshot = { account ->
                        reads += account
                        snapshot()
                    },
                )
            val accounts = listOf("a", "b")

            watcher.onEvent(MarmotEventFfi.DeviceLinkChanged("id", "b"), accounts, blocked = false)
            watcher.onEvent(MarmotEventFfi.DeviceLinkChanged("id", "other"), accounts, blocked = false)
            watcher.onEvent(MarmotEventFfi.LinkedDevicesChanged("id", "a"), accounts, blocked = false)

            assertEquals(listOf("b"), reads)
        }

    /** A read that finishes after the runtime changed is dropped. */
    @Test
    fun aReadFinishingAfterTheOwnerChangedIsDropped() =
        runTest {
            var owned = true
            val watcher =
                DeviceLinkRequestWatcher(
                    readSnapshot = {
                        owned = false
                        snapshot(prompt("p1", 10u))
                    },
                    owns = { owned },
                )

            watcher.refresh(listOf("a"), blocked = false)

            assertNull(watcher.queue.presented)
            assertTrue(watcher.snapshots.isEmpty())
        }

    /** A snapshot returned by a command updates the prompt straight away. */
    @Test
    fun aCommandsSnapshotUpdatesThePrompt() =
        runTest {
            val watcher = DeviceLinkRequestWatcher(readSnapshot = { snapshot(prompt("p1", 10u)) })
            watcher.refresh(listOf("a"), blocked = false)

            watcher.accept("a", snapshot(prompt("p1", 10u, DeviceLinkPromptStateFfi.Linking)), blocked = false)

            assertEquals(DeviceLinkPromptStateFfi.Linking, watcher.presentedPrompt?.state)
        }

    /** A read that began before a command's result was published is older than it, so it does not replace it. */
    @Test
    fun aReadBegunBeforeACommandsResultDoesNotReplaceIt() =
        runTest {
            lateinit var watcher: DeviceLinkRequestWatcher
            val linking = snapshot(prompt("p1", 10u, DeviceLinkPromptStateFfi.Linking))
            watcher =
                DeviceLinkRequestWatcher(
                    readSnapshot = {
                        watcher.accept("a", linking, blocked = false)
                        snapshot(prompt("p1", 10u))
                    },
                )

            watcher.refresh(listOf("a"), blocked = false)
            assertEquals(DeviceLinkPromptStateFfi.Linking, watcher.presentedPrompt?.state)

            watcher.onEvent(MarmotEventFfi.DeviceLinkChanged("id", "a"), listOf("a"), blocked = false)
            assertEquals(DeviceLinkPromptStateFfi.Linking, watcher.presentedPrompt?.state)
        }

    /** A command's snapshot that lands after MDK's newer state is corrected by the re-read that follows it. */
    @Test
    fun aRereadAfterACommandsResultRestoresTheNewerState() =
        runTest {
            val watcher = DeviceLinkRequestWatcher(readSnapshot = { snapshot(prompt("p1", 10u, ENDED)) })
            watcher.refresh(listOf("a"), blocked = false)
            watcher.onEvent(MarmotEventFfi.DeviceLinkChanged("id", "a"), listOf("a"), blocked = false)

            watcher.accept("a", snapshot(prompt("p1", 10u, DeviceLinkPromptStateFfi.Linking)), blocked = false)
            assertEquals(DeviceLinkPromptStateFfi.Linking, watcher.prompt("a", "p1")?.state)
            watcher.reread("a", blocked = false)

            assertEquals(ENDED, watcher.prompt("a", "p1")?.state)
        }

    /** Closing the prompt hides it and answers nothing; unblocking later does not bring it back. */
    @Test
    fun hidingThePresentedRequestAnswersNothing() =
        runTest {
            var reads = 0
            val watcher =
                DeviceLinkRequestWatcher(
                    readSnapshot = {
                        reads++
                        snapshot(prompt("p1", 10u))
                    },
                )
            watcher.refresh(listOf("a"), blocked = false)

            watcher.hidePresented(blocked = false)
            watcher.reoffer(blocked = false)

            assertNull(watcher.queue.presented)
            assertEquals(1, reads)
        }

    private companion object {
        val ENDED = DeviceLinkPromptStateFfi.Ended(DeviceLinkEndReasonFfi.TIMED_OUT)

        /** A request prompt in [state], open by default. */
        fun prompt(
            id: String,
            requestedAt: ULong,
            state: DeviceLinkPromptStateFfi = DeviceLinkPromptStateFfi.Preparing,
        ) = DeviceLinkPromptFfi(promptId = id, slotIdHex = null, requestedAt = requestedAt, state = state)

        /** A snapshot holding [prompts] and nothing else. */
        fun snapshot(vararg prompts: DeviceLinkPromptFfi) =
            DeviceLinkSnapshotFfi(
                newDevice = null,
                prompts = prompts.toList(),
                moves = emptyList(),
                deviceGroupStalled = false,
            )
    }
}
