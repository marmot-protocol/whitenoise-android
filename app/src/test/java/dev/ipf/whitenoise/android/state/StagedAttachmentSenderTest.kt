package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The composer's staged-attachment send, as offered to senders that do not go through the composer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class StagedAttachmentSenderTest {
    /** An empty shelf is not a send: the caller keeps its text-only path. */
    @Test
    fun nothingStagedRefusesWithoutDispatching() =
        runBlocking {
            val dispatched = mutableListOf<String>()
            val sender = sender(staged = false, dispatched = dispatched)

            assertFalse(sender.hasStagedAttachments())
            assertFalse(sender.sendWithCaption("hello") { error("must not report a pending message") })
            assertTrue(dispatched.isEmpty())
        }

    /** A shelf that is still preparing or already sending ignores an ordinary Send, so this one refuses too. */
    @Test
    fun busyShelfRefusesWithoutDispatching() =
        runBlocking {
            val dispatched = mutableListOf<String>()
            val sender = sender(busy = true, dispatched = dispatched)

            assertTrue(sender.hasStagedAttachments())
            assertFalse(sender.sendWithCaption("hello") { error("must not report a pending message") })
            assertTrue(dispatched.isEmpty())
        }

    /** The caption reaches the composer's own send and the pending message is reported exactly once. */
    @Test
    fun acceptedSendCarriesTheCaptionAndReportsPendingOnce() =
        runBlocking {
            val dispatched = mutableListOf<String>()
            var pending = 0
            val sender = sender(dispatched = dispatched)

            assertTrue(sender.sendWithCaption("edited dictation") { pending++ })

            assertEquals(listOf("edited dictation"), dispatched)
            assertEquals(1, pending)
        }

    /** A rejected send publishes nothing, so no pending message is reported and the caller keeps its draft. */
    @Test
    fun rejectedSendReportsNoPendingMessage() =
        runBlocking {
            val dispatched = mutableListOf<String>()
            var pending = 0
            val sender = sender(accept = false, dispatched = dispatched)

            assertFalse(sender.sendWithCaption("edited dictation") { pending++ })

            assertEquals(listOf("edited dictation"), dispatched)
            assertEquals(0, pending)
        }

    /** Builds a sender whose composer is staged and idle unless a test says otherwise. */
    private fun sender(
        staged: Boolean = true,
        busy: Boolean = false,
        accept: Boolean = true,
        dispatched: MutableList<String>,
    ) = StagedAttachmentSender(
        hasStaged = { staged },
        isBusy = { busy },
        dispatch = { caption, onResult ->
            dispatched += caption
            onResult(accept)
        },
    )
}
