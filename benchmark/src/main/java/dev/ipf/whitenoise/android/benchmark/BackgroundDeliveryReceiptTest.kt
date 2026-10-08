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
        BackgroundDeliveryReceipts.beginWindow(100)
        assertTrue(runCatching { BackgroundDeliveryReceipts.finishWindow() }.isFailure)
    }

    /** Reposting one enriched card cannot stand in for receiving five different payloads. */
    @Test
    fun repeatedEnrichmentDoesNotCompleteMissingMessages() {
        BackgroundDeliveryReceipts.beginWindow(100)
        repeat(5) { BackgroundDeliveryReceipts.record(FIXTURE_PACKAGE, listOf(texts.first()), 50) }
        assertTrue(runCatching { BackgroundDeliveryReceipts.finishWindow() }.isFailure)
    }

    /** A foreign package cannot contribute a matching body to the fixture's measured receipt set. */
    @Test
    fun anotherPackageCannotCompleteBurst() {
        BackgroundDeliveryReceipts.beginWindow(100)
        BackgroundDeliveryReceipts.record("unrelated.fixture", texts, 50)
        assertTrue(runCatching { BackgroundDeliveryReceipts.finishWindow() }.isFailure)
    }

    /** Catch-up after the closed background window cannot qualify in-window delivery. */
    @Test
    fun lateCallbacksCannotCompleteBurst() {
        BackgroundDeliveryReceipts.beginWindow(100)
        BackgroundDeliveryReceipts.record(FIXTURE_PACKAGE, texts, 101)
        assertTrue(runCatching { BackgroundDeliveryReceipts.finishWindow() }.isFailure)
    }

    /** An existing matching card must be replaced with a new disposable fixture before measurement. */
    @Test
    fun setupReceiptsRejectReusedFixture() {
        BackgroundDeliveryReceipts.record(FIXTURE_PACKAGE, texts, 0)
        assertTrue(runCatching { BackgroundDeliveryReceipts.beginWindow(100) }.isFailure)
    }

    /** Complete payloads qualify only while the same acknowledged listener is available. */
    @Test
    fun disconnectedListenerCannotQualify() {
        BackgroundDeliveryReceipts.beginWindow(100)
        BackgroundDeliveryReceipts.record(FIXTURE_PACKAGE, texts, 50)
        BackgroundDeliveryReceipts.connected = false
        assertTrue(runCatching { BackgroundDeliveryReceipts.finishWindow() }.isFailure)
    }

    /** Re-arming changes ownership and discards all receipt indices from the old generation. */
    @Test
    fun replacedGenerationCannotReuseOldReceipts() {
        BackgroundDeliveryReceipts.beginWindow(100)
        BackgroundDeliveryReceipts.record(FIXTURE_PACKAGE, texts, 50)
        BackgroundDeliveryReceipts.arm("replacement.fixture", texts)
        BackgroundDeliveryReceipts.beginWindow(200)
        assertTrue(runCatching { BackgroundDeliveryReceipts.finishWindow() }.isFailure)
    }

    /** All five distinct in-window payloads can complete without retaining their notification identities. */
    @Test
    fun completeMeasuredBurstPasses() {
        BackgroundDeliveryReceipts.beginWindow(100)
        texts.forEach { BackgroundDeliveryReceipts.record(FIXTURE_PACKAGE, listOf(it), 50) }
        BackgroundDeliveryReceipts.finishWindow()
    }

    private companion object {
        const val FIXTURE_PACKAGE = "disposable.fixture"
    }
}
