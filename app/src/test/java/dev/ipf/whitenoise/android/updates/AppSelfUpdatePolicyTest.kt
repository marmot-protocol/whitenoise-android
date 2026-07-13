package dev.ipf.whitenoise.android.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSelfUpdatePolicyTest {
    @Test
    fun zapstoreBuildStartsInAppFlow() {
        assertTrue(shouldStartInAppSelfUpdate(selfUpdateEnabled = true))
        assertFalse(shouldOpenExternalZapstoreListing(selfUpdateEnabled = true))
    }

    @Test
    fun playBuildOpensExternalListing() {
        assertFalse(shouldStartInAppSelfUpdate(selfUpdateEnabled = false))
        assertTrue(shouldOpenExternalZapstoreListing(selfUpdateEnabled = false))
    }
}
