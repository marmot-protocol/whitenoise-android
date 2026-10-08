package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Keyboard Send and dictation Send share one claim, so a staged shelf is only ever queued once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class StagedAttachmentSendClaimTest {
    /** A keyboard Send that arrives while a dictation dispatch is held before acceptance queues nothing. */
    @Test
    fun keyboardSendWhileDictationDispatchIsHeldQueuesExactlyOneBatch() =
        runBlocking {
            val screen = StagedScreen()
            val dictation =
                StagedAttachmentSender(hasStaged = screen::hasStaged, isBusy = screen::isBusy, dispatch = screen::send)
            val dictationOutcome = async(start = CoroutineStart.UNDISPATCHED) { dictation.sendWithCaption("spoken") {} }

            assertTrue("the dictation dispatch is held, not yet accepted", dictationOutcome.isActive)
            assertEquals(listOf(Batch("spoken", PHOTOS)), screen.queued)

            val keyboardResults = mutableListOf<Boolean>()
            screen.send("typed") { keyboardResults += it }

            assertEquals(listOf(false), keyboardResults)
            assertEquals("only the dictation batch was queued", listOf(Batch("spoken", PHOTOS)), screen.queued)
            assertTrue("the refused keyboard Send left the dictation claim alone", screen.claim.isHeld)

            screen.settle(0, accepted = true)

            assertTrue(dictationOutcome.await())
            assertEquals(1, screen.queued.size)
            assertTrue("the shelf was cleared by the accepted send", screen.shelf.isEmpty())
        }

    /** A dictation dispatch that passed the early busy check is still refused once a keyboard Send owns the shelf. */
    @Test
    fun dictationDispatchThatPassedTheEarlyBusyCheckIsRefusedByTheClaim() =
        runBlocking {
            val screen = StagedScreen()
            val keyboardResults = mutableListOf<Boolean>()
            screen.send("typed") { keyboardResults += it }
            // The dictation thread checked isBusy before the keyboard Send claimed, then dispatched on main.
            val staleEarlyCheck =
                StagedAttachmentSender(hasStaged = { true }, isBusy = { false }, dispatch = screen::send)

            // Without the refusal the dispatch would wait for a settle that never comes, so bound the wait.
            val published =
                withTimeout(REFUSAL_TIMEOUT_MS) { staleEarlyCheck.sendWithCaption("spoken") { fail("held") } }

            assertFalse(published)

            assertEquals(listOf(Batch("typed", PHOTOS)), screen.queued)
            assertTrue(keyboardResults.isEmpty())
            assertTrue("the keyboard send still owns the shelf", screen.claim.isHeld)
        }

    /** Acceptance releases the claim, so the next Send on a freshly staged shelf is allowed. */
    @Test
    fun claimIsReleasedAfterAcceptanceAndALaterSendIsAllowed() {
        val screen = StagedScreen()
        val results = mutableListOf<Boolean>()
        screen.send("first") { results += it }
        assertTrue(screen.claim.isHeld)

        screen.settle(0, accepted = true)

        assertEquals(listOf(true), results)
        assertFalse("acceptance released the claim", screen.claim.isHeld)

        screen.shelf = listOf("later.jpg")
        screen.send("second") { results += it }

        assertEquals(listOf(Batch("first", PHOTOS), Batch("second", listOf("later.jpg"))), screen.queued)
        assertTrue(screen.claim.isHeld)
    }

    /** Rejection releases the claim and leaves the shelf staged, so the same Send can be retried. */
    @Test
    fun claimIsReleasedAfterRejectionAndTheShelfCanBeRetried() {
        val screen = StagedScreen()
        val results = mutableListOf<Boolean>()
        screen.send("first") { results += it }

        screen.settle(0, accepted = false)

        assertEquals(listOf(false), results)
        assertFalse("rejection released the claim", screen.claim.isHeld)
        assertEquals("a rejected send keeps the shelf", PHOTOS, screen.shelf)

        screen.send("retry") { results += it }

        assertEquals(listOf(Batch("first", PHOTOS), Batch("retry", PHOTOS)), screen.queued)
    }

    /** A refused Send reports false and never gives up the first Send's ownership, however often it retries. */
    @Test
    fun refusedSendDoesNotResetTheFirstSendsClaim() {
        val screen = StagedScreen()
        val results = mutableListOf<Boolean>()
        screen.send("first") { results += it }

        repeat(3) { screen.send("competing") { results += it } }

        assertEquals(listOf(false, false, false), results)
        assertTrue("the first send still owns the claim", screen.claim.isHeld)
        assertEquals(1, screen.queued.size)

        screen.settle(0, accepted = true)

        assertEquals(listOf(false, false, false, true), results)
        assertFalse(screen.claim.isHeld)
    }

    /** A held claim refuses before the pre-claim check runs, so a refused Send shows no over-limit message. */
    @Test
    fun heldClaimRefusesWithoutConsultingTheOverLimitCheck() {
        val claim = StagedAttachmentSendClaim()
        val first = claim.tryClaim()
        var consulted = false
        var started = false
        val results = mutableListOf<Boolean>()

        claim.send(results::add, canStart = {
            consulted = true
            true
        }) { _, _ -> started = true }

        assertNotNull(first)
        assertFalse(consulted)
        assertFalse(started)
        assertEquals(listOf(false), results)
        assertTrue(claim.isHeld)
    }

    /** An over-limit shelf is refused before anything is claimed, so it cannot lock the shelf. */
    @Test
    fun refusalBeforeClaimingLeavesTheClaimFree() {
        val claim = StagedAttachmentSendClaim()
        var started = false
        val results = mutableListOf<Boolean>()

        claim.send(results::add, canStart = { false }) { _, _ -> started = true }

        assertFalse(started)
        assertEquals(listOf(false), results)
        assertFalse(claim.isHeld)
    }

    /** A dispatch that throws before it starts releases the claim and rethrows, so the shelf is not locked. */
    @Test
    fun dispatchThatThrowsBeforeStartingReleasesTheClaim() {
        val claim = StagedAttachmentSendClaim()
        val results = mutableListOf<Boolean>()

        try {
            claim.send(results::add) { _, _ -> error("could not start") }
            fail("the failure must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("could not start", expected.message)
        }

        assertTrue(results.isEmpty())
        assertFalse(claim.isHeld)
    }

    /** A send the media sender rejects synchronously, such as an empty shelf, releases the claim at once. */
    @Test
    fun synchronousRejectionReleasesTheClaim() {
        val claim = StagedAttachmentSendClaim()
        val results = mutableListOf<Boolean>()

        claim.send(results::add) { _, onRejected -> onRejected() }

        assertEquals(listOf(false), results)
        assertFalse(claim.isHeld)
    }

    /** Only the hold that took the claim can give it up, and a stale or repeated release never frees a newer one. */
    @Test
    fun staleOrRepeatedReleaseCannotFreeANewerClaim() {
        val claim = StagedAttachmentSendClaim()
        val first = claim.tryClaim()
        assertNotNull(first)
        assertNull("a held claim cannot be taken again", claim.tryClaim())
        first!!.release()
        assertFalse(claim.isHeld)

        val second = claim.tryClaim()
        assertNotNull(second)
        first.release()

        assertTrue("the earlier hold cannot free the newer claim", claim.isHeld)
        second!!.release()
        second.release()
        assertFalse(claim.isHeld)
    }

    /** One queued attachment batch, as the media sender would receive it. */
    private data class Batch(
        val caption: String,
        val attachments: List<String>,
    )

    /**
     * The conversation screen's attachment entry point with a media sender that settles when told to.
     *
     * A started send captures the shelf immediately but only clears it on acceptance, exactly like the
     * screen, so a second send that starts in between would capture the same shelf again.
     */
    private class StagedScreen {
        val claim = StagedAttachmentSendClaim()
        var shelf: List<String> = PHOTOS
        val queued = mutableListOf<Batch>()
        private val settlers = mutableListOf<(accepted: Boolean) -> Unit>()

        /** Whether the shelf holds anything an ordinary Send would carry. */
        fun hasStaged(): Boolean = shelf.isNotEmpty()

        /** Whether a send already owns the shelf. */
        fun isBusy(): Boolean = claim.isHeld

        /** Starts a send through the shared claim, holding it until [settle] is called for it. */
        fun send(
            caption: String,
            onResult: (Boolean) -> Unit,
        ) {
            claim.send(onResult) { onAccepted, onRejected ->
                val captured = shelf
                queued += Batch(caption, captured)
                settlers +=
                    { accepted: Boolean ->
                        if (accepted) {
                            shelf = shelf - captured.toSet()
                            onAccepted()
                        } else {
                            onRejected()
                        }
                    }
            }
        }

        /** Settles the send queued at [index] as accepted or rejected. */
        fun settle(
            index: Int,
            accepted: Boolean,
        ) = settlers[index](accepted)
    }

    private companion object {
        const val REFUSAL_TIMEOUT_MS = 5_000L
        val PHOTOS = listOf("one.jpg", "two.jpg")
    }
}
