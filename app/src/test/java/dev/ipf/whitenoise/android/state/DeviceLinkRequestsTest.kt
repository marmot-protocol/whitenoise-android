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

/** Link requests are read across profiles, announced in one notice, opened only by a tap and never answered here. */
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

    /** No request is presented on its own, open or settled: MDK's requests only feed the chat list's notice. */
    @Test
    fun noRequestIsPresentedWithoutATap() {
        val open = DeviceLinkRequest("a", "p1", 10, comparing = true)
        val settled = DeviceLinkRequest("a", "p0", 5, open = false)

        val queue = DeviceLinkRequestQueue().offer(listOf(settled, open))

        assertNull(queue.presented)
        assertEquals(open, queue.notice)
    }

    /** Opening the notice presents that request, and a newer one never replaces it while MDK still lists it. */
    @Test
    fun anOpenedRequestIsNotReplacedByAnotherOne() {
        val first = DeviceLinkRequest("a", "p1", 10)
        val older = DeviceLinkRequest("b", "p0", 1)

        val presented = DeviceLinkRequestQueue().offer(listOf(first)).open(first, blocked = false)
        val afterOlderArrives = presented.offer(listOf(older, first))

        assertEquals(first, presented.presented)
        assertEquals(first, afterOlderArrives.presented)
        assertEquals(first, afterOlderArrives.open(older, blocked = false).presented)
        assertEquals(older, afterOlderArrives.notice)
    }

    /** A settled request has no notice and cannot be opened. */
    @Test
    fun aSettledRequestIsNotAnnouncedOrOpened() {
        val settled = DeviceLinkRequest("a", "p1", 10, open = false)
        val queue = DeviceLinkRequestQueue().offer(listOf(settled))

        assertNull(queue.notice)
        assertNull(queue.open(settled, blocked = false).presented)
    }

    /** An opened request stays through to its outcome, so the user sees how it ended. */
    @Test
    fun anOpenedRequestStaysThroughToItsOutcome() {
        val request = DeviceLinkRequest("a", "p1", 10)
        val presented = DeviceLinkRequestQueue().offer(listOf(request)).open(request, blocked = false)

        val settled = presented.offer(listOf(request.copy(open = false)))

        assertEquals(request.copy(open = false), settled.presented)
    }

    /** The notice opens nothing while a protected flow is on screen; the request keeps its notice for later. */
    @Test
    fun nothingOpensWhileBlocked() {
        val request = DeviceLinkRequest("a", "p1", 10)
        val queue = DeviceLinkRequestQueue().offer(listOf(request))

        val blocked = queue.open(request, blocked = true)

        assertNull(blocked.presented)
        assertEquals(request, blocked.notice)
        assertEquals(request, blocked.open(request, blocked = false).presented)
    }

    /** Closing an opened request answers nothing and returns it to the notice; it never reopens by itself. */
    @Test
    fun closingReturnsTheRequestToTheNotice() {
        val request = DeviceLinkRequest("a", "p1", 10)
        val presented = DeviceLinkRequestQueue().offer(listOf(request)).open(request, blocked = false)

        val closed = presented.close().offer(listOf(request.copy(comparing = true)))

        assertNull(presented.notice)
        assertNull(closed.presented)
        assertEquals(request.copy(comparing = true), closed.notice)
    }

    /** Not now on the notice hides that request until MDK drops it, and the next request takes the notice. */
    @Test
    fun puttingTheNoticeOffHidesThatRequestUntilMdkDropsIt() {
        val first = DeviceLinkRequest("a", "p1", 10)
        val second = DeviceLinkRequest("a", "p2", 20)
        val queue = DeviceLinkRequestQueue().offer(listOf(first, second))

        val dismissed = queue.dismiss(first).offer(listOf(first.copy(comparing = true), second))
        val dropped = dismissed.offer(listOf(second))

        assertEquals(first, queue.notice)
        assertEquals(second, dismissed.notice)
        assertNull(dismissed.presented)
        assertEquals(setOf("a" to "p1"), dismissed.dismissed)
        assertTrue(dropped.dismissed.isEmpty())
    }

    /** However many requests are open there is one notice, and a repeated one takes it before an older request. */
    @Test
    fun manyRequestsMakeOneNoticeWithARepeatedOneFirst() {
        val older = DeviceLinkRequest("a", "p1", 10)
        val repeated = DeviceLinkRequest("b", "p2", 20, repeated = true)

        assertEquals(repeated, DeviceLinkRequestQueue().offer(listOf(older, repeated)).notice)
        assertEquals(older, DeviceLinkRequestQueue().offer(listOf(older, repeated.copy(repeated = false))).notice)
    }

    /** A request that reached comparison while closed waits for a tap instead of opening. */
    @Test
    fun aComparisonNeverOpensByItself() =
        runTest {
            var state: DeviceLinkPromptStateFfi = DeviceLinkPromptStateFfi.Preparing
            val watcher = DeviceLinkRequestWatcher(readSnapshot = { snapshot(prompt("p", 1u, state)) })
            watcher.reread("a")
            assertNull(watcher.queue.presented)

            watcher.open(watcher.queue.notice!!)
            watcher.close()
            state = DeviceLinkPromptStateFfi.Comparing(emptyList())
            watcher.reread("a")

            assertNull(watcher.queue.presented)
            assertTrue(watcher.queue.notice?.comparing == true)
        }

    /** The watcher remembers the shell's lock, so the notice opens nothing until it is reported unlocked. */
    @Test
    fun theWatcherOpensNothingWhileBlocked() =
        runTest {
            val watcher = DeviceLinkRequestWatcher(readSnapshot = { snapshot(prompt("p", 1u)) })
            watcher.blocked = true
            watcher.reread("a")
            val notice = watcher.queue.notice!!

            watcher.open(notice)
            assertNull(watcher.queue.presented)

            watcher.blocked = false
            watcher.open(notice)
            assertEquals("p", watcher.queue.presented?.promptId)
        }

    /** Not now on another profile's notice keeps it quiet when the same request reaches comparison. */
    @Test
    fun anIgnoredOtherProfileRequestStaysQuiet() =
        runTest {
            var state: DeviceLinkPromptStateFfi = DeviceLinkPromptStateFfi.Preparing
            val watcher =
                DeviceLinkRequestWatcher(
                    readSnapshot = { snapshot(prompt("p", 1u, state)) },
                    canPresentOutcome = { false },
                )
            watcher.reread("other")
            watcher.dismiss(watcher.queue.notice!!)
            state = DeviceLinkPromptStateFfi.Comparing(emptyList())
            watcher.reread("other")
            assertNull(watcher.queue.presented)
            assertNull(watcher.queue.notice)
        }

    /** A settled request for another account drops its notice, and the next request takes it. */
    @Test
    fun otherProfileNoticeEndsWhenTheRequestSettles() =
        runTest {
            var first: DeviceLinkPromptStateFfi = DeviceLinkPromptStateFfi.Preparing
            val watcher =
                DeviceLinkRequestWatcher(
                    readSnapshot = { ref ->
                        if (ref == "other") snapshot(prompt("p", 1u, first)) else snapshot(prompt("next", 2u))
                    },
                    canPresentOutcome = { it == "mine" },
                )
            watcher.refresh(listOf("other", "mine"))
            assertEquals("other", watcher.queue.notice?.accountRef)
            watcher.open(watcher.queue.notice!!)
            first = ENDED
            watcher.reread("other")
            assertNull(watcher.queue.presented)
            assertEquals("next", watcher.queue.notice?.promptId)
        }

    /** A request MDK no longer lists stops being presented. */
    @Test
    fun aRequestMdkDroppedIsNoLongerPresented() {
        val request = DeviceLinkRequest("a", "p1", 10)
        val presented = DeviceLinkRequestQueue().offer(listOf(request)).open(request, blocked = false)

        assertNull(presented.offer(emptyList()).presented)
    }

    /** The watcher reads every signed-in profile and announces the oldest open request, whichever profile owns it. */
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

            watcher.refresh(listOf("a", "b"))

            assertEquals(listOf("a", "b"), reads)
            assertNull(watcher.queue.presented)
            assertEquals(DeviceLinkRequest("b", "b1", 5), watcher.queue.notice)
            watcher.open(watcher.queue.notice!!)
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
            watcher.refresh(listOf("a"))
            watcher.open(watcher.queue.notice!!)
            fail = true

            watcher.refresh(listOf("a"))

            assertEquals(DeviceLinkRequest("a", "p1", 10), watcher.queue.presented)
        }

    /** A profile that signed out is dropped with its requests. */
    @Test
    fun aSignedOutProfileIsDropped() =
        runTest {
            val watcher = DeviceLinkRequestWatcher(readSnapshot = { snapshot(prompt("p1", 10u)) })
            watcher.refresh(listOf("a"))

            watcher.refresh(emptyList())

            assertNull(watcher.queue.notice)
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

            watcher.onEvent(MarmotEventFfi.DeviceLinkChanged("id", "b"), accounts)
            watcher.onEvent(MarmotEventFfi.DeviceLinkChanged("id", "other"), accounts)
            watcher.onEvent(MarmotEventFfi.LinkedDevicesChanged("id", "a"), accounts)

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

            watcher.refresh(listOf("a"))

            assertNull(watcher.queue.notice)
            assertTrue(watcher.snapshots.isEmpty())
        }

    /** A snapshot returned by a command updates the prompt straight away. */
    @Test
    fun aCommandsSnapshotUpdatesThePrompt() =
        runTest {
            val watcher = DeviceLinkRequestWatcher(readSnapshot = { snapshot(prompt("p1", 10u)) })
            watcher.refresh(listOf("a"))
            watcher.open(watcher.queue.notice!!)

            val linking = snapshot(prompt("p1", 10u, DeviceLinkPromptStateFfi.Linking))
            watcher.accept("a", linking, beforeCommand = watcher.snapshots["a"])

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
                        watcher.accept("a", linking, beforeCommand = watcher.snapshots["a"])
                        snapshot(prompt("p1", 10u))
                    },
                )

            watcher.refresh(listOf("a"))
            assertEquals(DeviceLinkPromptStateFfi.Linking, watcher.prompt("a", "p1")?.state)

            watcher.onEvent(MarmotEventFfi.DeviceLinkChanged("id", "a"), listOf("a"))
            assertEquals(DeviceLinkPromptStateFfi.Linking, watcher.prompt("a", "p1")?.state)
        }

    /** A delayed command and a failed reconciliation must preserve an independently observed terminal state. */
    @Test
    fun aSettledPromptSurvivesAnOlderCommandAndFailedRead() =
        runTest {
            var current = snapshot(prompt("p1", 10u))
            var fails = false
            val watcher =
                DeviceLinkRequestWatcher(readSnapshot = {
                    check(!fails)
                    current
                })
            watcher.refresh(listOf("a"))
            val beforeCommand = watcher.snapshots["a"]
            current = snapshot(prompt("p1", 10u, DeviceLinkPromptStateFfi.Linked))
            watcher.reread("a")
            fails = true

            val delayed = snapshot(prompt("p1", 10u, DeviceLinkPromptStateFfi.Linking))
            watcher.accept("a", delayed, beforeCommand = beforeCommand)
            watcher.reread("a")

            assertEquals(DeviceLinkPromptStateFfi.Linked, watcher.prompt("a", "p1")?.state)
        }

    /** Closing the prompt answers nothing; unblocking later does not bring it back over the app. */
    @Test
    fun closingThePresentedRequestAnswersNothing() =
        runTest {
            var reads = 0
            val watcher =
                DeviceLinkRequestWatcher(
                    readSnapshot = {
                        reads++
                        snapshot(prompt("p1", 10u))
                    },
                )
            watcher.refresh(listOf("a"))
            watcher.open(watcher.queue.notice!!)

            watcher.close()
            watcher.blocked = false

            assertNull(watcher.queue.presented)
            assertEquals("p1", watcher.queue.notice?.promptId)
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
