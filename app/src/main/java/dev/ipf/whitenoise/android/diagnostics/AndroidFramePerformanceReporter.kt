package dev.ipf.whitenoise.android.diagnostics

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import java.util.concurrent.atomic.AtomicInteger

private const val NANOS_PER_MILLISECOND = 1_000_000L

/** One Android framework frame sample translated into MDK's shared frame boundaries. */
internal data class AndroidFramePerformanceSample(
    val updateMs: Long,
    val layoutMs: Long,
    val drawMs: Long,
    val presentMs: Long,
)

/** Converts framework nanosecond buckets into the four platform-neutral frame stages. */
internal fun FrameMetrics.toHostPerformanceSample(): AndroidFramePerformanceSample =
    androidFramePerformanceSample(
        inputNanos = getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
        animationNanos = getMetric(FrameMetrics.ANIMATION_DURATION),
        layoutNanos = getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
        drawNanos = getMetric(FrameMetrics.DRAW_DURATION),
        syncNanos = getMetric(FrameMetrics.SYNC_DURATION),
        commandIssueNanos = getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
        swapBuffersNanos = getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
    )

/** Combines Android's detailed frame buckets into MDK's four shared host stages. */
internal fun androidFramePerformanceSample(
    inputNanos: Long,
    animationNanos: Long,
    layoutNanos: Long,
    drawNanos: Long,
    syncNanos: Long,
    commandIssueNanos: Long,
    swapBuffersNanos: Long,
): AndroidFramePerformanceSample =
    AndroidFramePerformanceSample(
        updateMs =
            nanosToMillis(
                inputNanos.coerceAtLeast(0L) + animationNanos.coerceAtLeast(0L),
            ),
        layoutMs = nanosToMillis(layoutNanos),
        drawMs =
            nanosToMillis(
                drawNanos.coerceAtLeast(0L) +
                    syncNanos.coerceAtLeast(0L) +
                    commandIssueNanos.coerceAtLeast(0L),
            ),
        presentMs = nanosToMillis(swapBuffersNanos),
    )

/** Preserves sub-millisecond work as zero while saturating invalid framework values. */
private fun nanosToMillis(nanos: Long): Long = nanos.coerceAtLeast(0L) / NANOS_PER_MILLISECOND

/**
 * Samples framework-owned frame phases off the UI thread.
 *
 * Recording every frame would add four FFI calls per frame. One sample per 60 frames keeps the
 * registry representative while bounding collection overhead to roughly four calls per second at
 * 60 Hz. The initial frame is sampled so short sessions still produce evidence.
 */
internal class AndroidFramePerformanceReporter(
    private val window: Window,
    private val emit: (HostPerformanceOperationFfi, Long) -> Unit,
    private val sampleEveryFrames: Int = DEFAULT_SAMPLE_EVERY_FRAMES,
) : AutoCloseable {
    private val frameCount = AtomicInteger(0)
    private val thread = HandlerThread("wn-frame-performance")
    private var attached = false
    private val listener =
        Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val index = frameCount.getAndIncrement()
            if (index % sampleEveryFrames != 0) return@OnFrameMetricsAvailableListener
            val sample = metrics.toHostPerformanceSample()
            emit(HostPerformanceOperationFfi.FRAME_UPDATE, sample.updateMs)
            emit(HostPerformanceOperationFfi.FRAME_LAYOUT, sample.layoutMs)
            emit(HostPerformanceOperationFfi.FRAME_DRAW, sample.drawMs)
            emit(HostPerformanceOperationFfi.FRAME_PRESENT, sample.presentMs)
        }

    init {
        require(sampleEveryFrames > 0)
    }

    /** Attaches the process-local listener once. */
    fun start() {
        if (attached) return
        attached = true
        thread.start()
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
    }

    /** Removes the listener and terminates its private callback thread. */
    override fun close() {
        if (!attached) return
        attached = false
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
    }

    private companion object {
        const val DEFAULT_SAMPLE_EVERY_FRAMES = 60
    }
}
