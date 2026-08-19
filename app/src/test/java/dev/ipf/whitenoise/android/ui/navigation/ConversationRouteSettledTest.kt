package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRouteSettledTest {
    @Test
    fun settlesOnlyWhenTheTransitionLandedAndTheDestinationIsReady() {
        assertTrue(
            conversationRouteSettled(
                currentStateMatchesTarget = true,
                transitionRunning = false,
                destinationContentReady = true,
            ),
        )
    }

    @Test
    fun neverSettlesMidFlightOrOnAnUnreadyDestination() {
        assertFalse(
            conversationRouteSettled(
                currentStateMatchesTarget = false,
                transitionRunning = false,
                destinationContentReady = true,
            ),
        )
        assertFalse(
            conversationRouteSettled(
                currentStateMatchesTarget = true,
                transitionRunning = true,
                destinationContentReady = true,
            ),
        )
        assertFalse(
            conversationRouteSettled(
                currentStateMatchesTarget = true,
                transitionRunning = false,
                destinationContentReady = false,
            ),
        )
    }
}
