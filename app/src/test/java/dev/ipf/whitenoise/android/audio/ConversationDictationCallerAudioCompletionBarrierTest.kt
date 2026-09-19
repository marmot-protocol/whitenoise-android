package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDictationCallerAudioCompletionBarrierTest {
    /** Paste or Send must not deliver while the microphone still owns an unsealed final read. */
    @Test
    fun requestedStopWaitsForFeedAndCaptureClosure() {
        val fixture = BarrierFixture()

        fixture.barrier.requireCaptureClosure()
        fixture.deliver()
        fixture.closeFeed()

        assertFalse(fixture.delivered)

        fixture.closeCapture()

        assertTrue(fixture.delivered)
    }

    /** Capture may close first; terminal delivery still waits for the provider feed boundary. */
    @Test
    fun requestedStopWaitsForFeedWhenCaptureClosesFirst() {
        val fixture = BarrierFixture()

        fixture.barrier.requireCaptureClosure()
        fixture.deliver()
        fixture.closeCapture()

        assertFalse(fixture.delivered)

        fixture.closeFeed()

        assertTrue(fixture.delivered)
    }

    /** A stop racing a provider result is observed when the provider feed eventually closes. */
    @Test
    fun stopBetweenProviderResultAndFeedClosureStillWaitsForCapture() {
        val fixture = BarrierFixture()

        fixture.deliver()
        fixture.barrier.requireCaptureClosure()
        fixture.closeFeed()

        assertFalse(fixture.delivered)

        fixture.closeCapture()

        assertTrue(fixture.delivered)
    }

    /** Ordinary segment completion continues as soon as its provider feed closes. */
    @Test
    fun segmentResultWithoutRequestedStopDoesNotWaitForCapture() {
        val fixture = BarrierFixture()

        fixture.deliver()
        fixture.closeFeed()

        assertTrue(fixture.delivered)
    }

    /** A provider error/result pair cannot complete one recognition generation twice. */
    @Test
    fun duplicateTerminalCallbacksDeliverExactlyOnce() {
        val fixture = BarrierFixture()

        fixture.barrier.requireCaptureClosure()
        fixture.deliver()
        fixture.deliver()
        fixture.closeFeed()
        fixture.closeCapture()

        assertTrue(fixture.delivered)
        assertEquals(1, fixture.deliveryCount)
    }

    /** Provider-owned microphone callbacks share the same duplicate fence. */
    @Test
    fun immediateTerminalCallbacksDeliverExactlyOnce() {
        val fixture = BarrierFixture()

        fixture.barrier.deliverImmediately(fixture::recordDelivery)
        fixture.barrier.deliverImmediately(fixture::recordDelivery)

        assertEquals(1, fixture.deliveryCount)
    }

    /** Exactly-once state belongs to one generation and cannot suppress the next generation. */
    @Test
    fun aNewRecognitionGenerationOwnsANewFence() {
        val firstGeneration = BarrierFixture()
        val secondGeneration = BarrierFixture()

        firstGeneration.barrier.deliverImmediately(firstGeneration::recordDelivery)
        firstGeneration.barrier.deliverImmediately(firstGeneration::recordDelivery)
        secondGeneration.barrier.deliverImmediately(secondGeneration::recordDelivery)

        assertEquals(1, firstGeneration.deliveryCount)
        assertEquals(1, secondGeneration.deliveryCount)
    }

    private class BarrierFixture {
        private val feed = ClosureSignal()
        private val capture = ClosureSignal()
        var deliveryCount = 0
            private set
        val delivered: Boolean
            get() = deliveryCount > 0
        val barrier = ConversationDictationCallerAudioCompletionBarrier { action -> action() }

        fun deliver() {
            barrier.deliver(
                onFeedClosed = feed::observe,
                onCaptureClosed = capture::observe,
                delivery = ::recordDelivery,
            )
        }

        fun recordDelivery() {
            deliveryCount += 1
        }

        fun closeFeed() = feed.close()

        fun closeCapture() = capture.close()
    }

    /** Minimal exactly-once closure signal matching the production capture/feed observer contract. */
    private class ClosureSignal {
        private var closed = false
        private val callbacks = mutableListOf<() -> Unit>()

        fun observe(callback: () -> Unit) {
            if (closed) callback() else callbacks += callback
        }

        fun close() {
            if (closed) return
            closed = true
            callbacks.toList().also { callbacks.clear() }.forEach { it() }
        }
    }
}
