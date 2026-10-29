package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import java.io.ByteArrayOutputStream

/** One still-image transport under matched characterization, created fresh for each scenario. */
internal class ThroughputLane(
    val variant: String,
    val binding: MediaWorkerBaselineBinding,
    val transport: MediaDecodeTransport,
    private val closer: java.io.Closeable,
) : java.io.Closeable {
    /** Closes the transport, then waits a bounded time for every observed endpoint's Binder death. */
    override fun close() {
        closer.close()
        val deadline = SystemClock.elapsedRealtime() + DEATH_SETTLE_MS
        while (SystemClock.elapsedRealtime() < deadline && !allEndpointsDead()) SystemClock.sleep(POLL_MS)
        binding.closeObservers()
    }

    /** True when every endpoint this lane connected has a kernel Binder death callback. */
    fun allEndpointsDead(): Boolean {
        val endpoints = binding.snapshot().getJSONArray("endpoints")
        return (0 until endpoints.length()).all { !endpoints.getJSONObject(it).isNull("death_observed_elapsed_ns") }
    }

    companion object {
        private const val DEATH_SETTLE_MS = 6_000L
        private const val POLL_MS = 20L
    }
}

/** Transport variants compared on one APK and device, names are stable report keys. */
internal object ThroughputVariants {
    /** Creates the named lane with a fresh read-only Binder witness and the shared admission observer. */
    fun create(
        variant: String,
        context: Context,
        observer: MediaAdmissionObserver,
    ): ThroughputLane {
        val binding = MediaWorkerBaselineBinding(context)
        return when (variant) {
            // The pre-#3373 production lane: one reusable worker across inputs.
            "reuse" ->
                ReusableMediaTransport(binding, "tp_reuse").let {
                    it.admissionObserver = observer
                    ThroughputLane(variant, binding, it, it)
                }
            // The pre-#3373 qualification candidate: one cold binding generation per input.
            "per_input" ->
                IsolatedMediaTransport(binding, "tp_fresh").let {
                    it.admissionObserver = observer
                    ThroughputLane(variant, binding, it, it)
                }
            else -> error("Unknown throughput variant $variant")
        }
    }
}

/** Synthetic, account-free inputs, distinct bytes per request so no cache can answer. */
internal class ThroughputCorpus private constructor(
    val thumbnails: List<ByteArray>,
    val viewers: List<ByteArray>,
    val mix: List<ByteArray>,
) {
    companion object {
        private const val THUMB_SOURCE_EDGE = 256
        private const val THUMB_COUNT = 160
        private const val VIEWER_WIDTH = 1920
        private const val VIEWER_HEIGHT = 1440
        private const val VIEWER_COUNT = 4
        private const val JPEG_QUALITY = 90

        /** Encodes the fixed corpus, the header records every input digest for matched comparisons. */
        fun create(): ThroughputCorpus {
            val thumbs =
                (0 until THUMB_COUNT).map { index ->
                    gradient(THUMB_SOURCE_EDGE, THUMB_SOURCE_EDGE, index, index % 2 == 0)
                }
            val viewers = (0 until VIEWER_COUNT).map { index -> gradient(VIEWER_WIDTH, VIEWER_HEIGHT, index, false) }
            val svg =
                """<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256">
                <rect width="256" height="256" fill="blue"/></svg>""".toByteArray()
            val mix = listOf(gradient(THUMB_SOURCE_EDGE, THUMB_SOURCE_EDGE, 0, true), gradient(256, 256, 0, false), svg)
            return ThroughputCorpus(thumbs, viewers, mix)
        }

        /** A deterministic gradient with an index stamp, encoded as PNG or JPEG. */
        private fun gradient(
            width: Int,
            height: Int,
            index: Int,
            png: Boolean,
        ): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            return try {
                val row = IntArray(width)
                for (y in 0 until height) {
                    for (x in 0 until width) row[x] = Color.rgb((x + index) % 256, (y + index * 7) % 256, (x + y) % 256)
                    bitmap.setPixels(row, 0, width, 0, y, width, 1)
                }
                val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                ByteArrayOutputStream().also { check(bitmap.compress(format, JPEG_QUALITY, it)) }.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
    }
}
