package dev.ipf.whitenoise.android.perf3439

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import org.json.JSONArray
import org.json.JSONObject

/** Bounded diagnostic-only timing snapshots; no per-frame I/O or application data. */
object Perf3439FrameMetrics {
    private const val CAPACITY = 5000
    private val lock = Any()
    private val thread = HandlerThread("WN-FrameMetrics").apply { start() }
    private val handler = Handler(thread.looper)
    private val rows = ArrayList<Row>()
    private val windows = ArrayList<WindowRecord>()
    private var nextWindow = 0
    private var overflow = 0
    private val metrics = listOf(
        "unknown_delay_ns" to FrameMetrics.UNKNOWN_DELAY_DURATION,
        "input_ns" to FrameMetrics.INPUT_HANDLING_DURATION,
        "animation_ns" to FrameMetrics.ANIMATION_DURATION,
        "layout_measure_ns" to FrameMetrics.LAYOUT_MEASURE_DURATION,
        "draw_ns" to FrameMetrics.DRAW_DURATION,
        "sync_ns" to FrameMetrics.SYNC_DURATION,
        "command_issue_ns" to FrameMetrics.COMMAND_ISSUE_DURATION,
        "swap_buffers_ns" to FrameMetrics.SWAP_BUFFERS_DURATION,
        "total_ns" to FrameMetrics.TOTAL_DURATION,
        "first_draw" to FrameMetrics.FIRST_DRAW_FRAME,
        "intended_vsync_ns" to FrameMetrics.INTENDED_VSYNC_TIMESTAMP,
        "vsync_ns" to FrameMetrics.VSYNC_TIMESTAMP,
        "gpu_ns" to FrameMetrics.GPU_DURATION,
        "deadline_ns" to FrameMetrics.DEADLINE,
    )

    private data class Row(
        val window: Int,
        val metrics: FrameMetrics,
        val dropped: Int,
        val callbackBootBeforeNs: Long,
        val callbackMonoNs: Long,
        val callbackBootAfterNs: Long,
    )

    private data class WindowRecord(
        val id: Int,
        val attachedBootNs: Long,
        var detachedBootNs: Long? = null,
    )

    /** Attach before the new dialog's first draw, without changing entry-frame admission. */
    fun attach(window: Window): () -> Unit {
        val id = synchronized(lock) {
            check(windows.size < 256) { "Bounded diagnostic window capacity reached" }
            (++nextWindow).also { windows.add(WindowRecord(it, SystemClock.elapsedRealtimeNanos())) }
        }
        val listener = Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
            val before = SystemClock.elapsedRealtimeNanos()
            val mono = System.nanoTime()
            val after = SystemClock.elapsedRealtimeNanos()
            val copy = FrameMetrics(frame)
            synchronized(lock) {
                if (rows.size < CAPACITY) rows.add(Row(id, copy, dropped, before, mono, after))
                else overflow++
            }
        }
        window.addOnFrameMetricsAvailableListener(listener, handler)
        return {
            window.removeOnFrameMetricsAvailableListener(listener)
            synchronized(lock) {
                windows.first { it.id == id }.detachedBootNs = SystemClock.elapsedRealtimeNanos()
            }
        }
    }

    /** Serialize only after capture; frame callbacks merely copy into the bounded memory store. */
    fun snapshot(offset: Int, requested: Int): String {
        val frames: List<Row>
        val knownWindows: List<WindowRecord>
        val count: Int
        val lost: Int
        synchronized(lock) {
            count = rows.size
            lost = overflow
            frames = rows.drop(offset.coerceAtLeast(0)).take(requested.coerceIn(1, 8))
            knownWindows = windows.map { it.copy() }
        }
        val values = JSONArray()
        frames.forEach { row ->
            val value = JSONObject()
                .put("window", row.window)
                .put("dropped_callbacks", row.dropped)
                .put("callback_boot_before_ns", row.callbackBootBeforeNs)
                .put("callback_mono_ns", row.callbackMonoNs)
                .put("callback_boot_after_ns", row.callbackBootAfterNs)
            metrics.forEach { (name, key) -> value.put(name, row.metrics.getMetric(key)) }
            value.put("frame_timeline_vsync_id", if (Build.VERSION.SDK_INT >= 36) row.metrics.getMetric(FrameMetrics.FRAME_TIMELINE_VSYNC_ID) else -1L)
            values.put(value)
        }
        val windowValues = JSONArray()
        knownWindows.forEach { window ->
            windowValues.put(JSONObject().put("id", window.id).put("attached_boot_ns", window.attachedBootNs).put("detached_boot_ns", window.detachedBootNs ?: JSONObject.NULL))
        }
        return JSONObject()
            .put("diagnostic_only", true)
            .put("pid", android.os.Process.myPid())
            .put("sdk", Build.VERSION.SDK_INT)
            .put("total", count)
            .put("overflow", lost)
            .put("offset", offset)
            .put("windows", windowValues)
            .put("frames", values)
            .toString()
    }
}

/** Shell-DUMP-protected export of anonymous fixture timing snapshots after a capture. */
class Perf3439FrameMetricsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != context.packageName + ".PERF3439_FRAME_METRICS") return
        resultCode = 0
        resultData = Perf3439FrameMetrics.snapshot(intent.getIntExtra("offset", 0), intent.getIntExtra("limit", 8))
    }
}
