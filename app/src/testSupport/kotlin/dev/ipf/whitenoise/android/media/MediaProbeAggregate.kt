package dev.ipf.whitenoise.android.media

import java.util.Locale
import kotlin.math.ceil

private const val KIB = 1024
private const val MIB = KIB * KIB
private const val SMALL_BYTES = 64 * KIB
private const val MEDIUM_BYTES = MIB
private const val LARGE_BYTES = 8 * MIB
private const val NEAR_LIMIT_BYTES = 30 * MIB
private const val SMALL_SAMPLES = 20
private const val MEDIUM_SAMPLES = 5
private const val LARGE_SAMPLES = 3
private const val NEAR_LIMIT_SAMPLES = 1

/** Closed labels for synthetic media measurements; no fixture identity enters the report. */
internal enum class MediaProbeOperation(
    val wireName: String,
) {
    PREPARATION("preparation"),
    UPLOAD("upload"),
    DOWNLOAD("download"),
}

/** Sizes cover the app's common path and an optional case below its 32 MiB send ceiling. */
internal enum class MediaProbeSize(
    val wireName: String,
    val byteCount: Int,
    val repetitions: Int,
) {
    SMALL("small", SMALL_BYTES, SMALL_SAMPLES),
    MEDIUM("medium", MEDIUM_BYTES, MEDIUM_SAMPLES),
    LARGE("large", LARGE_BYTES, LARGE_SAMPLES),
    NEAR_LIMIT("near_limit", NEAR_LIMIT_BYTES, NEAR_LIMIT_SAMPLES),
}

/** One successful operation's monotonic duration, verified payload and observed heap peaks. */
internal data class MediaProbeSample(
    val durationMs: Double,
    val payloadBytes: Long,
    val peakJavaBytes: Long,
    val peakNativeBytes: Long,
) {
    init {
        require(durationMs.isFinite() && durationMs >= 0.0)
        require(payloadBytes >= 0L && peakJavaBytes >= 0L && peakNativeBytes >= 0L)
    }
}

/** Emits aggregate numeric results with fixed keys and enum labels for machine comparison. */
internal fun mediaProbeAggregateJson(
    operation: MediaProbeOperation,
    size: MediaProbeSize,
    successes: List<MediaProbeSample>,
    failures: Int,
): String {
    require(failures >= 0)
    val ordered = successes.map(MediaProbeSample::durationMs).sorted()
    val p50 = ordered.nearestRank(0.50)
    val p95 = ordered.nearestRank(0.95)
    val maximum = ordered.lastOrNull()
    val totalBytes = successes.sumOf(MediaProbeSample::payloadBytes)
    val peakJava = successes.maxOfOrNull(MediaProbeSample::peakJavaBytes) ?: 0L
    val peakNative = successes.maxOfOrNull(MediaProbeSample::peakNativeBytes) ?: 0L
    return buildString {
        append("{\"schema\":1,\"operation\":\"")
        append(operation.wireName)
        append("\",\"size\":\"")
        append(size.wireName)
        append("\",\"payload_bytes_per_sample\":")
        append(size.byteCount)
        append(",\"samples\":")
        append(successes.size + failures)
        append(",\"successes\":")
        append(successes.size)
        append(",\"failures\":")
        append(failures)
        append(",\"payload_bytes_total\":")
        append(totalBytes)
        append(",\"network_bytes\":null,\"duration_ms\":{\"p50\":")
        append(p50.jsonNumber())
        append(",\"p95\":")
        append(p95.jsonNumber())
        append(",\"max\":")
        append(maximum.jsonNumber())
        append("},\"peak_heap_bytes\":{\"java\":")
        append(peakJava)
        append(",\"native\":")
        append(peakNative)
        append("}}")
    }
}

/** Nearest-rank percentile; null is explicit when no transfer completed. */
private fun List<Double>.nearestRank(fraction: Double): Double? {
    if (isEmpty()) return null
    return get(ceil(size * fraction).toInt() - 1)
}

private fun Double?.jsonNumber(): String = this?.let { String.format(Locale.US, "%.3f", it) } ?: "null"
