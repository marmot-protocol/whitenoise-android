package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Source capabilities and bounded provider staging, shared by codec and platform-service adapters. */
internal class MediaSourceAccess(
    private val context: Context,
) : java.io.Closeable {
    @Volatile private var closed = false
    private val sourceTimer =
        lazy {
            java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor()
        }
    private val sourceWatchdog by sourceTimer
    private val activeSource =
        AtomicReference<AtomicReference<AutoCloseable?>?>()
    private val activeCancellation =
        AtomicReference<CancellationSignal?>()

    @Suppress("ReturnCount") // Refuse expired or retired provider reads before holding scarce staging admission.
    fun <T> withProviderDeadline(block: (CancellationSignal, AtomicReference<AutoCloseable?>) -> T?): T? {
        if (closed ||
            !providerAdmission.tryAcquire(MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS)
        ) {
            return null
        }
        if (closed) {
            providerAdmission.release()
            return null
        }
        val signal = CancellationSignal()
        val source = AtomicReference<AutoCloseable?>()
        activeSource.set(source)
        activeCancellation.set(signal)
        var timer: java.util.concurrent.ScheduledFuture<*>? = null
        return try {
            timer =
                sourceWatchdog.schedule({
                    runCatching { signal.cancel() }
                    runCatching { source.get()?.close() }
                }, MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS)
            signal.throwIfCanceled()
            if (closed) null else block(signal, source)
        } finally {
            timer?.cancel(false)
            activeCancellation.compareAndSet(signal, null)
            activeSource.compareAndSet(source, null)
            providerAdmission.release()
        }
    }

    fun <T> withSelectedMediaInput(
        input: ParcelFileDescriptor,
        offset: Long,
        length: Long,
        block: (ParcelFileDescriptor) -> T?,
    ): T? =
        if (offset == 0L && length == input.statSize) {
            block(input) // Whole selected files keep the zero-copy video path.
        } else {
            withStagedInput({ writer -> copySelectedMediaSlice(input.fileDescriptor, offset, length, writer) }, block)
        }

    fun <T> withUriInput(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        block: (ParcelFileDescriptor) -> T?,
    ): T? {
        val staged =
            withProviderDeadline { signal, opened ->
                resolver.openAssetFileDescriptor(uri, "r", signal)?.use { asset ->
                    opened.set(asset)
                    asset.createInputStream().use { source ->
                        withStagedInput({ writer -> copyBoundedSource(source, writer) }) { input ->
                            ParcelFileDescriptor.dup(input.fileDescriptor)
                        }
                    }
                }
            }
        // Decoder admission/IPC owns the staged capability without occupying provider admission.
        return staged?.use(block)
    }

    fun <T> withMediaUriInput(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        block: (ParcelFileDescriptor) -> T?,
    ): T? {
        if (uri.scheme != "file" && uri.scheme != "content") return null
        val selected =
            withProviderDeadline { signal, opened ->
                resolver.openAssetFileDescriptor(uri, "r", signal)?.use { asset ->
                    opened.set(asset)
                    val size = asset.parcelFileDescriptor.statSize
                    val length = if (asset.declaredLength < 0L) size - asset.startOffset else asset.declaredLength
                    if (length > MAX_MEDIA_SOURCE_BYTES || !validMediaRange(size, asset.startOffset, length)) {
                        return@use null
                    }
                    signal.throwIfCanceled()
                    ParcelFileDescriptor.dup(asset.fileDescriptor).use { input ->
                        withSelectedMediaInput(input, asset.startOffset, length) { slice ->
                            ParcelFileDescriptor.dup(slice.fileDescriptor)
                        }
                    }
                }
            }
        // Parsing owns a separate capability; a stuck media service must not hold image-provider admission.
        return selected?.use(block)
    }

    private fun copyBoundedSource(
        source: java.io.InputStream,
        writer: java.io.OutputStream,
    ): Boolean {
        val chunk = ByteArray(MediaDecodePolicy.STAGING_CHUNK_BYTES)
        var total = 0L
        var count = source.read(chunk)
        val deadline = android.os.SystemClock.elapsedRealtime() + MediaDecodePolicy.DEADLINE_MS
        while (count > 0) {
            total += count
            if (total > MediaDecodePolicy.MAX_INPUT_BYTES ||
                android.os.SystemClock.elapsedRealtime() > deadline
            ) {
                return false
            }
            writer.write(chunk, 0, count)
            count = source.read(chunk)
        }
        return count < 0 && total > 0L
    }

    fun <T> withInput(
        bytes: ByteArray,
        block: (ParcelFileDescriptor) -> T,
    ): T? =
        withStagedInput({ writer ->
            writer.write(bytes)
            true
        }, block)

    private fun <T> withStagedInput(
        write: (java.io.OutputStream) -> Boolean,
        block: (ParcelFileDescriptor) -> T?,
    ): T? {
        val directory = File(context.cacheDir, "media-decoder-input").apply { mkdirs() }
        val file = File.createTempFile("source-", ".bin", directory)
        return try {
            file.outputStream().use { writer ->
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                    check(file.delete()) // No plaintext pathname survives, even if the app dies during staging.
                    if (write(writer)) {
                        writer.flush()
                        block(input)
                    } else {
                        null
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    override fun close() {
        closed = true
        if (sourceTimer.isInitialized()) {
            val signal = activeCancellation.get()
            val source = activeSource.get()
            sourceWatchdog.execute {
                runCatching { signal?.cancel() }
                runCatching { source?.get()?.close() }
                sourceWatchdog.shutdown()
            }
        }
    }

    companion object {
        private val providerAdmission = Semaphore(1)
    }
}
