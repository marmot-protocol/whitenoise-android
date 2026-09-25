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
    val updateMs: Long?,
    val layoutMs: Long?,
    val drawMs: Long?,
    val presentMs: Long?,
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
        updateMs = nanosToMillis(inputNanos, animationNanos),
        layoutMs = nanosToMillis(layoutNanos),
        drawMs = nanosToMillis(drawNanos, syncNanos, commandIssueNanos),
        presentMs = nanosToMillis(swapBuffersNanos),
    )

/** Sums only complete framework stages and preserves sub-millisecond work as zero. */
private fun nanosToMillis(vararg nanos: Long): Long? =
    nanos
        .takeIf { it.all { bucket -> bucket >= 0L } }
        ?.sum()
        ?.div(NANOS_PER_MILLISECOND)

/**
 * Fences queued frame callbacks to the Activity and first runtime owner that accepted a sample.
 *
 * A cold Activity may start before MDK exists, so owner capture remains lazy. Once captured, a
 * replacement owner is never adopted by this reporter.
 */
internal class FramePerformanceCallbackGuard<Owner : Any>(
    private val captureOwner: () -> Owner?,
    private val isCurrent: (Owner) -> Boolean,
) {
    private var active = true
    private var owner: Owner? = null

    /** Runs one queued callback only while this lifetime and its captured owner remain current. */
    @Synchronized
    fun runIfCurrent(block: (Owner) -> Unit): Boolean {
        val captured = if (active) owner ?: captureOwner()?.also { owner = it } else null
        val shouldRun = captured != null && isCurrent(captured)
        if (shouldRun) block(requireNotNull(captured))
        return shouldRun
    }

    /** Invalidates this Activity lifetime before its reporter begins teardown. */
    @Synchronized
    fun invalidate() {
        active = false
        owner = null
    }
}

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
            sample.updateMs?.let { emit(HostPerformanceOperationFfi.FRAME_UPDATE, it) }
            sample.layoutMs?.let { emit(HostPerformanceOperationFfi.FRAME_LAYOUT, it) }
            sample.drawMs?.let { emit(HostPerformanceOperationFfi.FRAME_DRAW, it) }
            sample.presentMs?.let { emit(HostPerformanceOperationFfi.FRAME_PRESENT, it) }
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
