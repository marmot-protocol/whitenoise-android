package dev.ipf.whitenoise.android.amber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the request-id correlation that stops a late result from a prior,
 * timed-out Amber prompt from satisfying the next caller (which would hand one
 * operation's signed payload to a different operation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AmberActivityCoordinatorTest {
    @Test
    fun staleResultCannotSatisfyGetPublicKey() {
        // A sign_event timed out; its late result must not satisfy the next
        // get_public_key request.
        assertFalse(
            AmberActivityCoordinator.shouldAcceptResult(
                expectedId = "login-req-7f3a",
                resultId = "stale-sign-event-id",
            ),
        )
    }

    @Test
    fun matchingRequestIdsAreAccepted() {
        assertTrue(AmberActivityCoordinator.shouldAcceptResult(expectedId = "req-A", resultId = "req-A"))
    }

    @Test
    fun resultWithoutIdIsDropped() {
        assertFalse(AmberActivityCoordinator.shouldAcceptResult(expectedId = "req-A", resultId = null))
    }

    @Test
    fun getPublicKeyIntentCarriesClientRequestId() {
        val requestId = "login-req-7f3a"
        val intent = Nip55.buildGetPublicKeyIntent(Nip55.defaultPermissionsJson(), requestId)
        assertEquals(requestId, intent.getStringExtra(Nip55.EXTRA_ID))
    }
}
