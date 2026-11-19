package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackTrustRequestTest {
    @Test
    fun currentRequestCanProceed() {
        val request = TtsPlaybackTrustRequest { true }
        request.respond(true)
        assertTrue(runBlocking { request.answer.await() })
    }

    @Test
    fun staleRequestCannotProceed() {
        var current = true
        val request = TtsPlaybackTrustRequest { current }
        current = false
        request.respond(true)
        assertFalse(runBlocking { request.answer.await() })
    }

    @Test
    fun dismissalCannotBeOverriddenByALateConfirmation() {
        val request = TtsPlaybackTrustRequest { true }
        request.respond(false)
        request.respond(true)
        assertFalse(runBlocking { request.answer.await() })
    }
}
