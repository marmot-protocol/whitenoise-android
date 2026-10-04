package dev.ipf.whitenoise.android.share

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import dev.ipf.whitenoise.android.ui.conversation.media.normalizeDocumentMime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream

private const val IMPORT_TIMEOUT_MS = 60_000L
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

/** Copies untrusted Android streams once. No sender, relay, encryption or upload dependency. */
internal class ShareFileImporter(
    private val files: PrivateShareFiles,
    private val metadata: (Uri, CancellationSignal) -> ShareSourceMetadata,
    private val open: (Uri, CancellationSignal) -> InputStream?,
    private val isAppOwnedProvider: (Uri) -> Boolean = { false },
    private val timeoutMs: Long = IMPORT_TIMEOUT_MS,
) {
    constructor(context: Context) : this(
        PrivateShareFiles(context),
        { uri, signal -> readShareSourceMetadata(context, uri, signal) },
        { uri, signal -> context.contentResolver.openAssetFileDescriptor(uri, "r", signal)?.createInputStream() },
        { uri ->
            val provider = context.packageManager.resolveContentProvider(uri.authority.orEmpty(), 0)
            provider?.applicationInfo?.uid == context.applicationInfo.uid
        },
    )

    suspend fun import(
        request: ShareRequest,
        progress: (ShareImportProgress) -> Unit = {},
    ): ShareRequest =
        withContext(Dispatchers.IO) {
            if (request.payload.importReady) return@withContext request
            files.cleanStale()
            files.recoverIncomplete()
            files.leases.releasePendingRequests()
            val sources = request.payload.streamUris.distinct()
            val batch = ImportedBatch()
            if (sources.size > SHARE_STREAM_MAX_ITEMS) batch.errors += ShareImportError.TooMany
            var retained = false
            try {
                withTimeout(timeoutMs) {
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
                        ),
                )
            } finally {
                if (!retained) batch.accepted.forEach(files::delete)
            }
        }

    private suspend fun importSources(
        sources: List<Uri>,
        intentMime: String?,
        batch: ImportedBatch,
        progress: (ShareImportProgress) -> Unit,
        requestId: String,
    ) {
        sources.take(SHARE_STREAM_MAX_ITEMS).forEachIndexed { index, uri ->
            currentCoroutineContext().ensureActive()
            val result =
                if (isRejectedSource(uri)) {
                    ImportedFile(error = ShareImportError.Scheme)
                } else {
                    importOne(
                        uri,
                        intentMime,
                        minOf(PRIVATE_SHARE_MAX_BYTES, PRIVATE_SHARE_BATCH_MAX_BYTES - batch.used),
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
                    )
                }
            batch.add(result)
        }
    }

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
        var used = 0L

        fun add(result: ImportedFile) {
            val uri = result.uri
            if (uri == null) {
                errors += requireNotNull(result.error)
            } else {
                accepted += uri
                used += result.size
            }
        }
    }

    private suspend fun importOne(
        source: Uri,
        intentMime: String?,
        remaining: Long,
        progress: (Long, Long?) -> Unit,
        onStaged: (Uri) -> Unit,
    ): ImportedFile {
        var staged: Uri? = null
        var copyLimit = remaining
        return try {
            if (remaining <= 0) return ImportedFile(error = ShareImportError.BatchTooLarge)
            val (uri, file) = files.newFile()
            staged = uri
            onStaged(uri)
            copyLimit = minOf(remaining, files.availableBytes())
            val reader = ShareSourceReader(metadata, open) { info, header -> sourceLimit(info, header, intentMime) }
            val copied = reader.copy(source, file, copyLimit, progress)
            files.finish(uri, copied.name, resolveShareMime(copied.mime, intentMime), copied.size)
            staged = null
            ImportedFile(uri, copied.size)
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

    private suspend fun importFailure(error: ShareImportError): ImportedFile {
        currentCoroutineContext().ensureActive()
        return ImportedFile(error = error)
    }

    private fun sourceLimit(
        info: ShareSourceMetadata,
        header: ByteArray,
        intentMime: String?,
    ): Long {
        val isImage =
            resolveShareMime(info.mime, intentMime).startsWith("image/") ||
                dev.ipf.whitenoise.android.media.MediaPipeline
                    .sniffImageMediaType(header) != null
        return if (isImage) PRIVATE_SHARE_MAX_BYTES else PRIVATE_SHARE_DOCUMENT_MAX_BYTES
    }

    private data class ImportedFile(
        val uri: Uri? = null,
        val size: Long = 0,
        val error: ShareImportError? = null,
    )
}

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
