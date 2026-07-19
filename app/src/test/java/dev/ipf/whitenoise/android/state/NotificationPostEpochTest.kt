package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPostEpochTest {
    @Test
    fun unchangedVisibilityKeepsThePostCurrent() {
        val epoch = NotificationPostEpoch()

        assertTrue(epoch.isCurrent(epoch.capture()))
    }

    @Test
    fun openingThenClosingDuringEnrichmentInvalidatesTheOriginalPost() {
        val epoch = NotificationPostEpoch()
        val captured = epoch.capture()

        epoch.advance()
        epoch.advance()

        assertFalse(epoch.isCurrent(captured))
    }
}
