package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.DeviceLinkEndReasonFfi
import dev.ipf.marmotkit.DeviceLinkPromptFfi
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A second unanswered or declined request on one profile within an hour stops Link being offered there. */
class DeviceLinkRepeatedRequestsTest {
    /** Waiting and unlinked endings count; anything this user or another device approved does not. */
    @Test
    fun onlyRequestsNobodyApprovedCountAsUnanswered() {
        val unanswered =
            listOf(
                DeviceLinkPromptStateFfi.Preparing,
                DeviceLinkPromptStateFfi.Comparing(emptyList()),
                DeviceLinkPromptStateFfi.CompetingRequests,
                DeviceLinkPromptStateFfi.RateLimited(1u),
                DeviceLinkPromptStateFfi.CannotLink(emptyList()),
                DeviceLinkPromptStateFfi.TooManyDevices,
                ended(DeviceLinkEndReasonFfi.TIMED_OUT),
                ended(DeviceLinkEndReasonFfi.CANCELLED_ELSEWHERE),
                ended(DeviceLinkEndReasonFfi.CODES_DIFFER),
                ended(DeviceLinkEndReasonFfi.REJECTED),
            )
        val answered =
            listOf(
                DeviceLinkPromptStateFfi.Linking,
                DeviceLinkPromptStateFfi.Linked,
                DeviceLinkPromptStateFfi.AddedElsewhere("a", "b"),
                ended(DeviceLinkEndReasonFfi.LINK_FAILED),
                ended(DeviceLinkEndReasonFfi.ANSWERED_ELSEWHERE),
            )

        unanswered.forEach { assertTrue(it.toString(), it.isUnanswered()) }
        answered.forEach { assertFalse(it.toString(), it.isUnanswered()) }
    }

    /** One request is not repeated; a second open one on the same profile marks both, and no other profile. */
    @Test
    fun aSecondRequestOnTheSameProfileIsRepeated() {
        val one = unansweredLinkRequests(emptyMap(), mapOf("a" to snapshot(prompt("p1"))), NOW)
        val two =
            unansweredLinkRequests(
                one,
                mapOf("a" to snapshot(prompt("p1"), prompt("p2")), "b" to snapshot(prompt("q"))),
                NOW,
            )

        assertTrue(repeatedLinkRequestAccounts(one).isEmpty())
        assertEquals(setOf("a"), repeatedLinkRequestAccounts(two))
    }

    /** A declined request leaves MDK's list at once but still counts, so the next request is repeated. */
    @Test
    fun aDeclinedRequestStillCounts() {
        val seen = unansweredLinkRequests(emptyMap(), mapOf("a" to snapshot(prompt("p1"))), NOW)
        val declined = unansweredLinkRequests(seen, mapOf("a" to snapshot()), NOW + 1_000L)
        val next = unansweredLinkRequests(declined, mapOf("a" to snapshot(prompt("p2"))), NOW + 2_000L)

        assertEquals(setOf("a"), repeatedLinkRequestAccounts(next))
    }

    /** A request the user linked stops counting, so a later request for another device is offered normally. */
    @Test
    fun aLinkedRequestStopsCounting() {
        val seen = unansweredLinkRequests(emptyMap(), mapOf("a" to snapshot(prompt("p1"))), NOW)
        val linking =
            unansweredLinkRequests(seen, mapOf("a" to snapshot(prompt("p1", DeviceLinkPromptStateFfi.Linking))), NOW)
        val next = unansweredLinkRequests(linking, mapOf("a" to snapshot(prompt("p2"))), NOW)

        assertTrue(repeatedLinkRequestAccounts(next).isEmpty())
    }

    /** A request first seen more than an hour ago no longer counts. */
    @Test
    fun anHourOldRequestNoLongerCounts() {
        val seen = unansweredLinkRequests(emptyMap(), mapOf("a" to snapshot(prompt("p1"))), NOW)
        val later = NOW + REPEATED_LINK_REQUEST_WINDOW_MS
        val next = unansweredLinkRequests(seen, mapOf("a" to snapshot(prompt("p2"))), later)

        assertTrue(repeatedLinkRequestAccounts(next).isEmpty())
        assertEquals(setOf("p2"), next["a"]?.keys)
    }

    /** A profile that is no longer watched is forgotten. */
    @Test
    fun anUnwatchedProfileIsForgotten() {
        val seen = unansweredLinkRequests(emptyMap(), mapOf("a" to snapshot(prompt("p1"), prompt("p2"))), NOW)

        assertTrue(unansweredLinkRequests(seen, emptyMap(), NOW).isEmpty())
    }

    /**
     * Through the watcher: a request declined and then a second one makes that second request repeated, it takes the
     * one notice, and an approval already under way is not marked.
     */
    @Test
    fun theWatcherMarksRepeatedRequestsFromMdkSnapshots() =
        runTest {
            var now = NOW
            var prompts = listOf(prompt("p1"))
            val watcher =
                DeviceLinkRequestWatcher(readSnapshot = { snapshot(*prompts.toTypedArray()) }, nowMs = { now })
            watcher.reread("a")
            assertFalse(watcher.queue.notice!!.repeated)

            prompts = emptyList()
            watcher.reread("a")
            assertNull(watcher.queue.notice)

            now += 10 * 60 * 1000L
            prompts = listOf(prompt("p2", DeviceLinkPromptStateFfi.Comparing(emptyList())))
            watcher.reread("a")
            assertTrue(watcher.queue.notice!!.repeated)

            prompts = listOf(prompt("p2", DeviceLinkPromptStateFfi.Linking))
            watcher.reread("a")
            assertFalse(
                watcher.queue.requests
                    .single()
                    .repeated,
            )
        }

    /** A repeated request that arrives during an open comparison turns the comparison into the warning. */
    @Test
    fun anOpenComparisonTurnsRepeatedWhenAnotherRequestArrives() =
        runTest {
            var prompts = listOf(prompt("p1", DeviceLinkPromptStateFfi.Comparing(emptyList())))
            val watcher =
                DeviceLinkRequestWatcher(readSnapshot = { snapshot(*prompts.toTypedArray()) }, nowMs = { NOW })
            watcher.reread("a")
            watcher.open(watcher.queue.notice!!)
            assertFalse(watcher.queue.presented!!.repeated)

            prompts = prompts + prompt("p2")
            watcher.reread("a")

            assertEquals("p1", watcher.queue.presented?.promptId)
            assertTrue(watcher.queue.presented!!.repeated)
            assertEquals("p2", watcher.queue.notice?.promptId)
        }

    private companion object {
        const val NOW = 5_000_000L

        fun ended(reason: DeviceLinkEndReasonFfi) = DeviceLinkPromptStateFfi.Ended(reason)

        fun prompt(
            id: String,
            state: DeviceLinkPromptStateFfi = DeviceLinkPromptStateFfi.Preparing,
        ) = DeviceLinkPromptFfi(promptId = id, slotIdHex = null, requestedAt = 1u, state = state)

        fun snapshot(vararg prompts: DeviceLinkPromptFfi) =
            DeviceLinkSnapshotFfi(
                newDevice = null,
                prompts = prompts.toList(),
                moves = emptyList(),
            )
    }
}
