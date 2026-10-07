package dev.ipf.whitenoise.android.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Late destination callbacks cannot repopulate diagnostics across a revoke or Clear boundary. */
@RunWith(RobolectricTestRunner::class)
class DmFrameDiagnosticBoundaryTest {
    private val records = mutableListOf<Map<String, Any>>()
    private val workerFailure = AtomicReference<Throwable?>(null)

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

    /** An opening admitted while disabled must not survive a later grant or emit a first-frame START. */
    @Test
    fun disabledAdmissionCannotCrossIntoTheNextGrant() {
        DmCreationDiagnostics.setEnabled(false)
        val old = DmCreationInteraction(records::add).nextAttempt()
        DmCreationDiagnostics.awaitFrame("a", "g", 1, old)
        assertNull(DmCreationDiagnostics.pendingFrame("a", "g", 1))
        DmCreationDiagnostics.setEnabled(true)
        DmCreationDiagnostics.firstFrame("a", "g", 1, old)
        assertEquals(0, records.size)
        completeFreshTicket()
    }

    /** Clear cannot finish between a captured START callback and insertion of that same ticket. */
    @Test
    fun clearSerializesWithTheEntireAdmission() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clearing = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        val attempt =
            DmCreationInteraction(emit = {
                records.add(it)
                if (it["outcome"] == "start") {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
            }).nextAttempt()
        val admission = boundaryWorker { DmCreationDiagnostics.awaitFrame("a", "g", 1, attempt) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val clear =
            boundaryWorker {
                clearing.countDown()
                DmCreationDiagnostics.clear()
                cleared.countDown()
            }
        try {
            assertTrue(clearing.await(5, TimeUnit.SECONDS))
            assertFalse(cleared.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            admission.join(5000)
            clear.join(5000)
        }
        assertFalse(admission.isAlive || clear.isAlive)
        assertNull(workerFailure.get())
        assertNull(DmCreationDiagnostics.pendingFrame("a", "g", 1))
        DmCreationDiagnostics.firstFrame("a", "g", 1, attempt)
        assertEquals(0, records.count { it["outcome"] == "success" })
    }

    /** Reapplying an enabled grant cannot interrupt a frame already emitting its only completion. */
    @Test
    fun enabledGrantSerializesWithFrameCompletion() {
        val completing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val granting = CountDownLatch(1)
        val granted = CountDownLatch(1)
        val attempt =
            DmCreationInteraction(emit = {
                if (it["outcome"] == "success") {
                    completing.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
                records.add(it)
                DmCreationDiagnostics.record(it)
            }).nextAttempt()
        DmCreationDiagnostics.awaitFrame("a", "g", 1, attempt)
        val completion = boundaryWorker { DmCreationDiagnostics.firstFrame("a", "g", 1, attempt) }
        assertTrue(completing.await(5, TimeUnit.SECONDS))
        val grant =
            boundaryWorker {
                granting.countDown()
                DmCreationDiagnostics.setEnabled(true)
                granted.countDown()
            }
        try {
            assertTrue(granting.await(5, TimeUnit.SECONDS))
            assertFalse(granted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            completion.join(5000)
            grant.join(5000)
        }
        assertFalse(completion.isAlive || grant.isAlive)
        assertNull(workerFailure.get())
        assertEquals(1, records.count { it["outcome"] == "success" })
        val retained = DmCreationDiagnostics.snapshot().filterKeys { it.endsWith(".jsonl") }
        val successes =
            retained.values.sumOf { bytes ->
                bytes
                    .toString(Charsets.UTF_8)
                    .lineSequence()
                    .filter(String::isNotBlank)
                    .count { JSONObject(it).optString("outcome") == "success" }
            }
        assertEquals(1, successes)
    }

    /** Captures worker failures for the owning test instead of silently losing background assertions. */
    private fun boundaryWorker(action: () -> Unit): Thread =
        thread(name = "dm-diagnostic-boundary") {
            runCatching(action).exceptionOrNull()?.let { workerFailure.compareAndSet(null, it) }
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
