package dev.ipf.whitenoise.android.amber

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the request-id correlation that stops a late result from a prior,
 * timed-out Amber prompt from satisfying the next caller (which would hand one
 * operation's signed payload to a different operation).
 */
class AmberActivityCoordinatorTest {
    @Test
    fun mismatchedRequestIdsAreDropped() {
        // A timed out; B is now the active request. A's late result must not
        // satisfy B.
        assertFalse(AmberActivityCoordinator.shouldAcceptResult(expectedId = "req-B", resultId = "req-A"))
    }

    @Test
    fun matchingRequestIdsAreAccepted() {
        assertTrue(AmberActivityCoordinator.shouldAcceptResult(expectedId = "req-A", resultId = "req-A"))
    }

    @Test
    fun missingIdsAreDeliveredWithoutCorrelation() {
        // get_public_key sends no id; some signer results may omit it. Either
        // side missing => deliver (no correlation possible), never drop.
        assertTrue(AmberActivityCoordinator.shouldAcceptResult(expectedId = null, resultId = null))
        assertTrue(AmberActivityCoordinator.shouldAcceptResult(expectedId = "req-A", resultId = null))
        assertTrue(AmberActivityCoordinator.shouldAcceptResult(expectedId = null, resultId = "req-A"))
    }
}
