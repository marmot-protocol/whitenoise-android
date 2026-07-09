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
    fun getPublicKeyBothMissingIsAccepted() {
        // get_public_key sends no id and its result echoes none: both null match.
        assertTrue(AmberActivityCoordinator.shouldAcceptResult(expectedId = null, resultId = null))
    }

    @Test
    fun idBearingRequestDropsResultWithoutId() {
        // An id-bearing request whose result omits the id (a stale prompt's late
        // result) is never delivered.
        assertFalse(AmberActivityCoordinator.shouldAcceptResult(expectedId = "req-A", resultId = null))
    }

    @Test
    fun noIdRequestAcceptsSignerGeneratedResultId() {
        // get_public_key sends no id, but signers answer it with a
        // self-generated id. The prompt lock keeps a single prompt pending, so
        // the no-id request accepts whatever id the result carries — dropping
        // it would block the caller for the full approval timeout.
        assertTrue(AmberActivityCoordinator.shouldAcceptResult(expectedId = null, resultId = "d0438f"))
    }
}
