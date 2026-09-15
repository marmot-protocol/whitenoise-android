package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/** Captures one native source and permanently invalidates it when its visible owner leaves. */
internal class TranscriptSaveOwner(
    private val current: () -> Boolean,
    val export: suspend (File) -> File?,
) {
    private val valid = AtomicBoolean(true)

    /** True while the owner is valid and its screen is current. */
    fun isCurrent(): Boolean = valid.get() && current()

    /** Marks the owner invalid. */
    fun invalidate() {
        valid.set(false)
    }
}

/** Keeps a picker result associated with its original owner until that result is consumed. */
internal class TranscriptSaveRequest(
    val owner: TranscriptSaveOwner,
) {
    var claimed = false
        private set

    /** Claims the request once; later claims return false. */
    fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }
}

/** One picker/write at a time, including an invalidated request still awaiting the external result. */
internal class TranscriptSaveRequests {
    private var pending by mutableStateOf<TranscriptSaveRequest?>(null)
    val busy: Boolean get() = pending != null

    /** Starts a save request for a current owner when none is pending. */
    fun begin(owner: TranscriptSaveOwner): TranscriptSaveRequest? {
        if (busy || !owner.isCurrent()) return null
        return TranscriptSaveRequest(owner).also { pending = it }
    }

    /** Claims the pending request for an accepted result of a still-current owner. */
    fun claimResult(accepted: Boolean): TranscriptSaveRequest? {
        val request = pending?.takeIf { it.claim() } ?: return null
        if (!accepted || !request.owner.isCurrent()) {
            finish(request)
        }
        return request.takeIf { pending === it }
    }

    /** Clears the pending request once it finished. */
    fun finish(request: TranscriptSaveRequest) {
        if (pending === request) pending = null
    }
}

/**
 * Copies the accepted native export off main and removes its private plaintext directory on every exit.
 * Exporter and provider failures retain their original cause while cleanup runs.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun saveConversationTranscript(
    cacheDir: File,
    owner: TranscriptSaveOwner,
    openOutput: () -> OutputStream?,
    discardOutput: () -> Unit,
) = withContext(Dispatchers.IO) {
    var temporaryDirectory: File? = null
    var originalFailure: Throwable? = null
    try {
        ensureTranscriptSaveOwner(owner)
        val directory = Files.createTempDirectory(cacheDir.toPath(), "transcript-save-").toFile()
        temporaryDirectory = directory
        val file = owner.export(directory) ?: throw IOException("The conversation transcript is unavailable")
        ensureTranscriptSaveOwner(owner)
        val output = openOutput() ?: throw IOException("The document provider did not open an output stream")
        output.use { destination ->
            file.inputStream().use { source ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    ensureTranscriptSaveOwner(owner)
                    val count = source.read(buffer)
                    if (count < 0) break
                    ensureTranscriptSaveOwner(owner)
                    destination.write(buffer, 0, count)
                }
            }
            destination.flush()
        }
        ensureTranscriptSaveOwner(owner)
    } catch (failure: Throwable) {
        originalFailure = failure
        try {
            discardOutput()
        } catch (cleanupFailure: Exception) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    } finally {
        cleanupTranscriptTemporaryDirectory(temporaryDirectory, originalFailure)
    }
}

/** Checks both coroutine cancellation and the account/runtime/controller fence before emitting plaintext. */
private suspend fun ensureTranscriptSaveOwner(owner: TranscriptSaveOwner) {
    currentCoroutineContext().ensureActive()
    if (!owner.isCurrent()) throw CancellationException("Transcript save owner changed")
}

/** Reports failed plaintext removal while keeping the original write failure or cancellation as the primary cause. */
@Suppress("TooGenericExceptionCaught")
internal fun cleanupTranscriptTemporaryDirectory(
    directory: File?,
    originalFailure: Throwable?,
    deleteDirectory: (File) -> Boolean = { it.deleteRecursively() },
) {
    if (directory == null) return
    try {
        if (!deleteDirectory(directory)) throw IOException("Could not remove the temporary transcript directory")
    } catch (cleanupFailure: Exception) {
        if (originalFailure == null) throw cleanupFailure
        originalFailure.addSuppressed(cleanupFailure)
    }
}
