package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class MediaRefusalCacheTest {
    @Test
    fun throwingParserIsThrottledPerRevisionAndHealthyOtherSourcesStillWork() {
        var time = 1L
        var calls = 0
        val cache = MediaRefusalCache { time }
        repeat(2) {
            assertNull(
                cache.attempt<Int>("bad", {}) {
                    calls++
                    throw IllegalArgumentException("synthetic malformed fixture")
                },
            )
        }
        assertEquals(1, calls)
        assertEquals(7, cache.attempt("healthy", {}) { 7 })
        time += MediaDecodePolicy.REFUSAL_TTL_MS
        assertEquals(8, cache.attempt("bad", {}) { 8 })
    }

    @Test
    fun retiredCallsDoNotQuarantineAHealthySource() {
        var retired = false
        val cache = MediaRefusalCache { 1L }
        try {
            cache.attempt<Int>("source", { if (retired) throw CancellationException() }) {
                retired = true
                null
            }
            throw AssertionError("Retired work must not publish or quarantine")
        } catch (_: CancellationException) {
            assertEquals(9, cache.attempt("source", {}) { 9 })
        }
    }
}
