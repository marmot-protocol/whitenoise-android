package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AmberSignInFailureTest {
    /** Both staged onboarding and legacy login share this duplicate-account guidance. */
    @Test
    fun duplicateIdentityUsesAccountSwitcherGuidance() {
        val duplicate = MarmotKitException.DuplicateIdentity("private account")
        assertEquals(
            AppText.Resource(R.string.amber_identity_already_added),
            amberSignInFailureDetail(duplicate),
        )
        assertEquals(
            AppText.Resource(R.string.amber_identity_already_added),
            amberSignInFailureDetail(IllegalStateException("onboarding failed", duplicate)),
        )
    }

    /** Other Amber failures keep the existing generic recovery detail. */
    @Test
    fun unrelatedFailureKeepsGenericDetail() {
        assertEquals(
            AppText.Resource(R.string.error_try_again),
            amberSignInFailureDetail(MarmotKitException.ExternalSignerUnavailable("private account")),
        )
    }
}
