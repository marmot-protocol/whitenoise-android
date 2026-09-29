package dev.ipf.whitenoise.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaProbeAggregateTest {
    @Test
    fun reportsNearestRankAndVerifiedBytesWithoutDynamicLabels() {
        val samples =
            (1..20).map { index ->
                MediaProbeSample(index.toDouble(), 65_536L, index * 100L, index * 200L)
            }

        assertEquals(
            "{\"schema\":1,\"operation\":\"download\",\"size\":\"small\"," +
                "\"payload_bytes_per_sample\":65536,\"samples\":20,\"successes\":20," +
                "\"failures\":0,\"payload_bytes_total\":1310720,\"network_bytes\":null," +
                "\"duration_ms\":{\"p50\":10.000,\"p95\":19.000,\"max\":20.000}," +
                "\"peak_heap_bytes\":{\"java\":2000,\"native\":4000}}",
            mediaProbeAggregateJson(MediaProbeOperation.DOWNLOAD, MediaProbeSize.SMALL, samples, 0),
        )
    }

    @Test
    fun failureOnlyReportDoesNotInventLatencyOrNetworkBytes() {
        assertEquals(
            "{\"schema\":1,\"operation\":\"upload\",\"size\":\"near_limit\"," +
                "\"payload_bytes_per_sample\":31457280,\"samples\":1,\"successes\":0," +
                "\"failures\":1,\"payload_bytes_total\":0,\"network_bytes\":null," +
                "\"duration_ms\":{\"p50\":null,\"p95\":null,\"max\":null}," +
                "\"peak_heap_bytes\":{\"java\":0,\"native\":0}}",
            mediaProbeAggregateJson(MediaProbeOperation.UPLOAD, MediaProbeSize.NEAR_LIMIT, emptyList(), 1),
        )
    }

    @Test
    fun rejectsNonFiniteAndNegativeInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaProbeSample(Double.NaN, 1L, 0L, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            mediaProbeAggregateJson(MediaProbeOperation.DOWNLOAD, MediaProbeSize.SMALL, emptyList(), -1)
        }
    }
}
