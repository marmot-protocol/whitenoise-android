package dev.ipf.whitenoise.android.share

import android.net.Uri
import android.os.CancellationSignal
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.concurrent.atomic.AtomicReference

private const val IMPORT_PROGRESS_INTERVAL_MS = 100L
private const val SHARE_IMAGE_HEADER_BYTES = 12

internal class ShareSizeExceeded(
    val fileLimited: Boolean,
) : IOException()

private data class ShareSourceBudget(
    val bytes: Long,
    val fileLimited: Boolean,
)

internal class EmptyShareSource : IllegalArgumentException()

internal class ShareSourceUnavailable(
    cause: IOException? = null,
) : IOException(cause)

internal data class CopiedShareSource(
    val name: String,
    val mime: String?,
    val size: Long,
)

/** Android provider cancellation and actual streamed-byte enforcement, independently of durable intake ownership. */
internal class ShareSourceReader(
    private val metadata: (Uri, CancellationSignal) -> ShareSourceMetadata,
    private val open: (Uri, CancellationSignal) -> InputStream?,
    private val sourceLimit: (ShareSourceMetadata, ByteArray) -> Long = { _, _ -> PRIVATE_SHARE_MAX_BYTES },
) {
    suspend fun copy(
        source: Uri,
        file: File,
        remaining: Long,
        progress: (Long, Long?) -> Unit,
    ): CopiedShareSource =
        coroutineScope {
            val input = AtomicReference<InputStream?>(null)
            val signal = CancellationSignal()
            val closer =
                launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { signal.cancel() }
                        runCatching { input.get()?.close() }
                    }
                }
            try {
                val info = providerRead { metadata(source, signal) }
                val name = sanitizeShareFilename(info.name) ?: "file"
                val opened = providerRead { open(source, signal) } ?: throw ShareSourceUnavailable()
                val stream = PushbackInputStream(opened, SHARE_IMAGE_HEADER_BYTES)
                input.set(stream)
                currentCoroutineContext().ensureActive()
                val fileLimit = sourceLimit(info, readHeader(stream, remaining))
                val budget = ShareSourceBudget(minOf(remaining, fileLimit), fileLimit <= remaining)
                val hint = info.size?.takeIf { it > 0 && it <= budget.bytes }
                val size = stream.use { copyStream(it, file, budget, hint, progress) }
                currentCoroutineContext().ensureActive()
                if (size == 0L) throw EmptyShareSource()
                progress(size, size)
                CopiedShareSource(name, info.mime, size)
            } finally {
                closer.cancel()
                runCatching { input.get()?.close() }
            }
        }

    private suspend fun readHeader(
        stream: PushbackInputStream,
        remaining: Long,
    ): ByteArray {
        val header = ByteArray(minOf(SHARE_IMAGE_HEADER_BYTES.toLong(), remaining + 1).toInt())
        var size = 0
        while (size < header.size) {
            currentCoroutineContext().ensureActive()
            val read = providerRead { stream.read(header, size, header.size - size) }
            if (read < 0) break
            size += read
        }
        stream.unread(header, 0, size)
        return header.copyOf(size)
    }

    private suspend fun copyStream(
        stream: InputStream,
        file: File,
        budget: ShareSourceBudget,
        hint: Long?,
        progress: (Long, Long?) -> Unit,
    ): Long =
        file.outputStream().use { output ->
            var size = 0L
            var lastProgressAt = 0L
            val buffer = ByteArray(16 * 1024)
            progress(0, hint)
            var ended = false
            while (!ended) {
                currentCoroutineContext().ensureActive()
                val limit = minOf(buffer.size.toLong(), budget.bytes - size + 1).toInt()
                val read = providerRead { stream.read(buffer, 0, limit) }
                ended = read == -1
                if (read > 0) {
                    size += read
                    if (size > budget.bytes) throw ShareSizeExceeded(budget.fileLimited)
                    output.write(buffer, 0, read)
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastProgressAt >= IMPORT_PROGRESS_INTERVAL_MS) {
                    progress(size, hint)
                    lastProgressAt = now
                }
            }
            output.fd.sync()
            size
        }

    private fun <T> providerRead(block: () -> T): T =
        try {
            block()
        } catch (failure: IOException) {
            throw ShareSourceUnavailable(failure)
        }
}
