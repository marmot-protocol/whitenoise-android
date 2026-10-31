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
    private val resources: MediaParentResources? = null,
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
        val timerCredit = resources?.task()
        return try {
            timer =
                registerTimeout {
                    if (timerCredit == null || timerCredit.start()) {
                        try {
                            runCatching { signal.cancel() }
                            runCatching { source.get()?.close() }
                        } finally {
                            timerCredit?.finish()
                        }
                    }
                }
            if (timer == null) return null
            signal.throwIfCanceled()
            if (closed) null else block(signal, source)
        } catch (failure: java.io.IOException) {
            // Retirement may close a provider capability while its admitted read is still unwinding.
            if (closed) null else throw failure
        } finally {
            timer?.cancel(false)
            timerCredit?.cancelBeforeStart()
            activeCancellation.compareAndSet(signal, null)
            activeSource.compareAndSet(source, null)
            providerAdmission.release()
        }
    }

    private fun registerTimeout(block: () -> Unit): java.util.concurrent.ScheduledFuture<*>? {
        if (resources == null) {
            return sourceWatchdog.schedule(Runnable(block), MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS)
        }
        // The AV close transition and lazy executor creation/registration share this monitor.
        // No lock is held over provider work or cancellation RPCs.
        return synchronized(this) {
            if (closed) {
                null
            } else {
                sourceWatchdog.schedule(
                    Runnable(block),
                    MediaDecodePolicy.DEADLINE_MS,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    fun <T> withMediaFileInput(
        file: File,
        block: (ParcelFileDescriptor) -> T?,
    ): T? {
        if (closed || !file.isFile || file.length() !in 1..MAX_MEDIA_SOURCE_BYTES) return null
        return own(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { input ->
            if (closed || input.value.statSize !in 1..MAX_MEDIA_SOURCE_BYTES) null else block(input.value)
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
                resolver.openAssetFileDescriptor(uri, "r", signal)?.let(::own)?.use { owner ->
                    opened.set(owner)
                    val asset = owner.value
                    // A provider can return after retirement despite cancellation.
                    if (closed || signal.isCanceled) {
                        return@use null
                    }
                    own(asset.createInputStream()).use { sourceOwner ->
                        val source = sourceOwner.value
                        withStagedInput({ writer -> copyBoundedSource(source, writer) }) { input ->
                            own(ParcelFileDescriptor.dup(input.fileDescriptor))
                        }
                    }
                }
            }
        // Decoder admission/IPC owns the staged capability without occupying provider admission.
        return staged?.use { block(it.value) }
    }

    fun <T> withMediaUriInput(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        block: (ParcelFileDescriptor) -> T?,
    ): T? {
        if (uri.scheme != "file" && uri.scheme != "content") return null
        val selected =
            withProviderDeadline { signal, opened ->
                resolver.openAssetFileDescriptor(uri, "r", signal)?.let(::own)?.use { owner ->
                    opened.set(owner)
                    val asset = owner.value
                    if (closed || signal.isCanceled) {
                        return@use null
                    }
                    val size = asset.parcelFileDescriptor.statSize
                    val length = if (asset.declaredLength < 0L) size - asset.startOffset else asset.declaredLength
                    if (length > MAX_MEDIA_SOURCE_BYTES || !validMediaRange(size, asset.startOffset, length)) {
                        return@use null
                    }
                    signal.throwIfCanceled()
                    own(ParcelFileDescriptor.dup(asset.fileDescriptor)).use { inputOwner ->
                        withSelectedMediaInput(inputOwner.value, asset.startOffset, length) { slice ->
                            own(ParcelFileDescriptor.dup(slice.fileDescriptor))
                        }
                    }
                }
            }
        // Parsing owns a separate capability; a stuck media service must not hold image-provider admission.
        return selected?.use { block(it.value) }
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
            own(file.outputStream()).use { writerOwner ->
                val writer = writerOwner.value
                own(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { inputOwner ->
                    val input = inputOwner.value
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

    private fun <T : AutoCloseable> own(value: T): MediaParentResource<T> {
        val resource = resources?.own(value) ?: MediaParentResource(value)
        return resource
    }

    @Synchronized
    override fun close() {
        if (resources != null && closed) return
        closed = true
        if (sourceTimer.isInitialized()) {
            val signal = activeCancellation.get()
            val source = activeSource.get()
            val credit = resources?.task()
            try {
                sourceWatchdog.execute {
                    if (credit == null || credit.start()) {
                        try {
                            runCatching { signal?.cancel() }
                            runCatching { source?.get()?.close() }
                            sourceWatchdog.shutdown()
                        } finally {
                            credit?.finish()
                        }
                    }
                }
            } catch (failure: RuntimeException) {
                resources?.uncertain()
                credit?.cancelBeforeStart()
                throw failure
            }
        }
    }

    companion object {
        private val providerAdmission = Semaphore(1)
    }
}
