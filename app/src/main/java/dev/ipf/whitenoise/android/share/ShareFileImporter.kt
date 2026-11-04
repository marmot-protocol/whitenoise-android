package dev.ipf.whitenoise.android.share

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import dev.ipf.whitenoise.android.state.stagedDiagnosticExportSource
import dev.ipf.whitenoise.android.ui.conversation.media.normalizeDocumentMime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

private const val MAX_SHARE_FILENAME_CHARS = 120

enum class ShareImportError {
    Unreadable,
    Scheme,
    Empty,
    FileTooLarge,
    BatchTooLarge,
    TooMany,
    Metadata,
    Storage,
    Interrupted,
}

internal data class ShareImportProgress(
    val item: Int,
    val count: Int,
    val bytes: Long,
    val total: Long?,
)

internal data class ShareSourceMetadata(
    val name: String?,
    val mime: String?,
    val size: Long?,
)

/**
 * A local file the app itself produced, admitted in place of an otherwise rejected app-owned provider
 * URI. [open] reads the file directly, and the importer keeps the copy only when its length and
 * SHA-256 equal [size] and [sha256].
 */
internal class TrustedShareSource(
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: ByteArray,
    val open: () -> InputStream,
    /** Called once the share request retains the verified copy, so the producer can retire its handle. */
    val onAdmitted: () -> Unit = {},
) {
    /** Fails the import unless the private copy holds exactly the bytes the producer recorded. */
    fun verify(copy: java.io.File) {
        if (copy.length() != size || !MessageDigest.isEqual(sha256Of(copy), sha256)) throw ShareSourceUnavailable()
    }
}

/** Copies untrusted Android streams once. No sender, relay, encryption or upload dependency. */
internal class ShareFileImporter(
    private val files: PrivateShareFiles,
    private val metadata: (Uri, CancellationSignal) -> ShareSourceMetadata,
    private val open: (Uri, CancellationSignal) -> InputStream?,
    private val isAppOwnedProvider: (Uri) -> Boolean = { false },
    private val timeoutMs: Long? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val trustedSource: (Uri) -> TrustedShareSource? = { null },
) {
    constructor(context: Context) : this(
        PrivateShareFiles(context),
        { uri, signal -> readShareSourceMetadata(context, uri, signal) },
        { uri, signal -> context.contentResolver.openAssetFileDescriptor(uri, "r", signal)?.createInputStream() },
        { uri ->
            val provider = context.packageManager.resolveContentProvider(uri.authority.orEmpty(), 0)
            provider?.applicationInfo?.uid == context.applicationInfo.uid
        },
        trustedSource = { uri -> stagedDiagnosticExportSource(context, uri) },
    )

    /**
     * Imports one request on I/O within item/byte/deadline limits, retaining completed sources under request
     * ownership.
     */
    suspend fun import(
        request: ShareRequest,
        progress: (ShareImportProgress) -> Unit = {},
    ): ShareRequest =
        withContext(ioDispatcher) {
            if (request.payload.importReady) return@withContext request
            files.cleanStale()
            files.recoverIncomplete()
            files.leases.releasePendingRequests()
            val sources = request.payload.streamUris.distinct()
            val batch = ImportedBatch()
            if (sources.size > SHARE_STREAM_MAX_ITEMS) batch.errors += ShareImportError.TooMany
            var retained = false
            try {
                withTimeout(timeoutMs ?: files.limits.importTimeoutMs()) {
                    importSources(sources, request.payload.intentMimeType, batch, progress, request.requestId)
                }
                files.leases.holdRequest(request.requestId, batch.accepted)
                retained = true
                request.copy(
                    payload =
                        request.payload.copy(
                            streamUris = batch.accepted,
                            importReady = true,
                            importErrors = batch.errors,
                            importRejectedCount = sources.size - batch.accepted.size,
                            importImageLimitBytes = files.limits.imageBytes,
                            importDocumentLimitBytes = files.limits.documentBytes,
                            importBatchLimitBytes = files.limits.batchCiphertextBytes,
                        ),
                )
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                files.leases.holdRequest(request.requestId, batch.accepted)
                retained = true
                request.copy(
                    payload =
                        request.payload.copy(
                            streamUris = batch.accepted,
                            importReady = true,
                            importErrors = batch.errors + ShareImportError.Interrupted,
                            importRejectedCount = sources.size - batch.accepted.size,
                            importImageLimitBytes = files.limits.imageBytes,
                            importDocumentLimitBytes = files.limits.documentBytes,
                            importBatchLimitBytes = files.limits.batchCiphertextBytes,
                        ),
                )
            } finally {
                if (retained) batch.onRetained.forEach { it() } else batch.accepted.forEach(files::delete)
            }
        }

    /**
     * Copies distinct admitted inputs in pick order and records each partial original before reading untrusted
     * bytes.
     */
    private suspend fun importSources(
        sources: List<Uri>,
        intentMime: String?,
        batch: ImportedBatch,
        progress: (ShareImportProgress) -> Unit,
        requestId: String,
    ) {
        sources.take(SHARE_STREAM_MAX_ITEMS).forEachIndexed { index, uri ->
            currentCoroutineContext().ensureActive()
            // Only a URI the rejection rule refuses is offered to the producer-owned lookup, so the
            // ordinary external path is unchanged and a forged self-provider URI stays rejected.
            val rejected = isRejectedSource(uri)
            val trusted = if (rejected) trustedSource(uri) else null
            val result =
                if (rejected && trusted == null) {
                    ImportedFile(error = ShareImportError.Scheme)
                } else {
                    importOne(
                        uri,
                        intentMime,
                        files.limits.remainingPlaintext(batch.used),
                        progress = { bytes, total ->
                            progress(
                                ShareImportProgress(
                                    index + 1,
                                    sources.size.coerceAtMost(SHARE_STREAM_MAX_ITEMS),
                                    bytes,
                                    total,
                                ),
                            )
                        },
                        onStaged = { files.leases.holdRequest(requestId, batch.accepted + it) },
                        trusted = trusted,
                    )
                }
            batch.add(result)
        }
    }

    /**
     * Rejects non-content or app-owned providers before any metadata/read call, including user-prefixed
     * authorities.
     */
    private fun isRejectedSource(uri: Uri): Boolean {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return true
        // ContentResolver strips Android's user-id prefix before choosing a provider.
        // Normalize that lookup here too, while retaining the original URI for external grants.
        val provider = uri.buildUpon().authority(uri.authority.orEmpty().substringAfterLast('@')).build()
        return files.owns(provider) || isAppOwnedProvider(provider)
    }

    private class ImportedBatch {
        val accepted = mutableListOf<Uri>()
        val errors = mutableListOf<ShareImportError>()
        val onRetained = mutableListOf<() -> Unit>()
        var used = 0L

        /**
         * Accounts only completed copies; rejected inputs contribute errors without consuming another owner's
         * bytes.
         */
        fun add(result: ImportedFile) {
            val uri = result.uri
            if (uri == null) {
                errors += requireNotNull(result.error)
            } else {
                accepted += uri
                result.onRetained?.let(onRetained::add)
                used += result.size + dev.ipf.whitenoise.android.ui.conversation.media.FILE_BACKED_TAG_BYTES
            }
        }
    }

    /**
     * Copies one bounded stream; cancellation propagates and every incomplete original is removed in finally.
     * A [trusted] source is read from its verified file and kept only when the copy matches its digest.
     */
    @Suppress("LongParameterList") // The trusted source shares every limit and cleanup path of a provider stream.
    private suspend fun importOne(
        source: Uri,
        intentMime: String?,
        remaining: Long,
        progress: (Long, Long?) -> Unit,
        onStaged: (Uri) -> Unit,
        trusted: TrustedShareSource? = null,
    ): ImportedFile {
        var staged: Uri? = null
        var copyLimit = remaining
        return try {
            if (remaining <= 0) return ImportedFile(error = ShareImportError.BatchTooLarge)
            val (uri, file) = files.newFile()
            staged = uri
            onStaged(uri)
            copyLimit = minOf(remaining, files.availableBytes())
            val copied = readerFor(trusted, intentMime).copy(source, file, copyLimit, progress)
            trusted?.verify(file)
            files.finish(uri, copied.name, resolveShareMime(copied.mime, intentMime), copied.size)
            staged = null
            ImportedFile(uri, copied.size, onRetained = trusted?.onAdmitted)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: EmptyShareSource) {
            importFailure(ShareImportError.Empty)
        } catch (_: SecurityException) {
            importFailure(ShareImportError.Unreadable)
        } catch (failure: IOException) {
            val error =
                when (failure) {
                    is ShareSizeExceeded ->
                        when {
                            failure.fileLimited -> ShareImportError.FileTooLarge
                            copyLimit < remaining -> ShareImportError.Storage
                            else -> ShareImportError.BatchTooLarge
                        }
                    is ShareSourceUnavailable -> ShareImportError.Unreadable
                    else -> ShareImportError.Storage
                }
            importFailure(error)
        } catch (_: Exception) {
            importFailure(ShareImportError.Metadata)
        } finally {
            staged?.let(files::delete)
        }
    }

    /** Reads a [trusted] source from its own file, or any other source through the provider callbacks. */
    private fun readerFor(
        trusted: TrustedShareSource?,
        intentMime: String?,
    ): ShareSourceReader {
        val limit = { info: ShareSourceMetadata, header: ByteArray -> sourceLimit(info, header, intentMime) }
        return if (trusted == null) {
            ShareSourceReader(metadata, open, limit)
        } else {
            ShareSourceReader(
                { _, _ -> ShareSourceMetadata(trusted.name, trusted.mime, trusted.size) },
                { _, _ -> trusted.open() },
                limit,
            )
        }
    }

    /**
     * Returns a typed rejection while preserving a caller's cancellation rather than disguising it as storage
     * failure.
     */
    private suspend fun importFailure(error: ShareImportError): ImportedFile {
        currentCoroutineContext().ensureActive()
        return ImportedFile(error = error)
    }

    /** Applies the image or document budget to metadata/signature-confirmed content before accepting streamed bytes. */
    private fun sourceLimit(
        info: ShareSourceMetadata,
        header: ByteArray,
        intentMime: String?,
    ): Long {
        val isImage =
            resolveShareMime(info.mime, intentMime).startsWith("image/") ||
                dev.ipf.whitenoise.android.media.MediaPipeline
                    .sniffImageMediaType(header) != null
        return if (isImage) files.limits.imageBytes else files.limits.documentBytes
    }

    private data class ImportedFile(
        val uri: Uri? = null,
        val size: Long = 0,
        val error: ShareImportError? = null,
        /** Runs only once the request retains this copy, so a cancelled batch leaves its producer handle bound. */
        val onRetained: (() -> Unit)? = null,
    )
}

/**
 * Preserves safe extensions while bounding names and neutralizing path/control characters; unusable names return
 * null.
 */
internal fun sanitizeShareFilename(name: String?): String? {
    val safe =
        (name ?: "file")
            .map { char ->
                when {
                    char == '/' || char == '\\' -> '_'
                    char.isISOControl() || Character.getType(char) == Character.FORMAT.toInt() -> '_'
                    else -> char
                }
            }.joinToString("")
            .trim()
            .trim('.')
    if (safe.isBlank()) return null
    val dot = safe.lastIndexOf('.')
    val extension = if (dot in 1 until safe.lastIndex && safe.length - dot <= 16) safe.substring(dot) else ""
    val stem = if (extension.isEmpty()) safe else safe.dropLast(extension.length)
    return stem.take(MAX_SHARE_FILENAME_CHARS - extension.length) + extension
}

/** Reads optional provider name/type/length under the same cancellation signal as the subsequent copy. */
private fun readShareSourceMetadata(
    context: Context,
    uri: Uri,
    signal: CancellationSignal,
): ShareSourceMetadata {
    var name: String? = null
    var size: Long? = null
    context.contentResolver
        .query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
            signal,
        )?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !it.isNull(nameIndex)) name = it.getString(nameIndex)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }
    return ShareSourceMetadata(name, context.contentResolver.getType(uri), size)
}

/** Chooses a concrete normalized provider type, falling back to the existing document MIME policy for wildcards. */
internal fun resolveShareMime(
    providerMime: String?,
    intentMime: String?,
): String {
    val provider =
        providerMime
            .orEmpty()
            .substringBefore(';')
            .trim()
            .lowercase(java.util.Locale.ROOT)
    val concrete = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
    return if (concrete.matches(provider)) provider else normalizeDocumentMime(intentMime)
}

/** Streams [file] through SHA-256 without holding it in memory. */
private fun sha256Of(file: java.io.File): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest()
}
