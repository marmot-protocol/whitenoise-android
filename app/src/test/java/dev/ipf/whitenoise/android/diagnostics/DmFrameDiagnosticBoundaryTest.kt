package dev.ipf.whitenoise.android.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Late destination callbacks cannot repopulate diagnostics across a revoke or Clear boundary. */
@RunWith(RobolectricTestRunner::class)
class DmFrameDiagnosticBoundaryTest {
    private val records = mutableListOf<Map<String, Any>>()

    /** Installs a fresh recorder while the captured sink exposes exactly which callbacks were admitted. */
    @Before
    fun attachRecorder() {
        DmCreationDiagnostics.attach(ApplicationProvider.getApplicationContext<Context>())
        DmCreationDiagnostics.setEnabled(true)
    }

    /** Restores disabled diagnostics and retires each sandbox's frame tickets and files. */
    @After
    fun closeRecorder() {
        DmCreationDiagnostics.setEnabled(false)
        DmCreationDiagnostics.clear()
    }

    /** Re-enabling within the ticket TTL must not revive a pre-revocation destination callback. */
    @Test
    fun consentRevocationRetiresTheOldTicketEvenAfterReenable() {
        val old = DmCreationInteraction(records::add).nextAttempt()
        DmCreationDiagnostics.awaitFrame("a", "g", 1, old)
        DmCreationDiagnostics.setEnabled(false)
        DmCreationDiagnostics.setEnabled(true)
        assertNull(DmCreationDiagnostics.pendingFrame("a", "g", 1))
        DmCreationDiagnostics.firstFrame("a", "g", 1, old)
        assertEquals(0, records.count { it["outcome"] == "success" })
        completeFreshTicket()
    }

    /** Clear keeps consent enabled but rejects both the old ticket and its late completion. */
    @Test
    fun clearRetiresTheOldTicketWithoutBlockingNewAttempts() {
        val old = DmCreationInteraction(records::add).nextAttempt()
        DmCreationDiagnostics.awaitFrame("a", "g", 1, old)
        DmCreationDiagnostics.clear()
        assertNull(DmCreationDiagnostics.pendingFrame("a", "g", 1))
        DmCreationDiagnostics.firstFrame("a", "g", 1, old)
        assertEquals(0, records.count { it["outcome"] == "success" })
        completeFreshTicket()
    }

    /** Reapplying enabled consent preserves normal first-frame continuity for an admitted attempt. */
    @Test
    fun continuouslyEnabledConsentPreservesTheTicket() {
        val attempt = DmCreationInteraction(records::add).nextAttempt()
        DmCreationDiagnostics.awaitFrame("a", "g", 1, attempt)
        DmCreationDiagnostics.setEnabled(true)
        assertEquals(attempt, DmCreationDiagnostics.pendingFrame("a", "g", 1))
        DmCreationDiagnostics.firstFrame("a", "g", 1, attempt)
        assertEquals(1, records.count { it["outcome"] == "success" })
    }

    /** A new recording epoch can admit and consume its own destination ticket exactly once. */
    private fun completeFreshTicket() {
        val fresh = DmCreationInteraction(records::add).nextAttempt()
        DmCreationDiagnostics.awaitFrame("a", "g", 1, fresh)
        DmCreationDiagnostics.firstFrame("a", "g", 1, fresh)
        DmCreationDiagnostics.firstFrame("a", "g", 1, fresh)
        assertEquals(1, records.count { it["outcome"] == "success" })
    }
}
