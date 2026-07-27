package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.PushRegistrationShareOutcomeFfi
import dev.ipf.marmotkit.PushRegistrationShareStatusFfi
import org.junit.Assert.assertEquals
import org.junit.Test

class PushRegistrationSharingStateTest {
    @Test
    fun pendingOutcomeIsADeferredDurableShareNotAFailure() {
        val outcome =
            PushRegistrationShareOutcomeFfi(
                status = PushRegistrationShareStatusFfi.PENDING,
                attemptedGroups = 4u,
                succeededGroups = 2u,
                failedGroups = 2u,
                pendingGroups = 2u,
            )

        assertEquals(
            PushRegistrationSharingState.PendingDurableRetry,
            pushRegistrationSharingState(outcome),
        )
    }

    @Test
    fun completeOutcomeNeedsNoDeferredShare() {
        val outcome =
            PushRegistrationShareOutcomeFfi(
                status = PushRegistrationShareStatusFfi.COMPLETE,
                attemptedGroups = 4u,
                succeededGroups = 4u,
                failedGroups = 0u,
                pendingGroups = 0u,
            )

        assertEquals(
            PushRegistrationSharingState.Complete,
            pushRegistrationSharingState(outcome),
        )
    }
}
