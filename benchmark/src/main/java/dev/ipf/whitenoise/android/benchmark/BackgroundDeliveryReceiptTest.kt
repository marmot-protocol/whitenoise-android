package dev.ipf.whitenoise.android.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Pins receipt ownership and time boundaries independently of real transport or power claims. */
@RunWith(AndroidJUnit4::class)
class BackgroundDeliveryReceiptTest {
    private val texts = (1..5).map { "disposable-fixture-$it" }

    /** Arms a fresh measurement generation without registering a system listener. */
    @Before
    fun setUp() {
        BackgroundDeliveryReceipts.arm(FIXTURE_PACKAGE, texts)
        BackgroundDeliveryReceipts.connected = true
    }

    /** Releases synthetic content and listener state between instrumented cases. */
    @After
    fun tearDown() {
        BackgroundDeliveryReceipts.disarm()
        BackgroundDeliveryReceipts.connected = false
    }

    /** A sleep-only smoke run cannot satisfy the receive-side burst contract. */
    @Test
    fun emptyBurstFails() {
        BackgroundDeliveryReceipts.beginWindow(0, 100, 1_000)
        assertTrue(runCatching { BackgroundDeliveryReceipts.finishWindow(100, 1_100) }.isFailure)
    }

    /** Reposting one enriched card cannot stand in for receiving five different payloads. */
    @Test
    fun repeatedEnrichmentDoesNotCompleteMissingMessages() {
        BackgroundDeliveryReceipts.beginWindow(0, 100, 1_000)
        repeat(5) { BackgroundDeliveryReceipts.record(FIXTURE_PACKAGE, listOf(texts.first()), 50, 1_050, 1_050) }
        assertTrue(runCatching { BackgroundDeliveryReceipts.finishWindow(100, 1_100) }.isFailure)
    }

    /** A foreign package cannot contribute a matching body to the fixture's measured receipt set. */
    @Test
    fun anotherPackageCannotCompleteBurst() {
        BackgroundDeliveryReceipts.beginWindow(0, 100, 1_000)
        BackgroundDeliveryReceipts.record("unrelated.fixture", texts, 50, 1_050, 1_050)
        assertTrue(runCatching { BackgroundDeliveryReceipts.finishWindow(100, 1_100) }.isFailure)
    }

    /** Catch-up after the closed background window cannot qualify in-window delivery. */
    @Test
    fun lateCallbacksCannotCompleteBurst() {
        BackgroundDeliveryReceipts.beginWindow(0, 100, 1_000)
        BackgroundDeliveryReceipts.record(FIXTURE_PACKAGE, texts, 101, 1_101, 1_101)
        assertTrue(runCatching { BackgroundDeliveryReceipts.finishWindow(100, 1_100) }.isFailure)
    }

    /** An existing matching card must be replaced with a new disposable fixture before measurement. */
    @Test
    fun setupReceiptsRejectReusedFixture() {
        BackgroundDeliveryReceipts.record(FIXTURE_PACKAGE, texts, 0, 1_000, 1_000)
        assertTrue(runCatching { BackgroundDeliveryReceipts.beginWindow(0, 100, 1_000) }.isFailure)
    }

    /** Complete payloads qualify only while the same acknowledged listener is available. */
    @Test
    fun disconnectedListenerCannotQualify() {
        BackgroundDeliveryReceipts.beginWindow(0, 100, 1_000)
        BackgroundDeliveryReceipts.record(FIXTURE_PACKAGE, texts, 50, 1_050, 1_050)
        BackgroundDeliveryReceipts.connected = false
        assertTrue(runCatching { BackgroundDeliveryReceipts.finishWindow(100, 1_100) }.isFailure)
    }

    /** Re-arming changes ownership and discards all receipt indices from the old generation. */
    @Test
    fun replacedGenerationCannotReuseOldReceipts() {
        BackgroundDeliveryReceipts.beginWindow(0, 100, 1_000)
        BackgroundDeliveryReceipts.record(FIXTURE_PACKAGE, texts, 50, 1_050, 1_050)
        BackgroundDeliveryReceipts.arm("replacement.fixture", texts)
        BackgroundDeliveryReceipts.beginWindow(100, 200, 1_100)
        assertTrue(runCatching { BackgroundDeliveryReceipts.finishWindow(100, 1_100) }.isFailure)
    }

    /** All five distinct in-window payloads can complete without retaining their notification identities. */
    @Test
    fun completeMeasuredBurstPasses() {
        BackgroundDeliveryReceipts.beginWindow(0, 100, 1_000)
        texts.forEach { BackgroundDeliveryReceipts.record(FIXTURE_PACKAGE, listOf(it), 50, 1_050, 1_050) }
        BackgroundDeliveryReceipts.finishWindow(100, 1_100)
    }

    /** A setup card queued until after the window opens retains its actual old posting time. */
    @Test
    fun delayedPreWindowCallbackCannotQualify() {
        BackgroundDeliveryReceipts.beginWindow(10, 100, 1_010)
        BackgroundDeliveryReceipts.record(FIXTURE_PACKAGE, texts, 50, 1_009, 1_050)
        assertTrue(runCatching { BackgroundDeliveryReceipts.finishWindow(100, 1_100) }.isFailure)
    }

    /** A clock adjustment cannot relabel a pre-window card as in-window delivery. */
    @Test
    fun wallClockStepInvalidatesReceipts() {
        BackgroundDeliveryReceipts.beginWindow(0, 100, 1_000)
        BackgroundDeliveryReceipts.record(FIXTURE_PACKAGE, texts, 50, 3_000, 3_000)
        assertTrue(runCatching { BackgroundDeliveryReceipts.finishWindow(100, 3_050) }.isFailure)
    }

    private companion object {
        const val FIXTURE_PACKAGE = "disposable.fixture"
    }
}
