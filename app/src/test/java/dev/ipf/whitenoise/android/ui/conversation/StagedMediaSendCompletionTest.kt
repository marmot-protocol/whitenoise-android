package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.state.StagedAttachmentSendClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retry settlement must not complete an already rejected UI attempt or unlock its successor. */
class StagedMediaSendCompletionTest {
    /** A failed send keeps a distinct eventual cleanup owner while its old claim completes only once. */
    @Test
    fun durableRetryCannotReportTwiceOrReleaseNewerClaim() {
        val claim = StagedAttachmentSendClaim()
        val results = mutableListOf<Boolean>()
        var settlements = 0
        lateinit var first: StagedMediaSendCompletion
        claim.send(results::add) { accepted, rejected ->
            first = StagedMediaSendCompletion(accepted, rejected) { settlements++ }
        }
        first.reject()
        claim.send(results::add) { _, _ -> }
        assertTrue(claim.isHeld)
        first.accept()
        first.accept()
        assertTrue(claim.isHeld)
        assertEquals(listOf(false), results)
        assertEquals(1, settlements)
    }

    /** Initial acceptance clears the exact shelf and reports success once, even when durable cleanup repeats. */
    @Test
    fun firstAcceptanceCompletesShelfAndAttemptOnce() {
        val calls = mutableListOf<String>()
        val completion =
            StagedMediaSendCompletion({ calls += "accepted" }, { calls += "rejected" }, { calls += "settled" })
        completion.accept()
        completion.reject()
        completion.accept()
        assertEquals(listOf("settled", "accepted"), calls)
    }
}
