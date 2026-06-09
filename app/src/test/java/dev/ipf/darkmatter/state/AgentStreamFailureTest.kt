package dev.ipf.darkmatter.state

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AgentStreamFailureTest {
    @Test
    fun cancellationIsPropagatedInsteadOfRenderingFailure() {
        val cancellation = CancellationException("screen left")

        val thrown =
            try {
                agentStreamFailureText(cancellation, ConversationControllerCopy())
                null
            } catch (error: CancellationException) {
                error
            }

        assertSame(cancellation, thrown)
    }

    @Test
    fun nonCancellationThrowableRendersFailureText() {
        val text =
            agentStreamFailureText(
                IllegalStateException("boom"),
                ConversationControllerCopy(),
            )

        assertEquals("Stream failed: boom", text)
    }
}
