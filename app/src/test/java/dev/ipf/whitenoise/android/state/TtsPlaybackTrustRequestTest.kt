package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackTrustRequestTest {
    /** An affirmative answer releases a request that still has its original owner. */
    @Test
    fun currentRequestCanProceed() {
        val request = TtsPlaybackTrustRequest { true }
        request.respond(true)
        assertTrue(runBlocking { request.awaitApproval() })
    }

    /** A retired playback owner cannot approve an outstanding question. */
    @Test
    fun staleRequestCannotProceed() {
        var current = true
        val request = TtsPlaybackTrustRequest { current }
        current = false
        request.respond(true)
        assertFalse(runBlocking { request.awaitApproval() })
    }

    /** Dismissing a question wins over a stale affirmative callback. */
    @Test
    fun dismissalCannotBeOverriddenByALateConfirmation() {
        val request = TtsPlaybackTrustRequest { true }
        request.respond(false)
        request.respond(true)
        assertFalse(runBlocking { request.awaitApproval() })
    }

    /** A later dismissal retires approval before the waiting playback consumes it. */
    @Test
    fun dismissalRevokesConfirmationBeforePlaybackConsumesIt() {
        val request = TtsPlaybackTrustRequest { true }
        request.respond(true)
        request.respond(false)
        assertFalse(runBlocking { request.awaitApproval() })
    }
}
